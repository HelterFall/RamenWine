package dev.ramenwine.display

import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Surface
import com.aspauldingcode.wawona.WawonaNative
import java.io.File
import java.io.IOException

/**
 * RamenWine adapter for the pinned Wawona Android compositor build.
 *
 * Upstream's Android JNI owns one process-global Rust core and Vulkan renderer.
 * Consequently this backend deliberately permits one active display session
 * per RamenWine app process. It does not create independent compositor cores.
 */
class WawonaDisplayBackend(context: Context) : GameDisplayBackend {
    private val appContext = context.applicationContext
    private val assets = WawonaRuntimeAssets(appContext)

    override val id: String = "wawona-android-vulkan"

    override fun start(
        surface: Surface,
        widthPx: Int,
        heightPx: Int
    ): GameDisplaySession {
        require(surface.isValid) { "The Android game surface is not ready." }
        require(widthPx > 0 && heightPx > 0) { "Game surface dimensions must be positive." }

        val leaseToken = WawonaProcessSessionGuard.acquire()
        val runtimeDirectory: File
        var nativeInitializationAttempted = false

        try {
            runtimeDirectory = WawonaRuntimePaths.prepare(appContext.cacheDir)

            WawonaProcessSessionGuard.withLease(leaseToken) {
                assets.ensureInstalled()

                // Upstream JNI sets WAWONA_FILES_DIR, XDG_RUNTIME_DIR and
                // related process environment variables in its native code.
                WawonaNative.nativePrepareShellEnvironment(appContext.filesDir.canonicalPath)
                // Match upstream MainActivity: dump the Android host keymap
                // before creating the core, then commit it after nativeInit.
                applyHostKeymap()

                nativeInitializationAttempted = true
                WawonaNative.nativeInit(runtimeDirectory.parentFile!!.absolutePath)
                if (!WawonaNative.nativeIsCompositorReady()) {
                    throw IOException("Wawona Wayland compositor did not start.")
                }
                WawonaNative.nativeReloadHostKeymap()

                val socket = File(runtimeDirectory, WAYLAND_SOCKET_NAME)
                WawonaRuntimePaths.verifyLiveSocket(socket)

                WawonaNative.nativeSetDisplayDensity(
                    appContext.resources.displayMetrics.density
                )
                WawonaNative.nativeSetHostSeatMode(0) // Multi-touch seat mode.

                WawonaNative.nativeSetSurface(surface)
                // Mirror WawonaSettings' clean-install defaults so the host
                // stays on Android's system Vulkan WSI + bundled ANGLE.
                WawonaNative.nativeSyncOutputSize(widthPx, heightPx)
                WawonaNative.nativeApplySettings(
                    false, // forceServerSideDecorations
                    true,  // autoRetinaScaling
                    0,     // automatic rendering backend
                    true,  // respectSafeArea
                    false, // renderMacOSPointer
                    false, // swapCmdAsCtrl
                    true,  // universalClipboard
                    true,  // colorSyncSupport
                    true,  // nestedCompositorsSupport
                    false, // useMetal4ForNested
                    true,  // multipleClients
                    true,  // waypipeRSSupport
                    false, // enableTCPListener
                    1234,  // tcpPort
                    "system",
                    "angle",
                    "auto"
                )
                if (!surface.isValid) {
                    throw IOException("Android game surface became invalid during attach.")
                }
            }

            return WawonaGameDisplaySession(
                surface = surface,
                endpoint = WaylandDisplayEndpoint(runtimeDirectory, WAYLAND_SOCKET_NAME),
                inputLeaseToken = leaseToken
            )
        } catch (error: Throwable) {
            runCatching {
                WawonaProcessSessionGuard.withLease(leaseToken) {
                    if (nativeInitializationAttempted) {
                        try {
                            // nativeInit creates the Vulkan instance before the
                            // Android Surface is attached, so failure cleanup
                            // must destroy the renderer state even on early exit.
                            WawonaNative.nativeDestroySurface(surface)
                        } finally {
                            WawonaNative.nativeShutdown()
                        }
                    }
                }
            }
            WawonaProcessSessionGuard.release(leaseToken)
            throw error
        }
    }

