package dev.ramenwine.display

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.aspauldingcode.wawona.WawonaNative

/** Applies the Android host keyboard map to the Wawona Wayland seat. */
internal object WawonaHostKeymap {
    private const val COMBINING = KeyCharacterMap.COMBINING_ACCENT
    private const val COMBINING_MASK = KeyCharacterMap.COMBINING_ACCENT_MASK

    fun apply(): Boolean {
        val keyMap = runCatching {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        }.getOrElse {
            KeyCharacterMap.load(KeyCharacterMap.BUILT_IN_KEYBOARD)
        }

        val ids = ArrayList<Int>(85)
        val levels = ArrayList<Int>(85 * 4)
        for (keyCode in 0..84) {
            val none = decode(keyMap.get(keyCode, 0))
            val shift = decode(keyMap.get(keyCode, KeyEvent.META_SHIFT_ON))
            val alt = decode(keyMap.get(keyCode, KeyEvent.META_ALT_ON))
            val shiftAlt = decode(
                keyMap.get(keyCode, KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON)
            )
            if (none == 0 && shift == 0 && alt == 0 && shiftAlt == 0) continue

            ids.add(keyCode)
            levels.add(none)
            levels.add(shift)
            levels.add(alt)
            levels.add(shiftAlt)
        }

        if (ids.isEmpty()) return false
        WawonaNative.nativeApplyHostKeyLevels(ids.toIntArray(), levels.toIntArray())
        return true
    }

    private fun decode(value: Int): Int {
        if (value == 0) return 0
        return if ((value and COMBINING) != 0) value and COMBINING_MASK else value
    }
}
