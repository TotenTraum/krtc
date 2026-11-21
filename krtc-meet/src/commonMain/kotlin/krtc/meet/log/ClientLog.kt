package krtc.meet.log

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import krtc.meet.appendLog
import kotlin.time.Clock

/** Local log and bounded upload queue. The minimum level applies to both destinations. */
object ClientLog {
    enum class Level(val letter: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E"), OFF("-") }

    class Entry(val timeMillis: Long, val level: Level, val tag: String, val message: String) {
        // Conservative UTF-16 payload estimate plus an allowance for the entry object.
        internal val storageBytes: Int get() = 128 + 2 * (tag.length + message.length)
    }

    const val MAX_PENDING: Int = 5_000
    const val MAX_PENDING_BYTES: Int = 1024 * 1024
    const val MAX_MESSAGE_CHARS: Int = 32 * 1024

    private val lock = SynchronizedObject()
    private val pending = ArrayDeque<Entry>()
    private var pendingBytes = 0
    private var dropped = 0
    private val pendingCount = MutableStateFlow(0)
    private val minimumLevelFlow = MutableStateFlow(Level.INFO)

    val minimumLevel: StateFlow<Level> = minimumLevelFlow.asStateFlow()
    val waiting: StateFlow<Int> = pendingCount.asStateFlow()
    internal val queuedStorageBytes: Int get() = synchronized(lock) { pendingBytes }

    fun isEnabled(level: Level): Boolean = level != Level.OFF && level.ordinal >= minimumLevelFlow.value.ordinal

    fun setMinimumLevel(level: Level) = synchronized(lock) {
        minimumLevelFlow.value = level
        // A newly reduced verbosity also applies to entries still waiting for upload.
        val retained = pending.filter { isEnabled(it.level) }
        pending.clear()
        pending.addAll(retained)
        pendingBytes = retained.sumOf { it.storageBytes }
        if (level == Level.OFF) dropped = 0
        pendingCount.value = pending.size
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)
    fun d(tag: String, message: () -> String) = log(Level.DEBUG, tag, message)

    fun log(level: Level, tag: String, message: () -> String) {
        if (isEnabled(level)) log(level, tag, message())
    }

    fun log(level: Level, tag: String, message: String) {
        if (!isEnabled(level)) return
        val text = if (message.length > MAX_MESSAGE_CHARS) message.take(MAX_MESSAGE_CHARS) + "… (cut)" else message
        val shortTag = tag.take(128)
        runCatching { appendLog("${level.letter} [$shortTag] $text") }
        val entry = Entry(Clock.System.now().toEpochMilliseconds(), level, shortTag, text)
        synchronized(lock) {
            if (!isEnabled(level)) return@synchronized
            pending.addLast(entry)
            pendingBytes += entry.storageBytes
            trim()
        }
    }

    private fun trim() {
        while (pending.size > MAX_PENDING || pendingBytes > MAX_PENDING_BYTES) {
            pendingBytes -= pending.removeFirst().storageBytes
            dropped++
        }
        pendingCount.value = pending.size
    }

    internal fun take(max: Int): List<Entry> = synchronized(lock) {
        if (max <= 0) return@synchronized emptyList()
        val out = ArrayList<Entry>(minOf(max, pending.size) + 1)
        if (dropped > 0) {
            if (isEnabled(Level.WARN)) out += Entry(Clock.System.now().toEpochMilliseconds(), Level.WARN, "log", "$dropped entries dropped: upload queue limit exceeded")
            dropped = 0
        }
        while (out.size < max && pending.isNotEmpty()) {
            val entry = pending.removeFirst()
            pendingBytes -= entry.storageBytes
            out += entry
        }
        pendingCount.value = pending.size
        out
    }

    internal fun putBack(entries: List<Entry>) = synchronized(lock) {
        for (entry in entries.asReversed()) {
            if (!isEnabled(entry.level)) continue
            pending.addFirst(entry)
            pendingBytes += entry.storageBytes
        }
        trim()
    }
}
