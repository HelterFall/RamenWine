package dev.ramenwine.display

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Installs XKB/font/Weston support data from the pinned Wawona APK.
 * Extraction is bounded, staged and SHA-256 verified.
 */
internal object WawonaSupportAssetInstaller {
    private const val MAX_FILES = 100_000
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
    private const val BUFFER_BYTES = 64 * 1024

    fun install(context: Context, manifest: WawonaPackageManifest): File {
        val root = File(context.filesDir, "wawona-rootfs")
        val markerText = markerText(manifest)

        if (File(root, MARKER_NAME).takeIf { it.isFile }?.readText() == markerText &&
            WawonaAssetIntegrity.matches(root, manifest.assets)
        ) {
            return root
        }

        val staging = File(context.filesDir, "wawona-rootfs.staging")
        val backup = File(context.filesDir, "wawona-rootfs.backup")
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            throw IllegalStateException("Could not create Wawona asset staging directory.")
        }

        try {
            var totalBytes = 0L
            require(manifest.assets.isNotEmpty() && manifest.assets.size <= MAX_FILES) {
                "Invalid Wawona asset inventory."
            }

            for ((assetPath, expectedHash) in manifest.assets) {
                val relative = WawonaAssetIntegrity.destinationRelativePath(assetPath)
                    ?: throw IllegalArgumentException("Unsafe Wawona asset path: $assetPath")
                val destination = File(staging, relative).canonicalFile
                requireContained(staging, destination)
                val parent = destination.parentFile
                    ?: throw IllegalStateException("Invalid Wawona asset destination.")
                if (!parent.isDirectory && !parent.mkdirs()) {
                    throw IllegalStateException("Could not create Wawona asset directory.")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var fileBytes = 0L
                context.assets.open(assetPath).use { rawInput ->
                    BufferedInputStream(rawInput, BUFFER_BYTES).use { input ->
                        FileOutputStream(destination).use { rawOutput ->
                            BufferedOutputStream(rawOutput, BUFFER_BYTES).use { output ->
                                val buffer = ByteArray(BUFFER_BYTES)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    fileBytes += count
                                    totalBytes += count
                                    require(fileBytes <= MAX_TOTAL_BYTES &&
                                        totalBytes <= MAX_TOTAL_BYTES
                                    ) { "Wawona support assets exceed the extraction limit." }
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                }

                val actualHash = digest.digest().joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
                require(actualHash == expectedHash) {
                    "Wawona support asset digest mismatch: $assetPath"
                }
            }

            File(staging, "home").mkdirs()
            File(staging, "usr/bin").mkdirs()
            File(staging, MARKER_NAME).writeText(markerText)

            require(WawonaAssetIntegrity.matches(staging, manifest.assets)) {
                "Extracted Wawona support assets failed the final integrity check."
            }

            backup.deleteRecursively()
            if (root.exists() && !root.renameTo(backup)) {
                throw IllegalStateException("Could not preserve the previous Wawona rootfs.")
            }
            if (!staging.renameTo(root)) {
                if (backup.exists()) backup.renameTo(root)
                throw IllegalStateException("Could not activate the Wawona support rootfs.")
            }
            backup.deleteRecursively()
            return root
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun markerText(manifest: WawonaPackageManifest): String =
        manifest.sourceRevision + "\n" +
            manifest.assets.toSortedMap().entries.joinToString("\n") { (path, hash) ->
                "$path=$hash"
            }

    private fun requireContained(parent: File, child: File) {
        val canonicalParent = parent.canonicalFile
        val canonicalChild = child.canonicalFile
        val prefix = canonicalParent.path + File.separator
        require(canonicalChild.path.startsWith(prefix)) {
            "Wawona asset escaped its staging directory."
        }
    }

    private const val MARKER_NAME = ".ramenwine-wawona-assets"
}
