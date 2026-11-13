package dev.ramenwine.display

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.Os
import android.util.Log
import com.aspauldingcode.wawona.WawonaNative
import java.io.File

/**
 * Runs the bundled Wayland smoke client in its own app process.
 *
 * The compositor stays in the main process. Killing this helper process closes
 * the client's Wayland socket even if the embedded native client is blocked in
 * its event loop; no compositor restart or unsafe pthread cancellation is used.
 */
class WawonaSmokeClientProvider : ContentProvider() {
    @Volatile
    private var stopScheduled = false

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        when (method) {
            METHOD_START -> startClient(extras)
            METHOD_STATUS -> clientStatus()
            METHOD_STOP -> scheduleProcessStop()
            else -> super.call(method, arg, extras)
        }

    private fun startClient(extras: Bundle?): Bundle {
        val result = Bundle().apply {
            putInt(KEY_PID, Process.myPid())
            putBoolean(KEY_STARTED, false)
        }
        return try {
            val app = requireNotNull(context) { "Smoke-client provider has no app context." }
            val runtimeDirectory = extras?.getString(KEY_RUNTIME_DIRECTORY)
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Wayland runtime directory was not supplied.")
            require(File(runtimeDirectory).isDirectory) {
                "Wayland runtime directory does not exist: $runtimeDirectory"
            }

            WawonaNative.loadLibraries()
            WawonaNative.nativePrepareShellEnvironment(app.filesDir.absolutePath)
            // nativeInit sets these variables in the compositor process. The
            // isolated client process must receive the same socket environment.
            Os.setenv("XDG_RUNTIME_DIR", runtimeDirectory, true)
            Os.setenv("WAYLAND_DISPLAY", WAYLAND_DISPLAY, true)

            if (WawonaNative.nativeIsWestonSimpleSHMRunning()) {
                result.putBoolean(KEY_ALREADY_RUNNING, true)
                result.putBoolean(KEY_RUNNING, true)
                Log.w(TAG, "A SHM client was already active in the isolated process.")
                return result
            }

            val started = WawonaNative.nativeRunWestonSimpleSHM()
            result.putBoolean(KEY_STARTED, started)
            result.putBoolean(
                KEY_RUNNING,
                started && WawonaNative.nativeIsWestonSimpleSHMRunning()
            )
            if (!started) {
                result.putString(KEY_ERROR, "Wawona did not start weston-simple-shm.")
            }
            Log.i(TAG, "Isolated weston-simple-shm start requested started=$started")
            result
        } catch (error: Exception) {
            result.putString(
                KEY_ERROR,
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
            Log.e(TAG, "Could not start isolated weston-simple-shm.", error)
            result
        } catch (error: LinkageError) {
            result.putString(
                KEY_ERROR,
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
            Log.e(TAG, "Wawona native client could not be loaded in the isolated process.", error)
            result
        }
    }

    private fun clientStatus(): Bundle {
        val running = runCatching {
            WawonaNative.nativeIsWestonSimpleSHMRunning()
        }.getOrElse { error ->
            Log.e(TAG, "Could not read isolated SHM client state.", error)
            false
        }
        return Bundle().apply {
            putInt(KEY_PID, Process.myPid())
            putBoolean(KEY_RUNNING, running)
        }
    }

    private fun scheduleProcessStop(): Bundle {
        val pid = Process.myPid()
        if (!stopScheduled) {
            stopScheduled = true
            Handler(Looper.getMainLooper()).postDelayed({
                Log.i(TAG, "Terminating isolated SHM client process pid=$pid.")
                Process.killProcess(pid)
            }, PROCESS_STOP_DELAY_MS)
        }
        return Bundle().apply {
            putInt(KEY_PID, pid)
            putBoolean(KEY_STOP_SCHEDULED, true)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY_SUFFIX = ".wawona-smoke-client"
        const val METHOD_START = "start"
        const val METHOD_STATUS = "status"
        const val METHOD_STOP = "stop"
        const val KEY_RUNTIME_DIRECTORY = "runtime_directory"
        const val KEY_PID = "pid"
        const val KEY_STARTED = "started"
        const val KEY_RUNNING = "running"
        const val KEY_ALREADY_RUNNING = "already_running"
        const val KEY_STOP_SCHEDULED = "stop_scheduled"
        const val KEY_ERROR = "error"

        private const val TAG = "RamenWineSmokeWorker"
        private const val WAYLAND_DISPLAY = "wayland-0"
        private const val PROCESS_STOP_DELAY_MS = 200L
    }
}
