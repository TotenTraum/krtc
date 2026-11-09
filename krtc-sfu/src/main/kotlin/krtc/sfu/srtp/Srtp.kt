package krtc.sfu.srtp

import krtc.protocol.constantTimeEquals
import krtc.protocol.putU32
import krtc.protocol.rtp.RtpHeader
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        /** Server preference: AES-GCM first (one AES-NI pass, no HMAC-SHA1). */
        public val preference: List<SrtpProfile> = listOf(AEAD_AES_128_GCM, AES128_CM_HMAC_SHA1_80)

        public fun byId(id: Int): SrtpProfile? = entries.firstOrNull { it.id == id }
    }
}

/** SRTP key derivation function (RFC 3711 §4.3.1) with key_derivation_rate = 0. */
public object SrtpKdf {
    public const val LABEL_RTP_ENCRYPTION: Int = 0
    public const val LABEL_RTP_AUTH: Int = 1
    public const val LABEL_RTP_SALT: Int = 2

    public fun derive(masterKey: ByteArray, masterSalt: ByteArray, label: Int, length: Int): ByteArray {
        val block = ByteArray(16)
        masterSalt.copyInto(block)
        block[7] = (block[7].toInt() xor label).toByte()
        val aes = Cipher.getInstance("AES/CTR/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), IvParameterSpec(block))
        return aes.doFinal(ByteArray(length))
    }
}

/**
 * One direction of an SRTP session (RTP only; RTCP is terminated by the SFU and not forwarded).
 *
 * Not thread-safe: each instance is owned by a single shard thread (see Shard).
 * [protect] writes into a separate output buffer so one decrypted packet can be re-encrypted
 * for many subscribers without copying it first.
 */
public class SrtpContext internal constructor(
    public val profile: SrtpProfile,
    sessionKey: ByteArray,
    private val salt: ByteArray,
    sessionAuthKey: ByteArray?,
) {
    private val encKey = SecretKeySpec(sessionKey, "AES")
    private val cipher: Cipher = Cipher.getInstance(if (profile.aead) "AES/GCM/NoPadding" else "AES/CTR/NoPadding")
    private val mac: Mac? = if (profile.aead) null else Mac.getInstance("HmacSHA1").apply {
        init(SecretKeySpec(requireNotNull(sessionAuthKey), "HmacSHA1"))
    }
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

    /** Estimated packet index for [seq] (RFC 3711 Appendix A). */
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
        if (index > (s.roc shl 16 or s.highestSeq.toLong())) {
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
        return if (profile.aead) {
            cipher.init(Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(dst, dstOff, hl)
            hl + cipher.doFinal(src, srcOff + hl, payloadLen, dst, dstOff + hl)
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, encKey, IvParameterSpec(iv))
            cipher.doFinal(src, srcOff + hl, payloadLen, dst, dstOff + hl)
            val m = mac!!
            m.update(dst, dstOff, len)
            rocBytes.putU32(0, roc)
            m.update(rocBytes)
            m.doFinal(tag, 0)
            tag.copyInto(dst, dstOff + len, 0, profile.tagLength)
            len + profile.tagLength
        }
    }

    /** Authenticates and decrypts SRTP in place; returns the RTP length, or -1 (auth failure, replay, malformed). */
    public fun unprotect(buf: ByteArray, off: Int, len: Int): Int {
        if (len < 12 + profile.tagLength) return -1
        if (!header.parse(buf, off, len - profile.tagLength)) return -1
        val hl = header.headerLength
        val seq = header.sequenceNumber
        val ssrc = header.ssrc
        val s = streams[ssrc] ?: StreamState(0, seq).also { streams[ssrc] = it }
        val roc = estimateRoc(s, seq)
        val index = (roc shl 16) or seq.toLong()
        if (isReplay(s, index)) return -1
        buildIv(ssrc, roc, seq)
        val plainLen: Int
        try {
            if (profile.aead) {
                cipher.init(Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(128, iv))
                cipher.updateAAD(buf, off, hl)
                plainLen = hl + cipher.doFinal(buf, off + hl, len - hl, buf, off + hl)
            } else {
                val authLen = len - profile.tagLength
                val m = mac!!
                m.update(buf, off, authLen)
                rocBytes.putU32(0, roc)
                m.update(rocBytes)
                m.doFinal(tag, 0)
                if (!constantTimeEquals(tag, 0, buf, off + authLen, profile.tagLength)) return -1
                cipher.init(Cipher.DECRYPT_MODE, encKey, IvParameterSpec(iv))
                cipher.doFinal(buf, off + hl, authLen - hl, buf, off + hl)
                plainLen = authLen
            }
        } catch (_: javax.crypto.AEADBadTagException) {
            return -1
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

        /** Splits DTLS-exported keying material (RFC 5764 §4.2) into (client, server) contexts. */
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
