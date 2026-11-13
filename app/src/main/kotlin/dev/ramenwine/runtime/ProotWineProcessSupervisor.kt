package dev.ramenwine.runtime

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val OUTPUT_DRAIN_MILLIS = 500L
private const val OUTPUT_CLOSE_WAIT_MILLIS = 250L
private const val SETUP_COMMAND_TIMEOUT_MILLIS = 5 * 60 * 1000L
private const val SETUP_COMMAND_STOP_WAIT_MILLIS = 1_500L
private const val SETUP_COMMAND_FORCE_STOP_WAIT_MILLIS = 1_500L
private const val PROCESS_TREE_SCAN_INTERVAL_MILLIS = 250L

// Keep the pre/post-spawn /proc snapshot atomic across concurrent sessions.
private val PROCESS_LAUNCH_LOCK = Any()

/**
 * Starts a previously validated PRoot command without a shell.
 *
 * Output is merged (stdout + stderr) and delivered on the main dispatcher.
 * This is a process boundary only; an Android display backend must already be
 * running and its Wayland socket must be supplied to ProotWineCommandBuilder.
 */
class ProotWineProcessSupervisor(
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    /**
     * Runs a short setup command such as wineboot and waits for its full exit.
     * Output is drained concurrently so the child cannot block on a full pipe.
     */
    suspend fun runToCompletion(
        command: ProotWineCommand,
        onOutput: (String) -> Unit,
        timeoutMillis: Long = SETUP_COMMAND_TIMEOUT_MILLIS
    ): Int = withContext(Dispatchers.IO) {
        require(timeoutMillis > 0L) { "Setup timeout must be positive." }
        // One-shot Wine commands use the guest run-wine-command.sh wrapper.
        // It asks wineserver to stop and waits for its lock to be released,
        // allowing registry hives to flush before PRoot itself exits.
        val process = synchronized(PROCESS_LAUNCH_LOCK) { createProcess(command) }
        val startedAtNanos = System.nanoTime()
        val appPid = ProcessTreeTerminator.currentProcessPid()?.toString() ?: "unknown"
        suspend fun emitDiagnostic(message: String) {
            withContext(callbackDispatcher) { onOutput(message) }
        }
        val outputJob = launch {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        try {
                            withContext(callbackDispatcher) {
                                onOutput(line)
                            }
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // A UI/log consumer failure must not stop pipe draining
                            // and block Wine/PRoot on a full output pipe.
                        }
                    }
                }
            } catch (_: Exception) {
                // The setup process can close its stream during shutdown.
            }
        }

        try {
            emitDiagnostic(
                "RamenWine: setup PRoot started pid=${appPid} guest=${command.guestExecutablePath} " +
                    "prefix=${command.guestEnvironment["WINEPREFIX"].orEmpty()}"
            )
            emitDiagnostic(
                "RamenWine: setup config BOX64_LOG=${command.guestEnvironment["BOX64_LOG"].orEmpty()} " +
                    "BOX64_DLSYM_ERROR=${command.guestEnvironment["BOX64_DLSYM_ERROR"].orEmpty()} " +
                    "WINEDEBUG=${command.guestEnvironment["WINEDEBUG"].orEmpty()} " +
                    "BOX64_LD_LIBRARY_PATH=${command.guestEnvironment["BOX64_LD_LIBRARY_PATH"].orEmpty()}"
            )
            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!completed) {
                emitDiagnostic(
                    "RamenWine: setup timeout pid=${appPid} alive=${process.isAlive} " +
                        "elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)}"
                )
                process.destroy()
                if (!process.waitFor(SETUP_COMMAND_STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(SETUP_COMMAND_FORCE_STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                }
                throw IOException(
                    "Wine prefix setup timed out after $timeoutMillis ms. " +
                        "The setup process was stopped; try again after checking the game files."
                )
            }

            val exitCode = process.exitValue()
            emitDiagnostic(
                "RamenWine: setup PRoot exit pid=${appPid} code=$exitCode " +
                    "elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)}"
            )
            val drained = withTimeoutOrNull(OUTPUT_DRAIN_MILLIS) {
                outputJob.join()
                true
            } ?: false
            if (!drained) {
                // A Wine child can inherit PRoot's output pipe. Do not keep the
                // launch UI blocked forever after the supervised process exits.
                runCatching { process.inputStream.close() }
                withTimeoutOrNull(OUTPUT_CLOSE_WAIT_MILLIS) { outputJob.join() }
            }
            exitCode
        } catch (error: Throwable) {
            if (process.isAlive) {
                process.destroyForcibly()
                runCatching { process.waitFor(SETUP_COMMAND_FORCE_STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS) }
            }
            runCatching { process.inputStream.close() }
            outputJob.cancel()
            throw error
        }
    }

    private fun createProcess(command: ProotWineCommand): Process {
        if (!command.prootExecutable.isFile || !command.prootExecutable.canExecute()) {
            throw IOException("The PRoot host executable is missing or not executable.")
        }
        if (!command.hostWorkingDirectory.isDirectory) {
            throw IOException("The PRoot working directory does not exist.")
        }

        val commandLine = buildList {
            add(command.prootExecutable.absolutePath)
            addAll(command.arguments)
        }
        val builder = ProcessBuilder(commandLine)
            .directory(command.hostWorkingDirectory)
            .redirectErrorStream(true)
        val environment = builder.environment()
        command.unsetEnvironment.forEach { environment.remove(it) }
        environment.putAll(command.environment)
        return builder.start()
    }

    suspend fun start(
        command: ProotWineCommand,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit
    ): ProotWineProcessSession = withContext(Dispatchers.IO) {
        if (!command.prootExecutable.isFile || !command.prootExecutable.canExecute()) {
            throw IOException("The PRoot host executable is missing or not executable.")
        }
        if (!command.hostWorkingDirectory.isDirectory) {
            throw IOException("The PRoot working directory does not exist.")
        }

        val parentPid = ProcessTreeTerminator.currentProcessPid()
            ?: throw IOException("Could not read the app PID from /proc/self/status.")
        val appPid = parentPid.toString()
        val (process, processTree) = synchronized(PROCESS_LAUNCH_LOCK) {
            val existingChildren = ProcessTreeTerminator.directChildPids(parentPid)
            val startedProcess = createProcess(command)
            val rootPid = if (startedProcess.isAlive) {
                ProcessTreeTerminator.findNewDirectChildPid(
                    parentPid = parentPid,
                    executable = command.prootExecutable,
                    existingPids = existingChildren
                )
            } else {
                null
            }
            startedProcess to rootPid?.let { ProcessTreeTerminator(it) }
        }
        runCatching {
            withContext(callbackDispatcher) {
                onOutput(
                    "RamenWine: game PRoot started pid=${appPid} " +
                        "guest=${command.guestExecutablePath} " +
                        "wayland=${command.guestEnvironment["WAYLAND_DISPLAY"].orEmpty()} " +
                        "runtime=${command.runtimeVersion}"
                )
            }
        }
        ProotWineProcessSession(
            process = process,
            onOutput = onOutput,
            onExit = onExit,
            callbackDispatcher = callbackDispatcher,
            processTree = processTree
        )
    }
}

