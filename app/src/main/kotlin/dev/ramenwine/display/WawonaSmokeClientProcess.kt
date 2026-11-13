package dev.ramenwine.display

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controls the isolated process that hosts the embedded Wawona SHM client.
 *
 * Calls into ContentProvider are made on Dispatchers.IO. A process exit, rather
 * than an upstream "running" counter, is the authoritative cleanup boundary.
 */
internal class WawonaSmokeClientProcess(context: Context) {
    private val appContext = context.applicationContext
    private val authority = appContext.packageName +
        WawonaSmokeClientProvider.AUTHORITY_SUFFIX

    @Volatile
    private var workerPid: Int? = null

    suspend fun start(runtimeDirectory: File): Boolean = withContext(Dispatchers.IO) {
        val currentPid = workerPid
        if (currentPid != null && isProcessAlive(currentPid)) {
            if (queryRunning(currentPid)) return@withContext true
            check(requestStopAndWait(currentPid)) {
                "A previous isolated Wawona SHM worker could not be terminated."
            }
        }
        workerPid = null

        val startExtras = Bundle().apply {
            putString(
                WawonaSmokeClientProvider.KEY_RUNTIME_DIRECTORY,
                runtimeDirectory.absolutePath
            )
        }
        var response = try {
            call(WawonaSmokeClientProvider.METHOD_START, startExtras)
        } catch (error: Exception) {
            // A Binder failure can happen after the provider has already started
            // the native thread. Ask that process to terminate before returning.
            runCatching {
                call(WawonaSmokeClientProvider.METHOD_STOP)
            }.getOrNull()?.getInt(WawonaSmokeClientProvider.KEY_PID, -1)
                ?.takeIf { it > 0 }
                ?.let { requestStopAndWait(it) }
            throw error
        }
        var pid = response.getInt(WawonaSmokeClientProvider.KEY_PID, -1)

        // Recover a stale helper left by a previous screen/session before
        // starting a new one. Never start two clients against the same socket.
        if (response.getBoolean(WawonaSmokeClientProvider.KEY_ALREADY_RUNNING)) {
            check(pid > 0 && requestStopAndWait(pid)) {
                "A previous isolated Wawona SHM worker could not be terminated."
            }
            response = call(
                WawonaSmokeClientProvider.METHOD_START,
                Bundle().apply {
                    putString(
                        WawonaSmokeClientProvider.KEY_RUNTIME_DIRECTORY,
                        runtimeDirectory.absolutePath
                    )
                }
            )
            pid = response.getInt(WawonaSmokeClientProvider.KEY_PID, -1)
        }

        val started = response.getBoolean(WawonaSmokeClientProvider.KEY_STARTED)
        if (!started || pid <= 0) {
            if (pid > 0) requestStopAndWait(pid)
            val detail = response.getString(WawonaSmokeClientProvider.KEY_ERROR)
                ?: "Wawona did not start the isolated SHM client."
            throw IllegalStateException(detail)
        }

        workerPid = pid
        Log.i(TAG, "Isolated Wawona SHM worker started pid=$pid.")
        true
    }

    suspend fun isRunning(): Boolean = withContext(Dispatchers.IO) {
        val pid = workerPid ?: return@withContext false
        if (!isProcessAlive(pid)) {
            workerPid = null
            return@withContext false
        }
        queryRunning(pid)
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val pid = workerPid ?: return@withContext true
        val stopped = requestStopAndWait(pid)
        if (stopped) {
            workerPid = null
            Log.i(TAG, "Isolated Wawona SHM worker process exited pid=$pid.")
        } else {
            Log.e(TAG, "Isolated Wawona SHM worker did not exit pid=$pid.")
        }
        stopped
    }

    /**
     * Best-effort fallback for a synchronous display-session close. The normal
     * SHM verification path uses suspend stop() and waits for /proc to disappear.
     */
    fun stopAsync() {
        val pid = workerPid ?: return
        Thread({
            if (requestStopAndWaitBlocking(pid, PROCESS_STOP_TIMEOUT_MS)) {
                workerPid = null
            }
        }, "RamenWineSmokeStop").apply {
            isDaemon = true
            start()
        }
    }

    private fun queryRunning(expectedPid: Int): Boolean {
        if (!isProcessAlive(expectedPid)) {
            workerPid = null
            return false
        }
        val response = call(WawonaSmokeClientProvider.METHOD_STATUS)
        val responsePid = response.getInt(WawonaSmokeClientProvider.KEY_PID, -1)
        if (responsePid != expectedPid) {
            workerPid = null
            if (responsePid > 0) requestStopAndWait(responsePid)
            return false
        }
        return response.getBoolean(WawonaSmokeClientProvider.KEY_RUNNING)
    }

    private fun requestStopAndWait(pid: Int): Boolean {
        if (!isProcessAlive(pid)) return true
        val response = runCatching {
            call(WawonaSmokeClientProvider.METHOD_STOP)
        }.getOrNull()
        if (
            response == null ||
            response.getInt(WawonaSmokeClientProvider.KEY_PID, -1) != pid ||
            !response.getBoolean(WawonaSmokeClientProvider.KEY_STOP_SCHEDULED)
        ) {
            return !isProcessAlive(pid)
        }
        return waitForProcessExitBlocking(pid, PROCESS_STOP_TIMEOUT_MS)
    }

    private fun requestStopAndWaitBlocking(pid: Int, timeoutMillis: Long): Boolean {
        if (!isProcessAlive(pid)) return true
        val response = runCatching {
            call(WawonaSmokeClientProvider.METHOD_STOP)
        }.getOrNull()
        if (
            response == null ||
            response.getInt(WawonaSmokeClientProvider.KEY_PID, -1) != pid ||
            !response.getBoolean(WawonaSmokeClientProvider.KEY_STOP_SCHEDULED)
        ) {
            return !isProcessAlive(pid)
        }
        return waitForProcessExitBlocking(pid, timeoutMillis)
    }

    private fun waitForProcessExitBlocking(pid: Int, timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (isProcessAlive(pid) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(PROCESS_EXIT_POLL_MS)
        }
        return !isProcessAlive(pid)
    }

    private fun isProcessAlive(pid: Int): Boolean = File("/proc/$pid").exists()

    private fun call(method: String, extras: Bundle? = null): Bundle =
        appContext.contentResolver.call(authority, method, null, extras)
            ?: throw IllegalStateException("Wawona smoke-client provider returned no response.")

    private companion object {
        const val TAG = "RamenWineDisplay"
        const val PROCESS_STOP_TIMEOUT_MS = 5_000L
        const val PROCESS_EXIT_POLL_MS = 25L
    }
}
