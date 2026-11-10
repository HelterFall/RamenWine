package dev.ramenwine.runtime

import java.io.File

/**
 * Structural requirements for the currently implemented glibc/PRoot launch path.
 * Passing these checks does not prove that native binaries execute on a device.
 */
internal object RuntimePackageRequirements {
    private val requiredExecutables = listOf(
        "host/bin/proot",
        "rootfs/usr/bin/env",
        "rootfs/usr/bin/box64",
        "rootfs/usr/bin/wine"
    )
    private val winebootCandidates = listOf(
        "rootfs/usr/lib/wine/x86_64-windows/wineboot.exe",
        "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe"
    )

    fun validateManifest(manifest: RuntimePackageManifest): List<String> {
        if (manifest.backend != RuntimeBackendId.GLIBC_PROOT_BOX64) return emptyList()

        val files = manifest.files.associateBy { it.path }
        val links = manifest.symlinks.associateBy { it.path }
        val errors = mutableListOf<String>()

        for (path in requiredExecutables) {
            val resolved = resolveManifestFile(path, files, links)
            when {
                resolved == null -> errors += "Missing required executable: $path"
                !resolved.executable -> errors += "Required executable is not marked executable: $path"
            }
        }

        if (winebootCandidates.none { resolveManifestFile(it, files, links) != null }) {
            errors += "Missing Wine prefix initializer (wineboot.exe)."
        }

        val waylandDriverPaths = (files.keys + links.keys).filter { path ->
            path.startsWith(WINE_ROOT_PREFIX) &&
                path.endsWith("/winewayland.drv.so")
        }
        if (waylandDriverPaths.none { resolveManifestFile(it, files, links) != null }) {
            errors += "Missing Wine Wayland display driver (winewayland.drv.so)."
        }

        return errors
    }

    fun inspectInstalled(backend: RuntimeBackendId, backendRoot: File): List<String> {
        if (backend != RuntimeBackendId.GLIBC_PROOT_BOX64) return emptyList()
        val errors = mutableListOf<String>()

        for (path in requiredExecutables) {
            val file = resolveInstalledFile(backendRoot, path)
            when {
                file == null -> errors += "Unsafe or missing launch path: $path"
                !file.isFile -> errors += "Required launch file is missing: $path"
                !file.canExecute() -> errors += "Required launch file is not executable: $path"
            }
        }

        val winebootPresent = winebootCandidates.any { path ->
            resolveInstalledFile(backendRoot, path)?.let { it.isFile && it.canRead() } == true
        }
        if (!winebootPresent) errors += "Missing readable Wine prefix initializer (wineboot.exe)."

        val hostLibraries = resolveInstalledFile(backendRoot, "host/lib")
        if (hostLibraries == null || !hostLibraries.isDirectory) {
            errors += "Missing PRoot host library directory: host/lib"
        }

        errors += RuntimeBinaryValidator.inspectInstalled(backend, backendRoot)
        return errors
    }

    private fun resolveManifestFile(
        requestedPath: String,
        files: Map<String, RuntimePackageFile>,
        links: Map<String, RuntimePackageSymlink>
    ): RuntimePackageFile? {
        var current = requestedPath
        val visited = HashSet<String>()
        repeat(MAX_SYMLINK_HOPS) {
            if (!visited.add(current)) return null
            files[current]?.let { return it }
            val link = links[current] ?: return null
            current = resolveManifestLinkTarget(current, link.target) ?: return null
        }
        return null
    }

    private fun resolveManifestLinkTarget(linkPath: String, target: String): String? {
        if (
            target.isBlank() ||
            target.contains('\\') ||
            target.contains('\u0000') ||
            target.any { it.isISOControl() }
        ) return null

        val domain = linkPath.substringBefore('/')
        if (target.startsWith('/') && domain == "host") return null

        val parts = when {
            target.startsWith('/') && domain == "rootfs" ->
                listOf("rootfs") + target.removePrefix("/").split('/')
            target.startsWith('/') -> target.removePrefix("/").split('/')
            else ->
                linkPath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() } +
                    target.split('/')
        }

        val normalized = mutableListOf<String>()
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (normalized.isEmpty()) return null
                    normalized.removeAt(normalized.lastIndex)
                }
                else -> normalized += part
            }
        }
        if (normalized.isEmpty()) return null
        if (domain == "rootfs" || domain == "host") {
            if (normalized.firstOrNull() != domain) return null
        }
        return normalized.joinToString("/")
    }

    private fun resolveInstalledFile(root: File, relativePath: String): File? {
        val safePath = SafeRuntimePath.normalizeRelativePath(relativePath) ?: return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val canonicalPrefix = canonicalRoot.path + File.separator
        val file = runCatching { File(root, safePath).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.path.startsWith(canonicalPrefix) }
    }

    private const val MAX_SYMLINK_HOPS = 40
    private const val WINE_ROOT_PREFIX = "rootfs/opt/kron4ek-wine/"
}
