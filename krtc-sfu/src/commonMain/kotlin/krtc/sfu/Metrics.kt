package krtc.sfu

import krtc.sfu.room.Shard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.atomics.AtomicLong

/** Process-wide counters for control-plane events (media counters live in each shard). */
public class Metrics {
    public val stunRequests: AtomicLong = AtomicLong(0)
    public val stunRejected: AtomicLong = AtomicLong(0)
    public val dtlsConnected: AtomicLong = AtomicLong(0)
    public val dtlsFailed: AtomicLong = AtomicLong(0)
    public val unknownSource: AtomicLong = AtomicLong(0)

    public fun toJson(participants: Int, rooms: Int, shards: List<Shard>): JsonObject = buildJsonObject {
        put("participants", participants)
        put("rooms", rooms)
        fun sum(f: (krtc.sfu.room.ShardCounters) -> AtomicLong) = shards.sumOf { f(it.counters).load() }
        put("rtpIn", sum { it.rtpIn })
        put("rtpOut", sum { it.rtpOut })
        put("bytesIn", sum { it.bytesIn })
        put("bytesOut", sum { it.bytesOut })
        put("rtcpIn", sum { it.rtcpIn })
        put("rtcpOut", sum { it.rtcpOut })
        put("srtpFailures", sum { it.srtpFailures })
        put("droppedOverload", sum { it.dropped })
        put("keyframeRequests", sum { it.keyframeRequests })
        put("nacksAnswered", sum { it.nacksAnswered })
        put("stunRequests", stunRequests.load())
        put("stunRejected", stunRejected.load())
        put("dtlsConnected", dtlsConnected.load())
        put("dtlsFailed", dtlsFailed.load())
        put("unknownSource", unknownSource.load())
        put("shardQueueDepth", JsonArray(shards.map { JsonPrimitive(it.queueDepth) }))
    }
}
