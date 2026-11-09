package krtc.protocol.stun

import krtc.protocol.constantTimeEquals
import krtc.protocol.crypto.Crc32
import krtc.protocol.crypto.Sha1
import krtc.protocol.putU16
import krtc.protocol.putU32
import krtc.protocol.u16
import krtc.protocol.u32

/** STUN (RFC 8489) as used by ICE (RFC 8445): Binding requests and responses only. */
public object StunConstants {
    public const val HEADER_LENGTH: Int = 20
    public const val MAGIC_COOKIE: Long = 0x2112A442L

    public const val BINDING_REQUEST: Int = 0x0001
    public const val BINDING_SUCCESS: Int = 0x0101
    public const val BINDING_ERROR: Int = 0x0111
    public const val BINDING_INDICATION: Int = 0x0011

    public const val ATTR_USERNAME: Int = 0x0006
    public const val ATTR_MESSAGE_INTEGRITY: Int = 0x0008
    public const val ATTR_ERROR_CODE: Int = 0x0009
    public const val ATTR_XOR_MAPPED_ADDRESS: Int = 0x0020
    public const val ATTR_PRIORITY: Int = 0x0024
    public const val ATTR_USE_CANDIDATE: Int = 0x0025
    public const val ATTR_SOFTWARE: Int = 0x8022
    public const val ATTR_FINGERPRINT: Int = 0x8028
    public const val ATTR_ICE_CONTROLLED: Int = 0x8029
    public const val ATTR_ICE_CONTROLLING: Int = 0x802A

    internal const val FINGERPRINT_XOR: Long = 0x5354554EL
}

public class StunAttribute(public val type: Int, public val value: ByteArray, /** offset of the attribute header in the message */ public val position: Int)

/** A parsed STUN message. Holds a reference to the original bytes for integrity checks. */
public class StunMessage private constructor(
    public val type: Int,
    public val transactionId: ByteArray,
    public val attributes: List<StunAttribute>,
    private val raw: ByteArray,
    private val rawOffset: Int,
    private val rawLength: Int,
) {
    public fun attribute(type: Int): StunAttribute? = attributes.firstOrNull { it.type == type }
    public fun has(type: Int): Boolean = attributes.any { it.type == type }

    public val username: String? get() = attribute(StunConstants.ATTR_USERNAME)?.value?.decodeToString()
    public val priority: Long? get() = attribute(StunConstants.ATTR_PRIORITY)?.value?.u32(0)
    public val useCandidate: Boolean get() = has(StunConstants.ATTR_USE_CANDIDATE)

    /**
     * Checks MESSAGE-INTEGRITY with the short-term credential [key] (the ICE password).
     * Per RFC 8489 §14.5 the HMAC covers the message up to the attribute, with the header length
     * adjusted to end right after MESSAGE-INTEGRITY.
     */
    public fun verifyIntegrity(key: ByteArray): Boolean {
        val mi = attribute(StunConstants.ATTR_MESSAGE_INTEGRITY) ?: return false
        val upTo = mi.position
        val buf = raw.copyOfRange(rawOffset, rawOffset + upTo)
        buf.putU16(2, upTo - StunConstants.HEADER_LENGTH + 4 + Sha1.DIGEST_LENGTH)
        val mac = Sha1.hmac(key, buf)
        return constantTimeEquals(mac, 0, mi.value, 0, Sha1.DIGEST_LENGTH)
    }

    /** Checks FINGERPRINT (RFC 8489 §14.7). Returns true when the attribute is absent. */
    public fun verifyFingerprint(): Boolean {
        val fp = attribute(StunConstants.ATTR_FINGERPRINT) ?: return true
        val crc = Crc32.compute(raw, rawOffset, fp.position) xor StunConstants.FINGERPRINT_XOR
        return crc == fp.value.u32(0)
    }

    public companion object {
        /** Returns null for anything that is not a well-formed STUN message. */
        public fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): StunMessage? {
            if (length < StunConstants.HEADER_LENGTH) return null
            val type = data.u16(offset)
            if (type and 0xC000 != 0) return null
            val msgLen = data.u16(offset + 2)
            if (msgLen % 4 != 0 || StunConstants.HEADER_LENGTH + msgLen > length) return null
            if (data.u32(offset + 4) != StunConstants.MAGIC_COOKIE) return null
            val tid = data.copyOfRange(offset + 8, offset + 20)
            val attrs = ArrayList<StunAttribute>(8)
            var p = StunConstants.HEADER_LENGTH
            val end = StunConstants.HEADER_LENGTH + msgLen
            while (p + 4 <= end) {
                val at = data.u16(offset + p)
                val alen = data.u16(offset + p + 2)
                if (p + 4 + alen > end) return null
                attrs += StunAttribute(at, data.copyOfRange(offset + p + 4, offset + p + 4 + alen), p)
                p += 4 + ((alen + 3) and 3.inv())
            }
            return StunMessage(type, tid, attrs, data, offset, StunConstants.HEADER_LENGTH + msgLen)
        }
    }
}

