package krtc.protocol

import krtc.protocol.crypto.Crc32
import krtc.protocol.crypto.Sha1
import krtc.protocol.rtp.AudioLevel
import krtc.protocol.rtp.Rtp
import krtc.protocol.rtp.RtpHeader
import krtc.protocol.sdp.Direction
import krtc.protocol.sdp.SessionDescription
import krtc.protocol.stun.StunConstants
import krtc.protocol.stun.StunMessage
import krtc.protocol.stun.StunMessageBuilder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
    @Test
    fun sha1KnownAnswers() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Sha1.digest(ByteArray(0)).toHex())
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sha1.digest("abc".encodeToByteArray()).toHex())
        val million = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", Sha1.digest(million).toHex())
    }

    @Test
    fun hmacSha1Rfc2202() {
        // RFC 2202 test case 2
        assertEquals(
            "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79",
            Sha1.hmac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).toHex(),
        )
        // test case 6: key longer than the block size
        assertEquals(
            "aa4ae5e15272d00e95705637ce8a3b55ed402112",
            Sha1.hmac(ByteArray(80) { 0xAA.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun crc32() {
        assertEquals(0xCBF43926L, Crc32.compute("123456789".encodeToByteArray()))
    }
}

class StunTest {
    // RFC 5769 §2.1
    private val sampleRequest = hexToBytes(
        """
        00 01 00 58 21 12 a4 42 b7 e7 a7 01 bc 34 d6 86 fa 87 df ae
        80 22 00 10 53 54 55 4e 20 74 65 73 74 20 63 6c 69 65 6e 74
        00 24 00 04 6e 00 01 ff 80 29 00 08 93 2f f9 b1 51 26 3b 36
        00 06 00 09 65 76 74 6a 3a 68 36 76 59 20 20 20
        00 08 00 14 9a ea a7 0c bf d8 cb 56 78 1e f2 b5 b2 d3 f2 49 c1 b5 71 a2
        80 28 00 04 e5 7a 3b cf
        """,
    )

    // RFC 5769 §2.2
    private val sampleResponse = hexToBytes(
        """
        01 01 00 3c 21 12 a4 42 b7 e7 a7 01 bc 34 d6 86 fa 87 df ae
        80 22 00 0b 74 65 73 74 20 76 65 63 74 6f 72 20
        00 20 00 08 00 01 a1 47 e1 12 a6 43
        00 08 00 14 2b 91 f5 99 fd 9e 90 c3 8c 74 89 f9 2a f9 ba 53 f0 6b e7 d7
        80 28 00 04 c0 7d 4c 96
        """,
    )
    private val password = "VOkJxbRl1RmTxUk/WvJxBt".encodeToByteArray()

    @Test
    fun parsesAndVerifiesRfc5769Request() {
        val m = assertNotNull(StunMessage.parse(sampleRequest))
        assertEquals(StunConstants.BINDING_REQUEST, m.type)
        assertEquals("evtj:h6vY", m.username)
        assertEquals(0x6e0001ffL, m.priority)
        assertTrue(m.has(StunConstants.ATTR_ICE_CONTROLLED))
        assertTrue(m.verifyIntegrity(password))
        assertFalse(m.verifyIntegrity("wrong".encodeToByteArray()))
        assertTrue(m.verifyFingerprint())
    }

    @Test
    fun rebuildsRfc5769ResponseBitExact() {
        val tid = sampleResponse.copyOfRange(8, 20)
        val built = StunMessageBuilder(StunConstants.BINDING_SUCCESS, tid)
            .software("test vector")
            .xorMappedAddress(byteArrayOf(192.toByte(), 0, 2, 1), 32853)
            .build(integrityKey = password, fingerprint = true)
        // The RFC pads SOFTWARE with a space where we pad with zero bytes, and MESSAGE-INTEGRITY covers
        // the padding, so the header, SOFTWARE value and XOR-MAPPED-ADDRESS must match bit for bit,
        // and the RFC's own MESSAGE-INTEGRITY/FINGERPRINT must verify.
        built[20 + 4 + 11] = 0x20
        assertContentEquals(sampleResponse.copyOfRange(0, 44), built.copyOfRange(0, 44))
        val parsed = assertNotNull(StunMessage.parse(sampleResponse))
        assertTrue(parsed.verifyIntegrity(password))
        assertTrue(parsed.verifyFingerprint())
    }

    @Test
    fun builderRoundTrip() {
        val tid = ByteArray(12) { it.toByte() }
        val bytes = StunMessageBuilder(StunConstants.BINDING_REQUEST, tid)
            .username("server:client").priority(12345).useCandidate()
            .build(integrityKey = "pwd".encodeToByteArray())
        val m = assertNotNull(StunMessage.parse(bytes))
        assertEquals("server:client", m.username)
        assertTrue(m.useCandidate)
        assertTrue(m.verifyIntegrity("pwd".encodeToByteArray()))
        assertTrue(m.verifyFingerprint())
        bytes[25] = (bytes[25] + 1).toByte()
        assertFalse(assertNotNull(StunMessage.parse(bytes)).verifyIntegrity("pwd".encodeToByteArray()))
    }

    @Test
    fun rejectsNonStun() {
        assertNull(StunMessage.parse(ByteArray(20)))
        assertEquals(PacketKind.RTP_OR_RTCP, classifyPacket(byteArrayOf(0x80.toByte())))
        assertEquals(PacketKind.DTLS, classifyPacket(byteArrayOf(22)))
        assertEquals(PacketKind.STUN, classifyPacket(sampleRequest))
    }
}

class RtpTest {
    @Test
    fun parsesHeaderWithOneByteExtension() {
        // V=2, X=1, PT=111, seq=0x1234, ts=0x01020304, ssrc=0xdeadbeef, ext 0xBEDE len 1: id=1 len=1 value 0x85, pad
        val pkt = hexToBytes("90 6f 12 34 01 02 03 04 de ad be ef be de 00 01 10 85 00 00 aa bb cc")
        val h = RtpHeader()
        assertTrue(h.parse(pkt, 0, pkt.size))
        assertEquals(111, h.payloadType)
        assertEquals(0x1234, h.sequenceNumber)
        assertEquals(0x01020304L, h.timestamp)
        assertEquals(0xdeadbeefL, h.ssrc)
        assertEquals(20, h.headerLength)
        assertEquals(3, h.payloadLength(pkt, 0, pkt.size))
        val at = h.findExtension(pkt, 0, 1)
        assertEquals(17, at)
        val level = AudioLevel.read(pkt, at)
        assertTrue(level.voiceActivity)
        assertEquals(5, level.level)
        assertEquals(-1, h.findExtension(pkt, 0, 2))
        assertFalse(Rtp.isRtcp(pkt, 0, pkt.size))
    }

    @Test
    fun seqDeltaWraps() {
        assertEquals(1, Rtp.seqDelta(0xFFFF, 0))
        assertEquals(-1, Rtp.seqDelta(0, 0xFFFF))
        assertEquals(10, Rtp.seqDelta(100, 110))
    }

    @Test
    fun detectsRtcp() {
        val sr = hexToBytes("80 c8 00 06 00 00 00 01 00 00 00 00")
        assertTrue(Rtp.isRtcp(sr, 0, sr.size))
    }
}

class SdpTest {
    private val chromeAnswer = listOf(
        "v=0",
        "o=- 4611731400430051336 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "a=group:BUNDLE 0 1",
        "a=msid-semantic: WMS",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "c=IN IP4 0.0.0.0",
        "a=rtcp:9 IN IP4 0.0.0.0",
        "a=ice-ufrag:abcd",
        "a=ice-pwd:0123456789abcdefghijklmn",
        "a=fingerprint:sha-256 AA:BB:CC",
        "a=setup:active",
        "a=mid:0",
        "a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level",
        "a=sendonly",
        "a=rtcp-mux",
        "a=rtpmap:111 opus/48000/2",
        "a=fmtp:111 minptime=10;useinbandfec=1",
        "a=ssrc:1001 cname:x",
        "a=ssrc:1001 msid:- t",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "c=IN IP4 0.0.0.0",
        "a=mid:1",
        "a=recvonly",
        "a=rtpmap:111 opus/48000/2",
        "",
    ).joinToString("\r\n")

    @Test
    fun parsesAndRoundTrips() {
        val sd = SessionDescription.parse(chromeAnswer)
        assertEquals(2, sd.media.size)
        val m0 = sd.media[0]
        assertEquals("0", m0.mid)
        assertEquals(Direction.SENDONLY, m0.direction)
        assertEquals(Direction.RECVONLY, sd.media[1].direction)
        assertEquals("abcd", sd.iceUfrag(m0))
        assertEquals("sha-256" to "AA:BB:CC", sd.fingerprint(m0))
        assertEquals("active", sd.setup(m0))
        assertEquals(listOf(1001L), m0.ssrcs)
        assertEquals("opus/48000/2", m0.rtpMaps[111])
        assertEquals(AudioLevel.URI, m0.extensions[1])
        assertEquals(chromeAnswer, sd.toString())
        // session-level fallback
        assertEquals("abcd", sd.iceUfrag(sd.media[0]))
        assertNull(sd.media[1].attribute("ice-ufrag"))
    }
}
