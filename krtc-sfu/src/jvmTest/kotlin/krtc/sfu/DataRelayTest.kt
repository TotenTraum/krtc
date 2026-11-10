package krtc.sfu

import krtc.protocol.sdp.SessionDescription
import krtc.signaling.DataEnvelope
import krtc.signaling.ServerMessage
import krtc.sfu.platform.nowMillis
import krtc.sfu.room.MediaPlan
import krtc.sfu.room.Participant
import krtc.sfu.sctp.DataChannel
import krtc.sfu.sctp.DataChannels
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Room messaging through the SFU's [krtc.sfu.room.DataRelay]: each participant gets a client-side SCTP endpoint
 * wired to the SFU's endpoint without DTLS in between. Everything runs on the room's shard thread, as in the SFU.
 */
class DataRelayTest {
    private val consumed = ConcurrentLinkedQueue<DataMessage>()
    private val sfu = Sfu(
        SfuConfig(
            udpPort = 0, shards = 1,
            dataPolicy = { m -> if (m.topic == "to-server") null.also { consumed += m } else m },
        ),
    )

    @AfterTest
    fun close() = sfu.close()

    private class Sink : SignalSink {
        val offers = ConcurrentLinkedQueue<ServerMessage.Offer>()
        override fun send(message: ServerMessage) { if (message is ServerMessage.Offer) offers += message }
        override fun close(reason: String) {}
    }

    /** A participant's client side: its SCTP endpoint and what arrived on its channels. */
    private inner class Client(val p: Participant) : DataChannels.Listener {
        val received = ConcurrentLinkedQueue<Pair<Boolean, DataEnvelope.Message>>() // reliable?, message
        lateinit var reliable: DataChannel
        lateinit var lossy: DataChannel
        val shard = p.room.shard
        val dc = DataChannels(dtlsServer = false, output = { pkt -> shard.execute { p.room.data.onPacket(p, pkt) } }, listener = this, random = { Random.nextLong() })

        fun start() = shard.execute {
            p.room.data.attach(p) { pkt -> shard.execute { dc.handle(pkt, pkt.size, nowMillis()) } }
            reliable = dc.open(DataEnvelope.RELIABLE_LABEL, now = nowMillis())!!
            lossy = dc.open(DataEnvelope.LOSSY_LABEL, ordered = false, maxRetransmits = 0, now = nowMillis())!!
            dc.connect(nowMillis())
        }

        fun send(topic: String, text: String, to: List<String> = emptyList(), reliable: Boolean = true) = shard.execute {
            dc.send(if (reliable) this.reliable else lossy, DataEnvelope.encode(to, topic, text.encodeToByteArray()), binary = true, now = nowMillis())
        }

        override fun onOpen(channel: DataChannel) {}
        override fun onMessage(channel: DataChannel, data: ByteArray, binary: Boolean) {
            received += (channel === reliable) to DataEnvelope.decode(data)!!
        }
        override fun onClose(channel: DataChannel) {}
        fun texts() = received.map { (rel, m) -> "${if (rel) "R" else "L"} ${m.ids.firstOrNull() ?: "server"} ${m.topic}: ${m.payload.decodeToString()}" }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun relaysByRecipientAndReliabilityAndLetsTheHostFilter() = runBlocking {
        val sinks = List(3) { Sink() }
        val ps = sinks.mapIndexed { i, s -> sfu.join(JoinRequest("room", "p$i"), s) as Participant }
        val offer = SessionDescription.parse(sinks[0].offers.last().sdp)
        val data = offer.media.single { it.mid == MediaPlan.DATA_MID }
        assertEquals("UDP/DTLS/SCTP", data.proto)
        assertEquals(listOf("webrtc-datachannel"), data.formats)
        assertEquals("5000", data.attribute("sctp-port"))
        assertEquals("d", offer.media[2].mid, "the data m-line comes right after the uplink")

        val clients = ps.map { Client(it) }
        clients.forEach { it.start() }
        waitFor("channels open on the SFU") { ps.all { it.reliableChannel != null && it.lossyChannel != null } }

        val (a, b, c) = clients
        a.send("chat", "hi all")
        a.send("chat", "just b", to = listOf(ps[1].id))
        b.send("cursor", "10,20", reliable = false)
        c.send("to-server", "secret")
        waitFor("delivery") { a.received.size == 1 && b.received.size == 2 && c.received.size == 2 }
        val idA = ps[0].id
        assertEquals(listOf("R $idA chat: hi all", "R $idA chat: just b"), b.texts())
        assertEquals(listOf("L ${ps[1].id} cursor: 10,20", "R $idA chat: hi all"), c.texts().sorted())
        assertEquals(listOf("L ${ps[1].id} cursor: 10,20"), a.texts())
        assertEquals("secret", consumed.single().payload.decodeToString(), "the policy consumed it; nobody received it")

        assertTrue(sfu.sendData("room", "from the host".encodeToByteArray(), topic = "notice", to = listOf(ps[2].id)))
        waitFor("host message") { c.received.size == 3 }
        assertEquals("R server notice: from the host", c.texts().last())
        val m = sfu.metrics()
        assertEquals(4, m.dataMessagesIn)
        assertEquals(6, m.dataMessagesOut)

        // A big message is fragmented by SCTP and arrives whole.
        val big = ByteArray(60_000) { it.toByte() }
        a.shard.execute { a.dc.send(a.reliable, DataEnvelope.encode(listOf(ps[1].id), "file", big), binary = true, now = nowMillis()) }
        waitFor("big message") { b.received.size == 3 }
        assertContentEquals(big, b.received.last().second.payload)
    }
}
