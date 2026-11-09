package dev.ramenwine.runtime

import dev.ramenwine.display.AndroidWaylandSocketValidator
import dev.ramenwine.display.WaylandSocketValidator
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Creates a new per-game Wine prefix before the selected Windows executable is
 * started. A prefix is ready only when both Wine registry files are present
 * and system.reg marks it as a 64-bit (win64) prefix for Wine 11 WoW64.
 * WINEARCH=wow64 selects the loader mode; it does not change the required
 * #arch=win64 prefix marker.
 *
 * The prefix lock prevents two launches from running wineboot into the same
 * directory at the same time.
 */
internal class WinePrefixInitializer(
    private val filesDirectory: File,
    private val processSupervisor: ProotWineProcessSupervisor,
    private val socketValidator: WaylandSocketValidator = AndroidWaylandSocketValidator,
    private val initializationTimeoutMillis: Long = DEFAULT_INITIALIZATION_TIMEOUT_MILLIS
) {
    private val prefixMutex = PROCESS_PREFIX_MUTEX

    init {
        require(initializationTimeoutMillis > 0L) {
            "Wine prefix initialization timeout must be positive."
        }
    }

    suspend fun ensureInitialized(
        request: ProotWineLaunchRequest,
        onOutput: (String) -> Unit
    ) {
        prefixMutex.withLock {
            val prefixDirectory = resolvePrefixDirectory(request.game.id)
            val hasSystemRegistry = File(prefixDirectory, SYSTEM_REGISTRY).isFile
            val hasUserRegistry = File(prefixDirectory, USER_REGISTRY).isFile

            if (hasSystemRegistry && hasUserRegistry) {
                if (!hasExpectedArchitecture(prefixDirectory)) {
                    throw IOException(
                        "The existing Wine prefix is not a 64-bit (win64) prefix. " +
                            "Wine 11 WoW64 cannot use a 32-bit prefix, and the prefix " +
                            "will not be overwritten automatically."
                    )
                }
                return@withLock
            }
            if (hasSystemRegistry || hasUserRegistry) {
                throw IOException(
                    "The Wine prefix is only partially initialized. " +
                        "It will not be used or overwritten automatically."
                )
            }

            val initializationCommand = withContext(Dispatchers.IO) {
                ProotWineCommandBuilder.build(
                    request.copy(operation = ProotWineOperation.INITIALIZE_PREFIX),
                    socketValidator
                )
            }

            val exit = CompletableDeferred<Int>()
            val processSession = processSupervisor.start(
                command = initializationCommand,
                onOutput = { line -> onOutput("[wineboot] " + line) },
                onExit = { exitCode -> exit.complete(exitCode) }
            )

            try {
                val exitCode = withTimeout(initializationTimeoutMillis) {
                    exit.await()
                }
                if (exitCode != 0) {
                    throw IOException(
                        "Wine prefix initialization failed with exit code $exitCode."
                    )
                }
                if (!isInitialized(prefixDirectory)) {
                    throw IOException(
                        "wineboot exited successfully but did not create a complete 64-bit " +
                            "Wine prefix (system.reg, user.reg and #arch=win64)."
                    )
                }
            } catch (error: Throwable) {
                // Do not leave an initialization process running after timeout,
                // cancellation, or a failed postcondition.
                withContext(NonCancellable) {
                    if (processSession.isAlive()) {
                        runCatching { processSession.stop() }
                    }
                }
                throw error
            }
        }
    }

    private fun resolvePrefixDirectory(gameId: String): File {
        if (!GAME_ID_PATTERN.matches(gameId)) {
            throw IOException("Invalid game ID for Wine prefix.")
        }

        val filesRoot = filesDirectory.canonicalFile
        val prefixRoot = File(filesRoot, "prefixes").canonicalFile
        requireContained(filesRoot, prefixRoot, "Wine prefix root")

        val prefixDirectory = File(prefixRoot, gameId).canonicalFile
        requireContained(prefixRoot, prefixDirectory, "Wine prefix directory")
        return prefixDirectory
    }

    private fun requireContained(parent: File, child: File, label: String) {
        val canonicalParent = parent.canonicalFile
        val canonicalChild = child.canonicalFile
        val prefix = canonicalParent.path + File.separator
        if (
            canonicalChild != canonicalParent &&
            !canonicalChild.path.startsWith(prefix)
        ) {
            throw IOException(label + " escapes app-private storage.")
        }
    }

    private fun isInitialized(prefixDirectory: File): Boolean =
        File(prefixDirectory, SYSTEM_REGISTRY).isFile &&
            File(prefixDirectory, USER_REGISTRY).isFile &&
            hasExpectedArchitecture(prefixDirectory)

    private fun hasExpectedArchitecture(prefixDirectory: File): Boolean {
        val systemRegistry = File(prefixDirectory, SYSTEM_REGISTRY)
        if (!systemRegistry.isFile) return false

        return try {
            systemRegistry.useLines { lines ->
                lines.take(REGISTRY_HEADER_LINE_LIMIT)
                    .any { line -> line.trim() == WINE_PREFIX_ARCHITECTURE_MARKER }
            }
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        // A process-wide lock also covers multiple Activity/coordinator instances.
        // Prefix setup is short-lived and rare; serializing all new prefix boots
        // avoids competing wineboot processes mutating the same host directory.
        private val PROCESS_PREFIX_MUTEX = Mutex()
        private const val DEFAULT_INITIALIZATION_TIMEOUT_MILLIS = 5 * 60 * 1000L
        private const val SYSTEM_REGISTRY = "system.reg"
        private const val USER_REGISTRY = "user.reg"
        private const val WINE_PREFIX_ARCHITECTURE_MARKER = "#arch=win64"
        private const val REGISTRY_HEADER_LINE_LIMIT = 16
        private val GAME_ID_PATTERN =
            Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    }
}
