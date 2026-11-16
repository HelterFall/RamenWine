package dev.ramenwine.runtime

import android.os.Process as AndroidProcess
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks only descendants of one supervised PRoot PID.
 *
 * PRoot's --kill-on-exit is the first cleanup layer. This tracker
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
        // Read the Linux children lists of this process tree rather than scanning
        // the device-wide /proc table every 250 ms. Include every thread's list:
        // PRoot can create children from a non-leader thread.
        val visited = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        pending.addLast(rootPid)

        while (pending.isNotEmpty()) {
            val parentPid = pending.removeFirst()
            val taskDirectory = File(procDirectory, "$parentPid/task")
            val tasks = runCatching { taskDirectory.listFiles() }.getOrNull() ?: continue

            for (task in tasks) {
                val childrenFile = File(task, "children")
                val childPids = runCatching {
                    childrenFile.readText(Charsets.US_ASCII)
                        .trim()
                        .split(Regex("\\s+"))
                        .mapNotNull(String::toIntOrNull)
                }.getOrDefault(emptyList())

                for (childPid in childPids) {
                    if (childPid <= 0 || !visited.add(childPid)) continue
                    val child = readProcStat(File(procDirectory, "$childPid/stat")) ?: continue
                    // Reject a stale children-list entry or a recycled PID.
                    if (child.parentPid != parentPid) continue
                    if (child.state != ZOMBIE_STATE && child.state != DEAD_STATE) {
                        observedStartTimes[child.pid] = child.startTimeTicks
                    }
                    pending.addLast(child.pid)
                }
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

    /**
     * Bounded snapshot of this PRoot tree. Command-line arguments and unrelated
     * device processes are deliberately excluded.
     */
    internal fun diagnosticSnapshot(): String {
        captureDescendants()
        val pids = (setOf(rootPid) + observedStartTimes.keys).sorted()
        val entries = pids.mapNotNull { pid ->
            val current = readProcStat(File(procDirectory, "$pid/stat")) ?: return@mapNotNull null
            if (pid != rootPid && observedStartTimes[pid] != current.startTimeTicks) {
                return@mapNotNull null
            }
            val statusFields = runCatching {
                File(procDirectory, "$pid/status").readLines(Charsets.US_ASCII)
                    .mapNotNull { line ->
                        val separator = line.indexOf(':')
                        if (separator <= 0) null else line.substring(0, separator) to
                            line.substring(separator + 1).trim()
                    }.toMap()
            }.getOrDefault(emptyMap())
            val name = (statusFields["Name"] ?: current.comm)
                .replace(',', '_')
                .replace(';', '_')
                .take(64)
            val rss = statusFields["VmRSS"]?.replace(Regex("\\s+"), " ") ?: "unavailable"
            val threads = statusFields["Threads"] ?: "unavailable"
            val waitChannel = runCatching {
                File(procDirectory, "$pid/wchan").readText(Charsets.US_ASCII).trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }?.take(64) ?: "unavailable"
            "$name(pid=$pid,ppid=${current.parentPid},state=${current.state}," +
                "rss=$rss,threads=$threads,wchan=$waitChannel)"
        }
        return entries.joinToString(";").take(MAX_DIAGNOSTIC_SNAPSHOT_LENGTH)
            .ifBlank { "<process tree unavailable>" }
    }

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
        val comm: String,
        val state: Char,
        val parentPid: Int,
        val startTimeTicks: Long
    )

    internal companion object {
        /**
         * Reads this process PID from Linux procfs rather than android.os.Process.
         * This keeps process bookkeeping usable in local JVM tests while using
         * the same kernel-provided PID on Android.
         */
        internal fun currentProcessPid(procDirectory: File = File("/proc")): Int? {
            val statusPid = runCatching {
                File(procDirectory, "self/status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("Pid:") }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.toIntOrNull()
                }
            }.getOrNull()
            if (statusPid != null && statusPid > 0) return statusPid

            return runCatching {
                File(procDirectory, "self/stat").useLines { lines ->
                    lines.firstOrNull()
                        ?.substringBefore(' ')
                        ?.toIntOrNull()
                }
            }.getOrNull()?.takeIf { it > 0 }
        }

        /**
         * Returns direct child PIDs for every thread of a process. Android's
         * java.lang.Process API does not expose pid(), so ProcessBuilder callers
         * identify their newly spawned child through this /proc relationship.
         */
        internal fun directChildPids(
            parentPid: Int,
            procDirectory: File = File("/proc")
        ): Set<Int> {
            val taskDirectory = File(procDirectory, "$parentPid/task")
            val tasks = runCatching { taskDirectory.listFiles()?.toList() }.getOrNull()
                ?: return emptySet()
            val result = LinkedHashSet<Int>()
            for (task in tasks) {
                val childPids = runCatching {
                    File(task, "children").readText(Charsets.US_ASCII)
                        .trim()
                        .split(Regex("\\s+"))
                        .mapNotNull(String::toIntOrNull)
                }.getOrDefault(emptyList())
                result.addAll(childPids.filter { it > 0 })
            }
            return result
        }

        /**
         * Waits briefly for Android's /proc children list to publish the newly
         * spawned PRoot child. ProcessBuilder.start() may return before procfs
         * exposes the child, so a single immediate snapshot is racy.
         */
        internal fun awaitNewDirectChildPid(
            parentPid: Int,
            executable: File,
            existingPids: Set<Int>,
            procDirectory: File = File("/proc"),
            timeoutMillis: Long = 750L
        ): Int? {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            do {
                findNewDirectChildPid(parentPid, executable, existingPids, procDirectory)
                    ?.let { return it }
                if (System.nanoTime() >= deadline) break
                try {
                    Thread.sleep(20L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            } while (true)
            return null
        }

        /**
         * Finds a newly spawned direct child with the expected argv[0].
         * Existing children are excluded to avoid attaching a second session
         * to an already-running PRoot process.
         */
        internal fun findNewDirectChildPid(
            parentPid: Int,
            executable: File,
            existingPids: Set<Int>,
            procDirectory: File = File("/proc")
        ): Int? {
            val expectedPaths = linkedSetOf(executable.absolutePath)
            runCatching { executable.canonicalPath }.getOrNull()?.let(expectedPaths::add)

            return directChildPids(parentPid, procDirectory).firstOrNull { pid ->
                if (pid in existingPids) return@firstOrNull false
                val commandLine = runCatching {
                    File(procDirectory, "$pid/cmdline").readBytes()
                }.getOrNull() ?: return@firstOrNull false
                val argumentEnd = commandLine.indexOf(0.toByte()).let { index ->
                    if (index < 0) commandLine.size else index
                }
                if (argumentEnd == 0) return@firstOrNull false
                val executableArgument = String(
                    commandLine,
                    0,
                    argumentEnd,
                    Charsets.UTF_8
                )
                executableArgument in expectedPaths
            }
        }

        private const val MAX_DIAGNOSTIC_SNAPSHOT_LENGTH = 6_000
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
            return ProcStat(
                pid = pid,
                comm = line.substring(open + 1, close),
                state = state,
                parentPid = parentPid,
                startTimeTicks = startTimeTicks
            )
        }
    }
}
