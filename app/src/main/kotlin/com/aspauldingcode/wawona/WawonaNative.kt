package com.aspauldingcode.wawona

import android.view.Surface

/**
 * Minimal JNI surface used by RamenWine's Wawona adapter.
 *
 * The package/class and native method names intentionally match the exported
 * JNI symbols in the official Wawona v26.8.12 Android release. Keep this wrapper
 * small; do not copy the upstream application UI into RamenWine.
 */
object WawonaNative {
    @Volatile
    private var librariesLoaded = false

    @Synchronized
    fun loadLibraries() {
        if (librariesLoaded) return

        // Match the upstream load order: ANGLE before the Wawona JNI library.
        System.loadLibrary("EGL_angle")
        System.loadLibrary("GLESv2_angle")
        System.loadLibrary("wawona")
        librariesLoaded = true
    }

    external fun nativePrepareShellEnvironment(filesDir: String)
    external fun nativeInit(cacheDir: String)
    external fun nativeIsCompositorReady(): Boolean
    external fun nativeSetSurface(surface: Surface)
    external fun nativeDestroySurface()
    external fun nativeResizeSurface(width: Int, height: Int)
    external fun nativeSyncOutputSize(width: Int, height: Int)
    external fun nativeSetDisplayDensity(density: Float)
    external fun nativeShutdown()

    // Android finger taps are translated to mouse input because standard
    // Windows visual novels consume pointer clicks rather than Wayland touch.
    external fun nativePointerEnter(x: Double, y: Double, timestampMs: Int)
    external fun nativePointerMotion(x: Double, y: Double, timestampMs: Int)
    external fun nativePointerButton(buttonCode: Int, state: Int, timestampMs: Int)
    external fun nativePointerLeave(timestampMs: Int)
    external fun nativeKeyEvent(keyCode: Int, state: Int, timestampMs: Int)
}
