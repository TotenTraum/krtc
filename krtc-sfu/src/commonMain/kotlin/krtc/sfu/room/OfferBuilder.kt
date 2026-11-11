package krtc.sfu.room

import krtc.protocol.rtp.AudioLevel
import krtc.protocol.rtp.RtpStreamId
import krtc.protocol.rtp.TransportCc
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
 * - `0` audio, `1` video — its own uplink (recvonly on the SFU side; video asks for simulcast q/h/f);
 * - `d` data channels (SCTP), when enabled;
 * - `s` its screen share uplink (one stream, no simulcast), when enabled;
 * - per slot i: `2+2i` audio and `3+2i` video of one other participant (sendonly), or `inactive` when
 *   the slot is free (someone left) or that participant sends no video; `s<i>` its screen share, when enabled.
 *   Free slots are reused.
 */
internal class OfferBuilder(
    /** Host candidate addresses, most preferred first. */
    private val announcedAddresses: List<String>,
    private val port: Int,
    private val fingerprint: String,
    /** Offer UDP host candidates. */
    private val udp: Boolean = true,
    /** Offer passive TCP host candidates (RFC 6544) on the same port. */
    private val tcp: Boolean = false,
    /** Offer an SCTP association for data channels as the third m-line ([MediaPlan.DATA_MID]). */
    private val dataChannels: Boolean = false,
    /** Offer a screen share uplink ([MediaPlan.UPLINK_SCREEN_MID]) and a screen m-line in every slot. */
    private val screenShare: Boolean = false,
) {
    internal fun build(p: Participant, sessionId: Long, version: Long, slots: List<Participant?>): SessionDescription {
        val sd = SessionDescription()
        sd.lines += SdpLine('v', "0")
        sd.lines += SdpLine('o', "- $sessionId $version IN IP4 0.0.0.0")
        sd.lines += SdpLine('s', "krtc-sfu")
        sd.lines += SdpLine('t', "0 0")
        sd.attributes += SdpAttribute("ice-lite")
        val mids = buildList {
            add(MediaPlan.UPLINK_AUDIO_MID)
            add(MediaPlan.UPLINK_VIDEO_MID)
            if (dataChannels) add(MediaPlan.DATA_MID)
            if (screenShare) {
                add(MediaPlan.UPLINK_SCREEN_MID)
                add(MediaPlan.UPLINK_SCREEN_AUDIO_MID)
            }
            for (i in slots.indices) {
                add("${2 + 2 * i}")
                add("${3 + 2 * i}")
                if (screenShare) {
                    add(MediaPlan.screenMid(i))
                    add(MediaPlan.screenAudioMid(i))
                }
            }
        }
        sd.attributes += SdpAttribute("group", "BUNDLE " + mids.joinToString(" "))
        sd.attributes += SdpAttribute("msid-semantic", " WMS *")
        sd.media += audio(p, mid = MediaPlan.UPLINK_AUDIO_MID, direction = Direction.RECVONLY, publisher = null, screen = false)
        sd.media += video(p, mid = MediaPlan.UPLINK_VIDEO_MID, direction = Direction.RECVONLY, feed = null, simulcast = true)
        if (dataChannels) sd.media += application(p)
        if (screenShare) {
            sd.media += video(p, mid = MediaPlan.UPLINK_SCREEN_MID, direction = Direction.RECVONLY, feed = null, simulcast = false)
            sd.media += audio(p, mid = MediaPlan.UPLINK_SCREEN_AUDIO_MID, direction = Direction.RECVONLY, publisher = null, screen = true)
        }
        slots.forEachIndexed { i, publisher ->
            sd.media += audio(p, "${2 + 2 * i}", if (publisher == null) Direction.INACTIVE else Direction.SENDONLY, publisher, screen = false)
            val kinds = if (screenShare) listOf(VideoKind.CAMERA, VideoKind.SCREEN) else listOf(VideoKind.CAMERA)
            for (kind in kinds) {
                val feed = (if (kind == VideoKind.CAMERA) publisher?.camera else publisher?.screen)?.takeIf { it.publishing }
                val mid = if (kind == VideoKind.CAMERA) "${3 + 2 * i}" else MediaPlan.screenMid(i)
                sd.media += video(p, mid, if (feed != null) Direction.SENDONLY else Direction.INACTIVE, feed, simulcast = false)
            }
            if (screenShare) {
                val sound = publisher?.takeIf { it.screenAudioPublishing }
                sd.media += audio(p, MediaPlan.screenAudioMid(i), if (sound != null) Direction.SENDONLY else Direction.INACTIVE, sound, screen = true)
            }
        }
        return sd
    }

    private fun transport(p: Participant, m: MediaDescription, mid: String) {
        val first = announcedAddresses.first()
        m.lines += SdpLine('c', "IN ${if (':' in first) "IP6" else "IP4"} $first")
        val a = m.attributes
        a += SdpAttribute("ice-ufrag", p.iceUfrag)
        a += SdpAttribute("ice-pwd", p.icePwd)
        a += SdpAttribute("fingerprint", fingerprint)
        a += SdpAttribute("setup", "passive")
        announcedAddresses.forEachIndexed { i, address ->
            // RFC 8445 §5.1.2: type preference 126 (host), local preference by order, component 1.
            // TCP gets a lower type preference so clients use it only when UDP fails.
            if (udp) a += SdpAttribute("candidate", "${1 + i} 1 udp ${priority(126, i)} $address $port typ host")
            if (tcp) a += SdpAttribute("candidate", "${101 + i} 1 tcp ${priority(80, i)} $address $port typ host tcptype passive")
        }
        a += SdpAttribute("end-of-candidates")
        a += SdpAttribute("mid", mid)
    }

    /** RFC 8841: SCTP over DTLS, bundled with the media; the browser is the DTLS client, so it uses even stream ids. */
    private fun application(p: Participant): MediaDescription {
        val m = MediaDescription("application", port, "UDP/DTLS/SCTP", mutableListOf("webrtc-datachannel"))
        transport(p, m, MediaPlan.DATA_MID)
        m.attributes += SdpAttribute("sctp-port", "${MediaPlan.SCTP_PORT}")
        m.attributes += SdpAttribute("max-message-size", "${krtc.signaling.DataEnvelope.MAX_MESSAGE_SIZE}")
        return m
    }

    private fun audio(p: Participant, mid: String, direction: Direction, publisher: Participant?, screen: Boolean): MediaDescription {
        val m = MediaDescription("audio", port, "UDP/TLS/RTP/SAVPF", mutableListOf("${MediaPlan.OPUS_PT}"))
        transport(p, m, mid)
        val a = m.attributes
        // Uplinks carry MID so the SFU tells the microphone from the screen audio (renamed away before forwarding).
        if (publisher == null && direction == Direction.RECVONLY) a += SdpAttribute("extmap", "${MediaPlan.MID_EXT_ID} ${RtpStreamId.MID_URI}")
        a += SdpAttribute("extmap", "${MediaPlan.AUDIO_LEVEL_EXT_ID} ${AudioLevel.URI}")
        a += SdpAttribute("extmap", "${MediaPlan.TRANSPORT_CC_EXT_ID} ${TransportCc.URI}")
        a += SdpAttribute(direction.sdp)
        a += SdpAttribute("rtcp-mux")
        a += SdpAttribute("rtcp-rsize")
        a += SdpAttribute("rtpmap", "${MediaPlan.OPUS_PT} opus/48000/2")
        a += SdpAttribute("fmtp", "${MediaPlan.OPUS_PT} minptime=10;useinbandfec=1;usedtx=1")
        a += SdpAttribute("rtcp-fb", "${MediaPlan.OPUS_PT} transport-cc")
        if (publisher != null) {
            // Screen audio shares the screen's MediaStream, so receivers keep picture and sound in sync.
            val (stream, track) = if (screen) "screen-${publisher.id}" to "screen-audio-${publisher.id}" else msid(publisher, "audio")
            val ssrc = if (screen) publisher.screenAudioOut.ssrc else publisher.audioOut.ssrc
            a += SdpAttribute("msid", "$stream $track")
            a += SdpAttribute("ssrc", "$ssrc cname:${publisher.cname}")
            a += SdpAttribute("ssrc", "$ssrc msid:$stream $track")
        }
        return m
    }

    private fun video(p: Participant, mid: String, direction: Direction, feed: VideoFeed?, simulcast: Boolean): MediaDescription {
        val pts = listOf(MediaPlan.VP8_PT, MediaPlan.VP8_RTX_PT, MediaPlan.H264_PT, MediaPlan.H264_RTX_PT)
        val m = MediaDescription("video", port, "UDP/TLS/RTP/SAVPF", pts.map { "$it" }.toMutableList())
        transport(p, m, mid)
        val a = m.attributes
        val uplink = feed == null && direction == Direction.RECVONLY
        if (uplink) {
            // The SFU's own MID/RID ids; renamed away before forwarding (MediaPlan.IGNORED_EXT_ID).
            a += SdpAttribute("extmap", "${MediaPlan.MID_EXT_ID} ${RtpStreamId.MID_URI}")
            a += SdpAttribute("extmap", "${MediaPlan.RID_EXT_ID} ${RtpStreamId.RID_URI}")
            a += SdpAttribute("extmap", "${MediaPlan.REPAIRED_RID_EXT_ID} ${RtpStreamId.REPAIRED_RID_URI}")
        }
        a += SdpAttribute("extmap", "${MediaPlan.TRANSPORT_CC_EXT_ID} ${TransportCc.URI}")
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
            a += SdpAttribute("rtcp-fb", "$pt transport-cc")
            a += SdpAttribute("rtcp-fb", "$pt nack")
            a += SdpAttribute("rtcp-fb", "$pt nack pli")
            a += SdpAttribute("rtcp-fb", "$pt ccm fir")
            val rtx = MediaPlan.rtxFor(pt)
            a += SdpAttribute("rtpmap", "$rtx rtx/90000")
            a += SdpAttribute("fmtp", "$rtx apt=$pt")
        }
        if (uplink && simulcast) {
            // Ask for simulcast: the browser creates one encoding per rid, scaled ¼, ½ and 1 (MediaPlan.LAYER_RIDS).
            MediaPlan.LAYER_RIDS.forEach { a += SdpAttribute("rid", "$it recv") }
            a += SdpAttribute("simulcast", "recv " + MediaPlan.LAYER_RIDS.joinToString(";"))
        }
        if (feed != null) {
            val publisher = feed.publisher
            // The camera shares a MediaStream with the microphone (lip sync); the screen is a stream of its own.
            val (stream, track) = if (feed.kind == VideoKind.CAMERA) msid(publisher, "video") else "screen-${publisher.id}" to "screen-${publisher.id}"
            a += SdpAttribute("msid", "$stream $track")
            a += SdpAttribute("ssrc-group", "FID ${feed.ssrc} ${feed.rtxSsrc}")
            for (ssrc in listOf(feed.ssrc, feed.rtxSsrc)) {
                a += SdpAttribute("ssrc", "$ssrc cname:${publisher.cname}")
                a += SdpAttribute("ssrc", "$ssrc msid:$stream $track")
            }
        }
        return m
    }

    private fun priority(typePreference: Int, index: Int): Long = (typePreference.toLong() shl 24) + ((65535L - index) shl 8) + 255

    /** One MediaStream per publisher, so the browser lip-syncs its audio and video tracks. */
    private fun msid(publisher: Participant, kind: String) = "peer-${publisher.id}" to "$kind-${publisher.id}"
}
