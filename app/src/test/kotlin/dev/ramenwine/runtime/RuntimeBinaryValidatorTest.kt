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

            assertTrue(errors.any { it.contains("x86-64 Wine loader") })
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

    private fun createRuntimeHeaders(backendRoot: File, wineMachine: Int) {
        listOf(
            "host/bin/proot" to 183,
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
