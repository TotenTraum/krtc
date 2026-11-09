package krtc.sfu.srtp

import krtc.protocol.constantTimeEquals
import krtc.protocol.putU32
import krtc.protocol.rtp.RtpHeader
import krtc.sfu.platform.AesCtr
import krtc.sfu.platform.AesGcm
import krtc.sfu.platform.HmacSha1

/** SRTP protection profiles negotiated through DTLS use_srtp (RFC 5764, RFC 7714). */
public enum class SrtpProfile(
    public val id: Int,
    public val keyLength: Int,
    public val saltLength: Int,
    public val tagLength: Int,
    public val aead: Boolean,
) {
    AEAD_AES_128_GCM(0x0007, 16, 12, 16, true),
    AES128_CM_HMAC_SHA1_80(0x0001, 16, 14, 10, false),
    ;

    public companion object {
        /** Server preference: AES-GCM first (one AES pass, no HMAC-SHA1). */
        public val preference: List<SrtpProfile> = listOf(AEAD_AES_128_GCM, AES128_CM_HMAC_SHA1_80)

        public fun byId(id: Int): SrtpProfile? = entries.firstOrNull { it.id == id }
    }
}

/** SRTP key derivation function (RFC 3711 §4.3.1) with key_derivation_rate = 0. */
public object SrtpKdf {
    public const val LABEL_RTP_ENCRYPTION: Int = 0
    public const val LABEL_RTP_AUTH: Int = 1
    public const val LABEL_RTP_SALT: Int = 2
    public const val LABEL_RTCP_ENCRYPTION: Int = 3
    public const val LABEL_RTCP_AUTH: Int = 4
    public const val LABEL_RTCP_SALT: Int = 5

    public fun derive(masterKey: ByteArray, masterSalt: ByteArray, label: Int, length: Int): ByteArray {
        val block = ByteArray(16)
        masterSalt.copyInto(block)
        block[7] = (block[7].toInt() xor label).toByte()
        val out = ByteArray(length)
        AesCtr(masterKey).process(block, ByteArray(length), 0, length, out, 0)
        return out
    }
}

/**
 * One direction of an SRTP session for RTP packets (RFC 3711, RFC 7714).
 *
 * Not thread-safe: each instance is owned by one shard. [protect] writes into a separate output
 * buffer, so one decrypted packet can be re-encrypted for many subscribers without copying it first.
 */
