package dev.ramenwine.runtime

import java.io.IOException
import java.io.InputStream
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val OUTPUT_DRAIN_MILLIS = 500L
private const val OUTPUT_CLOSE_WAIT_MILLIS = 250L
private const val OUTPUT_BATCH_INTERVAL_MILLIS = 150L
private const val OUTPUT_BATCH_MAX_LINES = 128
private const val OUTPUT_CHANNEL_CAPACITY = 1_024
private const val SETUP_HEARTBEAT_INTERVAL_MILLIS = 2_000L
private const val GAME_HEARTBEAT_INTERVAL_MILLIS = 5_000L
private const val SETUP_COMMAND_TIMEOUT_MILLIS = 5 * 60 * 1000L
private const val SETUP_COMMAND_STOP_WAIT_MILLIS = 1_500L
private const val SETUP_COMMAND_FORCE_STOP_WAIT_MILLIS = 1_500L
private const val PROCESS_TREE_SCAN_INTERVAL_MILLIS = 250L

// Keep the pre/post-spawn /proc snapshot atomic across concurrent sessions.
private val PROCESS_LAUNCH_LOCK = Any()

/**
 * Drain the child's pipe on IO and forward bounded batches to the callback dispatcher.
 * Lifecycle markers flush immediately; high-volume loader output is coalesced.
 */
