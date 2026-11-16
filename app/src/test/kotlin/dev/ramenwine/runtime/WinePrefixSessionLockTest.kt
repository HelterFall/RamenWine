package dev.ramenwine.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class WinePrefixSessionLockTest {
    @Test
    fun preventsTwoSessionsFromUsingTheSameGamePrefix() {
        val files = Files.createTempDirectory("ramenwine-prefix-lock").toFile()
        try {
            val first = WinePrefixSessionLock.acquire(files, "game-123")
            try {
                val error = runCatching {
                    WinePrefixSessionLock.acquire(files, "game-123")
                }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue(error?.message.orEmpty().contains("already active"))
            } finally {
                first.close()
            }

            val afterRelease = WinePrefixSessionLock.acquire(files, "game-123")
            afterRelease.close()
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun allowsDifferentGamesToUseIndependentPrefixes() {
        val files = Files.createTempDirectory("ramenwine-prefix-locks").toFile()
        try {
            val first = WinePrefixSessionLock.acquire(files, "game-a")
            val second = WinePrefixSessionLock.acquire(files, "game-b")
            try {
                assertTrue(File(files, "wine-session-locks/game-a.lock").isFile)
                assertTrue(File(files, "wine-session-locks/game-b.lock").isFile)
            } finally {
                first.close()
                second.close()
            }
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun closeIsIdempotent() {
        val files = Files.createTempDirectory("ramenwine-prefix-lock-close").toFile()
        try {
            val lock = WinePrefixSessionLock.acquire(files, "game-123")
            lock.close()
            lock.close()
            val reacquired = WinePrefixSessionLock.acquire(files, "game-123")
            reacquired.close()
        } finally {
            files.deleteRecursively()
        }
    }
}
