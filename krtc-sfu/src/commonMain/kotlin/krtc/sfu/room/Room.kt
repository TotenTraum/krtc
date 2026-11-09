package krtc.sfu.room

import kotlin.concurrent.atomics.incrementAndFetch
import krtc.protocol.rtp.AudioLevel
import krtc.protocol.rtp.Rtcp
import krtc.protocol.rtp.Rtp
import krtc.protocol.rtp.RtpHeader
import krtc.sfu.platform.PeerAddress
import krtc.sfu.platform.UdpServer
import krtc.sfu.platform.nowMillis
import krtc.sfu.platform.secureRandomInt
import krtc.sfu.platform.secureRandomLong
import krtc.sfu.srtp.SrtpSessionKeys
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/** Outbound signaling channel of a participant (implemented by the WebSocket layer). Must not block. */
public interface SignalSink {
    public fun send(message: String)
    public fun close()
}

/** Constants of the media plan the SFU dictates in its offers (it is always the offerer). */
public object MediaPlan {
    public const val OPUS_PT: Int = 111
    public const val VP8_PT: Int = 96
    public const val VP8_RTX_PT: Int = 97
    public const val H264_PT: Int = 102
    public const val H264_RTX_PT: Int = 103
    public const val AUDIO_LEVEL_EXT_ID: Int = 1

    /** -dBov at or below which a packet counts as speech (RFC 6464: 0 loudest, 127 silent). */
    public const val SPEAKING_LEVEL: Int = 50
    public const val SPEAKING_HOLD_MILLIS: Long = 400
    public const val CONSENT_TIMEOUT_MILLIS: Long = 30_000

    /** Bitrate the SFU allows each video publisher (sent as REMB). */
    public const val VIDEO_BITRATE: Long = 1_500_000
    public const val KEYFRAME_MIN_INTERVAL_MILLIS: Long = 300
    public const val REMB_INTERVAL_MILLIS: Long = 1_000
    public const val VIDEO_CACHE_PACKETS: Int = 512

    /** SSRC the SFU uses as the sender of its own RTCP (feedback to publishers): "krtc". */
    public const val SFU_SSRC: Long = 0x6b727463L

    public fun isVideo(pt: Int): Boolean = pt == VP8_PT || pt == H264_PT
    public fun rtxFor(pt: Int): Int = if (pt == H264_PT) H264_RTX_PT else VP8_RTX_PT
}

/**
 * Rewrites one forwarded stream (SSRC, sequence number, timestamp) so subscribers see a continuous
 * stream under an SSRC the SFU chose, even when the publisher restarts its own stream.
 * Shard thread only.
 */
public class OutboundStream(public val ssrc: Long, private val clockRate: Int) {
    private var lastSourceSsrc = -1L
    private var seqOffset = 0
    private var tsOffset = 0L
    private var started = false
    public var lastOutSeq: Int = 0; private set
    public var lastOutTs: Long = 0; private set