public class SrtpContext(
    public val profile: SrtpProfile,
    sessionKey: ByteArray,
    private val salt: ByteArray,
    sessionAuthKey: ByteArray?,
) {
    private val ctr: AesCtr? = if (profile.aead) null else AesCtr(sessionKey)
    private val gcm: AesGcm? = if (profile.aead) AesGcm(sessionKey) else null
    private val mac: HmacSha1? = if (profile.aead) null else HmacSha1(requireNotNull(sessionAuthKey))
    private val iv = ByteArray(if (profile.aead) 12 else 16)
    private val rocBytes = ByteArray(4)
    private val tag = ByteArray(20)
    private val header = RtpHeader()
    private val streams = HashMap<Long, StreamState>()

    /** Per-SSRC rollover counter, highest sequence number and replay window (RFC 3711 §3.3.1–3.3.2). */
    private class StreamState(var roc: Long, var highestSeq: Int) {
        var replayMask = 0L
        var highestIndex = -1L
    }

    /** Estimated rollover counter for [seq] (RFC 3711 Appendix A). */
    private fun estimateRoc(s: StreamState, seq: Int): Long {
        val sl = s.highestSeq
        return if (sl < 32768) {
            if (seq - sl > 32768) (s.roc - 1) and 0xFFFFFFFFL else s.roc
        } else {
            if (sl - 32768 > seq) (s.roc + 1) and 0xFFFFFFFFL else s.roc
        }
    }

    private fun commit(s: StreamState, roc: Long, seq: Int) {
        val index = (roc shl 16) or seq.toLong()
        if (index > ((s.roc shl 16) or s.highestSeq.toLong())) {
            s.roc = roc
            s.highestSeq = seq
        }
    }

    private fun buildIv(ssrc: Long, roc: Long, seq: Int) {
        if (profile.aead) {
            // RFC 7714 §8.1: 00 00 || SSRC || ROC || SEQ, XOR salt
            iv.fill(0)
            iv.putU32(2, ssrc)
            iv.putU32(6, roc)
            iv[10] = (seq ushr 8).toByte()
            iv[11] = seq.toByte()
            for (i in 0 until 12) iv[i] = (iv[i].toInt() xor salt[i].toInt()).toByte()
        } else {
            // RFC 3711 §4.1.1: (salt * 2^16) XOR (SSRC * 2^64) XOR (index * 2^16)
            salt.copyInto(iv)
            iv[14] = 0
            iv[15] = 0
            iv[4] = (iv[4].toInt() xor (ssrc ushr 24).toInt()).toByte()
            iv[5] = (iv[5].toInt() xor (ssrc ushr 16).toInt()).toByte()
            iv[6] = (iv[6].toInt() xor (ssrc ushr 8).toInt()).toByte()
            iv[7] = (iv[7].toInt() xor ssrc.toInt()).toByte()
            val index = (roc shl 16) or seq.toLong()
            for (i in 0 until 6) iv[8 + i] = (iv[8 + i].toInt() xor (index ushr (40 - 8 * i)).toInt()).toByte()
        }
    }

    /**
     * Encrypts the RTP packet `src[srcOff, srcOff+len)` into `dst` at [dstOff]; returns the SRTP length.
     * `dst` must have room for `len + profile.tagLength` bytes.
     */
    public fun protect(src: ByteArray, srcOff: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        require(header.parse(src, srcOff, len)) { "not RTP" }
        val hl = header.headerLength
        val seq = header.sequenceNumber
        val ssrc = header.ssrc
        val s = streams.getOrPut(ssrc) { StreamState(0, seq) }
        val roc = estimateRoc(s, seq)
        commit(s, roc, seq)
        if (src !== dst || srcOff != dstOff) src.copyInto(dst, dstOff, srcOff, srcOff + hl)
        buildIv(ssrc, roc, seq)
        val payloadLen = len - hl
        if (gcm != null) {
            return hl + gcm.encrypt(iv, dst, dstOff, hl, src, srcOff + hl, payloadLen, dst, dstOff + hl)
        }
        ctr!!.process(iv, src, srcOff + hl, payloadLen, dst, dstOff + hl)
        val m = mac!!
        m.update(dst, dstOff, len)
        rocBytes.putU32(0, roc)
        m.update(rocBytes, 0, 4)
        m.doFinal(tag, 0)
        tag.copyInto(dst, dstOff + len, 0, profile.tagLength)
        return len + profile.tagLength
    }

    /** Authenticates and decrypts SRTP in place; returns the RTP length, or -1 (auth failure, replay, malformed). */
    public fun unprotect(buf: ByteArray, off: Int, len: Int): Int {
        if (len < 12 + profile.tagLength) return -1
        if (!header.parse(buf, off, len - profile.tagLength)) return -1
        val hl = header.headerLength
        val seq = header.sequenceNumber
        val ssrc = header.ssrc
        val s = streams.getOrPut(ssrc) { StreamState(0, seq) }
        val roc = estimateRoc(s, seq)
        val index = (roc shl 16) or seq.toLong()
        if (isReplay(s, index)) return -1
        buildIv(ssrc, roc, seq)
        val plainLen: Int
        if (gcm != null) {
            val n = gcm.decrypt(iv, buf, off, hl, buf, off + hl, len - hl, buf, off + hl)
            if (n < 0) return -1
            plainLen = hl + n
        } else {
            val authLen = len - profile.tagLength
            val m = mac!!
            m.update(buf, off, authLen)
            rocBytes.putU32(0, roc)
            m.update(rocBytes, 0, 4)
            m.doFinal(tag, 0)
            if (!constantTimeEquals(tag, 0, buf, off + authLen, profile.tagLength)) return -1
            ctr!!.process(iv, buf, off + hl, authLen - hl, buf, off + hl)
            plainLen = authLen
        }
        markReplay(s, index)
        commit(s, roc, seq)
        return plainLen
    }

    private fun isReplay(s: StreamState, index: Long): Boolean {
        if (s.highestIndex < 0) return false
        val delta = index - s.highestIndex
        if (delta > 0) return false
        if (-delta >= 64) return true
        return (s.replayMask ushr (-delta).toInt()) and 1L != 0L
    }

    private fun markReplay(s: StreamState, index: Long) {
        if (s.highestIndex < 0) {
            s.highestIndex = index
            s.replayMask = 1L
            return
        }
        val delta = index - s.highestIndex
        if (delta > 0) {
            s.replayMask = if (delta >= 64) 1L else (s.replayMask shl delta.toInt()) or 1L
            s.highestIndex = index
        } else {
            s.replayMask = s.replayMask or (1L shl (-delta).toInt())
        }
    }

    public companion object {
        /** Derives session keys from a master key and salt (RFC 3711 §4.3). */
        public fun fromMaster(profile: SrtpProfile, masterKey: ByteArray, masterSalt: ByteArray): SrtpContext = SrtpContext(
            profile,
            SrtpKdf.derive(masterKey, masterSalt, SrtpKdf.LABEL_RTP_ENCRYPTION, profile.keyLength),
            SrtpKdf.derive(masterKey, masterSalt, SrtpKdf.LABEL_RTP_SALT, profile.saltLength),
            if (profile.aead) null else SrtpKdf.derive(masterKey, masterSalt, SrtpKdf.LABEL_RTP_AUTH, 20),
        )

        /** Splits DTLS-exported keying material (RFC 5764 §4.2) into (client → server, server → client) contexts. */
        public fun fromDtlsExport(profile: SrtpProfile, material: ByteArray): Pair<SrtpContext, SrtpContext> {
            val k = profile.keyLength
            val s = profile.saltLength
            require(material.size == 2 * (k + s))
            val clientKey = material.copyOfRange(0, k)
            val serverKey = material.copyOfRange(k, 2 * k)
            val clientSalt = material.copyOfRange(2 * k, 2 * k + s)
            val serverSalt = material.copyOfRange(2 * k + s, 2 * (k + s))
            return fromMaster(profile, clientKey, clientSalt) to fromMaster(profile, serverKey, serverSalt)
        }
    }
}

