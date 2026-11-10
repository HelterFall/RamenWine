package dev.ramenwine.runtime

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.system.Os
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

data class RuntimeInstallResult(
    val backend: RuntimeBackendId,
    val runtimeVersion: String,
    val installedFileCount: Int,
    val installedSymlinkCount: Int,
    val installedBytes: Long
)

/**
 * Installs an offline runtime ZIP package.
 *
 * Archive layout:
 *   runtime-manifest.json
 *   payload/<relative runtime file path>
 *
 * The manifest supplies SHA-256 digests, executable flags and symlink targets.
 * Extraction is staged and the active backend directory is replaced only
 * after every file has been verified.
 */
class RuntimePackageInstaller(private val context: Context) {
    suspend fun install(packageUri: Uri): RuntimeInstallResult {
        val runtimeRoot = File(context.filesDir, "runtime/current")
        val backendsRoot = File(runtimeRoot, "backends")
        if (!backendsRoot.exists() && !backendsRoot.mkdirs()) {
            throw IOException("Could not create runtime backend directory.")
        }

        ensureFreeSpace(0L)

        val installId = UUID.randomUUID().toString()
        val stagingDirectory = File(backendsRoot, ".staging-" + installId)
        if (!stagingDirectory.mkdir()) {
            throw IOException("Could not create runtime staging directory.")
        }

        var previousDirectory: File? = null
        var backendTargetDirectory: File? = null
        var activatedDirectory: File? = null

        try {
            val manifestBytes = ByteArrayOutputStream()
            val extractedDigests = LinkedHashMap<String, String>()
            var entryCount = 0
            var totalBytes = 0L
            var lastStorageCheckBytes = 0L

            val source = context.contentResolver.openInputStream(packageUri)
                ?: throw IOException("Could not open the selected runtime package.")

            source.use { rawInput ->
                ZipInputStream(BufferedInputStream(rawInput)).use { zip ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = zip.nextEntry ?: break
                        entryCount++
                        if (entryCount > MAX_ARCHIVE_ENTRIES) {
                            throw IOException("Runtime package contains too many ZIP entries.")
                        }

                        when {
                            entry.name == MANIFEST_ENTRY -> {
                                if (manifestBytes.size() > 0) {
                                    throw IOException("Runtime package contains duplicate manifests.")
                                }
                                copyManifest(zip, manifestBytes)
                            }

                            entry.name == PAYLOAD_ROOT || entry.name == "$PAYLOAD_ROOT/" -> {
                                // The payload root itself is only a container entry.
                            }

                            entry.name.startsWith("$PAYLOAD_ROOT/") -> {
                                val rawRelative = entry.name.removePrefix("$PAYLOAD_ROOT/")
                                    .removeSuffix("/")
                                val relativePath = SafeRuntimePath.normalizeRelativePath(rawRelative)
                                    ?: throw IOException("Unsafe path in runtime ZIP: " + entry.name)

                                val destination = resolveInside(stagingDirectory, relativePath)
                                    ?: throw IOException("Runtime ZIP path escapes the staging directory.")

                                if (entry.isDirectory) {
                                    if (!destination.exists() && !destination.mkdirs()) {
                                        throw IOException("Could not create runtime directory: " + relativePath)
                                    }
                                } else {
                                    if (extractedDigests.containsKey(relativePath)) {
                                        throw IOException("Duplicate runtime file: " + relativePath)
                                    }

                                    val parent = destination.parentFile
                                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                                        throw IOException("Could not create runtime file directory.")
                                    }

                                    val digest = MessageDigest.getInstance("SHA-256")
                                    FileOutputStream(destination).use { output ->
                                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                                        while (true) {
                                            coroutineContext.ensureActive()
                                            val read = zip.read(buffer)
                                            if (read < 0) break
                                            if (read == 0) continue

                                            val nextTotal = totalBytes + read.toLong()
                                            if (nextTotal < totalBytes || nextTotal > MAX_TOTAL_BYTES) {
                                                throw IOException("Runtime package exceeds the uncompressed size limit.")
                                            }

                                            if (nextTotal - lastStorageCheckBytes >= STORAGE_CHECK_INTERVAL) {
                                                ensureFreeSpace(read.toLong())
                                                lastStorageCheckBytes = nextTotal
                                            }

                                            digest.update(buffer, 0, read)
                                            output.write(buffer, 0, read)
                                            totalBytes = nextTotal
                                        }
                                        output.flush()
                                    }

                                    extractedDigests[relativePath] = digest.digest().toHexString()
                                }
                            }

                            else -> {
                                throw IOException(
                                    "Unexpected runtime ZIP entry. Expected " +
                                        MANIFEST_ENTRY + " or " + PAYLOAD_ROOT + "/..."
                                )
                            }
                        }

                        zip.closeEntry()
                    }
                }
            }

            if (manifestBytes.size() == 0) {
                throw IOException("Runtime package manifest is missing.")
            }

            val manifest = RuntimePackageManifestParser.parse(
                manifestBytes.toByteArray().toString(Charsets.UTF_8)
            )

            if (extractedDigests.size != manifest.files.size) {
                throw IOException("Runtime package file list does not match its manifest.")
            }

            val executablePaths = manifest.files
                .filter { it.executable }
                .map { it.path }
                .toSet()

