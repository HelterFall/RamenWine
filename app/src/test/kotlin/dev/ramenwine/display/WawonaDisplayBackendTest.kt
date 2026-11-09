package dev.ramenwine.display

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WawonaDisplayBackendTest {
    @Test
    fun processGuardAllowsOnlyOneActiveSession() {
        val first = WawonaProcessSessionGuard.acquire()
        try {
            try {
                WawonaProcessSessionGuard.acquire()
                throw AssertionError("A second Wawona session must be rejected.")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("one active"))
            }
        } finally {
            WawonaProcessSessionGuard.release(first)
        }

        val second = WawonaProcessSessionGuard.acquire()
        assertNotSame(first, second)
        WawonaProcessSessionGuard.release(second)
    }

    @Test
    fun processGuardSerializesCallsForTheActiveLease() {
        val lease = WawonaProcessSessionGuard.acquire()
        try {
            val result = WawonaProcessSessionGuard.withLease(lease) { "ok" }
            assertEquals("ok", result)

            val other = Any()
            try {
                WawonaProcessSessionGuard.withLease(other) { Unit }
                throw AssertionError("A foreign lease must be rejected.")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("no longer active"))
            }
        } finally {
            WawonaProcessSessionGuard.release(lease)
        }

        try {
            WawonaProcessSessionGuard.withLease(lease) { Unit }
            throw AssertionError("A released lease must be rejected.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("no longer active"))
        }
    }

    @Test
    fun runIfActiveDispatchesOnlyForCurrentLease() {
        val first = WawonaProcessSessionGuard.acquire()
        var calls = 0
        try {
            assertTrue(WawonaProcessSessionGuard.runIfActive(first) { calls += 1 })
        } finally {
            WawonaProcessSessionGuard.release(first)
        }

        val second = WawonaProcessSessionGuard.acquire()
        try {
            val staleDispatched = WawonaProcessSessionGuard.runIfActive(first) {
                calls += 100
            }
            val currentDispatched = WawonaProcessSessionGuard.runIfActive(second) {
                calls += 1
            }
            assertTrue(!staleDispatched)
            assertTrue(currentDispatched)
            assertEquals(2, calls)
        } finally {
            WawonaProcessSessionGuard.release(second)
        }
    }

    @Test
    fun closeLeaseIsIdempotentAndReleasesAfterSuccess() {
        val lease = WawonaProcessSessionGuard.acquire()
        var closeCalls = 0

        WawonaProcessSessionGuard.closeLease(lease) {
            closeCalls += 1
        }
        WawonaProcessSessionGuard.closeLease(lease) {
            closeCalls += 1
        }

        assertEquals(1, closeCalls)
        val nextLease = WawonaProcessSessionGuard.acquire()
        WawonaProcessSessionGuard.release(nextLease)
    }

    @Test
    fun closeLeaseReleasesEvenWhenNativeCleanupThrows() {
        val lease = WawonaProcessSessionGuard.acquire()
        val failure = IllegalStateException("native shutdown failed")
        try {
            WawonaProcessSessionGuard.closeLease(lease) {
                throw failure
            }
            throw AssertionError("The native cleanup failure must propagate.")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }

        val nextLease = WawonaProcessSessionGuard.acquire()
        WawonaProcessSessionGuard.release(nextLease)
    }

    @Test
    fun staleCloseCannotReleaseAnotherActiveSession() {
        val first = WawonaProcessSessionGuard.acquire()
        WawonaProcessSessionGuard.release(first)
        val second = WawonaProcessSessionGuard.acquire()

        WawonaProcessSessionGuard.closeLease(first) {
            throw AssertionError("A stale lease must not execute cleanup.")
        }

        try {
            WawonaProcessSessionGuard.acquire()
            throw AssertionError("The second session must remain active.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("one active"))
        } finally {
            WawonaProcessSessionGuard.release(second)
        }
    }

    @Test
    fun runtimeDirectoryIsResolvedInsideCanonicalCacheRoot() {
        val base = Files.createTempDirectory("ramenwine-cache").toFile()
        try {
            val alias = File(base, "cache-alias")
            assertTrue(alias.mkdir())
            val runtime = WawonaRuntimePaths.resolveRuntimeDirectory(alias)
            assertEquals(alias.canonicalFile, runtime.parentFile)
            assertEquals("wawona-runtime", runtime.name)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun runtimeDirectoryRejectsSymlinkEscape() {
        val base = Files.createTempDirectory("ramenwine-cache").toFile()
        val cache = File(base, "cache")
        val outside = File(base, "outside")
        try {
            assertTrue(cache.mkdir())
            assertTrue(outside.mkdir())
            Files.createSymbolicLink(
                File(cache, "wawona-runtime").toPath(),
                outside.toPath()
            )
            try {
                WawonaRuntimePaths.resolveRuntimeDirectory(cache)
                throw AssertionError("A cache symlink escape must be rejected.")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("escapes"))
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun runtimeDirectoryRejectsUpstreamSocketPathFallback() {
        val base = Files.createTempDirectory("ramenwine-cache").toFile()
        try {
            val longCache = File(base, "c".repeat(88))
            assertTrue(longCache.mkdirs())
            try {
                WawonaRuntimePaths.resolveRuntimeDirectory(longCache)
                throw AssertionError("An overlong AF_UNIX path must be rejected.")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("AF_UNIX"))
                assertTrue(expected.message!!.contains("/data/local/tmp"))
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun verifiesExactXkbFontAndWestonAssetHashes() {
        val root = Files.createTempDirectory("ramenwine-wawona-assets").toFile()
        try {
            val assetContents = mapOf(
                "wawona/xkb/rules/evdev" to "rules",
                "wawona/fonts/truetype/test.ttf" to "font",
                "wawona/weston/sign_close.png" to "decoration"
            )
            val hashes = assetContents.mapValues { (path, content) ->
                writeIntegrityFixture(root, path, content)
                integritySha256(content)
            }

            assertTrue(WawonaAssetIntegrity.matches(root, hashes))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsMissingAndModifiedWawonaAssets() {
        val root = Files.createTempDirectory("ramenwine-wawona-assets").toFile()
        try {
            val asset = File(root, "usr/share/X11/xkb/rules/evdev")
            asset.parentFile.mkdirs()
            asset.writeText("original")
            val hashes = mapOf("wawona/xkb/rules/evdev" to integritySha256("original"))

            assertTrue(WawonaAssetIntegrity.matches(root, hashes))
            asset.writeText("modified")
            assertFalse(WawonaAssetIntegrity.matches(root, hashes))
            asset.delete()
            assertFalse(WawonaAssetIntegrity.matches(root, hashes))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalUnknownRootsAndMalformedHashes() {
        val root = Files.createTempDirectory("ramenwine-wawona-assets").toFile()
        try {
            assertFalse(WawonaAssetIntegrity.matches(root, emptyMap()))
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
            assertFalse(
                WawonaAssetIntegrity.matches(
                    root,
                    mapOf("wawona/xkb/rules/evdev" to "not-a-sha256")
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
                    mapOf("wawona/xkb/rules/evdev" to integritySha256("outside"))
                )
            )
        } finally {
            base.deleteRecursively()
        }
    }

    private fun writeIntegrityFixture(root: File, assetPath: String, content: String) {
        val relativePath = when {
            assetPath.startsWith("wawona/xkb/") ->
                "usr/share/X11/xkb/" + assetPath.removePrefix("wawona/xkb/")
            assetPath.startsWith("wawona/fonts/") ->
                "usr/share/fonts/" + assetPath.removePrefix("wawona/fonts/")
            assetPath.startsWith("wawona/weston/") ->
                "usr/share/weston/" + assetPath.removePrefix("wawona/weston/")
            else -> error("Unexpected Wawona asset path in test fixture.")
        }
        File(root, relativePath).apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private fun integritySha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

}