/**
 * One direction of SRTCP (RFC 3711 §3.4; RFC 7714 §9 for AES-GCM). Not thread-safe (owned by a shard).
 *
 * Layouts:
 * - AES-GCM: header(8) || ciphertext || tag(16) || E|index(4); AAD = header(8) || E|index.
 * - AES-CM:  header(8) || ciphertext || E|index(4) || HMAC-SHA1-80 tag over everything before it.
 */
public class SrtcpContext(
    public val profile: SrtpProfile,
    sessionKey: ByteArray,
    private val salt: ByteArray,
    sessionAuthKey: ByteArray?,
) {
    private val ctr: AesCtr? = if (profile.aead) null else AesCtr(sessionKey)
    private val gcm: AesGcm? = if (profile.aead) AesGcm(sessionKey) else null
    private val mac: HmacSha1? = if (profile.aead) null else HmacSha1(requireNotNull(sessionAuthKey))
    private val iv = ByteArray(if (profile.aead) 12 else 16)
    private val aad = ByteArray(12)
    private val tag = ByteArray(20)
    private var txIndex = 0L
    private val replay = HashMap<Long, LongArray>() // ssrc → [highest index, mask]

    /** Test hook: the next [protect] uses SRTCP index [index]. */
    internal fun setNextIndex(index: Long) {
        txIndex = (index - 1) and 0x7FFFFFFFL
    }

    /** Bytes added by [protect]. */
    public val overhead: Int get() = 4 + profile.tagLength

    private fun buildIv(ssrc: Long, index: Long) {
        if (profile.aead) {
            iv.fill(0)
            iv.putU32(2, ssrc)
            iv.putU32(8, index)
            for (i in 0 until 12) iv[i] = (iv[i].toInt() xor salt[i].toInt()).toByte()
        } else {
            salt.copyInto(iv)
            iv[14] = 0
            iv[15] = 0
            for (i in 0 until 4) iv[4 + i] = (iv[4 + i].toInt() xor (ssrc ushr (24 - 8 * i)).toInt()).toByte()
            // index * 2^16: the 31-bit index occupies bytes 10..13
            for (i in 0 until 4) iv[10 + i] = (iv[10 + i].toInt() xor (index ushr (24 - 8 * i)).toInt()).toByte()
        }
    }

    /** Protects the RTCP compound `src[srcOff, srcOff+len)` into `dst`; returns the SRTCP length. */
    public fun protect(src: ByteArray, srcOff: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        require(len >= 8)
        txIndex = (txIndex + 1) and 0x7FFFFFFFL
        val index = txIndex
        val ssrc = ((src[srcOff + 4].toLong() and 0xFF) shl 24) or ((src[srcOff + 5].toLong() and 0xFF) shl 16) or
            ((src[srcOff + 6].toLong() and 0xFF) shl 8) or (src[srcOff + 7].toLong() and 0xFF)
        val eIndex = 0x80000000L or index
        buildIv(ssrc, index)
        src.copyInto(dst, dstOff, srcOff, srcOff + 8)
        if (gcm != null) {
            src.copyInto(aad, 0, srcOff, srcOff + 8)
            aad.putU32(8, eIndex)
            val n = gcm.encrypt(iv, aad, 0, 12, src, srcOff + 8, len - 8, dst, dstOff + 8)
            dst.putU32(dstOff + 8 + n, eIndex)
            return 8 + n + 4
        }
        ctr!!.process(iv, src, srcOff + 8, len - 8, dst, dstOff + 8)
        dst.putU32(dstOff + len, eIndex)
        val m = mac!!
        m.update(dst, dstOff, len + 4)
        m.doFinal(tag, 0)
        tag.copyInto(dst, dstOff + len + 4, 0, profile.tagLength)
        return len + 4 + profile.tagLength
    }

    /** Authenticates and decrypts SRTCP in place; returns the RTCP length, or -1. */
    public fun unprotect(buf: ByteArray, off: Int, len: Int): Int {
        if (len < 8 + overhead) return -1
        val ssrc = ((buf[off + 4].toLong() and 0xFF) shl 24) or ((buf[off + 5].toLong() and 0xFF) shl 16) or
            ((buf[off + 6].toLong() and 0xFF) shl 8) or (buf[off + 7].toLong() and 0xFF)
        val eAt = if (profile.aead) off + len - 4 else off + len - profile.tagLength - 4
        val eIndex = ((buf[eAt].toLong() and 0xFF) shl 24) or ((buf[eAt + 1].toLong() and 0xFF) shl 16) or
            ((buf[eAt + 2].toLong() and 0xFF) shl 8) or (buf[eAt + 3].toLong() and 0xFF)
        if (eIndex and 0x80000000L == 0L) return -1 // unencrypted SRTCP is not accepted
        val index = eIndex and 0x7FFFFFFFL
        val window = replay.getOrPut(ssrc) { longArrayOf(-1, 0) }
        if (window[0] >= 0) {
            val delta = index - window[0]
            if (delta <= 0 && (-delta >= 64 || (window[1] ushr (-delta).toInt()) and 1L != 0L)) return -1
        }
        buildIv(ssrc, index)
        val plainLen: Int
        if (gcm != null) {
            buf.copyInto(aad, 0, off, off + 8)
            aad.putU32(8, eIndex)
            val n = gcm.decrypt(iv, aad, 0, 12, buf, off + 8, len - 12, buf, off + 8)
            if (n < 0) return -1
            plainLen = 8 + n
        } else {
            val m = mac!!
            m.update(buf, off, eAt + 4 - off)
            m.doFinal(tag, 0)
            if (!constantTimeEquals(tag, 0, buf, eAt + 4, profile.tagLength)) return -1
            ctr!!.process(iv, buf, off + 8, eAt - off - 8, buf, off + 8)
            plainLen = eAt - off
        }
        val delta = index - window[0]
        if (window[0] < 0) {
            window[0] = index; window[1] = 1
        } else if (delta > 0) {
            window[1] = if (delta >= 64) 1L else (window[1] shl delta.toInt()) or 1L
            window[0] = index
        } else {
            window[1] = window[1] or (1L shl (-delta).toInt())
        }
        return plainLen
    }

    public companion object {
        public fun fromMaster(profile: SrtpProfile, masterKey: ByteArray, masterSalt: ByteArray): SrtcpContext = SrtcpContext(
            profile,
            SrtpKdf.derive(masterKey, masterSalt, SrtpKdf.LABEL_RTCP_ENCRYPTION, profile.keyLength),
            SrtpKdf.derive(masterKey, masterSalt, SrtpKdf.LABEL_RTCP_SALT, profile.saltLength),
            if (profile.aead) null else SrtpKdf.derive(masterKey, masterSalt, SrtpKdf.LABEL_RTCP_AUTH, 20),
        )
    }
}

