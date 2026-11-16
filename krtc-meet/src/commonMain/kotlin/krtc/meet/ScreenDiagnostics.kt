package krtc.meet

import krtc.core.RTCStats
import kotlinx.serialization.json.*
import kotlin.time.TimeSource

/** UI-confined incident recorder. Bounded history, delayed postmortems, no media or raw SDP/IP addresses. */
internal class ScreenDiagnostics(
    private val now: () -> Long = run { val origin = TimeSource.Monotonic.markNow(); { origin.elapsedNow().inWholeMilliseconds } },
    private val emit: (String, JsonObject) -> Unit,
) {
    private class Stream(val context: Map<String, String>, val expectedFps: Int, val started: Long) {
        val history = ArrayDeque<JsonObject>()
        var terminal = false
        var first = false
        var previous = emptyMap<String, Any?>()
        var previousAt: Long? = null
        var lowSince: Long? = null
        var lastReport = Long.MIN_VALUE / 2
        var peakFps = 0.0
        val milestones = mutableSetOf<String>()
    }
    private class Incident(val id: String, val key: String, val reason: String, val at: Long, val context: Map<String, String>, val before: List<JsonObject>) {
        val after = mutableListOf<JsonObject>()
    }
    private val streams = linkedMapOf<String, Stream>()
    private val pending = mutableListOf<Incident>()
    private val events = ArrayDeque<JsonObject>()
    private var context = emptyMap<String, String>()
    private var sequence = 0
    private var lastIncident = Long.MIN_VALUE / 2

    fun reset(values: Map<String, String>) {
        flush("call_changed")
        streams.clear(); events.clear()
        context = values.mapValues { it.value.take(2048) } + ("callStartMs" to now().toString())
        lastIncident = Long.MIN_VALUE / 2
    }

    fun context(values: Map<String, String>) { context = context + values.mapValues { it.value.take(2048) } }

    fun event(name: String, values: Map<String, String> = emptyMap()) {
        events.addLast(buildJsonObject {
            put("atMs", now()); put("event", name)
            put("details", strings(values.mapValues { it.value.take(2048) }))
        })
        while (events.size > 64) events.removeFirst()
    }

    fun start(key: String, values: Map<String, String>, fps: Int = 0) {
        stop(key)
        if (streams.size >= 16) stop(streams.keys.first())
        streams[key] = Stream(context + values.mapValues { it.value.take(2048) }, fps, now())
        event("stream_requested", values + ("stream" to key))
    }

    fun stop(key: String) {
        if (key in streams) event("stream_stopped", mapOf("stream" to key))
        pending.filter { it.key == key }.toList().forEach { finish(it, "stream_stopped") }
        streams.remove(key)
    }

    fun failure(key: String, reason: String, details: String = "") {
        event(reason, mapOf("stream" to key, "error" to details))
        trigger(key, reason)
        streams[key]?.terminal = true
    }

    fun sample(key: String, values: Map<String, Any?>, fps: Double?, firstFrame: Boolean, captureFps: Double? = null) {
        val stream = streams[key] ?: return
        val at = now()
        val interval = stream.previousAt?.let { at - it }?.takeIf { it > 0 }
        fun delta(field: String): Double? {
            val previous = (stream.previous[field] as? Number)?.toDouble() ?: return null
            val current = (values[field] as? Number)?.toDouble() ?: return null
            return (current - previous).takeIf { it >= 0 }
        }
        val frameDelta = delta(if ("framesSent" in values) "framesSent" else "framesDecoded")
        val observedFps = if (interval != null && frameDelta != null) frameDelta * 1000 / interval else fps ?: if (stream.first) 0.0 else null
        val sample = buildJsonObject {
            put("atMs", at); put("elapsedMs", at - stream.started); put("intervalMs", interval)
            put("observedFps", observedFps); put("stats", json(values))
        }
        stream.history.addLast(sample)
        while (stream.history.size > 10) stream.history.removeFirst()
        pending.filter { it.key == key }.toList().forEach {
            if (it.after.size < 8) it.after += sample
            if (at - it.at >= 8_000) finish(it, "post_context_complete")
        }
        for (field in listOf("encoderImplementation", "decoderImplementation", "frameWidth", "frameHeight", "transport.selectedCandidatePairId", "transport.iceState", "transport.dtlsState")) {
            if (stream.previous[field] != values[field] && field in stream.previous)
                event("stats_changed", mapOf("stream" to key, "field" to field, "before" to stream.previous[field].toString(), "after" to values[field].toString()))
        }
        for ((stage, count) in listOf("captured" to values["source.frames"], "encoded" to values["framesEncoded"], "sent" to values["framesSent"], "received" to values["framesReceived"], "decoded" to values["framesDecoded"], "delivered" to values["deliveredFrames"])) {
            if ((count as? Number)?.toLong()?.let { it > 0 } == true && stream.milestones.add(stage))
                event("first_$stage", mapOf("stream" to key, "elapsedMs" to (at - stream.started).toString()))
        }
        if (firstFrame && !stream.first) {
            stream.first = true
            event("first_frame", mapOf("stream" to key, "elapsedMs" to (at - stream.started).toString()))
        }
        if (observedFps != null && observedFps.isFinite()) stream.peakFps = maxOf(stream.peakFps, observedFps)
        val baseline = if (stream.expectedFps >= 30) stream.expectedFps.toDouble() else if (stream.expectedFps == 0) stream.peakFps else 0.0
        val low = !stream.terminal && stream.first && baseline >= 10 && observedFps != null && observedFps < baseline / 2
        if (low) {
            if (stream.lowSince == null) stream.lowSince = at
            if (at - stream.lowSince!! >= 6_000) {
                val reason = when {
                    delta("framesDecoded")?.let { it > 0 } == true && delta("deliveredFrames") == 0.0 -> "frame_delivery_stall"
                    delta("framesReceived")?.let { it > 0 } == true && delta("framesDecoded") == 0.0 -> "decode_stall"
                    delta("bytesReceived") == 0.0 -> "receive_stall"
                    delta("source.frames")?.let { it > 0 } == true && delta("framesEncoded") == 0.0 -> "encoding_stall"
                    delta("framesEncoded")?.let { it > 0 } == true && delta("framesSent") == 0.0 -> "send_stall"
                    captureFps != null && captureFps < baseline / 2 -> "capture_low_activity_or_stall"
                    values["qualityLimitationReason"] == "cpu" -> "encoder_cpu_limited"
                    values["qualityLimitationReason"] == "bandwidth" -> "bandwidth_limited_fps"
                    else -> "sustained_low_fps"
                }
                trigger(key, reason)
            }
        } else stream.lowSince = null
        stream.previous = values
        stream.previousAt = at
    }

    /** Must run even if getStats fails or no RTP exists. */
    fun tick() {
        val at = now()
        streams.toMap().forEach { (key, stream) -> if (!stream.terminal && !stream.first && at - stream.started >= 15_000) trigger(key, "first_frame_timeout") }
        pending.filter { at - it.at >= 10_000 }.toList().forEach { finish(it, "post_context_complete") }
    }

    private fun trigger(key: String, reason: String) {
        val stream = streams[key] ?: return
        val at = now()
        if (pending.any { it.key == key } || pending.size >= 4 || at - stream.lastReport < 60_000 || at - lastIncident < 10_000) return
        val id = "${context["callId"] ?: "call"}-${++sequence}"
        val incident = Incident(id, key, reason, at, stream.context + context, stream.history.toList())
        pending += incident
        stream.lastReport = at
        lastIncident = at
        event("incident_triggered", mapOf("id" to id, "stream" to key, "reason" to reason))
        // Persist immediately too: a crash during post-context collection must not lose the evidence.
        emit(incident.id, report(incident, "collecting_post_context"))
    }

    fun flush(reason: String) { pending.toList().forEach { finish(it, reason) } }

    private fun finish(incident: Incident, completion: String) {
        pending.remove(incident)
        emit(incident.id, report(incident, completion))
    }

    private fun report(incident: Incident, completion: String): JsonObject = buildJsonObject {
            put("schema", 1); put("id", incident.id); put("stream", incident.key); put("reason", incident.reason)
            put("triggerMs", incident.at); put("completion", completion)
            put("context", strings(context + incident.context)); put("events", JsonArray(events.toList()))
            put("before", JsonArray(incident.before)); put("after", JsonArray(incident.after))
        }

    companion object {
        private fun strings(values: Map<String, String>) = JsonObject(values.mapValues { JsonPrimitive(it.value) })
        private fun json(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is Number -> if (value.toDouble().isFinite()) JsonPrimitive(value) else JsonNull
            is Boolean -> JsonPrimitive(value)
            is String -> JsonPrimitive(value.take(2048))
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to json(it.value) })
            else -> JsonPrimitive(value.toString().take(2048))
        }
        /** Select fields explicitly: never copy arbitrary stats (candidate IPs, certificates, etc.). */
        fun stats(report: Map<String, RTCStats>, rtp: RTCStats?): Map<String, Any?> {
            if (rtp == null) return mapOf("rtpPresent" to false)
            val result = linkedMapOf<String, Any?>("rtpPresent" to true, "rtpId" to rtp.id)
            val fields = "mid ssrc kind frameWidth frameHeight framesPerSecond framesEncoded framesSent framesReceived framesDecoded framesDropped bytesSent bytesReceived packetsSent packetsReceived packetsLost nackCount pliCount retransmittedBytesSent totalEncodeTime totalDecodeTime totalPacketSendDelay qualityLimitationReason qualityLimitationDurations qualityLimitationResolutionChanges targetBitrate encoderImplementation decoderImplementation powerEfficientEncoder powerEfficientDecoder freezeCount totalFreezesDuration jitter jitterBufferDelay jitterBufferEmittedCount".split(' ')
            fields.forEach { if (it in rtp.members) result[it] = rtp.members[it] }
            fun related(id: String, prefix: String, keys: String) {
                val item = (rtp.members[id] as? String)?.let { report[it] } ?: return
                keys.split(' ').forEach { result["$prefix.$it"] = item.members[it] }
            }
            related("mediaSourceId", "source", "frames framesPerSecond width height")
            related("codecId", "codec", "mimeType payloadType sdpFmtpLine")
            related("remoteId", "remote", "packetsLost fractionLost roundTripTime totalRoundTripTime roundTripTimeMeasurements")
            val transport = (rtp.members["transportId"] as? String)?.let { report[it] }
            val pair = (transport?.members?.get("selectedCandidatePairId") as? String)?.let { report[it] }
            listOf("iceState", "dtlsState", "selectedCandidatePairId").forEach { result["transport.$it"] = transport?.members?.get(it) }
            "availableOutgoingBitrate currentRoundTripTime bytesSent bytesReceived packetsDiscardedOnSend requestsSent responsesReceived state".split(' ').forEach { result["network.$it"] = pair?.members?.get(it) }
            for (side in listOf("local", "remote")) {
                val candidate = (pair?.members?.get("${side}CandidateId") as? String)?.let { report[it] }
                for (field in listOf("protocol", "candidateType", "relayProtocol", "tcpType")) result["$side.$field"] = candidate?.members?.get(field)
            }
            return result
        }
    }
}
