package dev.ramenwine.runtime

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val outputJob: Job

    init {
        outputJob = scope.launch {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        withContext(callbackDispatcher) {
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
            completeAfterOutputDrain(exitCode)
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
                    if (!process.waitFor(FORCE_STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                        throw IOException("PRoot did not exit after forced termination.")
                    }
                }
            }

            val exitCode = process.exitValue()
            completeAfterOutputDrain(exitCode)
            exitCode
        }

    private suspend fun completeAfterOutputDrain(exitCode: Int) {
        // The stop() path and the natural-exit watcher can race. Both wait for
        // EOF before the single exit callback is allowed to cancel the reader.
        runCatching { outputJob.join() }
        notifyExit(exitCode)
    }

    private suspend fun notifyExit(exitCode: Int) {
        if (!finished.compareAndSet(false, true)) return
        try {
            withContext(callbackDispatcher) {
                onExit(exitCode)
            }
        } finally {
            scope.cancel()
        }
    }

    companion object {
        private const val DEFAULT_GRACE_PERIOD_MILLIS = 1_500L
        private const val FORCE_STOP_WAIT_MILLIS = 1_500L
        const val UNKNOWN_EXIT_CODE = -1
    }
}
