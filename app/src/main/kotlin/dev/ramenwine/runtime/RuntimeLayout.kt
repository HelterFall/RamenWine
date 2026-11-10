package dev.ramenwine.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Properties

enum class RuntimeState {
    NOT_INSTALLED,
    INCOMPLETE,
    FILES_PRESENT
}

enum class RuntimeBackendId(
    val directoryName: String,
    val displayName: String
) {
    BIONIC_ARM64EC_FEX(
        "bionic-arm64ec-fex",
        "Bionic · ARM64EC + FEX"
    ),
    BIONIC_X64_BOX64(
        "bionic-x64-box64",
        "Bionic · x86-64 + Box64"
    ),
    GLIBC_PROOT_BOX64(
        "glibc-proot-box64",
        "glibc rootfs · PRoot + Box64"
    )
}

data class RuntimeBackendInspection(
    val backend: RuntimeBackendId,
    val state: RuntimeState,
    val missingItems: List<String>,
    val runtimeVersion: String?
)

data class RuntimeInspection(
    val state: RuntimeState,
    val runtimeRoot: File,
    val backends: List<RuntimeBackendInspection>,
    val missingItems: List<String>
)

/**
 * Reads the local runtime.properties contract for each backend.
 *
 * A backend is considered present only after every required file exists and
 * every required executable has its executable bit set. This is an inventory
 * check, not proof that Wine can launch a Windows program.
 */
object RuntimeLayout {
    fun inspect(filesDir: File): RuntimeInspection {
        val runtimeRoot = File(filesDir, "runtime/current")
        val backends = RuntimeBackendId.entries.map { backend ->
            inspectBackend(runtimeRoot, backend)
        }

        val aggregateState = when {
            backends.any { it.state == RuntimeState.FILES_PRESENT } ->
                RuntimeState.FILES_PRESENT
            backends.any { it.state == RuntimeState.INCOMPLETE } ->
                RuntimeState.INCOMPLETE
            else -> RuntimeState.NOT_INSTALLED
        }

        val missingItems = backends
            .filter { it.state == RuntimeState.INCOMPLETE }
            .flatMap { backend ->
                backend.missingItems.map { backend.backend.displayName + ": " + it }
            }

        return RuntimeInspection(
            state = aggregateState,
            runtimeRoot = runtimeRoot,
            backends = backends,
            missingItems = missingItems
        )
    }

    private fun inspectBackend(
        runtimeRoot: File,
        backend: RuntimeBackendId
    ): RuntimeBackendInspection {
        val backendRoot = File(runtimeRoot, "backends/" + backend.directoryName)
        val manifestFile = File(backendRoot, MANIFEST_NAME)

        if (!manifestFile.exists()) {
            return RuntimeBackendInspection(
                backend = backend,
                state = RuntimeState.NOT_INSTALLED,
                missingItems = emptyList(),
                runtimeVersion = null
            )
        }

        if (!manifestFile.isFile) {
            return incomplete(backend, "runtime.properties is not a regular file.")
        }

        val properties = try {
            Properties().apply {
                manifestFile.inputStream().use { load(it) }
            }
        } catch (error: Exception) {
            return incomplete(
                backend,
                "Could not read runtime.properties: " + error.javaClass.simpleName
            )
        }

        if (properties.getProperty("backendId") != backend.directoryName) {
            return incomplete(backend, "Backend ID does not match its directory.")
        }

        if (properties.getProperty("hostAbi") != REQUIRED_HOST_ABI) {
            return incomplete(backend, "This runtime package is not built for $REQUIRED_HOST_ABI.")
        }

        val requiredFiles = parsePathList(properties.getProperty("requiredFiles"))
        val executableFiles = parsePathList(properties.getProperty("executableFiles"))
        val requiredSymlinks = parsePathList(properties.getProperty("requiredSymlinks"))

        if (requiredFiles.isEmpty()) {
            return incomplete(backend, "The manifest has no requiredFiles entries.")
        }

        if (!requiredFiles.containsAll(executableFiles)) {
            return incomplete(
                backend,
                "Every executableFiles entry must also appear in requiredFiles."
            )
        }
        if (requiredSymlinks.any { it in requiredFiles || it in executableFiles }) {
            return incomplete(backend, "A path cannot be both a file and a symlink.")
        }

        val missing = mutableListOf<String>()
        val canonicalRoot = runCatching { backendRoot.canonicalFile }.getOrNull()
            ?: return incomplete(backend, "Could not resolve the backend directory.")
        val canonicalPrefix = canonicalRoot.path + File.separator

        for (relativePath in requiredFiles) {
            val safeFile = resolveSafeFile(backendRoot, canonicalPrefix, relativePath)
            if (safeFile == null) {
                missing += "Unsafe path in manifest: $relativePath"
                continue
            }

            if (!safeFile.isFile) {
                missing += "Missing file: $relativePath"
            } else if (relativePath in executableFiles && !safeFile.canExecute()) {
                missing += "File is not executable: $relativePath"
            }
        }

        for (relativePath in requiredSymlinks) {
            if (!isSafeSymlink(backendRoot, canonicalRoot, relativePath)) {
                missing += "Missing, broken or unsafe symlink: $relativePath"
            }
        }

        missing += RuntimePackageRequirements.inspectInstalled(backend, backendRoot)

        return RuntimeBackendInspection(
            backend = backend,
            state = if (missing.isEmpty()) {
                RuntimeState.FILES_PRESENT
            } else {
                RuntimeState.INCOMPLETE
            },
            missingItems = missing,
            runtimeVersion = properties.getProperty("runtimeVersion")?.takeIf { it.isNotBlank() }
        )
    }

