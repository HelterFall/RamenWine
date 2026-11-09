package dev.ramenwine.runtime

import dev.ramenwine.display.WaylandDisplayEndpoint
import dev.ramenwine.display.WaylandSocketValidator
import dev.ramenwine.library.ImportedGame
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotWineCommandBuilderTest {
    @Test
    fun rejectsUnsupportedWindowsArchitectureBeforePreparingRuntimePaths() {
        val fixture = Fixture()
        try {
            val request = fixture.request().copy(
                game = fixture.request().game.copy(architecture = "ARM64")
            )

            val error = runCatching { fixture.build(request) }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("Unsupported Windows executable architecture"))
            assertFalse(File(fixture.rootfs, "games/" + fixture.gameId).exists())
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun buildsProotBox64WineCommandWithPrivateBindsAndWaylandSocket() {
        val fixture = Fixture()
        try {
            val command = fixture.build()

            assertEquals(fixture.proot.absoluteFile, command.prootExecutable.absoluteFile)
            assertTrue(command.arguments.contains("--rootfs=" + fixture.rootfs.absolutePath))
            assertTrue(command.arguments.contains("/usr/bin/box64"))
            assertTrue(command.arguments.contains("/usr/bin/wine"))
            assertTrue(command.arguments.contains("--kill-on-exit"))
            assertTrue(command.arguments.contains("--link2symlink"))
            assertTrue(command.arguments.contains("--sysvipc"))
            assertEquals(fixture.hostLibraries.absolutePath, command.environment["LD_LIBRARY_PATH"])
            assertEquals(fixture.prootLoader.absolutePath, command.environment["PROOT_LOADER"])
            assertEquals(fixture.prootTempDirectory.absolutePath, command.environment["PROOT_TMP_DIR"])
            assertFalse(command.environment.containsKey("BOX64_LD_LIBRARY_PATH"))
            assertFalse(command.environment.containsKey("WINEDLLPATH"))
            assertFalse(command.environment.containsKey("WINEARCH"))
            val envIndex = command.arguments.indexOf("/usr/bin/env")
            assertTrue(envIndex >= 0)
            assertEquals("-i", command.arguments[envIndex + 1])
            val guestEnvironment = command.arguments.drop(envIndex + 2)
                .takeWhile { it.contains("=") }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
            assertEquals(
                "/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu",
                guestEnvironment["LD_LIBRARY_PATH"]
            )
            assertEquals(
                "/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu:" +
                    "/usr/lib/box64-x86_64-linux-gnu:" +
                    "/usr/lib/x86_64-linux-gnu/wine/x86_64-unix",
                guestEnvironment["BOX64_LD_LIBRARY_PATH"]
            )
            assertEquals(
                "/usr/lib/x86_64-linux-gnu/wine/x86_64-windows:" +
                    "/usr/lib/x86_64-linux-gnu/wine/i386-windows:" +
                    "/usr/lib/x86_64-linux-gnu/wine/x86_64-unix",
                guestEnvironment["WINEDLLPATH"]
            )
            assertEquals("wow64", guestEnvironment["WINEARCH"])
            assertEquals("/run/user/10123", guestEnvironment["XDG_RUNTIME_DIR"])
            assertEquals("wayland-0", guestEnvironment["WAYLAND_DISPLAY"])
            assertEquals("/usr/share/X11/xkb", guestEnvironment["XKB_CONFIG_ROOT"])
            assertEquals("/prefixes/" + fixture.gameId, guestEnvironment["WINEPREFIX"])
            assertTrue(command.unsetEnvironment.contains("DISPLAY"))
            assertEquals(
                "/games/" + fixture.gameId + "/game.exe",
                command.guestExecutablePath
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
            assertTrue(File(fixture.rootfs, "games/" + fixture.gameId).isDirectory)
            assertTrue(File(fixture.rootfs, "prefixes/" + fixture.gameId).isDirectory)
            assertTrue(File(fixture.rootfs, "home/ramen").isDirectory)
            assertTrue(File(fixture.rootfs, "run/user/10123").isDirectory)
            assertEquals("/prefixes/" + fixture.gameId, guestEnvironment["WINEPREFIX"])
            assertTrue(command.unsetEnvironment.contains("DISPLAY"))
            assertTrue(command.unsetEnvironment.contains("LD_PRELOAD"))
            assertFalse(command.arguments.any { it.contains("sh -c") })
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun buildsWinebootPrefixInitializationCommand() {
        val fixture = Fixture()
        try {
            val request = fixture.request().copy(
                operation = ProotWineOperation.INITIALIZE_PREFIX
            )
            val command = fixture.build(request)

            assertEquals(
                "/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe",
                command.guestExecutablePath
            )
            assertEquals(
                listOf(
                    "/usr/bin/box64",
                    "/usr/bin/wine",
                    "/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe",
                    "--init"
                ),
                command.arguments.takeLast(4)
            )
            assertTrue(command.arguments.contains("/usr/bin/wine"))
            val envIndex = command.arguments.indexOf("/usr/bin/env")
            assertTrue(envIndex >= 0)
            assertEquals("-i", command.arguments[envIndex + 1])
            assertTrue(
                command.arguments.drop(envIndex + 2)
                    .contains("WINEPREFIX=/prefixes/" + fixture.gameId)
            )
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsMissingWinebootExecutableForPrefixInitialization() {
        val fixture = Fixture()
        try {
            fixture.wineboot.delete()
            val request = fixture.request().copy(
                operation = ProotWineOperation.INITIALIZE_PREFIX
            )
            val error = runCatching { fixture.build(request) }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("wineboot"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsRuntimeMissingBox64BundledLibunwind() {
        val fixture = Fixture()
        try {
            fixture.box64Libunwind.delete()
            val error = runCatching { fixture.build() }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("Box64 bundled libunwind"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsRuntimeMissingWineServerExecutable() {
        val fixture = Fixture()
        try {
            fixture.wineServer.delete()
            val error = runCatching { fixture.build() }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("wineserver"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsRuntimeMissingWineWaylandPeDriver() {
        val fixture = Fixture()
        try {
            fixture.wineWaylandPe.delete()
            val error = runCatching { fixture.build() }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("Wine Wayland PE driver"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsRuntimeMissingWineWaylandI386PeDriver() {
        val fixture = Fixture()
        try {
            fixture.wineWaylandI386Pe.delete()
            val error = runCatching { fixture.build() }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("Wine 32-bit Wayland PE driver"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsRuntimeMissingWaylandXkbRegistryLibrary() {
        val fixture = Fixture()
        try {
            fixture.xkbRegistry.delete()
            val error = runCatching { fixture.build() }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("XKB registry library"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsRuntimeMissingArm64GlibcLoader() {
        val fixture = Fixture()
        try {
            fixture.arm64GlibcLoader.delete()
            val error = runCatching { fixture.build() }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("ARM64 glibc loader"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsRuntimeMissingX64GlibcLoader() {
        val fixture = Fixture()
        try {
            fixture.glibcLoader.delete()
            val error = runCatching { fixture.build() }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("x86-64 glibc loader"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsPrefixParentSymlinkEscapingPrivateFilesDirectory() {
        val fixture = Fixture()
        val outside = Files.createTempDirectory("ramenwine-prefix-outside").toFile()
        try {
            Files.createSymbolicLink(
                File(fixture.filesDirectory, "prefixes").toPath(),
                outside.toPath()
            )

            val error = runCatching { fixture.build() }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("Wine prefix root"))
            assertFalse(File(outside, fixture.gameId).exists())
        } finally {
            outside.deleteRecursively()
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsUnsafeExecutablePath() {
        val fixture = Fixture(executableRelativePath = "../outside.exe")
        try {
            val error = runCatching {
                fixture.build()
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
                fixture.build(fixture.request().copy(game = invalidGame))
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
                fixture.build()
            }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("Wayland socket"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsRegularFileMasqueradingAsWaylandSocket() {
        val fixture = Fixture()
        try {
            val error = runCatching {
                ProotWineCommandBuilder.build(
                    fixture.request(),
                    WaylandSocketValidator { false }
                )
            }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("Unix-domain socket"))
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun rejectsWaylandRuntimeDirectorySymlinkEscapingAppCache() {
        val fixture = Fixture()
        val outside = Files.createTempDirectory("ramenwine-display-outside").toFile()
        try {
            fixture.displayDirectory.deleteRecursively()
            Files.createSymbolicLink(
                fixture.displayDirectory.toPath(),
                outside.toPath()
            )
            File(outside, "wayland-0").writeText("socket placeholder")

            val error = runCatching { fixture.build() }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertTrue(error?.message.orEmpty().contains("Wayland runtime directory"))
        } finally {
            outside.deleteRecursively()
            fixture.cleanup()
        }
    }

    @Test
    fun refusesRuntimeWithMissingProotExecutable() {
        val fixture = Fixture()
        try {
            fixture.proot.delete()
            val error = runCatching {
                fixture.build()
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
        val gameId = "12345678-1234-1234-1234-123456789abc"

        val backendRoot = File(
            filesDirectory,
            "runtime/current/backends/" + RuntimeBackendId.GLIBC_PROOT_BOX64.directoryName
        ).apply { mkdirs() }
        val rootfs = File(backendRoot, "rootfs").apply { mkdirs() }
        val proot = executable(backendRoot, "host/bin/proot")
        val prootLoader = executable(backendRoot, "host/libexec/proot/loader")
        val hostLibraries = File(backendRoot, "host/lib").apply { mkdirs() }
        val guestEnv = executable(rootfs, "usr/bin/env")
        val box64 = executable(rootfs, "usr/bin/box64")
        val wine = executable(rootfs, "usr/bin/wine")
        val wineServer = executable(rootfs, "usr/bin/wineserver")
        val wineboot = regular(
            rootfs,
            "usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe"
        )
        val arm64GlibcLoader = regular(rootfs, "lib/ld-linux-aarch64.so.1")
        val glibcLoader = regular(rootfs, "lib64/ld-linux-x86-64.so.2")
        val box64Libstdcpp = regular(
            rootfs,
            "usr/lib/box64-x86_64-linux-gnu/libstdc++.so.6"
        )
        val box64Libgcc = regular(
            rootfs,
            "usr/lib/box64-x86_64-linux-gnu/libgcc_s.so.1"
        )
        val box64Libunwind = regular(
            rootfs,
            "usr/lib/box64-x86_64-linux-gnu/libunwind.so.8"
        )
        val wineWaylandUnix = regular(
            rootfs,
            "usr/lib/x86_64-linux-gnu/wine/x86_64-unix/winewayland.so"
        )
        val wineWaylandPe = regular(
            rootfs,
            "usr/lib/x86_64-linux-gnu/wine/x86_64-windows/winewayland.drv"
        )
        val wineWaylandI386Pe = regular(
            rootfs,
            "usr/lib/x86_64-linux-gnu/wine/i386-windows/winewayland.drv"
        )
        val waylandClient = regular(
            rootfs,
            "usr/lib/x86_64-linux-gnu/libwayland-client.so.0"
        )
        val waylandCursor = regular(
            rootfs,
            "usr/lib/x86_64-linux-gnu/libwayland-cursor.so.0"
        )
        val waylandEgl = regular(
            rootfs,
            "usr/lib/x86_64-linux-gnu/libwayland-egl.so.1"
        )
        val xkbCommon = regular(
            rootfs,
            "usr/lib/x86_64-linux-gnu/libxkbcommon.so.0"
        )
        val xkbRegistry = regular(
            rootfs,
            "usr/lib/x86_64-linux-gnu/libxkbregistry.so.0"
        )

        val gameDirectory = File(filesDirectory, "games/" + gameId).apply { mkdirs() }
        val gameExe = File(gameDirectory, executableRelativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x4D, 0x5A))
        }

        val prootTempDirectory = File(cacheDirectory, "proot-tmp/" + gameId).apply { mkdirs() }
        val displayDirectory = File(cacheDirectory, "wawona-runtime").apply { mkdirs() }

        init {
            if (createWaylandSocket) {
                File(displayDirectory, "wayland-0").writeText("socket placeholder")
            }
        }

        fun request() = ProotWineLaunchRequest(
            filesDirectory = filesDirectory,
            cacheDirectory = cacheDirectory,
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

        fun build(gameRequest: ProotWineLaunchRequest = request()) =
            ProotWineCommandBuilder.build(
                gameRequest,
                WaylandSocketValidator { it.isFile }
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

        private fun regular(parent: File, relativePath: String): File {
            val file = File(parent, relativePath)
            file.parentFile?.mkdirs()
            file.writeText("test runtime file")
            return file
        }
    }
}
