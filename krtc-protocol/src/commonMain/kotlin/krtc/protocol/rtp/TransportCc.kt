package krtc.protocol.rtp

import krtc.protocol.putU16
import krtc.protocol.putU32
import krtc.protocol.u16
import krtc.protocol.u8

/**
 * Transport-wide congestion control (draft-holmer-rmcat-transport-wide-cc-extensions-01): the RTP header
 * extension carrying a per-transport sequence number, and the RTCP feedback (RTPFB, FMT 15) reporting when
 * each of those packets arrived. The media sender runs the bandwidth estimator (send-side BWE, as in GCC).
 */
public object TransportCc {
    public const val URI: String = "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01"
    public const val FMT: Int = 15

    /** Arrival-time resolution of the deltas: 250 µs. */
    public const val DELTA_MICROS: Long = 250

    /** Reference-time resolution: 64 ms. */
    public const val REFERENCE_MICROS: Long = 64_000

    /** Reads the 16-bit transport-wide sequence number at extension data offset [at]. */
    public fun read(buf: ByteArray, at: Int): Int = buf.u16(at)

    /** Overwrites the transport-wide sequence number at extension data offset [at]. */
    public fun write(buf: ByteArray, at: Int, seq: Int) {
        buf.putU16(at, seq and 0xFFFF)
    }

    /**
     * Builds one feedback packet for the consecutive sequence numbers starting at [baseSeq]:
     * [arrivalMicros]`[i]` is the arrival time of `baseSeq + i` on any monotonic microsecond clock, or a
     * negative value if it has not arrived. At least one entry must have arrived. Deltas that do not fit in
     * 16 bits end the packet early; [Built.count] says how many entries it covers.
     */
    public fun feedback(senderSsrc: Long, mediaSsrc: Long, baseSeq: Int, arrivalMicros: LongArray, count: Int, feedbackCount: Int): Built {
        val first = (0 until count).firstOrNull { arrivalMicros[it] >= 0 }
        require(first != null) { "a feedback must report at least one arrival" }
        val reference = arrivalMicros[first] / REFERENCE_MICROS
        var previous = reference * REFERENCE_MICROS
        val symbols = IntArray(count)
        val deltas = IntArray(count)
        var n = 0
        while (n < minOf(count, 0xFFFF)) {
            val t = arrivalMicros[n]
            if (t < 0) {
                symbols[n++] = NOT_RECEIVED
                continue
            }
            val ticks = (t - previous).floorDiv(DELTA_MICROS)
            if (ticks !in Short.MIN_VALUE..Short.MAX_VALUE) break
            symbols[n] = if (ticks in 0..255) SMALL else LARGE
            deltas[n] = ticks.toInt()
            previous += ticks * DELTA_MICROS
            n++
        }
        while (n > 1 && symbols[n - 1] == NOT_RECEIVED) n-- // nothing to report after the last arrival

        val chunks = (n + 6) / 7 // two-bit status vectors, 7 symbols each
        var deltaBytes = 0
        for (i in 0 until n) deltaBytes += when (symbols[i]) { SMALL -> 1; LARGE -> 2; else -> 0 }
        val unpadded = 20 + 2 * chunks + deltaBytes
        val size = (unpadded + 3) and 3.inv()
        val out = ByteArray(size)
        out[0] = (0x80 or FMT or (if (size != unpadded) 0x20 else 0)).toByte()
        out[1] = Rtcp.RTPFB.toByte()
        out.putU16(2, size / 4 - 1)
        out.putU32(4, senderSsrc)
        out.putU32(8, mediaSsrc)
        out.putU16(12, baseSeq and 0xFFFF)
        out.putU16(14, n)
        val ref24 = reference and 0xFFFFFF
        out[16] = (ref24 ushr 16).toByte()
        out[17] = (ref24 ushr 8).toByte()
        out[18] = ref24.toByte()
        out[19] = feedbackCount.toByte()
        var p = 20
        for (c in 0 until chunks) {
            var chunk = 0xC000 // status vector, two-bit symbols
            for (j in 0 until 7) {
                val i = c * 7 + j
                val s = if (i < n) symbols[i] else NOT_RECEIVED
                chunk = chunk or (s shl (12 - 2 * j))
            }
            out.putU16(p, chunk)
            p += 2
        }
        for (i in 0 until n) {
            when (symbols[i]) {
                SMALL -> out[p++] = deltas[i].toByte()
                LARGE -> { out.putU16(p, deltas[i] and 0xFFFF); p += 2 }
            }
        }
        if (size != unpadded) out[size - 1] = (size - unpadded).toByte()
        return Built(out, n)
    }

    public class Built(public val packet: ByteArray, public val count: Int)

    /** Marks a packet the feedback reports as not received. */
    public const val LOST: Long = Long.MIN_VALUE

    /** A parsed feedback packet: for each reported sequence number, its arrival time or [LOST]. */
    public class Feedback(
        public val baseSeq: Int,
        public val feedbackCount: Int,
        /** Arrival times in microseconds on the receiver's clock (reference time × 64 ms + deltas), or [LOST]. */
        public val arrivalMicros: LongArray,
    ) {
        public val count: Int get() = arrivalMicros.size
        public fun seq(i: Int): Int = (baseSeq + i) and 0xFFFF
    }

    /** Parses a transport-cc feedback packet at [at] (RTPFB, FMT 15) of [size] bytes; null if malformed. */
    public fun parse(buf: ByteArray, at: Int, size: Int): Feedback? {
        if (size < 20) return null
        val end = at + size - (if (buf.u8(at) and 0x20 != 0) buf.u8(at + size - 1) else 0)
        val baseSeq = buf.u16(at + 12)
        val count = buf.u16(at + 14)
        var reference = (buf.u8(at + 16) shl 16) or (buf.u8(at + 17) shl 8) or buf.u8(at + 18)
        if (reference and 0x800000 != 0) reference -= 0x1000000 // signed 24-bit
        val feedbackCount = buf.u8(at + 19)
        val symbols = IntArray(count)
        var p = at + 20
        var n = 0
        while (n < count) {
            if (p + 2 > end) return null
            val chunk = buf.u16(p)
            p += 2
            if (chunk and 0x8000 == 0) { // run length
                val symbol = (chunk ushr 13) and 3
                val run = chunk and 0x1FFF
                for (k in 0 until run) if (n < count) symbols[n++] = symbol
            } else if (chunk and 0x4000 == 0) { // one-bit vector, 14 symbols
                for (j in 0 until 14) if (n < count) symbols[n++] = (chunk ushr (13 - j)) and 1
            } else { // two-bit vector, 7 symbols
                for (j in 0 until 7) if (n < count) symbols[n++] = (chunk ushr (12 - 2 * j)) and 3
            }
        }
        val arrivals = LongArray(count)
        var t = reference.toLong() * REFERENCE_MICROS
        for (i in 0 until count) {
            when (symbols[i]) {
                SMALL -> {
                    if (p + 1 > end) return null
                    t += buf.u8(p++) * DELTA_MICROS
                    arrivals[i] = t
                }
                LARGE -> {
                    if (p + 2 > end) return null
                    t += buf.u16(p).toShort() * DELTA_MICROS
                    p += 2
                    arrivals[i] = t
                }
                else -> arrivals[i] = LOST
            }
        }
        return Feedback(baseSeq, feedbackCount, arrivals)
    }

    private const val NOT_RECEIVED = 0
    private const val SMALL = 1
    private const val LARGE = 2
}
