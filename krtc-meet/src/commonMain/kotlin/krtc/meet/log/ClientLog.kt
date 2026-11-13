package krtc.meet.log

import krtc.meet.appendLog
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The app's log: every entry goes to the local log ([appendLog]) and waits here for [LogUploader] to send it to the
 * server in batches. At most [MAX_PENDING] entries wait; beyond that the oldest are dropped (and the next batch says
 * how many). Thread-safe.
 */
object ClientLog {
    enum class Level(val letter: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    class Entry(val timeMillis: Long, val level: Level, val tag: String, val message: String)

    const val MAX_PENDING: Int = 5_000

    /** Longer messages (a thread dump) are cut, so one entry cannot fill a batch. */
    const val MAX_MESSAGE_CHARS: Int = 32 * 1024

    private val lock = SynchronizedObject()
    private val pending = ArrayDeque<Entry>()
    private var dropped = 0
    private val pendingCount = MutableStateFlow(0)

    /** How many entries wait to be sent; the uploader wakes up on it. */
    val waiting: StateFlow<Int> = pendingCount.asStateFlow()

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)

    @OptIn(ExperimentalTime::class)
    fun log(level: Level, tag: String, message: String) {
        val text = if (message.length > MAX_MESSAGE_CHARS) message.take(MAX_MESSAGE_CHARS) + "… (cut)" else message
        runCatching { appendLog("${level.letter} [$tag] $text") }
        val entry = Entry(Clock.System.now().toEpochMilliseconds(), level, tag, text)
        synchronized(lock) {
            pending.addLast(entry)
            while (pending.size > MAX_PENDING) {
                pending.removeFirst()
                dropped++
            }
            pendingCount.value = pending.size
        }
    }

    /** Takes up to [max] of the oldest entries to send, with a note first if some were dropped meanwhile. */
    @OptIn(ExperimentalTime::class)
    internal fun take(max: Int): List<Entry> = synchronized(lock) {
        val out = ArrayList<Entry>(minOf(max, pending.size) + 1)
        if (dropped > 0) {
            out += Entry(Clock.System.now().toEpochMilliseconds(), Level.WARN, "log", "$dropped entries dropped: the server was out of reach too long")
            dropped = 0
        }
        while (out.size < max && pending.isNotEmpty()) out += pending.removeFirst()
        pendingCount.value = pending.size
        out
    }

    /** Puts entries that could not be sent back in front, in order (dropping the oldest beyond the limit). */
    internal fun putBack(entries: List<Entry>) = synchronized(lock) {
        for (e in entries.asReversed()) pending.addFirst(e)
        while (pending.size > MAX_PENDING) {
            pending.removeFirst()
            dropped++
        }
        pendingCount.value = pending.size
    }
}
