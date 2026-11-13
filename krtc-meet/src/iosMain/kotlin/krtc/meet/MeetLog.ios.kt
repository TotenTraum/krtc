package krtc.meet

import platform.Foundation.NSLog
import platform.Foundation.NSThread
import kotlin.concurrent.AtomicInt

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
