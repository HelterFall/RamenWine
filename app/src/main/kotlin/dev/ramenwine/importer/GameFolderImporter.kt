package dev.ramenwine.importer

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import dev.ramenwine.inspection.PeHeaderInspector
import dev.ramenwine.inspection.SafeGamePath
import dev.ramenwine.library.GameLibraryStore
import dev.ramenwine.library.ImportedGame
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

data class ImportProgress(
    val copiedFiles: Int,
    val copiedBytes: Long
)

/**
 * Copies a SAF document tree into app-private storage using bounded streaming.
 * No source file is executed. A staged directory is renamed into place only
 * after the selected EXE has been copied and validated as a PE image.
 */
class GameFolderImporter(private val context: Context) {
    suspend fun importGame(
        treeUri: Uri,
        gameTitle: String,
        selectedExecutableRelativePath: String,
        onProgress: ((ImportProgress) -> Unit)? = null
    ): ImportedGame {
        val executablePath = SafeGamePath.normalizeRelativePath(
            selectedExecutableRelativePath
        ) ?: throw IOException("The selected EXE path is not a safe relative path.")

        if (!executablePath.endsWith(".exe", ignoreCase = true)) {
            throw IOException("The selected file does not have an .exe extension.")
        }

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Android no longer has access to the selected game folder.")

        if (!root.isDirectory) {
            throw IOException("The selected document is not a folder.")
        }

        val gamesRoot = File(context.filesDir, "games")
        if (!gamesRoot.exists() && !gamesRoot.mkdirs()) {
            throw IOException("Could not create the private games directory.")
        }

        val gameId = UUID.randomUUID().toString()
        val stagingDirectory = File(gamesRoot, ".$gameId.importing")
        val finalDirectory = File(gamesRoot, gameId)

        if (!stagingDirectory.mkdir()) {
            throw IOException("Could not create a temporary import directory.")
        }

        try {
            ensureFreeSpace(0L)
            val canonicalStaging = stagingDirectory.canonicalFile
            val canonicalPrefix = canonicalStaging.path + File.separator
            val queue = ArrayDeque<PendingDirectory>()
            val visitedPaths = HashSet<String>()
            var visitedEntries = 0
            var copiedFiles = 0
            var copiedBytes = 0L
            var lastStorageCheckBytes = 0L
            var selectedExecutableCopied = false

            queue.addLast(PendingDirectory(root, depth = 0, relativePath = ""))

            while (queue.isNotEmpty()) {
                coroutineContext.ensureActive()
                val current = queue.removeFirst()
                val children = try {
                    current.document.listFiles()
                } catch (error: Exception) {
                    throw IOException(
                        "Could not read folder " +
                            current.relativePath.ifBlank { root.name ?: "game" } + ".",
                        error
                    )
                }

                for (child in children) {
                    coroutineContext.ensureActive()
                    visitedEntries++
                    if (visitedEntries > MAX_ENTRIES) {
                        throw IOException("The game folder contains more than $MAX_ENTRIES entries.")
                    }

                    val name = SafeGamePath.validateSegment(child.name)
                        ?: throw IOException(
                            "The game contains a filename that cannot be represented safely in a Windows game folder."
                        )

                    val relativePath = if (current.relativePath.isEmpty()) {
                        name
                    } else {
                        current.relativePath + "/" + name
                    }

                    val pathKey = relativePath.lowercase(Locale.ROOT)
                    if (!visitedPaths.add(pathKey)) {
                        throw IOException(
                            "The game contains duplicate paths that differ only by letter case: " +
                                relativePath
                        )
                    }

                    val destination = File(stagingDirectory, relativePath).canonicalFile
                    if (!destination.path.startsWith(canonicalPrefix)) {
                        throw IOException("An unsafe destination path was rejected.")
                    }

                    when {
                        child.isDirectory -> {
                            if (current.depth >= MAX_DEPTH) {
                                throw IOException(
                                    "The game folder is nested deeper than $MAX_DEPTH directories."
                                )
                            }
                            if (!destination.exists() && !destination.mkdirs()) {
                                throw IOException("Could not create game subfolder: " + relativePath)
                            }
                            queue.addLast(
                                PendingDirectory(
                                    document = child,
                                    depth = current.depth + 1,
                                    relativePath = relativePath
                                )
                            )
                        }

                        child.isFile -> {
                            val parent = destination.parentFile
                            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                                throw IOException(
                                    "Could not create a parent directory for " + relativePath
                                )
                            }

                            val source = context.contentResolver.openInputStream(child.uri)
                                ?: throw IOException("Could not open game file: " + relativePath)

                            source.use { input ->
                                FileOutputStream(destination).use { output ->
                                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        if (read == 0) continue

                                        val nextTotal = copiedBytes + read.toLong()
                                        if (nextTotal < copiedBytes || nextTotal > MAX_TOTAL_BYTES) {
                                            throw IOException(
                                                "The game exceeds the import safety limit of " +
                                                    MAX_TOTAL_BYTES + " bytes."
                                            )
                                        }

                                        if (nextTotal - lastStorageCheckBytes >= STORAGE_CHECK_INTERVAL) {
                                            ensureFreeSpace(read.toLong())
                                            lastStorageCheckBytes = nextTotal
                                        }

                                        output.write(buffer, 0, read)
                                        copiedBytes = nextTotal
                                    }
                                    output.flush()
                                    output.fd.sync()
                                }
                            }

                            copiedFiles++
                            if (relativePath == executablePath) {
                                selectedExecutableCopied = true
                            }
                            onProgress?.invoke(ImportProgress(copiedFiles, copiedBytes))
                        }

                        else -> {
                            throw IOException("Unsupported document entry: " + relativePath)
                        }
                    }
                }
            }

