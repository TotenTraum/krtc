package krtc.meet

import krtc.core.RTCStats
import krtc.protocol.sdp.SessionDescription

/** Log actual RTP codec selection, not the first codec in an SDP preference list. */
internal class CodecDiagnostics {
    private var lastOffer: String? = null
    private var lastAnswer: String? = null
    private var offered = emptyMap<String, List<String>>()
    private var negotiated = emptyMap<String, List<String>>()
    private val selections = mutableMapOf<String, String>()

    fun sample(report: Map<String, RTCStats>, offer: String?, answer: String?): List<String> {
        if (offer != lastOffer) {
            offered = candidates(offer)
            lastOffer = offer
        }
        if (answer != lastAnswer) {
            negotiated = candidates(answer)
            lastAnswer = answer
        }
        val lines = mutableListOf<String>()
        val active = mutableSetOf<String>()
        for (rtp in report.values) {
            val outgoing = rtp.type == "outbound-rtp"
            if (!outgoing && rtp.type != "inbound-rtp") continue
            val codec = (rtp.members["codecId"] as? String)?.let(report::get) ?: continue
            val mime = codec.members["mimeType"] as? String ?: continue
            if (auxiliary(mime)) continue
            val mid = rtp.members["mid"] as? String ?: "unknown"
            val direction = if (outgoing) "out" else "in"
            val implementation = rtp.members[if (outgoing) "encoderImplementation" else "decoderImplementation"] ?: "unknown"
            val hardware = rtp.members[if (outgoing) "powerEfficientEncoder" else "powerEfficientDecoder"] ?: "unknown"
            val selected = describe(mime, codec.members["payloadType"], codec.members["sdpFmtpLine"] as? String)
            val details = "direction=$direction mid=$mid selected=$selected implementation=$implementation " +
                "powerEfficient=$hardware offered=${offered[mid] ?: "unknown"} negotiated=${negotiated[mid] ?: "unknown"}"
            active += rtp.id
            val previous = selections.put(rtp.id, details)
            if (previous != details) {
                lines += if (previous == null) "codec selected: $details" else "codec changed: $details; previous={$previous}"
            }
        }
        // A stopped/replaced RTP stream gets a fresh selection entry if it reappears.
        selections.keys.retainAll(active)
        return lines
    }

    companion object {
        fun selected(report: Map<String, RTCStats>, rtp: RTCStats): String {
            val codec = (rtp.members["codecId"] as? String)?.let(report::get) ?: return "unknown"
            return describe(codec.members["mimeType"] as? String ?: "unknown", codec.members["payloadType"], codec.members["sdpFmtpLine"] as? String)
        }

        private fun describe(mime: String, payload: Any?, fmtp: String?): String = buildString {
            append(mime)
            append("(pt=").append((payload as? Number)?.toInt() ?: payload ?: "unknown")
            if (!fmtp.isNullOrBlank()) append(", fmtp=").append(fmtp)
            append(')')
        }

        private fun auxiliary(mime: String): Boolean = mime.substringAfter('/').lowercase() in
            setOf("rtx", "red", "ulpfec", "flexfec-03", "telephone-event", "cn")

        private fun candidates(sdp: String?): Map<String, List<String>> {
            if (sdp == null) return emptyMap()
            return runCatching {
                SessionDescription.parse(sdp).media.mapNotNull { media ->
                    val mid = media.attribute("mid") ?: return@mapNotNull null
                    if (media.type != "video" && media.type != "audio") return@mapNotNull null
                    val mappings = media.attributes("rtpmap").associate { it.substringBefore(' ') to it.substringAfter(' ').substringBefore('/') }
                    val parameters = media.attributes("fmtp").associate { it.substringBefore(' ') to it.substringAfter(' ') }
                    mid to media.formats.mapNotNull codec@{ payload ->
                        val mime = mappings[payload]?.let { "${media.type}/$it" } ?: return@codec null
                        if (auxiliary(mime)) null else describe(mime, payload, parameters[payload])
                    }
                }.toMap()
            }.getOrDefault(emptyMap())
        }
    }
}
