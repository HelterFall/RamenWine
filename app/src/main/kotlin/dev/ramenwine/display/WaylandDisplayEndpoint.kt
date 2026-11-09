package dev.ramenwine.display

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * A Wayland socket exported by an Android-host compositor and bind-mounted
 * into the per-game PRoot rootfs.
 *
 * The directory must be private to RamenWine and shared with the game runtime
 * only for the duration of the corresponding display session.
 */
data class WaylandDisplayEndpoint(
    val hostRuntimeDirectory: File,
    val socketName: String = "wayland-0"
)

/** Checks the filesystem node itself; a regular file with the same name is not enough. */
fun interface WaylandSocketValidator {
    fun isUnixDomainSocket(file: File): Boolean
}

/** Android implementation backed by lstat(2), available on the app's API 26 floor. */
object AndroidWaylandSocketValidator : WaylandSocketValidator {
    override fun isUnixDomainSocket(file: File): Boolean = runCatching {
        val mode = Os.lstat(file.absolutePath).st_mode
        mode and OsConstants.S_IFMT == OsConstants.S_IFSOCK
    }.getOrDefault(false)
}
