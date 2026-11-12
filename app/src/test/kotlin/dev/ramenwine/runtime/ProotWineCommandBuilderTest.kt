package dev.ramenwine.runtime

import dev.ramenwine.library.ImportedGame
import dev.ramenwine.library.GameLocaleProfile
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotWineCommandBuilderTest {
    @Test
    fun buildsProotBox64WineCommandWithPrivateBindsAndWaylandSocket() {
        val fixture = Fixture()
        try {
            val command = ProotWineCommandBuilder.build(fixture.request())

            assertEquals("fixture-runtime-1", command.runtimeVersion)
            assertEquals(GameLocaleProfile.DEFAULT_LOCALE, command.guestEnvironment["LANG"])
            assertEquals(GameLocaleProfile.DEFAULT_LOCALE, command.guestEnvironment["LC_ALL"])
            assertEquals("1", command.guestEnvironment["BOX64_LOG"])
            assertTrue(command.arguments.contains("BOX64_LOG=1"))
            assertTrue(command.unsetEnvironment.contains("BOX64_LOG"))
            assertEquals("/opt/ramenwine-audio/lib/wine", command.guestEnvironment["WINEDLLPATH"])
            assertEquals("/run/user/10123/directaudio-relay.sock", command.guestEnvironment["BANNER_AUDIO_DIRECT_RELAY"])
            assertEquals(
                fixture.rootfs.canonicalFile,
                command.guestRootfsDirectory
            )
            assertEquals(
                File(fixture.filesDirectory, "prefixes/" + fixture.gameId).canonicalFile,
                command.hostPrefixDirectory
            )
            assertTrue(fixture.prefixDirectory.isDirectory)
            assertEquals(fixture.nativeProot.absoluteFile, command.prootExecutable.absoluteFile)
            assertTrue(command.arguments.contains("--rootfs=" + fixture.rootfs.absolutePath))
            assertTrue(command.arguments.contains("/usr/bin/env"))
            assertTrue(command.arguments.contains("/bin/sh"))
            assertTrue(command.arguments.contains("/opt/ramenwine-tools/run-wine-command.sh"))
            assertTrue(command.arguments.contains("--link2symlink"))
            assertFalse(command.arguments.contains("--kill-on-exit"))
            assertTrue(command.arguments.contains("--sysvipc"))
            assertTrue(command.arguments.contains("--bind=/sys/devices/system/cpu:/sys/devices/system/cpu"))
            assertTrue(File(fixture.rootfs, "sys/devices/system/cpu").isDirectory)
            assertEquals(
                listOf(
                    fixture.nativeLibraryDirectory.canonicalPath,
                    fixture.hostLibraries.canonicalPath
                ).joinToString(File.pathSeparator),
                command.environment["LD_LIBRARY_PATH"]
            )
            assertEquals(fixture.nativeProotLoader.canonicalPath, command.environment["PROOT_LOADER"])
            assertTrue(command.unsetEnvironment.contains("PROOT_LOADER"))
            assertTrue(command.environment.containsKey("PROOT_TMP_DIR"))
            assertFalse(command.environment.containsKey("XDG_RUNTIME_DIR"))
            assertFalse(command.environment.containsKey("WINEPREFIX"))
            assertEquals("/run/user/10123", command.guestEnvironment["XDG_RUNTIME_DIR"])
            assertEquals("wayland-0", command.guestEnvironment["WAYLAND_DISPLAY"])
            assertEquals("/prefixes/" + fixture.gameId, command.guestEnvironment["WINEPREFIX"])
            assertTrue(command.guestEnvironment["LD_LIBRARY_PATH"]!!.contains("aarch64-linux-gnu"))
            assertTrue(command.guestEnvironment["BOX64_LD_LIBRARY_PATH"]!!.startsWith(
                "/opt/ramenwine-wine/x86_64-unix:"
            ))
            assertTrue(command.guestEnvironment["BOX64_LD_LIBRARY_PATH"]!!.contains("x86_64-linux-gnu"))
            assertTrue(command.unsetEnvironment.contains("LD_LIBRARY_PATH"))
            assertEquals(
                "/games/" + fixture.gameId + "/game.exe",
                command.guestExecutablePath
            )
            assertEquals(
                "/games/" + fixture.gameId,
                command.guestWorkingDirectory
            )
            assertTrue(
                command.arguments.contains("--pwd=/games/" + fixture.gameId)
            )
            assertTrue(
                command.arguments.contains(
                    "--bind=" + fixture.gameDirectory.absolutePath + ":/games/" + fixture.gameId
                )
            )
            assertTrue(
                command.arguments.contains(
                    "--bind=" + fixture.displayDirectory.absolutePath + ":/run/user/10123"
                )
            )
            assertTrue(command.arguments.contains("XDG_RUNTIME_DIR=/run/user/10123"))
            assertTrue(command.arguments.contains("WAYLAND_DISPLAY=wayland-0"))
            assertTrue(command.arguments.contains("WINEPREFIX=/prefixes/" + fixture.gameId))
            assertTrue(
                command.arguments.indexOf("/usr/bin/env") <
                    command.arguments.indexOf("/bin/sh")
            )
            assertTrue(File(fixture.rootfs, "games/" + fixture.gameId).isDirectory)
            assertTrue(File(fixture.rootfs, "prefixes/" + fixture.gameId).isDirectory)
            assertTrue(File(fixture.rootfs, "home/ramen").isDirectory)
            assertTrue(File(fixture.rootfs, "run/user/10123").isDirectory)
            assertEquals("/prefixes/" + fixture.gameId, command.guestEnvironment["WINEPREFIX"])
            assertTrue(command.unsetEnvironment.contains("DISPLAY"))
            assertTrue(command.unsetEnvironment.contains("LD_PRELOAD"))
            assertTrue(command.unsetEnvironment.contains("WINEDLLPATH"))
            assertTrue(command.unsetEnvironment.contains("BANNER_AUDIO_DIRECT_RELAY"))
            assertFalse(command.arguments.any { it.contains("sh -c") })
            assertEquals(
                listOf(
                    "/bin/sh",
                    "/opt/ramenwine-tools/run-wine-command.sh",
                    "/games/" + fixture.gameId + "/game.exe"
                ),
                command.arguments.takeLast(3)
            )
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun runsNestedExecutableFromItsOwnDirectory() {
        val fixture = Fixture(executableRelativePath = "bin/game.exe")
        try {
            val command = ProotWineCommandBuilder.build(fixture.request())
            val expectedDirectory = "/games/" + fixture.gameId + "/bin"

            assertEquals(expectedDirectory, command.guestWorkingDirectory)
            assertTrue(command.arguments.contains("--pwd=" + expectedDirectory))
            assertTrue(File(fixture.gameDirectory, "bin").isDirectory)
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun appliesAnExplicitPerGameLocaleToWine() {
        val fixture = Fixture()
        try {
            val command = ProotWineCommandBuilder.build(
                fixture.request().copy(locale = "ru_RU.UTF-8")
            )
            assertEquals("ru_RU.UTF-8", command.guestEnvironment["LANG"])
            assertEquals("ru_RU.UTF-8", command.guestEnvironment["LC_ALL"])
            assertTrue(command.arguments.contains("LANG=ru_RU.UTF-8"))
            assertTrue(command.arguments.contains("LC_ALL=ru_RU.UTF-8"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsLocaleThatIsNotBundledInTheGuestRootfs() {
        val fixture = Fixture()
        try {
            val error = runCatching {
                ProotWineCommandBuilder.build(
                    fixture.request().copy(locale = "xx_XX.UTF-8")
                )
            }.exceptionOrNull()
            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("locale"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun buildsWritableGuestWinePrefixProbeCommand() {
        val fixture = Fixture()
        try {
            val gameCommand = ProotWineCommandBuilder.build(fixture.request())
            val probe = ProotWineCommandBuilder.buildWinePrefixWriteProbeCommand(gameCommand)

            assertEquals("/usr/bin/touch", probe.guestExecutablePath)
            assertEquals(
                "/prefixes/" + fixture.gameId + "/" +
                    ProotWineCommandBuilder.PREFIX_WRITE_PROBE_FILE_NAME,
                probe.arguments.last()
            )
            assertTrue(probe.arguments.contains("/usr/bin/touch"))
            assertTrue(probe.arguments.contains("WINEPREFIX=/prefixes/" + fixture.gameId))
            assertTrue(
                probe.arguments.contains(
                    "--bind=" + fixture.prefixDirectory.absolutePath + ":/prefixes/" + fixture.gameId
                )
            )
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsUnsafeGuestWinePrefixProbePath() {
        val fixture = Fixture()
        try {
            val error = runCatching {
                ProotWineCommandBuilder.buildWinePrefixWriteProbeCommand(
                    ProotWineCommandBuilder.build(fixture.request()).copy(
                        guestEnvironment = mapOf("WINEPREFIX" to "/prefixes/../outside")
                    )
                )
            }.exceptionOrNull()
            assertTrue(error is java.io.IOException)
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun buildsWineServerStopAndWaitCommandsWithTheSamePrefix() {
        val fixture = Fixture()
        try {
            val gameCommand = ProotWineCommandBuilder.build(fixture.request())
            val stop = ProotWineCommandBuilder.buildWineServerControlCommand(
                gameCommand,
                waitForExit = false
            )
            val wait = ProotWineCommandBuilder.buildWineServerControlCommand(
                gameCommand,
                waitForExit = true
            )

            assertEquals("/usr/bin/wineserver", stop.guestExecutablePath)
            assertEquals(
                listOf("/usr/bin/box64", "/usr/bin/wineserver", "-k"),
                stop.arguments.takeLast(3)
            )
            assertEquals(
                listOf("/usr/bin/box64", "/usr/bin/wineserver", "-w"),
                wait.arguments.takeLast(3)
            )
            assertEquals(gameCommand.guestEnvironment["WINEPREFIX"], stop.guestEnvironment["WINEPREFIX"])
            assertEquals(
                gameCommand.arguments.filter { it.startsWith("--bind=") },
                stop.arguments.filter { it.startsWith("--bind=") }
            )
            assertFalse(stop.arguments.contains("--kill-on-exit"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun buildsWineRegistryCommandThatSelectsDirectAudio() {
        val fixture = Fixture()
        try {
            val command = ProotWineCommandBuilder.buildAudioDriverSelectionCommand(
                ProotWineCommandBuilder.build(fixture.request())
            )
            assertEquals("reg.exe", command.guestExecutablePath)
            assertTrue(command.arguments.contains("/opt/ramenwine-tools/run-wine-command.sh"))
            assertFalse(command.arguments.contains("--kill-on-exit"))
            assertEquals("directaudio", command.arguments[command.arguments.lastIndex - 1])
            assertTrue(command.arguments.contains("HKCU\\Software\\Wine\\Drivers"))
            assertTrue(command.arguments.contains("/v"))
            assertTrue(command.arguments.contains("Audio"))
            assertTrue(command.arguments.contains("/d"))
            assertTrue(command.arguments.contains("directaudio"))
            assertTrue(command.arguments.contains("/f"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun buildsBundledWin32GdiSmokeCommandWithoutChangingGameFilesOrPrefix() {
        val fixture = Fixture()
        try {
            val gameCommand = ProotWineCommandBuilder.build(fixture.request())
            val smokeCommand = ProotWineCommandBuilder.buildWin32GdiSmokeCommand(gameCommand)

            assertEquals(
                "/opt/ramenwine-tests/ramenwine-gdi-smoke.exe",
                smokeCommand.guestExecutablePath
            )
            assertEquals("/opt/ramenwine-tests", smokeCommand.guestWorkingDirectory)
            assertTrue(smokeCommand.arguments.contains("--pwd=/opt/ramenwine-tests"))
            assertTrue(smokeCommand.arguments.contains("/opt/ramenwine-tools/run-wine-command.sh"))
            assertFalse(smokeCommand.arguments.contains("--kill-on-exit"))
            assertEquals(
                listOf(
                    "/bin/sh",
                    "/opt/ramenwine-tools/run-wine-command.sh",
                    "/opt/ramenwine-tests/ramenwine-gdi-smoke.exe"
                ),
                smokeCommand.arguments.takeLast(3)
            )
            assertEquals(gameCommand.hostPrefixDirectory, smokeCommand.hostPrefixDirectory)
            assertEquals(
                gameCommand.arguments.filter { it.startsWith("--bind=") },
                smokeCommand.arguments.filter { it.startsWith("--bind=") }
            )
            assertTrue(fixture.gameExe.isFile)
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun refusesWin32GdiSmokeWhenBundledExecutableIsMissing() {
        val fixture = Fixture()
        try {
            File(fixture.rootfs, "opt/ramenwine-tests/ramenwine-gdi-smoke.exe").delete()
            val error = runCatching {
                ProotWineCommandBuilder.buildWin32GdiSmokeCommand(
                    ProotWineCommandBuilder.build(fixture.request())
                )
            }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("missing or unreadable"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun buildsWinebootInitCommandForNewPrefix() {
        val fixture = Fixture()
        try {
            val command = ProotWineCommandBuilder.buildWinebootCommand(
                ProotWineCommandBuilder.build(fixture.request()),
                updateExistingPrefix = false
            )
            assertEquals(
                "/usr/lib/wine/x86_64-windows/wineboot.exe",
                command.guestExecutablePath
            )
            assertEquals("-i", command.arguments.last())
            assertEquals("/bin/sh", command.arguments[command.arguments.size - 4])
            assertEquals(
                "/opt/ramenwine-tools/run-wine-command.sh",
                command.arguments[command.arguments.size - 3]
            )
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun buildsWinebootUpdateCommandForExistingPrefix() {
        val fixture = Fixture()
        try {
            val command = ProotWineCommandBuilder.buildWinebootCommand(
                ProotWineCommandBuilder.build(fixture.request()),
                updateExistingPrefix = true
            )
            assertEquals("-u", command.arguments.last())
            assertEquals("2", command.guestEnvironment["BOX64_LOG"])
            assertEquals("1", command.guestEnvironment["BOX64_DLSYM_ERROR"])
            assertEquals(
                "err+all,+virtual,+mem_areas,+module",
                command.guestEnvironment["WINEDEBUG"]
            )
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsArm64ExecutableBeforeBuildingABox64WineCommand() {
        val fixture = Fixture()
        try {
            val unsupportedGame = fixture.request().game.copy(architecture = "ARM64")
            val error = runCatching {
                ProotWineCommandBuilder.build(
                    fixture.request().copy(game = unsupportedGame)
                )
            }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("x86-64"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun allows32BitX86ExecutableThroughWow64Backend() {
        val fixture = Fixture()
        try {
            val x86Game = fixture.request().game.copy(architecture = "x86")
            val command = ProotWineCommandBuilder.build(
                fixture.request().copy(game = x86Game)
            )
            assertEquals("/opt/ramenwine-tools/run-wine-command.sh", command.arguments[command.arguments.size - 2])
            assertFalse(command.arguments.contains("--kill-on-exit"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun refusesPrefixInitializationWhenWinebootIsMissing() {
        val fixture = Fixture()
        try {
            File(fixture.rootfs, "usr/lib/wine/x86_64-windows/wineboot.exe").delete()
            val error = runCatching {
                ProotWineCommandBuilder.buildWinebootCommand(
                    ProotWineCommandBuilder.build(fixture.request()),
                    updateExistingPrefix = false
                )
            }.exceptionOrNull()
            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("wineboot.exe"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsUnsafeExecutablePath() {
        val fixture = Fixture(executableRelativePath = "../outside.exe")
        try {
            val error = runCatching {
                ProotWineCommandBuilder.build(fixture.request())
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException || error is java.io.IOException)
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsInvalidGameIdBeforeCreatingGuestPaths() {
        val fixture = Fixture()
        try {
            val invalidGame = fixture.request().game.copy(
                id = "../escape",
                relativeDirectory = "games/../escape"
            )
            val error = runCatching {
                ProotWineCommandBuilder.build(fixture.request().copy(game = invalidGame))
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertFalse(File(fixture.rootfs, "games/escape").exists())
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun refusesToStartBeforeWaylandSocketExists() {
        val fixture = Fixture(createWaylandSocket = false)
        try {
            val error = runCatching {
                ProotWineCommandBuilder.build(fixture.request())
            }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("Wayland socket"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun refusesToBuildWhenApkNativeProotIsMissing() {
        val fixture = Fixture()
        try {
            fixture.nativeProot.delete()
            val error = runCatching {
                ProotWineCommandBuilder.build(fixture.request())
            }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("libproot.so"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun refusesToBuildWhenApkNativeAudioRelayIsMissing() {
        val fixture = Fixture()
        try {
            fixture.nativeAudioRelay.delete()
            val error = runCatching {
                ProotWineCommandBuilder.build(fixture.request())
            }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("libdirectaudio-relay.so"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun refusesToBuildWhenApkNativeProotLoaderIsMissing() {
        val fixture = Fixture()
        try {
            fixture.nativeProotLoader.delete()
            val error = runCatching {
                ProotWineCommandBuilder.build(fixture.request())
            }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("libproot-loader.so"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun refusesRuntimeWithMissingProotExecutable() {
        val fixture = Fixture()
        try {
            fixture.proot.delete()
            val error = runCatching {
                ProotWineCommandBuilder.build(fixture.request())
            }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("proot"))
        } finally {
            fixture.cleanup()
        }
    }

    private class Fixture(
        private val executableRelativePath: String = "game.exe",
        createWaylandSocket: Boolean = true
    ) {
        val root = Files.createTempDirectory("ramenwine-launch").toFile()
        val filesDirectory = File(root, "files").apply { mkdirs() }
        val cacheDirectory = File(root, "cache").apply { mkdirs() }
        val nativeLibraryDirectory = File(root, "native-libs").apply { mkdirs() }
        val nativeProot = executable(nativeLibraryDirectory, "libproot.so")
        val nativeAudioRelay = executable(nativeLibraryDirectory, "libdirectaudio-relay.so")
        val nativeProotLoader = executable(nativeLibraryDirectory, "libproot-loader.so")
        val gameId = "12345678-1234-1234-1234-123456789abc"

        val backendRoot = File(
            filesDirectory,
            "runtime/current/backends/" + RuntimeBackendId.GLIBC_PROOT_BOX64.directoryName
        ).apply { mkdirs() }
        val rootfs = File(backendRoot, "rootfs").apply { mkdirs() }
        val proot = executable(backendRoot, "host/bin/proot")
        val audioRelay = executable(backendRoot, "host/bin/directaudio-relay")
        val hostLibraries = File(backendRoot, "host/lib").apply { mkdirs() }
        val runtimeProperties = File(backendRoot, "runtime.properties").apply {
            writeText(
                "backendId=glibc-proot-box64\nhostAbi=arm64-v8a\nruntimeVersion=fixture-runtime-1\n"
            )
        }
        val env = executable(rootfs, "usr/bin/env")
        val box64 = executable(rootfs, "usr/bin/box64")
        val wine = executable(rootfs, "usr/bin/wine")
        val wineserver = executable(rootfs, "usr/bin/wineserver")
        val shell = executable(rootfs, "usr/bin/dash")
        val wineCommandWrapper = executable(rootfs, "opt/ramenwine-tools/run-wine-command.sh")
        val touch = executable(rootfs, "usr/bin/touch")
        val wineboot = executable(rootfs, "usr/lib/wine/x86_64-windows/wineboot.exe")
        val prefixDirectory = File(filesDirectory, "prefixes/" + gameId)
        val audioUnix = executable(rootfs, "opt/ramenwine-audio/lib/wine/x86_64-unix/winedirectaudio.so")
        val audioPe64 = executable(rootfs, "opt/ramenwine-audio/lib/wine/x86_64-windows/winedirectaudio.drv")
        val audioPe32 = executable(rootfs, "opt/ramenwine-audio/lib/wine/i386-windows/winedirectaudio.drv")
        val gdiSmoke = executable(rootfs, "opt/ramenwine-tests/ramenwine-gdi-smoke.exe")

        val gameDirectory = File(filesDirectory, "games/" + gameId).apply { mkdirs() }
        val gameExe = File(gameDirectory, executableRelativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x4D, 0x5A))
        }

        val displayDirectory = File(cacheDirectory, "wawona-runtime").apply { mkdirs() }

        init {
            // Match Debian Trixie's merged-/usr shell layout.
            Files.createSymbolicLink(File(rootfs, "usr/bin/sh").toPath(), Paths.get("dash"))
            Files.createSymbolicLink(File(rootfs, "bin").toPath(), Paths.get("usr/bin"))
            if (createWaylandSocket) {
                File(displayDirectory, "wayland-0").writeText("socket placeholder")
            }
        }

        fun request() = ProotWineLaunchRequest(
            filesDirectory = filesDirectory,
            cacheDirectory = cacheDirectory,
            nativeLibraryDirectory = nativeLibraryDirectory,
            game = ImportedGame(
                id = gameId,
                title = "Test visual novel",
                relativeDirectory = "games/" + gameId,
                executableRelativePath = executableRelativePath,
                architecture = "x86-64",
                totalBytes = gameExe.length(),
                importedAtEpochMillis = 1L
            ),
            display = WaylandDisplayEndpoint(displayDirectory),
            hostUid = 10123
        )

        fun cleanup() {
            root.deleteRecursively()
        }

        private fun executable(parent: File, relativePath: String): File {
            val file = File(parent, relativePath)
            file.parentFile?.mkdirs()
            file.writeText("test binary")
            file.setExecutable(true)
            return file
        }
    }
}
