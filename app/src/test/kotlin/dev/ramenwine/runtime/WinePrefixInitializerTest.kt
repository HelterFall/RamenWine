package dev.ramenwine.runtime

import dev.ramenwine.display.WaylandDisplayEndpoint
import dev.ramenwine.display.WaylandSocketValidator
import dev.ramenwine.library.ImportedGame
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WinePrefixInitializerTest {
    @Test
    fun initializesPrefixOnceForConcurrentRequests() = runBlocking {
        val fixture = Fixture(fixtureScript = "create-prefix")
        try {
            val output = CopyOnWriteArrayList<String>()
            val firstInitializer = fixture.initializer()
            val secondInitializer = fixture.initializer()
            val request = fixture.request()

            coroutineScope {
                val first = async { firstInitializer.ensureInitialized(request, output::add) }
                val second = async { secondInitializer.ensureInitialized(request, output::add) }
                first.await()
                second.await()
            }

            assertTrue(File(fixture.prefixDirectory, "system.reg").isFile)
            assertTrue(File(fixture.prefixDirectory, "user.reg").isFile)
            assertEquals("1", File(fixture.prefixDirectory, "wineboot-count").readText())
            assertTrue(output.any { it.contains("wineboot finished") })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun doesNotLaunchGameWhenWinebootExitsWithFailure() = runBlocking {
        val fixture = Fixture(fixtureScript = "exit-7")
        try {
            val error = runCatching {
                fixture.initializer().ensureInitialized(fixture.request()) {}
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("exit code 7"))
            assertFalse(File(fixture.prefixDirectory, "system.reg").exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun refusesToOverwritePartiallyInitializedPrefix() = runBlocking {
        val fixture = Fixture(fixtureScript = "create-prefix")
        try {
            fixture.prefixDirectory.mkdirs()
            File(fixture.prefixDirectory, "system.reg").writeText("partial")

            val error = runCatching {
                fixture.initializer().ensureInitialized(fixture.request()) {}
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("partially initialized"))
            assertFalse(File(fixture.prefixDirectory, "wineboot-count").exists())
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        fixtureScript: String
    ) : AutoCloseable {
        val root = Files.createTempDirectory("ramenwine-prefix-test").toFile()
        val filesDirectory = File(root, "files").apply { mkdirs() }
        private val cacheDirectory = File(root, "cache").apply { mkdirs() }
        private val gameId = "12345678-1234-1234-1234-123456789abc"
        val prefixDirectory = File(filesDirectory, "prefixes/$gameId")

        private val backendRoot = File(
            filesDirectory,
            "runtime/current/backends/" + RuntimeBackendId.GLIBC_PROOT_BOX64.directoryName
        ).apply { mkdirs() }
        private val rootfs = File(backendRoot, "rootfs").apply { mkdirs() }
        private val hostLibraries = File(backendRoot, "host/lib").apply { mkdirs() }
        private val proot = executable(
            backendRoot,
            "host/bin/proot",
            makeProotScript(fixtureScript)
        )
        private val displayDirectory = File(cacheDirectory, "display").apply { mkdirs() }

        init {
            executable(rootfs, "usr/bin/box64")
            executable(rootfs, "usr/bin/wine")
            executable(rootfs, "usr/bin/wineboot")
            regular(rootfs, "lib64/ld-linux-x86-64.so.2")
            regular(
                rootfs,
                "usr/lib/x86_64-linux-gnu/wine/x86_64-unix/winewayland.so"
            )
            regular(
                rootfs,
                "usr/lib/x86_64-linux-gnu/wine/x86_64-windows/winewayland.drv"
            )
            File(displayDirectory, "wayland-0").writeText("test socket placeholder")
            val gameDirectory = File(filesDirectory, "games/$gameId").apply { mkdirs() }
            File(gameDirectory, "game.exe").writeBytes(byteArrayOf(0x4D, 0x5A))
        }

        fun request() = ProotWineLaunchRequest(
            filesDirectory = filesDirectory,
            cacheDirectory = cacheDirectory,
            game = ImportedGame(
                id = gameId,
                title = "Prefix test",
                relativeDirectory = "games/$gameId",
                executableRelativePath = "game.exe",
                architecture = "x86-64",
                totalBytes = 2L,
                importedAtEpochMillis = 1L
            ),
            display = WaylandDisplayEndpoint(displayDirectory),
            hostUid = 10123
        )

        fun initializer() = WinePrefixInitializer(
            filesDirectory = filesDirectory,
            processSupervisor = ProotWineProcessSupervisor(Dispatchers.Unconfined),
            socketValidator = WaylandSocketValidator { it.isFile },
            initializationTimeoutMillis = 5_000L
        )

        override fun close() {
            root.deleteRecursively()
        }

        private fun regular(parent: File, relativePath: String): File {
            val file = File(parent, relativePath)
            file.parentFile?.mkdirs()
            file.writeText("fixture runtime file")
            return file
        }

        private fun executable(
            parent: File,
            relativePath: String,
            content: String = "fake binary"
        ): File {
            val file = File(parent, relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            check(file.setExecutable(true))
            return file
        }

        private fun makeProotScript(mode: String): String {
            val shebang = "#!/usr/bin/env python3\n"
            if (mode == "exit-7") {
                return shebang + """
                    import sys
                    print("wineboot failed", flush=True)
                    sys.exit(7)
                """.trimIndent() + "\n"
            }

            val escapedPrefix = prefixDirectory.absolutePath
                .replace("\\", "\\\\")
                .replace("'", "\\'")
            return shebang + """
                from pathlib import Path
                prefix = Path('$escapedPrefix')
                prefix.mkdir(parents=True, exist_ok=True)
                counter = prefix / "wineboot-count"
                count = int(counter.read_text() if counter.exists() else "0") + 1
                counter.write_text(str(count))
                (prefix / "system.reg").write_text("REGEDIT4")
                (prefix / "user.reg").write_text("REGEDIT4")
                print("wineboot finished", flush=True)
            """.trimIndent() + "\n"
        }
    }
}
