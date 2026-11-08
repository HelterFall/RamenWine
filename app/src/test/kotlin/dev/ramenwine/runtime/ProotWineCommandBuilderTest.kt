package dev.ramenwine.runtime

import dev.ramenwine.library.ImportedGame
import java.io.File
import java.nio.file.Files
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

            assertEquals(fixture.proot.absoluteFile, command.prootExecutable.absoluteFile)
            assertTrue(command.arguments.contains("--rootfs=" + fixture.rootfs.absolutePath))
            assertTrue(command.arguments.contains("/usr/bin/box64"))
            assertTrue(command.arguments.contains("/usr/bin/wine64"))
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
            assertEquals("/run/user/10123", command.environment["XDG_RUNTIME_DIR"])
            assertEquals("wayland-0", command.environment["WAYLAND_DISPLAY"])
            assertTrue(File(fixture.rootfs, "games/" + fixture.gameId).isDirectory)
            assertTrue(File(fixture.rootfs, "prefixes/" + fixture.gameId).isDirectory)
            assertTrue(File(fixture.rootfs, "home/ramen").isDirectory)
            assertTrue(File(fixture.rootfs, "run/user/10123").isDirectory)
            assertEquals("/prefixes/" + fixture.gameId, command.environment["WINEPREFIX"])
            assertTrue(command.unsetEnvironment.contains("DISPLAY"))
            assertTrue(command.unsetEnvironment.contains("LD_PRELOAD"))
            assertFalse(command.arguments.any { it.contains("sh -c") })
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
        val gameId = "12345678-1234-1234-1234-123456789abc"

        val backendRoot = File(
            filesDirectory,
            "runtime/current/backends/" + RuntimeBackendId.GLIBC_PROOT_BOX64.directoryName
        ).apply { mkdirs() }
        val rootfs = File(backendRoot, "rootfs").apply { mkdirs() }
        val proot = executable(backendRoot, "host/bin/proot")
        val box64 = executable(rootfs, "usr/bin/box64")
        val wine64 = executable(rootfs, "usr/bin/wine64")

        val gameDirectory = File(filesDirectory, "games/" + gameId).apply { mkdirs() }
        val gameExe = File(gameDirectory, executableRelativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x4D, 0x5A))
        }

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
