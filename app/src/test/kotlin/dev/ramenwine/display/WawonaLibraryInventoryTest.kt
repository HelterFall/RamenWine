package dev.ramenwine.display

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WawonaLibraryInventoryTest {
    @Test
    fun reportsTheNativeLibrariesRequiredByThePinnedAndroidHost() {
        val directory = Files.createTempDirectory("ramenwine-wawona-libs").toFile()
        try {
            val missing = WawonaLibraryInventory.missingFiles(directory)
            assertEquals(WawonaLibraryInventory.requiredFileNames, missing)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun checksTheWholeLibraryClosureRecordedInThePackageManifest() {
        val directory = Files.createTempDirectory("ramenwine-wawona-libs").toFile()
        try {
            val hashes = linkedMapOf(
                "libEGL_angle.so" to "0".repeat(64),
                "libGLESv2_angle.so" to "0".repeat(64),
                "libwawona.so" to "0".repeat(64),
                "libwayland-server.so" to "0".repeat(64)
            )
            hashes.keys.forEach { File(directory, it).writeBytes(byteArrayOf(1)) }

            assertTrue(WawonaLibraryInventory.missingFiles(directory, hashes).isEmpty())
            File(directory, "libwayland-server.so").delete()
            assertEquals(
                listOf("libwayland-server.so"),
                WawonaLibraryInventory.missingFiles(directory, hashes)
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
