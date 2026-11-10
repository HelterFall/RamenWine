package dev.ramenwine.library

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class GameStorageUsage(
    val gameFilesBytes: Long = 0L,
    val winePrefixBytes: Long = 0L,
    val wineHomeBytes: Long = 0L,
    val temporaryBytes: Long = 0L
) {
    val wineDataBytes: Long
        get() = saturatedAdd(winePrefixBytes, wineHomeBytes)

    val totalBytes: Long
        get() = saturatedAdd(
            saturatedAdd(gameFilesBytes, wineDataBytes),
            temporaryBytes
        )
}

data class StorageCleanupResult(
    val removedEntries: Int,
    val reclaimedBytes: Long
)

/**
 * Owns only app-created per-game directories. Imported games, Wine prefixes,
 * Wine HOME data and temporary files are deliberately kept separate from the
 * one shared runtime installation.
 */
class GameStorageManager(
    private val filesDirectory: File,
    private val cacheDirectory: File,
    private val removeFromLibrary: (String) -> Unit
) {
    fun usage(game: ImportedGame): GameStorageUsage {
        requireValidId(game.id)
        return GameStorageUsage(
            gameFilesBytes = directorySize(File(filesDirectory, "games/${game.id}")),
            winePrefixBytes = directorySize(File(filesDirectory, "prefixes/${game.id}")),
            wineHomeBytes = directorySize(File(filesDirectory, "wine-home/${game.id}")),
            temporaryBytes = saturatedAdd(
                directorySize(File(cacheDirectory, "wine-tmp/${game.id}")),
                directorySize(File(cacheDirectory, "proot-tmp/${game.id}"))
            )
        )
    }

    /** Runtime files are shared by all games and are not removed with a game. */
    fun sharedRuntimeBytes(): Long =
        directorySize(File(filesDirectory, "runtime"))

    /**
     * Removes the imported files and all per-game writable data. The library
     * entry is removed last, so a partial filesystem failure remains retryable.
     */
    fun deleteGame(game: ImportedGame) {
        requireValidId(game.id)
        deleteOwnedEntry(File(filesDirectory, "games"), game.id)
        deleteOwnedEntry(File(filesDirectory, "prefixes"), game.id)
        deleteOwnedEntry(File(filesDirectory, "wine-home"), game.id)
        deleteOwnedEntry(File(cacheDirectory, "wine-tmp"), game.id)
        deleteOwnedEntry(File(cacheDirectory, "proot-tmp"), game.id)
        removeFromLibrary(game.id)
    }

    /**
     * Repairs abandoned imports left by process death and removes per-game
     * directories that no longer have a matching library record. Call only
     * during app startup, before accepting a new import.
     */
    fun cleanupAbandonedData(registeredGameIds: Set<String>): StorageCleanupResult {
        val gamesRoot = File(filesDirectory, "games")
        val registered = registeredGameIds.filter { ID_PATTERN.matches(it) }.toSet()
        val validRegistered = registered.filterTo(mutableSetOf()) { id ->
            val path = File(gamesRoot, id).toPath()
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path)
        }

        var removed = 0
        var reclaimed = 0L

        // A library record without its imported folder cannot be launched.
        // Remove its remaining private data too, rather than leave orphaned saves.
        (registered - validRegistered).forEach { id ->
            listOf(
                File(filesDirectory, "games"),
                File(filesDirectory, "prefixes"),
                File(filesDirectory, "wine-home"),
                File(cacheDirectory, "wine-tmp"),
                File(cacheDirectory, "proot-tmp")
            ).forEach { parent ->
                val child = File(parent, id)
                if (existsWithoutFollowingLinks(child)) {
                    reclaimed = saturatedAdd(reclaimed, directorySize(child))
                    deleteOwnedEntry(parent, id)
                    removed++
                }
            }
            removeFromLibrary(id)
        }

        val gameOrphans = cleanOrphans(
            parent = gamesRoot,
            retainedIds = validRegistered,
            removeImportStaging = true
        )
        removed += gameOrphans.removedEntries
        reclaimed = saturatedAdd(reclaimed, gameOrphans.reclaimedBytes)

        listOf(
            File(filesDirectory, "prefixes"),
            File(filesDirectory, "wine-home"),
            File(cacheDirectory, "wine-tmp"),
            File(cacheDirectory, "proot-tmp")
        ).forEach { parent ->
            val orphans = cleanOrphans(parent, validRegistered, false)
            removed += orphans.removedEntries
            reclaimed = saturatedAdd(reclaimed, orphans.reclaimedBytes)
        }

        return StorageCleanupResult(removed, reclaimed)
    }

    private data class CleanupDelta(
        val removedEntries: Int = 0,
        val reclaimedBytes: Long = 0L
    )

    private fun cleanOrphans(
        parent: File,
        retainedIds: Set<String>,
        removeImportStaging: Boolean
    ): CleanupDelta {
        if (!parent.isDirectory || Files.isSymbolicLink(parent.toPath())) {
            return CleanupDelta()
        }
        var removed = 0
        var reclaimed = 0L
        val children = parent.listFiles() ?: return CleanupDelta()
        for (child in children) {
            val isOrphanId = ID_PATTERN.matches(child.name) && child.name !in retainedIds
            val isAbandonedImport = removeImportStaging &&
                STAGING_PATTERN.matches(child.name)
            if (isOrphanId || isAbandonedImport) {
                reclaimed = saturatedAdd(reclaimed, directorySize(child))
                deleteOwnedEntry(parent, child.name)
                removed++
            }
        }
        return CleanupDelta(removed, reclaimed)
    }

    private fun directorySize(entry: File): Long {
        val root = entry.toPath()
        if (!existsWithoutFollowingLinks(entry)) return 0L
        var total = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult {
                if (attributes.isRegularFile || attributes.isSymbolicLink) {
                    total = saturatedAdd(total, attributes.size())
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                error: IOException
            ): FileVisitResult {
                throw error
            }
        })
        return total
    }

    /**
     * Deletes a single direct child without following symlinks inside it.
     * Child names are restricted to UUIDs or the import-staging pattern.
     */
    private fun deleteOwnedEntry(parent: File, childName: String) {
        if (!ID_PATTERN.matches(childName) && !STAGING_PATTERN.matches(childName)) {
            throw IOException("Refusing to delete an unrecognized game-storage path.")
        }
        if (!parent.isDirectory || Files.isSymbolicLink(parent.toPath())) return

        val parentPath = parent.toPath().toAbsolutePath().normalize()
        val childPath = parentPath.resolve(childName).normalize()
        if (childPath.parent != parentPath) {
            throw IOException("Game-storage deletion escaped its parent directory.")
        }
        if (!Files.exists(childPath, LinkOption.NOFOLLOW_LINKS)) return

        Files.walkFileTree(childPath, object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: Path,
                error: IOException?
            ): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                error: IOException
            ): FileVisitResult {
                throw error
            }
        })
    }

    private fun existsWithoutFollowingLinks(entry: File): Boolean =
        Files.exists(entry.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun requireValidId(id: String) {
        require(ID_PATTERN.matches(id)) { "Invalid game ID." }
    }

    private companion object {
        val ID_PATTERN = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        val STAGING_PATTERN =
            Regex("\\.[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.importing")
    }
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE
    else if (right < 0L && left < Long.MIN_VALUE - right) Long.MIN_VALUE
    else left + right
