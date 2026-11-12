package dev.ramenwine.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.aspauldingcode.wawona.WawonaNative
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android Surface host for a Wayland visual-novel session.
 *
 * Touch is routed to the display backend. Android's software keyboard is
 * exposed only while the focused Wayland client asks for text entry.
 */
class WawonaSurfaceView(
    context: Context,
    private val session: DisplaySession,
    private val onSurfaceStateChanged: (Boolean) -> Unit = {}
) : SurfaceView(context), SurfaceHolder.Callback {
    /**
     * Starts the official Weston SHM client and verifies its pixels made it
     * through Wawona's compositor and presenter into this Android SurfaceView.
     */
    suspend fun verifyWaylandShmOutput(timeoutMillis: Long = 10_000L): SurfaceFrameVerification {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Surface frame verification must run on the Android main thread."
        }
        require(timeoutMillis > 0L) { "Verification timeout must be positive." }
        if (!isAttachedToWindow || !holder.surface.isValid || width <= 0 || height <= 0) {
            return SurfaceFrameVerification(
                visible = false,
                detail = "Android Surface is not attached or has no drawable size."
            )
        }
        if (!session.startSurfaceSmokeClient()) {
            return SurfaceFrameVerification(
                visible = false,
                detail = "Wawona could not start weston-simple-shm. Check the native Wawona bundle."
            )
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var lastCopyStatus = PixelCopy.ERROR_SOURCE_NO_DATA
        var lastVisiblePixels = 0
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!isAttachedToWindow || !holder.surface.isValid) {
                    return SurfaceFrameVerification(
                        visible = false,
                        detail = "Android Surface was detached during the Wayland test."
                    )
                }

                val capture = captureSurfaceFrame()
                lastCopyStatus = capture.copyStatus
                lastVisiblePixels = capture.visiblePixelSamples
                if (
                    capture.copyStatus == PixelCopy.SUCCESS &&
                    capture.visiblePixelSamples >= capture.requiredVisiblePixelSamples
                ) {
                    return SurfaceFrameVerification(
                        visible = true,
                        detail = "Успех: PixelCopy прочитал ${capture.visiblePixelSamples} " +
                            "нечёрных точек из ${capture.sampledPixels} на Android Surface. " +
                            "Wayland SHM-клиент действительно отобразился."
                    )
                }

                if (!session.isSurfaceSmokeClientRunning()) {
                    return SurfaceFrameVerification(
                        visible = false,
                        detail = "weston-simple-shm завершился до появления видимого кадра " +
                            "(PixelCopy status=$lastCopyStatus)."
                    )
                }
                delay(SMOKE_CAPTURE_INTERVAL_MS)
            }

            return SurfaceFrameVerification(
                visible = false,
                detail = "За ${timeoutMillis} мс не найден видимый кадр. " +
                    "PixelCopy status=$lastCopyStatus, нечёрных точек=$lastVisiblePixels. " +
                    "Это уже проверка содержимого Surface, а не только флага готовности Wawona."
            )
        } finally {
            Log.i(TAG, "Stopping weston-simple-shm smoke client after PixelCopy verification.")
            session.stopSurfaceSmokeClient()
            Log.i(TAG, "weston-simple-shm smoke client stop requested.")
            // The upstream client uses a detached native thread; settle before teardown.
            delay(SMOKE_CLIENT_STOP_SETTLE_MS)
        }
    }

    private suspend fun captureSurfaceFrame(): SurfaceFrameCapture =
        suspendCancellableCoroutine { continuation ->
            val captureWidth = width
            val captureHeight = height
            if (
                !isAttachedToWindow || !holder.surface.isValid ||
                captureWidth <= 0 || captureHeight <= 0
            ) {
                continuation.resume(
                    SurfaceFrameCapture(
                        copyStatus = PixelCopy.ERROR_SOURCE_NO_DATA,
                        visiblePixelSamples = 0,
                        sampledPixels = 0,
                        requiredVisiblePixelSamples = MIN_VISIBLE_PIXEL_SAMPLES
                    )
                )
                return@suspendCancellableCoroutine
            }

            val bitmap = try {
                Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                continuation.resume(
                    SurfaceFrameCapture(
                        copyStatus = PixelCopy.ERROR_UNKNOWN,
                        visiblePixelSamples = 0,
                        sampledPixels = 0,
                        requiredVisiblePixelSamples = MIN_VISIBLE_PIXEL_SAMPLES
                    )
                )
                return@suspendCancellableCoroutine
            }

            try {
                PixelCopy.request(this, bitmap, { status ->
                    val sample = if (status == PixelCopy.SUCCESS) {
                        countVisiblePixelSamples(bitmap)
                    } else {
                        PixelSample(0, 0)
                    }
                    bitmap.recycle()
                    if (continuation.isActive) {
                        continuation.resume(
                            SurfaceFrameCapture(
                                copyStatus = status,
                                visiblePixelSamples = sample.visible,
                                sampledPixels = sample.total,
                                requiredVisiblePixelSamples = maxOf(
                                    MIN_VISIBLE_PIXEL_SAMPLES,
                                    sample.total / MIN_VISIBLE_PIXEL_PERCENT_DIVISOR
                                )
                            )
                        )
                    }
                }, imeHandler)
            } catch (_: RuntimeException) {
                bitmap.recycle()
                if (continuation.isActive) {
                    continuation.resume(
                        SurfaceFrameCapture(
                            copyStatus = PixelCopy.ERROR_UNKNOWN,
                            visiblePixelSamples = 0,
                            sampledPixels = 0,
                            requiredVisiblePixelSamples = MIN_VISIBLE_PIXEL_SAMPLES
                        )
                    )
                }
            }
        }

    private fun countVisiblePixelSamples(bitmap: Bitmap): PixelSample {
        val columns = FRAME_SAMPLE_GRID
        val rows = FRAME_SAMPLE_GRID
        var visible = 0
        var total = 0
        for (row in 0 until rows) {
            val y = ((row + 0.5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
            for (column in 0 until columns) {
                val x = ((column + 0.5f) * bitmap.width / columns).toInt().coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                val brightness = (
                    Color.red(pixel) * 3 +
                        Color.green(pixel) * 6 +
                        Color.blue(pixel)
                    ) / 10
                total++
                if (brightness > NON_BLACK_BRIGHTNESS_THRESHOLD) visible++
            }
        }
        return PixelSample(visible, total)
    }

    private val imeHandler = Handler(Looper.getMainLooper())
    private var imePolling = false
    private var lastImeWanted: Boolean? = null

    private val imePollRunnable = object : Runnable {
        override fun run() {
            if (!imePolling || !isAttachedToWindow) return

            val wanted = holder.surface.isValid &&
                runCatching { WawonaNative.nativeTextEntryWanted() }.getOrDefault(false)

            if (lastImeWanted != wanted) {
                lastImeWanted = wanted
                if (wanted) showSoftwareKeyboard() else hideSoftwareKeyboard()
            }

            imeHandler.postDelayed(this, IME_POLL_INTERVAL_MS)
        }
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "RamenWine game display"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startImePolling()
    }

    override fun onDetachedFromWindow() {
        stopImePolling()
        hideSoftwareKeyboard()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!holder.surface.isValid) return
        val frame = holder.surfaceFrame
        val targetWidth = (width.takeIf { it > 0 } ?: frame.width()).coerceAtLeast(1)
        val targetHeight = (height.takeIf { it > 0 } ?: frame.height()).coerceAtLeast(1)
        Log.i(TAG, "Surface created: view=${width}x${height}, " +
            "holder=${frame.width()}x${frame.height()}, target=${targetWidth}x${targetHeight}, " +
            "density=${resources.displayMetrics.density}")
        session.attachSurface(
            surface = holder.surface,
            width = targetWidth,
            height = targetHeight,
            density = resources.displayMetrics.density
        )
        onSurfaceStateChanged(true)
        requestFocus()
        startImePolling()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        if (holder.surface.isValid) {
            Log.i(TAG, "Surface changed: view=${this.width}x${this.height}, holder=${width}x${height}")
            session.resizeSurface(width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopImePolling()
        hideSoftwareKeyboard()
        session.detachSurface(holder.surface)
        onSurfaceStateChanged(false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val handled = session.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled || super.onTouchEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val hintAndPurpose = IntArray(2)
        runCatching {
            WawonaNative.nativeGetTextInputContentType(hintAndPurpose)
        }

        outAttrs.inputType = inputTypeForPurpose(hintAndPurpose[1])
        outAttrs.imeOptions =
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return GameTextInputConnection(this)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        session.dispatchKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        session.dispatchKeyEvent(event) || super.onKeyUp(keyCode, event)

    private fun startImePolling() {
        if (imePolling || !isAttachedToWindow || !holder.surface.isValid) return
        imePolling = true
        lastImeWanted = null
        imeHandler.removeCallbacks(imePollRunnable)
        imeHandler.post(imePollRunnable)
    }

    private fun stopImePolling() {
        imePolling = false
        imeHandler.removeCallbacks(imePollRunnable)
        lastImeWanted = null
    }

    private fun showSoftwareKeyboard() {
        if (!isAttachedToWindow || !holder.surface.isValid) return
        requestFocus()
        val manager = inputMethodManager() ?: return
        manager.restartInput(this)

        @Suppress("DEPRECATION")
        manager.showSoftInput(this, InputMethodManager.SHOW_FORCED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            windowInsetsController?.show(WindowInsets.Type.ime())
        }

        // Some Android keyboards ignore the first request while a SurfaceView
        // is acquiring focus. Retry once after the view has settled.
        postDelayed({
            if (imePolling && lastImeWanted == true && isAttachedToWindow) {
                @Suppress("DEPRECATION")
                manager.showSoftInput(this, 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    windowInsetsController?.show(WindowInsets.Type.ime())
                }
            }
        }, IME_SHOW_RETRY_MS)
    }

    private fun hideSoftwareKeyboard() {
        val token = windowToken ?: return
        inputMethodManager()?.hideSoftInputFromWindow(token, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            windowInsetsController?.hide(WindowInsets.Type.ime())
        }
    }

    private fun inputMethodManager(): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun inputTypeForPurpose(purpose: Int): Int {
        val normalText = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        return when (purpose) {
            2 -> InputType.TYPE_CLASS_NUMBER
            9 -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            3 -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            4 -> InputType.TYPE_CLASS_PHONE
            5 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            6 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            8 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            13 -> InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else -> normalText
        }
    }

    private class GameTextInputConnection(view: WawonaSurfaceView) :
        BaseInputConnection(view, true) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text.isNullOrEmpty()) return true
            WawonaNative.nativePreeditText("", 0, 0)
            WawonaNative.nativeCommitText(text.toString())
            super.commitText(text, newCursorPosition)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val composing = text?.toString().orEmpty()
            WawonaNative.nativePreeditText(composing, 0, composing.length)
            super.setComposingText(text, newCursorPosition)
            return true
        }

        override fun finishComposingText(): Boolean {
            WawonaNative.nativePreeditText("", 0, 0)
            super.finishComposingText()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val before = beforeLength.coerceIn(0, MAX_DELETE_REPEAT)
            val after = afterLength.coerceIn(0, MAX_DELETE_REPEAT)
            // Wawona forwards this as a Wayland text-input-v3 delete request.
            // Do not synthesize Backspace/Delete as well: that would delete
            // the same characters twice in clients handling both paths.
            WawonaNative.nativeDeleteSurroundingText(before, after)

            super.deleteSurroundingText(before, after)
            return true
        }

        private companion object {
            const val MAX_DELETE_REPEAT = 4096
        }
    }

    private companion object {
        const val TAG = "RamenWineSurface"
        const val IME_POLL_INTERVAL_MS = 200L
        const val IME_SHOW_RETRY_MS = 120L
        const val SMOKE_CAPTURE_INTERVAL_MS = 200L
        const val SMOKE_CLIENT_STOP_SETTLE_MS = 300L
        const val FRAME_SAMPLE_GRID = 32
        const val MIN_VISIBLE_PIXEL_SAMPLES = 8
        const val MIN_VISIBLE_PIXEL_PERCENT_DIVISOR = 100
        const val NON_BLACK_BRIGHTNESS_THRESHOLD = 28
    }
}

data class SurfaceFrameVerification(
    val visible: Boolean,
    val detail: String
)

private data class SurfaceFrameCapture(
    val copyStatus: Int,
    val visiblePixelSamples: Int,
    val sampledPixels: Int,
    val requiredVisiblePixelSamples: Int
)

private data class PixelSample(
    val visible: Int,
    val total: Int
)