    private fun applyHostKeymap() {
        val keymap = try {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        } catch (_: Throwable) {
            KeyCharacterMap.load(KeyCharacterMap.BUILT_IN_KEYBOARD)
        }

        val ids = ArrayList<Int>(80)
        val levels = ArrayList<Int>(80 * 4)
        for (keyCode in 0..84) {
            val none = decodeKeymapValue(keymap.get(keyCode, 0))
            val shift = decodeKeymapValue(keymap.get(keyCode, KeyEvent.META_SHIFT_ON))
            val alt = decodeKeymapValue(keymap.get(keyCode, KeyEvent.META_ALT_ON))
            val shiftAlt = decodeKeymapValue(
                keymap.get(keyCode, KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON)
            )
            if (none == 0 && shift == 0) continue

            ids += keyCode
            levels += none
            levels += shift
            levels += alt
            levels += shiftAlt
        }

        if (ids.isNotEmpty()) {
            WawonaNative.nativeApplyHostKeyLevels(ids.toIntArray(), levels.toIntArray())
        }
    }

    private fun decodeKeymapValue(raw: Int): Int {
        if (raw == 0) return 0
        return if ((raw and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            raw and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            raw
        }
    }

    private companion object {
        const val WAYLAND_SOCKET_NAME = "wayland-0"
    }
}

internal interface WawonaInputSessionLease {
    val inputLeaseToken: Any
}

private class WawonaGameDisplaySession(
    private val surface: Surface,
    override val endpoint: WaylandDisplayEndpoint,
    override val inputLeaseToken: Any
) : GameDisplaySession, WawonaInputSessionLease {
    private var closed = false

    override fun resize(widthPx: Int, heightPx: Int) {
        require(widthPx > 0 && heightPx > 0) { "Game surface dimensions must be positive." }
        WawonaProcessSessionGuard.withLease(inputLeaseToken) {
            check(!closed) { "The Wawona display session is already closed." }
            WawonaNative.nativeResizeSurface(widthPx, heightPx)
            WawonaNative.nativeSyncOutputSize(widthPx, heightPx)
        }
    }

    override fun close() {
        WawonaProcessSessionGuard.closeLease(inputLeaseToken) {
            if (closed) return@closeLease
            closed = true
            try {
                // Upstream explicitly separates surface teardown from core
                // shutdown. Destroy the Vulkan surface first, then stop Rust.
                WawonaNative.nativeDestroySurface(surface)
            } finally {
                WawonaNative.nativeShutdown()
            }
        }
    }
}

/** Serializes all Wawona JNI work and guards its app-process-global state. */
internal object WawonaProcessSessionGuard {
    private var activeLease: Any? = null

    @Synchronized
    fun acquire(): Any {
        check(activeLease == null) {
            "Wawona supports one active compositor/game session per app process."
        }
        return Any().also { activeLease = it }
    }

    @Synchronized
    fun <T> withLease(token: Any, block: () -> T): T {
        check(activeLease === token) { "The Wawona compositor lease is no longer active." }
        return block()
    }

    @Synchronized
    fun runIfActive(token: Any, block: () -> Unit): Boolean {
        if (activeLease !== token) return false
        block()
        return true
    }

    @Synchronized
    fun closeLease(token: Any, block: () -> Unit) {
        if (activeLease !== token) return
        try {
            block()
        } finally {
            activeLease = null
        }
    }

    @Synchronized
    fun release(token: Any) {
        if (activeLease === token) {
            activeLease = null
        }
    }
}

internal object WawonaRuntimePaths {
    private const val RUNTIME_DIRECTORY_NAME = "wawona-runtime"
    private const val SOCKET_NAME = "wayland-0"
    private const val SOCKET_LOCK_NAME = "wayland-0.lock"
    private const val INSTANCE_LOCK_NAME = "instance.lock"
    private const val ANDROID_SUN_PATH_BYTES = 108
    // Upstream JNI reserves 32 bytes for the longest socket suffix.
    private const val UPSTREAM_SOCKET_SUFFIX_RESERVE = 32
    private const val DIRECTORY_MODE = 0x1C0 // 0700
    private const val FILE_TYPE_MASK = 0xF000

    fun resolveRuntimeDirectory(cacheDirectory: File): File {
        val cacheRoot = cacheDirectory.canonicalFile
        if (!cacheRoot.isDirectory) {
            throw IOException("App-private cache directory is unavailable.")
        }
        val runtimeDirectory = File(cacheRoot, RUNTIME_DIRECTORY_NAME).canonicalFile
        if (runtimeDirectory.parentFile != cacheRoot) {
            throw IOException("Wawona runtime directory escapes app-private cache storage.")
        }

        val pathBytes = runtimeDirectory.absolutePath.toByteArray(Charsets.UTF_8).size
        if (pathBytes + UPSTREAM_SOCKET_SUFFIX_RESERVE > ANDROID_SUN_PATH_BYTES) {
            throw IOException(
                "Wawona runtime path is too long for Android AF_UNIX; refusing its " +
                    "unsafe /data/local/tmp fallback."
            )
        }
        return runtimeDirectory
    }

