package dev.ramenwine.runtime

import dev.ramenwine.display.GameDisplaySession
import dev.ramenwine.display.WaylandDisplayEndpoint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySessionLeaseTest {
    @Test
    fun closeIsIdempotentAndResizeIsRejectedAfterClose() {
        val fake = FakeDisplaySession()
        val lease = DisplaySessionLease(fake)

        lease.resize(1280, 720)
        lease.close()
        lease.close()

        assertEquals(listOf(1280 to 720), fake.sizes)
        assertEquals(1, fake.closeCount)
        assertTrue(fake.closed)
        assertFalse(lease.closeFailure != null)
        try {
            lease.resize(1920, 1080)
            throw AssertionError("Resize should fail after display shutdown.")
        } catch (expected: IllegalStateException) {
            assertEquals("The game display session is already closed.", expected.message)
        }
    }

    @Test
    fun closeFailureIsRecordedWithoutEscapingOrRepeatingClose() {
        val failure = IllegalStateException("backend shutdown failed")
        val fake = FakeDisplaySession(closeError = failure)
        val lease = DisplaySessionLease(fake)

        lease.close()
        lease.close()

        assertEquals(1, fake.closeCount)
        assertSame(failure, lease.closeFailure)
    }

    private class FakeDisplaySession(
        private val closeError: Throwable? = null
    ) : GameDisplaySession {
        override val endpoint = WaylandDisplayEndpoint(File("/unused"), "wayland-test")
        val sizes = mutableListOf<Pair<Int, Int>>()
        var closeCount = 0
            private set
        var closed = false
            private set

        override fun resize(widthPx: Int, heightPx: Int) {
            check(!closed)
            sizes += widthPx to heightPx
        }

        override fun close() {
            closeCount += 1
            closed = true
            closeError?.let { throw it }
        }
    }
}