class ProotWineProcessSession internal constructor(
    private val process: Process,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit,
    private val callbackDispatcher: CoroutineDispatcher,
    private val processTree: ProcessTreeTerminator? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finished = AtomicBoolean(false)
    private val processExit = CompletableDeferred<Int>()
    private val callbackCompleted = CompletableDeferred<Unit>()
    private val stopMutex = Mutex()
    // Natural process exit and a user-triggered stop can reach completion together.
    // Only one path may drain output and deliver the final callback.
    private val outputDrainMutex = Mutex()
    private val outputJob: Job
    private val processTreeMonitor: Job

    init {
        outputJob = scope.launch {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        try {
                            withContext(callbackDispatcher) {
                                onOutput(line)
                            }
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // Keep draining even if a UI/log callback fails.
                        }
                    }
                }
            } catch (_: Exception) {
                // The process may close its stream while the session is stopping.
            }
        }

        // Capture the process tree while PRoot is alive. Some Wine/game children
        // may outlive a failed or interrupted PRoot shutdown and become reparented.
        processTree?.captureDescendants()
        processTreeMonitor = scope.launch {
            while (process.isAlive) {
                processTree?.captureDescendants()
                delay(PROCESS_TREE_SCAN_INTERVAL_MILLIS)
            }
            processTree?.captureDescendants()
        }

        // Exactly one coroutine observes process termination. Manual stop waits
        // on this deferred instead of racing a second Process.waitFor().
        scope.launch {
            val exitCode = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                UNKNOWN_EXIT_CODE
            }
            withContext(NonCancellable) {
                processTree?.captureDescendants()
                processTree?.terminateRemaining()
                processTreeMonitor.cancelAndJoin()
            }
            processExit.complete(exitCode)
            completeAfterOutputDrain(exitCode)
        }
    }

    fun isAlive(): Boolean = process.isAlive

    suspend fun stop(gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MILLIS): Int =
        withContext(Dispatchers.IO) {
            require(gracePeriodMillis >= 0L) { "Grace period cannot be negative." }

            stopMutex.withLock {
                val exitCode = if (!process.isAlive) {
                    processExit.await()
                } else {
                    process.destroy()
                    withTimeoutOrNull(gracePeriodMillis) {
                        processExit.await()
                    } ?: run {
                        process.destroyForcibly()
                        withTimeoutOrNull(FORCE_STOP_WAIT_MILLIS) {
                            processExit.await()
                        } ?: throw IOException(
                            "PRoot did not exit after graceful and forced termination."
                        )
                    }
                }

                completeAfterOutputDrain(exitCode)
                exitCode
            }
        }

    private suspend fun completeAfterOutputDrain(exitCode: Int) {
        outputDrainMutex.withLock {
            // The process watcher and stop() may both observe the same exit.
            // Reuse the first completion instead of draining/closing the pipe twice.
            if (finished.get()) {
                callbackCompleted.await()
                return@withLock
            }

            val drained = withTimeoutOrNull(OUTPUT_DRAIN_MILLIS) {
                outputJob.join()
                true
            } ?: false

            if (!drained) {
                // Wine descendants can inherit PRoot's output pipe. Do not hold the
                // game screen open forever waiting for an unrelated inherited FD.
                runCatching { process.inputStream.close() }
                val closed = withTimeoutOrNull(OUTPUT_CLOSE_WAIT_MILLIS) {
                    outputJob.join()
                    true
                } ?: false
                if (!closed) outputJob.cancel()
            }

            notifyExit(exitCode)
        }
    }

    private suspend fun notifyExit(exitCode: Int) {
        if (finished.compareAndSet(false, true)) {
            try {
                withContext(callbackDispatcher) {
                    onExit(exitCode)
                }
            } finally {
                callbackCompleted.complete(Unit)
                scope.cancel()
            }
        } else {
            callbackCompleted.await()
        }
    }

    companion object {
        private const val DEFAULT_GRACE_PERIOD_MILLIS = 1_500L
        private const val FORCE_STOP_WAIT_MILLIS = 1_500L
        const val UNKNOWN_EXIT_CODE = -1
    }
}
