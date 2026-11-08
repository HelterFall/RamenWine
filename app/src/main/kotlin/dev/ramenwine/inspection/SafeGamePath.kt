package dev.ramenwine.inspection

/**
 * Validates a single path component originating from an Android document
 * provider before it is used inside the private game-import directory.
 *
 * Windows-invalid names are rejected because the imported files are intended
 * to be visible through a Windows-compatible filesystem.
 */
object SafeGamePath {
    private val forbiddenCharacters = setOf('<', '>', ':', '"', '|', '?', '*')

    fun validateSegment(name: String?): String? {
        if (name.isNullOrBlank()) return null
        if (name == "." || name == "..") return null
        if (name.length > MAX_SEGMENT_LENGTH) return null
        if (name.startsWith(' ') || name.endsWith(' ') || name.endsWith('.')) return null

        if (name.any { character ->
                character == '/' ||
                    character == '\\' ||
                    character == '\u0000' ||
                    character.isISOControl() ||
                    character in forbiddenCharacters
            }
        ) {
            return null
        }

        val deviceName = name.substringBefore('.').uppercase()
        if (deviceName in RESERVED_WINDOWS_NAMES) return null

        return name
    }

    fun normalizeRelativePath(path: String?): String? {
        if (path.isNullOrBlank() || path.startsWith('/') || path.startsWith('\\')) {
            return null
        }

        val segments = path.split('/')
        if (segments.isEmpty()) return null
        if (segments.any { validateSegment(it) == null }) return null
        return segments.joinToString("/")
    }

    private const val MAX_SEGMENT_LENGTH = 240
    private val RESERVED_WINDOWS_NAMES = buildSet {
        add("CON")
        add("PRN")
        add("AUX")
        add("NUL")
        for (index in 1..9) {
            add("COM$index")
            add("LPT$index")
        }
    }
}
