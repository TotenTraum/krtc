package krtc.sfu.room

import krtc.protocol.rtp.Rtp
import krtc.protocol.rtp.RtpHeader
import krtc.protocol.rtp.Vp8
import krtc.sfu.platform.PeerAddress
import krtc.sfu.platform.nowMillis
import krtc.sfu.platform.secureRandomInt
import krtc.sfu.platform.secureRandomLong
import krtc.sfu.SfuParticipant
import krtc.sfu.SignalSink
import krtc.sfu.srtp.SrtpSessionKeys
import kotlin.concurrent.Volatile

/**
 * Rewrites one forwarded audio stream (SSRC, sequence number, timestamp) so subscribers see a
 * continuous stream under an SSRC the SFU chose, even when the publisher restarts its stream.
 * Shard thread only.
 */
internal class OutboundStream(public val ssrc: Long, private val clockRate: Int) {
    private var lastSourceSsrc = -1L
    private var seqOffset = 0
    private var tsOffset = 0L
    private var started = false
    internal var lastOutSeq: Int = 0; private set
    internal var lastOutTs: Long = 0; private set

    /** Rewrites the header of the plaintext packet in [buf] in place; returns the new sequence number. */
    internal fun rewrite(buf: ByteArray, h: RtpHeader): Int {
        if (h.ssrc != lastSourceSsrc) {
            if (lastSourceSsrc >= 0) {
                seqOffset = (lastOutSeq + 1 - h.sequenceNumber) and 0xFFFF
                tsOffset = (lastOutTs + clockRate / 50 - h.timestamp) and 0xFFFFFFFFL
            }
            lastSourceSsrc = h.ssrc
        }
        val seq = (h.sequenceNumber + seqOffset) and 0xFFFF
        val ts = (h.timestamp + tsOffset) and 0xFFFFFFFFL
        if (!started || Rtp.seqDelta(lastOutSeq, seq) > 0) {
            started = true
            lastOutSeq = seq
            lastOutTs = ts
        }
        Rtp.setSsrc(buf, 0, ssrc)
        Rtp.setSequenceNumber(buf, 0, seq)
        Rtp.setTimestamp(buf, 0, ts)
        return seq
    }

    internal fun mapTimestamp(sourceTs: Long): Long = (sourceTs + tsOffset) and 0xFFFFFFFFL
}

/** Ring buffer of recent packets keyed by sequence number. Allocation-free after warm-up. Shard thread only. */
internal class PacketCache(private val capacity: Int) {
    private val seqs = IntArray(capacity) { -1 }
    private val packets = arrayOfNulls<ByteArray>(capacity)
    private val lengths = IntArray(capacity)

    internal fun put(seq: Int, buf: ByteArray, length: Int) {
        val i = seq % capacity
        val slot = packets[i]?.takeIf { it.size >= length } ?: ByteArray(maxOf(length, 1400)).also { packets[i] = it }
        buf.copyInto(slot, 0, 0, length)
        lengths[i] = length
        seqs[i] = seq
    }

    internal fun packet(seq: Int): ByteArray? = if (seqs[seq % capacity] == seq) packets[seq % capacity] else null
    internal fun length(seq: Int): Int = lengths[seq % capacity]
}

/** One simulcast layer of a publisher's video uplink. Shard thread only. */
internal class VideoLayer(public val index: Int) {
    internal var ssrc: Long = -1; internal set
    internal var lastPacketMillis: Long = -1; internal set
    internal val cache = PacketCache(MediaPlan.VIDEO_CACHE_PACKETS)
    internal var lastKeyframeRequest = -1L

    // Incoming bitrate, for fitting layers to subscribers' bandwidth: bytes since the last tick, smoothed.
    private var bytesSinceTick = 0L
    internal var bitrateBps: Long = 0; private set

    internal fun countBytes(n: Int) {
        bytesSinceTick += n
    }

    internal fun updateBitrate(elapsedMillis: Long) {
        if (elapsedMillis <= 0) return
        val sample = bytesSinceTick * 8 * 1000 / elapsedMillis
        bitrateBps = if (bitrateBps == 0L) sample else (0.7 * bitrateBps + 0.3 * sample).toLong()
        bytesSinceTick = 0
    }

