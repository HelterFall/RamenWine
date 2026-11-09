package dev.ramenwine.display

import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.aspauldingcode.wawona.WawonaNative

/**
 * Android SurfaceView host for Wawona's compositor and basic game input.
 *
 * The owner starts WawonaDisplayBackend from onSurfaceChanged and calls
 * bindSession only after the display session has been created. Every input
 * JNI call is serialized against that exact session's shutdown lease.
 */
class WawonaSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    interface Callbacks {
        fun onSurfaceChanged(surface: Surface, widthPx: Int, heightPx: Int)
        fun onSurfaceDestroyed(surface: Surface)
    }

    var callbacks: Callbacks? = null

    @Volatile
    var inputEnabled: Boolean = false
        private set

    @Volatile
    private var inputLeaseToken: Any? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /**
     * Bind input to a Wawona session. Passing null disables input immediately.
     * A session from another display backend is rejected instead of dispatching
     * native events into an unrelated process-global compositor.
     */
    fun bindSession(session: GameDisplaySession?) {
        val token = (session as? WawonaInputSessionLease)?.inputLeaseToken
        require(session == null || token != null) {
            "The display session is not owned by Wawona."
        }
        inputLeaseToken = token
        inputEnabled = token != null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Dimensions are delivered by surfaceChanged; defer compositor startup.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            callbacks?.onSurfaceChanged(holder.surface, width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        bindSession(null)
        callbacks?.onSurfaceDestroyed(holder.surface)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !hasFocus()) {
            requestFocus()
        }

        val timestamp = event.eventTime.toNativeTimestamp()
        dispatchNative {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val index = event.actionIndex
                    WawonaNative.nativeTouchDown(
                        event.getPointerId(index),
                        event.getX(index),
                        event.getY(index),
                        timestamp
                    )
                    WawonaNative.nativeTouchFrame()
                }

                MotionEvent.ACTION_MOVE -> {
                    for (index in 0 until event.pointerCount) {
                        WawonaNative.nativeTouchMotion(
                            event.getPointerId(index),
                            event.getX(index),
                            event.getY(index),
                            timestamp
                        )
                    }
                    WawonaNative.nativeTouchFrame()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    val index = event.actionIndex
                    WawonaNative.nativeTouchUp(event.getPointerId(index), timestamp)
                    WawonaNative.nativeTouchFrame()
                }

                MotionEvent.ACTION_CANCEL -> WawonaNative.nativeTouchCancel()
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputEnabled) return super.onKeyDown(keyCode, event)
        dispatchNative {
            WawonaNative.nativeKeyEvent(keyCode, 1, event.eventTime.toNativeTimestamp())
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputEnabled) return super.onKeyUp(keyCode, event)
        dispatchNative {
            WawonaNative.nativeKeyEvent(keyCode, 0, event.eventTime.toNativeTimestamp())
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!inputEnabled) return super.onGenericMotionEvent(event)
        val timestamp = event.eventTime.toNativeTimestamp()
        val isPointer = event.source and InputDevice.SOURCE_CLASS_POINTER != 0

        if (event.actionMasked == MotionEvent.ACTION_SCROLL && isPointer) {
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            dispatchNative {
                if (vertical != 0f) WawonaNative.nativePointerAxis(0, vertical, timestamp)
                if (horizontal != 0f) WawonaNative.nativePointerAxis(1, horizontal, timestamp)
            }
            return true
        }

        if (event.source and InputDevice.SOURCE_MOUSE != 0) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    dispatchNative {
                        WawonaNative.nativePointerEnter(
                            event.x.toDouble(),
                            event.y.toDouble(),
                            timestamp
                        )
                    }
                    return true
                }

                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_MOVE -> {
                    dispatchNative {
                        WawonaNative.nativePointerMotion(
                            event.x.toDouble(),
                            event.y.toDouble(),
                            timestamp
                        )
                    }
                    return true
                }

                MotionEvent.ACTION_HOVER_EXIT -> {
                    dispatchNative { WawonaNative.nativePointerLeave(timestamp) }
                    return true
                }

                MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val button = when (event.actionButton) {
                        MotionEvent.BUTTON_PRIMARY -> BTN_LEFT
                        MotionEvent.BUTTON_SECONDARY -> BTN_RIGHT
                        MotionEvent.BUTTON_TERTIARY -> BTN_MIDDLE
                        else -> return super.onGenericMotionEvent(event)
                    }
                    val pressed = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                    dispatchNative {
                        WawonaNative.nativePointerButton(
                            button,
                            if (pressed) 1 else 0,
                            timestamp
                        )
                    }
                    return true
                }
            }
        }

        return super.onGenericMotionEvent(event)
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (inputEnabled) {
            dispatchNative { WawonaNative.nativeKeyboardFocus(gainFocus) }
        }
    }

    private fun dispatchNative(block: () -> Unit) {
        val token = inputLeaseToken ?: return
        if (!inputEnabled) return
        WawonaProcessSessionGuard.runIfActive(token, block)
    }

    private fun Long.toNativeTimestamp(): Int = (this % Int.MAX_VALUE).toInt()

    private companion object {
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112
    }
}
