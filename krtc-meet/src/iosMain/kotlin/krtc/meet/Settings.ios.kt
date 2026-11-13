package krtc.meet

import platform.Foundation.NSUserDefaults

private class UserDefaultsSettings : SettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun get(key: String): String? = defaults.stringForKey("krtc-meet.$key")
    override fun set(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey("krtc-meet.$key") else defaults.setObject(value, "krtc-meet.$key")
    }
}

actual fun defaultSettingsStore(): SettingsStore = UserDefaultsSettings()
