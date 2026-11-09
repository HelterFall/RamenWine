package dev.ramenwine.display

import java.io.File
import java.io.IOException
import java.security.MessageDigest

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
