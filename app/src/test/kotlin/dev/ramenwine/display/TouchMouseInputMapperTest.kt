package dev.ramenwine.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchMouseInputMapperTest {
    @Test
    fun tapBecomesOneLeftClickAtTheFingerPosition() {
        val mapper = TouchMouseInputMapper()

        assertEquals(
            listOf(
                TouchMouseInputMapper.Command.PointerEnter(24.0, 48.0, 100),
                TouchMouseInputMapper.Command.KeyboardFocus(true),
                TouchMouseInputMapper.Command.PointerButton(0x110, true, 100)
            ),
            mapper.pointerDown(7, 24.0, 48.0, 100)
        )
        assertEquals(
            listOf(
                TouchMouseInputMapper.Command.PointerMotion(24.0, 48.0, 116),
                TouchMouseInputMapper.Command.PointerButton(0x110, false, 116)
            ),
            mapper.pointerUp(7, 24.0, 48.0, 116)
        )
        assertNull(mapper.activePointerId)
    }

    @Test
    fun draggingMovesThePressedPointer() {
        val mapper = TouchMouseInputMapper()
        mapper.pointerDown(3, 10.0, 20.0, 1)

        assertEquals(
            listOf(TouchMouseInputMapper.Command.PointerMotion(40.0, 55.0, 2)),
            mapper.pointerMove(3, 40.0, 55.0, 2)
        )
        assertEquals(
            listOf(
                TouchMouseInputMapper.Command.PointerMotion(40.0, 55.0, 3),
                TouchMouseInputMapper.Command.PointerButton(0x110, false, 3)
            ),
            mapper.pointerUp(3, 40.0, 55.0, 3)
        )
    }

    @Test
    fun additionalFingerCannotStealOrDuplicateTheClick() {
        val mapper = TouchMouseInputMapper()
        mapper.pointerDown(1, 10.0, 10.0, 1)

        assertEquals(emptyList<TouchMouseInputMapper.Command>(),
            mapper.pointerDown(2, 80.0, 80.0, 2))
        assertEquals(emptyList<TouchMouseInputMapper.Command>(),
            mapper.pointerUp(2, 80.0, 80.0, 3))
        assertEquals(1, mapper.activePointerId)
        assertEquals(
            listOf(
                TouchMouseInputMapper.Command.PointerMotion(10.0, 10.0, 4),
                TouchMouseInputMapper.Command.PointerButton(0x110, false, 4)
            ),
            mapper.pointerUp(1, 10.0, 10.0, 4)
        )
    }

    @Test
    fun cancelAlwaysReleasesHeldClickAndIsIdempotent() {
        val mapper = TouchMouseInputMapper()
        mapper.pointerDown(4, 1.0, 2.0, 10)

        assertEquals(
            listOf(TouchMouseInputMapper.Command.PointerButton(0x110, false, 11)),
            mapper.cancel(11)
        )
        assertNull(mapper.activePointerId)
        assertEquals(emptyList<TouchMouseInputMapper.Command>(), mapper.cancel(12))
    }

    @Test
    fun movesAndReleasesFromUnknownPointersAreIgnored() {
        val mapper = TouchMouseInputMapper()
        assertEquals(emptyList<TouchMouseInputMapper.Command>(),
            mapper.pointerMove(1, 1.0, 2.0, 1))
        assertEquals(emptyList<TouchMouseInputMapper.Command>(),
            mapper.pointerUp(1, 1.0, 2.0, 2))
    }
}
