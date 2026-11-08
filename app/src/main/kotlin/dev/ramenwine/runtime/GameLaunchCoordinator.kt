package dev.ramenwine.runtime

import android.content.Context
import android.os.Process
import android.view.Surface
import dev.ramenwine.display.GameDisplayBackend
import dev.ramenwine.display.GameDisplaySession
import dev.ramenwine.library.ImportedGame
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Joins the Android display session and the PRoot process into one game
 * lifetime. A concrete compositor must be supplied by the application.
 */
class GameLaunchCoordinator(
    context: Context,
    private val displayBackend: GameDisplayBackend,
    private val processSupervisor: ProotWineProcessSupervisor = ProotWineProcessSupervisor()
) {
    private val appContext = context.applicationContext

    suspend fun launch(
        game: ImportedGame,
        surface: Surface,
        widthPx: Int,
        heightPx: Int,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit,
        hostUid: Int = Process.myUid(),
        locale: String = "C.UTF-8"
    ): GameLaunchSession {
        require(surface.isValid) { "The Android game surface is not ready." }
        require(widthPx > 0 && heightPx > 0) { "Game surface dimensions must be positive." }
        require(hostUid >= 0) { "Invalid Android UID." }

        // The compositor must own a live socket before the command builder
        // validates it and maps its runtime directory into the PRoot guest.
        val displaySession = displayBackend.start(surface, widthPx, heightPx)
        val displayLease = DisplaySessionLease(displaySession)

        try {
            val request = ProotWineLaunchRequest(
                filesDirectory = appContext.filesDir,
                cacheDirectory = appContext.cacheDir,
                game = game,
                display = displaySession.endpoint,
                hostUid = hostUid,
                locale = locale
            )
            val command = withContext(Dispatchers.IO) {
                ProotWineCommandBuilder.build(request)
            }
            val processSession = processSupervisor.start(
                command = command,
                onOutput = onOutput,
                onExit = { exitCode ->
                    // Do not let compositor cleanup prevent the consumer from
                    // receiving the process exit event.
                    displayLease.close()
                    onExit(exitCode)
                }
            )
            return GameLaunchSession(processSession, displayLease)
        } catch (error: Throwable) {
            displayLease.close()
            throw error
        }
    }
}

class GameLaunchSession internal constructor(
    private val processSession: ProotWineProcessSession,
    private val displayLease: DisplaySessionLease
) {
    val isAlive: Boolean
        get() = processSession.isAlive()

    val displayCleanupError: Throwable?
        get() = displayLease.closeFailure

    fun resize(widthPx: Int, heightPx: Int) {
        require(widthPx > 0 && heightPx > 0) { "Game surface dimensions must be positive." }
        displayLease.resize(widthPx, heightPx)
    }

    suspend fun stop(gracePeriodMillis: Long = 1_500L): Int {
        val exitCode = processSession.stop(gracePeriodMillis)
        displayLease.close()
        return exitCode
    }
}

internal class DisplaySessionLease(
    private val session: GameDisplaySession
) {
    private val closed = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable?>(null)

    val closeFailure: Throwable?
        get() = failure.get()

    fun resize(widthPx: Int, heightPx: Int) {
        check(!closed.get()) { "The game display session is already closed." }
        session.resize(widthPx, heightPx)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { session.close() }.onFailure(failure::set)
    }
}
