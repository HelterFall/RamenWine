package dev.ramenwine.library

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStorageManagerTest {
    private val gameId = "12345678-1234-1234-1234-123456789abc"

    @Test
    fun usageCountsImportedFilesWineDataAndTemporaryFiles() {
        val root = Files.createTempDirectory("ramenwine-storage").toFile()
        try {
            val cache = File(root, "cache").apply { mkdirs() }
            write(File(root, "games/$gameId/game.exe"), 11)
            write(File(root, "prefixes/$gameId/drive_c/users/ramen/save.dat"), 23)
            write(File(root, "wine-home/$gameId/config.ini"), 7)
            write(File(cache, "wine-tmp/$gameId/temp.bin"), 5)
            write(File(cache, "proot-tmp/$gameId/proot.tmp"), 3)

            val manager = GameStorageManager(root, cache) {}
            val usage = manager.usage(game())

            assertEquals(11L, usage.gameFilesBytes)
            assertEquals(23L, usage.winePrefixBytes)
            assertEquals(7L, usage.wineHomeBytes)
            assertEquals(8L, usage.temporaryBytes)
            assertEquals(49L, usage.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteGameRemovesItsFilesSavesAndCachesButKeepsSharedRuntime() {
        val root = Files.createTempDirectory("ramenwine-storage").toFile()
        try {
            val cache = File(root, "cache").apply { mkdirs() }
            write(File(root, "games/$gameId/game.exe"), 11)
            write(File(root, "prefixes/$gameId/save.dat"), 23)
            write(File(root, "wine-home/$gameId/settings.ini"), 7)
            write(File(cache, "wine-tmp/$gameId/temp.bin"), 5)
            write(File(cache, "proot-tmp/$gameId/proot.tmp"), 3)
            write(File(root, "runtime/backends/shared/runtime.bin"), 101)
            val removed = mutableListOf<String>()

            val manager = GameStorageManager(root, cache, removed::add)
            manager.deleteGame(game())

            assertFalse(File(root, "games/$gameId").exists())
            assertFalse(File(root, "prefixes/$gameId").exists())
            assertFalse(File(root, "wine-home/$gameId").exists())
            assertFalse(File(cache, "wine-tmp/$gameId").exists())
            assertFalse(File(cache, "proot-tmp/$gameId").exists())
            assertTrue(File(root, "runtime/backends/shared/runtime.bin").isFile)
            assertEquals(listOf(gameId), removed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun startupCleanupRemovesOrphansAndAbandonedImportStagesOnly() {
        val root = Files.createTempDirectory("ramenwine-storage").toFile()
        try {
            val cache = File(root, "cache").apply { mkdirs() }
            val registeredId = gameId
            val orphanId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            write(File(root, "games/$registeredId/game.exe"), 2)
            write(File(root, "prefixes/$registeredId/save.dat"), 3)
            write(File(root, "games/$orphanId/game.exe"), 5)
            write(File(root, "prefixes/$orphanId/save.dat"), 7)
            write(File(cache, "wine-tmp/$orphanId/temp.bin"), 11)
            write(File(root, "games/.$orphanId.importing/partial.bin"), 13)
            write(File(root, "runtime/shared.bin"), 17)
            val removed = mutableListOf<String>()

            val manager = GameStorageManager(root, cache, removed::add)
            val result = manager.cleanupAbandonedData(setOf(registeredId))

            assertTrue(result.removedEntries >= 3)
            assertFalse(File(root, "games/$orphanId").exists())
            assertFalse(File(root, "prefixes/$orphanId").exists())
            assertFalse(File(cache, "wine-tmp/$orphanId").exists())
            assertFalse(File(root, "games/.$orphanId.importing").exists())
            assertTrue(File(root, "games/$registeredId/game.exe").isFile)
            assertTrue(File(root, "runtime/shared.bin").isFile)
            assertTrue(removed.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deletingGameDoesNotFollowSymlinksOutsideItsDirectory() {
        val root = Files.createTempDirectory("ramenwine-storage").toFile()
        val outside = Files.createTempDirectory("ramenwine-outside").toFile()
        try {
            val cache = File(root, "cache").apply { mkdirs() }
            val externalFile = File(outside, "keep.dat").apply { writeText("keep") }
            val link = File(root, "prefixes/$gameId/external")
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), outside.toPath())

            GameStorageManager(root, cache) {}.deleteGame(game())

            assertTrue(externalFile.isFile)
            assertFalse(link.exists())
            assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private fun game() = ImportedGame(
        id = gameId,
        title = "Test game",
        relativeDirectory = "games/$gameId",
        executableRelativePath = "game.exe",
        architecture = "x86-64",
        totalBytes = 11,
        importedAtEpochMillis = 1L
    )

    private fun write(file: File, byteCount: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(byteCount) { 1 })
    }
}
