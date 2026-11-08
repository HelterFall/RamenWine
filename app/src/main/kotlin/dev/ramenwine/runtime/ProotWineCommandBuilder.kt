package dev.ramenwine.runtime

import dev.ramenwine.library.ImportedGame
import dev.ramenwine.inspection.SafeGamePath
import java.io.File
import java.io.IOException
import java.util.UUID

data class WaylandDisplayEndpoint(
    val hostRuntimeDirectory: File,
    val socketName: String = "wayland-0"
)

data class ProotWineLaunchRequest(
    val filesDirectory: File,
    val cacheDirectory: File,
    val game: ImportedGame,
    val display: WaylandDisplayEndpoint,
    val hostUid: Int,
    val locale: String = "C.UTF-8"
)

data class ProotWineCommand(
    val prootExecutable: File,
    val arguments: List<String>,
    val hostWorkingDirectory: File,
    val environment: Map<String, String>,
    val unsetEnvironment: Set<String>,
    val guestExecutablePath: String,
    val guestRuntimeDirectory: String
)

/**
 * Builds an argument-vector for the first Linux-rootfs launch backend.
 *
 * This builder does not execute anything. It validates every host/guest path
 * and prepares a PRoot -> Box64 -> Wine WoW64 command without invoking a shell.
 */
object ProotWineCommandBuilder {
    fun build(request: ProotWineLaunchRequest): ProotWineCommand {
        require(request.hostUid >= 0) { "Invalid Android UID." }
        require(ID_PATTERN.matches(request.game.id)) { "Invalid imported game ID." }
        require(request.game.relativeDirectory == "games/" + request.game.id) {
            "Imported game directory does not match its ID."
        }
        require(LOCALE_PATTERN.matches(request.locale)) { "Invalid locale name." }

        val executableRelativePath = SafeGamePath.normalizeRelativePath(
            request.game.executableRelativePath
        ) ?: throw IOException("The imported EXE path is unsafe.")

        if (!executableRelativePath.endsWith(".exe", ignoreCase = true)) {
            throw IOException("The selected game file is not an EXE.")
        }

        val filesRoot = request.filesDirectory.canonicalFile
        val runtimeRoot = File(filesRoot, "runtime/current").canonicalFile
        val backendsRoot = File(runtimeRoot, "backends").canonicalFile
        val backendRoot = File(backendsRoot, RuntimeBackendId.GLIBC_PROOT_BOX64.directoryName)
            .canonicalFile
        requireContained(runtimeRoot, backendRoot, "Runtime backend")
        
        val rootfs = File(backendRoot, "rootfs").canonicalFile
        requireContained(backendRoot, rootfs, "Linux rootfs")
        requireDirectory(rootfs, "Linux rootfs")

        val proot = executableFile(backendRoot, "host/bin/proot")
        val box64 = executableFile(rootfs, "usr/bin/box64")
        val wine64 = executableFile(rootfs, "usr/bin/wine64")

        val gamesRoot = File(filesRoot, "games").canonicalFile
        val gameDirectory = File(filesRoot, request.game.relativeDirectory).canonicalFile
        requireContained(gamesRoot, gameDirectory, "Imported game")
        requireDirectory(gameDirectory, "Imported game")

        val gameExecutable = File(gameDirectory, executableRelativePath).canonicalFile
        requireContained(gameDirectory, gameExecutable, "Game EXE")
        if (!gameExecutable.isFile || !gameExecutable.canRead()) {
            throw IOException("The selected game EXE is missing or unreadable.")
        }

        val prefixDirectory = ensurePrivateDirectory(
            File(filesRoot, "prefixes"),
            request.game.id
        )
        val homeDirectory = ensurePrivateDirectory(
            File(filesRoot, "wine-home"),
            request.game.id
        )
        val tempDirectory = ensurePrivateDirectory(
            File(request.cacheDirectory, "wine-tmp"),
            request.game.id
        )
        val prootTempDirectory = ensurePrivateDirectory(
            File(request.cacheDirectory, "proot-tmp"),
            request.game.id
        )

        val displayDirectory = request.display.hostRuntimeDirectory.canonicalFile
        requireDirectory(displayDirectory, "Wayland runtime directory")

        if (!SOCKET_NAME_PATTERN.matches(request.display.socketName)) {
            throw IOException("The Wayland socket name is invalid.")
        }

        val hostSocket = File(displayDirectory, request.display.socketName)
        if (!hostSocket.exists()) {
            throw IOException("The Wayland socket is not ready: " + hostSocket.absolutePath)
        }
        if (hostSocket.absolutePath.length >= MAX_UNIX_SOCKET_PATH_BYTES) {
            throw IOException("The Wayland socket path is too long for Android AF_UNIX.")
        }

        val guestGameDirectory = "/games/" + request.game.id
        val guestPrefixDirectory = "/prefixes/" + request.game.id
        val guestHomeDirectory = "/home/ramen"
        val guestTempDirectory = "/tmp"
        val guestRuntimeDirectory = "/run/user/" + request.hostUid
        val guestExecutable = guestGameDirectory + "/" + executableRelativePath

        val arguments = buildList {
            add("--rootfs=" + rootfs.absolutePath)
            add("--bind=/dev:/dev")
            add("--bind=/proc:/proc")
            add("--bind=" + gameDirectory.absolutePath + ":" + guestGameDirectory)
            add("--bind=" + prefixDirectory.absolutePath + ":" + guestPrefixDirectory)
            add("--bind=" + homeDirectory.absolutePath + ":" + guestHomeDirectory)
            add("--bind=" + tempDirectory.absolutePath + ":" + guestTempDirectory)
            add("--bind=" + displayDirectory.absolutePath + ":" + guestRuntimeDirectory)
            add("--pwd=" + guestGameDirectory)
            add("/usr/bin/box64")
            add("/usr/bin/wine64")
            add(guestExecutable)
        }

        val environment = linkedMapOf(
            "HOME" to guestHomeDirectory,
            "WINEPREFIX" to guestPrefixDirectory,
            "WINEDEBUG" to "err+all",
            "WAYLAND_DISPLAY" to request.display.socketName,
            "XDG_RUNTIME_DIR" to guestRuntimeDirectory,
            "TMPDIR" to guestTempDirectory,
            "PROOT_TMP_DIR" to prootTempDirectory.absolutePath,
            "LANG" to request.locale,
            "LC_ALL" to request.locale,
            "PATH" to "/usr/local/bin:/usr/bin:/bin",
            "BOX64_NOBANNER" to "1"
        )

        return ProotWineCommand(
            prootExecutable = proot,
            arguments = arguments,
            hostWorkingDirectory = backendRoot,
            environment = environment,
            unsetEnvironment = setOf(
                "DISPLAY",
                "WAYLAND_SOCKET",
                "LD_LIBRARY_PATH",
                "LD_PRELOAD"
            ),
            guestExecutablePath = guestExecutable,
            guestRuntimeDirectory = guestRuntimeDirectory
        )
    }

