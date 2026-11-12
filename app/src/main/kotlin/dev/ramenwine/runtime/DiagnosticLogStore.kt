package dev.ramenwine.runtime

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import dev.ramenwine.library.ImportedGame
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Keeps the complete output from the latest launch attempt and exports a
 * privacy-conscious text report for remote troubleshooting.
 *
 * The report includes app/device/runtime facts, the filesystem mount options
 * for Wine's files and the full PRoot/Wine stdout+stderr stream. It does not
 * collect other apps' logs, contacts, accounts, or game file contents.
 */
class DiagnosticLogStore(private val context: Context) {
    private val lock = Any()
    private val lines = ArrayList<String>()
    private var game: ImportedGame? = null
    private var sessionStartedAt: Long = System.currentTimeMillis()
    private var truncatedLines = 0

    fun beginSession(selectedGame: ImportedGame?) {
        synchronized(lock) {
            lines.clear()
            truncatedLines = 0
            game = selectedGame
            sessionStartedAt = System.currentTimeMillis()
            lines += "=== RamenWine launch diagnostic ==="
            lines += "Started: ${timestamp(sessionStartedAt)}"
            lines += "Selected game: ${selectedGame?.title ?: "<not selected>"}"
            selectedGame?.let {
                lines += "Game id: ${it.id}"
                lines += "Game EXE: ${it.executableRelativePath}"
                lines += "Game architecture: ${it.architecture}"
                lines += "Game locale: ${it.locale}"
            }
            lines += "----------------------------------------"
        }
    }

    fun record(message: String) {
        val safeMessage = message
            .replace("\u0000", "")
            .take(MAX_LINE_LENGTH)
        synchronized(lock) {
            lines += "${timestamp(System.currentTimeMillis())}  $safeMessage"
            trimIfNeeded()
        }
    }

    fun recordException(stage: String, error: Throwable) {
        val stack = StringWriter().also { writer ->
            error.printStackTrace(PrintWriter(writer))
        }.toString()
        record("ERROR at $stage: ${error.javaClass.name}: ${error.message.orEmpty()}")
        stack.lineSequence().forEach { record("  $it") }
        var cause = error.cause
        var depth = 0
        while (cause != null && depth < 8) {
            record("CAUSE[$depth]: ${cause.javaClass.name}: ${cause.message.orEmpty()}")
            cause = cause.cause
            depth++
        }
    }

    fun buildReport(): String {
        val snapshot = synchronized(lock) {
            lines.toList() to truncatedLines
        }
        return buildString {
            appendLine("RAMENWINE DIAGNOSTICS")
            appendLine("Generated: ${timestamp(System.currentTimeMillis())}")
            appendLine("This file contains app/runtime diagnostics and the latest launch log.")
            appendLine("It does not include game file contents or logs from other apps.")
            appendLine()
            appendDeviceSection()
            appendStorageSection()
            appendRuntimeSection()
            appendLine()
            appendLine("=== LAUNCH OUTPUT ===")
            if (snapshot.second > 0) {
                appendLine("[Older lines omitted to keep the report bounded: ${snapshot.second}]")
            }
            snapshot.first.forEach(::appendLine)
            appendLine()
            appendLogcatSection()
            appendFindings(snapshot.first)
        }
    }

