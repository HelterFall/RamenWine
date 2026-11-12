package dev.ramenwine.display

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.aspauldingcode.wawona.WawonaNative
import dev.ramenwine.runtime.WaylandDisplayEndpoint
import kotlinx.coroutines.delay
import java.io.File

class DisplayBackendUnavailableException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * In-process Wawona compositor adapter.
 *
 * Expensive APK hashing and support-asset extraction run in prepare() on an IO
 * dispatcher. start() is intentionally limited to JNI and Android Surface
 * lifecycle work on the main thread.
 */
class WawonaDisplayBackend(context: Context) : DisplayBackend {
    private val appContext = context.applicationContext

    override val id: String = "wawona"

    override fun availability(): DisplayBackendAvailability {
        val manifest = runCatching {
            WawonaPackageManifestReader.read(appContext)
        }.getOrNull()
        val missing = if (manifest == null) {
            WawonaLibraryInventory.requiredFileNames + WawonaPackageManifestReader.ASSET_PATH
        } else {
            WawonaLibraryInventory.requiredFileNames.filterNot {
                it in manifest.nativeLibraries
            }
        }
        return if (missing.isEmpty() && manifest != null) {
            DisplayBackendAvailability(
                available = true,
                detail = "Pinned Wawona package manifest is present; APK hashes and support assets are verified during preparation."
            )
        } else {
            DisplayBackendAvailability(
                available = false,
                detail = "Pinned Wawona native bundle is incomplete or not packaged in this APK.",
                missingLibraries = missing
            )
        }
    }

    @Volatile
    private var preparedManifest: WawonaPackageManifest? = null

    override fun prepare() {
        val manifest = try {
            WawonaPackageManifestReader.read(appContext)
        } catch (error: Exception) {
            throw DisplayBackendUnavailableException(
                "Wawona package manifest is missing or invalid: " +
                    (error.message ?: error.javaClass.simpleName),
                error
            )
        }

        val apkFile = File(appContext.applicationInfo.sourceDir)
        val missing = WawonaLibraryInventory.missingFromApk(
            apkFile,
            manifest.nativeLibraries
        )
        if (missing.isNotEmpty()) {
            throw DisplayBackendUnavailableException(
                "Wawona APK native payload failed verification: " + missing.joinToString()
            )
        }

        try {
            WawonaSupportAssetInstaller.install(appContext, manifest)
        } catch (error: Exception) {
            throw DisplayBackendUnavailableException(
                "Wawona support assets could not be prepared: " +
                    (error.message ?: error.javaClass.simpleName),
                error
            )
        }
        preparedManifest = manifest
    }

    override fun start(): DisplaySession {
        checkMainThread()

        val manifest = preparedManifest ?: throw DisplayBackendUnavailableException(
            "Wawona package must be verified and prepared before compositor startup."
        )

        if (!WawonaSessionGate.acquire()) {
            throw DisplayBackendUnavailableException(
                "A Wawona display session is already active in this process."
            )
        }

        try {
            val cacheDirectory = appContext.cacheDir
            val runtimeDirectory = File(cacheDirectory, RUNTIME_DIRECTORY_NAME)
            val socketPath = File(runtimeDirectory, WAYLAND_SOCKET_NAME).absolutePath
            val runtimePathBytes = runtimeDirectory.absolutePath.toByteArray(Charsets.UTF_8).size

            // Upstream reserves 32 bytes beyond XDG_RUNTIME_DIR for its longest
            // AF_UNIX socket suffix. Reject long paths rather than letting the
            // native code silently fall back outside the app-private sandbox.
            if (runtimePathBytes + SOCKET_SUFFIX_BUDGET >= MAX_UNIX_SOCKET_PATH_BYTES) {
                throw DisplayBackendUnavailableException(
                    "The app-private Wayland runtime path is too long for Android AF_UNIX."
                )
            }

            if (runtimeDirectory.exists()) {
                if (!runtimeDirectory.isDirectory) {
                    throw DisplayBackendUnavailableException(
                        "The app-private Wawona runtime path is not a directory."
                    )
                }
                // XDG_RUNTIME_DIR must be private. Let nativeInit create a new
                // directory with mode 0700; tighten an existing one explicitly.
                Os.chmod(runtimeDirectory.absolutePath, 448)
            }

            WawonaNative.loadLibraries()
            check(!WawonaNative.nativeIsWestonSimpleSHMRunning()) {
                "A previous Weston SHM test thread has not exited; reopen RamenWine before starting another display session."
            }
            WawonaNative.nativePrepareShellEnvironment(appContext.filesDir.absolutePath)
            WawonaNative.nativeInit(cacheDirectory.absolutePath)
            WawonaNative.nativeSetDisplayDensity(appContext.resources.displayMetrics.density)

            if (!WawonaNative.nativeIsCompositorReady()) {
                throw DisplayBackendUnavailableException(
                    "Wawona native core did not report a running compositor."
                )
            }

            if (!isUnixSocket(socketPath)) {
                throw DisplayBackendUnavailableException(
                    "Wawona reported ready but did not publish a Wayland socket at $socketPath."
                )
            }

            return WawonaDisplaySession(
                endpoint = WaylandDisplayEndpoint(
                    hostRuntimeDirectory = runtimeDirectory,
                    socketName = WAYLAND_SOCKET_NAME
                ),
                cacheDirectory = cacheDirectory
            )
        } catch (error: Exception) {
            runCatching { WawonaNative.nativeDestroySurface() }
            runCatching { WawonaNative.nativeShutdown() }
            WawonaSessionGate.release()
            if (error is DisplayBackendUnavailableException) throw error
            throw DisplayBackendUnavailableException(
                "Wawona could not initialize: " + (error.message ?: error.javaClass.simpleName),
                error
            )
        } catch (error: LinkageError) {
            runCatching { WawonaNative.nativeDestroySurface() }
            runCatching { WawonaNative.nativeShutdown() }
            WawonaSessionGate.release()
            throw DisplayBackendUnavailableException(
                "Wawona native libraries failed to load or resolve JNI symbols.",
                error
            )
        }
    }

