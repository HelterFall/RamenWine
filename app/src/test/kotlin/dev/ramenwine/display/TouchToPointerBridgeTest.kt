package dev.ramenwine.display

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchToPointerBridgeTest {
    @Test
    fun fingerTapBecomesOneLeftMouseClick() {
        val sink = RecordingPointerSink()
        val bridge = TouchToPointerBridge(sink)

        bridge.down(pointerId = 7, x = 10.0, y = 20.0, timestampMs = 100)
        bridge.up(pointerId = 7, x = 11.0, y = 21.0, timestampMs = 130)

        assertEquals(
            listOf(
                "enter:10.0:20.0:100",
                "button:272:1:100",
                "motion:11.0:21.0:130",
                "button:272:0:130"
            ),
            sink.events
        )
    }

    @Test
    fun onlyTheFirstPressedFingerControlsThePointer() {
        val sink = RecordingPointerSink()
        val bridge = TouchToPointerBridge(sink)

        bridge.down(1, 10.0, 20.0, 100)
        bridge.down(2, 90.0, 90.0, 101)
        bridge.move(2, 90.0, 91.0, 110)
        bridge.move(1, 12.0, 22.0, 111)
        bridge.up(2, 90.0, 92.0, 120)
        bridge.up(1, 13.0, 23.0, 121)

        assertEquals(
            listOf(
                "enter:10.0:20.0:100",
                "button:272:1:100",
                "motion:12.0:22.0:111",
                "motion:13.0:23.0:121",
                "button:272:0:121"
            ),
            sink.events
        )
    }

    @Test
    fun cancelAlwaysReleasesTheMouseButtonAndLeavesTheSurface() {
        val sink = RecordingPointerSink()
        val bridge = TouchToPointerBridge(sink)

        bridge.down(1, 2.0, 3.0, 10)
        bridge.cancel(20)
        bridge.cancel(30)

        assertEquals(
            listOf(
                "enter:2.0:3.0:10",
                "button:272:1:10",
                "button:272:0:20",
                "leave:20"
            ),
            sink.events
        )
    }

    private class RecordingPointerSink : WaylandPointerSink {
        val events = mutableListOf<String>()

        override fun enter(x: Double, y: Double, timestampMs: Int) {
            events += "enter:$x:$y:$timestampMs"
        }

        override fun motion(x: Double, y: Double, timestampMs: Int) {
            events += "motion:$x:$y:$timestampMs"
        }

        override fun button(buttonCode: Int, state: Int, timestampMs: Int) {
            events += "button:$buttonCode:$state:$timestampMs"
        }

        override fun leave(timestampMs: Int) {
            events += "leave:$timestampMs"
        }
    }
}
