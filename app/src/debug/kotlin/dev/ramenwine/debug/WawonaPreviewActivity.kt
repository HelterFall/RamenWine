package dev.ramenwine.debug

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.ramenwine.display.GameDisplaySession
import dev.ramenwine.display.WawonaDisplayBackend
import dev.ramenwine.display.WawonaSmokeTestSession
import dev.ramenwine.display.WawonaSurfaceView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Debug-only visual validation of Android Surface -> Wawona -> Wayland SHM.
 * This screen never starts Wine or executes imported Windows files.
 */
class WawonaPreviewActivity : Activity(), WawonaSurfaceView.Callbacks {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val backend by lazy { WawonaDisplayBackend(applicationContext) }

    private lateinit var surfaceView: WawonaSurfaceView
    private lateinit var statusView: TextView

    private var displaySession: GameDisplaySession? = null
    private var generation = 0
    private var closing = false
    private var closePending = false
    private var startPending = false
    private var pendingSurface: Surface? = null
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var pollRunnable: Runnable? = null
    private var pollStartedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = WawonaSurfaceView(this).apply {
            callbacks = this@WawonaPreviewActivity
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        statusView = TextView(this).apply {
            text = "Ожидание Android Surface…"
            setPadding(16, 8, 16, 8)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        root.addView(TextView(this).apply {
            text = "WAWONA WAYLAND / SHM SMOKE TEST"
            textSize = 16f
            setPadding(8, 8, 8, 4)
        })
        root.addView(TextView(this).apply {
            text = "Только тест графики. Wine и импортированные EXE не запускаются."
            textSize = 12f
            setPadding(8, 0, 8, 4)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        controls.addView(Button(this).apply {
            text = "Start SHM client"
            setOnClickListener { startSmokeClient() }
        })
        controls.addView(Button(this).apply {
            text = "Stop SHM client"
            setOnClickListener { stopSmokeClient() }
        })
        root.addView(controls)
        root.addView(statusView)
        root.addView(surfaceView)
        setContentView(root)
    }

    override fun onSurfaceChanged(surface: Surface, widthPx: Int, heightPx: Int) {
        if (closing || isFinishing || isDestroyed || !surface.isValid) return
        if (closePending || startPending) {
            pendingSurface = surface
            pendingWidth = widthPx
            pendingHeight = heightPx
            statusView.text = "Завершаю предыдущую native-сессию…"
            return
        }

        val active = displaySession
        if (active != null) {
            runCatching {
                nativeExecutor.execute { active.resize(widthPx, heightPx) }
            }
            return
        }

        val requestedGeneration = ++generation
        startPending = true
        statusView.text = "Запуск compositor на " + widthPx + "×" + heightPx + "…"
        runCatching {
            nativeExecutor.execute {
                val result = runCatching {
                    backend.start(surface, widthPx, heightPx)
                }
                mainHandler.post {
                    val created = result.getOrNull()
                    startPending = false
                    if (created == null) {
                        result.exceptionOrNull()?.let {
                            statusView.text = "Не удалось запустить Wawona: " +
                                (it.message ?: it.javaClass.simpleName)
                        }
                        resumePendingSurface()
                        return@post
                    }
                    if (
                        requestedGeneration != generation ||
                        closing ||
                        isFinishing ||
                        isDestroyed ||
                        !surface.isValid
                    ) {
                        closePending = true
                        closeDisplaySession(created) { resumePendingSurface() }
                        return@post
                    }

                    displaySession = created
                    surfaceView.bindSession(created)
                    statusView.text =
                        "Compositor ready · Wayland socket: " + created.endpoint.socketName
                    startSmokeClient()
                }
            }
        }.onFailure {
            startPending = false
            statusView.text = "Не удалось поставить запуск в очередь: " +
                (it.message ?: it.javaClass.simpleName)
            resumePendingSurface()
        }
    }

    override fun onSurfaceDestroyed(surface: Surface) {
        if (pendingSurface === surface) {
            pendingSurface = null
            pendingWidth = 0
            pendingHeight = 0
        }
        releaseDisplay("Android Surface уничтожена")
    }

    private fun startSmokeClient() {
        val smoke = displaySession as? WawonaSmokeTestSession
        if (smoke == null) {
            statusView.text = "Текущая сессия не поддерживает SHM smoke test."
            return
        }

        runCatching { smoke.startWestonSimpleShm() }
            .onSuccess { started ->
                if (!started) {
                    statusView.text = "Wawona не смог запустить weston-simple-shm."
                } else {
                    statusView.text =
                        "weston-simple-shm запущен. Ожидается Wayland SHM-окно."
                    beginSmokePolling(smoke)
                }
            }
            .onFailure {
                statusView.text = "Ошибка запуска SHM-клиента: " +
                    (it.message ?: it.javaClass.simpleName)
            }
    }

    private fun stopSmokeClient() {
        val smoke = displaySession as? WawonaSmokeTestSession ?: return
        runCatching { smoke.stopWestonSimpleShm() }
            .onFailure {
                statusView.text = "Ошибка остановки SHM-клиента: " +
                    (it.message ?: it.javaClass.simpleName)
            }
        statusView.text = "Остановка SHM-клиента…"
        beginSmokePolling(smoke, stopping = true)
    }

    private fun beginSmokePolling(
        smoke: WawonaSmokeTestSession,
        stopping: Boolean = false
    ) {
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollStartedAt = SystemClock.uptimeMillis()
        val poll = object : Runnable {
            override fun run() {
                val running = runCatching { smoke.isWestonSimpleShmRunning() }
                    .getOrDefault(false)
                if (running) {
                    statusView.text = if (stopping) {
                        "Ожидание остановки SHM-клиента…"
                    } else {
                        "weston-simple-shm активен · проверь цветное SHM-окно"
                    }
                    if (SystemClock.uptimeMillis() - pollStartedAt < SMOKE_POLL_LIMIT_MS) {
                        mainHandler.postDelayed(this, SMOKE_POLL_INTERVAL_MS)
                    } else if (stopping) {
                        statusView.text =
                            "SHM-клиент не подтвердил остановку; закрытие compositor продолжится."
                    } else {
                        statusView.text =
                            "SHM-клиент всё ещё активен. Проверь окно и нажми Stop перед закрытием."
                    }
                } else if (displaySession != null) {
                    statusView.text = "SHM-клиент завершился. Compositor продолжает работать."
                }
            }
        }
        pollRunnable = poll
        mainHandler.post(poll)
    }

    private fun releaseDisplay(reason: String) {
        generation += 1
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollRunnable = null
        surfaceView.bindSession(null)
        val old = displaySession
        if (old == null) {
            if (startPending) closePending = true
            return
        }
        displaySession = null
        closePending = true
        statusView.text = reason + " · закрываю compositor…"
        closeDisplaySession(old) { resumePendingSurface() }
    }

    private fun resumePendingSurface() {
        closePending = false
        val nextSurface = pendingSurface
        val nextWidth = pendingWidth
        val nextHeight = pendingHeight
        pendingSurface = null
        if (
            !closing && nextSurface != null && nextSurface.isValid &&
            nextWidth > 0 && nextHeight > 0
        ) {
            onSurfaceChanged(nextSurface, nextWidth, nextHeight)
        }
    }

    private fun closeDisplaySession(
        session: GameDisplaySession,
        onClosed: () -> Unit
    ) {
        val smoke = session as? WawonaSmokeTestSession
        if (smoke != null) {
            runCatching { smoke.stopWestonSimpleShm() }
        }

        // Upstream exposes an asynchronous detached SHM-client thread. Wait for
        // its native running flag to clear before destroying the Vulkan surface.
        val startedAt = SystemClock.uptimeMillis()
        val finishClose = object : Runnable {
            override fun run() {
                val running = smoke?.let {
                    runCatching { it.isWestonSimpleShmRunning() }.getOrDefault(false)
                } ?: false
                if (running && SystemClock.uptimeMillis() - startedAt < SMOKE_STOP_LIMIT_MS) {
                    mainHandler.postDelayed(this, SMOKE_POLL_INTERVAL_MS)
                } else {
                    runCatching {
                        nativeExecutor.execute {
                            runCatching { session.close() }
                            mainHandler.post { onClosed() }
                        }
                    }.onFailure {
                        session.close()
                        onClosed()
                    }
                }
            }
        }
        mainHandler.post(finishClose)
    }

    override fun onDestroy() {
        closing = true
        generation += 1
        pollRunnable?.let(mainHandler::removeCallbacks)
        surfaceView.bindSession(null)
        val old = displaySession
        displaySession = null
        if (old != null) {
            runCatching {
                nativeExecutor.execute {
                    val smoke = old as? WawonaSmokeTestSession
                    runCatching { smoke?.stopWestonSimpleShm() }
                    runCatching { old.close() }
                }
            }.onFailure { old.close() }
        }
        nativeExecutor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val SMOKE_POLL_INTERVAL_MS = 150L
        const val SMOKE_POLL_LIMIT_MS = 30_000L
        const val SMOKE_STOP_LIMIT_MS = 2_000L
    }
}
