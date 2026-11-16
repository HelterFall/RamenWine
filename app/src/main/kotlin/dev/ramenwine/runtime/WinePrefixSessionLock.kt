package dev.ramenwine.runtime

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An OS-backed, per-game lease covering prefix setup and the entire launched
 * Wine session. The lock lives outside the prefix so it cannot affect prefix
 * recovery decisions. The kernel releases it if Android kills the app process.
 */
internal class WinePrefixSessionLock private constructor(
    private val channel: FileChannel,
    private val fileLock: FileLock
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            if (fileLock.isValid) fileLock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        fun acquire(filesDirectory: File, gameId: String): WinePrefixSessionLock {
            require(gameId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
                "Invalid game ID for Wine prefix lock."
            }
            val lockDirectory = File(filesDirectory, "wine-session-locks")
            if (!lockDirectory.isDirectory && !lockDirectory.mkdirs()) {
                throw IOException("Could not create the Wine session lock directory.")
            }
            val lockFile = File(lockDirectory, "$gameId.lock")
            val channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )
            try {
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    throw IOException(
                        "A Wine session for this game is already active. Stop it before starting another."
                    )
                }
                return WinePrefixSessionLock(channel, lock)
            } catch (error: Exception) {
                runCatching { channel.close() }
                if (error is IOException) throw error
                throw IOException("Could not acquire the Wine prefix session lock.", error)
            }
        }
    }
}