    private fun StringBuilder.appendDeviceSection() {
        appendLine("=== DEVICE / APP ===")
        appendLine("App package: ${context.packageName}")
        val packageVersion = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName ?: "unknown"} (${info.longVersionCode})"
        }.getOrDefault("unavailable")
        appendLine("App version: $packageVersion")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Build fingerprint: ${Build.FINGERPRINT}")
        appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Process ABI: ${runCatching { System.getProperty("os.arch") }.getOrNull() ?: "unknown"}")
        appendLine("App UID: ${Process.myUid()}")
        appendLine("Available processors: ${Runtime.getRuntime().availableProcessors()}")
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull()
        appendLine("Host page size: ${pageSize?.let { "$it bytes" } ?: readKernelPageSize()}")
        appendLine("Kernel: ${System.getProperty("os.version") ?: "unknown"}")
        appendLine()
    }

    private fun StringBuilder.appendStorageSection() {
        appendLine("=== STORAGE / EXECUTION MOUNTS ===")
        val paths = linkedMapOf(
            "filesDir" to context.filesDir,
            "cacheDir" to context.cacheDir,
            "nativeLibraryDir" to File(context.applicationInfo.nativeLibraryDir),
            "runtime backend" to File(context.filesDir, "runtime/current/backends/glibc-proot-box64"),
            "Linux rootfs" to File(context.filesDir, "runtime/current/backends/glibc-proot-box64/rootfs"),
            "Wine distribution" to File(context.filesDir, "runtime/current/backends/glibc-proot-box64/rootfs/opt/ramenwine-wine")
        )
        val selected = synchronized(lock) { game }
        if (selected != null) {
            paths["Wine prefix"] = File(context.filesDir, "prefixes/${selected.id}")
            paths["Imported game"] = File(context.filesDir, selected.relativeDirectory)
            paths["Game EXE"] = File(context.filesDir, "${selected.relativeDirectory}/${selected.executableRelativePath}")
        }

        paths.forEach { (label, file) ->
            appendLine("$label:")
            appendLine("  path: ${runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)}")
            appendLine("  exists=${file.exists()} dir=${file.isDirectory} read=${file.canRead()} write=${file.canWrite()} execute=${file.canExecute()} size=${file.length()}")
            appendLine("  mount: ${findMount(file)}")
            val mount = findMount(file)
            if (file.exists() && mount.contains("noexec")) {
                appendLine("  !!! WARNING: this path is on a noexec mount.")
            }
            if (file.exists() && file.isDirectory) {
                runCatching {
                    val stats = StatFs(file.absolutePath)
                    appendLine("  storage: available=${stats.availableBytes} total=${stats.totalBytes}")
                }
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendRuntimeSection() {
        appendLine("=== RUNTIME INVENTORY ===")
        val inspection = runCatching { RuntimeLayout.inspect(context.filesDir) }.getOrNull()
        if (inspection == null) {
            appendLine("RuntimeLayout.inspect: failed")
        } else {
            appendLine("Runtime state: ${inspection.state}")
            inspection.backends.forEach { backend ->
                appendLine("Backend ${backend.backend.directoryName}: state=${backend.state}, version=${backend.runtimeVersion ?: "unknown"}")
                backend.missingItems.forEach { appendLine("  missing: $it") }
            }
        }

        val backendRoot = File(context.filesDir, "runtime/current/backends/glibc-proot-box64")
        val properties = Properties()
        runCatching {
            File(backendRoot, "runtime.properties").inputStream().use(properties::load)
        }
        appendLine("Runtime version: ${properties.getProperty("runtimeVersion", "unknown")}")
        appendLine("Runtime host ABI: ${properties.getProperty("hostAbi", "unknown")}")
        appendLine("Runtime backend id: ${properties.getProperty("backendId", "unknown")}")
        val candidateFiles = listOf(
            "rootfs/usr/bin/box64",
            "rootfs/usr/bin/wine",
            "rootfs/usr/bin/wineserver",
            "rootfs/usr/lib/wine/x86_64-windows/wineboot.exe",
            "rootfs/opt/ramenwine-wine/lib/wine/x86_64-windows/ntdll.dll",
            "rootfs/opt/ramenwine-wine/x86_64-windows/ntdll.dll"
        )
        candidateFiles.forEach { relative ->
            val file = File(backendRoot, relative)
            appendLine("$relative: exists=${file.exists()} read=${file.canRead()} execute=${file.canExecute()} size=${file.length()}")
        }

        val selected = synchronized(lock) { game }
        if (selected != null) {
            val prefix = File(context.filesDir, "prefixes/${selected.id}")
            appendLine("Prefix contents:")
            val entries = prefix.listFiles()?.sortedBy { it.name }.orEmpty()
            if (entries.isEmpty()) appendLine("  <empty or unreadable>")
            entries.take(80).forEach { file ->
                appendLine("  ${file.name} (dir=${file.isDirectory}, size=${file.length()})")
            }
            listOf("system.reg", "user.reg", "userdef.reg").forEach { name ->
                val file = File(prefix, name)
                appendLine("Prefix registry $name: exists=${file.isFile}, nonempty=${file.length() > 0L}, size=${file.length()}")
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendLogcatSection() {
        appendLine("=== ANDROID LOGCAT (THIS APP UID ONLY) ===")
        appendLine(readOwnLogcat())
        appendLine()
    }

    private fun StringBuilder.appendFindings(logLines: List<String>) {
        val combined = logLines.joinToString("\n")
        appendLine("=== AUTOMATIC HINTS ===")
        if (combined.contains("noexec filesystem", ignoreCase = true) ||
            combined.contains("failed to set", ignoreCase = true) &&
            combined.contains("protection", ignoreCase = true)
        ) {
            appendLine("HIGH: Wine could not assign executable memory protection to a mapped image. Check the mount options above: Wine files and prefix/game files must not be on a noexec mount. The exact affected path and mount are needed before changing storage layout.")
        }
        if (combined.contains("set_mem_area_state", ignoreCase = true) ||
            combined.contains("granularity_mask", ignoreCase = true)
        ) {
            appendLine("HIGH: Wine hit a virtual-memory alignment assertion. Compare Host page size above with the Box64 build revision included in the runtime version.")
        }
        if (combined.contains("libGLX_nvidia.so", ignoreCase = true)) {
            appendLine("NOTE: Box64 reported a missing NVIDIA GLX library. This may be an optional host GPU-wrapper probe; do not assume it caused prefix initialization to fail unless later graphics errors confirm it.")
        }
        if (combined.contains("/sys/devices/system/cpu", ignoreCase = true) ||
            combined.contains("failed to determine number of CPUs", ignoreCase = true)
        ) {
            appendLine("NOTE: CPU topology detection reported a sysfs issue. The runtime launch command should bind /sys/devices/system/cpu into the guest.")
        }
        if (!combined.contains("noexec filesystem", ignoreCase = true) &&
            !combined.contains("set_mem_area_state", ignoreCase = true) &&
            !combined.contains("libGLX_nvidia.so", ignoreCase = true)
        ) {
            appendLine("No known signature matched; use the complete process output and app logcat section.")
        }
        appendLine()
    }

    private fun findMount(file: File): String {
        val target = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val entries = runCatching { File("/proc/self/mounts").readLines() }.getOrDefault(emptyList())
        val best = entries.mapNotNull { line ->
            val fields = line.split(' ')
            if (fields.size < 4) return@mapNotNull null
            val mountPoint = fields[1].replace("\\040", " ")
            val normalized = mountPoint.trimEnd('/').ifBlank { "/" }
            if (target == normalized || target.startsWith(if (normalized == "/") "/" else "$normalized/")) {
                Triple(normalized.length, mountPoint, "${fields[2]} options=${fields[3]} source=${fields[0]}")
            } else null
        }.maxByOrNull { it.first }
        return best?.let { "${it.second} ${it.third}" } ?: "not found in /proc/self/mounts"
    }

    private fun readKernelPageSize(): String {
        val pageLine = runCatching {
            File("/proc/self/smaps").useLines { lines ->
                lines.firstOrNull { it.startsWith("KernelPageSize:") }
            }
        }.getOrNull()
        return pageLine ?: "unavailable"
    }

    private fun readOwnLogcat(): String {
        val process = try {
            ProcessBuilder(
                "logcat", "-d", "-v", "threadtime", "--uid",
                Process.myUid().toString(), "-t", LOGCAT_LINE_LIMIT.toString()
            ).redirectErrorStream(true).start()
        } catch (error: Exception) {
            return "logcat unavailable: ${error.javaClass.simpleName}: ${error.message}"
        }

        return try {
            val output = StringBuilder()
            process.inputStream.bufferedReader().useLines { sequence ->
                sequence.forEach { line ->
                    if (output.length < MAX_LOGCAT_CHARACTERS) {
                        output.appendLine(line)
                    }
                }
            }
            if (!process.waitFor(LOGCAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                output.appendLine("[logcat collection timed out]")
            }
            output.toString()
        } catch (error: Exception) {
            process.destroyForcibly()
            "logcat collection failed: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun trimIfNeeded() {
        var total = lines.sumOf { it.length + 1 }
        while (lines.size > MAX_LINES || total > MAX_LOG_CHARACTERS) {
            if (lines.isEmpty()) break
            val removed = lines.removeAt(0)
            total -= removed.length + 1
            truncatedLines++
        }
    }

    private fun timestamp(timeMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
            .format(Date(timeMillis))

    private companion object {
        const val MAX_LINE_LENGTH = 8_000
        const val MAX_LINES = 20_000
        const val MAX_LOG_CHARACTERS = 2_500_000
        const val LOGCAT_LINE_LIMIT = 2_000
        const val MAX_LOGCAT_CHARACTERS = 350_000
        const val LOGCAT_TIMEOUT_SECONDS = 5L
    }
}
