package krtc.meet

import android.util.Log
import kotlin.concurrent.thread

actual fun appendLog(line: String) {
    Log.i("krtc-meet", line)
}

actual fun logLocation(): String? = null

actual fun threadDump(): String? = Thread.getAllStackTraces().entries.joinToString("\n") { (t, stack) ->
    "\"${t.name}\" ${t.state}" + stack.joinToString("") { "\n    at $it" }
}

actual fun watchdog(afterMillis: Long, stillStuck: () -> Boolean, onStuck: () -> Unit) {
    thread(isDaemon = true, name = "krtc-meet-watchdog") {
        Thread.sleep(afterMillis)
        if (stillStuck()) onStuck()
    }
}

actual fun platformName(): String = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

internal actual fun diagnosticResources(): Map<String, String> = buildMap {
    val runtime = Runtime.getRuntime()
    put("heapUsedBytes", (runtime.totalMemory() - runtime.freeMemory()).toString())
    put("logicalCpu", runtime.availableProcessors().toString())
    put("processCpuTimeMs", android.os.Process.getElapsedCpuTime().toString())
}
internal actual fun diagnosticEnvironment(): Map<String, String> = mapOf("platform" to platformName())
internal actual fun saveDiagnosticReport(id: String, json: String): String? = null
