package dev.ramenwine.display

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Verifies the native-library closure inside the installed base APK.
 *
 * The app uses legacy JNI packaging so Wawona's dynamic helper-library
 * loading can resolve extracted files from nativeLibraryDir. We still inspect
 * sourceDir here to verify the exact bytes shipped in the APK against the
 * pinned provenance manifest before System.loadLibrary is attempted.
 */
internal object WawonaLibraryInventory {
    val requiredFileNames: List<String> = listOf(
        "libEGL_angle.so",
        "libGLESv2_angle.so",
        "libwawona.so"
    )

    fun missingFromApk(
        apkFile: File,
        expectedLibraries: Map<String, String>
    ): List<String> {
        val missing = linkedSetOf<String>()
        requiredFileNames.filterNotTo(missing) { it in expectedLibraries }

        if (!apkFile.isFile) {
            missing += "APK unavailable"
            return missing.toList().sorted()
        }

        val zip = runCatching { ZipFile(apkFile) }.getOrElse {
            missing += "APK unreadable"
            return missing.toList().sorted()
        }

        zip.use { archive ->
            var totalBytes = 0L
            for ((name, expectedHash) in expectedLibraries) {
                if (!SAFE_LIBRARY_NAME.matches(name) ||
                    !SHA256_PATTERN.matches(expectedHash)
                ) {
                    missing += name
                    continue
                }

                val entry = archive.getEntry("lib/arm64-v8a/$name")
                if (entry == null || entry.isDirectory ||
                    entry.size <= 0L || entry.size > MAX_LIBRARY_BYTES
                ) {
                    missing += name
                    continue
                }

                totalBytes += entry.size
                if (totalBytes > MAX_TOTAL_LIBRARY_BYTES) {
                    missing += "Native library payload exceeds the verification limit"
                    break
                }

                val actualHash = runCatching {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(BUFFER_BYTES)
                    archive.getInputStream(entry).buffered().use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) digest.update(buffer, 0, count)
                        }
                    }
                    digest.digest().joinToString("") { byte ->
                        "%02x".format(byte.toInt() and 0xff)
                    }
                }.getOrNull()

                if (actualHash != expectedHash) {
                    missing += name
                }
            }
        }

        return missing.toList().sorted()
    }

    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_LIBRARY_BYTES = 512L * 1024L * 1024L
    private const val MAX_TOTAL_LIBRARY_BYTES = 1536L * 1024L * 1024L
    private val SAFE_LIBRARY_NAME =
        Regex("lib[A-Za-z0-9_.+-]+\\.so(?:\\.[0-9]+)*")
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
}
