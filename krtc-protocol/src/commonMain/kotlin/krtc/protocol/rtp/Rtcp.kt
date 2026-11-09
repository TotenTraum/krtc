package krtc.protocol.rtp

import krtc.protocol.putU16
import krtc.protocol.putU32
import krtc.protocol.u16
import krtc.protocol.u32
import krtc.protocol.u8

/**
 * RTCP (RFC 3550 §6) and the feedback messages an SFU needs:
 * generic NACK (RFC 4585), PLI (RFC 4585), FIR (RFC 5104) and REMB (draft-alvestrand-rmcat-remb).
 */
public object Rtcp {
    public const val SR: Int = 200
    public const val RR: Int = 201
    public const val SDES: Int = 202
    public const val BYE: Int = 203
    public const val RTPFB: Int = 205
    public const val PSFB: Int = 206

    public const val FMT_NACK: Int = 1
    public const val FMT_PLI: Int = 1
    public const val FMT_FIR: Int = 4
    public const val FMT_AFB: Int = 15 // application layer feedback, used by REMB

    /**
     * Walks a compound packet, calling [block] with (packet type, count/FMT field, offset, size in bytes)
     * for each well-formed packet. Stops at the first malformed one.
     */
    public inline fun forEach(buf: ByteArray, offset: Int, length: Int, block: (type: Int, fmt: Int, at: Int, size: Int) -> Unit) {
        var p = offset
        val end = offset + length
        while (p + 4 <= end) {
            val b0 = buf.u8(p)
            if (b0 ushr 6 != 2) return
            val size = 4 * (buf.u16(p + 2) + 1)
            if (p + size > end) return
            block(buf.u8(p + 1), b0 and 0x1F, p, size)
            p += size
        }
    }

    public fun senderSsrc(buf: ByteArray, at: Int): Long = buf.u32(at + 4)

    /** Media source SSRC of a feedback message (RTPFB/PSFB). */
    public fun mediaSsrc(buf: ByteArray, at: Int): Long = buf.u32(at + 8)

    /** Lost sequence numbers listed by a generic NACK (PID + bitmask of following losses). */
    public fun nackSequences(buf: ByteArray, at: Int, size: Int): List<Int> {
        val out = ArrayList<Int>()
        var p = at + 12
        while (p + 4 <= at + size) {
            val pid = buf.u16(p)
            val blp = buf.u16(p + 2)
            out += pid
            for (i in 0 until 16) if (blp and (1 shl i) != 0) out += (pid + i + 1) and 0xFFFF
            p += 4
        }
        return out
    }

    /** Target SSRCs of a FIR (each FCI entry: SSRC, sequence number, 3 reserved bytes). */
    public fun firTargets(buf: ByteArray, at: Int, size: Int): List<Long> {
        val out = ArrayList<Long>()
        var p = at + 12
        while (p + 8 <= at + size) {
            out += buf.u32(p)
            p += 8
        }
        return out
    }

    private fun header(out: ByteArray, at: Int, fmt: Int, type: Int, sizeBytes: Int) {
        out[at] = (0x80 or fmt).toByte()
        out[at + 1] = type.toByte()
        out.putU16(at + 2, sizeBytes / 4 - 1)
    }

    /** Empty receiver report: lets feedback travel in a compound packet that starts with RR (RFC 3550 §6.1). */
    public fun receiverReport(senderSsrc: Long): ByteArray = ByteArray(8).also {
        header(it, 0, 0, RR, 8)
        it.putU32(4, senderSsrc)
    }

    public fun pli(senderSsrc: Long, mediaSsrc: Long): ByteArray = ByteArray(12).also {
        header(it, 0, FMT_PLI, PSFB, 12)
        it.putU32(4, senderSsrc)
        it.putU32(8, mediaSsrc)
    }

    public fun fir(senderSsrc: Long, targetSsrc: Long, sequence: Int): ByteArray = ByteArray(20).also {
        header(it, 0, FMT_FIR, PSFB, 20)
        it.putU32(4, senderSsrc)
        it.putU32(8, 0)
        it.putU32(12, targetSsrc)
        it[16] = sequence.toByte()
    }

    /** Receiver estimated maximum bitrate for [ssrcs], in bits per second. */
    public fun remb(senderSsrc: Long, bitrate: Long, ssrcs: List<Long>): ByteArray {
        val size = 20 + 4 * ssrcs.size
        val out = ByteArray(size)
        header(out, 0, FMT_AFB, PSFB, size)
        out.putU32(4, senderSsrc)
        out.putU32(8, 0)
        out[12] = 'R'.code.toByte(); out[13] = 'E'.code.toByte(); out[14] = 'M'.code.toByte(); out[15] = 'B'.code.toByte()
        var mantissa = bitrate
        var exp = 0
        while (mantissa > 0x3FFFF) {
            mantissa = mantissa ushr 1
            exp++
        }
        out[16] = ssrcs.size.toByte()
        out[17] = ((exp shl 2) or (mantissa ushr 16).toInt()).toByte()
        out[18] = (mantissa ushr 8).toByte()
        out[19] = mantissa.toByte()
        ssrcs.forEachIndexed { i, s -> out.putU32(20 + 4 * i, s) }
        return out
    }

    /** Parses a REMB bitrate (bits per second), or -1 if the packet at [at] is not REMB. */
    public fun rembBitrate(buf: ByteArray, at: Int, size: Int): Long {
        if (size < 20 || buf[at + 12].toInt() != 'R'.code || buf[at + 13].toInt() != 'E'.code ||
            buf[at + 14].toInt() != 'M'.code || buf[at + 15].toInt() != 'B'.code
        ) return -1
        val exp = buf.u8(at + 17) ushr 2
        val mantissa = ((buf.u8(at + 17) and 0x03).toLong() shl 16) or (buf.u8(at + 18).toLong() shl 8) or buf.u8(at + 19).toLong()
        return mantissa shl exp
    }

    /**
     * A sender report without report blocks, carrying the sender info of the SR at [at]
     * under [ssrc] with its RTP timestamp replaced by [rtpTimestamp] (for forwarding by an SFU).
     */
    public fun rewriteSenderReport(buf: ByteArray, at: Int, ssrc: Long, rtpTimestamp: Long): ByteArray {
        val out = ByteArray(28)
        header(out, 0, 0, SR, 28)
        out.putU32(4, ssrc)
        buf.copyInto(out, 8, at + 8, at + 16) // NTP timestamp
        out.putU32(16, rtpTimestamp)
        buf.copyInto(out, 20, at + 20, at + 28) // packet and octet counts
        return out
    }

    public fun senderReportRtpTimestamp(buf: ByteArray, at: Int): Long = buf.u32(at + 16)

    public fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var p = 0
        for (part in parts) {
            part.copyInto(out, p)
            p += part.size
        }
        return out
    }
}
