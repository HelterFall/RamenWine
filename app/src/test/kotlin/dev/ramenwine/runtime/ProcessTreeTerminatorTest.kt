package dev.ramenwine.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTreeTerminatorTest {
    @Test
    fun readsCurrentProcessPidFromProcStatusWithoutAndroidFramework() {
        val proc = Files.createTempDirectory("ramenwine-proc-self-pid").toFile()
        try {
            File(proc, "self/status").apply {
                parentFile.mkdirs()
                writeText("Name:\ttest\nState:\tS (sleeping)\nPid:\t2468\n")
            }

            assertEquals(2468, ProcessTreeTerminator.currentProcessPid(proc))
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun fallsBackToProcStatWhenStatusPidIsUnavailable() {
        val proc = Files.createTempDirectory("ramenwine-proc-self-stat").toFile()
        try {
            File(proc, "self/stat").apply {
                parentFile.mkdirs()
                writeText("1357 (test process) S 1 2 3 4 5")
            }

            assertEquals(1357, ProcessTreeTerminator.currentProcessPid(proc))
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun findsNewDirectChildPidFromProcChildren() {
        val proc = Files.createTempDirectory("ramenwine-proc-child-lookup").toFile()
        try {
            val parentTaskOne = File(proc, "500/task/500").apply { mkdirs() }
            File(parentTaskOne, "children").writeText("600")
            val parentTaskTwo = File(proc, "500/task/501").apply { mkdirs() }
            File(parentTaskTwo, "children").writeText("601 602")

            File(proc, "600/cmdline").apply {
                parentFile.mkdirs()
                writeBytes("/app/runtime/proot\u0000--kill-on-exit\u0000".toByteArray(Charsets.UTF_8))
            }
            File(proc, "601/cmdline").apply {
                parentFile.mkdirs()
                writeBytes("/app/runtime/proot\u0000--kill-on-exit\u0000".toByteArray(Charsets.UTF_8))
            }
            File(proc, "602/cmdline").apply {
                parentFile.mkdirs()
                writeBytes("/app/runtime/other\u0000".toByteArray(Charsets.UTF_8))
            }

            val pid = ProcessTreeTerminator.findNewDirectChildPid(
                parentPid = 500,
                executable = File("/app/runtime/proot"),
                existingPids = setOf(600),
                procDirectory = proc
            )

            assertEquals(601, pid)
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun capturesOnlyTheRootProcessesChildrenAndGrandchildren() {
        val proc = Files.createTempDirectory("ramenwine-proc-tree").toFile()
        try {
            writeStat(proc, pid = 42, comm = "proot", state = 'S', parentPid = 1, startTime = 50L, childPids = listOf(101, 104))
            writeStat(proc, pid = 101, comm = "wine-preloader", state = 'S', parentPid = 42, startTime = 100L, childPids = listOf(102))
            writeStat(proc, pid = 102, comm = "visual novel.exe", state = 'S', parentPid = 101, startTime = 200L)
            writeStat(proc, pid = 103, comm = "unrelated app", state = 'S', parentPid = 7, startTime = 300L)
            writeStat(proc, pid = 104, comm = "zombie", state = 'Z', parentPid = 42, startTime = 400L)

            val signals = mutableListOf<Pair<Int, Int>>()
            val tracker = ProcessTreeTerminator(
                rootPid = 42,
                procDirectory = proc,
                sendSignal = { pid, signal -> signals += pid to signal },
                termSettleMillis = 0L,
                killSettleMillis = 0L
            )

            tracker.captureDescendants()

            assertEquals(setOf(101, 102), tracker.trackedPids())
            tracker.terminateRemaining()

            assertEquals(setOf(101, 102), signals.filter { it.second == 15 }.map { it.first }.toSet())
            assertEquals(setOf(101, 102), signals.filter { it.second == 9 }.map { it.first }.toSet())
            assertTrue(signals.none { it.first == 103 || it.first == 104 })
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun diagnosticSnapshotIncludesOnlyTrackedProcessesAndResourceState() {
        val proc = Files.createTempDirectory("ramenwine-proc-diagnostics").toFile()
        try {
            writeStat(proc, pid = 42, comm = "proot", state = 'S', parentPid = 1, startTime = 50L, childPids = listOf(101))
            writeStat(proc, pid = 101, comm = "wine-preloader", state = 'D', parentPid = 42, startTime = 100L)
            writeStat(proc, pid = 103, comm = "unrelated-app", state = 'S', parentPid = 7, startTime = 300L)
            writeStatus(proc, 42, "proot", "1024 kB", "4")
            writeStatus(proc, 101, "wine-preloader", "4096 kB", "12")
            writeStatus(proc, 103, "unrelated-app", "8192 kB", "2")
            File(proc, "42/wchan").writeText("do_wait")
            File(proc, "101/wchan").writeText("futex_wait_queue")

            val snapshot = ProcessTreeTerminator(42, procDirectory = proc).diagnosticSnapshot()

            assertTrue(snapshot.contains("proot(pid=42,ppid=1,state=S"))
            assertTrue(snapshot.contains("rss=1024 kB,threads=4,wchan=do_wait"))
            assertTrue(snapshot.contains("wine-preloader(pid=101,ppid=42,state=D"))
            assertTrue(snapshot.contains("rss=4096 kB,threads=12,wchan=futex_wait_queue"))
            assertTrue(!snapshot.contains("unrelated-app"))
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun doesNotSignalAReusedPidAfterItsStartTimeChanges() {
        val proc = Files.createTempDirectory("ramenwine-proc-reused-pid").toFile()
        try {
            writeStat(proc, pid = 42, comm = "proot", state = 'S', parentPid = 1, startTime = 50L, childPids = listOf(101))
            writeStat(proc, pid = 101, comm = "old-game", state = 'S', parentPid = 42, startTime = 100L)
            val signals = mutableListOf<Pair<Int, Int>>()
            val tracker = ProcessTreeTerminator(
                rootPid = 42,
                procDirectory = proc,
                sendSignal = { pid, signal -> signals += pid to signal },
                termSettleMillis = 0L,
                killSettleMillis = 0L
            )

            tracker.captureDescendants()
            // Simulate the original process exiting and Android reusing PID 101
            // for an unrelated process after it was reparented.
            writeStat(proc, pid = 101, comm = "different-process", state = 'S', parentPid = 7, startTime = 999L)

            tracker.terminateRemaining()

            assertTrue(signals.isEmpty())
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun doesNotCaptureChildrenOfAReusedRootPid() {
        val proc = Files.createTempDirectory("ramenwine-proc-reused-root-pid").toFile()
        try {
            writeStat(proc, pid = 42, comm = "proot", state = 'S', parentPid = 1, startTime = 50L, childPids = listOf(101))
            writeStat(proc, pid = 101, comm = "original-game", state = 'S', parentPid = 42, startTime = 100L)

            val signals = mutableListOf<Pair<Int, Int>>()
            val tracker = ProcessTreeTerminator(
                rootPid = 42,
                procDirectory = proc,
                sendSignal = { pid, signal -> signals += pid to signal },
                termSettleMillis = 0L,
                killSettleMillis = 0L
            )
            tracker.captureDescendants()

            // The original child is now reparented, while Android reuses the
            // root PID for an unrelated process that has its own child.
            writeStat(proc, pid = 101, comm = "original-game", state = 'S', parentPid = 7, startTime = 100L)
            writeStat(proc, pid = 42, comm = "unrelated-root", state = 'S', parentPid = 1, startTime = 999L, childPids = listOf(103))
            writeStat(proc, pid = 103, comm = "unrelated-child", state = 'S', parentPid = 42, startTime = 200L)

            val snapshot = tracker.diagnosticSnapshot()
            tracker.terminateRemaining()

            assertTrue(!snapshot.contains("unrelated-root"))
            assertTrue(!snapshot.contains("unrelated-child"))
            assertTrue(signals.any { it == (101 to 15) })
            assertTrue(signals.any { it == (101 to 9) })
            assertTrue(signals.none { it.first == 42 || it.first == 103 })
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun parsesStatWhenCommandNameContainsSpacesAndClosingParentheses() {
        val parsed = ProcessTreeTerminator.parseStat(
            statLine(
                pid = 123,
                comm = "game helper ) worker",
                state = 'S',
                parentPid = 42,
                startTime = 987654L
            )
        )

        assertEquals(123, parsed?.pid)
        assertEquals('S', parsed?.state)
        assertEquals(42, parsed?.parentPid)
        assertEquals(987654L, parsed?.startTimeTicks)
    }

    @Test
    fun rejectsMalformedProcStatLines() {
        assertNull(ProcessTreeTerminator.parseStat("not-a-proc-stat"))
        assertNull(ProcessTreeTerminator.parseStat("123 (truncated)"))
    }

    private fun writeStatus(
        proc: File,
        pid: Int,
        name: String,
        rss: String,
        threads: String
    ) {
        File(proc, "$pid/status").apply {
            parentFile.mkdirs()
            writeText(
                "Name:\t$name\nState:\tS (sleeping)\nPid:\t$pid\n" +
                    "VmRSS:\t$rss\nThreads:\t$threads\n",
                Charsets.US_ASCII
            )
        }
    }

    private fun writeStat(
        proc: File,
        pid: Int,
        comm: String,
        state: Char,
        parentPid: Int,
        startTime: Long,
        childPids: List<Int> = emptyList()
    ) {
        val directory = File(proc, pid.toString()).apply { mkdirs() }
        File(directory, "stat").writeText(
            statLine(pid, comm, state, parentPid, startTime),
            Charsets.US_ASCII
        )
        val task = File(directory, "task/$pid").apply { mkdirs() }
        File(task, "children").writeText(childPids.joinToString(" "), Charsets.US_ASCII)
    }

    private fun statLine(
        pid: Int,
        comm: String,
        state: Char,
        parentPid: Int,
        startTime: Long
    ): String {
        val tail = listOf(
            state.toString(),
            parentPid.toString(),
            "1", "1", "0", "-1", "0", "0", "0", "0",
            "0", "0", "0", "0", "0", "20", "0", "1", "0",
            startTime.toString(), "0", "0"
        )
        return "$pid ($comm) " + tail.joinToString(" ")
    }
}
