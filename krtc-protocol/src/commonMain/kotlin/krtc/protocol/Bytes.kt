package krtc.protocol

// Big-endian accessors used by every wire format in this module.

public fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

public fun ByteArray.u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

public fun ByteArray.u32(at: Int): Long =
    (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()

public fun ByteArray.putU16(at: Int, value: Int) {
    this[at] = (value ushr 8).toByte()
    this[at + 1] = value.toByte()
}

public fun ByteArray.putU32(at: Int, value: Long) {
    this[at] = (value ushr 24).toByte()
    this[at + 1] = (value ushr 16).toByte()
    this[at + 2] = (value ushr 8).toByte()
    this[at + 3] = value.toByte()
}

private const val HEX = "0123456789abcdef"

public fun ByteArray.toHex(from: Int = 0, to: Int = size): String = buildString((to - from) * 2) {
    for (i in from until to) {
        val b = this@toHex[i].toInt() and 0xFF
        append(HEX[b ushr 4]).append(HEX[b and 0xF])
    }
}

/** Parses hex, ignoring whitespace and `:` separators (as in SDP fingerprints and RFC test vectors). */
public fun hexToBytes(hex: String): ByteArray {
    val clean = hex.filter { !it.isWhitespace() && it != ':' }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

/** Constant-time comparison for authentication tags. */
public fun constantTimeEquals(a: ByteArray, aOff: Int, b: ByteArray, bOff: Int, len: Int): Boolean {
    var diff = 0
    for (i in 0 until len) diff = diff or (a[aOff + i].toInt() xor b[bOff + i].toInt())
    return diff == 0
}

/**
 * Demultiplexing of packets arriving on one UDP 5-tuple by their first byte (RFC 7983).
 */
public enum class PacketKind { STUN, DTLS, RTP_OR_RTCP, UNKNOWN }

public fun classifyPacket(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): PacketKind {
    if (length < 1) return PacketKind.UNKNOWN
    val b = data.u8(offset)
    return when (b) {
        in 0..3 -> PacketKind.STUN
        in 20..63 -> PacketKind.DTLS
        in 128..191 -> PacketKind.RTP_OR_RTCP
        else -> PacketKind.UNKNOWN
    }
}