    private fun resolveSafeFile(
        backendRoot: File,
        canonicalPrefix: String,
        relativePath: String
    ): File? {
        if (
            relativePath.isBlank() ||
            relativePath.startsWith('/') ||
            relativePath.startsWith('\\') ||
            relativePath.contains('\\') ||
            relativePath.contains('\u0000')
        ) {
            return null
        }

        val segments = relativePath.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            return null
        }

        val file = runCatching {
            File(backendRoot, relativePath).canonicalFile
        }.getOrNull() ?: return null

        return file.takeIf { it.path.startsWith(canonicalPrefix) }
    }


    private fun isSafeSymlink(
        backendRoot: File,
        canonicalRoot: File,
        relativePath: String
    ): Boolean {
        val safeRelativePath = SafeRuntimePath.normalizeRelativePath(relativePath)
            ?: return false

        return runCatching {
            val rootPath = canonicalRoot.toPath().normalize()
            val linkPath = backendRoot.toPath().resolve(safeRelativePath).normalize()
            if (!linkPath.startsWith(rootPath)) return false

            val domain = safeRelativePath.substringBefore('/')
            val domainRoot = if (domain == "rootfs" || domain == "host") {
                rootPath.resolve(domain).normalize()
            } else {
                rootPath
            }
            if (!linkPath.startsWith(domainRoot)) return false

            val parent = linkPath.parent ?: return false
            val realParent = parent.toRealPath()
            if (!realParent.startsWith(domainRoot)) return false
            if (!Files.isSymbolicLink(linkPath)) return false

            val rawTarget = Files.readSymbolicLink(linkPath)
            if (rawTarget.isAbsolute && domain == "host") return false
            val absoluteRoot = if (domain == "rootfs") domainRoot else rootPath
            val targetPath = if (rawTarget.isAbsolute) {
                absoluteRoot.resolve(rawTarget.toString().removePrefix("/")).normalize()
            } else {
                linkPath.parent.resolve(rawTarget).normalize()
            }
            if (!targetPath.startsWith(domainRoot)) return false

            // Rootfs symlinks may point to virtual mount paths such as
            // /proc/self/fd. Their final component may not exist until PRoot
            // starts, so validate the nearest existing ancestor instead.
            var existingAncestor = targetPath
            while (
                !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(existingAncestor)
            ) {
                existingAncestor = existingAncestor.parent ?: return false
            }

            val realAncestor = existingAncestor.toRealPath()
            realAncestor.startsWith(domainRoot)
        }.getOrDefault(false)
    }

    private fun parsePathList(value: String?): List<String> {
        return value.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun incomplete(
        backend: RuntimeBackendId,
        message: String
    ) = RuntimeBackendInspection(
        backend = backend,
        state = RuntimeState.INCOMPLETE,
        missingItems = listOf(message),
        runtimeVersion = null
    )

    private const val MANIFEST_NAME = "runtime.properties"
    private const val REQUIRED_HOST_ABI = "arm64-v8a"
}
