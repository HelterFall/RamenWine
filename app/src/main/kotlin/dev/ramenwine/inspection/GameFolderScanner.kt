package dev.ramenwine.inspection

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.util.ArrayDeque

data class ExecutableCandidate(
    val displayName: String,
    val uri: Uri,
    val sizeBytes: Long?,
    val peInspection: PeInspection?
)

data class FolderScanResult(
    val folderName: String,
    val executableCandidates: List<ExecutableCandidate>,
    val visitedFiles: Int,
    val wasTruncated: Boolean,
    val warnings: List<String>
)

/**
 * Read-only, bounded SAF tree scanner. It only reads a small prefix of .exe
 * files and never attempts to execute them.
 */
class GameFolderScanner(private val context: Context) {
    fun scan(treeUri: Uri): FolderScanResult {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return FolderScanResult(
                folderName = "Selected folder",
                executableCandidates = emptyList(),
                visitedFiles = 0,
                wasTruncated = false,
                warnings = listOf("Android did not provide a readable document tree.")
            )

        val queue = ArrayDeque<DirectoryEntry>()
        val candidates = mutableListOf<ExecutableCandidate>()
        val warnings = mutableListOf<String>()
        var visitedFiles = 0
        var visitedEntries = 0
        var wasTruncated = false

        queue.addLast(DirectoryEntry(root, depth = 0))

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = try {
                current.document.listFiles()
            } catch (error: Exception) {
                warnings.addBounded(
                    "Could not list " + (current.document.name ?: "a directory") +
                        ": " + error.javaClass.simpleName
                )
                continue
            }

            for (child in children) {
                visitedEntries++
                if (visitedEntries > MAX_VISITED_ENTRIES) {
                    wasTruncated = true
                    queue.clear()
                    break
                }

                when {
                    child.isDirectory -> {
                        if (current.depth < MAX_DEPTH) {
                            queue.addLast(DirectoryEntry(child, current.depth + 1))
                        } else {
                            wasTruncated = true
                        }
                    }

                    child.isFile -> {
                        visitedFiles++
                        val name = child.name ?: "unnamed"
                        if (name.endsWith(".exe", ignoreCase = true)) {
                            val inspection = try {
                                context.contentResolver.openInputStream(child.uri)?.use {
                                    PeHeaderInspector.inspect(it.readPrefix(MAX_HEADER_BYTES))
                                }
                            } catch (error: Exception) {
                                warnings.addBounded(
                                    "Could not inspect $name: " + error.javaClass.simpleName
                                )
                                null
                            }

                            val size = runCatching { child.length() }
                                .getOrNull()
                                ?.takeIf { it >= 0L }

                            if (candidates.size < MAX_CANDIDATES) {
                                candidates += ExecutableCandidate(
                                    displayName = name,
                                    uri = child.uri,
                                    sizeBytes = size,
                                    peInspection = inspection
                                )
                            } else {
                                wasTruncated = true
                            }
                        }
                    }
                }
            }
        }

        return FolderScanResult(
            folderName = root.name ?: "Selected folder",
            executableCandidates = candidates.sortedWith(
                compareBy<ExecutableCandidate> { candidate ->
                    when (candidate.peInspection?.architecture) {
                        PeArchitecture.X86_64 -> 0
                        PeArchitecture.X86 -> 1
                        PeArchitecture.ARM64, PeArchitecture.ARM64EC -> 2
                        else -> 3
                    }
                }.thenBy { it.displayName.lowercase() }
            ),
            visitedFiles = visitedFiles,
            wasTruncated = wasTruncated,
            warnings = warnings
        )
    }

    private fun MutableList<String>.addBounded(message: String) {
        if (size < MAX_WARNINGS) add(message)
    }

    private fun InputStream.readPrefix(maxBytes: Int): ByteArray {
        val output = ByteArray(maxBytes)
        var total = 0

        while (total < maxBytes) {
            val read = read(output, total, maxBytes - total)
            if (read <= 0) break
            total += read
        }

        return output.copyOf(total)
    }

    private data class DirectoryEntry(
        val document: DocumentFile,
        val depth: Int
    )

    private companion object {
        const val MAX_DEPTH = 16
        const val MAX_VISITED_ENTRIES = 15_000
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_CANDIDATES = 500
        const val MAX_WARNINGS = 30
    }
}
