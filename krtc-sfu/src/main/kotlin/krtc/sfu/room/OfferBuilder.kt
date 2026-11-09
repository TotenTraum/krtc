package krtc.sfu.room

import krtc.protocol.rtp.AudioLevel
import krtc.protocol.sdp.Direction
import krtc.protocol.sdp.MediaDescription
import krtc.protocol.sdp.SdpAttribute
import krtc.protocol.sdp.SdpLine
import krtc.protocol.sdp.SessionDescription

/**
 * Builds the SFU's SDP offers. The SFU is always the offerer (JSEP §5.2), so it decides
 * payload types, header-extension ids and SSRCs, and can forward packets without renumbering them.
 *
 * Layout: m-line `0` receives the participant's microphone (recvonly on our side);
 * m-lines `1..n` each carry one other participant's audio (sendonly), or are `inactive` slots
 * left over from people who left and reused by the next joiner.
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
        sd.attributes += SdpAttribute("group", "BUNDLE " + (0..slots.size).joinToString(" "))
        sd.attributes += SdpAttribute("msid-semantic", " WMS *")
        sd.media += audio(p, mid = "0", direction = Direction.RECVONLY, publisher = null)
        slots.forEachIndexed { i, publisher ->
            sd.media += audio(p, mid = "${i + 1}", direction = if (publisher == null) Direction.INACTIVE else Direction.SENDONLY, publisher = publisher)
        }
        return sd
    }

    private fun audio(p: Participant, mid: String, direction: Direction, publisher: Participant?): MediaDescription {
        val m = MediaDescription("audio", port, "UDP/TLS/RTP/SAVPF", mutableListOf("${MediaPlan.OPUS_PT}"))
        m.lines += SdpLine('c', "IN IP4 $announcedAddress")
        val a = m.attributes
        a += SdpAttribute("ice-ufrag", p.iceUfrag)
        a += SdpAttribute("ice-pwd", p.icePwd)
        a += SdpAttribute("fingerprint", fingerprint)
        a += SdpAttribute("setup", "passive")
        a += SdpAttribute("candidate", "1 1 udp 2130706431 $announcedAddress $port typ host")
        a += SdpAttribute("end-of-candidates")
        a += SdpAttribute("mid", mid)
        a += SdpAttribute("extmap", "${MediaPlan.AUDIO_LEVEL_EXT_ID} ${AudioLevel.URI}")
        a += SdpAttribute(direction.sdp)
        a += SdpAttribute("rtcp-mux")
        a += SdpAttribute("rtpmap", "${MediaPlan.OPUS_PT} opus/48000/2")
        a += SdpAttribute("fmtp", "${MediaPlan.OPUS_PT} minptime=10;useinbandfec=1;usedtx=1")
        if (publisher != null) {
            val stream = "peer-${publisher.id}"
            a += SdpAttribute("msid", "$stream audio-${publisher.id}")
            a += SdpAttribute("ssrc", "${publisher.downlinkSsrc} cname:${publisher.cname}")
            a += SdpAttribute("ssrc", "${publisher.downlinkSsrc} msid:$stream audio-${publisher.id}")
        }
        return m
    }
}