/** Both directions' RTP and RTCP contexts derived from one DTLS export. */
public class SrtpSessionKeys(
    public val rtpIn: SrtpContext,
    public val rtpOut: SrtpContext,
    public val rtcpIn: SrtcpContext,
    public val rtcpOut: SrtcpContext,
) {
    public companion object {
        /** RFC 5764 §4.2: client key, server key, client salt, server salt. The SFU is the DTLS server. */
        public fun forDtlsServer(profile: SrtpProfile, material: ByteArray): SrtpSessionKeys {
            val k = profile.keyLength
            val s = profile.saltLength
            require(material.size == 2 * (k + s))
            val clientKey = material.copyOfRange(0, k)
            val serverKey = material.copyOfRange(k, 2 * k)
            val clientSalt = material.copyOfRange(2 * k, 2 * k + s)
            val serverSalt = material.copyOfRange(2 * k + s, 2 * (k + s))
            return SrtpSessionKeys(
                rtpIn = SrtpContext.fromMaster(profile, clientKey, clientSalt),
                rtpOut = SrtpContext.fromMaster(profile, serverKey, serverSalt),
                rtcpIn = SrtcpContext.fromMaster(profile, clientKey, clientSalt),
                rtcpOut = SrtcpContext.fromMaster(profile, serverKey, serverSalt),
            )
        }
    }
}
