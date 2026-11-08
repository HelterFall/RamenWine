package dev.ramenwine.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeHeaderInspectorTest {
    @Test
    fun detectsX86Pe() {
        val result = PeHeaderInspector.inspect(peFixture(machine = 0x014C))
        assertNotNull(result)
        assertTrue(result!!.isValidPe)
        assertEquals(PeArchitecture.X86, result.architecture)
    }

    @Test
    fun detectsX64Pe() {
        val result = PeHeaderInspector.inspect(peFixture(machine = 0x8664))
        assertNotNull(result)
        assertEquals(PeArchitecture.X86_64, result!!.architecture)
    }

    @Test
    fun ignoresNonMzFiles() {
        assertNull(PeHeaderInspector.inspect(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun reportsTruncatedDosHeaderWithoutThrowing() {
        val result = PeHeaderInspector.inspect(byteArrayOf(0x4D, 0x5A))
        assertNotNull(result)
        assertFalse(result!!.isValidPe)
        assertEquals(PeArchitecture.UNKNOWN, result.architecture)
    }

    @Test
    fun rejectsOutOfRangePeOffset() {
        val bytes = peFixture(machine = 0x8664)
        bytes[0x3C] = 0x7F
        bytes[0x3D] = 0xFF.toByte()
        bytes[0x3E] = 0xFF.toByte()
        bytes[0x3F] = 0x7F

        val result = PeHeaderInspector.inspect(bytes)
        assertNotNull(result)
        assertFalse(result!!.isValidPe)
        assertTrue(result.diagnostic!!.contains("offset"))
    }

    private fun peFixture(machine: Int, peOffset: Int = 0x80): ByteArray {
        val bytes = ByteArray(512)
        bytes[0] = 0x4D
        bytes[1] = 0x5A

        bytes[0x3C] = (peOffset and 0xFF).toByte()
        bytes[0x3D] = ((peOffset ushr 8) and 0xFF).toByte()
        bytes[0x3E] = ((peOffset ushr 16) and 0xFF).toByte()
        bytes[0x3F] = ((peOffset ushr 24) and 0xFF).toByte()

        bytes[peOffset] = 0x50
        bytes[peOffset + 1] = 0x45
        bytes[peOffset + 2] = 0
        bytes[peOffset + 3] = 0
        bytes[peOffset + 4] = (machine and 0xFF).toByte()
        bytes[peOffset + 5] = ((machine ushr 8) and 0xFF).toByte()
        return bytes
    }
}
