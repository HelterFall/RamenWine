package dev.ramenwine.runtime

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val OUTPUT_DRAIN_MILLIS = 500L
private const val OUTPUT_CLOSE_WAIT_MILLIS = 250L
private const val SETUP_COMMAND_TIMEOUT_MILLIS = 5 * 60 * 1000L
private const val SETUP_COMMAND_STOP_WAIT_MILLIS = 1_500L
private const val SETUP_COMMAND_FORCE_STOP_WAIT_MILLIS = 1_500L

/**
 * Starts a previously validated PRoot command without a shell.
 *
 * Output is merged (stdout + stderr) and delivered on the main dispatcher.
 * This is a process boundary only; an Android display backend must already be
 * running and its Wayland socket must be supplied to ProotWineCommandBuilder.
 */
class ProotWineProcessSupervisor {
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
        val process = createProcess(command)
        val outputJob = launch {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        withContext(Dispatchers.Main.immediate) {
                            onOutput(line)
                        }
                    }
                }
            } catch (_: Exception) {
                // The setup process can close its stream during shutdown.
            }
        }

        try {
            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!completed) {
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
            if (process.isAlive) process.destroyForcibly()
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

        val process = createProcess(command)
        ProotWineProcessSession(
            process = process,
            onOutput = onOutput,
            onExit = onExit
        )
    }
}

class ProotWineProcessSession internal constructor(
    private val process: Process,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val finished = AtomicBoolean(false)

    private val outputJob: Job

    init {
        outputJob = scope.launch {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        withContext(Dispatchers.Main.immediate) {
                            onOutput(line)
                        }
                    }
                }
            } catch (_: Exception) {
                // The process may close its stream while the session is stopping.
            }
        }

        scope.launch {
            val exitCode = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                UNKNOWN_EXIT_CODE
            }
            // Drain final output briefly. A descendant may inherit the pipe,
            // so the display/session must not wait forever after PRoot exits.
            val drained = withTimeoutOrNull(OUTPUT_DRAIN_MILLIS) {
                outputJob.join()
                true
            } ?: false
            if (!drained) {
                runCatching { process.inputStream.close() }
                withTimeoutOrNull(OUTPUT_CLOSE_WAIT_MILLIS) { outputJob.join() }
            }
            notifyExit(exitCode)
        }
    }

    fun isAlive(): Boolean = process.isAlive

    suspend fun stop(gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MILLIS): Int =
        withContext(Dispatchers.IO) {
            require(gracePeriodMillis >= 0L) { "Grace period cannot be negative." }

            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(gracePeriodMillis, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(FORCE_STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                }
            }

            val exitCode = runCatching { process.exitValue() }
                .getOrDefault(UNKNOWN_EXIT_CODE)
            notifyExit(exitCode)
            scope.cancel()
            exitCode
        }

    private suspend fun notifyExit(exitCode: Int) {
        if (!finished.compareAndSet(false, true)) return
        withContext(Dispatchers.Main.immediate) {
            onExit(exitCode)
        }
        scope.cancel()
    }

    companion object {
        private const val DEFAULT_GRACE_PERIOD_MILLIS = 1_500L
        private const val FORCE_STOP_WAIT_MILLIS = 1_500L
        const val UNKNOWN_EXIT_CODE = -1
    }
}
