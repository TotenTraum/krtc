package krtc.sfu

import krtc.sfu.sctp.Crc32c
import krtc.sfu.sctp.DataChannel
import krtc.sfu.sctp.DataChannels
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two SCTP endpoints (a DTLS client that initiates and the SFU side that answers) over a simulated link
 * with delay, loss and reordering, driven by a virtual clock.
 */
class SctpTest {
    private class Received(val channel: String, val data: ByteArray, val binary: Boolean)

    private class Side(name: String, server: Boolean, val net: Net, random: Random) : DataChannels.Listener {
        val opened = ArrayList<DataChannel>()
        val closed = ArrayList<String>()
        val messages = ArrayList<Received>()
        var connected = false
        val dc = DataChannels(server, { net.send(name, it) }, this, { random.nextLong() })
        override fun onConnected() { connected = true }
        override fun onOpen(channel: DataChannel) { opened += channel }
        override fun onMessage(channel: DataChannel, data: ByteArray, binary: Boolean) { messages += Received(channel.label, data, binary) }
        override fun onClose(channel: DataChannel) { closed += channel.label }
    }

    /** Packets in flight between "client" and "server": delay 20 ms ± jitter (reorders), optional loss. */
    private class Net(val lossPercent: Int, val random: Random) {
        var now = 0L
        val queue = ArrayList<Triple<Long, String, ByteArray>>()
        var dropped = 0
        var delivered = 0

        fun send(from: String, packet: ByteArray) {
            if (random.nextInt(100) < lossPercent) { dropped++; return }
            queue += Triple(now + 20 + random.nextLong(10), if (from == "client") "server" else "client", packet)
        }
    }

    private fun run(net: Net, client: Side, server: Side, millis: Long, until: () -> Boolean = { false }) {
        val end = net.now + millis
        while (net.now < end && !until()) {
            net.now++
            val due = net.queue.filter { it.first <= net.now }
            net.queue.removeAll(due)
            for ((_, to, packet) in due.sortedBy { it.first }) {
                net.delivered++
                (if (to == "server") server else client).dc.handle(packet, packet.size, net.now)
            }
            if (net.now % 10 == 0L) {
                client.dc.tick(net.now)
                server.dc.tick(net.now)
            }
        }
    }

    private fun pair(lossPercent: Int = 0, seed: Int = 1): Triple<Net, Side, Side> {
        val random = Random(seed)
        val net = Net(lossPercent, random)
        return Triple(net, Side("client", server = false, net, random), Side("server", server = true, net, random))
    }

    @Test
    fun crc32cMatchesTheRfc3720TestVector() {
        val zeros = ByteArray(32)
        assertEquals(0x8A9136AA.toInt(), Crc32c.compute(zeros, 0, 32).inv())
    }

    @Test
    fun handshakeAndDcepOpenBothWays() {
        val (net, client, server) = pair()
        val ch = client.dc.open("chat", now = 0)!!
        client.dc.connect(0)
        run(net, client, server, 1_000) { server.opened.isNotEmpty() }
        assertTrue(client.connected && server.connected)
        assertEquals(0, ch.id, "the DTLS client uses even stream ids")
        assertEquals("chat", server.opened.single().label)

        assertTrue(client.dc.send(ch, "hello".encodeToByteArray(), binary = false, now = net.now))
        val back = server.opened.single()
        assertTrue(server.dc.send(back, byteArrayOf(1, 2, 3), binary = true, now = net.now))
        assertTrue(server.dc.send(back, ByteArray(0), binary = true, now = net.now))
        run(net, client, server, 500) { client.messages.size == 2 && server.messages.size == 1 }
        assertEquals("hello", server.messages.single().data.decodeToString())
        assertContentEquals(byteArrayOf(1, 2, 3), client.messages[0].data)
        assertEquals(0, client.messages[1].data.size)

        // A channel the SFU opens gets an odd id.
        val fromServer = server.dc.open("server-push", now = net.now)!!
        assertEquals(1, fromServer.id)
        run(net, client, server, 500) { client.opened.any { it.label == "server-push" } }
        assertTrue(client.opened.any { it.label == "server-push" })
    }

