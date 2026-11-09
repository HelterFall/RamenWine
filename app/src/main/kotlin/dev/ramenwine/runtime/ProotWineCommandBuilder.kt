package dev.ramenwine.runtime

import dev.ramenwine.display.AndroidWaylandSocketValidator
import dev.ramenwine.display.WaylandDisplayEndpoint
import dev.ramenwine.display.WaylandSocketValidator
import dev.ramenwine.library.ImportedGame
import dev.ramenwine.inspection.SafeGamePath
import java.io.File
import java.io.IOException

enum class ProotWineOperation {
    RUN_GAME,
    INITIALIZE_PREFIX
}

data class ProotWineLaunchRequest(
    val filesDirectory: File,
    val cacheDirectory: File,
    val game: ImportedGame,
    val display: WaylandDisplayEndpoint,
    val hostUid: Int,
    val locale: String = "C.UTF-8",
    val operation: ProotWineOperation = ProotWineOperation.RUN_GAME
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
    fun build(
        request: ProotWineLaunchRequest,
        socketValidator: WaylandSocketValidator = AndroidWaylandSocketValidator
    ): ProotWineCommand {
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
        requireContained(filesRoot, runtimeRoot, "Runtime root")
        val backendsRoot = File(runtimeRoot, "backends").canonicalFile
        requireContained(runtimeRoot, backendsRoot, "Runtime backends")
        val backendRoot = File(backendsRoot, RuntimeBackendId.GLIBC_PROOT_BOX64.directoryName)
            .canonicalFile
        requireContained(runtimeRoot, backendRoot, "Runtime backend")
        
        val rootfs = File(backendRoot, "rootfs").canonicalFile
        requireContained(backendRoot, rootfs, "Linux rootfs")
        requireDirectory(rootfs, "Linux rootfs")

        // The guest rootfs is ARM64 Linux/glibc: Box64 is an ARM64 Linux
        // executable and needs the ARM64 loader before it can start. Box64 then
        // loads x86-64 Wine and its guest libraries through the x86-64 loader.
        // Check both ABI loaders and both halves of Wine's Wayland driver.
        runtimeFile(
            rootfs,
            ARM64_GLIBC_LOADER_PATH,
            "ARM64 glibc loader"
        )
        runtimeFile(
            rootfs,
            GLIBC_LOADER_PATH,
            "x86-64 glibc loader"
        )
        runtimeFile(
            rootfs,
            WINE_WAYLAND_UNIX_DRIVER_PATH,
            "Wine Wayland Unix driver"
        )
        runtimeFile(
            rootfs,
            WINE_WAYLAND_PE_DRIVER_PATH,
            "Wine Wayland PE driver"
        )

        val proot = executableFile(backendRoot, "host/bin/proot")
        val hostLibraryDirectory = File(backendRoot, "host/lib").canonicalFile
        requireContained(backendRoot, hostLibraryDirectory, "PRoot host libraries")
        requireDirectory(hostLibraryDirectory, "PRoot host libraries")
        executableFile(rootfs, "usr/bin/box64")
        executableFile(rootfs, "usr/bin/wine")
        if (request.operation == ProotWineOperation.INITIALIZE_PREFIX) {
            // Wine packages expose wineboot as an executable/symlink beside
            // the unified Wine loader.
            executableFile(rootfs, "usr/bin/wineboot")
        }

        val gamesRoot = File(filesRoot, "games").canonicalFile
        val gameDirectory = File(filesRoot, request.game.relativeDirectory).canonicalFile
        requireContained(gamesRoot, gameDirectory, "Imported game")
        requireDirectory(gameDirectory, "Imported game")

        val gameExecutable = File(gameDirectory, executableRelativePath).canonicalFile
        requireContained(gameDirectory, gameExecutable, "Game EXE")
        if (!gameExecutable.isFile || !gameExecutable.canRead()) {
            throw IOException("The selected game EXE is missing or unreadable.")
        }

        val cacheRoot = request.cacheDirectory.canonicalFile
        val prefixDirectory = ensurePrivateDirectory(
            filesRoot,
            File(filesRoot, "prefixes"),
            request.game.id,
            "Wine prefix"
        )
        val homeDirectory = ensurePrivateDirectory(
            filesRoot,
            File(filesRoot, "wine-home"),
            request.game.id,
            "Wine home"
        )
        val tempDirectory = ensurePrivateDirectory(
            cacheRoot,
            File(cacheRoot, "wine-tmp"),
            request.game.id,
            "Wine temporary"
        )
        val prootTempDirectory = ensurePrivateDirectory(
            cacheRoot,
            File(cacheRoot, "proot-tmp"),
            request.game.id,
            "PRoot temporary"
        )

        val displayDirectory = request.display.hostRuntimeDirectory.canonicalFile
        requireContained(cacheRoot, displayDirectory, "Wayland runtime directory")
        requireDirectory(displayDirectory, "Wayland runtime directory")

        if (!SOCKET_NAME_PATTERN.matches(request.display.socketName)) {
            throw IOException("The Wayland socket name is invalid.")
        }

        val hostSocket = File(displayDirectory, request.display.socketName)
        if (!hostSocket.exists()) {
            throw IOException("The Wayland socket is not ready: " + hostSocket.absolutePath)
        }
        if (!socketValidator.isUnixDomainSocket(hostSocket)) {
            throw IOException("The Wayland endpoint is not a Unix-domain socket: " + hostSocket.absolutePath)
        }
        if (hostSocket.absolutePath.toByteArray(Charsets.UTF_8).size >= MAX_UNIX_SOCKET_PATH_BYTES) {
            throw IOException("The Wayland socket path is too long for Android AF_UNIX.")
        }

        val guestGameDirectory = "/games/" + request.game.id
        val guestPrefixDirectory = "/prefixes/" + request.game.id
        val guestHomeDirectory = "/home/ramen"
        val guestTempDirectory = "/tmp"
        val guestRuntimeDirectory = "/run/user/" + request.hostUid
        val guestExecutable = when (request.operation) {
            ProotWineOperation.RUN_GAME -> guestGameDirectory + "/" + executableRelativePath
            ProotWineOperation.INITIALIZE_PREFIX -> "/usr/bin/wineboot"
        }

        // PRoot bind targets must exist in the guest filesystem. Create only
        // app-private directories inside this runtime's rootfs.
        listOf(
            "/dev",
            "/proc",
            guestGameDirectory,
            guestPrefixDirectory,
            guestHomeDirectory,
            guestTempDirectory,
            guestRuntimeDirectory
        ).forEach { guestPath ->
            ensureGuestDirectory(rootfs, guestPath)
        }

        val arguments = buildList {
            add("--kill-on-exit")
            add("--link2symlink")
            add("--sysvipc")
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
            when (request.operation) {
                ProotWineOperation.RUN_GAME -> {
                    add("/usr/bin/wine")
                    add(guestExecutable)
                }
                ProotWineOperation.INITIALIZE_PREFIX -> {
                    add("/usr/bin/wineboot")
                    add("--init")
                }
            }
        }

        val environment = linkedMapOf(
            "HOME" to guestHomeDirectory,
            "WINEPREFIX" to guestPrefixDirectory,
            "WINEDEBUG" to "err+all",
            "WAYLAND_DISPLAY" to request.display.socketName,
            "XDG_RUNTIME_DIR" to guestRuntimeDirectory,
            "TMPDIR" to guestTempDirectory,
            "PROOT_TMP_DIR" to prootTempDirectory.absolutePath,
            // LD_LIBRARY_PATH resolves Bionic dependencies for PRoot only; host/lib is not bound into the guest rootfs.
            "LD_LIBRARY_PATH" to File(backendRoot, "host/lib").canonicalPath,
            // BOX64_LD_LIBRARY_PATH is separate: it searches x86-64 ELF libraries
            // inside the guest rootfs (glibc, Wayland and Wine Unix libraries).
            "BOX64_LD_LIBRARY_PATH" to GUEST_X64_LIBRARY_PATH,
            "WINEDLLPATH" to GUEST_WINE_DLL_PATH,
            "WINEARCH" to "win64",
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
                "LD_PRELOAD",
                "ANDROID_ROOT",
                "ANDROID_DATA",
                "BOOTCLASSPATH",
                "ANDROID_ART_ROOT",
                "ANDROID_I18N_ROOT",
                "ANDROID_TZDATA_ROOT"
            ),
            guestExecutablePath = guestExecutable,
            guestRuntimeDirectory = guestRuntimeDirectory
        )
    }


    private fun ensureGuestDirectory(rootfs: File, guestAbsolutePath: String) {
        if (!guestAbsolutePath.startsWith('/')) {
            throw IOException("Guest mount target must be absolute.")
        }

        val relativePath = SafeGamePath.normalizeRelativePath(
            guestAbsolutePath.removePrefix("/")
        ) ?: throw IOException("Unsafe guest mount target.")

        val directory = File(rootfs, relativePath)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create guest mount point: " + guestAbsolutePath)
        }

        val canonicalRootfs = rootfs.canonicalFile
        val canonicalDirectory = directory.canonicalFile
        requireContained(canonicalRootfs, canonicalDirectory, "Guest mount point")
        if (!canonicalDirectory.isDirectory) {
            throw IOException("Guest mount target is not a directory: " + guestAbsolutePath)
        }
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

    private fun runtimeFile(root: File, relativePath: String, label: String): File {
        val normalized = SafeGamePath.normalizeRelativePath(relativePath)
            ?: throw IOException("Unsafe " + label + " path.")
        val file = File(root, normalized).canonicalFile
        requireContained(root, file, label)
        if (!file.isFile || !file.canRead()) {
            throw IOException("Required " + label + " is missing or unreadable: " + relativePath)
        }
        return file
    }

    private fun ensurePrivateDirectory(
        allowedRoot: File,
        parent: File,
        childName: String,
        label: String
    ): File {
        if (!ID_PATTERN.matches(childName)) {
            throw IOException("Unsafe private runtime directory name.")
        }

        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create private directory: " + parent.absolutePath)
        }

        val canonicalRoot = allowedRoot.canonicalFile
        val canonicalParent = parent.canonicalFile
        requireContained(canonicalRoot, canonicalParent, label + " root")

        val child = File(canonicalParent, childName)
        if (!child.exists() && !child.mkdirs()) {
            throw IOException("Could not create per-game " + label + " directory.")
        }

        val canonicalChild = child.canonicalFile
        requireContained(canonicalParent, canonicalChild, label)
        if (!canonicalChild.isDirectory) {
            throw IOException("Per-game " + label + " path is not a directory.")
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
    private const val ARM64_GLIBC_LOADER_PATH = "lib/ld-linux-aarch64.so.1"
    private const val GLIBC_LOADER_PATH = "lib64/ld-linux-x86-64.so.2"
    private const val WINE_WAYLAND_UNIX_DRIVER_PATH =
        "usr/lib/x86_64-linux-gnu/wine/x86_64-unix/winewayland.so"
    private const val WINE_WAYLAND_PE_DRIVER_PATH =
        "usr/lib/x86_64-linux-gnu/wine/x86_64-windows/winewayland.drv"
    private const val GUEST_X64_LIBRARY_PATH =
        "/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu:" +
            "/usr/lib/x86_64-linux-gnu/wine/x86_64-unix"
    private const val GUEST_WINE_DLL_PATH =
        "/usr/lib/x86_64-linux-gnu/wine/x86_64-windows:" +
            "/usr/lib/x86_64-linux-gnu/wine/x86_64-unix"
    private val ID_PATTERN = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val SOCKET_NAME_PATTERN = Regex("[A-Za-z0-9_.-]{1,40}")
    private val LOCALE_PATTERN = Regex("[A-Za-z0-9_.@-]{1,64}")
}
