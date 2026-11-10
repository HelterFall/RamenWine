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
    fun acceptsOnlyNonEmptyNativeLibraryFiles() {
        val directory = Files.createTempDirectory("ramenwine-wawona-libs").toFile()
        try {
            WawonaLibraryInventory.requiredFileNames.forEach { name ->
                File(directory, name).writeBytes(byteArrayOf(1))
            }

            assertTrue(WawonaLibraryInventory.missingFiles(directory).isEmpty())

            File(directory, "libwawona.so").writeBytes(byteArrayOf())
            assertEquals(
                listOf("libwawona.so"),
                WawonaLibraryInventory.missingFiles(directory)
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
