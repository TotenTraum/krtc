package krtc.sfu

import krtc.protocol.hexToBytes
import krtc.protocol.toHex
import krtc.sfu.srtp.SrtcpContext
import krtc.sfu.srtp.SrtpContext
import krtc.sfu.srtp.SrtpKdf
import krtc.sfu.srtp.SrtpProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SrtpTest {
    @Test
    fun keyDerivationRfc3711AppendixB3() {
        val key = hexToBytes("E1F97A0D3E018BE0D64FA32C06DE4139")
        val salt = hexToBytes("0EC675AD498AFEEBB6960B3AABE6")
        assertEquals("c61e7a93744f39ee10734afe3ff7a087", SrtpKdf.derive(key, salt, SrtpKdf.LABEL_RTP_ENCRYPTION, 16).toHex())
        assertEquals("30cbbc08863d8c85d49db34a9ae1", SrtpKdf.derive(key, salt, SrtpKdf.LABEL_RTP_SALT, 14).toHex())
        assertEquals(
            "cebe321f6ff7716b6fd4ab49af256a156d38baa4",
            SrtpKdf.derive(key, salt, SrtpKdf.LABEL_RTP_AUTH, 20).toHex(),
        )
    }

    @Test
    fun aesGcmRfc7714Section16_1_1() {
        val key = hexToBytes("000102030405060708090a0b0c0d0e0f")
        val salt = hexToBytes("517569642070726f2071756f")
        val plain = hexToBytes(
            "8040f17b 8041f8d3 5501a0b2 47616c6c 69612065 7374206f 6d6e6973 20646976 69736120 696e2070 61727465 73207472 6573",
        )
        val expected = hexToBytes(
            "8040f17b 8041f8d3 5501a0b2 f24de3a3 fb34de6c acba861c 9d7e4bca be633bd5 0d294e6f 42a5f47a 51c7d19b " +
                "36de3adf 8833899d 7f27beb1 6a9152cf 765ee439 0cce",
        )
        val tx = SrtpContext(SrtpProfile.AEAD_AES_128_GCM, key, salt, null)
        val out = ByteArray(plain.size + 16)
        val n = tx.protect(plain, 0, plain.size, out, 0)
        assertContentEquals(expected, out.copyOf(n))

        val rx = SrtpContext(SrtpProfile.AEAD_AES_128_GCM, key, salt, null)
        val m = rx.unprotect(out, 0, n)
        assertContentEquals(plain, out.copyOf(m))
    }

    @Test
    fun srtcpAesGcmRfc7714Section17_1() {
        val key = hexToBytes("000102030405060708090a0b0c0d0e0f")
        val salt = hexToBytes("517569642070726f2071756f")
        val rtcp = hexToBytes(
            "81c8000d 4d617273 4e545031 4e545032 52545020 0000042a 0000e930 4c756e61 deadbeef deadbeef deadbeef deadbeef deadbeef",
        )
        val expected = hexToBytes(
            "81c8000d 4d617273 63e94885 dcdab67c a727d766 2f6b7e99 7ff5c0f7 6c06f32d c676a5f1 730d6fda " +
                "4ce09b46 86303ded 0bb9275b c84aa458 96cf4d2f c5abf872 45d9eade 800005d4",
        )
        val tx = SrtcpContext(SrtpProfile.AEAD_AES_128_GCM, key, salt, null)
        tx.setNextIndex(0x5d4)
        val out = ByteArray(rtcp.size + tx.overhead)
        val n = tx.protect(rtcp, 0, rtcp.size, out, 0)
        assertContentEquals(expected, out.copyOf(n))

        val rx = SrtcpContext(SrtpProfile.AEAD_AES_128_GCM, key, salt, null)
        val copy = out.copyOf(n)
        assertEquals(rtcp.size, rx.unprotect(out, 0, n))
        assertContentEquals(rtcp, out.copyOf(rtcp.size))
        assertEquals(-1, rx.unprotect(copy, 0, n), "replayed SRTCP must be rejected")
    }

    @Test
    fun srtcpRoundTripForBothProfiles() {
        for (profile in SrtpProfile.entries) {
            val masterKey = ByteArray(profile.keyLength) { (it * 5).toByte() }
            val masterSalt = ByteArray(profile.saltLength) { (it + 9).toByte() }
            val tx = SrtcpContext.fromMaster(profile, masterKey, masterSalt)
            val rx = SrtcpContext.fromMaster(profile, masterKey, masterSalt)
            repeat(3) {
                val rtcp = krtc.protocol.rtp.Rtcp.concat(krtc.protocol.rtp.Rtcp.receiverReport(7), krtc.protocol.rtp.Rtcp.pli(7, 99))
                val out = ByteArray(rtcp.size + tx.overhead)
                val n = tx.protect(rtcp, 0, rtcp.size, out, 0)
                assertEquals(rtcp.size, rx.unprotect(out, 0, n), "$profile")
                assertContentEquals(rtcp, out.copyOf(rtcp.size))
            }
            val forged = ByteArray(40).also { it[0] = 0x80.toByte(); it[1] = 0xc9.toByte(); it[36] = 0x80.toByte() }
            assertEquals(-1, rx.unprotect(forged, 0, forged.size), "$profile forged")
        }
    }

    @Test
    fun roundTripAndReplayForBothProfiles() {
        for (profile in SrtpProfile.entries) {
            val masterKey = ByteArray(profile.keyLength) { (it * 7).toByte() }
            val masterSalt = ByteArray(profile.saltLength) { (it * 3 + 1).toByte() }
            val tx = SrtpContext.fromMaster(profile, masterKey, masterSalt)
            val rx = SrtpContext.fromMaster(profile, masterKey, masterSalt)
            for (seq in listOf(65533, 65534, 65535, 0, 1, 2)) { // crosses the ROC boundary
                val rtp = hexToBytes("80 6f 00 00 00 00 03 c0 11 22 33 44 de ad be ef ca fe")
                rtp[2] = (seq ushr 8).toByte()
                rtp[3] = seq.toByte()
                val srtp = ByteArray(rtp.size + profile.tagLength)
                val n = tx.protect(rtp, 0, rtp.size, srtp, 0)
                val copy = srtp.copyOf(n)
                assertEquals(rtp.size, rx.unprotect(srtp, 0, n), "$profile seq=$seq")
                assertContentEquals(rtp, srtp.copyOf(rtp.size))
                assertEquals(-1, rx.unprotect(copy, 0, n), "$profile replay seq=$seq must be rejected")
            }
            val bad = ByteArray(40)
            bad[0] = 0x80.toByte()
            assertEquals(-1, rx.unprotect(bad, 0, bad.size), "$profile forged packet must be rejected")
        }
    }
}
