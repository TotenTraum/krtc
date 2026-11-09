package krtc.protocol.rtp

import krtc.protocol.u16
import krtc.protocol.u8

/**
 * VP8 RTP payload descriptor (RFC 7741 §4.2): key-frame detection and rewriting of PictureID and
 * TL0PICIDX, which an SFU must keep continuous when it switches a receiver between simulcast layers.
 */
public object Vp8 {
    /** Parsed descriptor fields; offsets are relative to the payload start. -1 when absent. */
    public class Descriptor {
        public var start: Boolean = false; internal set
        public var partitionId: Int = 0; internal set
        public var pictureId: Int = -1; internal set
        public var pictureIdBits: Int = 0; internal set
        public var pictureIdOffset: Int = -1; internal set
        public var tl0PicIdx: Int = -1; internal set
        public var tl0Offset: Int = -1; internal set
        public var size: Int = 0; internal set
    }

    /** Parses the descriptor at [payload] (the RTP payload start); returns false if malformed. */
    public fun parse(buf: ByteArray, payload: Int, length: Int, d: Descriptor): Boolean {
        if (length < 1) return false
        val b0 = buf.u8(payload)
        d.start = b0 and 0x10 != 0
        d.partitionId = b0 and 0x07
        d.pictureId = -1; d.pictureIdBits = 0; d.pictureIdOffset = -1; d.tl0PicIdx = -1; d.tl0Offset = -1
        var p = 1
        if (b0 and 0x80 != 0) {
            if (length < 2) return false
            val x = buf.u8(payload + 1)
            p = 2
            if (x and 0x80 != 0) { // I: PictureID
                if (length < p + 1) return false
                d.pictureIdOffset = p
                if (buf.u8(payload + p) and 0x80 != 0) { // M: 15-bit
                    if (length < p + 2) return false
                    d.pictureId = buf.u16(payload + p) and 0x7FFF
                    d.pictureIdBits = 15
                    p += 2
                } else {
                    d.pictureId = buf.u8(payload + p) and 0x7F
                    d.pictureIdBits = 7
                    p += 1
                }
            }
            if (x and 0x40 != 0) { // L: TL0PICIDX
                if (length < p + 1) return false
                d.tl0Offset = p
                d.tl0PicIdx = buf.u8(payload + p)
                p += 1
            }
            if (x and 0x30 != 0) p += 1 // T or K: TID/Y/KEYIDX byte
        }
        if (p > length) return false
        d.size = p
        return true
    }

    /** True if this packet starts a key frame (first partition start, P bit of the VP8 header is 0). */
    public fun isKeyFrame(buf: ByteArray, payload: Int, length: Int, d: Descriptor): Boolean =
        d.start && d.partitionId == 0 && length > d.size && (buf.u8(payload + d.size) and 0x01) == 0

    public fun setPictureId(buf: ByteArray, payload: Int, d: Descriptor, value: Int) {
        if (d.pictureIdOffset < 0) return
        val at = payload + d.pictureIdOffset
        if (d.pictureIdBits == 15) {
            buf[at] = (0x80 or ((value ushr 8) and 0x7F)).toByte()
            buf[at + 1] = value.toByte()
        } else {
            buf[at] = (value and 0x7F).toByte()
        }
    }

    public fun setTl0PicIdx(buf: ByteArray, payload: Int, d: Descriptor, value: Int) {
        if (d.tl0Offset >= 0) buf[payload + d.tl0Offset] = value.toByte()
    }
}

/** H.264 RTP payload (RFC 6184): key-frame detection for single NAL units, STAP-A and FU-A. */
public object H264 {
    private const val IDR = 5
    private const val SPS = 7
    private const val STAP_A = 24
    private const val FU_A = 28

    public fun isKeyFrame(buf: ByteArray, payload: Int, length: Int): Boolean {
        if (length < 1) return false
        val type = buf.u8(payload) and 0x1F
        return when (type) {
            IDR, SPS -> true
            STAP_A -> {
                var p = payload + 1
                val end = payload + length
                while (p + 2 < end) {
                    val size = buf.u16(p)
                    if (p + 2 + size > end || size == 0) return false
                    val t = buf.u8(p + 2) and 0x1F
                    if (t == IDR || t == SPS) return true
                    p += 2 + size
                }
                false
            }
            FU_A -> length >= 2 && (buf.u8(payload + 1) and 0x80) != 0 && (buf.u8(payload + 1) and 0x1F).let { it == IDR || it == SPS }
            else -> false
        }
    }
}

/** RTP stream identifier header extensions (RFC 8852) used to signal simulcast layers. */
public object RtpStreamId {
    public const val MID_URI: String = "urn:ietf:params:rtp-hdrext:sdes:mid"
    public const val RID_URI: String = "urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id"
    public const val REPAIRED_RID_URI: String = "urn:ietf:params:rtp-hdrext:sdes:repaired-rtp-stream-id"

    /** Reads extension [id] of the parsed packet as an ASCII string, or null. */
    public fun read(buf: ByteArray, h: RtpHeader, id: Int): String? {
        val at = h.findExtension(buf, 0, id)
        if (at < 0) return null
        return buf.decodeToString(at, at + h.lastElementLength)
    }

    /**
     * Renames one-byte-header extension elements whose id is in [ids] to [replacement] in place, so a
     * receiver that never negotiated [replacement] ignores them (e.g. a publisher's MID/RID must not
     * be seen by subscribers, whose own MID mapping differs).
     */
    public fun rename(buf: ByteArray, h: RtpHeader, ids: IntArray, replacement: Int) {
        if (h.extensionOffset < 0 || h.extensionProfile != 0xBEDE) return
        var p = h.extensionOffset
        val end = h.extensionOffset + h.extensionLength
        while (p < end) {
            val b = buf.u8(p)
            if (b == 0) { p++; continue }
            val id = b ushr 4
            if (id == 15) return
            val len = (b and 0x0F) + 1
            if (id in ids) buf[p] = ((replacement shl 4) or (b and 0x0F)).toByte()
            p += 1 + len
        }
    }
}
