package krtc.sfu.room

import krtc.protocol.rtp.AudioLevel
import krtc.protocol.rtp.Rtp
import krtc.protocol.rtp.RtpHeader
import krtc.sfu.Metrics
import krtc.sfu.srtp.SrtpContext
import krtc.sfu.transport.UdpServer
import java.net.InetSocketAddress
import java.security.SecureRandom

/** Outbound signaling channel of a participant (implemented by the WebSocket layer). Must not block. */
public interface SignalSink {
    public fun send(message: String)
    public fun close()
}

/** Constants of the media plan the SFU dictates in its offers. */
public object MediaPlan {
    public const val OPUS_PT: Int = 111
    public const val AUDIO_LEVEL_EXT_ID: Int = 1

    /** -dBov at or below which a packet counts as speech (RFC 6464: 0 loudest, 127 silent). */
    public const val SPEAKING_LEVEL: Int = 50
    public const val SPEAKING_HOLD_MILLIS: Long = 400
    public const val CONSENT_TIMEOUT_MILLIS: Long = 30_000
}

/**
 * A member of a room. Fields are grouped by owning thread:
 * - transport (UDP thread, volatile): remote address, consent time;
 * - media (room's shard thread only): SRTP contexts, rewriting and speaking state;
 * - negotiation (any thread, guarded by `this`): offer/answer state, see [Room] and SignalingSession.
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

    /** SSRC under which this participant's audio is forwarded to everyone else. */
    public val downlinkSsrc: Long = (random.nextInt().toLong() and 0x7FFFFFFFL) or 1L
    public val cname: String = randomIceString(16)

    @Volatile public var remote: InetSocketAddress? = null
    @Volatile public var lastConsentMillis: Long = System.currentTimeMillis()

    // --- media state, shard thread only ---
    internal var srtpIn: SrtpContext? = null
    internal var srtpOut: SrtpContext? = null
    private var lastSourceSsrc = -1L
    private var seqOffset = 0
    private var tsOffset = 0L
    private var lastOutSeq = 0
    private var lastOutTs = 0L
    internal var speaking = false
    internal var lastLoudMillis = 0L

    /** Publishers whose downlink m-line this participant has accepted (from the last applied answer). */
    @Volatile public var receiving: Set<String> = emptySet()

    /** Installs SRTP keys on the owning shard; media starts flowing afterwards. */
    public fun installSrtp(inbound: SrtpContext, outbound: SrtpContext) {
        room.shard.execute {
            srtpIn = inbound
            srtpOut = outbound
        }
    }

    /**
     * Rewrites seq/timestamp so the downlink stream stays continuous when the publisher's own
     * SSRC changes (e.g. after it replaces its track). Returns false if nothing should be sent.
     */
    internal fun rewriteForDownlink(buf: ByteArray, h: RtpHeader) {
        val ssrc = h.ssrc
        if (ssrc != lastSourceSsrc) {
            if (lastSourceSsrc >= 0) {
                seqOffset = (lastOutSeq + 1 - h.sequenceNumber) and 0xFFFF
                tsOffset = (lastOutTs + 960 - h.timestamp) and 0xFFFFFFFFL
            }
            lastSourceSsrc = ssrc
        }
        val seq = (h.sequenceNumber + seqOffset) and 0xFFFF
        val ts = (h.timestamp + tsOffset) and 0xFFFFFFFFL
        if (Rtp.seqDelta(lastOutSeq, seq) > 0 || lastOutSeq == 0) {
            lastOutSeq = seq
            lastOutTs = ts
        }
        Rtp.setSsrc(buf, 0, downlinkSsrc)
        Rtp.setSequenceNumber(buf, 0, seq)
        Rtp.setTimestamp(buf, 0, ts)
    }

    override fun toString(): String = "Participant($id, $name)"

    private companion object {
        val random = SecureRandom()
        const val ICE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        fun randomIceString(n: Int) = buildString { repeat(n) { append(ICE_CHARS[random.nextInt(ICE_CHARS.length)]) } }
    }
}

/**
 * A voice room. Membership changes take a lock and publish an immutable snapshot;
 * the media path ([forward]) reads the snapshot on the room's shard without locking.
 */
public class Room(
    public val name: String,
    public val shard: Shard,
    private val udp: UdpServer?,
    private val metrics: Metrics,
    private val onSpeaking: (Participant, Boolean) -> Unit,
) {
    @Volatile public var members: List<Participant> = emptyList()
        private set

    private val header = RtpHeader() // shard thread only

    @Synchronized
    public fun add(p: Participant) {
        members = members + p
    }

    @Synchronized
    public fun remove(p: Participant): Boolean {
        val before = members.size
        members = members - p
        return members.size != before
    }

    /**
     * Media hot path, on the shard thread: authenticate and decrypt once, then rewrite the header and
     * re-encrypt for every subscriber. Cost is O(listeners) per packet, the classic SFU fan-out.
     */
    public fun forward(from: Participant, data: ByteArray, length: Int) {
        if (Rtp.isRtcp(data, 0, length)) {
            // RTCP is terminated here: receiver reports/feedback are not needed for Opus forwarding.
            metrics.rtcpDropped.increment()
            return
        }
        val inbound = from.srtpIn ?: return
        val plainLen = inbound.unprotect(data, 0, length)
        if (plainLen < 0) {
            metrics.srtpFailures.increment()
            return
        }
        val h = header
        if (!h.parse(data, 0, plainLen) || h.payloadType != MediaPlan.OPUS_PT) return
        metrics.rtpIn.increment()
        metrics.bytesIn.add(length.toLong())

        trackSpeaking(from, data, h)
        from.rewriteForDownlink(data, h)

        val out = shard.out
        for (sub in members) {
            if (sub === from) continue
            val ctx = sub.srtpOut ?: continue
            val to = sub.remote ?: continue
            if (from.id !in sub.receiving) continue
            val n = ctx.protect(data, 0, plainLen, out, 0)
            udp?.send(shard.index, shard.sendBuffer, out, n, to)
            metrics.rtpOut.increment()
            metrics.bytesOut.add(n.toLong())
        }
    }

    private fun trackSpeaking(p: Participant, data: ByteArray, h: RtpHeader) {
        val at = h.findExtension(data, 0, MediaPlan.AUDIO_LEVEL_EXT_ID)
        if (at < 0) return
        val level = AudioLevel.read(data, at)
        if (level.level <= MediaPlan.SPEAKING_LEVEL) {
            p.lastLoudMillis = System.currentTimeMillis()
            if (!p.speaking) {
                p.speaking = true
                onSpeaking(p, true)
            }
        }
    }

    /** Periodic housekeeping on the shard: speaking timeouts. */
    internal fun tick(now: Long) {
        for (p in members) {
            if (p.speaking && now - p.lastLoudMillis > MediaPlan.SPEAKING_HOLD_MILLIS) {
                p.speaking = false
                onSpeaking(p, false)
            }
        }
    }
}
