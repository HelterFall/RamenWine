package dev.ramenwine.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLayoutSymlinkTest {
    @Test
    fun validSymlinkIsAcceptedWhenFilesDirectoryUsesCanonicalAlias() {
        val root = Files.createTempDirectory("ramenwine-runtime-canonical").toFile()
        val alias = File(root.parentFile, root.name + "-alias")
        try {
            val backend = RuntimeBackendId.GLIBC_PROOT_BOX64
            val backendRoot = backendRoot(root, backend)
            createGlibcContract(backendRoot)

            val realLibrary = File(backendRoot, "lib/libwine.so.1")
            realLibrary.parentFile?.mkdirs()
            realLibrary.writeText("test library")
            Files.createSymbolicLink(
                File(backendRoot, "lib/libwine.so").toPath(),
                Paths.get("libwine.so.1")
            )

            writeManifest(
                backendRoot = backendRoot,
                backend = backend,
                requiredFiles = "bin/wine,lib/libwine.so.1",
                executableFiles = "bin/wine",
                requiredSymlinks = "lib/libwine.so"
            )

            // Android's filesDir can be exposed through a path alias. The
            // verifier must build link paths from the same canonical root
            // that it uses for containment checks.
            Files.createSymbolicLink(alias.toPath(), root.toPath())
            val result = RuntimeLayout.inspect(alias)

            assertEquals(
                RuntimeState.FILES_PRESENT,
                result.backends.first { it.backend == backend }.state
            )
        } finally {
            Files.deleteIfExists(alias.toPath())
            root.deleteRecursively()
        }
    }

    @Test
    fun missingDirectAudioAndroidHelperMakesBackendIncomplete() {
        val root = Files.createTempDirectory("ramenwine-runtime-audio-helper").toFile()
        try {
            val backend = RuntimeBackendId.GLIBC_PROOT_BOX64
            val backendRoot = backendRoot(root, backend)
            createGlibcContract(backendRoot)
            File(backendRoot, "host/bin/directaudio-relay").delete()

            writeManifest(
                backendRoot = backendRoot,
                backend = backend,
                requiredFiles = "bin/wine",
                executableFiles = "bin/wine",
                requiredSymlinks = ""
            )

            val result = RuntimeLayout.inspect(root)
            val inspection = result.backends.first { it.backend == backend }

            assertEquals(RuntimeState.INCOMPLETE, inspection.state)
            assertTrue(inspection.missingItems.any {
                it.contains("host/bin/directaudio-relay")
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validSymlinkInsideBackendIsAccepted() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val backend = RuntimeBackendId.GLIBC_PROOT_BOX64
            val backendRoot = backendRoot(root, backend)
            createGlibcContract(backendRoot)

            val realLibrary = File(backendRoot, "lib/libwine.so.1")
            realLibrary.parentFile?.mkdirs()
            realLibrary.writeText("test library")
            Files.createSymbolicLink(
                File(backendRoot, "lib/libwine.so").toPath(),
                Paths.get("libwine.so.1")
            )

            writeManifest(
                backendRoot = backendRoot,
                backend = backend,
                requiredFiles = "bin/wine,lib/libwine.so.1",
                executableFiles = "bin/wine",
                requiredSymlinks = "lib/libwine.so"
            )

            val result = RuntimeLayout.inspect(root)
            assertEquals(
                RuntimeState.FILES_PRESENT,
                result.backends.first { it.backend == backend }.state
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installedGuestAbsoluteWineserverLinkIsAcceptedAfterNormalization() {
        val root = Files.createTempDirectory("ramenwine-runtime-wineserver-link").toFile()
        try {
            val backend = RuntimeBackendId.GLIBC_PROOT_BOX64
            val backendRoot = backendRoot(root, backend)
            createGlibcContract(backendRoot)

            val wineserverLink = File(backendRoot, "rootfs/usr/bin/wineserver")
            assertTrue(wineserverLink.delete())
            val wineserverTarget = File(
                backendRoot,
                "rootfs/opt/ramenwine-wine/lib/wine/wineserver"
            )
            wineserverTarget.parentFile?.mkdirs()
            wineserverTarget.writeBytes(elf64(62))
            wineserverTarget.setExecutable(true)

            // RuntimePackageInstaller rewrites guest-absolute links into
            // relative links that remain inside the extracted rootfs.
            Files.createSymbolicLink(
                wineserverLink.toPath(),
                Paths.get("../../opt/ramenwine-wine/lib/wine/wineserver")
            )

            writeManifest(
                backendRoot = backendRoot,
                backend = backend,
                requiredFiles = "bin/wine",
                executableFiles = "bin/wine",
                requiredSymlinks = ""
            )

            val result = RuntimeLayout.inspect(root)
            assertEquals(
                RuntimeState.FILES_PRESENT,
                result.backends.first { it.backend == backend }.state
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun symlinkToRuntimeCreatedMountPathIsAccepted() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val backend = RuntimeBackendId.GLIBC_PROOT_BOX64
            val backendRoot = backendRoot(root, backend)
            createGlibcContract(backendRoot)

            File(backendRoot, "proc").mkdirs()
            val devDirectory = File(backendRoot, "dev")
            devDirectory.mkdirs()
            Files.createSymbolicLink(
                File(devDirectory, "fd").toPath(),
                Paths.get("../proc/self/fd")
            )

            writeManifest(
                backendRoot = backendRoot,
                backend = backend,
                requiredFiles = "bin/wine",
                executableFiles = "bin/wine",
                requiredSymlinks = "dev/fd"
            )

            val result = RuntimeLayout.inspect(root)
            assertEquals(
                RuntimeState.FILES_PRESENT,
                result.backends.first { it.backend == backend }.state
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun symlinkEscapingBackendIsRejected() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val backend = RuntimeBackendId.GLIBC_PROOT_BOX64
            val backendRoot = backendRoot(root, backend)
            createGlibcContract(backendRoot)

            val outside = File(root, "outside-secret.txt")
            outside.writeText("outside")
            val libraryDirectory = File(backendRoot, "lib")
            libraryDirectory.mkdirs()
            Files.createSymbolicLink(
                File(libraryDirectory, "escape").toPath(),
                Paths.get("../../../../../outside-secret.txt")
            )

            writeManifest(
                backendRoot = backendRoot,
                backend = backend,
                requiredFiles = "bin/wine",
                executableFiles = "bin/wine",
                requiredSymlinks = "lib/escape"
            )

            val result = RuntimeLayout.inspect(root)
            assertEquals(
                RuntimeState.INCOMPLETE,
                result.backends.first { it.backend == backend }.state
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun backendRoot(root: File, backend: RuntimeBackendId): File =
        File(root, "runtime/current/backends/" + backend.directoryName).apply { mkdirs() }

    private fun createGlibcContract(backendRoot: File) {
        listOf(
            "host/bin/proot" to 183,
            "host/bin/directaudio-relay" to 183,
            "rootfs/usr/bin/env" to 183,
            "rootfs/usr/bin/dash" to 183,
            "rootfs/usr/bin/box64" to 183,
            "rootfs/usr/bin/wine" to 62,
            "rootfs/usr/bin/wineserver" to 62
        ).forEach { (relative, machine) ->
            val executable = File(backendRoot, relative)
            executable.parentFile?.mkdirs()
            executable.writeBytes(elf64(machine))
            executable.setExecutable(true)
        }

        Files.createSymbolicLink(
            File(backendRoot, "rootfs/usr/bin/sh").toPath(),
            Paths.get("dash")
        )
        Files.createSymbolicLink(
            File(backendRoot, "rootfs/bin").toPath(),
            Paths.get("usr/bin")
        )

        val legacyExecutable = File(backendRoot, "bin/wine")
        legacyExecutable.parentFile?.mkdirs()
        legacyExecutable.writeText("test executable")
        legacyExecutable.setExecutable(true)

        val hostLibrary = File(backendRoot, "host/lib/libproot.so")
        hostLibrary.parentFile?.mkdirs()
        hostLibrary.writeText("test library")

        val wineboot = File(backendRoot, "rootfs/usr/lib/wine/x86_64-windows/wineboot.exe")
        wineboot.parentFile?.mkdirs()
        wineboot.writeBytes(pe32PlusAmd64())

        val audioUnix = File(
            backendRoot,
            "rootfs/opt/ramenwine-audio/lib/wine/x86_64-unix/winedirectaudio.so"
        )
        audioUnix.parentFile?.mkdirs()
        audioUnix.writeBytes(elf64(62))

        val audioPe64 = File(
            backendRoot,
            "rootfs/opt/ramenwine-audio/lib/wine/x86_64-windows/winedirectaudio.drv"
        )
        audioPe64.parentFile?.mkdirs()
        audioPe64.writeBytes(pe32PlusAmd64())

        val audioPe32 = File(
            backendRoot,
            "rootfs/opt/ramenwine-audio/lib/wine/i386-windows/winedirectaudio.drv"
        )
        audioPe32.parentFile?.mkdirs()
        audioPe32.writeBytes(pe32I386())

        val waylandDriver = File(
            backendRoot,
            "rootfs/opt/ramenwine-wine/x86_64-unix/winewayland.so"
        )
        waylandDriver.parentFile?.mkdirs()
        waylandDriver.writeBytes(elf64(62))

        // RuntimeLayout now verifies the bundled Win32 GDI smoke executable
        // as part of the installed glibc backend contract.
        val gdiSmoke = File(
            backendRoot,
            "rootfs/opt/ramenwine-tests/ramenwine-gdi-smoke.exe"
        )
        gdiSmoke.parentFile?.mkdirs()
        gdiSmoke.writeBytes(pe32PlusAmd64())
    }


    private fun elf64(machine: Int): ByteArray = ByteArray(64).apply {
        this[0] = 0x7f.toByte()
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = 2
        this[5] = 1
        this[6] = 1
        this[18] = (machine and 0xff).toByte()
        this[19] = ((machine ushr 8) and 0xff).toByte()
    }

    private fun pe32I386(): ByteArray = ByteArray(256).apply {
        this[0] = 'M'.code.toByte()
        this[1] = 'Z'.code.toByte()
        this[0x3c] = 0x80.toByte()
        this[0x80] = 'P'.code.toByte()
        this[0x81] = 'E'.code.toByte()
        this[0x82] = 0
        this[0x83] = 0
        this[0x84] = 0x4c
        this[0x85] = 0x01
        this[0x98] = 0x0b
        this[0x99] = 0x01
    }

    private fun pe32PlusAmd64(): ByteArray = ByteArray(256).apply {
        this[0] = 'M'.code.toByte()
        this[1] = 'Z'.code.toByte()
        this[0x3c] = 0x80.toByte()
        this[0x80] = 'P'.code.toByte()
        this[0x81] = 'E'.code.toByte()
        this[0x82] = 0
        this[0x83] = 0
        this[0x84] = 0x64
        this[0x85] = 0x86.toByte()
        this[0x98] = 0x0b
        this[0x99] = 0x02
    }

    private fun writeManifest(
        backendRoot: File,
        backend: RuntimeBackendId,
        requiredFiles: String,
        executableFiles: String,
        requiredSymlinks: String
    ) {
        File(backendRoot, "runtime.properties").writeText(
            "backendId=" + backend.directoryName + "\n" +
                "hostAbi=arm64-v8a\n" +
                "runtimeVersion=test\n" +
                "requiredFiles=" + requiredFiles + "\n" +
                "executableFiles=" + executableFiles + "\n" +
                "requiredSymlinks=" + requiredSymlinks + "\n"
        )
    }
}
