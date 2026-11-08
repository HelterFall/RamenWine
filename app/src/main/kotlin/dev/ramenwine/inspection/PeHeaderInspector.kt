package dev.ramenwine.inspection

/**
 * A bounded PE header reader. It never executes or loads the inspected file.
 *
 * The caller supplies only a small prefix of the file. A missing PE signature
 * is reported as a diagnostic instead of being treated as proof that the file
 * is safe or launchable.
 */
object PeHeaderInspector {
    private const val DOS_HEADER_SIZE = 64
    private const val PE_SIGNATURE_SIZE = 4
    private const val COFF_HEADER_SIZE = 20
    private const val PE_OFFSET_FIELD = 0x3C
    private const val MAX_ACCEPTED_PE_OFFSET = 1024 * 1024

    fun inspect(prefix: ByteArray): PeInspection? {
        if (prefix.size < 2 || prefix[0] != 'M'.code.toByte() || prefix[1] != 'Z'.code.toByte()) {
            return null
        }

        if (prefix.size < DOS_HEADER_SIZE) {
            return PeInspection(PeArchitecture.UNKNOWN, false, "DOS header is truncated")
        }

        val peOffset = readUInt32LittleEndian(prefix, PE_OFFSET_FIELD)
        if (peOffset > MAX_ACCEPTED_PE_OFFSET.toLong()) {
            return PeInspection(PeArchitecture.UNKNOWN, false, "PE header offset exceeds inspection limit")
        }

        val requiredEnd = peOffset + PE_SIGNATURE_SIZE + COFF_HEADER_SIZE
        if (requiredEnd > prefix.size.toLong()) {
            return PeInspection(PeArchitecture.UNKNOWN, false, "PE header is outside the inspected file prefix")
        }

        val offset = peOffset.toInt()
        if (
            prefix[offset] != 'P'.code.toByte() ||
            prefix[offset + 1] != 'E'.code.toByte() ||
            prefix[offset + 2] != 0.toByte() ||
            prefix[offset + 3] != 0.toByte()
        ) {
            return PeInspection(PeArchitecture.UNKNOWN, false, "MZ header found, but PE signature is missing")
        }

        val machine = readUInt16LittleEndian(prefix, offset + PE_SIGNATURE_SIZE)
        val architecture = when (machine) {
            0x014C -> PeArchitecture.X86
            0x8664 -> PeArchitecture.X86_64
            0x01C0, 0x01C4 -> PeArchitecture.ARM32
            0xAA64 -> PeArchitecture.ARM64
            0xA641 -> PeArchitecture.ARM64EC
            else -> PeArchitecture.UNKNOWN
        }

        val diagnostic = if (architecture == PeArchitecture.UNKNOWN) {
            "Valid PE signature; unrecognised COFF machine 0x" + machine.toString(16)
        } else {
            null
        }

        return PeInspection(
            architecture = architecture,
            isValidPe = true,
            diagnostic = diagnostic
        )
    }

    private fun readUInt16LittleEndian(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt32LittleEndian(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}

enum class PeArchitecture(val displayName: String) {
    X86("x86"),
    X86_64("x86-64"),
    ARM32("ARM32"),
    ARM64("ARM64"),
    ARM64EC("ARM64EC"),
    UNKNOWN("архитектура не определена")
}

data class PeInspection(
    val architecture: PeArchitecture,
    val isValidPe: Boolean,
    val diagnostic: String?
)
