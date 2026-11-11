package dev.ramenwine.runtime

import dev.ramenwine.library.ImportedGame
import dev.ramenwine.library.GameLocaleProfile
import dev.ramenwine.inspection.SafeGamePath
import java.io.File
import java.io.IOException

data class WaylandDisplayEndpoint(
    val hostRuntimeDirectory: File,
    val socketName: String = "wayland-0"
)

data class ProotWineLaunchRequest(
    val filesDirectory: File,
    val cacheDirectory: File,
    /** APK-extracted Android-host binaries; app-data files are not execve()-eligible on API 29+. */
    val nativeLibraryDirectory: File,
    val game: ImportedGame,
    val display: WaylandDisplayEndpoint,
    val hostUid: Int,
    val locale: String = GameLocaleProfile.DEFAULT_LOCALE
)

data class ProotWineCommand(
    val prootExecutable: File,
    val arguments: List<String>,
    val hostWorkingDirectory: File,
    /** Environment used by the Android-host PRoot process only. */
    val environment: Map<String, String>,
    /** Clean Linux-rootfs environment applied by the guest /usr/bin/env. */
    val guestEnvironment: Map<String, String>,
    val unsetEnvironment: Set<String>,
    val guestExecutablePath: String,
    val guestWorkingDirectory: String,
    val guestRuntimeDirectory: String,
    val guestRootfsDirectory: File,
    val hostPrefixDirectory: File,
    val runtimeVersion: String
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
        if (!GameLocaleProfile.isSupported(request.locale)) {
            throw IOException("The selected game locale is not included in this runtime.")
        }

        val executableRelativePath = SafeGamePath.normalizeRelativePath(
            request.game.executableRelativePath
        ) ?: throw IOException("The imported EXE path is unsafe.")

        if (!executableRelativePath.endsWith(".exe", ignoreCase = true)) {
            throw IOException("The selected game file is not an EXE.")
        }
        if (request.game.architecture != "x86" && request.game.architecture != "x86-64") {
            throw IOException(
                "This Wine/Box64 runtime supports Windows x86 and x86-64 games only. " +
                    "This game is marked as " + request.game.architecture + "."
            )
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

        val runtimePropertiesFile = File(backendRoot, "runtime.properties")
        if (!runtimePropertiesFile.isFile) {
            throw IOException("Runtime metadata is missing.")
        }
        val runtimeMetadata = try {
            java.util.Properties().apply {
                runtimePropertiesFile.inputStream().use { load(it) }
            }
        } catch (error: Exception) {
            throw IOException("Runtime metadata could not be read.", error)
        }
        if (runtimeMetadata.getProperty("backendId") !=
            RuntimeBackendId.GLIBC_PROOT_BOX64.directoryName
        ) {
            throw IOException("Runtime metadata backend ID does not match the selected backend.")
        }
        if (runtimeMetadata.getProperty("hostAbi") != "arm64-v8a") {
            throw IOException("The installed PRoot runtime is not built for arm64-v8a.")
        }
        val runtimeVersion = runtimeMetadata.getProperty("runtimeVersion")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 128 }
            ?: throw IOException("Runtime version is missing from runtime.properties.")

        // Keep the runtime package's copies as integrity-checked payload files,
        // but execute Android-host ELF programs only from the APK-extracted native
        // library directory. Android 10+ denies execve() from writable filesDir.
        requireRuntimeFile(backendRoot, "host/bin/proot")
        requireRuntimeFile(backendRoot, "host/bin/directaudio-relay")
        val nativeLibraryDirectory = request.nativeLibraryDirectory.canonicalFile
        val proot = androidHostExecutable(nativeLibraryDirectory, "libproot.so")
        androidHostExecutable(nativeLibraryDirectory, "libdirectaudio-relay.so")
        val prootLoader = androidHostExecutable(nativeLibraryDirectory, "libproot-loader.so")

        val hostLibraryDirectory = File(backendRoot, "host/lib").canonicalFile
        requireContained(backendRoot, hostLibraryDirectory, "PRoot host libraries")
        requireDirectory(hostLibraryDirectory, "PRoot host libraries")
        executableFile(rootfs, "usr/bin/env")
        executableFile(rootfs, "usr/bin/touch")
        executableFile(rootfs, "usr/bin/box64")
        executableFile(rootfs, "usr/bin/wine")
        executableFile(rootfs, "usr/bin/wineserver")
        executableFile(rootfs, "bin/sh")
        executableFile(rootfs, "opt/ramenwine-tools/run-wine-command.sh")
        requireRuntimeFile(rootfs, "opt/ramenwine-audio/lib/wine/x86_64-unix/winedirectaudio.so")
        requireRuntimeFile(rootfs, "opt/ramenwine-audio/lib/wine/x86_64-windows/winedirectaudio.drv")
        requireRuntimeFile(rootfs, "opt/ramenwine-audio/lib/wine/i386-windows/winedirectaudio.drv")

        val gamesRoot = File(filesRoot, "games").canonicalFile
        val gameDirectory = File(filesRoot, request.game.relativeDirectory).canonicalFile
        requireContained(gamesRoot, gameDirectory, "Imported game")
        requireDirectory(gameDirectory, "Imported game")

        val gameExecutable = File(gameDirectory, executableRelativePath).canonicalFile
        requireContained(gameDirectory, gameExecutable, "Game EXE")
        if (!gameExecutable.isFile || !gameExecutable.canRead()) {
            throw IOException("The selected game EXE is missing or unreadable.")
        }

        val executableParentRelativePath = executableRelativePath.substringBeforeLast('/', "")
        val gameWorkingDirectory = if (executableParentRelativePath.isEmpty()) {
            gameDirectory
        } else {
            File(gameDirectory, executableParentRelativePath).canonicalFile
        }
        requireContained(gameDirectory, gameWorkingDirectory, "Game working directory")
        if (!gameWorkingDirectory.isDirectory) {
            throw IOException("The selected EXE working directory is missing or unreadable.")
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
        if (hostSocket.absolutePath.toByteArray(Charsets.UTF_8).size >= MAX_UNIX_SOCKET_PATH_BYTES) {
            throw IOException("The Wayland socket path is too long for Android AF_UNIX.")
        }

        val guestGameDirectory = "/games/" + request.game.id
        val guestPrefixDirectory = "/prefixes/" + request.game.id
        val guestHomeDirectory = "/home/ramen"
        val guestTempDirectory = "/tmp"
        val guestRuntimeDirectory = "/run/user/" + request.hostUid
        val guestExecutable = guestGameDirectory + "/" + executableRelativePath
        val guestWorkingDirectory = if (executableParentRelativePath.isEmpty()) {
            guestGameDirectory
        } else {
            guestGameDirectory + "/" + executableParentRelativePath
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

        val guestEnvironment = linkedMapOf(
            "HOME" to guestHomeDirectory,
            "WINEPREFIX" to guestPrefixDirectory,
            "WINEDEBUG" to "err+all",
            "WAYLAND_DISPLAY" to request.display.socketName,
            "XDG_RUNTIME_DIR" to guestRuntimeDirectory,
            "TMPDIR" to guestTempDirectory,
            "LD_LIBRARY_PATH" to
                "/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu:/lib:/usr/lib",
            "BOX64_LD_LIBRARY_PATH" to
                "/opt/ramenwine-wine/x86_64-unix:" +
                    "/opt/ramenwine-wine/lib/wine/x86_64-unix:" +
                    "/opt/ramenwine-wine/lib64:/opt/ramenwine-wine/lib:" +
                    "/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu:/lib:/usr/lib",
            "LANG" to request.locale,
            "LC_ALL" to request.locale,
            "PATH" to "/opt/ramenwine-wine/bin:/usr/local/bin:/usr/bin:/bin",
            "BOX64_NOBANNER" to "1",
            "WINEDLLPATH" to "/opt/ramenwine-audio/lib/wine",
            "BANNER_AUDIO_DIRECT_RELAY" to
                guestRuntimeDirectory + "/directaudio-relay.sock"
        )

        val arguments = buildList {
            // Wine starts a detached wineserver and services.exe. The guest
            // wrapper below shuts them down cleanly before PRoot exits.
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
            add("--pwd=" + guestWorkingDirectory)

            // PRoot itself runs on Android and needs the Bionic host library
            // path. Do not reuse it for Box64/Wine inside the glibc rootfs.
            // The guest env binary strips host-only values and installs a
            // separate guest environment without invoking a shell.
            add("/usr/bin/env")
            HOST_ONLY_ENVIRONMENT_KEYS.forEach { name ->
                add("-u")
                add(name)
            }
            guestEnvironment.forEach { (name, value) ->
                add("$name=$value")
            }
            add("/bin/sh")
            add("/opt/ramenwine-tools/run-wine-command.sh")
            add(guestExecutable)
        }

        val environment = linkedMapOf(
            "LD_LIBRARY_PATH" to listOf(
                nativeLibraryDirectory.canonicalPath,
                hostLibraryDirectory.canonicalPath
            ).joinToString(File.pathSeparator),
            "PROOT_TMP_DIR" to prootTempDirectory.absolutePath,
            "PROOT_LOADER" to prootLoader.canonicalPath
        )

        return ProotWineCommand(
            prootExecutable = proot,
            arguments = arguments,
            hostWorkingDirectory = backendRoot,
            environment = environment,
            guestEnvironment = guestEnvironment,
            unsetEnvironment = HOST_ONLY_ENVIRONMENT_KEYS,
            guestExecutablePath = guestExecutable,
            guestWorkingDirectory = guestWorkingDirectory,
            guestRuntimeDirectory = guestRuntimeDirectory,
            guestRootfsDirectory = rootfs,
            hostPrefixDirectory = prefixDirectory,
            runtimeVersion = runtimeVersion
        )
    }


    /**
     * Exercises the exact guest WINEPREFIX bind before starting Wine.
     * A successful host-side file check proves the guest path is writable.
     */
    fun buildWinePrefixWriteProbeCommand(gameCommand: ProotWineCommand): ProotWineCommand {
        val guestPrefix = gameCommand.guestEnvironment["WINEPREFIX"]
            ?: throw IOException("The Wine prefix path is not configured.")
        if (!WINE_PREFIX_PATH_PATTERN.matches(guestPrefix)) {
            throw IOException("The Wine prefix path is invalid for the write probe.")
        }

        val probePath = guestPrefix + "/" + PREFIX_WRITE_PROBE_FILE_NAME
        val arguments = gameCommand.arguments.dropLast(3) + listOf(
            "/usr/bin/touch",
            probePath
        )
        return gameCommand.copy(
            arguments = arguments,
            guestExecutablePath = "/usr/bin/touch"
        )
    }

    const val PREFIX_WRITE_PROBE_FILE_NAME = ".ramenwine-prefix-write-probe"

    /**
     * Builds a separate PRoot command that asks the active Wine server to stop
     * or waits for it to release the prefix lock. Used by the app's Stop action
     * before falling back to terminating the Android-host PRoot process.
     */
    fun buildWineServerControlCommand(
        gameCommand: ProotWineCommand,
        waitForExit: Boolean
    ): ProotWineCommand {
        val arguments = gameCommand.arguments.dropLast(3) + listOf(
            "/usr/bin/box64",
            "/usr/bin/wineserver",
            if (waitForExit) "-w" else "-k"
        )
        return gameCommand.copy(
            arguments = arguments,
            guestExecutablePath = "/usr/bin/wineserver"
        )
    }

    fun buildAudioDriverSelectionCommand(
        gameCommand: ProotWineCommand
    ): ProotWineCommand {
        val relaySocket = gameCommand.guestEnvironment["BANNER_AUDIO_DIRECT_RELAY"]
            ?: throw IOException("The Android audio relay socket is not configured.")
        if (!relaySocket.startsWith("/run/user/") ||
            !relaySocket.endsWith("/directaudio-relay.sock")
        ) {
            throw IOException("The Android audio relay socket path is invalid.")
        }

        val arguments = gameCommand.arguments.dropLast(3) + listOf(
            "/bin/sh",
            "/opt/ramenwine-tools/run-wine-command.sh",
            "reg.exe",
            "add",
            "HKCU\\Software\\Wine\\Drivers",
            "/v",
            "Audio",
            "/d",
            "directaudio",
            "/f"
        )
        return gameCommand.copy(
            arguments = arguments,
            guestExecutablePath = "reg.exe"
        )
    }

    /**
     * Reuses a selected game's validated private prefix and PRoot mounts, but
     * launches the bundled Win32 GDI test window instead of the user's EXE.
     * This exercises Wine/Box64/Wayland without modifying imported game files.
     */
    fun buildWin32GdiSmokeCommand(gameCommand: ProotWineCommand): ProotWineCommand {
        val relativePath = "opt/ramenwine-tests/ramenwine-gdi-smoke.exe"
        val smokeExecutable = requireRuntimeFile(gameCommand.guestRootfsDirectory, relativePath)
        val guestExecutable = "/" + relativePath
        val guestWorkingDirectory = "/opt/ramenwine-tests"
        val arguments = gameCommand.arguments.dropLast(3)
            .map { argument ->
                if (argument.startsWith("--pwd=")) {
                    "--pwd=" + guestWorkingDirectory
                } else {
                    argument
                }
            } + listOf(
                "/bin/sh",
                "/opt/ramenwine-tools/run-wine-command.sh",
                guestExecutable
            )

        if (!smokeExecutable.canRead()) {
            throw IOException("The bundled Win32 GDI smoke executable is unreadable.")
        }

        return gameCommand.copy(
            arguments = arguments,
            guestExecutablePath = guestExecutable,
            guestWorkingDirectory = guestWorkingDirectory
        )
    }

    fun buildWinebootCommand(
        gameCommand: ProotWineCommand,
        updateExistingPrefix: Boolean
    ): ProotWineCommand {
        val rootfs = gameCommand.guestRootfsDirectory.canonicalFile
        val candidates = listOf(
            "usr/lib/wine/x86_64-windows/wineboot.exe",
            "usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe"
        )
        val selected = candidates.firstNotNullOfOrNull { relative ->
            val file = File(rootfs, relative).canonicalFile
            if (file.isFile && file.canRead() &&
                file.path.startsWith(rootfs.path + File.separator)
            ) relative else null
        } ?: throw IOException(
            "Wine prefix initializer wineboot.exe is missing from the runtime package."
        )

        // wineboot starts wineserver and services.exe. Run it through the
        // clean-shutdown wrapper so registry hives are flushed before PRoot exits.
        val arguments = gameCommand.arguments.dropLast(3) + listOf(
            "/bin/sh",
            "/opt/ramenwine-tools/run-wine-command.sh",
            "/" + selected,
            if (updateExistingPrefix) "-u" else "-i"
        )
        return gameCommand.copy(
            arguments = arguments,
            guestExecutablePath = "/" + selected
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

    private fun androidHostExecutable(nativeLibraryDirectory: File, fileName: String): File {
        val root = nativeLibraryDirectory.canonicalFile
        requireDirectory(root, "Android native library")
        val executable = File(root, fileName).canonicalFile
        requireContained(root, executable, "Android host executable")
        if (!executable.isFile || !executable.canExecute()) {
            throw IOException(
                "Required Android native executable is missing or not executable: " + fileName
            )
        }
        return executable
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

    private fun requireRuntimeFile(root: File, relativePath: String): File {
        val normalized = SafeGamePath.normalizeRelativePath(relativePath)
            ?: throw IOException("Unsafe runtime file path.")
        val file = File(root, normalized).canonicalFile
        requireContained(root, file, "Runtime file")
        if (!file.isFile || !file.canRead()) {
            throw IOException("Required runtime file is missing or unreadable: " + relativePath)
        }
        return file
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

    // These values must not leak from Android into the glibc/Wine guest.
    // LD_LIBRARY_PATH and PROOT_TMP_DIR are reset for the guest by /usr/bin/env
    // but remain available to the host PRoot process.
    private val HOST_ONLY_ENVIRONMENT_KEYS = setOf(
        "DISPLAY",
        "WAYLAND_DISPLAY",
        "WAYLAND_SOCKET",
        "XDG_RUNTIME_DIR",
        "HOME",
        "TMPDIR",
        "PROOT_TMP_DIR",
        "PROOT_LOADER",
        "LD_LIBRARY_PATH",
        "PATH",
        "WINEPREFIX",
        "WINEDEBUG",
        "BOX64_LD_LIBRARY_PATH",
        "BOX64_NOBANNER",
        "WINEDLLPATH",
        "BANNER_AUDIO_DIRECT_RELAY",
        "LD_PRELOAD",
        "ANDROID_ROOT",
        "ANDROID_DATA",
        "BOOTCLASSPATH",
        "ANDROID_ART_ROOT",
        "ANDROID_I18N_ROOT",
        "ANDROID_TZDATA_ROOT"
    )

    private val ID_PATTERN = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val WINE_PREFIX_PATH_PATTERN =
        Regex("/prefixes/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val SOCKET_NAME_PATTERN = Regex("[A-Za-z0-9_.-]{1,40}")
}
