package dev.ramenwine.display

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import dev.ramenwine.runtime.WaylandDisplayEndpoint

data class DisplayBackendAvailability(
    val available: Boolean,
    val detail: String,
    val missingLibraries: List<String> = emptyList()
)

interface DisplayBackend {
    val id: String

    fun availability(): DisplayBackendAvailability

    /**
     * Performs disk, archive and asset preparation off the Android main thread.
     * This must not call JNI or touch an Android Surface.
     */
    fun prepare() {}

    /**
     * Starts the compositor and returns only after its Wayland socket is ready.
     * Implementations are Android-main-thread lifecycle APIs.
     */
    fun start(): DisplaySession
}

interface DisplaySession {
    val endpoint: WaylandDisplayEndpoint

    /** True after an Android Surface has been handed to the native presenter. */
    val isSurfaceAttached: Boolean

    /** Clients may be launched only after the socket and a host Surface exist. */
    val isReadyForClients: Boolean

    fun attachSurface(surface: Surface, width: Int, height: Int, density: Float)
    fun resizeSurface(width: Int, height: Int)
    fun detachSurface(surface: Surface)
    fun dispatchTouchEvent(event: MotionEvent): Boolean
    fun dispatchKeyEvent(event: KeyEvent): Boolean

    /** Starts the upstream Weston SHM test client, if this backend provides one. */
    suspend fun startSurfaceSmokeClient(): Boolean = false

    /** True while the backend's SHM smoke client is still connected. */
    suspend fun isSurfaceSmokeClientRunning(): Boolean = false

    /**
     * Terminates the isolated SHM client process. The compositor remains running;
     * the caller separately verifies that its Android Surface is clear.
     */
    suspend fun resetAfterSmokeClient(): Boolean = false

    fun stop()
}
