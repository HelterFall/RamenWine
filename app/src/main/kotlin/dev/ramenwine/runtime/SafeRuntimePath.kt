package dev.ramenwine.runtime

import java.io.File

object SafeRuntimePath {
    fun normalizeRelativePath(path: String?): String? {
        if (
            path.isNullOrBlank() ||
            path.startsWith('/') ||
            path.startsWith('\\') ||
            path.contains('\\') ||
            path.contains('\u0000') ||
        ) {
            return null
        }

        val segments = path.split('/')
        if (segments.isEmpty() || segments.any { segment ->
                segment.isBlank() ||
                    segment == "." ||
                    segment == ".." ||
                    segment.length > MAX_SEGMENT_LENGTH ||
                    segment.any { it.isISOControl() }
            }
        ) {
            return null
        }

        return segments.joinToString("/")
    }

    /**
     * Converts an absolute Linux-root symlink target or a relative target into
     * a relative link that stays inside the unpacked runtime directory.
     */
    fun normalizeSymlinkTarget(
        runtimeRoot: File,
        linkRelativePath: String,
        target: String
    ): String? {
        if (
            target.isBlank() ||
            target.contains('\\') ||
            target.contains('\u0000') ||
            target.any { it.isISOControl() }
        ) return null

        return runCatching {
            val safeLinkPath = normalizeRelativePath(linkRelativePath) ?: return null
            val rootPath = runtimeRoot.canonicalFile.toPath().normalize()
            val linkPath = rootPath.resolve(safeLinkPath).normalize()
            val linkParent = linkPath.parent ?: return null
            val domain = safeLinkPath.substringBefore('/')
            val domainRoot = if (domain == "rootfs" || domain == "host") {
                rootPath.resolve(domain).normalize()
            } else {
                rootPath
            }

            // Absolute links in rootfs are relative to the guest root.
            // Absolute host links would point outside app-private storage.
            val targetPath = if (target.startsWith('/')) {
                if (domain == "host") return null
                val guestTarget = target.removePrefix("/")
                // /host is reserved for the Android-side runtime payload and
                // is not part of the Linux guest namespace. Do not let an
                // absolute guest symlink cross from rootfs into host files.
                if (domain == "rootfs" && guestTarget.substringBefore('/') == "host") {
                    return null
                }
                val absoluteRoot = if (domain == "rootfs") domainRoot else rootPath
                absoluteRoot.resolve(guestTarget).normalize()
            } else {
                linkParent.resolve(target).normalize()
            }

            if (!targetPath.startsWith(domainRoot)) return null
            val relativeTarget = linkParent.relativize(targetPath)
                .toString()
                .replace(File.separatorChar, '/')
            // A symlink that points to its own parent (for example,
            // /usr/bin/X11 -> .) has an empty relative path. Preserve it
            // explicitly as "." because Os.symlink rejects an empty target.
            relativeTarget.ifEmpty { "." }
        }.getOrNull()
    }

    private const val MAX_SEGMENT_LENGTH = 240
}