            if (!selectedExecutableCopied) {
                throw IOException(
                    "The selected EXE was not found in the copied folder. " +
                        "Scan the game again and choose its main executable."
                )
            }

            val selectedExecutable = File(stagingDirectory, executablePath).canonicalFile
            if (!selectedExecutable.path.startsWith(canonicalPrefix) || !selectedExecutable.isFile) {
                throw IOException("The selected EXE is not present in the imported game.")
            }

            val peInspection = FileInputStream(selectedExecutable).use { input ->
                val prefix = ByteArray(PE_HEADER_READ_BYTES)
                var total = 0
                while (total < prefix.size) {
                    val read = input.read(prefix, total, prefix.size - total)
                    if (read <= 0) break
                    total += read
                }
                PeHeaderInspector.inspect(prefix.copyOf(total))
            }

            if (peInspection?.isValidPe != true) {
                throw IOException("The selected .exe does not contain a valid Windows PE header.")
            }

            if (!stagingDirectory.renameTo(finalDirectory)) {
                throw IOException("Could not finalize the imported game directory.")
            }

            val imported = ImportedGame(
                id = gameId,
                title = gameTitle.ifBlank { root.name ?: "Visual novel" },
                relativeDirectory = "games/" + gameId,
                executableRelativePath = executablePath,
                architecture = peInspection.architecture.displayName,
                totalBytes = copiedBytes,
                importedAtEpochMillis = System.currentTimeMillis()
            )

            try {
                GameLibraryStore(context).add(imported)
            } catch (error: Throwable) {
                finalDirectory.deleteRecursively()
                throw error
            }

            return imported
        } catch (error: Throwable) {
            stagingDirectory.deleteRecursively()
            throw error
        }
    }

    private fun ensureFreeSpace(nextChunkBytes: Long) {
        val availableBytes = StatFs(context.filesDir.absolutePath).availableBytes
        if (availableBytes - nextChunkBytes < MIN_FREE_SPACE_RESERVE) {
            throw IOException(
                "Not enough free storage to safely import the game. Free up space and try again."
            )
        }
    }

    private data class PendingDirectory(
        val document: DocumentFile,
        val depth: Int,
        val relativePath: String
    )

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_ENTRIES = 100_000
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val PE_HEADER_READ_BYTES = 64 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L * 1024L
        const val MIN_FREE_SPACE_RESERVE = 128L * 1024L * 1024L
        const val STORAGE_CHECK_INTERVAL = 4L * 1024L * 1024L
    }
}
