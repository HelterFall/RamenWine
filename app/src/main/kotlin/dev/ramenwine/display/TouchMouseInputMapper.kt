package dev.ramenwine.display

/**
 * Converts one Android finger into the left mouse button used by traditional
 * Windows visual novels. Most VN engines expect mouse clicks, not a Windows
 * touch digitizer. Only the first finger owns the pointer; extra fingers are
 * ignored so a two-finger gesture cannot generate duplicate story advances.
 *
 * This class is deliberately Android-free so its gesture state machine can be
 * unit-tested on the JVM.
 */
internal class TouchMouseInputMapper {
    sealed class Command {
        data class PointerEnter(
            val x: Double,
            val y: Double,
            val timestamp: Int
        ) : Command()

        data class PointerMotion(
            val x: Double,
            val y: Double,
            val timestamp: Int
        ) : Command()

        data class PointerButton(
            val button: Int,
            val pressed: Boolean,
            val timestamp: Int
        ) : Command()

        data class KeyboardFocus(val focused: Boolean) : Command()
    }

    val activePointerId: Int?
        get() = ownerPointerId

    private var ownerPointerId: Int? = null

    fun pointerDown(
        pointerId: Int,
        x: Double,
        y: Double,
        timestamp: Int
    ): List<Command> {
        if (ownerPointerId != null) return emptyList()

        ownerPointerId = pointerId
        return listOf(
            Command.PointerEnter(x, y, timestamp),
            // The native focus callback can arrive before pointer hit-testing
            // has identified a Wayland window. Focus it only after PointerEnter.
            Command.KeyboardFocus(focused = true),
            Command.PointerButton(BTN_LEFT, pressed = true, timestamp = timestamp)
        )
    }

    fun pointerMove(
        pointerId: Int,
        x: Double,
        y: Double,
        timestamp: Int
    ): List<Command> {
        if (ownerPointerId != pointerId) return emptyList()
        return listOf(Command.PointerMotion(x, y, timestamp))
    }

    fun pointerUp(
        pointerId: Int,
        x: Double,
        y: Double,
        timestamp: Int
    ): List<Command> {
        if (ownerPointerId != pointerId) return emptyList()

        ownerPointerId = null
        return listOf(
            Command.PointerMotion(x, y, timestamp),
            Command.PointerButton(BTN_LEFT, pressed = false, timestamp = timestamp)
        )
    }

    /**
     * Releases a held click when Android cancels a gesture or the game surface
     * is detached. Returning the release before dropping the display lease
     * prevents the guest from being left with a permanently pressed button.
     */
    fun cancel(timestamp: Int): List<Command> {
        if (ownerPointerId == null) return emptyList()

        ownerPointerId = null
        return listOf(Command.PointerButton(BTN_LEFT, pressed = false, timestamp))
    }

    private companion object {
        const val BTN_LEFT = 0x110
    }
}
