package dev.ramenwine.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/**
 * Starts the native ARM64 audio helper directly as the Android application UID.
 *
 * Wine runs inside the glibc PRoot guest and cannot dlopen Android's Bionic
 * libaaudio.so. DirectAudio therefore keeps the AAudio stream in this host
 * helper and connects the Wine driver through a Unix socket and shared-memory
 * rings. No shell, external app or user-installed audio server is involved.
 */
internal class DirectAudioRelaySupervisor(
    private val filesDirectory: File
) {
    fun start(runtimeDirectory: File): DirectAudioRelaySession {
        if (Build.VERSION.SDK_INT < MIN_ANDROID_API) {
            throw IOException(
                "The bundled Android audio backend requires Android 9 (API 28) or later."
            )
        }

        val canonicalRuntimeDirectory = runtimeDirectory.canonicalFile
        if (!canonicalRuntimeDirectory.isDirectory || !canonicalRuntimeDirectory.canWrite()) {
            throw IOException("The Android audio relay directory is not writable.")
        }

        val helper = File(
            filesDirectory,
            "runtime/current/backends/glibc-proot-box64/host/bin/directaudio-relay"
        ).canonicalFile
        val expectedRoot = File(
            filesDirectory,
            "runtime/current/backends/glibc-proot-box64/host/bin"
        ).canonicalFile
        if (!helper.path.startsWith(expectedRoot.path + File.separator) ||
            !helper.isFile || !helper.canExecute()
        ) {
            throw IOException("The bundled DirectAudio Android helper is missing or not executable.")
        }

        val socket = File(canonicalRuntimeDirectory, SOCKET_NAME)
        if (socket.absolutePath.toByteArray(Charsets.UTF_8).size >= MAX_UNIX_SOCKET_PATH_BYTES) {
            throw IOException("The DirectAudio socket path is too long for Android AF_UNIX.")
        }
        if (socket.exists() && !socket.delete()) {
            throw IOException("A stale DirectAudio socket could not be removed.")
        }

        val builder = ProcessBuilder(
            helper.absolutePath,
            "--socket",
            socket.absolutePath
        ).directory(canonicalRuntimeDirectory)
            .redirectOutput(File("/dev/null"))
            .redirectError(File("/dev/null"))

        // Do not allow an unrelated native loader configuration from the app
        // process to change which Android libraries the helper loads.
        builder.environment().apply {
            remove("LD_LIBRARY_PATH")
            remove("LD_PRELOAD")
            remove("LD_AUDIT")
        }

        val process = try {
            builder.start()
        } catch (error: Exception) {
            throw IOException("Could not start the Android audio relay.", error)
        }

        // The Wine client makes a one-shot relay connection. Wait for the
        // listening socket before allowing the launch sequence to continue.
        val deadline = System.nanoTime() + SOCKET_READY_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                socket.delete()
                throw IOException(
                    "The DirectAudio helper exited during startup. " +
                        "See Android logcat tag DA-Relay for its diagnostic."
                )
            }
            if (isUnixSocket(socket) && canConnectToRelay(socket)) {
                return DirectAudioRelaySession(process, socket)
            }
            try {
                Thread.sleep(SOCKET_POLL_INTERVAL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroyForcibly()
                socket.delete()
                throw IOException("DirectAudio startup was interrupted.", interrupted)
            }
        }

        process.destroyForcibly()
        socket.delete()
        throw IOException(
            "The DirectAudio helper did not accept a local connection on its listening socket. " +
                "See Android logcat tag DA-Relay for its diagnostic."
        )
    }

    private fun isUnixSocket(socket: File): Boolean = runCatching {
        OsConstants.S_ISSOCK(Os.lstat(socket.absolutePath).st_mode)
    }.getOrDefault(false)

    /**
     * DirectAudio's own mmdevapi test_connect performs this same short AF_UNIX
     * probe: connect and close without sending a hello. The relay explicitly
     * treats it as a readiness check, not a game audio stream.
     */
    private fun canConnectToRelay(socket: File): Boolean {
        val probe = LocalSocket()
        return try {
            probe.connect(
                LocalSocketAddress(
                    socket.absolutePath,
                    LocalSocketAddress.Namespace.FILESYSTEM
                ),
                SOCKET_PROBE_TIMEOUT_MILLIS
            )
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { probe.close() }
        }
    }

    private companion object {
        const val MIN_ANDROID_API = 28
        const val SOCKET_NAME = "directaudio-relay.sock"
        const val MAX_UNIX_SOCKET_PATH_BYTES = 108
        const val SOCKET_READY_TIMEOUT_NANOS = 2_000_000_000L
        const val SOCKET_POLL_INTERVAL_MILLIS = 20L
        const val SOCKET_PROBE_TIMEOUT_MILLIS = 200
    }
}

/** Owns the helper for exactly one Wine game session. */
internal class DirectAudioRelaySession(
    private val process: Process,
    private val socket: File
) {
    val isAlive: Boolean
        get() = process.isAlive

    fun stop() {
        if (process.isAlive) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
        runCatching { socket.delete() }
    }
}
