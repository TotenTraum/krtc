package krtc.sfu.room

import krtc.protocol.rtp.AudioLevel
import krtc.protocol.sdp.Direction
import krtc.protocol.sdp.MediaDescription
import krtc.protocol.sdp.SdpAttribute
import krtc.protocol.sdp.SdpLine
import krtc.protocol.sdp.SessionDescription

/**
 * Builds the SFU's SDP offers. The SFU is always the offerer (JSEP §5.2), so it decides payload types,
 * header-extension ids and SSRCs, and forwards packets without renumbering payload types.
 *
 * Layout for a participant:
 * - `0` audio, `1` video — its own uplink (recvonly on the SFU side);
 * - per slot i: `2+2i` audio and `3+2i` video of one other participant (sendonly), or `inactive` when
 *   the slot is free (someone left) or that participant sends no video. Free slots are reused.
 */
public class OfferBuilder(
    private val announcedAddress: String,
    private val port: Int,
    private val fingerprint: String,
) {
    public fun build(p: Participant, sessionId: Long, version: Long, slots: List<Participant?>): SessionDescription {
        val sd = SessionDescription()
        sd.lines += SdpLine('v', "0")
        sd.lines += SdpLine('o', "- $sessionId $version IN IP4 0.0.0.0")
        sd.lines += SdpLine('s', "krtc-sfu")
        sd.lines += SdpLine('t', "0 0")
        sd.attributes += SdpAttribute("ice-lite")
        sd.attributes += SdpAttribute("group", "BUNDLE " + (0 until 2 + 2 * slots.size).joinToString(" "))
        sd.attributes += SdpAttribute("msid-semantic", " WMS *")
        sd.media += audio(p, mid = "0", direction = Direction.RECVONLY, publisher = null)
        sd.media += video(p, mid = "1", direction = Direction.RECVONLY, publisher = null)
        slots.forEachIndexed { i, publisher ->
            sd.media += audio(p, "${2 + 2 * i}", if (publisher == null) Direction.INACTIVE else Direction.SENDONLY, publisher)
            val sendsVideo = publisher != null && publisher.publishesVideo
            sd.media += video(p, "${3 + 2 * i}", if (sendsVideo) Direction.SENDONLY else Direction.INACTIVE, publisher.takeIf { sendsVideo })
        }
        return sd
    }

    private fun transport(p: Participant, m: MediaDescription, mid: String) {
        m.lines += SdpLine('c', "IN IP4 $announcedAddress")
        val a = m.attributes
        a += SdpAttribute("ice-ufrag", p.iceUfrag)
        a += SdpAttribute("ice-pwd", p.icePwd)
        a += SdpAttribute("fingerprint", fingerprint)
        a += SdpAttribute("setup", "passive")
        a += SdpAttribute("candidate", "1 1 udp 2130706431 $announcedAddress $port typ host")
        a += SdpAttribute("end-of-candidates")
        a += SdpAttribute("mid", mid)
    }

    private fun audio(p: Participant, mid: String, direction: Direction, publisher: Participant?): MediaDescription {
        val m = MediaDescription("audio", port, "UDP/TLS/RTP/SAVPF", mutableListOf("${MediaPlan.OPUS_PT}"))
        transport(p, m, mid)
        val a = m.attributes
        a += SdpAttribute("extmap", "${MediaPlan.AUDIO_LEVEL_EXT_ID} ${AudioLevel.URI}")
        a += SdpAttribute(direction.sdp)
        a += SdpAttribute("rtcp-mux")
        a += SdpAttribute("rtcp-rsize")
        a += SdpAttribute("rtpmap", "${MediaPlan.OPUS_PT} opus/48000/2")
        a += SdpAttribute("fmtp", "${MediaPlan.OPUS_PT} minptime=10;useinbandfec=1;usedtx=1")
        if (publisher != null) {
            val (stream, track) = msid(publisher, "audio")
            a += SdpAttribute("msid", "$stream $track")
            a += SdpAttribute("ssrc", "${publisher.audioOut.ssrc} cname:${publisher.cname}")
            a += SdpAttribute("ssrc", "${publisher.audioOut.ssrc} msid:$stream $track")
        }
        return m
    }

    private fun video(p: Participant, mid: String, direction: Direction, publisher: Participant?): MediaDescription {
        val pts = listOf(MediaPlan.VP8_PT, MediaPlan.VP8_RTX_PT, MediaPlan.H264_PT, MediaPlan.H264_RTX_PT)
        val m = MediaDescription("video", port, "UDP/TLS/RTP/SAVPF", pts.map { "$it" }.toMutableList())
        transport(p, m, mid)
        val a = m.attributes
        a += SdpAttribute(direction.sdp)
        a += SdpAttribute("rtcp-mux")
        a += SdpAttribute("rtcp-rsize")
        for ((pt, codec, fmtp) in listOf(
            Triple(MediaPlan.VP8_PT, "VP8/90000", null),
            Triple(MediaPlan.H264_PT, "H264/90000", "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f"),
        )) {
            a += SdpAttribute("rtpmap", "$pt $codec")
            fmtp?.let { a += SdpAttribute("fmtp", "$pt $it") }
            a += SdpAttribute("rtcp-fb", "$pt goog-remb")
            a += SdpAttribute("rtcp-fb", "$pt nack")
            a += SdpAttribute("rtcp-fb", "$pt nack pli")
            a += SdpAttribute("rtcp-fb", "$pt ccm fir")
            val rtx = MediaPlan.rtxFor(pt)
            a += SdpAttribute("rtpmap", "$rtx rtx/90000")
            a += SdpAttribute("fmtp", "$rtx apt=$pt")
        }
        if (publisher != null) {
            val (stream, track) = msid(publisher, "video")
            a += SdpAttribute("msid", "$stream $track")
            a += SdpAttribute("ssrc-group", "FID ${publisher.videoOut.ssrc} ${publisher.videoRtxSsrc}")
            for (ssrc in listOf(publisher.videoOut.ssrc, publisher.videoRtxSsrc)) {
                a += SdpAttribute("ssrc", "$ssrc cname:${publisher.cname}")
                a += SdpAttribute("ssrc", "$ssrc msid:$stream $track")
            }
        }
        return m
    }

    /** One MediaStream per publisher, so the browser lip-syncs its audio and video tracks. */
    private fun msid(publisher: Participant, kind: String) = "peer-${publisher.id}" to "$kind-${publisher.id}"
}
