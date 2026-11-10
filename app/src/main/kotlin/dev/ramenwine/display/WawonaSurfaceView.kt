package dev.ramenwine.display

import android.content.Context
import android.os.Handler
import android.os.Looper
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
        if (holder.surface.isValid) session.resizeSurface(width, height)
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
            WawonaNative.nativeDeleteSurroundingText(before, after)

            val timestamp = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            repeat(before) {
                WawonaNative.nativeInjectKey(KEY_BACKSPACE, true, timestamp)
                WawonaNative.nativeInjectKey(KEY_BACKSPACE, false, timestamp)
            }
            repeat(after) {
                WawonaNative.nativeInjectKey(KEY_DELETE, true, timestamp)
                WawonaNative.nativeInjectKey(KEY_DELETE, false, timestamp)
            }

            super.deleteSurroundingText(before, after)
            return true
        }

        private companion object {
            const val KEY_BACKSPACE = 14
            const val KEY_DELETE = 111
            const val MAX_DELETE_REPEAT = 4096
        }
    }

    private companion object {
        const val IME_POLL_INTERVAL_MS = 200L
        const val IME_SHOW_RETRY_MS = 120L
    }
}
