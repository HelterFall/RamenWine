package dev.ramenwine.display

/**
 * Converts a single Android finger gesture into a Wayland mouse gesture.
 *
 * Wine's desktop applications (including most visual novels) consume wl_pointer
 * button events. Sending only wl_touch does not guarantee that a Windows game
 * receives the left-click used to advance dialogue.
 */
internal interface WaylandPointerSink {
    fun enter(x: Double, y: Double, timestampMs: Int)
    fun motion(x: Double, y: Double, timestampMs: Int)
    fun button(buttonCode: Int, state: Int, timestampMs: Int)
    fun leave(timestampMs: Int)
}

internal class TouchToPointerBridge(
    private val sink: WaylandPointerSink
) {
    private var activePointerId: Int? = null
    private var pointerEntered = false

    fun down(pointerId: Int, x: Double, y: Double, timestampMs: Int) {
        if (activePointerId != null) return

        if (pointerEntered) {
            sink.motion(x, y, timestampMs)
        } else {
            sink.enter(x, y, timestampMs)
            pointerEntered = true
        }

        activePointerId = pointerId
        sink.button(BTN_LEFT, BUTTON_PRESSED, timestampMs)
    }

    fun move(pointerId: Int, x: Double, y: Double, timestampMs: Int) {
        if (activePointerId == pointerId) {
            sink.motion(x, y, timestampMs)
        }
    }

    fun up(pointerId: Int, x: Double, y: Double, timestampMs: Int) {
        if (activePointerId != pointerId) return

        sink.motion(x, y, timestampMs)
        sink.button(BTN_LEFT, BUTTON_RELEASED, timestampMs)
        activePointerId = null
    }

    fun cancel(timestampMs: Int) {
        if (activePointerId != null) {
            sink.button(BTN_LEFT, BUTTON_RELEASED, timestampMs)
            activePointerId = null
        }
        if (pointerEntered) {
            sink.leave(timestampMs)
            pointerEntered = false
        }
    }

    private companion object {
        const val BTN_LEFT = 0x110
        const val BUTTON_RELEASED = 0
        const val BUTTON_PRESSED = 1
    }
}
