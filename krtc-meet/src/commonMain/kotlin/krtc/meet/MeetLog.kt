package krtc.meet

/**
 * The app's diagnostic log: what happened in the call, to find out afterwards why something did not work on a
 * machine nobody here can look at. Desktop: a file ([logLocation]); Android and iOS: the system log.
 */
expect fun appendLog(line: String)

/** Where [appendLog] writes, to show the user; null where it goes to the system log. */
expect fun logLocation(): String?

/** Stack traces of all threads (desktop JVM), to see where a hang is; null where unavailable. */
expect fun threadDump(): String?

/**
 * Calls [onStuck] after [afterMillis] if [stillStuck] says so, on a thread of its own: it must still fire when the UI
 * thread or the coroutine threads are blocked, which is what it is for.
 */
expect fun watchdog(afterMillis: Long, stillStuck: () -> Boolean, onStuck: () -> Unit)

/** The OS, its version and the device, for the log's header on the server. */
expect fun platformName(): String

/** Cheap process resources. Unsupported counters are omitted, never invented. Call off the UI thread. */
internal expect fun diagnosticResources(): Map<String, String>

/** Collected once per call, off the UI thread (may query GPU/driver information). */
internal expect fun diagnosticEnvironment(): Map<String, String>

/** Retained standalone JSON reports on desktop; mobile reports still go to ClientLog. */
internal expect fun saveDiagnosticReport(id: String, json: String): String?
