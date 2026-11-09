package dev.ramenwine.runtime

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Performs a small device-side ABI check before a glibc/PRoot runtime is
 * activated. The Python packager still checks the complete DT_NEEDED closure;
 * this check independently catches missing files and wrong ELF/PE machines in
 * the exact ZIP that the Android installer is about to activate.
 */
internal object GlibcRuntimeAbiValidator {
    internal const val ELF_MACHINE_AARCH64 = 183
    internal const val ELF_MACHINE_X86_64 = 62
    internal const val PE_MACHINE_AMD64 = 0x8664
    internal const val PE_MACHINE_I386 = 0x014c

    private data class ElfSpec(
        val path: String,
        val machine: Int,
        val interpreter: String? = null
    )

    private data class PeSpec(
        val path: String,
        val machine: Int
    )

    private data class ElfInfo(
        val machine: Int,
        val interpreter: String?
    )

    fun validate(backendDirectory: File) {
        val root = backendDirectory.canonicalFile
        if (!root.isDirectory) {
            throw IOException("The glibc runtime directory is missing.")
        }

        val elfFiles = listOf(
            ElfSpec("host/bin/proot", ELF_MACHINE_AARCH64, "/system/bin/linker64"),
            ElfSpec("host/libexec/proot/loader", ELF_MACHINE_AARCH64),
            ElfSpec("host/lib/libtalloc.so.2", ELF_MACHINE_AARCH64),
            ElfSpec("host/lib/libandroid-shmem.so", ELF_MACHINE_AARCH64),
            ElfSpec("rootfs/lib/ld-linux-aarch64.so.1", ELF_MACHINE_AARCH64),
            ElfSpec("rootfs/lib64/ld-linux-x86-64.so.2", ELF_MACHINE_X86_64),
            ElfSpec(
                "rootfs/usr/bin/env",
                ELF_MACHINE_AARCH64,
                "/lib/ld-linux-aarch64.so.1"
            ),
            ElfSpec(
                "rootfs/usr/bin/box64",
                ELF_MACHINE_AARCH64,
                "/lib/ld-linux-aarch64.so.1"
            ),
            ElfSpec(
                "rootfs/usr/bin/wine",
                ELF_MACHINE_X86_64,
                "/lib64/ld-linux-x86-64.so.2"
            ),
            ElfSpec(
                "rootfs/usr/bin/wineserver",
                ELF_MACHINE_X86_64,
                "/lib64/ld-linux-x86-64.so.2"
            ),
            ElfSpec(
                "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-unix/wine-preloader",
                ELF_MACHINE_X86_64
            ),
            ElfSpec(
                "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-unix/winewayland.so",
                ELF_MACHINE_X86_64
            ),
            ElfSpec(
                "rootfs/usr/lib/box64-x86_64-linux-gnu/libstdc++.so.6",
                ELF_MACHINE_X86_64
            ),
            ElfSpec(
                "rootfs/usr/lib/box64-x86_64-linux-gnu/libgcc_s.so.1",
                ELF_MACHINE_X86_64
            ),
            ElfSpec(
                "rootfs/usr/lib/box64-x86_64-linux-gnu/libunwind.so.8",
                ELF_MACHINE_X86_64
            )
        )

        for (spec in elfFiles) {
            val file = requiredFile(root, spec.path)
            validateElf(file, spec.machine, spec.interpreter)
        }

        val waylandLibraries = listOf(
            "libwayland-client.so.0",
            "libwayland-cursor.so.0",
            "libwayland-egl.so.1",
            "libxkbcommon.so.0",
            "libxkbregistry.so.0"
        )
        for (name in waylandLibraries) {
            validateElf(
                requiredFile(root, "rootfs/usr/lib/x86_64-linux-gnu/$name"),
                ELF_MACHINE_X86_64
            )
            validateElf(
                requiredFile(root, "rootfs/usr/lib/aarch64-linux-gnu/$name"),
                ELF_MACHINE_AARCH64
            )
        }

        val peFiles = listOf(
            PeSpec(
                "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe",
                PE_MACHINE_AMD64
            ),
            PeSpec(
                "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/reg.exe",
                PE_MACHINE_AMD64
            ),
            PeSpec(
                "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/ntdll.dll",
                PE_MACHINE_AMD64
            ),
            PeSpec(
                "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/winewayland.drv",
                PE_MACHINE_AMD64
            ),
            PeSpec(
                "rootfs/usr/lib/x86_64-linux-gnu/wine/i386-windows/ntdll.dll",
                PE_MACHINE_I386
            ),
            PeSpec(
                "rootfs/usr/lib/x86_64-linux-gnu/wine/i386-windows/winewayland.drv",
                PE_MACHINE_I386
            ),
            PeSpec(
                "rootfs/usr/lib/ramenwine/probes/wayland-probe-x86_64.exe",
                PE_MACHINE_AMD64
            ),
            PeSpec(
                "rootfs/usr/lib/ramenwine/probes/wayland-probe-i386.exe",
                PE_MACHINE_I386
            )
        )

        for (spec in peFiles) {
            validatePe(requiredFile(root, spec.path), spec.machine)
        }
    }

