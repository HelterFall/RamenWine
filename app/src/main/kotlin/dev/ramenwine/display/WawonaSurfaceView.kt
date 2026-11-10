package dev.ramenwine.display

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Minimal Android Surface host for a DisplaySession.
 *
 * Text/IME composition and richer mouse/scroll mapping remain separate work;
 * this view forwards basic touch and hardware-key events to the compositor.
 */
class WawonaSurfaceView(
    context: Context,
    private val session: DisplaySession,
    private val onSurfaceStateChanged: (Boolean) -> Unit = {}
) : SurfaceView(context), SurfaceHolder.Callback {
    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "RamenWine game display"
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        session.dispatchKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        session.dispatchKeyEvent(event) || super.onKeyUp(keyCode, event)
}
