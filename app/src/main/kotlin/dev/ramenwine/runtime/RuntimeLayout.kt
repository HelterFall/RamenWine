package dev.ramenwine.runtime

import java.io.File

enum class RuntimeState {
    NOT_INSTALLED,
    INCOMPLETE,
    FILES_PRESENT
}

data class RuntimeInspection(
    val state: RuntimeState,
    val runtimeRoot: File,
    val missingItems: List<String>
)

/**
 * Describes the planned on-device layout for the host PRoot binary and the
 * Linux rootfs. Presence checks are not a runtime self-test.
 */
object RuntimeLayout {
    fun inspect(filesDir: File): RuntimeInspection {
        val runtimeRoot = File(filesDir, "runtime/current")
        val expected = listOf(
            "host/bin/proot" to "Android PRoot host binary",
            "rootfs/usr/bin/box64" to "Box64 inside Linux rootfs",
            "rootfs/usr/bin/wine" to "Wine loader inside Linux rootfs"
        )

        val missing = expected
            .filter { (relativePath, _) ->
                val file = File(runtimeRoot, relativePath)
                !file.isFile || !file.canExecute()
            }
            .map { (_, description) -> description }

        val state = when {
            !runtimeRoot.exists() -> RuntimeState.NOT_INSTALLED
            missing.isNotEmpty() -> RuntimeState.INCOMPLETE
            else -> RuntimeState.FILES_PRESENT
        }

        return RuntimeInspection(state, runtimeRoot, missing)
    }
}
