package krtc.meet

import android.content.Context

/** Real storage needs a Context: AndroidMediaPlatform provides [PreferencesSettings]. */
actual fun defaultSettingsStore(): SettingsStore = MemorySettings()

class PreferencesSettings(context: Context) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("krtc-meet", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
