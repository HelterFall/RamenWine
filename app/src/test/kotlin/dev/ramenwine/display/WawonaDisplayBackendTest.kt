package dev.ramenwine.display

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
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
}
