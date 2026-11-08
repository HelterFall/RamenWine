package dev.ramenwine.runtime

/**
 * Small JNI boundary for native host integration. This is deliberately not a
 * Wine launcher yet; the Linux rootfs + PRoot runtime is a separate milestone.
 */
object NativeRuntime {
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("ramenwine_native")
    }.exceptionOrNull()

    fun diagnostic(): String {
        if (loadError != null) {
            return "Native bridge unavailable: " + loadError.javaClass.simpleName
        }

        return runCatching { nativeBuildInfo() }
            .getOrElse { "Native bridge call failed: " + it.javaClass.simpleName }
    }

    private external fun nativeBuildInfo(): String
}
