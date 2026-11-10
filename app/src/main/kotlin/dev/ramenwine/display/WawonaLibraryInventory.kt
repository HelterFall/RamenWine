package dev.ramenwine.display

import java.io.File

/**
 * Checks the complete native library closure recorded by the pinned Wawona APK.
 * Presence is only a packaging check; the Android linker must still load it.
 */
internal object WawonaLibraryInventory {
    val requiredFileNames: List<String> = listOf(
        "libEGL_angle.so",
        "libGLESv2_angle.so",
        "libwawona.so"
    )

    fun missingFiles(nativeLibraryDirectory: File): List<String> =
        missingFiles(nativeLibraryDirectory, requiredFileNames.toSet())

    fun missingFiles(
        nativeLibraryDirectory: File,
        expectedLibraries: Map<String, String>
    ): List<String> {
        val missing = linkedSetOf<String>()
        requiredFileNames.filterNotTo(missing) { it in expectedLibraries }

        for (name in expectedLibraries.keys) {
            if (!SAFE_LIBRARY_NAME.matches(name)) {
                missing += name
                continue
            }
            val file = File(nativeLibraryDirectory, name)
            if (!file.isFile || file.length() <= 0L) {
                missing += name
            }
        }
        return missing.toList().sorted()
    }

    private val SAFE_LIBRARY_NAME =
        Regex("lib[A-Za-z0-9_.+-]+\\.so(?:\\.[0-9]+)*")
}