    @Test
    fun reliableOrderedDeliveryOverALossyLinkWithFragmentation() {
        val (net, client, server) = pair(lossPercent = 10, seed = 7)
        val ch = client.dc.open("bulk", now = 0)!!
        client.dc.connect(0)
        run(net, client, server, 20_000) { server.opened.isNotEmpty() }
        val sent = List(200) { i -> ByteArray(if (i % 10 == 0) 5_000 else 300 + i) { (i + it).toByte() } }
        for (m in sent) assertTrue(client.dc.send(ch, m, binary = true, now = net.now))
        run(net, client, server, 60_000) { server.messages.size == sent.size }
        assertEquals(sent.size, server.messages.size, "all messages arrive")
        sent.forEachIndexed { i, m -> assertContentEquals(m, server.messages[i].data, "message $i in order") }
        assertTrue(net.dropped > 0)
        run(net, client, server, 5_000) { client.dc.bufferedAmount == 0L }
        assertEquals(0L, client.dc.bufferedAmount, "everything acknowledged")
    }

    @Test
    fun unorderedWithoutRetransmissionsLosesSomeAndNeverStalls() {
        val (net, client, server) = pair(lossPercent = 20, seed = 3)
        val lossy = client.dc.open("lossy", ordered = false, maxRetransmits = 0, now = 0)!!
        val reliable = client.dc.open("reliable", now = 0)!!
        client.dc.connect(0)
        run(net, client, server, 20_000) { server.opened.size == 2 }
        repeat(300) { i ->
            client.dc.send(lossy, byteArrayOf(i.toByte()), binary = true, now = net.now)
            if (i % 30 == 0) client.dc.send(reliable, byteArrayOf(i.toByte()), binary = true, now = net.now)
            run(net, client, server, 5)
        }
        run(net, client, server, 30_000) { server.messages.count { it.channel == "reliable" } == 10 && client.dc.bufferedAmount == 0L }
        val lossyCount = server.messages.count { it.channel == "lossy" }
        assertTrue(lossyCount in 150 until 300, "some lossy messages are lost, most arrive: $lossyCount")
        assertEquals(10, server.messages.count { it.channel == "reliable" }, "the reliable channel is unaffected")
        assertEquals(0L, client.dc.bufferedAmount, "abandoned messages are skipped with FORWARD-TSN")
    }

    @Test
    fun orderedPartialReliabilityKeepsTheStreamMoving() {
        val (net, client, server) = pair(lossPercent = 30, seed = 11)
        val ch = client.dc.open("pr", ordered = true, maxRetransmits = 1, now = 0)!!
        client.dc.connect(0)
        run(net, client, server, 30_000) { server.opened.isNotEmpty() }
        repeat(100) { i -> client.dc.send(ch, byteArrayOf(i.toByte()), binary = true, now = net.now); run(net, client, server, 10) }
        run(net, client, server, 30_000) { client.dc.bufferedAmount == 0L }
        val got = server.messages.map { it.data[0].toInt() and 0xFF }
        assertEquals(got.sorted(), got, "delivered in order")
        assertTrue(got.size in 60..100, "most arrive: ${got.size}")
        assertTrue(got.contains(99) || got.size < 100, "the stream does not stall on abandoned messages")
    }

    @Test
    fun closingAChannelResetsTheStreamOnBothSides() {
        val (net, client, server) = pair()
        val ch = client.dc.open("temp", now = 0)!!
        client.dc.connect(0)
        run(net, client, server, 1_000) { server.opened.isNotEmpty() }
        client.dc.send(ch, "last".encodeToByteArray(), binary = false, now = net.now)
        client.dc.close(ch, net.now)
        run(net, client, server, 2_000) { client.closed.isNotEmpty() && server.closed.isNotEmpty() }
        assertEquals("last", server.messages.single().data.decodeToString(), "data sent before the close is delivered")
        assertEquals(listOf("temp"), server.closed)
        assertEquals(listOf("temp"), client.closed)
        // The id can be used again, and SSNs start over.
        val again = client.dc.open("again", now = net.now)!!
        assertEquals(2, again.id) // ids are not reused at once by the allocator...
        val reused = client.dc.open("reused", negotiatedId = 0, now = net.now)!!
        assertEquals(0, reused.id) // ...but stream 0 is free again
    }
}
