package dev.ramenwine.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeRuntimePathTest {
    @Test
    fun absoluteRootfsSymlinkIsRewrittenRelativeToGuestRoot() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val actual = SafeRuntimePath.normalizeSymlinkTarget(
                runtimeRoot = root,
                linkRelativePath = "rootfs/usr/bin/wine",
                target = "/usr/lib/wine/wine"
            )
            assertEquals("../lib/wine/wine", actual)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rootfsSymlinkCannotEscapeIntoHostPayload() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val actual = SafeRuntimePath.normalizeSymlinkTarget(
                runtimeRoot = root,
                linkRelativePath = "rootfs/usr/bin/wine",
                target = "/host/bin/proot"
            )
            assertNull(actual)
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun relativeDotSymlinkToCurrentDirectoryIsPreserved() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val actual = SafeRuntimePath.normalizeSymlinkTarget(
                runtimeRoot = root,
                linkRelativePath = "rootfs/usr/bin/X11",
                target = "."
            )
            assertEquals(".", actual)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absoluteRootfsSymlinkToItsParentIsPreservedAsDot() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val actual = SafeRuntimePath.normalizeSymlinkTarget(
                runtimeRoot = root,
                linkRelativePath = "rootfs/usr/bin/X11",
                target = "/usr/bin"
            )
            assertEquals(".", actual)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absoluteHostSymlinkIsRejected() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val actual = SafeRuntimePath.normalizeSymlinkTarget(
                runtimeRoot = root,
                linkRelativePath = "host/bin/proot",
                target = "/system/bin/linker64"
            )
            assertNull(actual)
        } finally {
            root.deleteRecursively()
        }
    }
}
