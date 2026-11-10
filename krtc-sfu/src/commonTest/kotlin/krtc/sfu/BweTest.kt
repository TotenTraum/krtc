package krtc.sfu

import krtc.protocol.rtp.Rtcp
import krtc.protocol.rtp.TransportCc
import krtc.sfu.room.SendSideBwe
import krtc.sfu.room.TwccReceiver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Send-side BWE against a simulated bottleneck: the sender paces packets at the current estimate, a FIFO
 * link of fixed capacity delays (and optionally drops) them, and a [TwccReceiver] produces the feedback —
 * the same pieces the SFU uses on both sides of a transport.
 */
class BweTest {
    private class Result(val estimates: List<Long>)

    private fun simulate(capacityBps: Long, seconds: Int, lossPercent: Int = 0, startBps: Long = 1_000_000): Result {
        val bwe = SendSideBwe(startBps)
        val receiver = TwccReceiver()
        val random = Random(1)
        val packetBytes = 1_200
        val inFlight = ArrayDeque<Pair<Int, Long>>() // transport seq, arrival µs
        var linkFreeAt = 0L
        var nextSend = 0L
        var nextFeedback = 50_000L
        val estimates = ArrayList<Long>()
        val end = seconds * 1_000_000L
        var now = 0L
        while (now < end) {
            if (now >= nextSend) {
                val seq = bwe.onSend(now, packetBytes)
                val serialization = packetBytes * 8L * 1_000_000 / capacityBps
                linkFreeAt = maxOf(linkFreeAt, now) + serialization
                if (random.nextInt(100) >= lossPercent) inFlight.addLast(seq to linkFreeAt + 20_000) // + 20 ms propagation
                nextSend = now + packetBytes * 8L * 1_000_000 / bwe.estimateBps
            }
            while (inFlight.isNotEmpty() && inFlight.first().second <= now) {
                val (seq, at) = inFlight.removeFirst()
                receiver.record(seq, at)
            }
            if (now >= nextFeedback) {
                nextFeedback += 50_000
                receiver.feedback(1)?.let { packet ->
                    Rtcp.forEach(packet, 0, packet.size) { _, _, at, size ->
                        TransportCc.parse(packet, at, size)?.let { bwe.onFeedback(it, now / 1000 + 20) }
                    }
                }
                if (now % 1_000_000 < 50_000) estimates += bwe.estimateBps
            }
            now += 250
        }
        return Result(estimates)
    }

    @Test
    fun convergesBelowABottleneck() {
        val capacity = 800_000L
        val r = simulate(capacity, seconds = 30, startBps = 2_000_000)
        val tail = r.estimates.takeLast(10)
        assertTrue(tail.all { it in capacity / 3..capacity * 13 / 10 }, "estimates near ${capacity / 1000} kbps: $tail")
    }

    @Test
    fun growsWhenThereIsRoom() {
        val r = simulate(capacityBps = 10_000_000, seconds = 30, startBps = 300_000)
        assertTrue(r.estimates.last() > 2_000_000, "estimate grows past 2 Mbps: ${r.estimates}")
    }

    @Test
    fun backsOffOnHeavyLoss() {
        val r = simulate(capacityBps = 10_000_000, seconds = 15, lossPercent = 25, startBps = 2_000_000)
        assertTrue(r.estimates.last() < 1_000_000, "estimate drops under 25 % loss: ${r.estimates}")
    }
}
