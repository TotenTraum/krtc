package krtc.meet

import platform.Foundation.NSLog
import platform.Foundation.NSThread

actual fun appendLog(line: String) {
    NSLog("krtc-meet: %@", line)
}

actual fun logLocation(): String? = null

actual fun threadDump(): String? = null

actual fun watchdog(afterMillis: Long, stillStuck: () -> Boolean, onStuck: () -> Unit) {
    val thread = NSThread {
        NSThread.sleepForTimeInterval(afterMillis / 1000.0)
        if (stillStuck()) onStuck()
    }
    thread.start()
}

actual fun platformName(): String = platform.UIKit.UIDevice.currentDevice.let { "${it.systemName} ${it.systemVersion}, ${it.model}" }

internal actual fun diagnosticResources(): Map<String, String> = emptyMap()
internal actual fun diagnosticEnvironment(): Map<String, String> = mapOf("platform" to platformName())
internal actual fun saveDiagnosticReport(id: String, json: String): String? = null
