package dev.ramenwine.runtime

import android.os.Process as AndroidProcess
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks only descendants of one supervised PRoot PID.
 *
 * PRoot's --kill-on-exit remains the primary cleanup mechanism. This tracker
 * remembers descendants while PRoot is alive and terminates any survivors
 * after the parent exits. PID start-time checks prevent signaling a different
 * process if Android reuses a PID after a child has already exited.
 */
internal class ProcessTreeTerminator(
    private val rootPid: Int,
    private val procDirectory: File = File("/proc"),
    private val sendSignal: (Int, Int) -> Unit = { pid, signal ->
        AndroidProcess.sendSignal(pid, signal)
    },
    private val termSettleMillis: Long = TERM_SETTLE_MILLIS,
    private val killSettleMillis: Long = KILL_SETTLE_MILLIS
) {
    private val observedStartTimes = ConcurrentHashMap<Int, Long>()

    init {
        require(rootPid > 0) { "A valid PRoot PID is required." }
    }

    /** Call periodically while the root PRoot process is alive. */
    fun captureDescendants() {
        // Process visibility can vary by Android build and transient /proc
        // entries can disappear while scanning. A failed snapshot must not
        // crash the process watcher or prevent its normal exit callback.
        val directories = runCatching { procDirectory.listFiles() }.getOrNull() ?: return
        val entries = directories.asSequence()
            .mapNotNull { directory ->
                val pid = directory.name.toIntOrNull() ?: return@mapNotNull null
                if (pid <= 0) return@mapNotNull null
                readProcStat(File(directory, "stat"))
            }
            .associateBy { it.pid }

        val childrenByParent = entries.values.groupBy { it.parentPid }
        val visited = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        pending.addLast(rootPid)

        while (pending.isNotEmpty()) {
            val parentPid = pending.removeFirst()
            for (child in childrenByParent[parentPid].orEmpty()) {
                if (!visited.add(child.pid)) continue
                if (child.state != ZOMBIE_STATE && child.state != DEAD_STATE) {
                    observedStartTimes[child.pid] = child.startTimeTicks
                }
                pending.addLast(child.pid)
            }
        }
    }

    /**
     * Stops remembered descendants that are still the same live processes.
     * The caller runs this on an IO thread after PRoot exits.
     */
    fun terminateRemaining() {
        captureDescendants()
        val initial = currentSurvivors()
        initial.forEach { pid ->
            runCatching { sendSignal(pid, SIGTERM) }
        }

        sleepUninterruptibly(termSettleMillis)

        currentSurvivors().forEach { pid ->
            runCatching { sendSignal(pid, SIGKILL) }
        }
        sleepUninterruptibly(killSettleMillis)
    }

    internal fun trackedPids(): Set<Int> = observedStartTimes.keys.toSet()

    private fun currentSurvivors(): List<Int> {
        return observedStartTimes.mapNotNull { (pid, expectedStartTime) ->
            val current = readProcStat(File(procDirectory, "$pid/stat"))
            if (
                current != null &&
                current.startTimeTicks == expectedStartTime &&
                current.state != ZOMBIE_STATE &&
                current.state != DEAD_STATE
            ) {
                pid
            } else {
                null
            }
        }
    }

    private fun readProcStat(file: File): ProcStat? = runCatching {
        parseStat(file.readText(Charsets.US_ASCII))
    }.getOrNull()

    private fun sleepUninterruptibly(durationMillis: Long) {
        if (durationMillis <= 0L) return
        val deadline = System.nanoTime() + durationMillis * 1_000_000L
        var interrupted = false
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) break
            try {
                Thread.sleep(
                    remaining / 1_000_000L,
                    (remaining % 1_000_000L).toInt()
                )
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    internal data class ProcStat(
        val pid: Int,
        val state: Char,
        val parentPid: Int,
        val startTimeTicks: Long
    )

    internal companion object {
        private const val SIGTERM = 15
        private const val SIGKILL = 9
        private const val ZOMBIE_STATE = 'Z'
        private const val DEAD_STATE = 'X'
        private const val TERM_SETTLE_MILLIS = 150L
        private const val KILL_SETTLE_MILLIS = 100L

        /** Parses /proc/<pid>/stat without splitting the parenthesized comm field. */
        internal fun parseStat(line: String): ProcStat? {
            val open = line.indexOf('(')
            val close = line.lastIndexOf(')')
            if (open <= 0 || close <= open) return null

            val pid = line.substring(0, open).trim().toIntOrNull() ?: return null
            val fields = line.substring(close + 1).trim()
                .split(Regex("\\s+"))
            // The tail starts at field 3 (state); ppid is field 4 and starttime is field 22.
            if (fields.size <= 19) return null
            val state = fields[0].singleOrNull() ?: return null
            val parentPid = fields[1].toIntOrNull() ?: return null
            val startTimeTicks = fields[19].toLongOrNull() ?: return null
            return ProcStat(pid, state, parentPid, startTimeTicks)
        }
    }
}
