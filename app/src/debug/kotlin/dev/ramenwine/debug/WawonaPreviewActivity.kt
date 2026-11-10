package dev.ramenwine.debug

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.ramenwine.display.GameDisplaySession
import dev.ramenwine.display.WawonaDisplayBackend
import dev.ramenwine.display.WawonaSmokeTestSession
import dev.ramenwine.display.WawonaSurfaceView
import java.util.ArrayDeque
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
    private val diagnosticsLock = Any()
    private val diagnosticEvents = ArrayDeque<String>()

    private lateinit var surfaceView: WawonaSurfaceView
    private lateinit var statusView: TextView
    private lateinit var diagnosticsView: TextView

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
    private var lastSmokeRunning: Boolean? = null

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
        diagnosticsView = TextView(this).apply {
            textSize = 10f
            setPadding(16, 4, 16, 8)
            maxLines = 5
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
            text = "Технический тест графики Wawona. Он не запускает игры или Wine. " +
                "При успехе должно появиться цветное тестовое окно; чёрный экран означает, " +
                "что отрисовку ещё нужно починить."
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
        controls.addView(Button(this).apply {
            text = "Скопировать отчёт"
            setOnClickListener { copyDiagnostics() }
        })
        root.addView(controls)
        root.addView(statusView)
        root.addView(diagnosticsView)
        root.addView(surfaceView)
        setContentView(root)

        record(
            "Activity created; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); " +
                "ABIs=${Build.SUPPORTED_ABIS.joinToString()}"
        )
        setStatus("Ожидание Android Surface…")
    }

    override fun onSurfaceChanged(surface: Surface, widthPx: Int, heightPx: Int) {
        record(
            "surfaceChanged: id=${System.identityHashCode(surface)}, valid=${surface.isValid}, " +
                "size=${widthPx}x${heightPx}, startPending=$startPending, " +
                "closePending=$closePending, active=${displaySession != null}"
        )
        if (closing || isFinishing || isDestroyed || !surface.isValid) {
            record("surfaceChanged ignored: Activity closing or Surface invalid")
            return
        }
        if (closePending || startPending) {
            pendingSurface = surface
            pendingWidth = widthPx
            pendingHeight = heightPx
            setStatus(
                if (closePending) {
                    "Закрываю предыдущую графическую сессию…"
                } else {
                    "Wawona уже запускается; ожидаю готовности графики…"
                }
            )
            return
        }

        val active = displaySession
        if (active != null) {
            record("Resizing active compositor to ${widthPx}x${heightPx}")
            runCatching {
                nativeExecutor.execute { active.resize(widthPx, heightPx) }
            }.onFailure {
                recordError("Could not queue Surface resize", it)
                setStatus("Не удалось изменить размер Surface: " + (it.message ?: it.javaClass.simpleName))
            }
            return
        }

        val requestedGeneration = ++generation
        startPending = true
        setStatus("Запуск compositor на ${widthPx}×${heightPx}…")
        val startAt = SystemClock.elapsedRealtime()
        record("Queue compositor start: generation=$requestedGeneration")
        runCatching {
            nativeExecutor.execute {
                record("Calling WawonaDisplayBackend.start()")
                val result = runCatching {
                    backend.start(surface, widthPx, heightPx)
                }
                val elapsed = SystemClock.elapsedRealtime() - startAt
                mainHandler.post {
                    val created = result.getOrNull()
                    startPending = false
                    if (created == null) {
                        val error = result.exceptionOrNull()
                        if (error != null) {
                            recordError("Wawona backend start failed after ${elapsed}ms", error)
                            setStatus(
                                "Не удалось запустить Wawona: " +
                                    (error.message ?: error.javaClass.simpleName)
                            )
                        } else {
                            record("Wawona backend returned no session after ${elapsed}ms")
                            setStatus("Wawona не вернул графическую сессию.")
                        }
                        if (closing) {
                            nativeExecutor.shutdown()
                        } else {
                            resumePendingSurface()
                        }
                        return@post
                    }
                    record(
                        "Wawona backend started in ${elapsed}ms; socket=" +
                            created.endpoint.socketName
                    )
                    if (
                        requestedGeneration != generation ||
                        closing ||
                        isFinishing ||
                        isDestroyed ||
                        !surface.isValid
                    ) {
                        record("New Wawona session is stale; closing it")
                        closePending = true
                        closeDisplaySession(created) { resumePendingSurface() }
                        return@post
                    }

                    val newerSurface = pendingSurface
                    if (newerSurface != null && newerSurface !== surface && newerSurface.isValid) {
                        record(
                            "A different Surface arrived during startup: old=" +
                                "${System.identityHashCode(surface)}, new=" +
                                "${System.identityHashCode(newerSurface)}"
                        )
                        closePending = true
                        closeDisplaySession(created) { resumePendingSurface() }
                        return@post
                    }

                    val latestWidth = if (pendingSurface === surface) pendingWidth else widthPx
                    val latestHeight = if (pendingSurface === surface) pendingHeight else heightPx
                    if (pendingSurface === surface) {
                        pendingSurface = null
                        pendingWidth = 0
                        pendingHeight = 0
                    }

                    displaySession = created
                    surfaceView.bindSession(created)
                    setStatus("Compositor ready · Wayland socket: " + created.endpoint.socketName)
                    if (latestWidth != widthPx || latestHeight != heightPx) {
                        record("Applying latest Surface size ${latestWidth}x${latestHeight}")
                        setStatus("Применяю последний размер Surface…")
                        nativeExecutor.execute {
                            val resizeResult = runCatching {
                                created.resize(latestWidth, latestHeight)
                            }
                            mainHandler.post {
                                if (displaySession === created) {
                                    resizeResult.onFailure {
                                        recordError("Initial Surface resize failed", it)
                                        setStatus(
                                            "Ошибка изменения размера: " +
                                                (it.message ?: it.javaClass.simpleName)
                                        )
                                    }
                                    startSmokeClient()
                                }
                            }
                        }
                    } else {
                        startSmokeClient()
                    }
                }
            }
        }.onFailure {
            startPending = false
            recordError("Could not queue Wawona startup", it)
            setStatus("Не удалось поставить запуск в очередь: " + (it.message ?: it.javaClass.simpleName))
            if (closing) {
                nativeExecutor.shutdown()
            } else {
                resumePendingSurface()
            }
        }
    }

    override fun onSurfaceDestroyed(surface: Surface) {
        record("surfaceDestroyed: id=${System.identityHashCode(surface)}, valid=${surface.isValid}")
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
            record("SHM start skipped: active session does not implement WawonaSmokeTestSession")
            setStatus("Текущая сессия не поддерживает SHM smoke test.")
            return
        }

        lastSmokeRunning = null
        record("Requesting weston-simple-shm start")
        setStatus("Запускаю тестовый SHM-клиент…")
        runCatching { smoke.startWestonSimpleShm() }
            .onSuccess { started ->
                record("weston-simple-shm start result=$started")
                if (!started) {
                    setStatus("Wawona не смог запустить weston-simple-shm.")
                } else {
                    setStatus("weston-simple-shm запущен. Ожидается Wayland SHM-окно.")
                    beginSmokePolling(smoke)
                }
            }
            .onFailure {
                recordError("weston-simple-shm start failed", it)
                setStatus("Ошибка запуска SHM-клиента: " + (it.message ?: it.javaClass.simpleName))
            }
    }

    private fun stopSmokeClient() {
        val smoke = displaySession as? WawonaSmokeTestSession ?: return
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollRunnable = null
        record("User requested SHM client stop")
        runCatching { smoke.stopWestonSimpleShm() }
            .onSuccess {
                record("SHM stop request returned; upstream does not provide pthread join")
                setStatus("Stop request отправлен. Upstream не предоставляет join для SHM-потока.")
            }
            .onFailure {
                recordError("SHM client stop failed", it)
                setStatus("Ошибка остановки SHM-клиента: " + (it.message ?: it.javaClass.simpleName))
            }
    }

    private fun beginSmokePolling(smoke: WawonaSmokeTestSession) {
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollStartedAt = SystemClock.uptimeMillis()
        lastSmokeRunning = null
        val poll = object : Runnable {
            override fun run() {
                val running = runCatching { smoke.isWestonSimpleShmRunning() }
                    .onFailure { recordError("SHM running-state query failed", it) }
                    .getOrDefault(false)
                if (lastSmokeRunning != running) {
                    lastSmokeRunning = running
                    record("SHM native running flag changed to $running")
                }
                if (running) {
                    setStatus("weston-simple-shm запущен · проверь цветное SHM-окно")
                    if (SystemClock.uptimeMillis() - pollStartedAt < SMOKE_POLL_LIMIT_MS) {
                        mainHandler.postDelayed(this, SMOKE_POLL_INTERVAL_MS)
                    } else {
                        record("SHM smoke polling reached the ${SMOKE_POLL_LIMIT_MS}ms limit")
                        setStatus("SHM-клиент всё ещё активен. Нажми Stop перед закрытием.")
                    }
                } else if (displaySession != null) {
                    record("SHM running flag is false while compositor session is still active")
                    setStatus(
                        "SHM-клиент не активен. Чёрный экран означает, что визуальный тест не пройден."
                    )
                }
            }
        }
        pollRunnable = poll
        mainHandler.post(poll)
    }

    private fun releaseDisplay(reason: String) {
        record("Releasing display: $reason")
        generation += 1
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollRunnable = null
        surfaceView.bindSession(null)
        val old = displaySession
        if (old == null) {
            if (startPending) {
                closePending = true
                record("No active session yet; mark current startup stale and wait for its cleanup")
            }
            return
        }
        displaySession = null
        closePending = true
        setStatus(reason + " · закрываю compositor…")
        closeDisplaySession(old) { resumePendingSurface() }
    }

    private fun resumePendingSurface() {
        closePending = false
        if (closing) {
            nativeExecutor.shutdown()
            pendingSurface = null
            return
        }
        val nextSurface = pendingSurface
        val nextWidth = pendingWidth
        val nextHeight = pendingHeight
        pendingSurface = null
        pendingWidth = 0
        pendingHeight = 0
        if (
            !closing && nextSurface != null && nextSurface.isValid &&
            nextWidth > 0 && nextHeight > 0
        ) {
            record("Resuming compositor startup for queued Surface")
            onSurfaceChanged(nextSurface, nextWidth, nextHeight)
        } else {
            record("No valid queued Surface after compositor cleanup")
        }
    }

    private fun closeDisplaySession(
        session: GameDisplaySession,
        onClosed: () -> Unit
    ) {
        record("Queue Wawona display session close")
        // GameDisplaySession.close() requests native SHM stop (once per
        // session), applies a short best-effort grace window, then destroys
        // the Surface and core. Upstream does not expose a pthread join.
        runCatching {
            nativeExecutor.execute {
                val closeAt = SystemClock.elapsedRealtime()
                val closeResult = runCatching { session.close() }
                val elapsed = SystemClock.elapsedRealtime() - closeAt
                mainHandler.post {
                    closeResult.onFailure { recordError("Wawona session close failed", it) }
                    record("Wawona session close returned after ${elapsed}ms")
                    onClosed()
                }
            }
        }.onFailure {
            recordError("Could not queue Wawona session close", it)
            val closeResult = runCatching { session.close() }
            closeResult.onFailure { recordError("Fallback Wawona session close failed", it) }
            onClosed()
        }
    }

    private fun setStatus(message: String) {
        if (!::statusView.isInitialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setStatus(message) }
            return
        }
        if (statusView.text.toString() != message) {
            statusView.text = message
            record("STATUS: $message")
        }
    }

    private fun record(message: String) {
        val safeMessage = message.replace('\n', ' ').take(MAX_EVENT_LENGTH)
        val line = "[${SystemClock.elapsedRealtime()}ms] $safeMessage"
        synchronized(diagnosticsLock) {
            diagnosticEvents.addLast(line)
            while (diagnosticEvents.size > MAX_DIAGNOSTIC_EVENTS) {
                diagnosticEvents.removeFirst()
            }
        }
        Log.i(DIAGNOSTIC_TAG, safeMessage)
        if (::diagnosticsView.isInitialized) {
            mainHandler.post { diagnosticsView.text = diagnosticPreview() }
        }
    }

    private fun recordError(message: String, error: Throwable) {
        record("ERROR: $message: ${error.javaClass.simpleName}: ${error.message}")
        Log.e(DIAGNOSTIC_TAG, message, error)
    }

    private fun diagnosticPreview(): String {
        val events = synchronized(diagnosticsLock) { diagnosticEvents.toList().takeLast(PREVIEW_EVENT_COUNT) }
        return events.joinToString(separator = "\n")
    }

    private fun buildDiagnosticReport(): String {
        val events = synchronized(diagnosticsLock) { diagnosticEvents.toList() }
        return buildString {
            appendLine("RamenWine Wawona graphics diagnostics")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Surface valid: ${if (::surfaceView.isInitialized) surfaceView.holder.surface.isValid else false}")
            appendLine("Compositor session active: ${displaySession != null}")
            appendLine("Startup pending: $startPending; close pending: $closePending")
            appendLine("Recent events:")
            events.forEach(::appendLine)
        }
    }

    private fun copyDiagnostics() {
        runCatching {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("RamenWine Wawona diagnostics", buildDiagnosticReport())
            )
        }.onSuccess {
            record("Diagnostic report copied to clipboard")
            setStatus("Отчёт скопирован. Вставь его сюда сообщением.")
        }.onFailure {
            recordError("Could not copy diagnostic report", it)
            setStatus("Не удалось скопировать отчёт: " + (it.message ?: it.javaClass.simpleName))
        }
    }

    override fun onPause() {
        record("Activity onPause")
        releaseDisplay("Приложение приостановлено")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        record("Activity onResume")
        if (!closing && ::surfaceView.isInitialized) {
            surfaceView.post {
                if (closing || isFinishing || isDestroyed) return@post
                val surface = surfaceView.holder.surface
                val width = surfaceView.width
                val height = surfaceView.height
                record(
                    "onResume Surface check: id=${System.identityHashCode(surface)}, " +
                        "valid=${surface.isValid}, size=${width}x${height}"
                )
                if (surface.isValid && width > 0 && height > 0) {
                    onSurfaceChanged(surface, width, height)
                }
            }
        }
    }

    override fun onDestroy() {
        record("Activity onDestroy")
        closing = true
        generation += 1
        pollRunnable?.let(mainHandler::removeCallbacks)
        surfaceView.bindSession(null)
        val old = displaySession
        displaySession = null
        if (old != null) {
            closePending = true
            closeDisplaySession(old) { resumePendingSurface() }
        } else if (!startPending && !closePending) {
            nativeExecutor.shutdown()
        }
        super.onDestroy()
    }

    private companion object {
        const val SMOKE_POLL_INTERVAL_MS = 300L
        const val SMOKE_POLL_LIMIT_MS = 30_000L
        const val MAX_DIAGNOSTIC_EVENTS = 80
        const val PREVIEW_EVENT_COUNT = 6
        const val MAX_EVENT_LENGTH = 240
        const val DIAGNOSTIC_TAG = "RamenWineWawona"
    }
}
