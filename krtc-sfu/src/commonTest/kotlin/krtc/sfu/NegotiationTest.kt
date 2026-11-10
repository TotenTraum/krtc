package krtc.sfu

import krtc.protocol.sdp.Direction
import krtc.protocol.sdp.SessionDescription
import krtc.signaling.ServerMessage
import krtc.sfu.room.Participant
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NegotiationTest {
    private val sfu = Sfu(SfuConfig(udpPort = 0, shards = 2, dataChannels = false)) // m-line layout without the data m-line; DataRelayTest covers it

    @AfterTest
    fun close() = sfu.close()

    private class RecordingSink(val camera: Boolean) : SignalSink {
        val messages = mutableListOf<ServerMessage>()
        val answered = mutableSetOf<Long>()
        override fun send(message: ServerMessage) { messages += message }
        override fun close(reason: String) {}
        fun offers() = messages.filterIsInstance<ServerMessage.Offer>()
        fun lastOffer() = offers().last()
    }

    private val sinks = mutableMapOf<Participant, RecordingSink>()

    private suspend fun join(room: String, name: String, camera: Boolean = false): Participant {
        val sink = RecordingSink(camera)
        return (sfu.join(JoinRequest(room, name), sink) as Participant).also { sinks[it] = sink }
    }

    /** Answers like a browser would: mic on mid 0, camera on mid 1 if it has one, receive everything else. */
    private fun answer(sink: RecordingSink, offer: ServerMessage.Offer): String {
        val sd = SessionDescription.parse(offer.sdp)
        val lines = mutableListOf("v=0", "o=- 1 1 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE " + sd.media.joinToString(" ") { it.mid!! })
        for (m in sd.media) {
            lines += "m=${m.type} 9 UDP/TLS/RTP/SAVPF ${m.formats.joinToString(" ")}"
            lines += "a=mid:${m.mid}"
            lines += "a=ice-ufrag:test"
            lines += "a=ice-pwd:testtesttesttesttesttest"
            lines += "a=fingerprint:sha-256 " + List(32) { "AB" }.joinToString(":")
            lines += "a=setup:active"
            lines += "a=" + when (m.mid) {
                "0" -> "sendonly"
                "1" -> if (sink.camera) "sendonly" else "inactive"
                else -> m.direction.reversed().sdp
            }
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    /** Answers every outstanding offer until signaling settles. */
    private fun settle() {
        repeat(10) {
            var answered = false
            for ((p, sink) in sinks) {
                val offer = sink.offers().lastOrNull() ?: continue
                val v = offer.version
                if (!sink.answered.add(v)) continue
                sfu.onMessage(p, krtc.signaling.ClientMessage.Answer(v, answer(sink, offer)))
                answered = true
            }
            if (!answered) return
        }
    }

    private fun directions(p: Participant): List<String> =
        SessionDescription.parse(sinks[p]!!.lastOffer().sdp).media.map { "${it.mid}:${it.direction.sdp}" }

    private fun mids(p: Participant): Map<String, String> = sinks[p]!!.lastOffer().mids

    @Test
    fun offersFollowMembershipVideoAndReuseSlots() = runTest {
        val a = join("r", "a")
        settle()
        assertEquals(listOf("0:recvonly", "1:recvonly"), directions(a))

        val b = join("r", "b", camera = true)
        settle()
        // b sends video, so a receives b's audio (mid 2) and video (mid 3); b receives only a's audio
        assertEquals(listOf("0:recvonly", "1:recvonly", "2:sendonly", "3:sendonly"), directions(a))
        assertEquals(listOf("0:recvonly", "1:recvonly", "2:sendonly", "3:inactive"), directions(b))
        assertEquals(mapOf("2" to b.id, "3" to b.id), mids(a))
        assertEquals(setOf(b.id), a.receiving)
        assertEquals(setOf(b.id), a.receivingVideo)
        assertTrue(b.publishesVideo)

        val c = join("r", "c")
        settle()
        assertEquals(listOf("0:recvonly", "1:recvonly", "2:sendonly", "3:sendonly", "4:sendonly", "5:inactive"), directions(a))

        // b leaves: its slot goes inactive, c keeps mids 4/5
        sfu.leave(b)
        sinks.remove(b)
        settle()
        assertEquals(listOf("0:recvonly", "1:recvonly", "2:inactive", "3:inactive", "4:sendonly", "5:inactive"), directions(a))
        assertEquals(setOf(c.id), a.receiving)
        assertEquals(emptySet(), a.receivingVideo)

        // d joins with a camera and takes the free slot instead of adding m-lines
        val d = join("r", "d", camera = true)
        settle()
        assertEquals(listOf("0:recvonly", "1:recvonly", "2:sendonly", "3:sendonly", "4:sendonly", "5:inactive"), directions(a))
        assertEquals(d.id, mids(a)["3"])

        // offers carry ICE-lite, the passive DTLS role and the SFU's SSRCs (video + RTX in an FID group)
        val sdp = SessionDescription.parse(sinks[a]!!.lastOffer().sdp)
        assertTrue(sdp.hasAttribute("ice-lite"))
        assertEquals("passive", sdp.setup(sdp.media[3]))
        assertEquals(listOf(d.audioOut.ssrc), sdp.media[2].ssrcs)
        assertEquals(listOf(d.videoSsrc, d.videoRtxSsrc), sdp.media[3].ssrcs)
        assertEquals(listOf("FID ${d.videoSsrc} ${d.videoRtxSsrc}"), sdp.media[3].attributes("ssrc-group"))
        assertEquals(Direction.SENDONLY, sdp.media[3].direction)
    }

    @Test
    fun offersAreSerializedWhileOneIsOutstanding() = runTest {
        val a = join("q", "a")
        // a has not answered its first offer; two joins must not produce overlapping offers
        join("q", "b")
        join("q", "c")
        assertEquals(1, sinks[a]!!.offers().size)
        settle()
        assertEquals(2, sinks[a]!!.offers().size)
        assertEquals(listOf("0:recvonly", "1:recvonly", "2:sendonly", "3:inactive", "4:sendonly", "5:inactive"), directions(a))
    }
}
