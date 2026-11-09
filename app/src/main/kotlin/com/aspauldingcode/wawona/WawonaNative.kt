package com.aspauldingcode.wawona

import android.view.Surface

/**
 * The small JNI surface RamenWine uses from Wawona's Android build.
 *
 * Keep this class name and package: upstream android_jni.c exports JNI symbols
 * for com.aspauldingcode.wawona.WawonaNative. This deliberately does not import
 * Wawona's application UI, settings model, machine store or SSH shell.
 */
object WawonaNative {
    init {
        // The upstream renderer links against these ANGLE SONAMEs. Loading the
        // original libEGL.so/libGLESv2.so names as well would load duplicates.
        System.loadLibrary("EGL_angle")
        System.loadLibrary("GLESv2_angle")
        System.loadLibrary("wawona")
    }

    external fun nativePrepareShellEnvironment(filesDir: String)
    external fun nativeInit(cacheDir: String)
    external fun nativeIsCompositorReady(): Boolean
    external fun nativeSetSurface(surface: Surface)
    external fun nativeDestroySurface(surface: Surface?)
    external fun nativeResizeSurface(width: Int, height: Int)
    external fun nativeSyncOutputSize(width: Int, height: Int)
    external fun nativeShutdown()
    external fun nativeSetDisplayDensity(density: Float)
    external fun nativeSetHostSeatMode(mode: Int)
    external fun nativeApplyHostKeyLevels(ids: IntArray, levels: IntArray)
    external fun nativeReloadHostKeymap()

    external fun nativeTouchDown(id: Int, x: Float, y: Float, timestampMs: Int)
    external fun nativeTouchUp(id: Int, timestampMs: Int)
    external fun nativeTouchMotion(id: Int, x: Float, y: Float, timestampMs: Int)
    external fun nativeTouchCancel()
    external fun nativeTouchFrame()

    external fun nativeKeyEvent(keycode: Int, state: Int, timestampMs: Int)
    external fun nativePointerMotion(x: Double, y: Double, timestampMs: Int)
    external fun nativePointerButton(buttonCode: Int, state: Int, timestampMs: Int)
    external fun nativePointerEnter(x: Double, y: Double, timestampMs: Int)
    external fun nativePointerLeave(timestampMs: Int)
    external fun nativePointerAxis(axis: Int, value: Float, timestampMs: Int)
    external fun nativeKeyboardFocus(hasFocus: Boolean)
}
