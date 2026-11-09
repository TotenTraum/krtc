package krtc.protocol.crypto

/**
 * SHA-1 and HMAC-SHA1 (RFC 2104) in pure Kotlin, for STUN MESSAGE-INTEGRITY on every target.
 * Hot paths on the JVM (SRTP authentication) use the platform's accelerated implementation instead.
 */
public object Sha1 {
    public const val DIGEST_LENGTH: Int = 20
    private const val BLOCK = 64

    public fun digest(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        val h = intArrayOf(0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476, 0xC3D2E1F0.toInt())
        val bitLen = length.toLong() * 8
        val padded = ((length + 9 + BLOCK - 1) / BLOCK) * BLOCK
        val msg = ByteArray(padded)
        data.copyInto(msg, 0, offset, offset + length)
        msg[length] = 0x80.toByte()
        for (i in 0 until 8) msg[padded - 1 - i] = (bitLen ushr (8 * i)).toByte()
        val w = IntArray(80)
        for (block in 0 until padded step BLOCK) {
            for (i in 0 until 16) {
                val p = block + 4 * i
                w[i] = ((msg[p].toInt() and 0xFF) shl 24) or ((msg[p + 1].toInt() and 0xFF) shl 16) or
                    ((msg[p + 2].toInt() and 0xFF) shl 8) or (msg[p + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]
            for (i in 0 until 80) {
                val f: Int
                val k: Int
                when {
                    i < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                    i < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                    i < 60 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt() }
                    else -> { f = b xor c xor d; k = 0xCA62C1D6.toInt() }
                }
                val t = a.rotateLeft(5) + f + e + k + w[i]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = t
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e
        }
        val out = ByteArray(DIGEST_LENGTH)
        for (i in 0 until 5) for (j in 0 until 4) out[4 * i + j] = (h[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    public fun hmac(key: ByteArray, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        val k = if (key.size > BLOCK) digest(key) else key
        val ipad = ByteArray(BLOCK + length)
        val opad = ByteArray(BLOCK + DIGEST_LENGTH)
        for (i in 0 until BLOCK) {
            val kb = if (i < k.size) k[i].toInt() else 0
            ipad[i] = (kb xor 0x36).toByte()
            opad[i] = (kb xor 0x5C).toByte()
        }
        data.copyInto(ipad, BLOCK, offset, offset + length)
        digest(ipad).copyInto(opad, BLOCK)
        return digest(opad)
    }
}

/** CRC-32 (ISO-HDLC), as used by the STUN FINGERPRINT attribute. */
public object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    public fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        var c = -1
        for (i in offset until offset + length) c = table[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        return (c.inv().toLong()) and 0xFFFFFFFFL
    }
}
