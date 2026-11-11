package krtc.sfu

import krtc.protocol.sdp.Direction
import krtc.protocol.sdp.SessionDescription
import krtc.signaling.ClientMessage
import krtc.signaling.ServerMessage
import krtc.sfu.room.Participant
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Screen share negotiation: the `s` uplink, `s<i>` per slot, `screens` in offers, start and stop by renegotiation. */
class ScreenShareTest {
    private val sfu = Sfu(SfuConfig(udpPort = 0, shards = 1, dataChannels = false))

    @AfterTest
    fun close() = sfu.close()

    private class Sink : SignalSink {
        val offers = mutableListOf<ServerMessage.Offer>()
        val answered = mutableSetOf<Long>()
        var sharing = false
        override fun send(message: ServerMessage) { if (message is ServerMessage.Offer) offers += message }
        override fun close(reason: String) {}
    }

    private val sinks = mutableMapOf<Participant, Sink>()

    private fun answer(sink: Sink, offer: ServerMessage.Offer): String {
        val sd = SessionDescription.parse(offer.sdp)
        val lines = mutableListOf("v=0", "o=- 1 1 IN IP4 127.0.0.1", "s=-", "t=0 0")
        for (m in sd.media) {
            lines += "m=${m.type} 9 UDP/TLS/RTP/SAVPF ${m.formats.joinToString(" ")}"
            lines += "a=mid:${m.mid}"
            lines += "a=fingerprint:sha-256 " + List(32) { "AB" }.joinToString(":")
            lines += "a=setup:active"
            when (m.mid) {
                "0" -> lines += "a=sendonly"
                "1" -> lines += "a=inactive"
                "s" -> if (sink.sharing) {
                    lines += "a=sendonly"
                    lines += "a=ssrc:1111 cname:x"
                    lines += "a=ssrc:2222 cname:x"
                } else lines += "a=inactive"
                else -> lines += "a=" + m.direction.reversed().sdp
            }
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    private fun settle() {
        repeat(10) {
            var answered = false
            for ((p, sink) in sinks) {
                val offer = sink.offers.lastOrNull() ?: continue
                if (!sink.answered.add(offer.version)) continue
                sfu.onMessage(p, ClientMessage.Answer(offer.version, answer(sink, offer)))
                answered = true
            }
            if (!answered) return
        }
    }

    private fun lastOffer(p: Participant) = sinks[p]!!.offers.last()
    private fun media(p: Participant) = SessionDescription.parse(lastOffer(p).sdp).media

    @Test
    fun screenShareStartsAndStopsByRenegotiation() = runTest {
        val sa = Sink()
        val sb = Sink()
        val pa = sfu.join(JoinRequest("r", "a"), sa) as Participant
        sinks[pa] = sa
        val pb = sfu.join(JoinRequest("r", "b"), sb) as Participant
        sinks[pb] = sb
        settle()

        // Layout: uplink 0, 1, s; slot 0: 2, 3, s0. The screen uplink asks for no simulcast.
        assertEquals(listOf("0", "1", "s", "2", "3", "s0"), media(pa).map { it.mid })
        val uplink = media(pa).single { it.mid == "s" }
        assertEquals(Direction.RECVONLY, uplink.direction)
        assertTrue(uplink.attributes("rid").isEmpty() && !uplink.hasAttribute("simulcast"))
        assertEquals(Direction.INACTIVE, media(pa).single { it.mid == "s0" }.direction)
        assertEquals(emptyList(), lastOffer(pa).screens)

        // b starts sharing: it asks for a new offer and answers `s` with sendonly.
        sb.sharing = true
        sfu.onMessage(pb, ClientMessage.Renegotiate)
        settle()
        assertTrue(pb.screen.publishing)
        assertEquals(setOf(1111L, 2222L), pb.screenSsrcs)
        val s0 = media(pa).single { it.mid == "s0" }
        assertEquals(Direction.SENDONLY, s0.direction)
        assertEquals(listOf(pb.screen.ssrc, pb.screen.rtxSsrc), s0.ssrcs)
        assertEquals(listOf("screen-${pb.id} screen-${pb.id}"), s0.attributes("msid"))
        assertEquals(listOf("s0"), lastOffer(pa).screens)
        assertEquals(pb.id, lastOffer(pa).mids["s0"])
        assertTrue(pb.screen.key in pa.receivingVideo)
        assertFalse(pb.camera.key in pa.receivingVideo, "b has no camera")
        assertTrue(sfu.participant(pb.id)!!.sharesScreen)

        // b stops.
        sb.sharing = false
        sfu.onMessage(pb, ClientMessage.Renegotiate)
        settle()
        assertFalse(pb.screen.publishing)
        assertEquals(Direction.INACTIVE, media(pa).single { it.mid == "s0" }.direction)
        assertEquals(emptyList(), lastOffer(pa).screens)
    }
}