private fun CoroutineScope.launchBatchedOutputReader(
    input: InputStream,
    callbackDispatcher: CoroutineDispatcher,
    onOutput: (String) -> Unit
): Job = launch(Dispatchers.IO) {
    val channel = Channel<String>(capacity = OUTPUT_CHANNEL_CAPACITY)
    val readerJob = launch(Dispatchers.IO) {
        try {
            input.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    channel.send(line)
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // The child may close its pipe during shutdown.
        } finally {
            channel.close()
        }
    }

    val batch = ArrayList<String>(OUTPUT_BATCH_MAX_LINES)
    suspend fun flushBatch() {
        if (batch.isEmpty()) return
        val payload = batch.joinToString("\n")
        batch.clear()
        try {
            withContext(callbackDispatcher) { onOutput(payload) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // A UI/log consumer failure must never stop draining the child pipe.
        }
    }

    try {
        while (true) {
            val received = withTimeoutOrNull(OUTPUT_BATCH_INTERVAL_MILLIS) {
                channel.receiveCatching()
            }
            if (received == null) {
                flushBatch()
                continue
            }
            if (received.isClosed) {
                flushBatch()
                break
            }
            val line = received.getOrNull() ?: continue
            batch += line
            if (batch.size >= OUTPUT_BATCH_MAX_LINES || line.startsWith("RamenWine:")) {
                flushBatch()
            }
        }
    } finally {
        channel.cancel()
        readerJob.cancelAndJoin()
    }
}

/**
 * Starts a previously validated PRoot command without a shell.
 *
 * Output is merged (stdout + stderr), coalesced off-thread and delivered to the
 * callback dispatcher in bounded batches. Lifecycle markers flush immediately.
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
        val appPid = ProcessTreeTerminator.currentProcessPid()
        val (process, prootPid) = synchronized(PROCESS_LAUNCH_LOCK) {
            val existingChildren = appPid?.let { ProcessTreeTerminator.directChildPids(it) }.orEmpty()
            val startedProcess = createProcess(command)
            val childPid = if (startedProcess.isAlive && appPid != null) {
                ProcessTreeTerminator.findNewDirectChildPid(
                    parentPid = appPid,
                    executable = command.prootExecutable,
                    existingPids = existingChildren
                )
            } else {
                null
            }
            startedProcess to childPid
        }
        val processTree = prootPid?.let(::ProcessTreeTerminator)
        processTree?.captureDescendants()
        val startedAtNanos = System.nanoTime()
        suspend fun emitDiagnostic(message: String) {
            withContext(callbackDispatcher) { onOutput(message) }
        }
        val outputJob = launchBatchedOutputReader(
            input = process.inputStream,
            callbackDispatcher = callbackDispatcher,
            onOutput = onOutput
        )

        try {
            emitDiagnostic(
                "RamenWine: setup PRoot started app_pid=${appPid ?: "unknown"} " +
                    "proot_pid=${prootPid ?: "unavailable"} guest=${command.guestExecutablePath} " +
                    "prefix=${command.guestEnvironment["WINEPREFIX"].orEmpty()}"
            )
            emitDiagnostic(
                "RamenWine: setup config BOX64_LOG=${command.guestEnvironment["BOX64_LOG"].orEmpty()} " +
                    "BOX64_DLSYM_ERROR=${command.guestEnvironment["BOX64_DLSYM_ERROR"].orEmpty()} " +
                    "WINEDEBUG=${command.guestEnvironment["WINEDEBUG"].orEmpty()} " +
                    "BOX64_LD_LIBRARY_PATH=${command.guestEnvironment["BOX64_LD_LIBRARY_PATH"].orEmpty()}"
            )
            var completed = false
            while (!completed) {
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
                val remainingMillis = timeoutMillis - elapsedMillis
                if (remainingMillis <= 0L) break
                completed = process.waitFor(
                    minOf(SETUP_HEARTBEAT_INTERVAL_MILLIS, remainingMillis),
                    TimeUnit.MILLISECONDS
                )
                if (!completed && process.isAlive) {
                    val tree = processTree?.diagnosticSnapshot() ?: "<PRoot PID unavailable>"
                    emitDiagnostic(
                        "RamenWine: setup heartbeat guest=${command.guestExecutablePath} " +
                            "elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)} " +
                            "app_pid=${appPid ?: "unknown"} proot_pid=${prootPid ?: "unavailable"} " +
                            "proot_alive=${process.isAlive} process_tree=$tree " +
                            "prefix_files=${prefixDiagnosticSnapshot(command.hostPrefixDirectory)} " +
                            "memory=${hostMemoryDiagnosticSnapshot()}"
                    )
                }
            }
            if (!completed) {
                val tree = processTree?.diagnosticSnapshot() ?: "<PRoot PID unavailable>"
                emitDiagnostic(
                    "RamenWine: setup timeout proot_pid=${prootPid ?: "unavailable"} " +
                        "alive=${process.isAlive} " +
                        "elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)} " +
                        "process_tree=$tree prefix_files=${prefixDiagnosticSnapshot(command.hostPrefixDirectory)}"
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
                "RamenWine: setup PRoot exit app_pid=${appPid ?: "unknown"} " +
                    "proot_pid=${prootPid ?: "unavailable"} code=$exitCode " +
                    "elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)}"
            )
            processTree?.let { tree ->
                emitDiagnostic("RamenWine: setup process tree at exit: ${tree.diagnosticSnapshot()}")
            }
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
            // wineboot/wineserver can leave descendants behind when PRoot is
            // terminated by a timeout. Clean only processes observed beneath this
            // exact PRoot PID, with PID start-time checks guarding against reuse.
            processTree?.let { tree ->
                runCatching { tree.terminateRemaining() }
                runCatching {
                    emitDiagnostic(
                        "RamenWine: setup process tree after cleanup: ${tree.diagnosticSnapshot()}"
                    )
                }
            }
            runCatching { process.inputStream.close() }
            outputJob.cancel()
            throw error
        }
    }

    private fun prefixDiagnosticSnapshot(prefix: File): String {
        val registry = listOf("system.reg", "user.reg", "userdef.reg").joinToString(",") { name ->
            val file = File(prefix, name)
            "$name=${file.isFile && file.length() > 0L}:${file.length()}"
        }
        val entries = prefix.list()?.size?.toString() ?: "unreadable"
        val marker = File(prefix, ".ramenwine-prefix")
        return "entries=$entries,marker=${marker.isFile},registry=[$registry]"
    }

    private fun hostMemoryDiagnosticSnapshot(): String {
        val available = runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemAvailable:") }?.substringAfter(':')?.trim()
            }
        }.getOrNull() ?: "unavailable"
        val appRss = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()
            }
        }.getOrNull() ?: "unavailable"
        return "MemAvailable=$available,app_VmRSS=$appRss"
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
        val (process, prootPid) = synchronized(PROCESS_LAUNCH_LOCK) {
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
            startedProcess to rootPid
        }
        val processTree = prootPid?.let(::ProcessTreeTerminator)
        runCatching {
            withContext(callbackDispatcher) {
                onOutput(
                    "RamenWine: game PRoot started app_pid=${appPid} " +
                        "proot_pid=${prootPid ?: "unavailable"} " +
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
        outputJob = scope.launchBatchedOutputReader(
            input = process.inputStream,
            callbackDispatcher = callbackDispatcher,
            onOutput = onOutput
        )

        // Capture the process tree while PRoot is alive. Some Wine/game children
        // may outlive a failed or interrupted PRoot shutdown and become reparented.
        processTree?.captureDescendants()
        processTreeMonitor = scope.launch {
            var nextHeartbeatAtNanos = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(GAME_HEARTBEAT_INTERVAL_MILLIS)
            val startedAtNanos = System.nanoTime()
            while (process.isAlive) {
                processTree?.captureDescendants()
                val now = System.nanoTime()
                if (processTree != null && now >= nextHeartbeatAtNanos) {
                    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(now - startedAtNanos)
                    val tree = processTree.diagnosticSnapshot()
                    try {
                        withContext(callbackDispatcher) {
                            onOutput(
                                "RamenWine: game heartbeat elapsed_ms=$elapsedMillis " +
                                    "process_alive=${process.isAlive} process_tree=$tree"
                            )
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // A diagnostic callback failure must not stop process monitoring.
                    }
                    nextHeartbeatAtNanos = now +
                        TimeUnit.MILLISECONDS.toNanos(GAME_HEARTBEAT_INTERVAL_MILLIS)
                }
                delay(PROCESS_TREE_SCAN_INTERVAL_MILLIS)
            }
            processTree?.captureDescendants()
            processTree?.let { tree ->
                try {
                    withContext(callbackDispatcher) {
                        onOutput("RamenWine: process tree at exit: ${tree.diagnosticSnapshot()}")
                    }
                } catch (_: Exception) {
                    // Exit reporting must not mask the process exit callback.
                }
            }
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
