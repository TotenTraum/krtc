package krtc.sfu.platform

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel

/**
 * JVM: NIO DatagramChannels bound to one port with SO_REUSEPORT. The JDK serializes
 * `DatagramChannel.send` on a per-channel lock, so each shard sends through its own channel.
 * On Linux the kernel also spreads incoming packets across the channels, so each gets a receive thread.
 */
public actual class UdpServer actual constructor(
    bindHost: String,
    port: Int,
    sockets: Int,
    private val onPacket: (data: ByteArray, length: Int, from: PeerAddress) -> Unit,
) {
    private val log = logger("krtc.sfu.udp")
    private val channels: List<DatagramChannel>

    init {
        val bindAddress = InetAddress.getByName(bindHost)
        val first = open(InetSocketAddress(bindAddress, port), reusePort = sockets > 1)
        val boundPort = (first.localAddress as InetSocketAddress).port
        val rest = if (first.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
            List(sockets - 1) { open(InetSocketAddress(bindAddress, boundPort), reusePort = true) }
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

    public actual val localPort: Int get() = (channels[0].localAddress as InetSocketAddress).port
    public actual val socketCount: Int get() = channels.size

    private val threads = channels.mapIndexed { i, ch ->
        Thread.ofPlatform().name("udp-rx-$i").daemon().unstarted { receiveLoop(ch) }
    }

    // Direct buffers per sending thread: shards, the UDP threads (STUN) and DTLS threads all send.
    private val sendBuffers = ThreadLocal.withInitial { ByteBuffer.allocateDirect(2048) }

    public actual fun start(): UdpServer = apply { threads.forEach { it.start() } }

    private fun receiveLoop(channel: DatagramChannel) {
        val buf = ByteBuffer.allocateDirect(2048)
        while (channel.isOpen) {
            try {
                buf.clear()
                val from = channel.receive(buf) as InetSocketAddress? ?: continue
                buf.flip()
                val len = buf.remaining()
                val data = ByteArray(len + 16)
                buf.get(data, 0, len)
                onPacket(data, len, PeerAddress(from.address.address, from.port))
            } catch (_: ClosedChannelException) {
                return
            } catch (t: Throwable) {
                log.warn("UDP receive error", t)
            }
        }
    }

    public actual fun send(lane: Int, data: ByteArray, length: Int, to: PeerAddress) {
        val scratch = sendBuffers.get()
        scratch.clear()
        scratch.put(data, 0, length)
        scratch.flip()
        try {
            channels[lane % channels.size].send(scratch, InetSocketAddress(InetAddress.getByAddress(to.ip), to.port))
        } catch (_: ClosedChannelException) {
        }
    }

    public actual fun close() {
        channels.forEach { it.close() }
    }
}
