package dev.ramenwine.runtime

import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProotWineProcessSupervisorTest {
    @Test
    fun setupCommandIsStoppedWhenItExceedsItsTimeout() = runBlocking {
        val sleeper = listOf(File("/usr/bin/sleep"), File("/bin/sleep"))
            .firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("A POSIX sleep executable is required for this process test.", sleeper != null)

        val workingDirectory = Files.createTempDirectory("ramenwine-setup-timeout").toFile()
        try {
            val command = ProotWineCommand(
                prootExecutable = sleeper!!,
                arguments = listOf("30"),
                hostWorkingDirectory = workingDirectory,
                environment = emptyMap(),
                guestEnvironment = emptyMap(),
                unsetEnvironment = emptySet(),
                guestExecutablePath = "/bin/sleep",
                guestWorkingDirectory = workingDirectory.absolutePath,
                guestRuntimeDirectory = "/tmp",
                guestRootfsDirectory = workingDirectory,
                hostPrefixDirectory = workingDirectory,
                runtimeVersion = "test"
            )

            val output = CopyOnWriteArrayList<String>()
            val error = runCatching {
                ProotWineProcessSupervisor(
                    callbackDispatcher = Dispatchers.IO
                ).runToCompletion(
                    command = command,
                    onOutput = { line -> output.add(line) },
                    timeoutMillis = 100L
                )
            }.exceptionOrNull()

            assertTrue(
                "Expected timeout IOException, got ${error?.javaClass?.name}: ${error?.message}",
                error is IOException
            )
            assertTrue(
                "Expected timeout message, got: ${error?.message}",
                error?.message.orEmpty().contains("timed out")
            )
            assertTrue(
                "Missing setup-start diagnostic. Output: ${output.joinToString(" | ")}",
                output.any { it.contains("setup PRoot started") }
            )
            assertTrue(
                "Missing timeout diagnostic. Output: ${output.joinToString(" | ")}",
                output.any { it.contains("setup timeout") }
            )
        } finally {
            workingDirectory.deleteRecursively()
        }
    }
    @Test
    fun manualStopDrainsFinalOutputBeforeExitCallback() = runBlocking {
        val fakeProcess = FinalOutputProcess("wine-final-line")
        val output = CopyOnWriteArrayList<String>()
        val outputAtExit = CopyOnWriteArrayList<String>()
        val exit = CompletableDeferred<Int>()
        val session = ProotWineProcessSession(
            process = fakeProcess,
            onOutput = output::add,
            onExit = { code ->
                outputAtExit.addAll(output)
                exit.complete(code)
            },
            callbackDispatcher = Dispatchers.Unconfined
        )

        val stopCode = session.stop(gracePeriodMillis = 3_000L)
        val callbackCode = withTimeout(TEST_TIMEOUT_MILLIS) { exit.await() }

        assertEquals(0, stopCode)
        assertEquals(0, callbackCode)
        assertEquals(listOf("wine-final-line"), output.toList())
        assertEquals(output.toList(), outputAtExit.toList())
        assertEquals(1, fakeProcess.destroyCalls.get())
        assertFalse(session.isAlive())
    }

    @Test
    fun naturalExitRacingWithManualStopDeliversOneFinalCallback() = runBlocking {
        val fakeProcess = FinalOutputProcess("wine-natural-exit-line")
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

        fakeProcess.exitNaturally()
        val stopCode = session.stop(gracePeriodMillis = 0L)
        val callbackCode = withTimeout(TEST_TIMEOUT_MILLIS) { exit.await() }

        assertEquals(0, stopCode)
        assertEquals(0, callbackCode)
        assertEquals(1, exitCount.get())
        assertEquals(listOf("wine-natural-exit-line"), output.toList())
        assertFalse(session.isAlive())
    }

    @Test
    fun concurrentStopCallsOnlyTerminateOnceAndShareExitCallback() = runBlocking {
        val fakeProcess = FinalOutputProcess("wine-stopping-line")
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

        val stopCodes = coroutineScope {
            val first = async { session.stop(3_000L) }
            val second = async { session.stop(3_000L) }
            listOf(first.await(), second.await())
        }

        assertEquals(listOf(0, 0), stopCodes)
        assertEquals(0, withTimeout(TEST_TIMEOUT_MILLIS) { exit.await() })
        assertEquals(1, exitCount.get())
        assertEquals(1, fakeProcess.destroyCalls.get())
        assertEquals(listOf("wine-stopping-line"), output.toList())
        assertFalse(session.isAlive())
    }

    private class FinalOutputProcess(
        private val finalLine: String
    ) : Process() {
        private val input = PipedInputStream()
        private val writer = PipedOutputStream(input)
        private val exited = CountDownLatch(1)
        private val terminated = AtomicBoolean(false)
        private val outputSink = java.io.ByteArrayOutputStream()

        @Volatile
        private var code = 0

        val destroyCalls = AtomicInteger()

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
            finish()
        }

        override fun destroyForcibly(): Process {
            finish()
            return this
        }

        fun exitNaturally() = finish()

        private fun finish() {
            if (!terminated.compareAndSet(false, true)) return
            try {
                writer.write((finalLine + "\n").toByteArray(Charsets.UTF_8))
                writer.flush()
            } finally {
                writer.close()
                exited.countDown()
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }

}
