package dev.ramenwine.runtime

import java.io.File
import java.io.RandomAccessFile

/**
 * Checks the machine architecture of the binaries used by the glibc/PRoot path.
 * This is a header check only; it does not execute the binaries.
 */
internal object RuntimeBinaryValidator {
    private data class ElfIdentity(
        val elfClass: Int,
        val dataEncoding: Int,
        val machine: Int
    )

    private val elfRequirements = listOf(
        Triple("host/bin/proot", ELF_MACHINE_AARCH64, "Android-host PRoot"),
        Triple("rootfs/usr/bin/env", ELF_MACHINE_AARCH64, "guest env"),
        Triple("rootfs/usr/bin/box64", ELF_MACHINE_AARCH64, "Box64"),
        Triple("rootfs/usr/bin/wine", ELF_MACHINE_X86_64, "Wine loader for Box64")
    )

    private val winebootCandidates = listOf(
        "rootfs/usr/lib/wine/x86_64-windows/wineboot.exe",
        "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe"
    )

    fun inspectInstalled(backend: RuntimeBackendId, backendRoot: File): List<String> {
        if (backend != RuntimeBackendId.GLIBC_PROOT_BOX64) return emptyList()
        val errors = mutableListOf<String>()

        for ((relativePath, expectedMachine, label) in elfRequirements) {
            val file = resolveInside(backendRoot, relativePath) ?: continue
            if (!file.isFile) continue
            val identity = readElfIdentity(file)
            if (identity != ElfIdentity(ELF_CLASS_64, ELF_DATA_LITTLE_ENDIAN, expectedMachine)) {
                val expected = if (expectedMachine == ELF_MACHINE_AARCH64) "AArch64" else "x86-64"
                errors += "$label must be a 64-bit little-endian $expected ELF: $relativePath"
            }
        }

        val winebootFiles = winebootCandidates
            .asSequence()
            .mapNotNull { resolveInside(backendRoot, it) }
            .filter { it.isFile && it.canRead() }
            .toList()
        if (winebootFiles.isNotEmpty() && winebootFiles.none(::isX86_64Pe32Plus)) {
            errors += "Wine prefix initializer must be an x86-64 PE32+ executable."
        }

        return errors
    }

    private fun readElfIdentity(file: File): ElfIdentity? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < ELF_HEADER_MIN_BYTES) return null
            val header = ByteArray(ELF_HEADER_MIN_BYTES)
            input.readFully(header)
            if (
                header[0] != 0x7f.toByte() ||
                header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() ||
                header[3] != 'F'.code.toByte()
            ) return null

            val elfClass = header[4].toInt() and 0xff
            val dataEncoding = header[5].toInt() and 0xff
            if (dataEncoding !in setOf(ELF_DATA_LITTLE_ENDIAN, ELF_DATA_BIG_ENDIAN)) return null
            val machine = when (dataEncoding) {
                ELF_DATA_LITTLE_ENDIAN ->
                    ((header[19].toInt() and 0xff) shl 8) or (header[18].toInt() and 0xff)
                else ->
                    ((header[18].toInt() and 0xff) shl 8) or (header[19].toInt() and 0xff)
            }
            ElfIdentity(elfClass, dataEncoding, machine)
        }
    }.getOrNull()

    private fun isX86_64Pe32Plus(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < DOS_HEADER_BYTES) return false
            val dosHeader = ByteArray(DOS_HEADER_BYTES)
            input.readFully(dosHeader)
            if (dosHeader[0] != 'M'.code.toByte() || dosHeader[1] != 'Z'.code.toByte()) {
                return false
            }

            val peOffset = readUInt32LittleEndian(dosHeader, PE_OFFSET_FIELD)
            if (peOffset < DOS_HEADER_BYTES || peOffset > MAX_PE_HEADER_OFFSET) return false
            if (peOffset + PE_HEADER_BYTES > input.length()) return false

            input.seek(peOffset)
            val peHeader = ByteArray(PE_HEADER_BYTES.toInt())
            input.readFully(peHeader)
            peHeader[0] == 'P'.code.toByte() &&
                peHeader[1] == 'E'.code.toByte() &&
                peHeader[2] == 0.toByte() &&
                peHeader[3] == 0.toByte() &&
                readUInt16LittleEndian(peHeader, 4) == PE_MACHINE_AMD64 &&
                readUInt16LittleEndian(peHeader, 24) == PE_OPTIONAL_HEADER_PE32_PLUS
        }
    }.getOrDefault(false)

    private fun readUInt32LittleEndian(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun readUInt16LittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun resolveInside(root: File, relativePath: String): File? {
        val safePath = SafeRuntimePath.normalizeRelativePath(relativePath) ?: return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val prefix = canonicalRoot.path + File.separator
        val file = runCatching { File(root, safePath).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.path.startsWith(prefix) }
    }

    private const val ELF_HEADER_MIN_BYTES = 20L
    private const val ELF_CLASS_64 = 2
    private const val ELF_DATA_LITTLE_ENDIAN = 1
    private const val ELF_DATA_BIG_ENDIAN = 2
    private const val ELF_MACHINE_X86_64 = 62
    private const val ELF_MACHINE_AARCH64 = 183
    private const val DOS_HEADER_BYTES = 64L
    private const val PE_OFFSET_FIELD = 0x3c
    private const val PE_HEADER_BYTES = 26L
    private const val MAX_PE_HEADER_OFFSET = 16L * 1024L * 1024L
    private const val PE_MACHINE_AMD64 = 0x8664
    private const val PE_OPTIONAL_HEADER_PE32_PLUS = 0x20b
}
