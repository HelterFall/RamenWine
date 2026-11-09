package dev.ramenwine.runtime

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Starts a previously validated PRoot command without a shell.
 *
 * Output is merged (stdout + stderr) and delivered on the callback dispatcher.
 * This is a process boundary only; an Android display backend must already be
 * running and its Wayland socket must be supplied to ProotWineCommandBuilder.
 */
class ProotWineProcessSupervisor(
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
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

        val process = builder.start()
        ProotWineProcessSession(
            process = process,
            onOutput = onOutput,
            onExit = onExit,
            callbackDispatcher = callbackDispatcher
        )
    }
}

class ProotWineProcessSession internal constructor(
    private val process: Process,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit,
    private val callbackDispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finished = AtomicBoolean(false)
    private val processExit = CompletableDeferred<Int>()
    private val callbackCompleted = CompletableDeferred<Unit>()
    private val stopMutex = Mutex()
    private val outputJob: Job

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
                        } catch (_: Exception) {
                            // A UI/log consumer failure must not stop draining
                            // the child pipe and potentially block the process.
                        }
                    }
                }
            } catch (_: Exception) {
                // The process may close its stream while the session is stopping.
            }
        }

        // Keep exactly one waiter for Process.waitFor(). stop() awaits this
        // result instead of racing a second waitFor(timeout) against the watcher.
        scope.launch {
            val exitCode = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                UNKNOWN_EXIT_CODE
            }
            processExit.complete(exitCode)
            completeAfterOutputDrain(exitCode)
        }
    }

    fun isAlive(): Boolean = process.isAlive

    suspend fun stop(gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MILLIS): Int =
        withContext(Dispatchers.IO) {
            require(gracePeriodMillis >= 0L) { "Grace period cannot be negative." }

            // Signal termination only once, even when the Activity and UI both
            // request shutdown at the same time.
            stopMutex.withLock {
                if (process.isAlive) {
                    process.destroy()
                }

                val gracefulExit = withTimeoutOrNull(gracePeriodMillis) {
                    processExit.await()
                }
                val exitCode = gracefulExit ?: run {
                    process.destroyForcibly()
                    withTimeoutOrNull(FORCE_STOP_WAIT_MILLIS) {
                        processExit.await()
                    } ?: throw IOException("PRoot did not exit after forced termination.")
                }

                completeAfterOutputDrain(exitCode)
                exitCode
            }
        }

    private suspend fun completeAfterOutputDrain(exitCode: Int) {
        // Natural exit and stop both wait for EOF before the single callback.
        outputJob.join()
        notifyExit(exitCode)
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
            // stop() and the process watcher both wait until the one callback
            // has actually returned, not merely until one path wins the CAS.
            callbackCompleted.await()
        }
    }

    companion object {
        private const val DEFAULT_GRACE_PERIOD_MILLIS = 1_500L
        private const val FORCE_STOP_WAIT_MILLIS = 1_500L
        const val UNKNOWN_EXIT_CODE = -1
    }
}
