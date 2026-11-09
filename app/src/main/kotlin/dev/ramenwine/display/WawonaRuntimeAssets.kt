package dev.ramenwine.display

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Installs only the compositor support assets that RamenWine actually needs.
 * Wawona's shell, terminal, SSH, Niri and machine UI assets are not copied.
 */
internal class WawonaRuntimeAssets(context: Context) {
    private val appContext = context.applicationContext
    private val filesRoot = appContext.filesDir.canonicalFile
    private val rootfs = File(filesRoot, ROOTFS_DIRECTORY).canonicalFile
    private val marker = File(rootfs, MARKER_NAME)

    fun ensureInstalled() {
        requireInside(filesRoot, rootfs, "Wawona rootfs")
        val xkb = File(rootfs, "usr/share/X11/xkb")
        val fonts = File(rootfs, "usr/share/fonts")
        val weston = File(rootfs, "usr/share/weston")
        val expectedHashes = loadAssetHashes()

        if (
            marker.isFile &&
            marker.readText(Charsets.UTF_8) == WAWONA_REVISION &&
            xkb.isDirectory &&
            fonts.isDirectory &&
            WawonaAssetIntegrity.matches(rootfs, expectedHashes)
        ) {
            return
        }

        copyAssetTree("wawona/xkb", xkb, required = true)
        copyAssetTree("wawona/fonts", fonts, required = true)
        copyAssetTree("wawona/weston", weston, required = false)

        if (!WawonaAssetIntegrity.matches(rootfs, expectedHashes)) {
            throw IOException(
                "Wawona support assets failed their SHA-256 integrity check after installation."
            )
        }

        marker.parentFile?.mkdirs()
        val temporaryMarker = File(marker.parentFile, marker.name + ".tmp")
        FileOutputStream(temporaryMarker).use { output ->
            output.write(WAWONA_REVISION.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (marker.exists() && !marker.delete()) {
            temporaryMarker.delete()
            throw IOException("Could not replace Wawona asset marker.")
        }
        if (!temporaryMarker.renameTo(marker)) {
            temporaryMarker.delete()
            throw IOException("Could not activate Wawona compositor assets.")
        }
    }

    private fun loadAssetHashes(): Map<String, String> {
        val provenance = try {
            appContext.assets.open(PROVENANCE_ASSET_PATH).bufferedReader(Charsets.UTF_8).use {
                org.json.JSONObject(it.readText())
            }
        } catch (error: Exception) {
            throw IOException("Missing or invalid Wawona asset provenance manifest.", error)
        }
        if (provenance.optString("revision") != WAWONA_REVISION) {
            throw IOException("Wawona asset provenance does not match the pinned revision.")
        }

        val assets = provenance.optJSONArray("assets")
            ?: throw IOException("Wawona asset provenance has no asset inventory.")
        val hashes = provenance.optJSONObject("assetSha256")
            ?: throw IOException("Wawona asset provenance has no SHA-256 inventory.")
        if (assets.length() == 0 || hashes.length() != assets.length()) {
            throw IOException("Wawona asset provenance inventory is empty or inconsistent.")
        }

        val result = linkedMapOf<String, String>()
        for (index in 0 until assets.length()) {
            val path = assets.optString(index, "")
            val digest = hashes.optString(path, "")
            if (
                path.isBlank() ||
                path in result ||
                !digest.matches(ASSET_SHA256_PATTERN) ||
                !isAllowedAssetPath(path)
            ) {
                throw IOException("Wawona asset provenance contains an unsafe or invalid entry.")
            }
            result[path] = digest
        }
        val hashKeys = hashes.keys()
        while (hashKeys.hasNext()) {
            if (hashKeys.next() !in result) {
                throw IOException("Wawona asset SHA-256 inventory contains an unknown path.")
            }
        }
        return result
    }

    private fun isAllowedAssetPath(path: String): Boolean {
        if (
            !path.startsWith("wawona/xkb/") &&
            !path.startsWith("wawona/fonts/") &&
            !path.startsWith("wawona/weston/")
        ) {
            return false
        }
        return !path.contains('\\') &&
            !path.contains('\u0000') &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun copyAssetTree(assetPath: String, destination: File, required: Boolean) {
        val entries = appContext.assets.list(assetPath)
        if (entries == null || entries.isEmpty()) {
            if (required) throw IOException("Missing or empty Wawona asset tree: " + assetPath)
            return
        }

        ensureDirectory(destination)
        var copiedFiles = 0
        var copiedBytes = 0L

        fun copy(currentAsset: String, currentDestination: File) {
            val names = appContext.assets.list(currentAsset)
                ?: throw IOException("Could not list Wawona asset directory: " + currentAsset)
            for (name in names.sorted()) {
                if (
                    name.isBlank() || name == "." || name == ".." ||
                    name.contains('/') || name.contains('\\') ||
                    name.any { it.isISOControl() }
                ) {
                    throw IOException("Unsafe Wawona asset entry: " + name)
                }
                val childAsset = currentAsset + "/" + name
                val childDestination = File(currentDestination, name)
                requireInside(rootfs, childDestination, "Wawona asset destination")
                val children = appContext.assets.list(childAsset)
                if (children != null && children.isNotEmpty()) {
                    ensureDirectory(childDestination)
                    copy(childAsset, childDestination)
                } else {
                    childDestination.parentFile?.let(::ensureDirectory)
                    val temporary = File(childDestination.parentFile, childDestination.name + ".tmp")
                    appContext.assets.open(childAsset).use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                copiedBytes += read.toLong()
                                if (copiedBytes > MAX_ASSET_BYTES) {
                                    throw IOException("Wawona asset payload exceeds the size limit.")
                                }
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                    if (childDestination.exists() && !childDestination.delete()) {
                        temporary.delete()
                        throw IOException("Could not replace Wawona asset: " + childAsset)
                    }
                    if (!temporary.renameTo(childDestination)) {
                        temporary.delete()
                        throw IOException("Could not install Wawona asset: " + childAsset)
                    }
                    copiedFiles += 1
                    if (copiedFiles > MAX_ASSET_FILES) {
                        throw IOException("Wawona asset tree contains too many files.")
                    }
                }
            }
        }

        copy(assetPath, destination)
    }

    private fun ensureDirectory(directory: File) {
        requireInside(rootfs, directory, "Wawona asset directory")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create Wawona asset directory: " + directory.path)
        }
        if (!directory.isDirectory) {
            throw IOException("Wawona asset destination is not a directory: " + directory.path)
        }
    }

    private fun requireInside(root: File, candidate: File, description: String) {
        val canonicalRoot = root.canonicalFile
        val canonicalCandidate = candidate.canonicalFile
        val prefix = canonicalRoot.path + File.separator
        if (!canonicalCandidate.path.startsWith(prefix)) {
            throw IOException(description + " escapes app-private storage.")
        }
    }

    private companion object {
        const val ROOTFS_DIRECTORY = "wawona-rootfs"
        const val WAWONA_REVISION = "d298ddd25726fec0977285aa5f57181110c966a6"
        const val PROVENANCE_ASSET_PATH = "wawona/provenance.json"
        val ASSET_SHA256_PATTERN = Regex("[0-9a-f]{64}")
        const val MARKER_NAME = ".ramenwine-wawona-assets-" + WAWONA_REVISION
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_ASSET_FILES = 100_000
        const val MAX_ASSET_BYTES = 128L * 1024L * 1024L
    }


}


/**
 * Verifies the exact files extracted from the pinned Wawona APK.
 *
 * Asset paths in provenance.json deliberately use APK-relative names. Their
 * on-disk locations are mapped into the small app-private support tree.
 */
internal object WawonaAssetIntegrity {
    private const val MAX_ASSET_FILES = 100_000
    private const val BUFFER_BYTES = 64 * 1024
    private const val HEX = "0123456789abcdef"
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

    fun matches(rootfs: File, expectedHashes: Map<String, String>): Boolean {
        if (expectedHashes.isEmpty() || expectedHashes.size > MAX_ASSET_FILES) {
            return false
        }

        val canonicalRoot = runCatching { rootfs.canonicalFile }.getOrNull() ?: return false
        val rootPrefix = canonicalRoot.path + File.separator
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)

        for ((assetPath, expectedHash) in expectedHashes) {
            if (!SHA256_PATTERN.matches(expectedHash)) return false
            val relativePath = mapAssetPath(assetPath) ?: return false
            val file = runCatching {
                File(canonicalRoot, relativePath).canonicalFile
            }.getOrNull() ?: return false

            if (!file.path.startsWith(rootPrefix) || !file.isFile) return false

            val actualHash = try {
                digest.reset()
                file.inputStream().buffered().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) digest.update(buffer, 0, read)
                    }
                }
                toHex(digest.digest())
            } catch (_: IOException) {
                return false
            }

            if (actualHash != expectedHash) return false
        }
        return true
    }

    private fun mapAssetPath(assetPath: String): String? {
        if (
            assetPath.isBlank() ||
            assetPath.contains('\\') ||
            assetPath.contains('\u0000')
        ) {
            return null
        }

        val parts = assetPath.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        if (parts.size < 3 || parts[0] != "wawona") return null

        val destination = when (parts[1]) {
            "xkb" -> "usr/share/X11/xkb"
            "fonts" -> "usr/share/fonts"
            "weston" -> "usr/share/weston"
            else -> return null
        }
        return destination + "/" + parts.drop(2).joinToString("/")
    }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
}
