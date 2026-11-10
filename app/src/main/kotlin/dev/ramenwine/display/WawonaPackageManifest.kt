package dev.ramenwine.display

import android.content.Context
import org.json.JSONObject

internal data class WawonaPackageManifest(
    val sourceRevision: String,
    val sourceVersion: String,
    val apkSha256: String,
    val nativeLibraries: Map<String, String>,
    val assets: Map<String, String>
)

internal object WawonaPackageManifestReader {
    const val ASSET_PATH = "wawona/provenance.json"
    const val PINNED_SOURCE_REVISION =
        "75ffe2982bd7d99b29b7e743c4f70a9becf61f86"
    const val PINNED_SOURCE_VERSION = "26.8.12"
    const val PINNED_SOURCE_RELEASE_TAG = "v26.8.12"
    const val PINNED_APK_SHA256 = "f9a3f82ac9c6e47701622f9f3d9be355cb549438e34c49760c7ac9b528dc5570"

    private val hashPattern = Regex("[0-9a-f]{64}")
    private val nativeLibraryNamePattern = Regex("lib[A-Za-z0-9_.+-]+\\.so(?:\\.[0-9]+)*")

    fun read(context: Context): WawonaPackageManifest {
        val jsonText = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
        require(jsonText.length <= MAX_MANIFEST_CHARS) {
            "Wawona provenance manifest is too large."
        }

        val root = JSONObject(jsonText)
        require(root.optInt("schemaVersion", -1) == 1) {
            "Unsupported Wawona package manifest schema."
        }

        val revision = root.getString("sourceRevision")
        require(revision == PINNED_SOURCE_REVISION) {
            "Wawona bundle source revision does not match the pinned adapter."
        }
        require(root.getString("sourceReleaseTag") == PINNED_SOURCE_RELEASE_TAG) {
            "Wawona release tag does not match the pinned adapter."
        }
        require(root.getString("sourceAsset") == "Wawona-26.8.12.apk") {
            "Wawona source asset does not match the pinned adapter."
        }

        val sourceVersion = root.getString("sourceVersion")
        require(sourceVersion == PINNED_SOURCE_VERSION) {
            "Wawona source version does not match the pinned adapter."
        }

        val apkSha256 = root.getString("apkSha256")
        require(hashPattern.matches(apkSha256) && apkSha256 == PINNED_APK_SHA256) {
            "Wawona APK digest does not match the pinned official release."
        }
        require(root.getString("upstreamAssetSha256") == PINNED_APK_SHA256) {
            "Wawona upstream release asset digest is invalid."
        }

        val nativeObject = root.getJSONObject("nativeLibraries")
        val nativeLibraries = readHashMap(nativeObject, MAX_NATIVE_LIBRARIES) { name ->
            require(nativeLibraryNamePattern.matches(name)) {
                "Invalid Wawona native library name: $name"
            }
        }

        WawonaLibraryInventory.requiredFileNames.forEach { required ->
            require(required in nativeLibraries) {
                "Wawona package is missing required native library $required."
            }
        }

        val assetsObject = root.getJSONObject("assets")
        val assets = readHashMap(assetsObject, MAX_ASSET_FILES) { assetPath ->
            require(WawonaAssetIntegrity.destinationRelativePath(assetPath) != null) {
                "Unsupported Wawona asset path: $assetPath"
            }
        }

        require(assets.keys.any { it.startsWith("wawona/xkb/") }) {
            "Wawona XKB support assets are missing."
        }
        require(assets.keys.any { it.startsWith("wawona/fonts/") }) {
            "Wawona font assets are missing."
        }
        require(assets.keys.any { it.startsWith("wawona/weston/") }) {
            "Wawona Weston support assets are missing."
        }

        return WawonaPackageManifest(
            sourceRevision = revision,
            sourceVersion = sourceVersion,
            apkSha256 = apkSha256,
            nativeLibraries = nativeLibraries,
            assets = assets
        )
    }

    private inline fun readHashMap(
        objectValue: JSONObject,
        maximumEntries: Int,
        validateKey: (String) -> Unit
    ): Map<String, String> {
        val keys = objectValue.keys()
        val result = LinkedHashMap<String, String>()
        while (keys.hasNext()) {
            require(result.size < maximumEntries) {
                "Wawona package manifest contains too many entries."
            }
            val key = keys.next()
            validateKey(key)
            val hash = objectValue.getString(key)
            require(hashPattern.matches(hash)) {
                "Invalid SHA-256 for Wawona package entry $key."
            }
            require(result.put(key, hash) == null) {
                "Duplicate Wawona package entry $key."
            }
        }
        return result
    }

    private const val MAX_MANIFEST_CHARS = 16 * 1024 * 1024
    private const val MAX_NATIVE_LIBRARIES = 2_000
    private const val MAX_ASSET_FILES = 100_000
}