    // Upstream loss tracking (publisher → SFU), for NACKs to the publisher.
    private var highestSeq = -1
    private val missing = LinkedHashMap<Int, IntArray>() // seq → [attempts, last request time (ms) as int]

    /** Records an arriving packet; returns true if it revealed new gaps worth NACKing right away. */
    internal fun trackArrival(seq: Int, now: Long): Boolean {
        if (highestSeq < 0) {
            highestSeq = seq
            return false
        }
        val d = Rtp.seqDelta(highestSeq, seq)
        if (d <= 0) {
            missing.remove(seq) // late or retransmitted
            return false
        }
        var gap = false
        if (d in 2..MAX_GAP) {
            for (i in 1 until d) missing[(highestSeq + i) and 0xFFFF] = intArrayOf(0, 0)
            gap = true
            while (missing.size > MAX_MISSING) missing.remove(missing.keys.first())
        }
        highestSeq = seq
        return gap
    }

    internal fun isMissing(seq: Int): Boolean = seq in missing
    internal fun hasMissing(): Boolean = missing.isNotEmpty()

    /** Sequence numbers due for a (re)request now; gives up after a few attempts. */
    internal fun missingToRequest(now: Long): List<Int> {
        val nowInt = now.toInt()
        val out = ArrayList<Int>()
        val it = missing.entries.iterator()
        while (it.hasNext()) {
            val (seq, state) = it.next()
            if (state[0] >= MAX_NACKS) {
                it.remove()
                continue
            }
            if (state[0] == 0 || nowInt - state[1] >= NACK_RETRY_MILLIS) {
                state[0]++
                state[1] = nowInt
                out += seq
            }
        }
        return out
    }

    private companion object {
        const val MAX_GAP = 200
        const val MAX_MISSING = 256
        const val MAX_NACKS = 3
        const val NACK_RETRY_MILLIS = 100
    }

    internal fun isActive(now: Long): Boolean = lastPacketMillis >= 0 && now - lastPacketMillis < MediaPlan.LAYER_TIMEOUT_MILLIS
}

internal enum class VideoKind { CAMERA, SCREEN }

/**
 * One video uplink of a participant — its camera (up to three simulcast layers) or its screen share (one
 * stream, treated as the top layer) — and how it is forwarded: under the SFU's own SSRC and RTX SSRC.
 * Layer state is shard-thread only; [publishing] and [muted] are written by negotiation and the host.
 */
internal class VideoFeed(val publisher: Participant, val kind: VideoKind) {
    /** Identifies the feed in subscribers' maps: the publisher id for the camera, `id/screen` for the screen. */
    val key: String = if (kind == VideoKind.CAMERA) publisher.id else publisher.id + SCREEN_SUFFIX
    val ssrc: Long = Participant.randomSsrc()
    val rtxSsrc: Long = Participant.randomSsrc()
    val layers = Array(MediaPlan.LAYER_RIDS.size) { VideoLayer(it) }
    var rtxSeq = secureRandomInt(0x8000)

    @Volatile var publishing: Boolean = false
    @Volatile var muted: Boolean = false

    private val layerBySsrc = HashMap<Long, Int>()
    private val layerByRtxSsrc = HashMap<Long, Int>()

    /** The uplink layer a video packet belongs to: from its RID extension, else from its SSRC. */
    fun layerOf(ssrc: Long, rid: String?): VideoLayer {
        val index = if (rid != null) MediaPlan.layerOf(rid).also { layerBySsrc[ssrc] = it } else layerBySsrc.getOrPut(ssrc) { MediaPlan.TOP_LAYER }
        return layers[index]
    }

    fun layerBySsrc(ssrc: Long): VideoLayer? = layerBySsrc[ssrc]?.let { layers[it] }

    /** The layer an RTX stream repairs: from its repaired-RID, else remembered by SSRC, else the only active layer. */
    fun layerOfRtx(rtxSsrc: Long, repairedRid: String?): VideoLayer? {
        if (repairedRid != null) return layers[MediaPlan.layerOf(repairedRid).also { layerByRtxSsrc[rtxSsrc] = it }]
        layerByRtxSsrc[rtxSsrc]?.let { return layers[it] }
        return layers.filter { it.ssrc >= 0 }.singleOrNull()
    }

