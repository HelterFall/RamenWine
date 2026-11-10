package dev.ramenwine.display

import java.io.File

/**
 * Native libraries loaded by Wawona's Android JNI host.
 *
 * Names match WawonaNative.loadLibraries() in the pinned upstream revision.
 * This is an inventory check, not proof that the ELF dependencies can load.
 */
internal object WawonaLibraryInventory {
    val requiredFileNames: List<String> = listOf(
        "libEGL_angle.so",
        "libGLESv2_angle.so",
        "libwawona.so"
    )

    fun missingFiles(nativeLibraryDirectory: File): List<String> =
        requiredFileNames.filterNot { name ->
            val file = File(nativeLibraryDirectory, name)
            file.isFile && file.length() > 0L
        }
}
