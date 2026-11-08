package dev.ramenwine.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeGamePathTest {
    @Test
    fun acceptsNestedWindowsGamePath() {
        assertEquals(
            "data/scenario/start.exe",
            SafeGamePath.normalizeRelativePath("data/scenario/start.exe")
        )
    }

    @Test
    fun rejectsTraversalSegments() {
        assertNull(SafeGamePath.normalizeRelativePath("../outside.exe"))
        assertNull(SafeGamePath.normalizeRelativePath("data/../../outside.exe"))
    }

    @Test
    fun rejectsWindowsDeviceNames() {
        assertNull(SafeGamePath.validateSegment("CON"))
        assertNull(SafeGamePath.validateSegment("nul.txt"))
        assertNull(SafeGamePath.validateSegment("LPT1.log"))
    }

    @Test
    fun rejectsSeparatorsAndInvalidCharactersInsideSegments() {
        assertNull(SafeGamePath.validateSegment("nested/file.exe"))
        assertNull(SafeGamePath.validateSegment("name?.exe"))
        assertNull(SafeGamePath.validateSegment("trailing."))
        assertNull(SafeGamePath.validateSegment("trailing "))
    }

    @Test
    fun rejectsAbsolutePaths() {
        assertNull(SafeGamePath.normalizeRelativePath("/root/game.exe"))
        assertNull(SafeGamePath.normalizeRelativePath("\\root\\game.exe"))
    }
}
