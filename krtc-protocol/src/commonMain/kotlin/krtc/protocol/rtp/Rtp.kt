package krtc.protocol.rtp

import krtc.protocol.u16
import krtc.protocol.u32
import krtc.protocol.u8

/**
 * Allocation-free view of an RTP header (RFC 3550 §5.1) over a byte buffer.
 * Reuse one instance per thread on hot paths: [parse] overwrites all fields.
 */
public class RtpHeader {
    public var padding: Boolean = false; private set
    public var marker: Boolean = false; private set
    public var payloadType: Int = 0; private set
    public var sequenceNumber: Int = 0; private set
    public var timestamp: Long = 0; private set
    public var ssrc: Long = 0; private set
    public var csrcCount: Int = 0; private set

    /** Header length including CSRCs and the extension block; the payload starts here. */
    public var headerLength: Int = 0; private set

    /** Offset (relative to the packet) and length of the extension data, or -1 / 0 without extensions. */
    public var extensionOffset: Int = -1; private set
    public var extensionLength: Int = 0; private set
    public var extensionProfile: Int = 0; private set

    /** Parses [length] bytes at [offset]; returns false if this is not a valid RTP packet. */
    public fun parse(buf: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 12) return false
        val b0 = buf.u8(offset)
        if (b0 ushr 6 != 2) return false
        padding = b0 and 0x20 != 0
        val hasExtension = b0 and 0x10 != 0
        csrcCount = b0 and 0x0F
        val b1 = buf.u8(offset + 1)
        marker = b1 and 0x80 != 0
        payloadType = b1 and 0x7F
        sequenceNumber = buf.u16(offset + 2)
        timestamp = buf.u32(offset + 4)
        ssrc = buf.u32(offset + 8)
        var len = 12 + 4 * csrcCount
        extensionOffset = -1
        extensionLength = 0
        extensionProfile = 0
        if (hasExtension) {
            if (length < len + 4) return false
            extensionProfile = buf.u16(offset + len)
            extensionLength = 4 * buf.u16(offset + len + 2)
            extensionOffset = len + 4
            len += 4 + extensionLength
        }
        if (len > length) return false
        headerLength = len
        return true
    }

    /**
     * Finds header extension element [id] (RFC 8285, one- or two-byte form).
     * Returns its data offset relative to the packet start, or -1; [lastElementLength] holds its length.
     */
    public fun findExtension(buf: ByteArray, offset: Int, id: Int): Int {
        if (extensionOffset < 0) return -1
        val oneByte = extensionProfile == 0xBEDE
        val twoByte = extensionProfile and 0xFFF0 == 0x1000
        if (!oneByte && !twoByte) return -1
        var p = extensionOffset
        val end = extensionOffset + extensionLength
        while (p < end) {
            val b = buf.u8(offset + p)
            if (b == 0) { p++; continue } // padding
            val elemId: Int
            val elemLen: Int
            if (oneByte) {
                elemId = b ushr 4
                if (elemId == 15) return -1
                elemLen = (b and 0x0F) + 1
                p += 1
            } else {
                if (p + 1 >= end) return -1
                elemId = b
                elemLen = buf.u8(offset + p + 1)
                p += 2
            }
            if (p + elemLen > end) return -1
            if (elemId == id) {
                lastElementLength = elemLen
                return p
            }
            p += elemLen
        }
        return -1
    }

    public var lastElementLength: Int = 0; private set

    /** Payload length excluding RTP padding. */
    public fun payloadLength(buf: ByteArray, offset: Int, length: Int): Int {
        val pad = if (padding) buf.u8(offset + length - 1) else 0
        return length - headerLength - pad
    }
}

public object Rtp {
    /** RFC 5761 §4: with rtcp-mux, packets whose second byte is in 192..223 are RTCP. */
    public fun isRtcp(buf: ByteArray, offset: Int, length: Int): Boolean =
        length >= 8 && buf.u8(offset + 1) in 192..223

    /** Signed distance a → b between 16-bit sequence numbers, handling wrap-around. */
    public fun seqDelta(a: Int, b: Int): Int {
        val d = (b - a) and 0xFFFF
        return if (d >= 0x8000) d - 0x10000 else d
    }

    public fun setSsrc(buf: ByteArray, offset: Int, ssrc: Long) {
        buf[offset + 8] = (ssrc ushr 24).toByte()
        buf[offset + 9] = (ssrc ushr 16).toByte()
        buf[offset + 10] = (ssrc ushr 8).toByte()
        buf[offset + 11] = ssrc.toByte()
    }

    public fun setSequenceNumber(buf: ByteArray, offset: Int, seq: Int) {
        buf[offset + 2] = (seq ushr 8).toByte()
        buf[offset + 3] = seq.toByte()
    }

    public fun setTimestamp(buf: ByteArray, offset: Int, ts: Long) {
        buf[offset + 4] = (ts ushr 24).toByte()
        buf[offset + 5] = (ts ushr 16).toByte()
        buf[offset + 6] = (ts ushr 8).toByte()
        buf[offset + 7] = ts.toByte()
    }
}

/** Client-to-mixer audio level (RFC 6464): voice-activity flag and level in -dBov (0 = loudest, 127 = silence). */
public class AudioLevel(public val voiceActivity: Boolean, public val level: Int) {
    public companion object {
        public const val URI: String = "urn:ietf:params:rtp-hdrext:ssrc-audio-level"

        public fun read(buf: ByteArray, at: Int): AudioLevel {
            val b = buf.u8(at)
            return AudioLevel(b and 0x80 != 0, b and 0x7F)
        }
    }
}
