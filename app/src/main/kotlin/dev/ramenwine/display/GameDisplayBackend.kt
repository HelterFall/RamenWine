package dev.ramenwine.display

import android.view.Surface

/**
 * Boundary between the Android game surface and a compositor implementation.
 *
 * The backend owns its Wayland server/socket and must stop compositor threads
 * and unlink its socket when the returned session is closed. The caller owns
 * the Android Surface and controls its lifecycle.
 */
interface GameDisplayBackend {
    val id: String

    fun start(
        surface: Surface,
        widthPx: Int,
        heightPx: Int
    ): GameDisplaySession
}

interface GameDisplaySession : AutoCloseable {
    val endpoint: WaylandDisplayEndpoint

    fun resize(widthPx: Int, heightPx: Int)

    override fun close()
}
