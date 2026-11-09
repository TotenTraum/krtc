package krtc.sfu.room

import java.nio.ByteBuffer
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A single worker thread that owns a subset of rooms. All media work for a room
 * (SRTP decrypt, fan-out, re-encrypt, send) happens here, so per-room state needs no locks
 * and rooms scale across cores. This is the unit of horizontal scaling inside one process.
 */
public class Shard(public val index: Int) : AutoCloseable {
    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "shard-$index").apply { isDaemon = true }
    }

    /** Scratch buffers owned by this shard's thread. */
    internal val out: ByteArray = ByteArray(2048)
    internal val sendBuffer: ByteBuffer = ByteBuffer.allocateDirect(2048)

    public val queueDepth: Int get() = executor.queue.size

    public fun execute(task: Runnable) {
        executor.execute(task)
    }

    public fun every(periodMillis: Long, task: Runnable) {
        executor.scheduleAtFixedRate(task, periodMillis, periodMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
