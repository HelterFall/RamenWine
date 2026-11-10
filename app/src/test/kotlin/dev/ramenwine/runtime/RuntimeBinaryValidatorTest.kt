package dev.ramenwine.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeBinaryValidatorTest {
    @Test
    fun rejectsArm64WineLoaderInBox64Backend() {
        val root = Files.createTempDirectory("ramenwine-binary-check").toFile()
        try {
            val backendRoot = File(root, "backend")
            createRuntimeHeaders(backendRoot, wineMachine = 183)

            val errors = RuntimeBinaryValidator.inspectInstalled(
                RuntimeBackendId.GLIBC_PROOT_BOX64,
                backendRoot
            )

            assertTrue(errors.any { it.contains("Wine loader for Box64") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsExpectedElfAndWinebootHeaders() {
        val root = Files.createTempDirectory("ramenwine-binary-check").toFile()
        try {
            val backendRoot = File(root, "backend")
            createRuntimeHeaders(backendRoot, wineMachine = 62)

            val errors = RuntimeBinaryValidator.inspectInstalled(
                RuntimeBackendId.GLIBC_PROOT_BOX64,
                backendRoot
            )

            assertFalse(errors.any { it.contains("Wine loader for Box64") })
            assertTrue(errors.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsWrongArchitectureForDirectAudio32BitDriver() {
        val root = Files.createTempDirectory("ramenwine-binary-check").toFile()
        try {
            val backendRoot = File(root, "backend")
            createRuntimeHeaders(backendRoot, wineMachine = 62)
            val audioPe32 = File(
                backendRoot,
                "rootfs/opt/ramenwine-audio/lib/wine/i386-windows/winedirectaudio.drv"
            )
            audioPe32.writeBytes(pe32PlusAmd64())

            val errors = RuntimeBinaryValidator.inspectInstalled(
                RuntimeBackendId.GLIBC_PROOT_BOX64,
                backendRoot
            )

            assertTrue(errors.any { it.contains("i386 PE32") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsRuntimeWithoutWineWaylandDriver() {
        val root = Files.createTempDirectory("ramenwine-binary-check").toFile()
        try {
            val backendRoot = File(root, "backend")
            createRuntimeHeaders(backendRoot, wineMachine = 62)
            File(
                backendRoot,
                "rootfs/opt/ramenwine-wine/x86_64-unix/winewayland.so"
            ).delete()

            val errors = RuntimeBinaryValidator.inspectInstalled(
                RuntimeBackendId.GLIBC_PROOT_BOX64,
                backendRoot
            )

            assertTrue(errors.any { it.contains("Wine Wayland Unix library is missing") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsArm64WineWaylandDriver() {
        val root = Files.createTempDirectory("ramenwine-binary-check").toFile()
        try {
            val backendRoot = File(root, "backend")
            createRuntimeHeaders(backendRoot, wineMachine = 62)
            File(
                backendRoot,
                "rootfs/opt/ramenwine-wine/x86_64-unix/winewayland.so"
            ).writeBytes(elf64(183))

            val errors = RuntimeBinaryValidator.inspectInstalled(
                RuntimeBackendId.GLIBC_PROOT_BOX64,
                backendRoot
            )

            assertTrue(errors.any { it.contains("x86-64 ELF") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createRuntimeHeaders(backendRoot: File, wineMachine: Int) {
        listOf(
            "host/bin/proot" to 183,
            "host/bin/directaudio-relay" to 183,
            "rootfs/usr/bin/env" to 183,
            "rootfs/usr/bin/box64" to 183,
            "rootfs/usr/bin/wine" to wineMachine
        ).forEach { (relative, machine) ->
            val file = File(backendRoot, relative)
            file.parentFile?.mkdirs()
            file.writeBytes(elf64(machine))
        }

        val wineboot = File(
            backendRoot,
            "rootfs/usr/lib/wine/x86_64-windows/wineboot.exe"
        )
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
        this[0x84] = 0x64
        this[0x85] = 0x86.toByte()
        this[0x98] = 0x0b
        this[0x99] = 0x02
    }
}
