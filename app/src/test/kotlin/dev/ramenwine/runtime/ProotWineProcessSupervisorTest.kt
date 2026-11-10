package dev.ramenwine.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotWineProcessSupervisorTest {
    @Test
    fun cancellingWhileProcessCreationIsInFlightStopsTheCreatedProcess() = runBlocking {
        val fixture = ScriptFixture("import time\ntime.sleep(30)")
        val enteredLauncher = CountDownLatch(1)
        val releaseLauncher = CountDownLatch(1)
        val fakeProcess = FinalOutputProcess("cancelled-start")
        val supervisor = ProotWineProcessSupervisor(Dispatchers.Unconfined) {
            enteredLauncher.countDown()
            check(releaseLauncher.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            fakeProcess
        }

        try {
            val start = async(Dispatchers.Default) {
                supervisor.start(
                    command = fixture.command(),
                    onOutput = {},
                    onExit = {}
                )
            }

            assertTrue(enteredLauncher.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            start.cancel()
            releaseLauncher.countDown()
            start.join()

            assertTrue(
                "The process created after cancellation must receive a stop request.",
                fakeProcess.destroyObserved.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            )
            assertEquals(1, fakeProcess.destroyCalls.get())
            assertFalse(fakeProcess.isAlive)
        } finally {
            releaseLauncher.countDown()
            fixture.close()
        }
    }

    @Test
    fun drainsMergedOutputBeforeNaturalExitCallback() = runBlocking {
        val fixture = ScriptFixture(
            """
            import sys
            print("stdout-line", flush=True)
            print("stderr-line", file=sys.stderr, flush=True)
            sys.exit(7)
            """.trimIndent()
        )
        try {
            val output = CopyOnWriteArrayList<String>()
            val outputAtExit = AtomicReference<List<String>>(emptyList())
            val exitCount = AtomicInteger()
            val exit = CompletableDeferred<Int>()
            val session = ProotWineProcessSupervisor(Dispatchers.Unconfined).start(
                command = fixture.command(),
                onOutput = output::add,
                onExit = { code ->
                    outputAtExit.set(output.toList())
                    exitCount.incrementAndGet()
                    exit.complete(code)
                }
            )

            val exitCode = withTimeout(TEST_TIMEOUT_MILLIS) { exit.await() }

            assertEquals(7, exitCode)
            assertEquals(listOf("stdout-line", "stderr-line"), output.toList())
            assertEquals(output.toList(), outputAtExit.get())
            assertEquals(1, exitCount.get())
            assertFalse(session.isAlive())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun drainsTerminationOutputBeforeManualStopCallback() = runBlocking {
        val fakeProcess = FinalOutputProcess("terminated")
        val output = CopyOnWriteArrayList<String>()
        val outputAtExit = AtomicReference<List<String>>(emptyList())
        val exitCount = AtomicInteger()
        val exit = CompletableDeferred<Int>()
        val session = ProotWineProcessSession(
            process = fakeProcess,
            onOutput = output::add,
            onExit = { code ->
                outputAtExit.set(output.toList())
                exitCount.incrementAndGet()
                exit.complete(code)
            },
            callbackDispatcher = Dispatchers.Unconfined
        )

        val stopExitCode = session.stop(gracePeriodMillis = 3_000L)
        val callbackExitCode = withTimeout(TEST_TIMEOUT_MILLIS) { exit.await() }

        assertEquals(0, stopExitCode)
        assertEquals(0, callbackExitCode)
        assertEquals(listOf("terminated"), output.toList())
        assertEquals(output.toList(), outputAtExit.get())
        assertEquals(1, exitCount.get())
        assertEquals(1, fakeProcess.destroyCalls.get())
        assertFalse(session.isAlive())
    }

    @Test
    fun concurrentStopCallsShareOneExitCallback() = runBlocking {
        val fakeProcess = FinalOutputProcess("stopping")
        val output = CopyOnWriteArrayList<String>()
        val exitCount = AtomicInteger()
        val exit = CompletableDeferred<Int>()
        val session = ProotWineProcessSession(
            process = fakeProcess,
            onOutput = output::add,
            onExit = { code ->
                exitCount.incrementAndGet()
                exit.complete(code)
            },
            callbackDispatcher = Dispatchers.Unconfined
        )

        val stopResults = coroutineScope {
            val first = async { session.stop(3_000L) }
            val second = async { session.stop(3_000L) }
            listOf(first.await(), second.await())
        }

        assertEquals(listOf(0, 0), stopResults)
        assertEquals(0, withTimeout(TEST_TIMEOUT_MILLIS) { exit.await() })
        assertEquals(1, exitCount.get())
        assertEquals(1, fakeProcess.destroyCalls.get())
        assertEquals(listOf("stopping"), output.toList())
        assertFalse(session.isAlive())
    }

    @Test
    fun escalatesToForcedTerminationAfterGracePeriod() = runBlocking {
        val fakeProcess = FinalOutputProcess(
            finalLine = "force-stopped",
            ignoreGracefulDestroy = true
        )
        val output = CopyOnWriteArrayList<String>()
        val exitCount = AtomicInteger()
        val exit = CompletableDeferred<Int>()
        val session = ProotWineProcessSession(
            process = fakeProcess,
            onOutput = output::add,
            onExit = { code ->
                exitCount.incrementAndGet()
                exit.complete(code)
            },
            callbackDispatcher = Dispatchers.Unconfined
        )

        val exitCode = session.stop(gracePeriodMillis = 25L)
        val callbackExitCode = withTimeout(TEST_TIMEOUT_MILLIS) { exit.await() }

        assertEquals(137, exitCode)
        assertEquals(137, callbackExitCode)
        assertEquals(listOf("force-stopped"), output.toList())
        assertEquals(1, fakeProcess.destroyCalls.get())
        assertEquals(1, fakeProcess.forceKillCalls.get())
        assertEquals(1, exitCount.get())
        assertFalse(session.isAlive())
    }

    private class FinalOutputProcess(
        private val finalLine: String,
        private val ignoreGracefulDestroy: Boolean = false
    ) : Process() {
        private val input = java.io.PipedInputStream()
        private val writer = java.io.PipedOutputStream(input)
        private val exited = CountDownLatch(1)
        private val terminated = java.util.concurrent.atomic.AtomicBoolean(false)
        private val outputSink = java.io.ByteArrayOutputStream()

        @Volatile
        private var code: Int = 0

        val destroyCalls = AtomicInteger()
        val forceKillCalls = AtomicInteger()
        val destroyObserved = CountDownLatch(1)

        override fun getOutputStream(): java.io.OutputStream = outputSink

        override fun getInputStream(): java.io.InputStream = input

        override fun getErrorStream(): java.io.InputStream =
            java.io.ByteArrayInputStream(byteArrayOf())

        override fun waitFor(): Int {
            exited.await()
            return code
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            exited.await(timeout, unit)

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("Process is still running.")
            return code
        }

        override fun isAlive(): Boolean = exited.count > 0L

        override fun destroy() {
            destroyCalls.incrementAndGet()
            if (!ignoreGracefulDestroy) finish(0)
            destroyObserved.countDown()
        }

        override fun destroyForcibly(): Process {
            forceKillCalls.incrementAndGet()
            finish(137)
            return this
        }

        private fun finish(exitCode: Int) {
            if (!terminated.compareAndSet(false, true)) return
            code = exitCode
            try {
                writer.write((finalLine + "\n").toByteArray(Charsets.UTF_8))
                writer.flush()
            } finally {
                writer.close()
                exited.countDown()
            }
        }
    }

    private class ScriptFixture(scriptBody: String) : AutoCloseable {
        private val root = Files.createTempDirectory("ramenwine-process-test").toFile()
        private val executable = File(root, "fake-proot").apply {
            writeText("#!/usr/bin/env python3\n" + scriptBody + "\n")
            check(setExecutable(true)) { "Could not mark the process fixture executable." }
        }

        fun command() = ProotWineCommand(
            prootExecutable = executable,
            arguments = emptyList(),
            hostWorkingDirectory = root,
            environment = emptyMap(),
            unsetEnvironment = emptySet(),
            guestExecutablePath = "/games/test/game.exe",
            guestRuntimeDirectory = "/run/user/10000"
        )

        override fun close() {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