    /** Rewrites the header of the plaintext packet in [buf] in place; returns the new sequence number. */
    public fun rewrite(buf: ByteArray, h: RtpHeader): Int {
        if (h.ssrc != lastSourceSsrc) {
            if (lastSourceSsrc >= 0) {
                // New source stream: continue right after the last packet we sent (one 20 ms frame later).
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

    /** Maps a publisher RTP timestamp into this stream's timestamp space (for forwarded sender reports). */
    public fun mapTimestamp(sourceTs: Long): Long = (sourceTs + tsOffset) and 0xFFFFFFFFL
}

/**
 * Ring buffer of the last forwarded (rewritten, plaintext) video packets, so the SFU answers NACKs
 * itself instead of asking the publisher. Allocation-free after warm-up. Shard thread only.
 */
public class PacketCache(private val capacity: Int) {
    private val seqs = IntArray(capacity) { -1 }
    private val packets = arrayOfNulls<ByteArray>(capacity)
    private val lengths = IntArray(capacity)

    public fun put(seq: Int, buf: ByteArray, length: Int) {
        val i = seq % capacity
        val slot = packets[i]?.takeIf { it.size >= length } ?: ByteArray(maxOf(length, 1400)).also { packets[i] = it }
        buf.copyInto(slot, 0, 0, length)
        lengths[i] = length
        seqs[i] = seq
    }

    /** The cached packet for [seq] (valid until the next [put]), or null. */
    public fun packet(seq: Int): ByteArray? = if (seqs[seq % capacity] == seq) packets[seq % capacity] else null

    public fun length(seq: Int): Int = lengths[seq % capacity]
}

/**
 * A member of a room. Fields are grouped by owning thread:
 * - transport (UDP thread, volatile): remote address, consent time;
 * - media (room's shard thread only): SRTP contexts, uplink SSRCs, caches, speaking state;
 * - negotiation results (volatile snapshots written by Sfu.PeerSession).
 */
public class Participant(
    public val id: String,
    public val name: String,
    public val room: Room,
    public val sink: SignalSink,
) {
    // ICE-lite credentials (RFC 8445 §5.3: ufrag ≥ 4 chars, pwd ≥ 22 chars of ice-char)
    public val iceUfrag: String = randomIceString(8)
    public val icePwd: String = randomIceString(24)
    public val icePwdBytes: ByteArray = icePwd.encodeToByteArray()
    public val cname: String = randomIceString(16)

    /** This participant's media as forwarded to everyone else, under SSRCs the SFU chose. */
    public val audioOut: OutboundStream = OutboundStream(randomSsrc(), 48_000)
    public val videoOut: OutboundStream = OutboundStream(randomSsrc(), 90_000)
    public val videoRtxSsrc: Long = randomSsrc()

    @Volatile public var remote: PeerAddress? = null
    @Volatile public var lastConsentMillis: Long = nowMillis()

    /** Whether this participant sends video (its answer accepted the video uplink m-line). */
    @Volatile public var publishesVideo: Boolean = false

    /** Publishers whose audio / video m-lines this participant has accepted (from the last applied answer). */
    @Volatile public var receiving: Set<String> = emptySet()
    @Volatile public var receivingVideo: Set<String> = emptySet()

    // --- media state, shard thread only ---
    internal var keys: SrtpSessionKeys? = null
    internal var uplinkAudioSsrc = -1L
    internal var uplinkVideoSsrc = -1L
    internal val videoCache = PacketCache(MediaPlan.VIDEO_CACHE_PACKETS)
    internal var rtxSeq = secureRandomInt(0x8000)
    internal var lastKeyframeRequest = -1L
    internal var keyframePending = false
    internal var speaking = false
    internal var lastLoudMillis = 0L

    /** Installs SRTP/SRTCP keys on the owning shard; media starts flowing afterwards. */
    public fun installKeys(k: SrtpSessionKeys) {
        room.shard.execute { keys = k }
    }

    override fun toString(): String = "Participant($id, $name)"

    internal companion object {
        const val ICE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        fun randomIceString(n: Int) = buildString { repeat(n) { append(ICE_CHARS[secureRandomInt(ICE_CHARS.length)]) } }
        fun randomSsrc(): Long = (secureRandomLong() and 0x7FFFFFFFL) or 1L
    }
}

/**
 * A room. Membership changes take a lock and publish an immutable snapshot; the media path reads the
 * snapshot on the room's shard without locking.
 */
public class Room(
    public val name: String,
    public val shard: Shard,
    private val udp: UdpServer?,
    private val onSpeaking: (Participant, Boolean) -> Unit,
    /** Testing only: drop this percentage of downlink video packets (first transmissions, not RTX). */
    private val simulatedLossPercent: Int = 0,
) {
    private val lock = SynchronizedObject()
    private val lossRandom = kotlin.random.Random(42)

    @Volatile public var members: List<Participant> = emptyList()
        private set

    private val header = RtpHeader() // shard thread only
    private val counters get() = shard.counters
    private val scratch = ByteArray(2048) // shard thread only: RTX packets and RTCP
    private var lastRemb = 0L

    /** Adds [p] and returns the members that were already there. */
    public fun add(p: Participant): List<Participant> = synchronized(lock) {
        val before = members
        members = before + p
        before
    }

    /** Removes [p] and returns the remaining members. */
    public fun remove(p: Participant): List<Participant> = synchronized(lock) {
        members = members - p
        members
    }

    /**
     * Media hot path, on the shard thread: authenticate and decrypt once, then rewrite the header and
     * re-encrypt for every subscriber. Cost is O(subscribers) per packet — the classic SFU fan-out.
     */
    public fun forward(from: Participant, data: ByteArray, length: Int) {
        val keys = from.keys ?: return
        if (Rtp.isRtcp(data, 0, length)) {
            onRtcp(from, keys, data, length)
            return
        }
        val plainLen = keys.rtpIn.unprotect(data, 0, length)
        if (plainLen < 0) {
            counters.srtpFailures.incrementAndFetch()
            return
        }
        val h = header
        if (!h.parse(data, 0, plainLen)) return
        counters.rtpIn.incrementAndFetch()
        counters.bytesIn.addAndFetch(length.toLong())
        val pt = h.payloadType
        when {
            pt == MediaPlan.OPUS_PT -> {
                from.uplinkAudioSsrc = h.ssrc
                trackSpeaking(from, data, h)
                from.audioOut.rewrite(data, h)
                fanOut(from, data, plainLen, video = false)
            }
            MediaPlan.isVideo(pt) -> {
                if (from.uplinkVideoSsrc != h.ssrc) {
                    from.uplinkVideoSsrc = h.ssrc
                    if (from.keyframePending) requestKeyframe(from)
                }
                val seq = from.videoOut.rewrite(data, h)
                from.videoCache.put(seq, data, plainLen)
                fanOut(from, data, plainLen, video = true)
            }
            // Upstream retransmissions only answer NACKs the SFU sends; it sends none, so drop them.
            else -> Unit
        }
    }

    private fun fanOut(from: Participant, data: ByteArray, plainLen: Int, video: Boolean) {
        val out = shard.out
        for (sub in members) {
            if (sub === from) continue
            val k = sub.keys ?: continue
            val to = sub.remote ?: continue
            if (from.id !in (if (video) sub.receivingVideo else sub.receiving)) continue
            val n = k.rtpOut.protect(data, 0, plainLen, out, 0)
            if (video && simulatedLossPercent > 0 && lossRandom.nextInt(100) < simulatedLossPercent) continue
            send(out, n, to)
        }
    }

    private fun send(buf: ByteArray, n: Int, to: PeerAddress) {
        udp?.send(shard.index, buf, n, to)
        counters.rtpOut.incrementAndFetch()
        counters.bytesOut.addAndFetch(n.toLong())
    }

    // ------------------------------------------------------------------ RTCP

    private fun onRtcp(from: Participant, keys: SrtpSessionKeys, data: ByteArray, length: Int) {
        val n = keys.rtcpIn.unprotect(data, 0, length)
        if (n < 0) {
            counters.srtpFailures.incrementAndFetch()
            return
        }
        counters.rtcpIn.incrementAndFetch()
        Rtcp.forEach(data, 0, n) { type, fmt, at, size ->
            when {
                type == Rtcp.SR -> forwardSenderReport(from, data, at)
                type == Rtcp.RTPFB && fmt == Rtcp.FMT_NACK -> {
                    val publisher = publisherOfVideo(Rtcp.mediaSsrc(data, at))
                    if (publisher != null) retransmit(publisher, from, Rtcp.nackSequences(data, at, size))
                }
                type == Rtcp.PSFB && fmt == Rtcp.FMT_PLI -> publisherOfVideo(Rtcp.mediaSsrc(data, at))?.let(::requestKeyframe)
                type == Rtcp.PSFB && fmt == Rtcp.FMT_FIR ->
                    Rtcp.firTargets(data, at, size).forEach { publisherOfVideo(it)?.let(::requestKeyframe) }
                // RR, SDES, BYE and subscriber REMB are terminated here.
            }
        }
    }

    private fun publisherOfVideo(downlinkSsrc: Long): Participant? = members.firstOrNull { it.videoOut.ssrc == downlinkSsrc }

    /** Forwards a publisher's SR under the downlink SSRC so subscribers can synchronize audio and video. */
    private fun forwardSenderReport(from: Participant, data: ByteArray, at: Int) {
        val video = when (Rtcp.senderSsrc(data, at)) {
            from.uplinkAudioSsrc -> false
            from.uplinkVideoSsrc -> true
            else -> return
        }
        val stream = if (video) from.videoOut else from.audioOut
        val sr = Rtcp.rewriteSenderReport(data, at, stream.ssrc, stream.mapTimestamp(Rtcp.senderReportRtpTimestamp(data, at)))
        for (sub in members) {
            if (sub === from || from.id !in (if (video) sub.receivingVideo else sub.receiving)) continue
            sendRtcp(sub, sr)
        }
    }

    /** Answers a subscriber's NACK from the cache with RTX packets (RFC 4588). */
    private fun retransmit(publisher: Participant, subscriber: Participant, seqs: List<Int>) {
        val k = subscriber.keys ?: return
        val to = subscriber.remote ?: return
        val rtx = scratch
        for (seq in seqs) {
            val pkt = publisher.videoCache.packet(seq) ?: continue
            val len = publisher.videoCache.length(seq)
            if (!header.parse(pkt, 0, len)) continue
            val hl = header.headerLength
            pkt.copyInto(rtx, 0, 0, hl)
            rtx[1] = ((pkt[1].toInt() and 0x80) or MediaPlan.rtxFor(header.payloadType)).toByte()
            publisher.rtxSeq = (publisher.rtxSeq + 1) and 0xFFFF
            Rtp.setSequenceNumber(rtx, 0, publisher.rtxSeq)
            Rtp.setSsrc(rtx, 0, publisher.videoRtxSsrc)
            rtx[hl] = (seq ushr 8).toByte() // original sequence number
            rtx[hl + 1] = seq.toByte()
            pkt.copyInto(rtx, hl + 2, hl, len)
            val n = k.rtpOut.protect(rtx, 0, len + 2, shard.out, 0)
            send(shard.out, n, to)
            counters.nacksAnswered.incrementAndFetch()
        }
    }

    /** Asks [publisher] for a key frame (PLI), at most once per KEYFRAME_MIN_INTERVAL. */
    public fun requestKeyframe(publisher: Participant) {
        if (publisher.uplinkVideoSsrc < 0) {
            publisher.keyframePending = true // ask as soon as its video starts
            return
        }
        val now = nowMillis()
        if (publisher.lastKeyframeRequest >= 0 && now - publisher.lastKeyframeRequest < MediaPlan.KEYFRAME_MIN_INTERVAL_MILLIS) return
        publisher.lastKeyframeRequest = now
        publisher.keyframePending = false
        sendRtcp(publisher, Rtcp.concat(Rtcp.receiverReport(MediaPlan.SFU_SSRC), Rtcp.pli(MediaPlan.SFU_SSRC, publisher.uplinkVideoSsrc)))
        counters.keyframeRequests.incrementAndFetch()
    }

    private fun sendRtcp(to: Participant, rtcp: ByteArray) {
        val k = to.keys ?: return
        val addr = to.remote ?: return
        val n = k.rtcpOut.protect(rtcp, 0, rtcp.size, scratch, 0)
        udp?.send(shard.index, scratch, n, addr)
        counters.rtcpOut.incrementAndFetch()
    }

    private fun trackSpeaking(p: Participant, data: ByteArray, h: RtpHeader) {
        val at = h.findExtension(data, 0, MediaPlan.AUDIO_LEVEL_EXT_ID)
        if (at < 0) return
        val level = AudioLevel.read(data, at)
        if (level.level <= MediaPlan.SPEAKING_LEVEL) {
            p.lastLoudMillis = nowMillis()
            if (!p.speaking) {
                p.speaking = true
                onSpeaking(p, true)
            }
        }
    }

    /** Periodic housekeeping on the shard: speaking timeouts and bandwidth hints to video publishers. */
    internal fun tick(now: Long) {
        val remb = now - lastRemb >= MediaPlan.REMB_INTERVAL_MILLIS
        if (remb) lastRemb = now
        for (p in members) {
            if (p.speaking && now - p.lastLoudMillis > MediaPlan.SPEAKING_HOLD_MILLIS) {
                p.speaking = false
                onSpeaking(p, false)
            }
            if (remb && p.uplinkVideoSsrc >= 0) {
                sendRtcp(
                    p,
                    Rtcp.concat(Rtcp.receiverReport(MediaPlan.SFU_SSRC), Rtcp.remb(MediaPlan.SFU_SSRC, MediaPlan.VIDEO_BITRATE, listOf(p.uplinkVideoSsrc))),
                )
            }
        }
    }
}
