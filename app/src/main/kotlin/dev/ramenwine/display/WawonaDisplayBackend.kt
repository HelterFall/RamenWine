package dev.ramenwine.display

import android.content.Context
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.aspauldingcode.wawona.WawonaNative
import dev.ramenwine.runtime.WaylandDisplayEndpoint
import java.io.File

class DisplayBackendUnavailableException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * In-process Wawona compositor adapter.
 *
 * The native libraries are not bundled yet. availability() therefore reports
 * the missing package until the pinned arm64-v8a Wawona build is staged into
 * RamenWine's jniLibs. start() fails closed when any prerequisite is absent.
 */
class WawonaDisplayBackend(context: Context) : DisplayBackend {
    private val appContext = context.applicationContext

    override val id: String = "wawona"

    override fun availability(): DisplayBackendAvailability {
        val libraryDirectory = File(appContext.applicationInfo.nativeLibraryDir)
        val missing = WawonaLibraryInventory.missingFiles(libraryDirectory)
        return if (missing.isEmpty()) {
            DisplayBackendAvailability(
                available = true,
                detail = "Wawona native libraries are present; runtime loading is still required."
            )
        } else {
            DisplayBackendAvailability(
                available = false,
                detail = "Wawona native libraries are not packaged in this APK.",
                missingLibraries = missing
            )
        }
    }

    override fun start(): DisplaySession {
        checkMainThread()

        val availability = availability()
        if (!availability.available) {
            throw DisplayBackendUnavailableException(
                availability.detail + " Missing: " + availability.missingLibraries.joinToString()
            )
        }

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

            if (!runtimeDirectory.exists() && !runtimeDirectory.mkdirs()) {
                throw DisplayBackendUnavailableException(
                    "Could not create the app-private Wawona runtime directory."
                )
            }

            WawonaNative.loadLibraries()
            WawonaNative.nativeInit(cacheDirectory.absolutePath)

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
                )
            )
        } catch (error: Exception) {
            runCatching { WawonaNative.nativeShutdown() }
            WawonaSessionGate.release()
            if (error is DisplayBackendUnavailableException) throw error
            throw DisplayBackendUnavailableException(
                "Wawona could not initialize: " + (error.message ?: error.javaClass.simpleName),
                error
            )
        } catch (error: LinkageError) {
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
        override val endpoint: WaylandDisplayEndpoint
    ) : DisplaySession {
        private var attachedSurface: Surface? = null
        private var stopped = false

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
                resizeSurface(width, height)
                return
            }

            attachedSurface?.let(WawonaNative::nativeDestroySurface)
            WawonaNative.nativeSetDisplayDensity(density)
            WawonaNative.nativeSetSurface(surface)
            attachedSurface = surface
            resizeSurface(width, height)
        }

        override fun resizeSurface(width: Int, height: Int) {
            checkMainThread()
            if (stopped || attachedSurface == null || width <= 0 || height <= 0) return
            WawonaNative.nativeResizeSurface(width, height)
            WawonaNative.nativeSyncOutputSize(width, height)
        }

        override fun detachSurface(surface: Surface) {
            checkMainThread()
            if (stopped || attachedSurface !== surface) return
            WawonaNative.nativeDestroySurface(surface)
            attachedSurface = null
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            checkMainThread()
            if (stopped || attachedSurface == null) return false

            val timestamp = (event.eventTime % Int.MAX_VALUE).toInt()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val index = event.actionIndex
                    WawonaNative.nativeTouchDown(
                        event.getPointerId(index),
                        event.getX(index),
                        event.getY(index),
                        timestamp
                    )
                    WawonaNative.nativeTouchFrame()
                }
                MotionEvent.ACTION_MOVE -> {
                    for (index in 0 until event.pointerCount) {
                        WawonaNative.nativeTouchMotion(
                            event.getPointerId(index),
                            event.getX(index),
                            event.getY(index),
                            timestamp
                        )
                    }
                    WawonaNative.nativeTouchFrame()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    val index = event.actionIndex
                    WawonaNative.nativeTouchUp(event.getPointerId(index), timestamp)
                    WawonaNative.nativeTouchFrame()
                }
                MotionEvent.ACTION_CANCEL -> WawonaNative.nativeTouchCancel()
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

        override fun stop() {
            checkMainThread()
            if (stopped) return
            stopped = true
            try {
                attachedSurface?.let(WawonaNative::nativeDestroySurface)
                attachedSurface = null
                WawonaNative.nativeShutdown()
            } finally {
                WawonaSessionGate.release()
            }
        }

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
        const val RUNTIME_DIRECTORY_NAME = "wawona-runtime"
        const val WAYLAND_SOCKET_NAME = "wayland-0"
        const val MAX_UNIX_SOCKET_PATH_BYTES = 108
        const val SOCKET_SUFFIX_BUDGET = 32
    }
}
