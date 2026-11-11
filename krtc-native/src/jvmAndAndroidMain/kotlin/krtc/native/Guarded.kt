package krtc.native

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A native handle that one thread may release while others still use it (leaving a call while an offer is being
 * answered, a view detaching from a track): calls hold the read lock, [release] the write lock, so no call ever
 * reaches freed memory. After release, [use] throws and [useOr] returns its default.
 */
internal class Guarded(private val value: Long) {
    private val lock = ReentrantReadWriteLock()
    private var released = false

    val isReleased: Boolean get() = lock.read { released }

    fun <T> use(block: (Long) -> T): T = lock.read {
        check(!released) { "already closed" }
        block(value)
    }

    fun <T> useOr(default: T, block: (Long) -> T): T = lock.read { if (released) default else block(value) }

    /** Releases once; true if this call did. */
    fun release(block: (Long) -> Unit): Boolean = lock.write {
        if (released) return false
        released = true
        block(value)
        true
    }
}
