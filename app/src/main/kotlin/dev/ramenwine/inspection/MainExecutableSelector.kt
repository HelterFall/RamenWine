package dev.ramenwine.inspection

import java.util.Locale

/**
 * Conservative heuristic for selecting a likely game executable from a folder.
 * It never executes a file. An ambiguous folder returns null so the player can
 * choose the correct file rather than launching a setup/uninstall utility.
 */
object MainExecutableSelector {
    data class Hint(
        val relativePath: String,
        val architecture: PeArchitecture,
        val isValidPe: Boolean
    )

    fun select(candidates: List<Hint>, folderName: String): String? {
        val ranked = candidates.mapNotNull { candidate ->
            score(candidate, folderName)?.let { score ->
                candidate.relativePath to score
            }
        }.sortedWith(
            compareByDescending<Pair<String, Int>> { it.second }
                .thenBy { it.first.lowercase(Locale.ROOT) }
        )

        val best = ranked.firstOrNull() ?: return null
        if (best.second < MIN_CONFIDENT_SCORE) return null
        if (ranked.getOrNull(1)?.second == best.second) return null
        return best.first
    }

    private fun score(candidate: Hint, folderName: String): Int? {
        if (!candidate.isValidPe) return null
        val architectureScore = when (candidate.architecture) {
            PeArchitecture.X86_64 -> 12
            PeArchitecture.X86 -> 7
            else -> return null
        }

        val normalizedPath = candidate.relativePath
            .replace('\\', '/')
            .trim('/')
        val segments = normalizedPath.split('/')
        if (segments.isEmpty() || segments.any { it.isBlank() }) return null

        val lowerSegments = segments.map { it.lowercase(Locale.ROOT) }
        if (lowerSegments.any { segment ->
                EXCLUDED_DIRECTORY_MARKERS.any(segment::contains)
            }
        ) return null

        val fileName = lowerSegments.last()
        if (!fileName.endsWith(".exe")) return null
        val stem = fileName.removeSuffix(".exe")
        if (EXCLUDED_FILE_MARKERS.any(stem::contains)) return null

        val normalizedFolder = normalize(folderName)
        val normalizedStem = normalize(stem)
        val nameScore = when {
            normalizedFolder.length >= 3 && normalizedStem == normalizedFolder -> 90
            stem in WELL_KNOWN_GAME_EXECUTABLES -> 70
            stem.endsWith("win64shipping") || stem.endsWith("win64") -> 58
            stem == "launcher" || stem == "start" -> 42
            stem == "game" -> 68
            else -> 0
        }

        val locationScore = if (segments.size == 1) 20 else 0
        return nameScore + locationScore + architectureScore
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private const val MIN_CONFIDENT_SCORE = 60

    private val WELL_KNOWN_GAME_EXECUTABLES = setOf(
        "rpg_rt",
        "game",
        "game64",
        "nw",
        "onscripter",
        "tyrano",
        "krkr",
        "renpy"
    )

    private val EXCLUDED_FILE_MARKERS = listOf(
        "uninstall",
        "setup",
        "installer",
        "redist",
        "redistributable",
        "vcredist",
        "dxsetup",
        "crashhandler",
        "crashreport",
        "repair",
        "updater"
    )

    private val EXCLUDED_DIRECTORY_MARKERS = listOf(
        "redist",
        "redistributable",
        "directx",
        "vcredist",
        "uninstall"
    )
}
