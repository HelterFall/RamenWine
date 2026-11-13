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
    fun startSurfaceSmokeClient(): Boolean = false

    /** True while the backend's SHM smoke client is still connected. */
    fun isSurfaceSmokeClientRunning(): Boolean = false

    /**
     * Tears down and recreates the compositor so the SHM test client disconnects
     * through the Wayland server. Returns only after the client thread has exited
     * and the Android Surface has been attached to the fresh compositor.
     */
    suspend fun resetAfterSmokeClient(): Boolean = false

    fun stop()
}
