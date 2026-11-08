package dev.ramenwine.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class RuntimePackageFile(
    val path: String,
    val sha256: String,
    val executable: Boolean
)

data class RuntimePackageSymlink(
    val path: String,
    val target: String
)

data class RuntimePackageManifest(
    val backend: RuntimeBackendId,
    val hostAbi: String,
    val runtimeVersion: String,
    val files: List<RuntimePackageFile>,
    val symlinks: List<RuntimePackageSymlink>
)

object RuntimePackageManifestParser {
    fun parse(json: String): RuntimePackageManifest {
        val root = JSONObject(json)

        require(root.optInt("schemaVersion", -1) == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported runtime package manifest schema."
        }

        val backendId = root.optString("backendId")
        val backend = RuntimeBackendId.entries.firstOrNull {
            it.directoryName == backendId
        } ?: throw IllegalArgumentException("Unknown runtime backend: $backendId")

        val hostAbi = root.optString("hostAbi")
        require(hostAbi == "arm64-v8a") {
            "Runtime package ABI is not arm64-v8a."
        }

        val version = root.optString("runtimeVersion")
        require(VERSION_PATTERN.matches(version)) {
            "Runtime version is missing or contains unsupported characters."
        }

        val filesArray = root.optJSONArray("files") ?: JSONArray()
        require(filesArray.length() in 1..MAX_ENTRIES) {
            "Runtime manifest must contain between 1 and $MAX_ENTRIES files."
        }

        val seenPaths = HashSet<String>()
        val files = buildList {
            for (index in 0 until filesArray.length()) {
                val item = filesArray.optJSONObject(index)
                    ?: throw IllegalArgumentException("Invalid file entry at index $index.")

                val path = SafeRuntimePath.normalizeRelativePath(item.optString("path"))
                    ?: throw IllegalArgumentException("Unsafe runtime file path at index $index.")

                require(seenPaths.add(path.lowercase(Locale.ROOT))) {
                    "Duplicate runtime path (case-insensitive): $path"
                }

                val digest = item.optString("sha256").lowercase()
                require(SHA256_PATTERN.matches(digest)) {
                    "Invalid SHA-256 digest for $path."
                }

                add(
                    RuntimePackageFile(
                        path = path,
                        sha256 = digest,
                        executable = item.optBoolean("executable", false)
                    )
                )
            }
        }

        val symlinkArray = root.optJSONArray("symlinks") ?: JSONArray()
        require(symlinkArray.length() <= MAX_ENTRIES) {
            "Too many runtime symlinks."
        }

        val symlinks = buildList {
            for (index in 0 until symlinkArray.length()) {
                val item = symlinkArray.optJSONObject(index)
                    ?: throw IllegalArgumentException("Invalid symlink entry at index $index.")

                val path = SafeRuntimePath.normalizeRelativePath(item.optString("path"))
                    ?: throw IllegalArgumentException("Unsafe runtime symlink path at index $index.")

                require(seenPaths.add(path)) { "Duplicate runtime path: $path" }

                val target = item.optString("target")
                require(target.isNotBlank()) { "Missing symlink target for $path." }

                add(RuntimePackageSymlink(path, target))
            }
        }

        return RuntimePackageManifest(
            backend = backend,
            hostAbi = hostAbi,
            runtimeVersion = version,
            files = files,
            symlinks = symlinks
        )
    }

    private const val SUPPORTED_SCHEMA_VERSION = 1
    private const val MAX_ENTRIES = 100_000
    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}
