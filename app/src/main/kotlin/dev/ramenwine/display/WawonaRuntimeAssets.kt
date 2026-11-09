package dev.ramenwine.display

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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

        if (marker.isFile && xkb.isDirectory && fonts.isDirectory) {
            return
        }

        copyAssetTree("wawona/xkb", xkb, required = true)
        copyAssetTree("wawona/fonts", fonts, required = true)
        copyAssetTree("wawona/weston", weston, required = false)

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

    private fun copyAssetTree(assetPath: String, destination: File, required: Boolean) {
        val entries = appContext.assets.list(assetPath)
            ?: throw IOException("Missing packaged Wawona asset tree: " + assetPath)
        if (entries.isEmpty()) {
            if (required) throw IOException("Wawona asset tree is empty: " + assetPath)
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
        const val MARKER_NAME = ".ramenwine-wawona-assets-" + WAWONA_REVISION
        const val WAWONA_REVISION = "d298ddd25726fec0977285aa5f57181110c966a6"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_ASSET_FILES = 100_000
        const val MAX_ASSET_BYTES = 128L * 1024L * 1024L
    }
}
