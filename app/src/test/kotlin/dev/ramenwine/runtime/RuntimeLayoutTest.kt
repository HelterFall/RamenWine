package dev.ramenwine.runtime

import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.json.JSONArray
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
    fun glibcBackendIsIncompleteWithoutWinebootOrHostLibraries() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val backend = RuntimeBackendId.GLIBC_PROOT_BOX64
            val backendRoot = File(
                root,
                "runtime/current/backends/" + backend.directoryName
            )
            listOf(
                "host/bin/proot",
                "rootfs/usr/bin/env",
                "rootfs/usr/bin/box64",
                "rootfs/usr/bin/wine"
            ).forEach { relative ->
                val executable = File(backendRoot, relative)
                executable.parentFile?.mkdirs()
                executable.writeText("test executable")
                executable.setExecutable(true)
            }
            writeManifest(
                root = root,
                backend = backend,
                requiredFiles = "host/bin/proot,rootfs/usr/bin/env,rootfs/usr/bin/box64,rootfs/usr/bin/wine",
                executableFiles = "host/bin/proot,rootfs/usr/bin/env,rootfs/usr/bin/box64,rootfs/usr/bin/wine"
            )

            val result = RuntimeLayout.inspect(root)
            val inspection = result.backends.first { it.backend == backend }

            assertEquals(RuntimeState.INCOMPLETE, inspection.state)
            assertTrue(inspection.missingItems.any { it.contains("wineboot") })
            assertTrue(inspection.missingItems.any { it.contains("host/lib") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installedManifestSupportsCommaInFilename() {
        val root = Files.createTempDirectory("ramenwine-runtime").toFile()
        try {
            val backend = RuntimeBackendId.BIONIC_ARM64EC_FEX
            val backendRoot = File(
                root,
                "runtime/current/backends/" + backend.directoryName
            )
            val relative = "share/i18n/charmaps/ISO_8859-1,GL.gz"
            val charmap = File(backendRoot, relative)
            charmap.parentFile?.mkdirs()
            charmap.writeText("locale charmap")
            charmap.setExecutable(true)

            fun encode(paths: List<String>): String {
                val json = JSONArray().apply { paths.forEach { put(it) } }.toString()
                return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.toByteArray(Charsets.UTF_8))
            }

            val manifest = File(backendRoot, "runtime.properties")
            manifest.writeText(
                "backendId=" + backend.directoryName + "\n" +
                    "hostAbi=arm64-v8a\n" +
                    "runtimeVersion=test\n" +
                    "pathListFormat=json-base64-v1\n" +
                    "requiredFilesBase64=" + encode(listOf(relative)) + "\n" +
                    "executableFilesBase64=" + encode(listOf(relative)) + "\n" +
                    "requiredSymlinksBase64=" + encode(emptyList()) + "\n"
            )

            val result = RuntimeLayout.inspect(root)
            assertEquals(RuntimeState.FILES_PRESENT, result.state)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeManifest(
        root: File,
        backend: RuntimeBackendId,
        requiredFiles: String,
        executableFiles: String
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
                "executableFiles=" + executableFiles + "\n"
        )
    }
}