    override fun toString(): String = "VideoFeed($key)"

    internal companion object {
        const val SCREEN_SUFFIX = "/screen"
    }
}

/**
 * What one subscriber receives from one video feed: the chosen simulcast layer and an independent
 * numbering (seq, timestamp, VP8 PictureID/TL0PICIDX) that stays continuous across layer switches.
 * Also remembers which source packet went out under each sequence number, so NACKs are served from the
 * publisher's per-layer cache without a per-subscriber packet copy. Shard thread only.
 */
internal class DownlinkVideo {
    /** Layer the subscriber asked for (by default the top one); the SFU sends the best active layer ≤ it. */
    internal var preferred: Int = MediaPlan.TOP_LAYER

    /** Highest layer the subscriber's estimated bandwidth allows (bandwidth allocation in Room.tick). */
    internal var allowed: Int = MediaPlan.TOP_LAYER

    /** The layer cap actually applied: the subscriber's preference and its bandwidth. */
    internal val cap: Int get() = minOf(preferred, allowed)
    internal var current: Int = -1; internal set
    internal var target: Int = -1; internal set

    private var started = false
    private var seqOffset = 0
    internal var tsOffset = 0L; private set
    private var picIdOffset = 0
    private var tl0Offset = 0
    private var lastOutSeq = 0
    private var lastOutTs = 0L
    private var lastOutPicId = -1
    private var lastOutTl0 = -1

    private val historySize = MediaPlan.VIDEO_CACHE_PACKETS
    private val histOutSeq = IntArray(historySize) { -1 }
    private val histSource = IntArray(historySize) // layer shl 16 or source seq
    private val histTsOffset = LongArray(historySize)
    private val histPicOffsets = IntArray(historySize) // picIdOffset shl 8 or tl0Offset (low byte)

    /** Switches to [layer] at a key frame whose first packet has these source values. */
    internal fun switchTo(layer: Int, h: RtpHeader, d: Vp8.Descriptor?) {
        current = layer
        target = layer
        if (!started) return
        seqOffset = (lastOutSeq + 1 - h.sequenceNumber) and 0xFFFF
        tsOffset = (lastOutTs + 3000 - h.timestamp) and 0xFFFFFFFFL // ~33 ms at 90 kHz after the last frame sent
        if (d != null && d.pictureId >= 0 && lastOutPicId >= 0) picIdOffset = (lastOutPicId + 1 - d.pictureId) and 0x7FFF
        if (d != null && d.tl0PicIdx >= 0 && lastOutTl0 >= 0) tl0Offset = (lastOutTl0 + 1 - d.tl0PicIdx) and 0xFF
    }

    /**
     * Rewrites the plaintext packet in [buf] for this subscriber (original values come from [h] and [d],
     * not from the buffer, which other subscribers may already have rewritten) and records it for NACKs.
     */
    internal fun rewrite(buf: ByteArray, h: RtpHeader, d: Vp8.Descriptor?, payload: Int, ssrc: Long): Int {
        val seq = (h.sequenceNumber + seqOffset) and 0xFFFF
        val ts = (h.timestamp + tsOffset) and 0xFFFFFFFFL
        Rtp.setSsrc(buf, 0, ssrc)
        Rtp.setSequenceNumber(buf, 0, seq)
        Rtp.setTimestamp(buf, 0, ts)
        if (d != null && d.pictureId >= 0) {
            val pic = (d.pictureId + picIdOffset) and (if (d.pictureIdBits == 15) 0x7FFF else 0x7F)
            Vp8.setPictureId(buf, payload, d, pic)
            if (!started || Rtp.seqDelta(lastOutSeq, seq) > 0) lastOutPicId = pic
        }
        if (d != null && d.tl0PicIdx >= 0) {
            val tl0 = (d.tl0PicIdx + tl0Offset) and 0xFF
            Vp8.setTl0PicIdx(buf, payload, d, tl0)
            if (!started || Rtp.seqDelta(lastOutSeq, seq) > 0) lastOutTl0 = tl0
        }
        if (!started || Rtp.seqDelta(lastOutSeq, seq) > 0) {
            lastOutSeq = seq
            lastOutTs = ts
        }
        started = true
        val i = seq % historySize
        histOutSeq[i] = seq
        histSource[i] = (current shl 16) or h.sequenceNumber
        histTsOffset[i] = tsOffset
        histPicOffsets[i] = (picIdOffset shl 8) or tl0Offset
        return seq
    }

