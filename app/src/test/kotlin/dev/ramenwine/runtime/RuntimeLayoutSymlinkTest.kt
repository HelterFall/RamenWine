package dev.ramenwine.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLayoutTest {
    @Test
    fun missingRuntimeIsNotInstalled() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val result = RuntimeLayout.inspect(root)
            assertEquals(RuntimeState.NOT_INSTALLED, result.state)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyRuntimeDirectoryIsNotInstalled() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            File(root, "runtime/current").mkdirs()
            val result = RuntimeLayout.inspect(root)
            assertEquals(RuntimeState.NOT_INSTALLED, result.state)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestWithMissingFilesIsIncomplete() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            writeManifest(
                root = root,
                backend = RuntimeBackendId.GLIBC_PROOT_BOX64,
                requiredFiles = "host/bin/proot,rootfs/usr/bin/box64,rootfs/usr/bin/wine",
                executableFiles = "host/bin/proot,rootfs/usr/bin/box64,rootfs/usr/bin/wine"
            )

            val result = RuntimeLayout.inspect(root)

            assertEquals(RuntimeState.INCOMPLETE, result.state)
            assertTrue(result.missingItems.any { it.contains("host/bin/proot") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installedBackendIsRecognizedOnlyWhenRequiredExecutablesExist() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val backend = RuntimeBackendId.BIONIC_ARM64EC_FEX
            val backendRoot = File(
                root,
                "runtime/current/backends/" + backend.directoryName
            )
            backendRoot.mkdirs()
            val wine = File(backendRoot, "bin/wine")
            wine.parentFile?.mkdirs()
            wine.writeText("test executable")
            wine.setExecutable(true)

            writeManifest(
                root = root,
                backend = backend,
                requiredFiles = "bin/wine",
                executableFiles = "bin/wine"
            )

            val result = RuntimeLayout.inspect(root)

            assertEquals(RuntimeState.FILES_PRESENT, result.state)
            assertEquals(
                RuntimeState.FILES_PRESENT,
                result.backends.first { it.backend == backend }.state
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestCannotEscapeItsBackendDirectory() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            writeManifest(
                root = root,
                backend = RuntimeBackendId.BIONIC_X64_BOX64,
                requiredFiles = "../outside",
                executableFiles = ""
            )

            val result = RuntimeLayout.inspect(root)

            assertEquals(RuntimeState.INCOMPLETE, result.state)
            assertTrue(result.missingItems.any { it.contains("Unsafe path") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validSymlinkInsideBackendIsAccepted() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val backend = RuntimeBackendId.GLIBC_PROOT_BOX64
            val backendRoot = File(root, "runtime/current/backends/" + backend.directoryName)
            val executable = File(backendRoot, "bin/wine")
            executable.parentFile?.mkdirs()
            executable.writeText("test executable")
            executable.setExecutable(true)

            val realLibrary = File(backendRoot, "lib/libwine.so.1")
            realLibrary.parentFile?.mkdirs()
            realLibrary.writeText("test library")
            Files.createSymbolicLink(
                File(backendRoot, "lib/libwine.so").toPath(),
                java.nio.file.Paths.get("libwine.so.1")
            )

            writeManifest(
                root = root,
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
    fun symlinkEscapingBackendIsRejected() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val backend = RuntimeBackendId.GLIBC_PROOT_BOX64
            val backendRoot = File(root, "runtime/current/backends/" + backend.directoryName)
            val executable = File(backendRoot, "bin/wine")
            executable.parentFile?.mkdirs()
            executable.writeText("test executable")
            executable.setExecutable(true)

            val outside = File(root, "outside-secret.txt")
            outside.writeText("outside")
            val libraryDirectory = File(backendRoot, "lib")
            libraryDirectory.mkdirs()
            Files.createSymbolicLink(
                File(libraryDirectory, "escape").toPath(),
                java.nio.file.Paths.get("../../../../../outside-secret.txt")
            )

            writeManifest(
                root = root,
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

    private fun writeManifest(
        root: File,
        backend: RuntimeBackendId,
        requiredFiles: String,
        executableFiles: String,
        requiredSymlinks: String = ""
    ) {
        val directory = File(
            root,
            "runtime/current/backends/" + backend.directoryName
        )
        directory.mkdirs()
        val manifest = File(directory, "runtime.properties")
        manifest.writeText(
            "backendId=" + backend.directoryName + "\n" +
                "hostAbi=arm64-v8a\n" +
                "runtimeVersion=test\n" +
                "requiredFiles=" + requiredFiles + "\n" +
                "executableFiles=" + executableFiles + "\n" +
                "requiredSymlinks=" + requiredSymlinks + "\n"
        )
    }
}