    fun prepare(cacheDirectory: File): File {
        val runtimeDirectory = resolveRuntimeDirectory(cacheDirectory)
        ensurePrivateDirectory(runtimeDirectory)
        // A killed Wawona process can leave Wayland and compositor instance
        // lock files behind. Upstream documents that a stale instance.lock
        // permits a socket to appear but rejects every client connection.
        removeStaleOwnedRegularFile(File(runtimeDirectory, SOCKET_LOCK_NAME))
        removeStaleOwnedRegularFile(File(runtimeDirectory, INSTANCE_LOCK_NAME))
        removeStaleSocket(File(runtimeDirectory, SOCKET_NAME))
        return runtimeDirectory
    }

    fun verifyLiveSocket(socket: File) {
        if (!AndroidWaylandSocketValidator.isUnixDomainSocket(socket)) {
            throw IOException(
                "Wawona did not publish a real Wayland Unix-domain socket: " +
                    socket.absolutePath
            )
        }

        val stat = try {
            Os.lstat(socket.absolutePath)
        } catch (error: ErrnoException) {
            throw IOException("Could not inspect Wawona Wayland socket.", error)
        }
        if (stat.st_uid != Process.myUid()) {
            throw IOException("Wawona Wayland socket is not owned by the app UID.")
        }
        if (socket.absolutePath.toByteArray(Charsets.UTF_8).size >= ANDROID_SUN_PATH_BYTES) {
            throw IOException("Wawona Wayland socket path exceeds Android AF_UNIX limits.")
        }
    }

    private fun ensurePrivateDirectory(directory: File) {
        if (!directory.exists()) {
            try {
                Os.mkdir(directory.absolutePath, DIRECTORY_MODE)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST) {
                    throw IOException("Could not create Wawona runtime directory.", error)
                }
            }
        }

        val stat = try {
            Os.lstat(directory.absolutePath)
        } catch (error: ErrnoException) {
            throw IOException("Could not inspect Wawona runtime directory.", error)
        }
        if ((stat.st_mode and FILE_TYPE_MASK) != OsConstants.S_IFDIR) {
            throw IOException("Wawona runtime path is not a real directory.")
        }
        if (stat.st_uid != Process.myUid()) {
            throw IOException("Wawona runtime directory is not owned by the app UID.")
        }

        val permissions = stat.st_mode and 0x1FF
        if (permissions != DIRECTORY_MODE) {
            try {
                Os.chmod(directory.absolutePath, DIRECTORY_MODE)
            } catch (error: ErrnoException) {
                throw IOException("Could not make Wawona runtime directory private.", error)
            }
        }
    }

    private fun removeStaleOwnedRegularFile(file: File) {
        val stat = try {
            Os.lstat(file.absolutePath)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return
            throw IOException("Could not inspect a previous Wawona lock file.", error)
        }

        if ((stat.st_mode and FILE_TYPE_MASK) != OsConstants.S_IFREG) {
            throw IOException(
                "Refusing to remove a non-regular Wawona lock path: " + file.name
            )
        }
        if (stat.st_uid != Process.myUid()) {
            throw IOException(
                "Refusing to remove a Wawona lock file owned by another UID."
            )
        }
        try {
            Os.remove(file.absolutePath)
        } catch (error: ErrnoException) {
            throw IOException("Could not remove a stale Wawona lock file.", error)
        }
    }

    private fun removeStaleSocket(socket: File) {
        val stat = try {
            Os.lstat(socket.absolutePath)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return
            throw IOException("Could not inspect a previous Wawona socket.", error)
        }

        if ((stat.st_mode and FILE_TYPE_MASK) != OsConstants.S_IFSOCK) {
            throw IOException(
                "Refusing to replace a non-socket file at the Wawona Wayland endpoint."
            )
        }
        if (stat.st_uid != Process.myUid()) {
            throw IOException("Refusing to unlink a Wayland socket owned by another UID.")
        }
        try {
            Os.remove(socket.absolutePath)
        } catch (error: ErrnoException) {
            throw IOException("Could not remove a stale Wawona Wayland socket.", error)
        }
    }
}
