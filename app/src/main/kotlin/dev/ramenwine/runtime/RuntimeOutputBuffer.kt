package dev.ramenwine.runtime

/**
 * Bounded in-memory tail of PRoot, Box64 and Wine output.
 *
 * Runtime output is useful when prefix initialization or a game launch fails,
 * but it must not grow without limit during a long-running session.
 */
internal class RuntimeOutputBuffer(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxLineCharacters: Int = DEFAULT_MAX_LINE_CHARACTERS,
    private val maxCharacters: Int = DEFAULT_MAX_CHARACTERS
) {
    private val lines = ArrayDeque<String>()
    private var retainedCharacters = 0

    init {
        require(maxLines > 0) { "The runtime log must retain at least one line." }
        require(maxLineCharacters > 0) { "The runtime log line limit must be positive." }
        require(maxCharacters > 0) { "The runtime log size limit must be positive." }
    }

    @Synchronized
    fun append(outputLine: String) {
        val line = outputLine.take(minOf(maxLineCharacters, maxCharacters))
        if (line.isEmpty()) return

        while (
            lines.isNotEmpty() &&
            (lines.size >= maxLines || retainedCharacters + line.length > maxCharacters)
        ) {
            retainedCharacters -= lines.removeFirst().length
        }

        // A single line may exceed the total limit only if the constructor
        // configured a smaller maxCharacters than maxLineCharacters; take()
        // above prevents that case.
        lines.addLast(line)
        retainedCharacters += line.length
    }

    @Synchronized
    fun snapshot(limit: Int = DEFAULT_SNAPSHOT_LINES): List<String> {
        require(limit >= 0) { "The runtime log snapshot limit cannot be negative." }
        if (limit == 0) return emptyList()
        return lines.toList().takeLast(limit)
    }

    @Synchronized
    fun clear() {
        lines.clear()
        retainedCharacters = 0
    }

    private companion object {
        const val DEFAULT_MAX_LINES = 80
        const val DEFAULT_MAX_LINE_CHARACTERS = 1_024
        const val DEFAULT_MAX_CHARACTERS = 16 * 1_024
        const val DEFAULT_SNAPSHOT_LINES = 12
    }
}
