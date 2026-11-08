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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotWineProcessSupervisorTest {
    @Test
    fun drainsMergedOutputBeforeNaturalExitCallback() = runBlocking {
        val fixture = ScriptFixture(
            """
            printf 'stdout-line\\n'
            printf 'stderr-line\\n' >&2
            exit 7
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
        val fixture = ScriptFixture(
            """
            printf 'ready\\n'
            trap 'printf "terminated\\n"; exit 0' TERM
            while :; do
                sleep 1 &
                wait $!
            done
            """.trimIndent()
        )
        try {
            val output = CopyOnWriteArrayList<String>()
            val outputAtExit = AtomicReference<List<String>>(emptyList())
            val ready = CountDownLatch(1)
            val exitCount = AtomicInteger()
            val exit = CompletableDeferred<Int>()
            val session = ProotWineProcessSupervisor(Dispatchers.Unconfined).start(
                command = fixture.command(),
                onOutput = { line ->
                    output += line
                    if (line == "ready") ready.countDown()
                },
                onExit = { code ->
                    outputAtExit.set(output.toList())
                    exitCount.incrementAndGet()
                    exit.complete(code)
                }
            )

            assertTrue("The fixture process did not become ready.", ready.await(
                TEST_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS
            ))

            val stopExitCode = session.stop(gracePeriodMillis = 3_000L)
            val callbackExitCode = withTimeout(TEST_TIMEOUT_MILLIS) { exit.await() }

            assertEquals(0, stopExitCode)
            assertEquals(0, callbackExitCode)
            assertTrue(output.contains("terminated"))
            assertTrue(outputAtExit.get().contains("terminated"))
            assertEquals(1, exitCount.get())
            assertFalse(session.isAlive())
        } finally {
            fixture.close()
        }
    }

    private class ScriptFixture(scriptBody: String) : AutoCloseable {
        private val root = Files.createTempDirectory("ramenwine-process-test").toFile()
        private val executable = File(root, "fake-proot").apply {
            writeText("#!/bin/sh\\n" + scriptBody + "\\n")
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
