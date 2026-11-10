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
            assertTrue(
                File(fixture.prefixDirectory, "system.reg")
                    .readText()
                    .contains("#arch=win64")
            )
            assertEquals("1", File(fixture.prefixDirectory, "wineboot-count").readText())
            assertEquals("1", File(fixture.prefixDirectory, "wayland-config-count").readText())
            assertEquals(
                "graphics=wayland-v1",
                File(fixture.prefixDirectory, ".ramenwine-wayland-driver").readText()
            )
            assertTrue(output.any { it.contains("wineboot finished") })
            assertTrue(output.any { it.contains("[wine-wayland]") })
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
            assertFalse(fixture.prefixDirectory.exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun refusesToUseExisting32BitPrefixInWine11Wow64Mode() = runBlocking {
        val fixture = Fixture(fixtureScript = "create-prefix")
        try {
            fixture.prefixDirectory.mkdirs()
            File(fixture.prefixDirectory, "system.reg").writeText(
                "WINE REGISTRY Version 2\n#arch=win32\n"
            )
            File(fixture.prefixDirectory, "user.reg").writeText(
                "WINE REGISTRY Version 2\n#arch=win32\n"
            )

            val error = runCatching {
                fixture.initializer().ensureInitialized(fixture.request()) {}
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("not a 64-bit"))
            assertFalse(File(fixture.prefixDirectory, "wineboot-count").exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun rejectsWinebootSuccessWhenItCreates32BitPrefix() = runBlocking {
        val fixture = Fixture(fixtureScript = "create-prefix-win32")
        try {
            val error = runCatching {
                fixture.initializer().ensureInitialized(fixture.request()) {}
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("#arch=win64"))
            assertFalse(fixture.prefixDirectory.exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retriesWinebootAfterFailedStagingAttempt() = runBlocking {
        val fixture = Fixture(fixtureScript = "create-prefix-win32")
        try {
            val initializer = fixture.initializer()
            val firstError = runCatching {
                initializer.ensureInitialized(fixture.request()) {}
            }.exceptionOrNull()

            assertTrue(firstError is IOException)
            assertFalse(fixture.prefixDirectory.exists())

            fixture.useFixtureScript("create-prefix")
            initializer.ensureInitialized(fixture.request()) {}

            assertTrue(File(fixture.prefixDirectory, "system.reg").isFile)
            assertTrue(File(fixture.prefixDirectory, "user.reg").isFile)
            assertTrue(
                File(fixture.prefixDirectory, "system.reg")
                    .readText()
                    .contains("#arch=win64")
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun configuresAnExistingWin64PrefixForWaylandWithoutRunningWineboot() = runBlocking {
        val fixture = Fixture(fixtureScript = "create-prefix")
        try {
            fixture.prefixDirectory.mkdirs()
            File(fixture.prefixDirectory, "system.reg").writeText(
                "WINE REGISTRY Version 2\n#arch=win64\n"
            )
            File(fixture.prefixDirectory, "user.reg").writeText(
                "WINE REGISTRY Version 2\n#arch=win64\n"
            )

            fixture.initializer().ensureInitialized(fixture.request()) {}

            assertFalse(File(fixture.prefixDirectory, "wineboot-count").exists())
            assertEquals("1", File(fixture.prefixDirectory, "wayland-config-count").readText())
            assertEquals(
                "graphics=wayland-v1",
                File(fixture.prefixDirectory, ".ramenwine-wayland-driver").readText()
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun skipsWaylandRegistryCommandWhenPrefixMarkerIsCurrent() = runBlocking {
        val fixture = Fixture(fixtureScript = "create-prefix")
        try {
            fixture.prefixDirectory.mkdirs()
            File(fixture.prefixDirectory, "system.reg").writeText(
                "WINE REGISTRY Version 2\n#arch=win64\n"
            )
            File(fixture.prefixDirectory, "user.reg").writeText(
                "WINE REGISTRY Version 2\n#arch=win64\n"
            )
            File(fixture.prefixDirectory, ".ramenwine-wayland-driver")
                .writeText("graphics=wayland-v1")

            fixture.initializer().ensureInitialized(fixture.request()) {}

            assertFalse(File(fixture.prefixDirectory, "wayland-config-count").exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun doesNotPromoteStagingPrefixWhenWaylandRegistryCommandFails() = runBlocking {
        val fixture = Fixture(fixtureScript = "wayland-config-exit-9")
        try {
            val error = runCatching {
                fixture.initializer().ensureInitialized(fixture.request()) {}
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("reg.exe exit code 9"))
            assertFalse(fixture.prefixDirectory.exists())
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
        private val prootLoader = executable(backendRoot, "host/libexec/proot/loader")
        private val proot = executable(
            backendRoot,
            "host/bin/proot",
            makeProotScript(fixtureScript)
        )
        private val displayDirectory = File(cacheDirectory, "display").apply { mkdirs() }

        init {
            executable(rootfs, "usr/bin/env")
            executable(rootfs, "usr/bin/box64")
            regular(rootfs, "usr/lib/box64-x86_64-linux-gnu/libstdc++.so.6")
            regular(rootfs, "usr/lib/box64-x86_64-linux-gnu/libgcc_s.so.1")
            regular(rootfs, "usr/lib/box64-x86_64-linux-gnu/libunwind.so.8")
            executable(rootfs, "usr/bin/wine")
            executable(rootfs, "usr/bin/wineserver")
            executable(
                rootfs,
                "usr/lib/x86_64-linux-gnu/wine/x86_64-unix/wine-preloader"
            )
            regular(
                rootfs,
                "usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe"
            )
            regular(
                rootfs,
                "usr/lib/x86_64-linux-gnu/wine/x86_64-windows/reg.exe"
            )
            regular(rootfs, "lib/ld-linux-aarch64.so.1")
            regular(rootfs, "lib64/ld-linux-x86-64.so.2")
            regular(
                rootfs,
                "usr/lib/x86_64-linux-gnu/wine/x86_64-unix/winewayland.so"
            )
            regular(
                rootfs,
                "usr/lib/x86_64-linux-gnu/wine/x86_64-windows/winewayland.drv"
            )
            regular(
                rootfs,
                "usr/lib/x86_64-linux-gnu/wine/i386-windows/winewayland.drv"
            )
            regular(rootfs, "usr/lib/x86_64-linux-gnu/libwayland-client.so.0")
            regular(rootfs, "usr/lib/x86_64-linux-gnu/libwayland-cursor.so.0")
            regular(rootfs, "usr/lib/x86_64-linux-gnu/libwayland-egl.so.1")
            regular(rootfs, "usr/lib/x86_64-linux-gnu/libxkbcommon.so.0")
            regular(rootfs, "usr/lib/x86_64-linux-gnu/libxkbregistry.so.0")
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

        fun useFixtureScript(mode: String) {
            proot.writeText(makeProotScript(mode))
            check(proot.setExecutable(true))
        }

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

            return shebang + """
                import sys
                from pathlib import Path
                prefix_binding = next(
                    argument.split("=", 1)[1]
                    for argument in sys.argv
                    if argument.startswith("--bind=") and ":/prefixes/" in argument
                )
                host_prefix = prefix_binding.rsplit(":/prefixes/", 1)[0]
                prefix = Path(host_prefix)
                prefix.mkdir(parents=True, exist_ok=True)
                if any(argument.endswith("/reg.exe") for argument in sys.argv):
                    required = [
                        "add",
                        "HKCU\\Software\\Wine\\Drivers",
                        "/v",
                        "Graphics",
                        "/t",
                        "REG_SZ",
                        "/d",
                        "wayland",
                        "/f",
                    ]
                    if any(item not in sys.argv for item in required):
                        print("invalid Wayland registry command", flush=True)
                        sys.exit(10)
                    if "$mode" == "wayland-config-exit-9":
                        print("Wayland registry command failed", flush=True)
                        sys.exit(9)
                    config_counter = prefix / "wayland-config-count"
                    config_count = int(
                        config_counter.read_text() if config_counter.exists() else "0"
                    ) + 1
                    config_counter.write_text(str(config_count))
                    print("Wayland graphics driver configured", flush=True)
                    sys.exit(0)

                counter = prefix / "wineboot-count"
                count = int(counter.read_text() if counter.exists() else "0") + 1
                counter.write_text(str(count))
                arch = "win32" if "$mode" == "create-prefix-win32" else "win64"
                registry = f"WINE REGISTRY Version 2\n#arch={arch}\n"
                (prefix / "system.reg").write_text(registry)
                (prefix / "user.reg").write_text(registry)
                print("wineboot finished", flush=True)
            """.trimIndent() + "\n"
        }
    }
}