    internal fun validateElf(
        file: File,
        expectedMachine: Int,
        expectedInterpreter: String? = null
    ) {
        val info = inspectElf(file)
        if (info.machine != expectedMachine) {
            throw IOException(
                "Wrong ELF architecture in ${file.name}: expected ${machineName(expectedMachine)}, " +
                    "found ${machineName(info.machine)}."
            )
        }
        if (expectedInterpreter != null && info.interpreter != expectedInterpreter) {
            throw IOException(
                "Wrong ELF loader in ${file.name}: expected $expectedInterpreter, " +
                    "found ${info.interpreter ?: "no interpreter"}."
            )
        }
    }

    internal fun validatePe(file: File, expectedMachine: Int) {
        val machine = inspectPeMachine(file)
        if (machine != expectedMachine) {
            throw IOException(
                "Wrong Windows architecture in ${file.name}: expected ${peMachineName(expectedMachine)}, " +
                    "found ${peMachineName(machine)}."
            )
        }
    }

    private fun requiredFile(root: File, relativePath: String): File {
        val file = File(root, relativePath).canonicalFile
        val prefix = root.path + File.separator
        if (!file.path.startsWith(prefix) || !file.isFile) {
            throw IOException("Required runtime file is missing or unsafe: $relativePath")
        }
        return file
    }

