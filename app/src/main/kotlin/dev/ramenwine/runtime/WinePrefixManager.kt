package dev.ramenwine.runtime

import java.io.File
import java.io.IOException
import android.util.AtomicFile

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
        val registryReady = presentRegistryCount == registryFiles.size

        if (presentRegistryCount in 1 until registryFiles.size && !marker.isFile) {
            throw IOException(
                "The Wine prefix is partially initialized. It was left untouched to protect game data."
            )
        }

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

        val missingRegistryFiles = registryFiles.filterNot { it.isFile && it.length() > 0L }
        if (missingRegistryFiles.isNotEmpty()) {
            throw IOException(
                "wineboot exited successfully but did not create: " +
                    missingRegistryFiles.joinToString { it.name }
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
    }
}
