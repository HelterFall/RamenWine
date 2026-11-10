package dev.ramenwine.inspection

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.util.ArrayDeque
import java.util.Locale

data class ExecutableCandidate(
    val displayName: String,
    val relativePath: String,
    val uri: Uri,
    val sizeBytes: Long?,
    val peInspection: PeInspection?
)

data class FolderScanResult(
    val folderName: String,
    val executableCandidates: List<ExecutableCandidate>,
    val visitedFiles: Int,
    val wasTruncated: Boolean,
    val warnings: List<String>,
    val hasBlockingIssues: Boolean = false
) {
    val canImport: Boolean
        get() = !wasTruncated && !hasBlockingIssues
}

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
                warnings = listOf("Android did not provide a readable document tree."),
                hasBlockingIssues = true
            )

        if (!root.isDirectory) {
            return FolderScanResult(
                folderName = root.name ?: "Selected folder",
                executableCandidates = emptyList(),
                visitedFiles = 0,
                wasTruncated = false,
                warnings = listOf("The selected document is not a folder."),
                hasBlockingIssues = true
            )
        }

        val queue = ArrayDeque<DirectoryEntry>()
        val candidates = mutableListOf<ExecutableCandidate>()
        val warnings = mutableListOf<String>()
        val visitedPaths = HashSet<String>()
        var hasBlockingIssues = false
        var visitedFiles = 0
        var visitedEntries = 0
        var wasTruncated = false

        queue.addLast(DirectoryEntry(root, depth = 0, relativePath = ""))

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = try {
                SafDocumentTree.listChildren(context, current.document)
            } catch (error: Exception) {
                warnings.addBounded(
                    "Could not list " + (current.relativePath.ifBlank { root.name ?: "game folder" }) +
                        ": " + error.javaClass.simpleName
                )
                hasBlockingIssues = true
                continue
            }

            for (child in children) {
                visitedEntries++
                if (visitedEntries > MAX_VISITED_ENTRIES) {
                    wasTruncated = true
                    queue.clear()
                    break
                }

                val segment = SafeGamePath.validateSegment(child.name)
                if (segment == null) {
                    warnings.addBounded(
                        "An entry has a filename that cannot be represented safely in a Windows game folder."
                    )
                    hasBlockingIssues = true
                    continue
                }

                val relativePath = if (current.relativePath.isEmpty()) {
                    segment
                } else {
                    current.relativePath + "/" + segment
                }

                if (!visitedPaths.add(relativePath.lowercase(Locale.ROOT))) {
                    warnings.addBounded(
                        "Duplicate Windows path (names differ only by letter case): " + relativePath
                    )
                    hasBlockingIssues = true
                    continue
                }

                when {
                    child.isDirectory -> {
                        if (current.depth < MAX_DEPTH) {
                            queue.addLast(
                                DirectoryEntry(
                                    document = child,
                                    depth = current.depth + 1,
                                    relativePath = relativePath
                                )
                            )
                        } else {
                            wasTruncated = true
                        }
                    }

                    child.isFile -> {
                        visitedFiles++
                        if (segment.endsWith(".exe", ignoreCase = true)) {
                            val inspection = try {
                                val input = context.contentResolver.openInputStream(child.uri)
                                if (input == null) {
                                    warnings.addBounded("Could not open EXE for inspection: " + relativePath)
                                    hasBlockingIssues = true
                                    null
                                } else {
                                    input.use {
                                        PeHeaderInspector.inspect(it.readPrefix(MAX_HEADER_BYTES))
                                    }
                                }
                            } catch (error: Exception) {
                                warnings.addBounded(
                                    "Could not inspect $relativePath: " + error.javaClass.simpleName
                                )
                                hasBlockingIssues = true
                                null
                            }

                            val size = runCatching { child.length() }
                                .getOrNull()
                                ?.takeIf { it >= 0L }

                            if (candidates.size < MAX_CANDIDATES) {
                                candidates += ExecutableCandidate(
                                    displayName = segment,
                                    relativePath = relativePath,
                                    uri = child.uri,
                                    sizeBytes = size,
                                    peInspection = inspection
                                )
                            } else {
                                wasTruncated = true
                            }
                        }
                    }

                    else -> {
                        warnings.addBounded("Unsupported folder entry: " + relativePath)
                        hasBlockingIssues = true
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
                }.thenBy { it.relativePath.lowercase() }
            ),
            visitedFiles = visitedFiles,
            wasTruncated = wasTruncated,
            warnings = warnings,
            hasBlockingIssues = hasBlockingIssues
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
        val depth: Int,
        val relativePath: String
    )

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_VISITED_ENTRIES = 100_000
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_CANDIDATES = 500
        const val MAX_WARNINGS = 30
    }
}
