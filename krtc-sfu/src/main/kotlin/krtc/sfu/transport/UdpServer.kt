package krtc.sfu.transport

import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel

/**
 * One UDP port for all participants (as in ICE-lite SFUs such as mediasoup or Jitsi Videobridge),
 * served by [sockets] channels bound to it with SO_REUSEPORT.
 *
 * Why several sockets: the JDK serializes `DatagramChannel.send` on a per-channel lock, so a single
 * socket caps the whole process at one core's worth of sendto calls. Each shard sends through its
 * own socket instead. Clients still see one source address. On Linux the kernel also spreads
 * incoming packets across the sockets (by 4-tuple hash), so every socket gets a receive thread.
 */
public class UdpServer(
    bind: InetSocketAddress,
    sockets: Int = 1,
    private val onPacket: (data: ByteArray, length: Int, from: InetSocketAddress) -> Unit,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(UdpServer::class.java)

    private val channels: List<DatagramChannel>

    init {
        val first = open(bind, reusePort = sockets > 1)
        val port = (first.localAddress as InetSocketAddress).port
        val rest = if (first.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
            List(sockets - 1) { open(InetSocketAddress(bind.address, port), reusePort = true) }
        } else {
            if (sockets > 1) log.warn("SO_REUSEPORT unsupported; using a single UDP socket")
            emptyList()
        }
        channels = listOf(first) + rest
    }

    private fun open(at: InetSocketAddress, reusePort: Boolean): DatagramChannel = DatagramChannel.open().apply {
        if (reusePort && supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
            setOption(StandardSocketOptions.SO_REUSEPORT, true)
        }
        setOption(StandardSocketOptions.SO_RCVBUF, 8 shl 20)
        setOption(StandardSocketOptions.SO_SNDBUF, 8 shl 20)
        bind(at)
    }

    public val localPort: Int get() = (channels[0].localAddress as InetSocketAddress).port
    public val socketCount: Int get() = channels.size

    private val threads = channels.mapIndexed { i, ch ->
        Thread.ofPlatform().name("udp-rx-$i").daemon().unstarted { receiveLoop(ch) }
    }

    public fun start(): UdpServer = apply { threads.forEach { it.start() } }

    private fun receiveLoop(channel: DatagramChannel) {
        val buf = ByteBuffer.allocateDirect(2048)
        while (channel.isOpen) {
            try {
                buf.clear()
                val from = channel.receive(buf) as InetSocketAddress? ?: continue
                buf.flip()
                val len = buf.remaining()
                // One copy per incoming packet; +16 leaves room for an SRTP tag when re-protecting in place.
                val data = ByteArray(len + 16)
                buf.get(data, 0, len)
                onPacket(data, len, from)
            } catch (_: ClosedChannelException) {
                return
            } catch (t: Throwable) {
                log.warn("UDP receive error", t)
            }
        }
    }

    /**
     * Sends through socket [lane] (a shard index) from a caller-owned direct buffer, so neither the
     * socket lock nor the buffer is shared between shards.
     */
    public fun send(lane: Int, scratch: ByteBuffer, data: ByteArray, length: Int, to: InetSocketAddress) {
        scratch.clear()
        scratch.put(data, 0, length)
        scratch.flip()
        channels[lane % channels.size].send(scratch, to)
    }

    override fun close() {
        channels.forEach { it.close() }
    }
}
