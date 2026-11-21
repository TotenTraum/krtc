package krtc.meet

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** ~/.krtc-meet, or the directory in the system property krtc.meet.logDir (tests). */
private val logFile: File by lazy {
    (System.getProperty("krtc.meet.logDir")?.let(::File) ?: File(System.getProperty("user.home"), ".krtc-meet"))
        .apply { mkdirs() }.resolve("krtc-meet.log")
}
private const val MAX_LOG_BYTES = 5L * 1024 * 1024
private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
private val lock = Any()

actual fun appendLog(line: String) {
    val text = "${LocalDateTime.now().format(time)} [${Thread.currentThread().name}] $line\n"
    synchronized(lock) {
        runCatching {
            appendRotatingLog(logFile, text, MAX_LOG_BYTES)
        }
    }
}

/** The caller serializes writes. Rotation replaces the previous file on Windows as well. */
internal fun appendRotatingLog(file: File, text: String, maxBytes: Long) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (file.exists() && file.length() + bytes.size > maxBytes) {
        java.nio.file.Files.move(file.toPath(), File(file.parentFile, "krtc-meet.previous.log").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    file.appendBytes(bytes)
}

actual fun logLocation(): String? = logFile.absolutePath

actual fun threadDump(): String? = Thread.getAllStackTraces().entries.sortedBy { it.key.name }.joinToString("\n") { (t, stack) ->
    "\"${t.name}\" ${t.state}" + stack.joinToString("") { "\n    at $it" }
}

actual fun watchdog(afterMillis: Long, stillStuck: () -> Boolean, onStuck: () -> Unit) {
    thread(isDaemon = true, name = "krtc-meet-watchdog") {
        Thread.sleep(afterMillis)
        if (stillStuck()) onStuck()
    }
}

actual fun platformName(): String =
    "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}"

internal actual fun diagnosticResources(): Map<String, String> = buildMap {
    val runtime = Runtime.getRuntime()
    put("heapUsedBytes", (runtime.totalMemory() - runtime.freeMemory()).toString())
    put("heapMaxBytes", runtime.maxMemory().toString())
    put("logicalCpu", runtime.availableProcessors().toString())
    put("threads", java.lang.management.ManagementFactory.getThreadMXBean().threadCount.toString())
    val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    os?.processCpuLoad?.takeIf { it >= 0 }?.let { put("processCpuFraction", it.toString()) }
    os?.processCpuTime?.takeIf { it >= 0 }?.let { put("processCpuTimeNs", it.toString()) }
    os?.committedVirtualMemorySize?.takeIf { it >= 0 }?.let { put("committedVirtualBytes", it.toString()) }
    val gc = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
    put("gcTimeMs", gc.sumOf { it.collectionTime.coerceAtLeast(0) }.toString())
}

internal actual fun diagnosticEnvironment(): Map<String, String> = buildMap {
    put("platform", platformName())
    runCatching {
        val os = System.getProperty("os.name").lowercase()
        val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"
        val (directory, name) = when {
            os.startsWith("windows") -> "windows-$arch" to "krtc.dll"
            os.startsWith("mac") -> "macos-$arch" to "libkrtc_jni.dylib"
            else -> "linux-$arch" to "libkrtc_jni.so"
        }
        val stream = System.getProperty("krtc.native.library")?.let { File(it).inputStream() }
            ?: krtc.native.NativePeerConnectionFactory::class.java.getResourceAsStream("/krtc/native/$directory/$name")
        if (stream != null) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            stream.use { input ->
                val buffer = ByteArray(16384)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            put("nativeSha256", digest.digest().joinToString("") { "%02x".format(it) })
        } else put("nativeSha256", "unavailable_system_library")
    }.onFailure { put("nativeSha256", "query_unavailable") }
    if (System.getProperty("os.name").startsWith("Windows")) {
        // Fixed local query, no user input. Bounded wait and output; no serial numbers or machine/user names.
        val result = runCatching {
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion,VideoProcessor | ConvertTo-Json -Compress")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                if (process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS))
                    process.inputStream.bufferedReader().use { it.readText().take(4096) }
                else "query_timeout"
            } finally { process.destroyForcibly() }
        }
        put("gpuDriver", result.getOrElse { "query_unavailable: ${it.javaClass.simpleName}" })
    }
}

internal actual fun saveDiagnosticReport(id: String, json: String): String? = runCatching {
    val dir = File(logFile.parentFile, "diagnostics").apply { mkdirs() }
    val safe = id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(100)
    val file = File(dir, "$safe.json")
    val temporary = java.nio.file.Files.createTempFile(dir.toPath(), ".report-", ".tmp")
    try {
        java.nio.file.Files.writeString(temporary, json)
        try {
            java.nio.file.Files.move(temporary, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temporary, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { java.nio.file.Files.deleteIfExists(temporary) }
    dir.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
    file.absolutePath
}.getOrNull()
