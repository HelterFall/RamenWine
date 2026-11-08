package dev.ramenwine.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeLayoutSymlinkTest {
    @Test
    fun validSymlinkInsideBackendIsAccepted() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val backend = RuntimeBackendId.GLIBC_PROOT_BOX64
            val backendRoot = backendRoot(root, backend)
            createExecutable(backendRoot)

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
    fun symlinkEscapingBackendIsRejected() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val backend = RuntimeBackendId.GLIBC_PROOT_BOX64
            val backendRoot = backendRoot(root, backend)
            createExecutable(backendRoot)

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

    private fun createExecutable(backendRoot: File) {
        val executable = File(backendRoot, "bin/wine")
        executable.parentFile?.mkdirs()
        executable.writeText("test executable")
        executable.setExecutable(true)
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