    /** For a NACKed [outSeq]: (layer, source seq), or null if it is no longer remembered. */
    internal fun source(outSeq: Int): Int? = (outSeq % historySize).let { if (histOutSeq[it] == outSeq) histSource[it] else null }
    internal fun sentTsOffset(outSeq: Int): Long = histTsOffset[outSeq % historySize]
    internal fun sentPicIdOffset(outSeq: Int): Int = histPicOffsets[outSeq % historySize] ushr 8
    internal fun sentTl0Offset(outSeq: Int): Int = histPicOffsets[outSeq % historySize] and 0xFF
}

/**
 * A member of a room. Fields are grouped by owning thread:
 * - transport (UDP thread, volatile): remote address, consent time;
 * - media (room's shard thread only): keys, uplink SSRCs and layers, downlink state, speaking;
 * - negotiation results (volatile snapshots written by Sfu.PeerSession).
 */
internal class Participant(
    override val id: String,
    override val name: String,
    internal val room: Room,
    internal val sink: SignalSink,
) : SfuParticipant {
    override val roomName: String get() = room.name

    /** Server-side mute: the SFU stops forwarding this participant's audio / video. */
    @Volatile internal var audioMuted: Boolean = false

    // ICE-lite credentials (RFC 8445 §5.3: ufrag ≥ 4 chars, pwd ≥ 22 chars of ice-char). Replaced on ICE restart;
    // the previous pair stays valid until the client's checks switch to the new one.
    @Volatile internal var iceUfrag: String = randomIceString(8); private set
    @Volatile internal var icePwd: String = randomIceString(24); private set
    @Volatile internal var icePwdBytes: ByteArray = icePwd.encodeToByteArray(); private set
    @Volatile internal var previousIceUfrag: String? = null; private set
    @Volatile internal var previousIcePwdBytes: ByteArray? = null; private set

    /** New ICE credentials for an ICE restart; the old ones are kept as "previous". */
    internal fun restartIce() {
        previousIceUfrag = iceUfrag
        previousIcePwdBytes = icePwdBytes
        iceUfrag = randomIceString(8)
        icePwd = randomIceString(24)
        icePwdBytes = icePwd.encodeToByteArray()
    }

    /** The password for checks that use [ufrag] (current or previous), or null if it is not ours. */
    internal fun icePasswordFor(ufrag: String): ByteArray? = when (ufrag) {
        iceUfrag -> icePwdBytes
        previousIceUfrag -> previousIcePwdBytes
        else -> null
    }

    internal fun forgetPreviousIce() {
        previousIceUfrag = null
        previousIcePwdBytes = null
    }
    internal val cname: String = randomIceString(16)

    /** This participant's media as forwarded to everyone else, under SSRCs the SFU chose. */
    internal val audioOut: OutboundStream = OutboundStream(randomSsrc(), 48_000)

    /** Its screen share's audio (system or tab sound), forwarded like the microphone but without speaking detection. */
    internal val screenAudioOut: OutboundStream = OutboundStream(randomSsrc(), 48_000)
    @Volatile internal var screenAudioPublishing: Boolean = false
    @Volatile internal var screenAudioMuted: Boolean = false

    /** SSRCs the answer declared for the screen audio uplink. */
    @Volatile internal var screenAudioSsrcs: Set<Long> = emptySet()

    /** Publishers whose screen audio m-lines this participant has accepted (last applied answer). */
    @Volatile internal var receivingScreenAudio: Set<String> = emptySet()

    /** Its video uplinks: the camera (simulcast) and a screen share (one layer). */
    internal val camera: VideoFeed = VideoFeed(this, VideoKind.CAMERA)
    internal val screen: VideoFeed = VideoFeed(this, VideoKind.SCREEN)
    internal val feeds: List<VideoFeed> = listOf(camera, screen)
    internal val videoSsrc: Long get() = camera.ssrc
    internal val videoRtxSsrc: Long get() = camera.rtxSsrc

    @Volatile internal var remote: PeerAddress? = null
    @Volatile internal var lastConsentMillis: Long = nowMillis()

    /** Whether this participant sends camera video (its answer accepted the video uplink m-line). */
    internal var publishesVideo: Boolean
        get() = camera.publishing
        set(value) { camera.publishing = value }

    /** Server-side mute of the camera. */
    internal var videoMuted: Boolean
        get() = camera.muted
        set(value) { camera.muted = value }

    /** Publishers whose audio m-lines, and video feeds ([VideoFeed.key]), this participant has accepted (last applied answer). */
    @Volatile internal var receiving: Set<String> = emptySet()
    @Volatile internal var receivingVideo: Set<String> = emptySet()

    // --- media state, shard thread only ---
    internal var keys: SrtpSessionKeys? = null
    internal var uplinkAudioSsrc = -1L
    internal var uplinkScreenAudioSsrc = -1L
    internal val layers: Array<VideoLayer> get() = camera.layers
    internal val downlinks = HashMap<String, DownlinkVideo>() // by feed key
    private val feedBySsrc = HashMap<Long, VideoFeed>()

    /** Uplink transport-cc: arrival times of this participant's packets, fed back to it. */
    internal val twccIn = TwccReceiver()

    /** Downlink transport-cc: send history and the bandwidth estimate towards this participant. */
    internal val bwe = SendSideBwe()

    /** Testing only (Room.simulatedDownlinkBps): token bucket of the simulated downlink, in bytes. */
    internal var shapingTokens = 0.0
    internal var shapingLastMicros = -1L
    internal var speaking = false
    internal var lastLoudMillis = 0L

    /** Its answer accepted the data channel m-line (written by Sfu.PeerSession). */
    @Volatile internal var dataNegotiated: Boolean = false

    // --- data channels, shard thread only (DataRelay) ---
    internal var data: krtc.sfu.sctp.DataChannels? = null
    internal var reliableChannel: krtc.sfu.sctp.DataChannel? = null
    internal var lossyChannel: krtc.sfu.sctp.DataChannel? = null

    /** Installs SRTP/SRTCP keys on the owning shard; media starts flowing afterwards. */
    internal fun installKeys(k: SrtpSessionKeys) {
        room.shard.execute { keys = k }
    }

    /**
     * The feed an uplink video (or RTX) packet belongs to: by its MID extension, else by SSRCs from the answer
     * (screen share), else as learned earlier; the camera by default.
     */
    internal fun feedOf(ssrc: Long, mid: String?): VideoFeed {
        val byMid = when (mid) {
            MediaPlan.UPLINK_VIDEO_MID -> camera
            MediaPlan.UPLINK_SCREEN_MID -> screen
            else -> null
        }
        if (byMid != null) {
            feedBySsrc[ssrc] = byMid
            return byMid
        }
        return feedBySsrc[ssrc] ?: (if (ssrc in screenSsrcs) screen else camera).also { feedBySsrc[ssrc] = it }
    }

    /** SSRCs (media and RTX) the answer declared for the screen uplink. */
    @Volatile internal var screenSsrcs: Set<Long> = emptySet()

    internal fun layerBySsrc(ssrc: Long): Pair<VideoFeed, VideoLayer>? =
        feeds.firstNotNullOfOrNull { f -> f.layerBySsrc(ssrc)?.let { f to it } }

    internal fun downlinkFrom(feedKey: String): DownlinkVideo = downlinks.getOrPut(feedKey) { DownlinkVideo() }

    override fun toString(): String = "Participant($id, $name)"

    internal companion object {
        const val ICE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        fun randomIceString(n: Int) = buildString { repeat(n) { append(ICE_CHARS[secureRandomInt(ICE_CHARS.length)]) } }
        fun randomSsrc(): Long = (secureRandomLong() and 0x7FFFFFFFL) or 1L
    }
}