    private fun isUnixSocket(path: String): Boolean = runCatching {
        OsConstants.S_ISSOCK(Os.lstat(path).st_mode)
    }.getOrDefault(false)

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Wawona lifecycle must be controlled from the Android main thread."
        }
    }

    private class WawonaDisplaySession(
        override val endpoint: WaylandDisplayEndpoint,
        private val cacheDirectory: File
    ) : DisplaySession {
        private var attachedSurface: Surface? = null
        private var attachedWidth = 0
        private var attachedHeight = 0
        private var attachedDensity = 1f
        private var stopped = false
        private val touchMouseInput = TouchToPointerBridge(
            object : WaylandPointerSink {
                override fun enter(x: Double, y: Double, timestampMs: Int) {
                    WawonaNative.nativePointerEnter(x, y, timestampMs)
                }

                override fun motion(x: Double, y: Double, timestampMs: Int) {
                    WawonaNative.nativePointerMotion(x, y, timestampMs)
                }

                override fun button(buttonCode: Int, state: Int, timestampMs: Int) {
                    WawonaNative.nativePointerButton(buttonCode, state, timestampMs)
                }

                override fun leave(timestampMs: Int) {
                    WawonaNative.nativePointerLeave(timestampMs)
                }
            }
        )

        override val isSurfaceAttached: Boolean
            get() = attachedSurface != null && !stopped

        override val isReadyForClients: Boolean
            get() = isSurfaceAttached && !stopped

        override fun attachSurface(
            surface: Surface,
            width: Int,
            height: Int,
            density: Float
        ) {
            checkMainThread()
            check(!stopped) { "Wawona display session is stopped." }
            require(surface.isValid) { "Android Surface is not valid." }
            require(width > 0 && height > 0) { "Surface dimensions must be positive." }
            require(density > 0f) { "Display density must be positive." }

            if (attachedSurface === surface) {
                attachedDensity = density
                resizeSurface(width, height)
                return
            }

            if (attachedSurface != null) {
                touchMouseInput.cancel(currentTimestamp())
                // Preserve connected Wayland clients across Android Surface recreation.
                // Destroying the presenter does not stop the compositor or falsify
                // the upstream SHM client's actual running state.
                WawonaNative.nativeDestroySurface()
            }
            attachedDensity = density
            WawonaNative.nativeSetDisplayDensity(density)
            WawonaNative.nativeSetSurface(surface)
            attachedSurface = surface
            resizeSurface(width, height)
        }

        override fun resizeSurface(width: Int, height: Int) {
            checkMainThread()
            if (stopped || attachedSurface == null || width <= 0 || height <= 0) return
            attachedWidth = width
            attachedHeight = height
            WawonaNative.nativeResizeSurface(width, height)
            WawonaNative.nativeSyncOutputSize(width, height)
        }

        override fun detachSurface(surface: Surface) {
            checkMainThread()
            if (stopped || attachedSurface !== surface) return
            touchMouseInput.cancel(currentTimestamp())
            // Keep the Wayland compositor alive across a temporary Android
            // Surface detach. A later Surface can be attached to the same session.
            WawonaNative.nativeDestroySurface()
            attachedSurface = null
            attachedWidth = 0
            attachedHeight = 0
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            checkMainThread()
            if (stopped || attachedSurface == null) return false

            val timestamp = (event.eventTime % Int.MAX_VALUE).toInt()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val index = event.actionIndex
                    touchMouseInput.down(
                        event.getPointerId(index),
                        event.getX(index).toDouble(),
                        event.getY(index).toDouble(),
                        timestamp
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    // The bridge tracks the first pressed finger and ignores
                    // additional pointers so one gesture cannot create several
                    // overlapping Windows mouse buttons.
                    for (index in 0 until event.pointerCount) {
                        touchMouseInput.move(
                            event.getPointerId(index),
                            event.getX(index).toDouble(),
                            event.getY(index).toDouble(),
                            timestamp
                        )
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    val index = event.actionIndex
                    touchMouseInput.up(
                        event.getPointerId(index),
                        event.getX(index).toDouble(),
                        event.getY(index).toDouble(),
                        timestamp
                    )
                }
                MotionEvent.ACTION_CANCEL -> touchMouseInput.cancel(timestamp)
                else -> return false
            }
            return true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            checkMainThread()
            if (stopped || attachedSurface == null) return false

            val state = when (event.action) {
                KeyEvent.ACTION_DOWN -> 1
                KeyEvent.ACTION_UP -> 0
                else -> return false
            }
            val timestamp = (event.eventTime % Int.MAX_VALUE).toInt()
            WawonaNative.nativeKeyEvent(event.keyCode, state, timestamp)
            return true
        }

        override fun startSurfaceSmokeClient(): Boolean {
            checkMainThread()
            check(!stopped) { "Wawona display session is stopped." }
            check(attachedSurface?.isValid == true) {
                "Attach the Android Surface before starting the Wayland smoke client."
            }
            if (WawonaNative.nativeIsWestonSimpleSHMRunning()) return true
            return WawonaNative.nativeRunWestonSimpleSHM()
        }

        override fun isSurfaceSmokeClientRunning(): Boolean {
            checkMainThread()
            return !stopped && WawonaNative.nativeIsWestonSimpleSHMRunning()
        }

        override suspend fun resetAfterSmokeClient(): Boolean {
            checkMainThread()
            check(!stopped) { "Wawona display session is stopped." }

            val surface = attachedSurface?.takeIf { it.isValid }
                ?: throw IllegalStateException("Android Surface detached before the SHM reset.")
            val width = attachedWidth
            val height = attachedHeight
            val density = attachedDensity
            check(width > 0 && height > 0 && density > 0f) {
                "Android Surface dimensions or density are invalid during SHM reset."
            }

            touchMouseInput.cancel(currentTimestamp())

            // Avoid the upstream client-stop helper: it clears its running
            // counter when shutdown is requested, before the detached thread exits.
            // Stopping the compositor disconnects the Wayland client; the counter
            // reaches zero only after weston_simple_shm_thread_func actually returns.
            WawonaNative.nativeDestroySurface()
            WawonaNative.nativeShutdown()

            val exitDeadline = SystemClock.elapsedRealtime() + SMOKE_CLIENT_EXIT_TIMEOUT_MS
            while (
                WawonaNative.nativeIsWestonSimpleSHMRunning() &&
                SystemClock.elapsedRealtime() < exitDeadline
            ) {
                delay(SMOKE_CLIENT_EXIT_POLL_MS)
            }
            if (WawonaNative.nativeIsWestonSimpleSHMRunning()) {
                val detail = "weston-simple-shm thread did not return within " +
                    "${SMOKE_CLIENT_EXIT_TIMEOUT_MS} ms after compositor shutdown."
                Log.w(TAG, detail)
                throw IllegalStateException(detail)
            }

            check(surface.isValid) {
                "Android Surface became invalid while Wawona was resetting after the SHM test."
            }
            WawonaNative.nativeInit(cacheDirectory.absolutePath)
            check(WawonaNative.nativeIsCompositorReady()) {
                "Wawona compositor did not become ready after SHM reset."
            }

            val socketPath = File(endpoint.hostRuntimeDirectory, endpoint.socketName).absolutePath
            check(isUnixSocket(socketPath)) {
                "Wawona Wayland socket was not restored after SHM reset: $socketPath"
            }

            WawonaNative.nativeSetDisplayDensity(density)
            WawonaNative.nativeSetSurface(surface)
            resizeSurface(width, height)
            Log.i(TAG, "Wawona compositor restarted and Android Surface reattached after SHM test.")
            return true
        }

        override fun stop() {
            checkMainThread()
            if (stopped) return
            stopped = true
            try {
                // The pinned Wawona release also releases Vulkan resources when
                // no Android Surface was attached.
                touchMouseInput.cancel(currentTimestamp())
                WawonaNative.nativeDestroySurface()
                attachedSurface = null
                WawonaNative.nativeShutdown()
            } finally {
                WawonaSessionGate.release()
            }
        }

        private fun isUnixSocket(path: String): Boolean = runCatching {
            OsConstants.S_ISSOCK(Os.lstat(path).st_mode)
        }.getOrDefault(false)

        private fun currentTimestamp(): Int =
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

        private fun checkMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "Wawona Surface and input events must run on the Android main thread."
            }
        }
    }

    private object WawonaSessionGate {
        private var active = false

        @Synchronized
        fun acquire(): Boolean {
            if (active) return false
            active = true
            return true
        }

        @Synchronized
        fun release() {
            active = false
        }
    }

    private companion object {
        const val TAG = "RamenWineDisplay"
        const val SMOKE_CLIENT_EXIT_TIMEOUT_MS = 5_000L
        const val SMOKE_CLIENT_EXIT_POLL_MS = 50L
        const val RUNTIME_DIRECTORY_NAME = "wawona-runtime"
        const val WAYLAND_SOCKET_NAME = "wayland-0"
        const val MAX_UNIX_SOCKET_PATH_BYTES = 108
        const val SOCKET_SUFFIX_BUDGET = 32
    }
}
