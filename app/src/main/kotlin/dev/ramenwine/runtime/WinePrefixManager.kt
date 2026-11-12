package dev.ramenwine.runtime

import java.io.File
import java.io.IOException
import android.util.AtomicFile
import kotlinx.coroutines.delay

internal enum class WinePrefixRecoveryAction {
    SKIP,
    INITIALIZE,
    UPDATE
}

/**
 * Pure recovery policy so interrupted wineboot states can be covered without
 * invoking Wine or touching a real game prefix in a unit test.
 */
internal object WinePrefixRecoveryPolicy {
    fun decide(
        populatedRegistryFiles: Int,
        markerExists: Boolean,
        markerMatches: Boolean,
        prefixHasData: Boolean
    ): WinePrefixRecoveryAction {
        require(populatedRegistryFiles in 0..REQUIRED_REGISTRY_FILE_COUNT) {
            "Registry file count is outside the supported range."
        }

        if (
            populatedRegistryFiles == REQUIRED_REGISTRY_FILE_COUNT &&
            markerExists &&
            markerMatches
        ) {
            return WinePrefixRecoveryAction.SKIP
        }

        // A failed first boot may leave only .update-timestamp or drive_c.
        // Wine's -i can then skip wine.inf when the timestamp matches, even
        // though the prefix registry hives were never flushed. Any state in
        // the prefix therefore means recovery must use -u.
        return if (populatedRegistryFiles > 0 || markerExists || prefixHasData) {
            WinePrefixRecoveryAction.UPDATE
        } else {
            WinePrefixRecoveryAction.INITIALIZE
        }
    }

    private const val REQUIRED_REGISTRY_FILE_COUNT = 3
}

/**
 * Initializes each game's Wine prefix once per installed runtime version.
 * A marker is committed only after wineboot succeeds and registry files exist.
 */
internal class WinePrefixManager(
    private val processSupervisor: ProotWineProcessSupervisor
) {
    suspend fun ensureInitialized(
        gameCommand: ProotWineCommand,
        onOutput: (String) -> Unit
    ): Boolean {
        val prefix = gameCommand.hostPrefixDirectory
        if (!prefix.isDirectory) {
            throw IOException("The per-game Wine prefix directory is missing.")
        }

        val marker = File(prefix, MARKER_NAME)
        val expectedMarker = markerContents(gameCommand)
        val registryFiles = listOf(
            File(prefix, "system.reg"),
            File(prefix, "user.reg"),
            File(prefix, "userdef.reg")
        )
        val presentRegistryCount = registryFiles.count { it.isFile && it.length() > 0L }
        val markerExists = marker.isFile
        val markerMatches = markerExists && runCatching {
            marker.readText(Charsets.UTF_8) == expectedMarker
        }.getOrDefault(false)
        val prefixHasData = prefix.list()?.any { it != MARKER_NAME } == true
        val recoveryAction = WinePrefixRecoveryPolicy.decide(
            populatedRegistryFiles = presentRegistryCount,
            markerExists = markerExists,
            markerMatches = markerMatches,
            prefixHasData = prefixHasData
        )

        if (recoveryAction == WinePrefixRecoveryAction.SKIP) return false

        // An interrupted first-time wineboot may have created only a timestamp
        // or directory tree, without registry hives. Treat any non-empty
        // prefix as partial state and run wineboot -u rather than retrying -i.
        // The recovery path does not delete or replace existing files.
        val updateExistingPrefix = recoveryAction == WinePrefixRecoveryAction.UPDATE
        val winebootCommand = ProotWineCommandBuilder.buildWinebootCommand(
            gameCommand = gameCommand,
            updateExistingPrefix = updateExistingPrefix
        )
        onOutput(
            "RamenWine: wineboot " +
                (if (updateExistingPrefix) "-u" else "-i") +
                " WINEPREFIX=" + gameCommand.guestEnvironment["WINEPREFIX"].orEmpty()
        )
        val exitCode = processSupervisor.runToCompletion(winebootCommand, onOutput)
        if (exitCode != 0) {
            val operation = if (updateExistingPrefix) "update" else "initialization"
            throw IOException("Wine prefix $operation failed with exit code $exitCode.")
        }

        // The guest wrapper waits for wineserver shutdown before PRoot exits.
        // Keep a bounded poll as a final filesystem-visibility safeguard.
        val missingRegistryFiles = awaitRegistryFiles(registryFiles)
        if (missingRegistryFiles.isNotEmpty()) {
            val entries = prefix.list()?.sorted()?.take(24)?.joinToString()
                ?.ifBlank { "<empty>" } ?: "<unreadable>"
            val homePrefix = defaultHomePrefix(prefix)
            val misplacedCount = homePrefix?.let { directory ->
                listOf("system.reg", "user.reg", "userdef.reg").count { name ->
                    val file = File(directory, name)
                    file.isFile && file.length() > 0L
                }
            } ?: 0
            val locationHint = if (misplacedCount > 0) {
                " Wine appears to have written registry files under HOME/.wine instead of WINEPREFIX."
            } else {
                ""
            }
            throw IOException(
                "wineboot exited with code 0, but after " +
                    (REGISTRY_WAIT_TIMEOUT_MILLIS / 1000) +
                    "s these files are still missing in " + prefix.absolutePath + ": " +
                    missingRegistryFiles.joinToString { it.name } +
                    ". Prefix contents: " + entries + "." + locationHint
            )
        }

        onOutput("RamenWine: all three Wine registry files are present.")
        writeMarkerAtomically(marker, expectedMarker)
        return true
    }

    private suspend fun awaitRegistryFiles(registryFiles: List<File>): List<File> {
        val deadline = System.nanoTime() + REGISTRY_WAIT_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val missing = registryFiles.filterNot { it.isFile && it.length() > 0L }
            if (missing.isEmpty()) return emptyList()
            delay(REGISTRY_POLL_INTERVAL_MILLIS)
        }
        return registryFiles.filterNot { it.isFile && it.length() > 0L }
    }

    private fun defaultHomePrefix(prefix: File): File? {
        val filesDirectory = prefix.parentFile?.parentFile ?: return null
        return File(filesDirectory, "wine-home/" + prefix.name + "/.wine")
    }

    private fun markerContents(command: ProotWineCommand): String = buildString {
        append("schema=1\n")
        append("backend=glibc-proot-box64\n")
        append("runtimeVersion=").append(command.runtimeVersion).append('\n')
        append("locale=").append(command.guestEnvironment["LC_ALL"].orEmpty()).append('\n')
    }

    private fun writeMarkerAtomically(marker: File, contents: String) {
        val atomicFile = AtomicFile(marker)
        val output = atomicFile.startWrite()
        try {
            output.write(contents.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw IOException("Could not atomically save the Wine prefix version marker.", error)
        }
    }

    private companion object {
        const val MARKER_NAME = ".ramenwine-prefix"
        const val REGISTRY_WAIT_TIMEOUT_MILLIS = 60_000L
        const val REGISTRY_WAIT_TIMEOUT_NANOS = REGISTRY_WAIT_TIMEOUT_MILLIS * 1_000_000L
        const val REGISTRY_POLL_INTERVAL_MILLIS = 250L
    }
}
