package krtc.meet

import java.io.File
import java.util.Properties
import kotlin.concurrent.thread

/**
 * A properties file in ~/.krtc-meet (or the directory in the system property krtc.meet.settingsDir, for tests).
 * Writes are batched: the file is rewritten at most twice a second and when the app exits.
 */
private class FileSettings(private val file: File) : SettingsStore {
    private val props = Properties().apply { runCatching { file.inputStream().use { load(it) } } }
    private var dirty = false

    init {
        thread(isDaemon = true, name = "krtc-meet-settings") {
            while (true) {
                Thread.sleep(500)
                flush()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { flush() })
    }

    override fun get(key: String): String? = synchronized(props) { props.getProperty(key) }

    override fun set(key: String, value: String?) {
        synchronized(props) {
            if (value == null) props.remove(key) else props.setProperty(key, value)
            dirty = true
        }
    }

    private fun flush() {
        val copy = synchronized(props) {
            if (!dirty) return
            dirty = false
            Properties().also { it.putAll(props) }
        }
        runCatching {
            file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { copy.store(it, "krtc-meet settings") }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}

actual fun defaultSettingsStore(): SettingsStore {
    val dir = System.getProperty("krtc.meet.settingsDir")?.let(::File) ?: File(System.getProperty("user.home"), ".krtc-meet")
    return FileSettings(dir.resolve("settings.properties"))
}
