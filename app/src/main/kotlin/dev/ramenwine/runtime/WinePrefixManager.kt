package dev.ramenwine.runtime

import java.io.File
import java.io.IOException

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
        val registryReady = File(prefix, "system.reg").isFile &&
            File(prefix, "user.reg").isFile

        if (registryReady && marker.isFile &&
            runCatching {
                marker.readText(Charsets.UTF_8) == expectedMarker
            }.getOrDefault(false)
        ) {
            return false
        }

        val updateExistingPrefix = registryReady || marker.exists()
        val winebootCommand = ProotWineCommandBuilder.buildWinebootCommand(
            gameCommand = gameCommand,
            updateExistingPrefix = updateExistingPrefix
        )
        val exitCode = processSupervisor.runToCompletion(winebootCommand, onOutput)
        if (exitCode != 0) {
            val operation = if (updateExistingPrefix) "update" else "initialization"
            throw IOException("Wine prefix $operation failed with exit code $exitCode.")
        }

        if (!File(prefix, "system.reg").isFile ||
            !File(prefix, "user.reg").isFile
        ) {
            throw IOException(
                "wineboot exited successfully but did not create the prefix registry files."
            )
        }

        writeMarkerAtomically(marker, expectedMarker)
        return true
    }

    private fun markerContents(command: ProotWineCommand): String = buildString {
        append("schema=1\n")
        append("backend=glibc-proot-box64\n")
        append("runtimeVersion=").append(command.runtimeVersion).append('\n')
    }

    private fun writeMarkerAtomically(marker: File, contents: String) {
        val temporary = File(marker.parentFile, MARKER_NAME + ".tmp")
        temporary.delete()
        temporary.writeText(contents, Charsets.UTF_8)
        if (marker.exists() && !marker.delete()) {
            temporary.delete()
            throw IOException("Could not replace the Wine prefix version marker.")
        }
        if (!temporary.renameTo(marker)) {
            temporary.delete()
            throw IOException("Could not activate the Wine prefix version marker.")
        }
    }

    private companion object {
        const val MARKER_NAME = ".ramenwine-prefix"
    }
}
