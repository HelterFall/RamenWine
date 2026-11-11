package dev.ramenwine.runtime

import android.content.Context
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.IOException
import dev.ramenwine.display.DisplayBackend
import dev.ramenwine.display.DisplaySession
import dev.ramenwine.library.ImportedGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the ordered lifecycle of one game:
 *
 * 1. start the in-process display backend;
 * 2. let the UI attach its Android Surface;
 * 3. build the validated PRoot/Wine command;
 * 4. start the process only after the display is ready;
 * 5. stop the display when the process exits.
 *
 * Display lifecycle calls and callbacks are confined to the Android main thread.
 */
class GameSessionCoordinator(
    context: Context,
    private val displayBackend: DisplayBackend,
    private val processSupervisor: ProotWineProcessSupervisor = ProotWineProcessSupervisor()
) {
    private val appContext = context.applicationContext

    private var displaySession: DisplaySession? = null
    private var processSession: ProotWineProcessSession? = null
    private var audioRelaySession: DirectAudioRelaySession? = null
    private var audioRelayDisplay: DisplaySession? = null
    private var launchInProgress = false
    private var displayPreparationInProgress = false

    /**
     * Starts the compositor and returns a session for the UI to attach its
     * SurfaceView. This does not start a game process.
     */
    suspend fun openDisplay(): DisplaySession = withContext(Dispatchers.Main.immediate) {
        checkMainThread()
        check(displaySession == null) { "A display session is already open." }
        check(processSession?.isAlive() != true) { "A game process is still running." }
        check(!displayPreparationInProgress) { "Display preparation is already running." }

        displayPreparationInProgress = true
        try {
            // APK hashing and asset extraction can take noticeable time on a
            // phone. Keep all of that I/O off the UI thread; native init remains
            // on main because Wawona owns Android Surface/Vulkan lifecycle.
            withContext(Dispatchers.IO) {
                displayBackend.prepare()
            }
            checkMainThread()
            displayBackend.start().also { displaySession = it }
        } finally {
            displayPreparationInProgress = false
        }
    }

    /**
     * Starts the selected imported EXE after the caller's SurfaceView is
     * attached. If command construction or process start fails, the display
     * session is closed so native resources are not leaked.
     */
    suspend fun launchGame(
        game: ImportedGame,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit,
        launchDiagnosticWindow: Boolean = false
    ): GameSession = withContext(Dispatchers.Main.immediate) {
        checkMainThread()
        check(!launchInProgress) { "A game launch is already being prepared." }
        check(processSession?.isAlive() != true) { "A game process is already running." }

        val display = requireNotNull(displaySession) {
            "Open the display backend before launching a game."
        }
        check(display.isReadyForClients) {
            "The compositor socket is ready, but the Android game Surface is not attached."
        }

        launchInProgress = true
        try {
            val relay = withContext(Dispatchers.IO) {
                DirectAudioRelaySupervisor(File(appContext.applicationInfo.nativeLibraryDir)).start(
                    display.endpoint.hostRuntimeDirectory
                )
            }
            audioRelaySession = relay
            audioRelayDisplay = display

            val request = ProotWineLaunchRequest(
                filesDirectory = appContext.filesDir,
                cacheDirectory = appContext.cacheDir,
                nativeLibraryDirectory = File(appContext.applicationInfo.nativeLibraryDir),
                game = game,
                display = display.endpoint,
                hostUid = Process.myUid(),
                locale = game.locale
            )
            val command = withContext(Dispatchers.IO) {
                ProotWineCommandBuilder.build(request)
            }

            WinePrefixManager(processSupervisor).ensureInitialized(
                gameCommand = command,
                onOutput = onOutput
            )

            val audioDriverExitCode = processSupervisor.runToCompletion(
                command = ProotWineCommandBuilder.buildAudioDriverSelectionCommand(command),
                onOutput = onOutput,
                timeoutMillis = AUDIO_DRIVER_SETUP_TIMEOUT_MILLIS
            )
            if (audioDriverExitCode != 0) {
                throw IOException(
                    "Wine could not enable the Android audio driver " +
                        "(reg.exe exit code $audioDriverExitCode)."
                )
            }

            val processCommand = if (launchDiagnosticWindow) {
                withContext(Dispatchers.IO) {
                    ProotWineCommandBuilder.buildWin32GdiSmokeCommand(command)
                }
            } else {
                command
            }

            val process = processSupervisor.start(
                command = processCommand,
                onOutput = onOutput,
                onExit = { exitCode ->
                    try {
                        closeDisplayIfCurrent(display)
                    } finally {
                        processSession = null
                        onExit(exitCode)
                    }
                }
            )

            // The process can exit very quickly. In that case its exit callback
            // may already have closed the display before start() returns.
            if (process.isAlive() && displaySession === display) {
                processSession = process
            }

            GameSession(game, process, launchDiagnosticWindow) {
                closeDisplayIfCurrent(display)
            }
        } catch (error: Exception) {
            closeDisplayIfCurrent(display)
            throw error
        } finally {
            launchInProgress = false
        }
    }

    /**
     * Closes an idle display session. A running process must be stopped through
     * GameSession.stop() first, so it cannot be left without a display.
     */
    fun closeDisplay() {
        checkMainThread()
        check(processSession?.isAlive() != true) {
            "Stop the running game session before closing its display."
        }
        val current = displaySession ?: return
        closeDisplayIfCurrent(current)
    }

    private fun closeDisplayIfCurrent(expected: DisplaySession) {
        checkMainThread()
        if (displaySession !== expected) return
        if (audioRelayDisplay === expected) {
            audioRelaySession?.stop()
            audioRelaySession = null
            audioRelayDisplay = null
        }
        displaySession = null
        processSession = null
        // Process exit must still be delivered if native teardown reports an error.
        runCatching { expected.stop() }
    }

    private companion object {
        const val AUDIO_DRIVER_SETUP_TIMEOUT_MILLIS = 30_000L
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Game session display lifecycle must run on the Android main thread."
        }
    }
}

/** A launched game process paired with the display session that hosts it. */
class GameSession internal constructor(
    val game: ImportedGame,
    private val process: ProotWineProcessSession,
    val isDiagnostic: Boolean,
    private val releaseDisplay: () -> Unit
) {
    val isRunning: Boolean
        get() = process.isAlive()

    suspend fun stop(): Int {
        return try {
            process.stop()
        } finally {
            // Keep the compositor and Android Surface alive if PRoot resisted
            // both termination attempts. The user must retain a way to retry
            // stopping the still-running game instead of being left on a dead screen.
            if (!process.isAlive()) {
                withContext(Dispatchers.Main.immediate) {
                    releaseDisplay()
                }
            }
        }
    }
}
