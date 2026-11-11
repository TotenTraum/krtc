package krtc.sfu.room

/** Constants of the media plan the SFU dictates in its offers (it is always the offerer). */
internal object MediaPlan {
    internal const val OPUS_PT: Int = 111
    internal const val VP8_PT: Int = 96
    internal const val VP8_RTX_PT: Int = 97
    internal const val H264_PT: Int = 102
    internal const val H264_RTX_PT: Int = 103

    // Header extension ids. MID/RID/repaired-RID appear only on the video uplink m-line.
    internal const val AUDIO_LEVEL_EXT_ID: Int = 1
    internal const val MID_EXT_ID: Int = 2
    internal const val RID_EXT_ID: Int = 3
    internal const val REPAIRED_RID_EXT_ID: Int = 4

    /** Transport-wide sequence number (transport-cc), on every m-line in both directions. */
    internal const val TRANSPORT_CC_EXT_ID: Int = 5
    internal const val TWCC_FEEDBACK_INTERVAL_MILLIS: Long = 50

    /** Uplink m-lines of every offer: microphone, camera (simulcast), screen share (one layer). */
    internal const val UPLINK_AUDIO_MID: String = "0"
    internal const val UPLINK_VIDEO_MID: String = "1"
    internal const val UPLINK_SCREEN_MID: String = "s"

    /** Uplink m-line of the screen share's audio (system or tab sound), next to [UPLINK_SCREEN_MID]. */
    internal const val UPLINK_SCREEN_AUDIO_MID: String = "sa"

    /** The screen share of the participant in downlink slot [slot]. */
    internal fun screenMid(slot: Int): String = "s$slot"

    /** The screen share's audio of the participant in downlink slot [slot]. */
    internal fun screenAudioMid(slot: Int): String = "sa$slot"

    /** The data channel m-line (SCTP over DTLS, RFC 8841), third in every offer when data channels are on. */
    internal const val DATA_MID: String = "d"
    internal const val SCTP_PORT: Int = 5000

    /** How often SCTP timers (retransmission, partial reliability, stream reset) run. */
    internal const val SCTP_TICK_MILLIS: Long = 50

    /** Downlink budget kept for each forwarded audio stream when fitting video layers to the estimate. */
    internal const val AUDIO_RESERVE_BPS: Long = 64_000

    /** Upgrade a layer only if everything then fits in this share of the estimate (hysteresis). */
    internal const val UPGRADE_HEADROOM: Double = 0.85

    /** Unnegotiated id the uplink-only extensions are renamed to before forwarding, so subscribers ignore them. */
    internal const val IGNORED_EXT_ID: Int = 14

    /**
     * Simulcast layers in the order offered in `a=simulcast:recv q;h;f`. Per the WebRTC spec the
     * answerer scales the i-th of N encodings down by 2^(N-1-i), so q = ¼, h = ½, f = full resolution.
     * A publisher without simulcast sends a single stream, treated as the top layer.
     */
    internal val LAYER_RIDS: List<String> = listOf("q", "h", "f")
    internal const val TOP_LAYER: Int = 2

    /** -dBov at or below which a packet counts as speech (RFC 6464: 0 loudest, 127 silent). */
    internal const val SPEAKING_LEVEL: Int = 50
    internal const val SPEAKING_HOLD_MILLIS: Long = 400
    internal const val CONSENT_TIMEOUT_MILLIS: Long = 30_000

    /** Total bitrate the SFU allows each video publisher across its layers (sent as REMB). */
    internal const val VIDEO_BITRATE: Long = 2_500_000
    internal const val KEYFRAME_MIN_INTERVAL_MILLIS: Long = 300
    internal const val REMB_INTERVAL_MILLIS: Long = 1_000

    /** A layer that sent nothing for this long is considered stopped (e.g. the publisher dropped it for bandwidth). */
    internal const val LAYER_TIMEOUT_MILLIS: Long = 1_000
    internal const val VIDEO_CACHE_PACKETS: Int = 512

    /** SSRC the SFU uses as the sender of its own RTCP (feedback to publishers): "krtc". */
    internal const val SFU_SSRC: Long = 0x6b727463L

    internal fun isVideo(pt: Int): Boolean = pt == VP8_PT || pt == H264_PT
    internal fun isRtx(pt: Int): Boolean = pt == VP8_RTX_PT || pt == H264_RTX_PT
    internal fun aptFor(rtxPt: Int): Int = if (rtxPt == H264_RTX_PT) H264_PT else VP8_PT
    internal fun rtxFor(pt: Int): Int = if (pt == H264_PT) H264_RTX_PT else VP8_RTX_PT
    internal fun layerOf(rid: String): Int = LAYER_RIDS.indexOf(rid).let { if (it < 0) TOP_LAYER else it }
}
