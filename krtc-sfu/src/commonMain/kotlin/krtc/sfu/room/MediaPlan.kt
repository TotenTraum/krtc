package krtc.sfu.room

/** Constants of the media plan the SFU dictates in its offers (it is always the offerer). */
public object MediaPlan {
    public const val OPUS_PT: Int = 111
    public const val VP8_PT: Int = 96
    public const val VP8_RTX_PT: Int = 97
    public const val H264_PT: Int = 102
    public const val H264_RTX_PT: Int = 103

    // Header extension ids. MID/RID/repaired-RID appear only on the video uplink m-line.
    public const val AUDIO_LEVEL_EXT_ID: Int = 1
    public const val MID_EXT_ID: Int = 2
    public const val RID_EXT_ID: Int = 3
    public const val REPAIRED_RID_EXT_ID: Int = 4

    /** Unnegotiated id the uplink-only extensions are renamed to before forwarding, so subscribers ignore them. */
    public const val IGNORED_EXT_ID: Int = 14

    /**
     * Simulcast layers in the order offered in `a=simulcast:recv q;h;f`. Per the WebRTC spec the
     * answerer scales the i-th of N encodings down by 2^(N-1-i), so q = ¼, h = ½, f = full resolution.
     * A publisher without simulcast sends a single stream, treated as the top layer.
     */
    public val LAYER_RIDS: List<String> = listOf("q", "h", "f")
    public const val TOP_LAYER: Int = 2

    /** -dBov at or below which a packet counts as speech (RFC 6464: 0 loudest, 127 silent). */
    public const val SPEAKING_LEVEL: Int = 50
    public const val SPEAKING_HOLD_MILLIS: Long = 400
    public const val CONSENT_TIMEOUT_MILLIS: Long = 30_000

    /** Total bitrate the SFU allows each video publisher across its layers (sent as REMB). */
    public const val VIDEO_BITRATE: Long = 2_500_000
    public const val KEYFRAME_MIN_INTERVAL_MILLIS: Long = 300
    public const val REMB_INTERVAL_MILLIS: Long = 1_000

    /** A layer that sent nothing for this long is considered stopped (e.g. the publisher dropped it for bandwidth). */
    public const val LAYER_TIMEOUT_MILLIS: Long = 1_000
    public const val VIDEO_CACHE_PACKETS: Int = 512

    /** SSRC the SFU uses as the sender of its own RTCP (feedback to publishers): "krtc". */
    public const val SFU_SSRC: Long = 0x6b727463L

    public fun isVideo(pt: Int): Boolean = pt == VP8_PT || pt == H264_PT
    public fun rtxFor(pt: Int): Int = if (pt == H264_PT) H264_RTX_PT else VP8_RTX_PT
    public fun layerOf(rid: String): Int = LAYER_RIDS.indexOf(rid).let { if (it < 0) TOP_LAYER else it }
}
