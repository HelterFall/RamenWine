package dev.ramenwine

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import dev.ramenwine.display.WawonaDisplayBackend
import dev.ramenwine.display.WawonaSurfaceView
import dev.ramenwine.library.GameLibraryStore
import dev.ramenwine.library.ImportedGame
import dev.ramenwine.runtime.GameLaunchCoordinator
import dev.ramenwine.runtime.GameLaunchSession
import dev.ramenwine.runtime.RuntimeBackendId
import dev.ramenwine.runtime.RuntimeLayout
import dev.ramenwine.runtime.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen game-only surface. It does not start a Windows desktop or show
 * desktop controls: Wawona displays the imported game's window, and
 * WawonaSurfaceView maps finger taps to the clicks expected by visual novels.
 */
class GamePlayerActivity : ComponentActivity(), WawonaSurfaceView.Callbacks {
    companion object {
        const val EXTRA_GAME_ID = "dev.ramenwine.extra.GAME_ID"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var surfaceView: WawonaSurfaceView
    private lateinit var statusView: TextView
    private lateinit var game: ImportedGame
    private lateinit var coordinator: GameLaunchCoordinator

    private var currentSurface: Surface? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var launchJob: Job? = null
    private var launchSession: GameLaunchSession? = null
    private val shutdownJobs = mutableSetOf<Job>()
    private var cleanupShutdownRequested = false
    private var generation = 0
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        surfaceView = WawonaSurfaceView(this).apply {
            callbacks = this@GamePlayerActivity
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 12, 14, 18))
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(28, 22, 28, 22)
            text = "Подготовка новеллы…"
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                statusView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    requestExit()
                }
            }
        )

        val gameId = intent.getStringExtra(EXTRA_GAME_ID)
        val storedGame = gameId?.let { id ->
            GameLibraryStore(applicationContext).list().firstOrNull { it.id == id }
        }
        if (storedGame == null) {
            showStatus("Эта игра больше не найдена в библиотеке. Вернись назад и добавь её снова.")
            surfaceView.visibility = View.GONE
            return
        }
        game = storedGame

        if (game.architecture != "x86" && game.architecture != "x86-64") {
            showStatus(
                "Эта новелла использует архитектуру " + game.architecture +
                    ". Сейчас поддерживаются Windows x86 и x86-64. " +
                    "Игра осталась в библиотеке, исходная папка не затронута."
            )
            surfaceView.visibility = View.GONE
            return
        }

        val prootBackend = RuntimeLayout.inspect(filesDir).backends.firstOrNull {
            it.backend == RuntimeBackendId.GLIBC_PROOT_BOX64
        }
        if (prootBackend?.state != RuntimeState.FILES_PRESENT) {
            showStatus("Игровой движок ещё не установлен. Новелла сохранена в библиотеке, запуск пока недоступен.")
            surfaceView.visibility = View.GONE
            return
        }

        coordinator = GameLaunchCoordinator(
            applicationContext,
            WawonaDisplayBackend(applicationContext)
        )
        showStatus("Ожидание игрового экрана…")
        // Surface callbacks can arrive before the library/runtime checks finish.
        // Start now if Android already created the Surface.
        currentSurface?.takeIf { it.isValid }?.let { surface ->
            if (currentWidth > 0 && currentHeight > 0) {
                startGame(surface, currentWidth, currentHeight)
            }
        }
    }

    override fun onSurfaceChanged(surface: Surface, widthPx: Int, heightPx: Int) {
        if (closing || isFinishing || isDestroyed || !surface.isValid) return

        currentSurface = surface
        currentWidth = widthPx
        currentHeight = heightPx

        val active = launchSession
        if (active != null && active.isAlive) {
            cleanupScope.launch {
                runCatching { active.resize(widthPx, heightPx) }
            }
            return
        }
        if (launchJob?.isActive == true || !::game.isInitialized || !::coordinator.isInitialized) {
            return
        }

        startGame(surface, widthPx, heightPx)
    }

    override fun onSurfaceDestroyed(surface: Surface) {
        if (currentSurface !== surface) return
        currentSurface = null
        currentWidth = 0
        currentHeight = 0
        generation += 1

        launchJob?.cancel()
        launchJob = null

        val active = launchSession
        launchSession = null
        scheduleSessionStop(active)
    }

    private fun startGame(surface: Surface, widthPx: Int, heightPx: Int) {
        val requestedGeneration = ++generation
        showStatus("Запускаю новеллу…")

        launchJob = uiScope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    coordinator.launch(
                        game = game,
                        surface = surface,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        onOutput = { /* Runtime diagnostics remain off the game surface. */ },
                        onExit = { exitCode ->
                            mainHandler.post {
                                handleGameExit(exitCode)
                            }
                        }
                    )
                }

                val latestSurface = currentSurface
                if (
                    requestedGeneration != generation ||
                    closing ||
                    isFinishing ||
                    isDestroyed ||
                    latestSurface !== surface ||
                    !surface.isValid
                ) {
                    scheduleSessionStop(created)
                    return@launch
                }

                if (!created.isAlive) {
                    surfaceView.bindSession(null)
                    showStatus("Новелла завершилась сразу после запуска.")
                    return@launch
                }

                launchSession = created
                surfaceView.bindSession(created.displaySession)
                statusView.visibility = View.GONE

                val latestWidth = currentWidth
                val latestHeight = currentHeight
                if (latestWidth > 0 && latestHeight > 0 &&
                    (latestWidth != widthPx || latestHeight != heightPx)
                ) {
                    cleanupScope.launch {
                        runCatching { created.resize(latestWidth, latestHeight) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestedGeneration == generation && !closing && !isDestroyed) {
                    showStatus(
                        "Не удалось запустить новеллу: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                }
            } finally {
                if (requestedGeneration == generation) {
                    launchJob = null
                }
            }
        }
    }

    private fun handleGameExit(exitCode: Int) {
        if (closing || isFinishing || isDestroyed) return
        surfaceView.bindSession(null)
        launchSession = null
        showStatus(
            if (exitCode == 0) {
                "Новелла завершилась."
            } else {
                "Новелла закрылась (код $exitCode)."
            }
        )
    }

    private fun requestExit() {
        if (closing) return
        closing = true
        generation += 1
        launchJob?.cancel()
        launchJob = null
        surfaceView.bindSession(null)
        showStatus("Закрываю новеллу…")

        val active = launchSession
        launchSession = null
        scheduleSessionStop(active) {
            if (!isFinishing) finish()
        }
    }

    /**
     * Stops are tracked until the process and its display lease are both released.
     * The cleanup scope is cancelled only after Activity teardown has started and
     * every stop (plus any in-flight launch coroutine) has completed.
     */
    private fun scheduleSessionStop(
        session: GameLaunchSession?,
        onComplete: (() -> Unit)? = null
    ) {
        if (session == null) {
            onComplete?.invoke()
            cancelCleanupScopeWhenIdle()
            return
        }

        val job = cleanupScope.launch {
            withContext(NonCancellable) {
                runCatching { session.stop() }
            }
            if (onComplete != null) {
                withContext(Dispatchers.Main.immediate) {
                    onComplete()
                }
            }
        }
        trackShutdownJob(job)
    }

    private fun trackShutdownJob(job: Job) {
        shutdownJobs.add(job)
        job.invokeOnCompletion {
            mainHandler.post {
                shutdownJobs.remove(job)
                cancelCleanupScopeWhenIdle()
            }
        }
    }

    private fun cancelCleanupScopeWhenIdle() {
        if (cleanupShutdownRequested && shutdownJobs.isEmpty()) {
            cleanupScope.cancel()
        }
    }

    private fun showStatus(message: String) {
        if (!::statusView.isInitialized || isDestroyed) return
        statusView.text = message
        statusView.visibility = View.VISIBLE
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }

    override fun onDestroy() {
        closing = true
        generation += 1
        if (::surfaceView.isInitialized) {
            surfaceView.bindSession(null)
        }
        launchJob?.cancel()
        launchJob = null

        val active = launchSession
        launchSession = null

        cleanupShutdownRequested = true
        launchJob?.let(::trackShutdownJob)
        scheduleSessionStop(active)
        cancelCleanupScopeWhenIdle()

        uiScope.cancel()
        super.onDestroy()
    }
}
