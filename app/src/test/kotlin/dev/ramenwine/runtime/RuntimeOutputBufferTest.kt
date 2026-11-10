package dev.ramenwine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeOutputBufferTest {
    @Test
    fun retainsOnlyTheNewestConfiguredNumberOfLines() {
        val buffer = RuntimeOutputBuffer(maxLines = 3, maxLineCharacters = 20, maxCharacters = 100)

        (1..5).forEach { buffer.append("line-$it") }

        assertEquals(listOf("line-3", "line-4", "line-5"), buffer.snapshot())
    }

    @Test
    fun enforcesTheAggregateCharacterLimit() {
        val buffer = RuntimeOutputBuffer(maxLines = 10, maxLineCharacters = 20, maxCharacters = 10)

        buffer.append("123456")
        buffer.append("abcdef")

        assertEquals(listOf("abcdef"), buffer.snapshot())
    }

    @Test
    fun truncatesAnOversizedIndividualLine() {
        val buffer = RuntimeOutputBuffer(maxLines = 10, maxLineCharacters = 4, maxCharacters = 20)

        buffer.append("abcdefgh")

        assertEquals(listOf("abcd"), buffer.snapshot())
    }

    @Test
    fun snapshotReturnsOnlyTheRequestedTailAndClearResetsTheBuffer() {
        val buffer = RuntimeOutputBuffer()
        buffer.append("first")
        buffer.append("second")
        buffer.append("third")

        assertEquals(listOf("second", "third"), buffer.snapshot(2))
        buffer.clear()

        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun rejectsInvalidBounds() {
        try {
            RuntimeOutputBuffer(maxLines = 0)
            throw AssertionError("A zero line limit must be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("at least one line"))
        }

        try {
            RuntimeOutputBuffer(maxCharacters = 0)
            throw AssertionError("A zero character limit must be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size limit"))
        }
    }
}
