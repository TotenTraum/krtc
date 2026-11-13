package krtc.meet

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** The app's saved settings: string values by key. Writes are cheap and may happen often (a slider). */
interface SettingsStore {
    operator fun get(key: String): String?
    operator fun set(key: String, value: String?)
}

/** Kept in memory only: tests, or a platform without storage. */
class MemorySettings : SettingsStore {
    private val lock = SynchronizedObject()
    private val values = HashMap<String, String>()
    override fun get(key: String): String? = synchronized(lock) { values[key] }
    override fun set(key: String, value: String?) {
        synchronized(lock) { if (value == null) values.remove(key) else values[key] = value }
    }
}

/** Desktop: `~/.krtc-meet/settings.properties`; iOS: NSUserDefaults; Android uses SharedPreferences (AndroidMediaPlatform). */
expect fun defaultSettingsStore(): SettingsStore

internal val sharedDefaultSettings: SettingsStore by lazy { defaultSettingsStore() }
