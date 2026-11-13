package krtc.meet

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** ~/.krtc-meet, or the directory in the system property krtc.meet.logDir (tests). */
private val logFile: File by lazy {
    (System.getProperty("krtc.meet.logDir")?.let(::File) ?: File(System.getProperty("user.home"), ".krtc-meet"))
        .apply { mkdirs() }.resolve("krtc-meet.log").also { f ->
        // One previous log is kept, so a restart after a problem does not wipe it.
        if (f.length() > MAX_LOG_BYTES) f.renameTo(File(f.parentFile, "krtc-meet.previous.log"))
    }
}
private const val MAX_LOG_BYTES = 5L * 1024 * 1024
private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
private val lock = Any()

actual fun appendLog(line: String) {
    val text = "${LocalDateTime.now().format(time)} [${Thread.currentThread().name}] $line\n"
    synchronized(lock) { runCatching { logFile.appendText(text) } }
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
