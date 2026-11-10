package krtc.sfu

import krtc.sfu.room.Shard
import krtc.sfu.room.ShardCounters
import kotlin.concurrent.atomics.AtomicLong

/** Process-wide counters for control-plane events (media counters live in each shard). */
internal class Metrics {
    val stunRequests = AtomicLong(0)
    val stunRejected = AtomicLong(0)
    val dtlsConnected = AtomicLong(0)
    val dtlsFailed = AtomicLong(0)
    val unknownSource = AtomicLong(0)

    fun snapshot(participants: Int, rooms: Int, shards: List<Shard>): SfuMetrics {
        fun sum(f: (ShardCounters) -> AtomicLong) = shards.sumOf { f(it.counters).load() }
        return SfuMetrics(
            participants = participants,
            rooms = rooms,
            rtpIn = sum { it.rtpIn },
            rtpOut = sum { it.rtpOut },
            bytesIn = sum { it.bytesIn },
            bytesOut = sum { it.bytesOut },
            rtcpIn = sum { it.rtcpIn },
            rtcpOut = sum { it.rtcpOut },
            srtpFailures = sum { it.srtpFailures },
            droppedOverload = sum { it.dropped },
            keyframeRequests = sum { it.keyframeRequests },
            nacksAnswered = sum { it.nacksAnswered },
            nacksSent = sum { it.nacksSent },
            rtxRecovered = sum { it.rtxRecovered },
            dataMessagesIn = sum { it.dataIn },
            dataMessagesOut = sum { it.dataOut },
            dataMessagesDropped = sum { it.dataDropped },
            stunRequests = stunRequests.load(),
            stunRejected = stunRejected.load(),
            dtlsConnected = dtlsConnected.load(),
            dtlsFailed = dtlsFailed.load(),
            unknownSource = unknownSource.load(),
            shardQueueDepth = shards.map { it.queueDepth },
        )
    }
}
