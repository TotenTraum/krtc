package krtc.signaling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalingTest {
    @Test
    fun wireFormatIsStable() {
        assertEquals(
            """{"type":"offer","version":3,"sdp":"v=0","mids":{"2":"a","s0":"a"},"screens":["s0"]}""",
            SignalingProtocol.encode(ServerMessage.Offer(3, "v=0", mapOf("2" to "a", "s0" to "a"), listOf("s0"))),
        )
        assertEquals("""{"type":"layer","peer":"a","layer":"q"}""", SignalingProtocol.encode(ClientMessage.Layer("a", "q")))
        assertEquals("""{"type":"renegotiate"}""", SignalingProtocol.encode(ClientMessage.Renegotiate))
        // Offers from servers that predate screen sharing still decode.
        assertEquals(ServerMessage.Offer(3, "v=0", mapOf("2" to "a")), SignalingProtocol.decodeServer("""{"type":"offer","version":3,"sdp":"v=0","mids":{"2":"a"}}"""))
    }

    @Test
    fun roundTripsAndToleratesUnknowns() {
        val welcome = ServerMessage.Welcome("1", "room", listOf(PeerInfo("2", "Bob")))
        assertEquals(welcome, SignalingProtocol.decodeServer(SignalingProtocol.encode(welcome)))
        assertEquals(ClientMessage.Answer(1, "sdp"), SignalingProtocol.decodeClient("""{"type":"answer","version":1,"sdp":"sdp","extra":true}"""))
        assertNull(SignalingProtocol.decodeServer("""{"type":"future-message"}"""))
        assertNull(SignalingProtocol.decodeClient("not json"))
    }
}
