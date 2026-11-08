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
            path.contains(',')
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
        ) {
            return null
        }

        return runCatching {
            val rootPath = runtimeRoot.canonicalFile.toPath().normalize()
            val linkPath = rootPath.resolve(linkRelativePath).normalize()
            val linkParent = linkPath.parent ?: return null
            val targetPath = if (target.startsWith('/')) {
                rootPath.resolve(target.removePrefix("/")).normalize()
            } else {
                linkParent.resolve(target).normalize()
            }

            if (!targetPath.startsWith(rootPath)) {
                return null
            }

            linkParent.relativize(targetPath).toString()
                .replace(File.separatorChar, '/')
        }.getOrNull()
    }

    private const val MAX_SEGMENT_LENGTH = 240
}
