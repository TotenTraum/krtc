package krtc.protocol

import krtc.protocol.rtp.Rtcp
import krtc.protocol.rtp.TransportCc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TransportCcTest {
    @Test
    fun feedbackRoundTripWithLossesAndLargeDeltas() {
        val base = 64_000L * 1000 + 1_234
        // small deltas, a loss, a large (> 63.75 ms) delta, a negative delta (reordering), another loss at the end
        val arrivals = longArrayOf(base, base + 500, -1, base + 1_250, base + 90_000, base + 89_000, base + 89_500, -1)
        val built = TransportCc.feedback(0x6b727463, 42, 65_530, arrivals, arrivals.size, feedbackCount = 7)
        assertEquals(7, built.count, "the trailing loss is not reported")
        assertEquals(0, built.packet.size % 4)

        var seen = 0
        Rtcp.forEach(built.packet, 0, built.packet.size) { type, fmt, at, size ->
            assertEquals(Rtcp.RTPFB, type)
            assertEquals(TransportCc.FMT, fmt)
            val fb = assertNotNull(TransportCc.parse(built.packet, at, size))
            assertEquals(65_530, fb.baseSeq)
            assertEquals(7, fb.feedbackCount)
            assertEquals(7, fb.count)
            assertEquals(65_535, fb.seq(5))
            assertEquals(0, fb.seq(6))
            assertEquals(TransportCc.LOST, fb.arrivalMicros[2])
            // Deltas are quantized to 250 µs; relative timing survives exactly here.
            val received = intArrayOf(0, 1, 3, 4, 5, 6)
            for (i in received) assertEquals(arrivals[i] - arrivals[0], fb.arrivalMicros[i] - fb.arrivalMicros[0], "packet $i")
            seen++
        }
        assertEquals(1, seen)
    }

    @Test
    fun parsesRunLengthAndOneBitChunks() {
        // base 100, 20 packets: run of 10 "small delta", then a one-bit vector: 1,0,1,0,... (14 symbols; 10 used)
        val p = ByteArray(20 + 4 + 15 + 1)
        p[0] = (0x80 or TransportCc.FMT).toByte()
        p[1] = Rtcp.RTPFB.toByte()
        p.putU16(2, p.size / 4 - 1)
        p.putU16(12, 100)
        p.putU16(14, 20)
        p[18] = 2 // reference time 2 × 64 ms
        p[19] = 1
        p.putU16(20, (1 shl 13) or 10) // run length: symbol 1, 10 packets
        p.putU16(22, 0x8000 or 0b10101010101010) // one-bit vector
        for (i in 0 until 15) p[24 + i] = 4 // 1 ms each
        val fb = assertNotNull(TransportCc.parse(p, 0, p.size))
        assertEquals(20, fb.count)
        assertEquals(128_000L + 1_000, fb.arrivalMicros[0])
        assertEquals(128_000L + 10_000, fb.arrivalMicros[9])
        assertEquals(128_000L + 11_000, fb.arrivalMicros[10])
        assertEquals(TransportCc.LOST, fb.arrivalMicros[11])
        assertEquals(128_000L + 15_000, fb.arrivalMicros[18])
        assertEquals(TransportCc.LOST, fb.arrivalMicros[19])
    }

    @Test
    fun extensionSequenceNumber() {
        val buf = ByteArray(4)
        TransportCc.write(buf, 1, 0x1_2345)
        assertEquals(0x2345, TransportCc.read(buf, 1))
    }
}
