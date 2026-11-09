package krtc.sfu

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.LongAdder

/** Process-wide counters. LongAdder keeps increments cheap from many shard threads. */
public class Metrics {
    public val rtpIn: LongAdder = LongAdder()
    public val rtpOut: LongAdder = LongAdder()
    public val bytesIn: LongAdder = LongAdder()
    public val bytesOut: LongAdder = LongAdder()
    public val rtcpDropped: LongAdder = LongAdder()
    public val srtpFailures: LongAdder = LongAdder()
    public val stunRequests: LongAdder = LongAdder()
    public val stunRejected: LongAdder = LongAdder()
    public val dtlsConnected: LongAdder = LongAdder()
    public val dtlsFailed: LongAdder = LongAdder()
    public val unknownSource: LongAdder = LongAdder()

    public fun toJson(participants: Int, rooms: Int, shards: List<Int>): JsonObject = buildJsonObject {
        put("participants", participants)
        put("rooms", rooms)
        put("rtpIn", rtpIn.sum())
        put("rtpOut", rtpOut.sum())
        put("bytesIn", bytesIn.sum())
        put("bytesOut", bytesOut.sum())
        put("rtcpDropped", rtcpDropped.sum())
        put("srtpFailures", srtpFailures.sum())
        put("stunRequests", stunRequests.sum())
        put("stunRejected", stunRejected.sum())
        put("dtlsConnected", dtlsConnected.sum())
        put("dtlsFailed", dtlsFailed.sum())
        put("unknownSource", unknownSource.sum())
        put("shardQueueDepth", kotlinx.serialization.json.JsonArray(shards.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }
}
