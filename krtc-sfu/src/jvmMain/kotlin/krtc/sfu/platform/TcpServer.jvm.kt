package krtc.sfu.platform

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

/** JVM ICE-TCP listener: blocking NIO channels, a virtual thread per connection. */
internal actual class TcpServer actual constructor(
    addresses: List<ByteArray>,
    private val port: Int,
    private val onPacket: (data: ByteArray, length: Int, from: PeerAddress) -> Unit,
) {
    private val log = logger("krtc.sfu.tcp")
    private val listeners: List<ServerSocketChannel> = addresses.mapNotNull { ip ->
        try {
            ServerSocketChannel.open().apply {
                setOption(StandardSocketOptions.SO_REUSEADDR, true)
                bind(InetSocketAddress(InetAddress.getByAddress(ip), port), 128)
            }
        } catch (e: IOException) {
            log.warn("no ICE-TCP on ${PeerAddress(ip, port)}: $e")
            null
        }
    }
    private val connections = ConcurrentHashMap<PeerAddress, SocketChannel>()

    internal actual fun start(): TcpServer = apply {
        listeners.forEachIndexed { g, l -> Thread.ofPlatform().name("tcp-accept-$g").daemon().start { acceptLoop(l, g) } }
    }

    private fun acceptLoop(listener: ServerSocketChannel, group: Int) {
        while (listener.isOpen) {
            val ch = try {
                listener.accept()
            } catch (_: IOException) {
                return
            }
            val remote = ch.remoteAddress as InetSocketAddress
            val address = PeerAddress(remote.address.address, remote.port, tcp = true).also { it.via = group }
            if (connections.size >= MAX_CONNECTIONS) {
                ch.close()
                continue
            }
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true)
            ch.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
            connections[address] = ch
            Thread.ofVirtual().name("tcp-$address").start {
                try {
                    readLoop(ch, address)
                } catch (_: IOException) {
                } finally {
                    connections.remove(address)
                    ch.close()
                }
            }
        }
    }

    private fun readLoop(ch: SocketChannel, address: PeerAddress) {
        val header = ByteBuffer.allocate(2)
        while (ch.isOpen) {
            header.clear()
            if (!readFully(ch, header)) return
            val len = header.getShort(0).toInt() and 0xFFFF
            if (len == 0 || len > 2048) return
            val data = ByteArray(len + 16)
            if (!readFully(ch, ByteBuffer.wrap(data, 0, len))) return
            try {
                onPacket(data, len, address)
            } catch (t: Throwable) {
                log.warn("TCP packet handling failed", t)
            }
        }
    }

    private fun readFully(ch: SocketChannel, buf: ByteBuffer): Boolean {
        while (buf.hasRemaining()) if (ch.read(buf) < 0) return false
        return true
    }

    internal actual fun send(data: ByteArray, length: Int, to: PeerAddress) {
        val ch = connections[to] ?: return
        val frame = ByteBuffer.allocate(length + 2)
        frame.putShort(length.toShort()).put(data, 0, length).flip()
        try {
            synchronized(ch) { while (frame.hasRemaining()) ch.write(frame) }
        } catch (_: IOException) {
            ch.close()
        }
    }

    internal actual fun close() {
        listeners.forEach { it.close() }
        connections.values.forEach { it.close() }
    }

    private companion object {
        const val MAX_CONNECTIONS = 4096
    }
}