            for (fileEntry in manifest.files) {
                coroutineContext.ensureActive()
                val actualDigest = extractedDigests[fileEntry.path]
                    ?: throw IOException("Runtime package is missing " + fileEntry.path)

                if (actualDigest != fileEntry.sha256) {
                    throw IOException("SHA-256 mismatch for " + fileEntry.path)
                }

                val file = resolveInside(stagingDirectory, fileEntry.path)
                    ?: throw IOException("Unsafe file path in runtime manifest.")

                if (!file.isFile) {
                    throw IOException("Runtime payload entry is not a regular file: " + fileEntry.path)
                }

                if (!file.setReadable(true, true)) {
                    throw IOException("Could not set read permission on " + fileEntry.path)
                }

                val executable = fileEntry.path in executablePaths
                if (!file.setExecutable(executable, true)) {
                    throw IOException("Could not set executable permission on " + fileEntry.path)
                }
            }

            for (link in manifest.symlinks.sortedByDescending { it.path.count { c -> c == '/' } }) {
                coroutineContext.ensureActive()
                val destination = resolveInside(stagingDirectory, link.path)
                    ?: throw IOException("Unsafe symlink path in runtime manifest.")

                if (destination.exists() || destination.absoluteFile == stagingDirectory.absoluteFile) {
                    throw IOException("Symlink destination already exists: " + link.path)
                }

                val parent = destination.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw IOException("Could not create symlink parent directory.")
                }

                val safeTarget = SafeRuntimePath.normalizeSymlinkTarget(
                    runtimeRoot = stagingDirectory,
                    linkRelativePath = link.path,
                    target = link.target
                ) ?: throw IOException("Symlink target escapes the runtime package: " + link.path)

                try {
                    Os.symlink(safeTarget, destination.absolutePath)
                } catch (error: Exception) {
                    throw IOException("Could not create runtime symlink: " + link.path, error)
                }
            }

            if (manifest.backend == RuntimeBackendId.GLIBC_PROOT_BOX64) {
                // Reject a package with incorrect ARM64/x86-64 ELF or Windows PE
                // components before replacing a previously working runtime.
                GlibcRuntimeAbiValidator.validate(stagingDirectory)
            }

            writeRuntimeProperties(stagingDirectory, manifest)

            val backendDirectory = File(backendsRoot, manifest.backend.directoryName)
            backendTargetDirectory = backendDirectory
            if (backendDirectory.exists()) {
                val backup = File(backendsRoot, ".backup-" + installId)
                if (!backendDirectory.renameTo(backup)) {
                    throw IOException("Could not move the previous runtime backend to a backup.")
                }
                previousDirectory = backup
            }

            if (!stagingDirectory.renameTo(backendDirectory)) {
                throw IOException("Could not activate the new runtime backend.")
            }

            activatedDirectory = backendDirectory

            val installedBackend = RuntimeLayout.inspect(context.filesDir)
                .backends
                .first { it.backend == manifest.backend }

            if (installedBackend.state != RuntimeState.FILES_PRESENT) {
                throw IOException(
                    "Runtime package was extracted but failed the post-install inventory check: " +
                        installedBackend.missingItems.joinToString()
                )
            }

            previousDirectory?.deleteRecursively()
            previousDirectory = null

            return RuntimeInstallResult(
                backend = manifest.backend,
                runtimeVersion = manifest.runtimeVersion,
                installedFileCount = manifest.files.size,
                installedSymlinkCount = manifest.symlinks.size,
                installedBytes = totalBytes
            )
        } catch (error: Throwable) {
            if (activatedDirectory != null && activatedDirectory.exists()) {
                activatedDirectory.deleteRecursively()
            }
            val backup = previousDirectory
            val destination = backendTargetDirectory
            if (
                backup != null &&
                backup.exists() &&
                destination != null &&
                !destination.exists()
            ) {
                backup.renameTo(destination)
            }
            throw error
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    private suspend fun copyManifest(input: ZipInputStream, output: ByteArrayOutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (output.size() + read > MAX_MANIFEST_BYTES) {
                throw IOException("Runtime manifest exceeds the size limit.")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun writeRuntimeProperties(
        backendDirectory: File,
        manifest: RuntimePackageManifest
    ) {
        val requiredFiles = manifest.files.joinToString(",") { it.path }
        val executableFiles = manifest.files
            .filter { it.executable }
            .joinToString(",") { it.path }
        val requiredSymlinks = manifest.symlinks.joinToString(",") { it.path }

        val properties = buildString {
            append("backendId=").append(manifest.backend.directoryName).append('\n')
            append("hostAbi=").append(manifest.hostAbi).append('\n')
            append("runtimeVersion=").append(manifest.runtimeVersion).append('\n')
            append("requiredFiles=").append(requiredFiles).append('\n')
            append("executableFiles=").append(executableFiles).append('\n')
            append("requiredSymlinks=").append(requiredSymlinks).append('\n')
        }

        FileOutputStream(File(backendDirectory, "runtime.properties")).use { output ->
            output.write(properties.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun resolveInside(root: File, relativePath: String): File? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val canonicalPrefix = canonicalRoot.path + File.separator
        val destination = runCatching {
            File(root, relativePath).canonicalFile
        }.getOrNull() ?: return null
        return destination.takeIf { it.path.startsWith(canonicalPrefix) }
    }

    private fun ensureFreeSpace(nextChunkBytes: Long) {
        val available = StatFs(context.filesDir.absolutePath).availableBytes
        if (available - nextChunkBytes < MIN_FREE_SPACE_RESERVE) {
            throw IOException("Not enough free space to install the runtime package.")
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private companion object {
        const val MANIFEST_ENTRY = "runtime-manifest.json"
        const val PAYLOAD_ROOT = "payload"
        const val MAX_ARCHIVE_ENTRIES = 100_000
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L * 1024L
        const val MIN_FREE_SPACE_RESERVE = 128L * 1024L * 1024L
        const val STORAGE_CHECK_INTERVAL = 4L * 1024L * 1024L
    }
}