    private fun executableFile(root: File, relativePath: String): File {
        val normalized = SafeGamePath.normalizeRelativePath(relativePath)
            ?: throw IOException("Unsafe runtime executable path.")
        val file = File(root, normalized).canonicalFile
        requireContained(root, file, "Runtime executable")
        if (!file.isFile || !file.canExecute()) {
            throw IOException("Required runtime executable is missing or not executable: " + relativePath)
        }
        return file
    }

    private fun ensurePrivateDirectory(parent: File, childName: String): File {
        if (!ID_PATTERN.matches(childName)) {
            throw IOException("Unsafe private runtime directory name.")
        }

        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create private directory: " + parent.absolutePath)
        }

        val canonicalParent = parent.canonicalFile
        val child = File(parent, childName)
        if (!child.exists() && !child.mkdirs()) {
            throw IOException("Could not create per-game runtime directory.")
        }

        val canonicalChild = child.canonicalFile
        requireContained(canonicalParent, canonicalChild, "Per-game runtime directory")
        if (!canonicalChild.isDirectory) {
            throw IOException("Per-game runtime path is not a directory.")
        }
        return canonicalChild
    }

    private fun requireDirectory(directory: File, label: String) {
        if (!directory.isDirectory) {
            throw IOException(label + " directory is missing: " + directory.absolutePath)
        }
    }

    private fun requireContained(parent: File, child: File, label: String) {
        val canonicalParent = parent.canonicalFile
        val canonicalChild = child.canonicalFile
        val prefix = canonicalParent.path + File.separator
        if (canonicalChild != canonicalParent && !canonicalChild.path.startsWith(prefix)) {
            throw IOException(label + " escapes its allowed directory.")
        }
    }

    private const val MAX_UNIX_SOCKET_PATH_BYTES = 108
    private val ID_PATTERN = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val SOCKET_NAME_PATTERN = Regex("[A-Za-z0-9_.-]{1,40}")
    private val LOCALE_PATTERN = Regex("[A-Za-z0-9_.@-]{1,64}")
}
