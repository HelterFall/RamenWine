package dev.ramenwine.runtime

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class GlibcRuntimeAbiValidatorTest {
    @Test
    fun acceptsAarch64ElfWithAndroidInterpreter() {
        val file = temporaryFile("proot")
        writeElf(file, GlibcRuntimeAbiValidator.ELF_MACHINE_AARCH64, "/system/bin/linker64")

        GlibcRuntimeAbiValidator.validateElf(
            file,
            GlibcRuntimeAbiValidator.ELF_MACHINE_AARCH64,
            "/system/bin/linker64"
        )
    }

    @Test(expected = IOException::class)
    fun rejectsWrongElfMachine() {
        val file = temporaryFile("box64")
        writeElf(file, GlibcRuntimeAbiValidator.ELF_MACHINE_X86_64, null)

        GlibcRuntimeAbiValidator.validateElf(
            file,
            GlibcRuntimeAbiValidator.ELF_MACHINE_AARCH64
        )
    }

    @Test(expected = IOException::class)
    fun rejectsWrongElfInterpreter() {
        val file = temporaryFile("wine")
        writeElf(file, GlibcRuntimeAbiValidator.ELF_MACHINE_X86_64, "/lib/ld-linux.so.2")

        GlibcRuntimeAbiValidator.validateElf(
            file,
            GlibcRuntimeAbiValidator.ELF_MACHINE_X86_64,
            "/lib64/ld-linux-x86-64.so.2"
        )
    }

    @Test
    fun acceptsBothSupportedWindowsPeMachines() {
        val amd64 = temporaryFile("game-amd64.exe")
        val i386 = temporaryFile("game-i386.exe")
        writePe(amd64, GlibcRuntimeAbiValidator.PE_MACHINE_AMD64)
        writePe(i386, GlibcRuntimeAbiValidator.PE_MACHINE_I386)

        GlibcRuntimeAbiValidator.validatePe(
            amd64,
            GlibcRuntimeAbiValidator.PE_MACHINE_AMD64
        )
        GlibcRuntimeAbiValidator.validatePe(
            i386,
            GlibcRuntimeAbiValidator.PE_MACHINE_I386
        )
    }

    @Test(expected = IOException::class)
    fun rejectsPeFileInWrongArchitectureDirectory() {
        val file = temporaryFile("winewayland.drv")
        writePe(file, GlibcRuntimeAbiValidator.PE_MACHINE_AMD64)

        GlibcRuntimeAbiValidator.validatePe(
            file,
            GlibcRuntimeAbiValidator.PE_MACHINE_I386
        )
    }

    private fun temporaryFile(name: String): File {
        val directory = Files.createTempDirectory("ramenwine-abi-test").toFile()
        temporaryDirectories += directory
        return File(directory, name)
    }

    private fun writeElf(file: File, machine: Int, interpreter: String?) {
        val interpreterBytes = interpreter?.toByteArray(Charsets.US_ASCII)
            ?.plus(0.toByte())
        val programHeaderCount = if (interpreterBytes == null) 0 else 1
        val programHeaderOffset = 64
        val interpreterOffset = programHeaderOffset + 56
        val size = interpreterOffset + (interpreterBytes?.size ?: 0)
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(0, 0x7f.toByte())
        buffer.put(1, 'E'.code.toByte())
        buffer.put(2, 'L'.code.toByte())
        buffer.put(3, 'F'.code.toByte())
        buffer.put(4, 2)
        buffer.put(5, 1)
        buffer.put(6, 1)
        buffer.putShort(16, 2)
        buffer.putShort(18, machine.toShort())
        buffer.putInt(20, 1)
        buffer.putLong(32, programHeaderOffset.toLong())
        buffer.putShort(52, 64)
        buffer.putShort(54, 56)
        buffer.putShort(56, programHeaderCount.toShort())

        if (interpreterBytes != null) {
            buffer.putInt(programHeaderOffset, 3)
            buffer.putInt(programHeaderOffset + 4, 4)
            buffer.putLong(programHeaderOffset + 8, interpreterOffset.toLong())
            buffer.putLong(programHeaderOffset + 32, interpreterBytes.size.toLong())
            buffer.putLong(programHeaderOffset + 40, interpreterBytes.size.toLong())
            buffer.position(interpreterOffset)
            buffer.put(interpreterBytes)
        }

        file.writeBytes(buffer.array().toString(Charsets.ISO_8859_1))
        Files.write(file.toPath(), buffer.array())
    }

    private fun writePe(file: File, machine: Int) {
        val bytes = ByteArray(70)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        buffer.putInt(0x3c, 64)
        bytes[64] = 'P'.code.toByte()
        bytes[65] = 'E'.code.toByte()
        buffer.putShort(68, machine.toShort())
        Files.write(file.toPath(), bytes)
    }

    private companion object {
        val temporaryDirectories = mutableListOf<File>()
    }
}
