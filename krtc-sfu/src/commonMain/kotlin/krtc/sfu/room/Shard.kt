package krtc.sfu.room

import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.decrementAndFetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong

/** Per-shard counters: written by one thread only, so increments never contend across cores. */
internal class ShardCounters {
    internal val rtpIn: AtomicLong = AtomicLong(0)
    internal val rtpOut: AtomicLong = AtomicLong(0)
    internal val bytesIn: AtomicLong = AtomicLong(0)
    internal val bytesOut: AtomicLong = AtomicLong(0)
    internal val rtcpIn: AtomicLong = AtomicLong(0)
    internal val rtcpOut: AtomicLong = AtomicLong(0)
    internal val srtpFailures: AtomicLong = AtomicLong(0)
    internal val dropped: AtomicLong = AtomicLong(0)
    internal val keyframeRequests: AtomicLong = AtomicLong(0)
    internal val nacksAnswered: AtomicLong = AtomicLong(0)
    internal val nacksSent: AtomicLong = AtomicLong(0)
    internal val rtxRecovered: AtomicLong = AtomicLong(0)
    internal val dataIn: AtomicLong = AtomicLong(0)
    internal val dataOut: AtomicLong = AtomicLong(0)
    internal val dataDropped: AtomicLong = AtomicLong(0)
}

/**
 * A single worker thread that owns a subset of rooms. All media work for a room (SRTP decrypt,
 * fan-out, re-encrypt, send) happens here, so per-room state needs no locks and rooms scale across
 * cores. The inbound queue is bounded: under overload packets are dropped (and counted) instead of
 * accumulating latency and memory.
 */
internal class Shard(public val index: Int, capacity: Int = 16_384) : AutoCloseable {
    private val dispatcher = newSingleThreadContext("shard-$index")
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val queue = Channel<() -> Unit>(capacity)
    private val depth = AtomicInt(0)

    internal val counters: ShardCounters = ShardCounters()

    /** Scratch output buffer owned by this shard's thread. */
    internal val out: ByteArray = ByteArray(2048)

    internal val queueDepth: Int get() = depth.load()

    init {
        scope.launch {
            for (task in queue) {
                depth.decrementAndFetch()
                try {
                    task()
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    krtc.sfu.platform.logger("krtc.sfu.Shard").error("shard $index task failed", t)
                }
            }
        }
    }

    /** Enqueues [task]; returns false (and counts a drop) if the shard is overloaded. */
    internal fun execute(task: () -> Unit): Boolean {
        if (queue.trySend(task).isSuccess) {
            depth.incrementAndFetch()
            return true
        }
        counters.dropped.incrementAndFetch()
        return false
    }

    internal fun every(periodMillis: Long, task: () -> Unit) {
        scope.launch {
            while (isActive) {
                delay(periodMillis)
                execute(task)
            }
        }
    }

    override fun close() {
        queue.close()
        scope.cancel()
        dispatcher.close()
    }
}