    private fun inspectElf(file: File): ElfInfo {
        if (!file.isFile) {
            throw IOException("Required ELF file is missing: ${file.path}")
        }

        try {
            RandomAccessFile(file, "r").use { input ->
                val header = readAt(input, 0L, ELF_HEADER_BYTES)
                if (
                    header[0] != 0x7f.toByte() ||
                    header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() ||
                    header[3] != 'F'.code.toByte()
                ) {
                    throw IOException("${file.name} is not an ELF executable or library.")
                }
                if (header[4] != ELF_CLASS_64 || header[5] != ELF_DATA_LITTLE_ENDIAN) {
                    throw IOException("${file.name} must be a 64-bit little-endian ELF file.")
                }

                val view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val machine = view.getShort(18).toInt() and 0xffff
                val programHeaderOffset = view.getLong(32)
                val programHeaderSize = view.getShort(54).toInt() and 0xffff
                val programHeaderCount = view.getShort(56).toInt() and 0xffff

                if (programHeaderCount > MAX_PROGRAM_HEADERS) {
                    throw IOException("${file.name} has too many ELF program headers.")
                }
                if (programHeaderCount > 0 && programHeaderSize < ELF_PROGRAM_HEADER_BYTES) {
                    throw IOException("${file.name} has an invalid ELF program-header size.")
                }

                val tableBytes = programHeaderSize.toLong() * programHeaderCount.toLong()
                if (
                    programHeaderCount > 0 &&
                    (programHeaderOffset < ELF_HEADER_BYTES ||
                        programHeaderOffset > input.length() - tableBytes)
                ) {
                    throw IOException("${file.name} has a truncated ELF program-header table.")
                }

                var interpreter: String? = null
                for (index in 0 until programHeaderCount) {
                    val offset = programHeaderOffset + index.toLong() * programHeaderSize
                    val programHeader = readAt(input, offset, ELF_PROGRAM_HEADER_BYTES)
                    val programView = ByteBuffer.wrap(programHeader).order(ByteOrder.LITTLE_ENDIAN)
                    val type = programView.getInt(0)
                    if (type != PT_INTERP) continue
                    if (interpreter != null) {
                        throw IOException("${file.name} has multiple ELF interpreters.")
                    }

                    val stringOffset = programView.getLong(8)
                    val stringSize = programView.getLong(32)
                    if (
                        stringOffset < 0L ||
                        stringSize !in 2L..MAX_INTERPRETER_BYTES.toLong() ||
                        stringOffset > input.length() - stringSize
                    ) {
                        throw IOException("${file.name} has an invalid ELF interpreter segment.")
                    }

                    val raw = readAt(input, stringOffset, stringSize.toInt())
                    if (raw.last() != 0.toByte() || raw.dropLast(1).any { it == 0.toByte() }) {
                        throw IOException("${file.name} has a malformed ELF interpreter path.")
                    }
                    interpreter = String(raw, 0, raw.size - 1, US_ASCII)
                    if (!interpreter.startsWith('/')) {
                        throw IOException("${file.name} has a non-absolute ELF interpreter.")
                    }
                }
                return ElfInfo(machine, interpreter)
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Could not inspect ELF file ${file.path}.", error)
        }
    }

    private fun inspectPeMachine(file: File): Int {
        if (!file.isFile) {
            throw IOException("Required Windows PE file is missing: ${file.path}")
        }

        try {
            RandomAccessFile(file, "r").use { input ->
                val dosHeader = readAt(input, 0L, PE_DOS_HEADER_BYTES)
                if (dosHeader[0] != 'M'.code.toByte() || dosHeader[1] != 'Z'.code.toByte()) {
                    throw IOException("${file.name} is not a Windows PE file.")
                }

                val dosView = ByteBuffer.wrap(dosHeader).order(ByteOrder.LITTLE_ENDIAN)
                val peOffset = dosView.getInt(0x3c).toLong() and 0xffff_ffffL
                if (peOffset < PE_DOS_HEADER_BYTES || peOffset > input.length() - PE_HEADER_BYTES) {
                    throw IOException("${file.name} has an invalid PE header offset.")
                }

                val peHeader = readAt(input, peOffset, PE_HEADER_BYTES)
                if (
                    peHeader[0] != 'P'.code.toByte() ||
                    peHeader[1] != 'E'.code.toByte() ||
                    peHeader[2] != 0.toByte() ||
                    peHeader[3] != 0.toByte()
                ) {
                    throw IOException("${file.name} has no PE signature.")
                }

                return ByteBuffer.wrap(peHeader).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(4).toInt() and 0xffff
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Could not inspect Windows PE file ${file.path}.", error)
        }
    }

    private fun readAt(input: RandomAccessFile, offset: Long, length: Int): ByteArray {
        if (offset < 0L || length < 0 || offset > input.length() - length.toLong()) {
            throw IOException("A runtime binary contains an out-of-range header.")
        }
        val bytes = ByteArray(length)
        input.seek(offset)
        input.readFully(bytes)
        return bytes
    }

    private fun machineName(machine: Int): String = when (machine) {
        ELF_MACHINE_AARCH64 -> "AArch64"
        ELF_MACHINE_X86_64 -> "x86-64"
        else -> "machine 0x${machine.toString(16)}"
    }

    private fun peMachineName(machine: Int): String = when (machine) {
        PE_MACHINE_AMD64 -> "Windows x86-64"
        PE_MACHINE_I386 -> "Windows x86"
        else -> "PE machine 0x${machine.toString(16)}"
    }

    private const val ELF_HEADER_BYTES = 64
    private const val ELF_PROGRAM_HEADER_BYTES = 56
    private const val PE_DOS_HEADER_BYTES = 64
    private const val PE_HEADER_BYTES = 6
    private const val PT_INTERP = 3
    private const val MAX_PROGRAM_HEADERS = 4096
    private const val MAX_INTERPRETER_BYTES = 4096
    private const val ELF_CLASS_64: Byte = 2
    private const val ELF_DATA_LITTLE_ENDIAN: Byte = 1
    private val US_ASCII: Charset = Charsets.US_ASCII
}
