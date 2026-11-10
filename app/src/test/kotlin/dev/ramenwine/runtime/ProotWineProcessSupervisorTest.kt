package dev.ramenwine.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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
                guestRuntimeDirectory = "/tmp",
                guestRootfsDirectory = workingDirectory,
                hostPrefixDirectory = workingDirectory,
                runtimeVersion = "test"
            )

            val error = runCatching {
                ProotWineProcessSupervisor().runToCompletion(
                    command = command,
                    onOutput = {},
                    timeoutMillis = 100L
                )
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("timed out"))
        } finally {
            workingDirectory.deleteRecursively()
        }
    }
}
