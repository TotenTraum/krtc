package krtc.sfu.sctp

import krtc.protocol.putU16
import krtc.protocol.putU32
import krtc.protocol.u16
import krtc.protocol.u32
import krtc.protocol.u8

// SCTP packet format (RFC 9260 §3) as carried over DTLS (RFC 8261): common header, chunks, CRC32c.

internal object ChunkType {
    const val DATA = 0
    const val INIT = 1
    const val INIT_ACK = 2
    const val SACK = 3
    const val HEARTBEAT = 4
    const val HEARTBEAT_ACK = 5
    const val ABORT = 6
    const val SHUTDOWN = 7
    const val SHUTDOWN_ACK = 8
    const val ERROR = 9
    const val COOKIE_ECHO = 10
    const val COOKIE_ACK = 11
    const val SHUTDOWN_COMPLETE = 14
    const val RECONFIG = 130 // RFC 6525
    const val FORWARD_TSN = 192 // RFC 3758
}

internal object ParamType {
    const val HEARTBEAT_INFO = 1
    const val STATE_COOKIE = 7
    const val OUTGOING_RESET_REQUEST = 13
    const val INCOMING_RESET_REQUEST = 14
    const val RECONFIG_RESPONSE = 16
    const val SUPPORTED_EXTENSIONS = 0x8008
    const val FORWARD_TSN_SUPPORTED = 0xC000
}

/** DATA chunk flags. */
internal object DataFlags {
    const val END = 1
    const val BEGIN = 2
    const val UNORDERED = 4
}

/** One chunk of a received packet; the value is `buf[off until off + len]` (without the 4-byte chunk header). */
internal class Chunk(val type: Int, val flags: Int, val buf: ByteArray, val off: Int, val len: Int) {
    fun u16(at: Int) = buf.u16(off + at)
    fun u32(at: Int) = buf.u32(off + at)
    fun bytes(from: Int, to: Int = len) = buf.copyOfRange(off + from, off + to)
}

internal class SctpPacket(val sourcePort: Int, val destinationPort: Int, val verificationTag: Long, val chunks: List<Chunk>) {
    internal companion object {
        const val HEADER = 12

        /** Null when malformed or the checksum is wrong. */
        fun parse(buf: ByteArray, len: Int = buf.size): SctpPacket? {
            if (len < HEADER + 4) return null
            val stored = (buf.u8(8)) or (buf.u8(9) shl 8) or (buf.u8(10) shl 16) or (buf.u8(11) shl 24)
            if (stored != Crc32c.packet(buf, len)) return null
            val chunks = ArrayList<Chunk>(2)
            var at = HEADER
            while (at + 4 <= len) {
                val type = buf.u8(at)
                val flags = buf.u8(at + 1)
                val length = buf.u16(at + 2)
                if (length < 4 || at + length > len) return null
                chunks += Chunk(type, flags, buf, at + 4, length - 4)
                at += (length + 3) and 3.inv()
            }
            return SctpPacket(buf.u16(0), buf.u16(2), buf.u32(4), chunks)
        }

        /** Assembles [chunks] (each a complete, padded chunk) into one packet with its checksum. */
        fun build(sourcePort: Int, destinationPort: Int, verificationTag: Long, chunks: List<ByteArray>): ByteArray {
            val out = ByteArray(HEADER + chunks.sumOf { it.size })
            out.putU16(0, sourcePort)
            out.putU16(2, destinationPort)
            out.putU32(4, verificationTag)
            var at = HEADER
            for (c in chunks) {
                c.copyInto(out, at)
                at += c.size
            }
            val crc = Crc32c.packet(out, out.size)
            out[8] = crc.toByte()
            out[9] = (crc ushr 8).toByte()
            out[10] = (crc ushr 16).toByte()
            out[11] = (crc ushr 24).toByte()
            return out
        }
    }
}

/** Builds one chunk: header, value, padding to 4 bytes. */
internal fun chunk(type: Int, flags: Int, value: ByteArray): ByteArray {
    val length = 4 + value.size
    val out = ByteArray((length + 3) and 3.inv())
    out[0] = type.toByte()
    out[1] = flags.toByte()
    out.putU16(2, length)
    value.copyInto(out, 4)
    return out
}

/** Builds a TLV parameter, padded to 4 bytes. */
internal fun param(type: Int, value: ByteArray): ByteArray {
    val length = 4 + value.size
    val out = ByteArray((length + 3) and 3.inv())
    out.putU16(0, type)
    out.putU16(2, length)
    value.copyInto(out, 4)
    return out
}

/** Iterates the TLV parameters in `[from, to)` of [buf]: (type, value offset, value length). */
internal inline fun forEachParam(buf: ByteArray, from: Int, to: Int, block: (type: Int, off: Int, len: Int) -> Unit) {
    var at = from
    while (at + 4 <= to) {
        val type = buf.u16(at)
        val length = buf.u16(at + 2)
        if (length < 4 || at + length > to) return
        block(type, at + 4, length - 4)
        at += (length + 3) and 3.inv()
    }
}

/** Big-endian writer for chunk values. */
internal class Writer(size: Int) {
    val bytes = ByteArray(size)
    var at = 0
    fun u8(v: Int) = apply { bytes[at++] = v.toByte() }
    fun u16(v: Int) = apply { bytes.putU16(at, v); at += 2 }
    fun u32(v: Long) = apply { bytes.putU32(at, v and 0xFFFFFFFFL); at += 4 }
    fun put(b: ByteArray) = apply { b.copyInto(bytes, at); at += b.size }
}

/** CRC32c (Castagnoli), as SCTP uses it (RFC 9260 appendix A): stored little-endian in the common header. */
internal object Crc32c {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x82F63B78.toInt() else c ushr 1 }
        c
    }

    fun compute(buf: ByteArray, from: Int, to: Int, initial: Int = -1): Int {
        var crc = initial
        for (i in from until to) crc = table[(crc xor buf[i].toInt()) and 0xFF] xor (crc ushr 8)
        return crc
    }

    /** The checksum of a packet whose checksum field (bytes 8..11) is taken as zero. */
    fun packet(buf: ByteArray, len: Int): Int {
        var crc = compute(buf, 0, 8)
        repeat(4) { crc = table[crc and 0xFF] xor (crc ushr 8) }
        return compute(buf, 12, len, crc).inv()
    }
}
