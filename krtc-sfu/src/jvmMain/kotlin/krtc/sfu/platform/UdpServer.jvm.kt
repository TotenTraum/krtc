package krtc.sfu.platform

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel

/**
 * JVM: NIO DatagramChannels. A wildcard bind (`0.0.0.0` for IPv4, `::` for IPv4 + IPv6) opens one group of
 * channels per local interface address, so every reply leaves from the address the peer sent to (NIO has no
 * IP_PKTINFO). Each group has [sockets] SO_REUSEPORT channels: the JDK serializes `DatagramChannel.send` per
 * channel, so each shard sends through its own, and on Linux the kernel spreads incoming packets across them.
 */
internal actual class UdpServer actual constructor(
    bindHost: String,
    port: Int,
    sockets: Int,
    private val onPacket: (data: ByteArray, length: Int, from: PeerAddress) -> Unit,
) {
    private val log = logger("krtc.sfu.udp")
    private val groups: List<List<DatagramChannel>>
    internal actual val localAddresses: List<ByteArray>

    init {
        val addresses = resolveBind(bindHost)
        // The shard sockets share the port with SO_REUSEPORT, which the OS also allows across processes: a second
        // SFU on the same port would silently split the packets with the first. A plain bind first fails if anyone
        // else holds the port.
        if (port != 0) for (address in addresses) {
            try {
                DatagramChannel.open(if (address is Inet6Address) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET)
                    .use { it.bind(InetSocketAddress(address, port)) }
            } catch (e: java.net.BindException) {
                throw IllegalArgumentException("UDP port $port on ${address.hostAddress} is already in use (another krtc-sfu?)", e)
            } catch (_: java.io.IOException) {
                // Other failures are handled per address below.
            }
        }
        var boundPort = port
        val opened = ArrayList<List<DatagramChannel>>()
        val used = ArrayList<ByteArray>()
        for (address in addresses) {
            try {
                val first = open(InetSocketAddress(address, boundPort), reusePort = sockets > 1)
                if (boundPort == 0) boundPort = (first.localAddress as InetSocketAddress).port
                val rest = if (first.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
                    List(sockets - 1) { open(InetSocketAddress(address, boundPort), reusePort = true) }
                } else {
                    if (sockets > 1 && opened.isEmpty()) log.warn("SO_REUSEPORT unsupported; one UDP socket per address")
                    emptyList()
                }
                opened += listOf(first) + rest
                used += address.address
            } catch (t: java.io.IOException) {
                if (addresses.size == 1) throw t
                log.warn("skipping $address:$boundPort: $t")
            }
        }
        check(opened.isNotEmpty()) { "no UDP socket could be bound for $bindHost:$port" }
        groups = opened
        localAddresses = used
    }

    /** Wildcards expand to the interface addresses (link-local IPv6 skipped); anything else is one address. */
    private fun resolveBind(host: String): List<InetAddress> {
        val ipv6 = when (host) {
            "", "0.0.0.0" -> false
            "::" -> true
            else -> return listOf(InetAddress.getByName(host))
        }
        val found = NetworkInterface.networkInterfaces().filter { it.isUp }.flatMap { it.inetAddresses() }
            .filter { it is Inet4Address || (ipv6 && it is Inet6Address && !it.isLinkLocalAddress) }
            .map { if (it is Inet6Address) InetAddress.getByAddress(it.address) else it } // drop scope ids
            .toList()
        return found.ifEmpty { listOf(InetAddress.getByName(if (ipv6) "::" else "0.0.0.0")) }
    }

    private fun open(at: InetSocketAddress, reusePort: Boolean): DatagramChannel {
        val family = if (at.address is Inet6Address) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET
        return DatagramChannel.open(family).apply {
            if (reusePort && supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
                setOption(StandardSocketOptions.SO_REUSEPORT, true)
            }
            setOption(StandardSocketOptions.SO_RCVBUF, 8 shl 20)
            setOption(StandardSocketOptions.SO_SNDBUF, 8 shl 20)
            bind(at)
        }
    }

    internal actual val localPort: Int get() = (groups[0][0].localAddress as InetSocketAddress).port
    internal actual val socketCount: Int get() = groups.sumOf { it.size }

    private val threads = groups.flatMapIndexed { g, channels ->
        channels.mapIndexed { i, ch -> Thread.ofPlatform().name("udp-rx-$g-$i").daemon().unstarted { receiveLoop(ch, g) } }
    }

    // Direct buffers per sending thread: shards, the UDP threads (STUN) and DTLS threads all send.
    private val sendBuffers = ThreadLocal.withInitial { ByteBuffer.allocateDirect(2048) }

    internal actual fun start(): UdpServer = apply { threads.forEach { it.start() } }

    private fun receiveLoop(channel: DatagramChannel, group: Int) {
        val buf = ByteBuffer.allocateDirect(2048)
        while (channel.isOpen) {
            try {
                buf.clear()
                val from = channel.receive(buf) as InetSocketAddress? ?: continue
                buf.flip()
                val len = buf.remaining()
                val data = ByteArray(len + 16)
                buf.get(data, 0, len)
                val ip = from.address.let { a -> if (a is Inet6Address && a.isIPv4CompatibleAddress) a.address.copyOfRange(12, 16) else a.address }
                onPacket(data, len, PeerAddress(ip, from.port).also { it.via = group })
            } catch (_: ClosedChannelException) {
                return
            } catch (t: Throwable) {
                log.warn("UDP receive error", t)
            }
        }
    }

    internal actual fun send(lane: Int, data: ByteArray, length: Int, to: PeerAddress) {
        val channels = groups.getOrNull(to.via) ?: return
        val scratch = sendBuffers.get()
        scratch.clear()
        scratch.put(data, 0, length)
        scratch.flip()
        try {
            channels[lane % channels.size].send(scratch, InetSocketAddress(InetAddress.getByAddress(to.ip), to.port))
        } catch (_: ClosedChannelException) {
        } catch (t: java.io.IOException) {
            log.debug("UDP send to $to failed: $t")
        }
    }

    internal actual fun close() {
        groups.flatten().forEach { it.close() }
    }
}
