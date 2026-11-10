package dev.ramenwine.display

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WawonaLibraryInventoryTest {
    @Test
    fun reportsRequiredNativeLibrariesMissingFromAnIncompleteManifest() {
        val directory = Files.createTempDirectory("ramenwine-wawona-libs").toFile()
        try {
            val apk = File(directory, "app.apk")
            writeApk(apk, emptyMap())
            assertEquals(
                WawonaLibraryInventory.requiredFileNames,
                WawonaLibraryInventory.missingFromApk(apk, emptyMap())
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun verifiesAllNativeLibrariesInsideTheApkAgainstTheirSha256Hashes() {
        val directory = Files.createTempDirectory("ramenwine-wawona-libs").toFile()
        try {
            val apk = File(directory, "app.apk")
            val payload = linkedMapOf(
                "libEGL_angle.so" to "egl".toByteArray(),
                "libGLESv2_angle.so" to "gles".toByteArray(),
                "libwawona.so" to "wawona".toByteArray(),
                "libwayland-server.so" to "wayland".toByteArray()
            )
            writeApk(apk, payload)
            val hashes = payload.mapValues { sha256(it.value) }

            assertTrue(
                WawonaLibraryInventory.missingFromApk(apk, hashes).isEmpty()
            )

            val wrongHash = hashes + ("libwawona.so" to sha256("not-wawona".toByteArray()))
            assertEquals(
                listOf("libwawona.so"),
                WawonaLibraryInventory.missingFromApk(apk, wrongHash)
            )

            val missingPayload = payload - "libwayland-server.so"
            writeApk(apk, missingPayload)
            assertEquals(
                listOf("libwayland-server.so"),
                WawonaLibraryInventory.missingFromApk(apk, hashes)
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeApk(file: File, libraries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for ((name, bytes) in libraries) {
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
