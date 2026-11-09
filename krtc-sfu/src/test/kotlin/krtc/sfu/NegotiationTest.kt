package krtc.sfu

import krtc.protocol.sdp.Direction
import krtc.protocol.sdp.SessionDescription
import krtc.sfu.room.SignalSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NegotiationTest {
    private val sfu = Sfu(SfuConfig(udpPort = 0, shards = 2))

    @AfterTest
    fun close() = sfu.close()

    private class RecordingSink : SignalSink {
        val messages = CopyOnWriteArrayList<JsonObject>()
        var closed = false
        override fun send(message: String) { messages += Json.parseToJsonElement(message).jsonObject }
        override fun close() { closed = true }
        fun offers() = messages.filter { it["type"]!!.jsonPrimitive.content == "offer" }
        fun lastOffer() = offers().last()
    }

    /** Answers like a browser would: mirror directions, own ICE credentials and DTLS fingerprint. */
    private fun answer(offer: JsonObject): String {
        val sd = SessionDescription.parse(offer["sdp"]!!.jsonPrimitive.content)
        val lines = mutableListOf("v=0", "o=- 1 1 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE " + sd.media.joinToString(" ") { it.mid!! })
        for (m in sd.media) {
            lines += "m=audio 9 UDP/TLS/RTP/SAVPF 111"
            lines += "a=mid:${m.mid}"
            lines += "a=ice-ufrag:test"
            lines += "a=ice-pwd:testtesttesttesttesttest"
            lines += "a=fingerprint:sha-256 " + List(32) { "AB" }.joinToString(":")
            lines += "a=setup:active"
            lines += "a=" + if (m.mid == "0") "sendonly" else m.direction.reversed().sdp
            lines += "a=rtpmap:111 opus/48000/2"
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    private fun answerLast(p: krtc.sfu.room.Participant, sink: RecordingSink) {
        val offer = sink.lastOffer()
        sfu.onAnswer(p, offer["version"]!!.jsonPrimitive.long, answer(offer))
    }

    private fun directions(offer: JsonObject): List<String> =
        SessionDescription.parse(offer["sdp"]!!.jsonPrimitive.content).media.map { "${it.mid}:${it.direction.sdp}" }

    @Test
    fun offersFollowMembershipAndReuseSlots() {
        val sa = RecordingSink(); val sb = RecordingSink(); val sc = RecordingSink()
        val a = sfu.join("r", "a", sa)
        answerLast(a, sa)
        assertEquals(listOf("0:recvonly"), directions(sa.lastOffer()))

        val b = sfu.join("r", "b", sb)
        answerLast(a, sa); answerLast(b, sb)
        assertEquals(listOf("0:recvonly", "1:sendonly"), directions(sa.lastOffer()))
        assertEquals(setOf(b.id), a.receiving)

        val c = sfu.join("r", "c", sc)
        answerLast(a, sa); answerLast(b, sb); answerLast(c, sc)
        assertEquals(listOf("0:recvonly", "1:sendonly", "2:sendonly"), directions(sa.lastOffer()))
        assertEquals(setOf(b.id, c.id), a.receiving)

        // b leaves: its slot goes inactive, c keeps mid 2
        sfu.leave(b)
        answerLast(a, sa)
        assertEquals(listOf("0:recvonly", "1:inactive", "2:sendonly"), directions(sa.lastOffer()))
        assertEquals(setOf(c.id), a.receiving)

        // d joins and takes the free slot 1 instead of adding an m-line
        val sd = RecordingSink()
        val d = sfu.join("r", "d", sd)
        answerLast(a, sa)
        assertEquals(listOf("0:recvonly", "1:sendonly", "2:sendonly"), directions(sa.lastOffer()))
        assertEquals(d.id, sa.lastOffer()["mids"]!!.jsonObject["1"]!!.jsonPrimitive.content)

        // every offer to a carries ICE-lite, passive DTLS role, and the SFU's SSRC for each publisher
        val sdp = SessionDescription.parse(sa.lastOffer()["sdp"]!!.jsonPrimitive.content)
        assertTrue(sdp.hasAttribute("ice-lite"))
        assertEquals("passive", sdp.setup(sdp.media[1]))
        assertEquals(listOf(d.downlinkSsrc), sdp.media[1].ssrcs)
        assertEquals(Direction.SENDONLY, sdp.media[1].direction)
    }

    @Test
    fun offersAreSerializedWhileOneIsOutstanding() {
        val sa = RecordingSink()
        val a = sfu.join("q", "a", sa)
        // a has not answered its first offer; two joins must not produce overlapping offers
        sfu.join("q", "b", RecordingSink())
        sfu.join("q", "c", RecordingSink())
        assertEquals(1, sa.offers().size)
        answerLast(a, sa)
        assertEquals(2, sa.offers().size)
        assertEquals(listOf("0:recvonly", "1:sendonly", "2:sendonly"), directions(sa.lastOffer()))
    }
}
