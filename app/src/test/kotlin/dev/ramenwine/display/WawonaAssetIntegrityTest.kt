package dev.ramenwine.display

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WawonaAssetIntegrityTest {
    @Test
    fun acceptsExactXkbFontAndWestonAssets() {
        val root = Files.createTempDirectory("ramenwine-wawona-assets").toFile()
        try {
            val assets = mapOf(
                "wawona/xkb/rules/evdev" to "rules",
                "wawona/fonts/truetype/test.ttf" to "font",
                "wawona/weston/sign_close.png" to "decoration"
            )
            val hashes = assets.mapValues { (assetPath, content) ->
                writeAsset(root, assetPath, content)
                sha256(content)
            }

            assertTrue(WawonaAssetIntegrity.matches(root, hashes))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsMissingOrModifiedAsset() {
        val root = Files.createTempDirectory("ramenwine-wawona-assets").toFile()
        try {
            val asset = File(root, "usr/share/X11/xkb/rules/evdev")
            asset.parentFile.mkdirs()
            asset.writeText("original")
            val expected = mapOf("wawona/xkb/rules/evdev" to sha256("original"))

            assertTrue(WawonaAssetIntegrity.matches(root, expected))
            asset.writeText("modified")
            assertFalse(WawonaAssetIntegrity.matches(root, expected))
            asset.delete()
            assertFalse(WawonaAssetIntegrity.matches(root, expected))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalAndUnknownAssetRoots() {
        val root = Files.createTempDirectory("ramenwine-wawona-assets").toFile()
        try {
            assertFalse(
                WawonaAssetIntegrity.matches(
                    root,
                    mapOf("../../outside" to "0".repeat(64))
                )
            )
            assertFalse(
                WawonaAssetIntegrity.matches(
                    root,
                    mapOf("wawona/lib/libwawona.so" to "0".repeat(64))
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsAssetSymlinkThatEscapesWawonaRootfs() {
        val base = Files.createTempDirectory("ramenwine-wawona-assets").toFile()
        val root = File(base, "rootfs")
        val outside = File(base, "outside")
        try {
            val link = File(root, "usr/share/X11/xkb/rules/evdev")
            link.parentFile.mkdirs()
            outside.writeText("outside")
            Files.createSymbolicLink(link.toPath(), outside.toPath())

            assertFalse(
                WawonaAssetIntegrity.matches(
                    root,
                    mapOf("wawona/xkb/rules/evdev" to sha256("outside"))
                )
            )
        } finally {
            base.deleteRecursively()
        }
    }

    private fun writeAsset(root: File, assetPath: String, content: String): File {
        val relative = when {
            assetPath.startsWith("wawona/xkb/") ->
                "usr/share/X11/xkb/" + assetPath.removePrefix("wawona/xkb/")
            assetPath.startsWith("wawona/fonts/") ->
                "usr/share/fonts/" + assetPath.removePrefix("wawona/fonts/")
            assetPath.startsWith("wawona/weston/") ->
                "usr/share/weston/" + assetPath.removePrefix("wawona/weston/")
            else -> error("unexpected fixture asset path")
        }
        return File(root, relative).apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
