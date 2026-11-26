package dev.ramenwine.runtime

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.system.Os
import android.system.OsConstants
import dev.ramenwine.library.ImportedGame
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
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
    private val persistedLaunchLog = File(context.filesDir, "diagnostics/latest-launch.log")
    private val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RamenWineDiagnostics").apply { isDaemon = true }
    }
    private var game: ImportedGame? = null
    private var sessionStartedAt: Long = System.currentTimeMillis()
    private var sessionStartedElapsedRealtime: Long = 0L
    private var sessionId: String = "not-started"
    private var truncatedLines = 0

    fun beginSession(selectedGame: ImportedGame?) {
        synchronized(lock) {
            lines.clear()
            truncatedLines = 0
            game = selectedGame
            sessionStartedAt = System.currentTimeMillis()
            sessionStartedElapsedRealtime = SystemClock.elapsedRealtime()
            sessionId = UUID.randomUUID().toString()
            lines += "=== RamenWine launch diagnostic ==="
            lines += "Session ID: $sessionId"
            lines += "Started: ${timestamp(sessionStartedAt)}"
            lines += "Selected game: ${selectedGame?.title ?: "<not selected>"}"
            selectedGame?.let {
                lines += "Game id: ${it.id}"
                lines += "Game EXE: ${it.executableRelativePath}"
                lines += "Game architecture: ${it.architecture}"
                lines += "Game locale: ${it.locale}"
            }
            lines += "----------------------------------------"
            val initialContents = lines.joinToString(separator = "\n", postfix = "\n")
            persistenceExecutor.execute {
                runCatching {
                    persistedLaunchLog.parentFile?.mkdirs()
                    persistedLaunchLog.writeText(initialContents, Charsets.UTF_8)
                }.onFailure { error ->
                    Log.e(DIAGNOSTIC_LOG_TAG, "Could not initialize persistent launch log.", error)
                }
            }
        }
        Log.i(DIAGNOSTIC_LOG_TAG, "DIAGNOSTIC_SESSION_START ${timestamp(sessionStartedAt)}")
    }

    fun record(message: String) = recordBatch(listOf(message))

    /**
     * Persists and mirrors a group of process-output lines with one IO write.
     * This avoids queuing a disk task for every bursty Box64/Wine log line.
     */
    fun recordBatch(messages: List<String>) {
        val safeMessages = messages.map { message ->
            message.replace("\u0000", "").take(MAX_LINE_LENGTH)
        }.filter { it.isNotEmpty() }
        if (safeMessages.isEmpty()) return

        val nowWallClock = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val persistedLines = safeMessages.map { message ->
            val elapsed = if (sessionStartedElapsedRealtime > 0L) {
                (nowElapsed - sessionStartedElapsedRealtime).coerceAtLeast(0L)
            } else {
                0L
            }
            "${timestamp(nowWallClock)} +${elapsed}ms  $message"
        }
        synchronized(lock) {
            lines.addAll(persistedLines)
            trimIfNeeded()
            val persistedBatch = persistedLines.joinToString(separator = "\n", postfix = "\n")
            persistenceExecutor.execute {
                runCatching {
                    persistedLaunchLog.parentFile?.mkdirs()
                    persistedLaunchLog.appendText(persistedBatch, Charsets.UTF_8)
                }.onFailure { error ->
                    Log.e(DIAGNOSTIC_LOG_TAG, "Could not append persistent launch log batch.", error)
                }
                safeMessages.joinToString("\n")
                    .chunked(LOGCAT_MESSAGE_CHUNK)
                    .forEachIndexed { index, chunk ->
                        Log.i(DIAGNOSTIC_LOG_TAG, "[$index] $chunk")
                    }
            }
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
        val reportCollectionStartedAt = System.currentTimeMillis()
        val snapshot = synchronized(lock) {
            Triple(lines.toList(), truncatedLines, sessionId to sessionStartedElapsedRealtime)
        }
        val persistedOutput = runCatching {
            persistenceExecutor.submit(Runnable {}).get(LOG_PERSISTENCE_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (persistedLaunchLog.isFile) persistedLaunchLog.readText(Charsets.UTF_8) else ""
        }.getOrNull()
        val launchOutputLines = if (persistedOutput.isNullOrBlank()) {
            snapshot.first
        } else {
            persistedOutput.lineSequence().toList()
        }
        return buildString {
            appendLine("RAMENWINE DIAGNOSTICS")
            appendLine("Collection started: ${timestamp(reportCollectionStartedAt)}")
            appendLine("Session ID: ${snapshot.third.first}")
            val reportElapsedMillis = if (snapshot.third.second > 0L) {
                (SystemClock.elapsedRealtime() - snapshot.third.second).coerceAtLeast(0L)
            } else {
                0L
            }
            appendLine("Session elapsed at report: ${reportElapsedMillis} ms")
            appendLine("This file contains app/runtime diagnostics and the latest launch log.")
            appendLine("It does not include game file contents or logs from other apps.")
            appendLine()
            appendDeviceSection()
            appendHostResourceSection()
            appendStorageSection()
            appendRuntimeSection()
            appendLaunchStageSummary(launchOutputLines)
            appendDirectAudioLogSection()
            appendLine()
            appendLine("=== LAUNCH OUTPUT ===")
            if (persistedOutput.isNullOrBlank()) {
                if (snapshot.second > 0) {
                    appendLine("[Older lines omitted to keep the report bounded: ${snapshot.second}]")
                }
                snapshot.first.forEach(::appendLine)
                if (persistedOutput == null) {
                    appendLine("[Persistent launch log could not be read; showing in-memory output.]")
                }
            } else {
                append(persistedOutput)
                if (!persistedOutput.endsWith("\n")) appendLine()
            }
            appendLine()
            val logcatOutput = readOwnLogcat()
            appendLine("Logcat snapshot captured: ${timestamp(System.currentTimeMillis())}")
            appendLogcatSection(logcatOutput)
            appendFindings(snapshot.first + launchOutputLines + logcatOutput.lineSequence())
            appendLine("Report build completed: ${timestamp(System.currentTimeMillis())}")
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
        appendLine("Target SDK: ${context.applicationInfo.targetSdkVersion}")
        appendLine("SELinux process context: ${runCatching { File("/proc/self/attr/current").readText().trim() }.getOrDefault("unavailable")}")
        appendLine("Available processors: ${Runtime.getRuntime().availableProcessors()}")
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull()
        appendLine("Host page size: ${pageSize?.let { "$it bytes" } ?: readKernelPageSize()}")
        appendLine("Kernel: ${System.getProperty("os.version") ?: "unknown"}")
        appendLine()
    }

    private fun StringBuilder.appendHostResourceSection() {
        appendLine("=== HOST MEMORY / PROCESS LIMITS ===")
        val status = runCatching { File("/proc/self/status").readLines() }.getOrDefault(emptyList())
        val statusFields = listOf(
            "Name:", "Pid:", "PPid:", "VmPeak:", "VmSize:", "VmHWM:", "VmRSS:",
            "VmData:", "VmStk:", "VmExe:", "VmLib:", "Threads:", "FDSize:"
        )
        appendLine("App process:")
        status.filter { line -> statusFields.any(line::startsWith) }.forEach { appendLine("  $it") }
        val memory = runCatching { File("/proc/meminfo").readLines() }.getOrDefault(emptyList())
        val memoryFields = listOf(
            "MemTotal:", "MemAvailable:", "MemFree:", "SwapTotal:", "SwapFree:",
            "CommitLimit:", "Committed_AS:"
        )
        appendLine("Android host memory:")
        memory.filter { line -> memoryFields.any(line::startsWith) }.forEach { appendLine("  $it") }
        listOf(
            "vm.max_map_count",
            "vm.mmap_rnd_bits",
            "vm.overcommit_memory",
            "vm.overcommit_ratio"
        ).forEach { name ->
            val value = runCatching {
                File("/proc/sys/$name").readText().trim()
            }.getOrDefault("unavailable")
            appendLine("$name: $value")
        }
        appendLine("Note: these are Android-host values after the failed launch; the guest preflight values are in LAUNCH OUTPUT.")
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
        appendGuestPackageVersions(backendRoot)
        appendGuestGraphicsLibraries(backendRoot)

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
            val marker = File(prefix, ".ramenwine-prefix")
            appendLine("Prefix completion marker: exists=${marker.isFile}, size=${marker.length()}")
            if (marker.isFile) {
                appendLine("Prefix completion marker contents:")
                marker.readLines().take(8).forEach { appendLine("  $it") }
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendGuestPackageVersions(backendRoot: File) {
        appendLine("Guest Debian package versions:")
        val statusFile = File(backendRoot, "rootfs/var/lib/dpkg/status")
        if (!statusFile.isFile) {
            appendLine("  dpkg status database unavailable")
            return
        }
        val wanted = setOf(
            "libc6:amd64",
            "libgcc-s1:amd64",
            "libstdc++6:amd64",
            "libgl1-mesa-dri:amd64",
            "libegl-mesa0:amd64",
            "libglx-mesa0:amd64",
            "libgbm1:amd64",
            "libvulkan1:amd64",
            "libwayland-client0:amd64"
        )
        val paragraphs = runCatching {
            statusFile.readText().split("\\n\\s*\\n".toRegex())
        }.getOrDefault(emptyList())
        paragraphs.forEach { paragraph ->
            val fields = paragraph.lineSequence().mapNotNull { line ->
                val separator = line.indexOf(": ")
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 2)
            }.toMap()
            val name = fields["Package"] ?: return@forEach
            val architecture = fields["Architecture"].orEmpty()
            val key = if (architecture.isBlank()) name else "$name:$architecture"
            if (key in wanted && fields["Status"]?.endsWith(" installed") == true) {
                appendLine("  $key ${fields["Version"].orEmpty()}")
            }
        }
    }

    private fun StringBuilder.appendGuestGraphicsLibraries(backendRoot: File) {
        appendLine("Guest graphics / C++ loader candidates:")
        val rootfs = File(backendRoot, "rootfs")
        val directories = listOf(
            "usr/lib/x86_64-linux-gnu",
            "lib/x86_64-linux-gnu",
            "usr/lib/x86_64-linux-gnu/dri"
        ).map { File(rootfs, it) }
        val candidates = directories.flatMap { directory ->
            directory.listFiles()?.filter { file ->
                val name = file.name.lowercase(Locale.US)
                name.contains("gallium") || name.contains("stdc++") ||
                    name.contains("glx_mesa") || name.contains("egl_mesa") ||
                    name.contains("glvnd") || name.contains("vulkan")
            }.orEmpty()
        }.distinctBy { it.absolutePath }.take(80)
        if (candidates.isEmpty()) {
            appendLine("  no matching graphics/C++ libraries found")
        } else {
            candidates.forEach { file ->
                val target = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                appendLine("  ${file.absolutePath} -> $target (exists=${file.exists()}, size=${file.length()})")
            }
        }
    }

    private fun StringBuilder.appendLaunchStageSummary(launchOutput: List<String>) {
        appendLine("=== LAUNCH STAGE SUMMARY ===")
        val events = launchOutput.filter { line ->
            line.contains("RamenWine: stage=") ||
                line.contains("UI: launch_mode=") ||
                line.contains("first-frame visible=")
        }.takeLast(MAX_LAUNCH_STAGE_EVENTS)
        if (events.isEmpty()) {
            appendLine("No structured launch-stage events were recorded.")
        } else {
            events.forEach(::appendLine)
        }
        appendLine()
    }

    private fun StringBuilder.appendDirectAudioLogSection() {
        appendLine("=== DIRECTAUDIO RELAY OUTPUT ===")
        val logFile = File(context.cacheDir, "wawona-runtime/directaudio-relay.log")
        if (!logFile.isFile) {
            appendLine("DirectAudio helper output file is not present.")
            appendLine()
            return
        }
        appendLine("File size: ${logFile.length()} bytes")
        val output = runCatching {
            RandomAccessFile(logFile, "r").use { file ->
                val length = file.length()
                val start = (length - MAX_DIRECTAUDIO_LOG_CHARACTERS).coerceAtLeast(0L)
                val bytes = ByteArray((length - start).toInt())
                file.seek(start)
                file.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrElse {
            "Could not read DirectAudio log: ${it.javaClass.simpleName}: ${it.message}"
        }
        appendLine(output)
        if (logFile.length() > MAX_DIRECTAUDIO_LOG_CHARACTERS) {
            appendLine("[Earlier DirectAudio output omitted; showing the last $MAX_DIRECTAUDIO_LOG_CHARACTERS bytes.]")
        }
        appendLine()
    }

    private fun StringBuilder.appendLogcatSection(logcatOutput: String) {
        appendLine("=== ANDROID LOGCAT (THIS APP UID ONLY; INCLUDES PERSISTED LAUNCH OUTPUT) ===")
        appendLine(logcatOutput)
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
        if (combined.contains("avc: denied", ignoreCase = true) &&
            combined.contains("execmod", ignoreCase = true)
        ) {
            appendLine("HIGH: Android SELinux explicitly denied execmod for a Wine DLL. This is not a noexec mount: check the app Target SDK and SELinux process context above. Android AOSP grants this legacy permission to app-private files only to apps targeting API 28 or lower.")
        }
        if (combined.contains("set_mem_area_state", ignoreCase = true) ||
            combined.contains("granularity_mask", ignoreCase = true)
        ) {
            appendLine("HIGH: Wine hit a virtual-memory alignment assertion. Compare Host page size above with the Box64 build revision included in the runtime version.")
        }
        if (combined.contains("GLIBCXX_3.4", ignoreCase = true) &&
            combined.contains("not found", ignoreCase = true)
        ) {
            appendLine("HIGH: Box64 could not resolve a versioned GLIBCXX symbol while loading a guest graphics library. Compare the Guest Debian package versions and the printed libstdc++/libgallium paths; this is a separate loader failure from Wine's memory-alignment assertion until the logs prove otherwise.")
        }
        if (combined.contains("libGLX_nvidia.so", ignoreCase = true)) {
            appendLine("NOTE: Box64 reported a missing NVIDIA GLX library. This may be an optional host GPU-wrapper probe; do not assume it caused prefix initialization to fail unless later graphics errors confirm it.")
        }
        if (combined.contains("/sys/devices/system/cpu", ignoreCase = true) ||
            combined.contains("failed to determine number of CPUs", ignoreCase = true)
        ) {
            appendLine("NOTE: CPU topology detection reported a sysfs issue. The runtime launch command should bind /sys/devices/system/cpu into the guest.")
        }
        if (combined.contains("RamenWine: setup heartbeat", ignoreCase = true)) {
            appendLine("INFO: A Wine setup heartbeat was captured. Its line includes the actual PRoot PID, tracked child-process states, wait channels, RSS, prefix registry-file state and host memory.")
        }
        if (combined.contains("RamenWine: setup timeout", ignoreCase = true)) {
            appendLine("HIGH: A supervised setup command exceeded its timeout. Use the final process-tree snapshot to distinguish a live wait, an exited child or a missing PRoot PID.")
        }
        if (
            combined.contains("RamenWine: stage=wineboot status=starting", ignoreCase = true) &&
            !combined.contains("RamenWine: stage=wineboot status=finished", ignoreCase = true)
        ) {
            appendLine("HIGH: wineboot started but no completion event was recorded before diagnostics were exported. Check the most recent setup heartbeat and process-tree state; this report alone does not prove a permanent hang.")
        }
        if (
            combined.contains("RamenWine: stage=wineboot status=finished", ignoreCase = true) &&
            !combined.contains("RamenWine: stage=prefix-registry status=verified", ignoreCase = true)
        ) {
            appendLine("HIGH: wineboot returned, but the app did not verify all three populated registry hives. Check the registry wait events and the final prefix inventory.")
        }
        if (combined.contains("UI: Win32 GDI first-frame visible=true", ignoreCase = true)) {
            appendLine("INFO: PixelCopy observed non-black pixels after the built-in Win32 GDI smoke launch. This confirms a real frame reached the Android Surface; it does not certify a game's own rendering.")
        }
        if (combined.contains("UI: Win32 GDI first-frame visible=false", ignoreCase = true)) {
            appendLine("HIGH: The built-in Win32 GDI smoke process was launched, but a non-black Android Surface frame was not confirmed within the observation window. Check process heartbeats separately: the process may still be running.")
        }
        if (combined.contains("UI: selected EXE first-frame visible=true", ignoreCase = true)) {
            appendLine("INFO: PixelCopy observed non-black pixels after the selected Windows EXE launch. A frame reached the Android Surface; this alone does not prove the game is interactive or fully rendered.")
        }
        if (combined.contains("UI: selected EXE first-frame visible=false", ignoreCase = true)) {
            appendLine("NOTE: The selected Windows EXE did not produce a confirmed non-black frame during the observation window. Check its process heartbeat and exit code before treating this as a crash.")
        }
        if (!combined.contains("noexec filesystem", ignoreCase = true) &&
            !combined.contains("set_mem_area_state", ignoreCase = true) &&
            !combined.contains("libGLX_nvidia.so", ignoreCase = true) &&
            !combined.contains("RamenWine: setup heartbeat", ignoreCase = true) &&
            !combined.contains("RamenWine: setup timeout", ignoreCase = true) &&
            !combined.contains("RamenWine: stage=wineboot", ignoreCase = true) &&
            !combined.contains("UI: Win32 GDI first-frame", ignoreCase = true)
        ) {
            appendLine("No known signature matched; use the structured stage summary, process snapshots and complete output.")
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
                "logcat", "-d", "-b", "all", "-v", "threadtime", "--uid",
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
            val captured = output.toString()
            val capturedLines = captured.lineSequence().toList()
            val latestSessionStart = capturedLines.indexOfLast { line ->
                line.contains(DIAGNOSTIC_LOG_TAG) && line.contains("DIAGNOSTIC_SESSION_START")
            }
            if (latestSessionStart >= 0) {
                capturedLines.drop(latestSessionStart).joinToString("\n")
            } else {
                captured
            }
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
        const val LOGCAT_LINE_LIMIT = 4_000
        const val MAX_LOGCAT_CHARACTERS = 650_000
        const val LOGCAT_MESSAGE_CHUNK = 3_000
        const val MAX_DIRECTAUDIO_LOG_CHARACTERS = 100_000
        const val MAX_LAUNCH_STAGE_EVENTS = 80
        const val DIAGNOSTIC_LOG_TAG = "RamenWineDiag"
        const val LOGCAT_TIMEOUT_SECONDS = 5L
        const val LOG_PERSISTENCE_FLUSH_TIMEOUT_SECONDS = 5L
    }
}
