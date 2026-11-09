package dev.ramenwine.runtime

import android.content.Context
import android.os.Process
import android.view.Surface
import dev.ramenwine.display.GameDisplayBackend
import dev.ramenwine.display.GameDisplaySession
import dev.ramenwine.library.ImportedGame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    // Wawona JNI shutdown may wait for detached clients and must never run on
    // the Android main thread from the process-exit callback.
    private val displayCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefixInitializer = WinePrefixInitializer(
        filesDirectory = appContext.filesDir,
        processSupervisor = processSupervisor
    )

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
            // Never launch the game in an empty or partially-created prefix.
            // The initializer is a no-op once both Wine registry files exist.
            prefixInitializer.ensureInitialized(request, onOutput)

            val command = withContext(Dispatchers.IO) {
                ProotWineCommandBuilder.build(request)
            }
            val processSession = processSupervisor.start(
                command = command,
                onOutput = onOutput,
                onExit = { exitCode ->
                    // Process callbacks default to Main.immediate. Move Wawona's
                    // potentially blocking native teardown off that thread, then
                    // preserve the public callback's main-thread delivery.
                    displayCleanupScope.launch {
                        displayLease.closeOnIo()
                        withContext(Dispatchers.Main.immediate) {
                            onExit(exitCode)
                        }
                    }
                }
            )
            return GameLaunchSession(processSession, displayLease)
        } catch (error: Throwable) {
            displayLease.closeOnIo()
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

    /** The active Wawona session is exposed only so the SurfaceView can bind its input lease. */
    val displaySession: GameDisplaySession
        get() = displayLease.displaySession

    val displayCleanupError: Throwable?
        get() = displayLease.closeFailure

    fun resize(widthPx: Int, heightPx: Int) {
        require(widthPx > 0 && heightPx > 0) { "Game surface dimensions must be positive." }
        displayLease.resize(widthPx, heightPx)
    }

    suspend fun stop(gracePeriodMillis: Long = 1_500L): Int {
        return try {
            processSession.stop(gracePeriodMillis)
        } finally {
            // Always release the Android surface/compositor, even if the
            // process cannot be stopped or the caller is being cancelled.
            withContext(NonCancellable) {
                displayLease.closeOnIo()
            }
        }
    }
}

internal class DisplaySessionLease(
    private val session: GameDisplaySession
) {
    private var closed = false

    @Volatile
    private var failure: Throwable? = null

    val displaySession: GameDisplaySession
        get() = session

    val closeFailure: Throwable?
        get() = failure

    @Synchronized
    fun resize(widthPx: Int, heightPx: Int) {
        check(!closed) { "The game display session is already closed." }
        session.resize(widthPx, heightPx)
    }

    /**
     * Native compositor teardown can block while it stops clients and releases
     * Vulkan state. Keep that work off the caller's thread (especially Main).
     */
    suspend fun closeOnIo() {
        withContext(Dispatchers.IO + NonCancellable) {
            close()
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { session.close() }
            .onFailure { failure = it }
    }
}