/** Builds STUN messages; MESSAGE-INTEGRITY and FINGERPRINT are appended last by [build]. */
public class StunMessageBuilder(private val type: Int, private val transactionId: ByteArray) {
    init {
        require(transactionId.size == 12) { "transaction id must be 12 bytes" }
    }

    private val attrs = ArrayList<Pair<Int, ByteArray>>()

    public fun attribute(type: Int, value: ByteArray): StunMessageBuilder = apply { attrs += type to value }

    public fun username(value: String): StunMessageBuilder = attribute(StunConstants.ATTR_USERNAME, value.encodeToByteArray())

    public fun software(value: String): StunMessageBuilder = attribute(StunConstants.ATTR_SOFTWARE, value.encodeToByteArray())

    public fun priority(value: Long): StunMessageBuilder = attribute(StunConstants.ATTR_PRIORITY, ByteArray(4).also { it.putU32(0, value) })

    public fun useCandidate(): StunMessageBuilder = attribute(StunConstants.ATTR_USE_CANDIDATE, ByteArray(0))

    /** XOR-MAPPED-ADDRESS for an IPv4 (4 bytes) or IPv6 (16 bytes) address. */
    public fun xorMappedAddress(address: ByteArray, port: Int): StunMessageBuilder {
        require(address.size == 4 || address.size == 16)
        val v = ByteArray(4 + address.size)
        v[1] = if (address.size == 4) 0x01 else 0x02
        v.putU16(2, port xor (StunConstants.MAGIC_COOKIE ushr 16).toInt())
        val mask = ByteArray(16)
        mask.putU32(0, StunConstants.MAGIC_COOKIE)
        transactionId.copyInto(mask, 4)
        for (i in address.indices) v[4 + i] = (address[i].toInt() xor mask[i].toInt()).toByte()
        return attribute(StunConstants.ATTR_XOR_MAPPED_ADDRESS, v)
    }

    public fun errorCode(code: Int, reason: String): StunMessageBuilder {
        val r = reason.encodeToByteArray()
        val v = ByteArray(4 + r.size)
        v[2] = (code / 100).toByte()
        v[3] = (code % 100).toByte()
        r.copyInto(v, 4)
        return attribute(StunConstants.ATTR_ERROR_CODE, v)
    }

    /**
     * Serializes the message. With [integrityKey], appends MESSAGE-INTEGRITY; with [fingerprint], FINGERPRINT.
     */
    public fun build(integrityKey: ByteArray? = null, fingerprint: Boolean = true): ByteArray {
        val bodyLen = attrs.sumOf { 4 + ((it.second.size + 3) and 3.inv()) } +
            (if (integrityKey != null) 4 + Sha1.DIGEST_LENGTH else 0) + (if (fingerprint) 8 else 0)
        val out = ByteArray(StunConstants.HEADER_LENGTH + bodyLen)
        out.putU16(0, type)
        out.putU32(4, StunConstants.MAGIC_COOKIE)
        transactionId.copyInto(out, 8)
        var p = StunConstants.HEADER_LENGTH
        for ((t, v) in attrs) {
            out.putU16(p, t)
            out.putU16(p + 2, v.size)
            v.copyInto(out, p + 4)
            p += 4 + ((v.size + 3) and 3.inv())
        }
        if (integrityKey != null) {
            out.putU16(2, p - StunConstants.HEADER_LENGTH + 4 + Sha1.DIGEST_LENGTH)
            val mac = Sha1.hmac(integrityKey, out, 0, p)
            out.putU16(p, StunConstants.ATTR_MESSAGE_INTEGRITY)
            out.putU16(p + 2, Sha1.DIGEST_LENGTH)
            mac.copyInto(out, p + 4)
            p += 4 + Sha1.DIGEST_LENGTH
        }
        if (fingerprint) {
            out.putU16(2, p - StunConstants.HEADER_LENGTH + 8)
            val crc = Crc32.compute(out, 0, p) xor StunConstants.FINGERPRINT_XOR
            out.putU16(p, StunConstants.ATTR_FINGERPRINT)
            out.putU16(p + 2, 4)
            out.putU32(p + 4, crc)
            p += 8
        }
        out.putU16(2, p - StunConstants.HEADER_LENGTH)
        return out
    }
}
