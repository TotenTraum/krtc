package krtc.sfu.platform

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import krtc.sfu.openssl.krtc_recv_all
import krtc.sfu.openssl.krtc_send_all
import krtc.sfu.openssl.krtc_set_ipv6_only
import krtc.sfu.openssl.krtc_sockaddr_make
import krtc.sfu.openssl.krtc_sockaddr_read
import krtc.sfu.openssl.krtc_tcp_setup
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.concurrent.Volatile

/** Kotlin/Native ICE-TCP listener: blocking POSIX sockets, one reader thread per connection. */
@OptIn(ExperimentalCoroutinesApi::class)
internal actual class TcpServer actual constructor(
    addresses: List<ByteArray>,
    private val port: Int,
    private val onPacket: (data: ByteArray, length: Int, from: PeerAddress) -> Unit,
) {
    private val log = logger("krtc.sfu.tcp")
    private val listeners: List<Int> = addresses.mapNotNull { ip -> runCatching { listen(ip) }.onFailure { log.warn("no ICE-TCP on ${PeerAddress(ip, port)}: ${it.message}") }.getOrNull() }
    private val lock = SynchronizedObject()
    private val connections = HashMap<PeerAddress, Connection>() // guarded by lock

    @Volatile private var closed = false

    private class Connection(val fd: Int) {
        val writeLock = SynchronizedObject()
        val frame = ByteArray(2 + 2048)
    }

    private fun listen(ip: ByteArray): Int = memScoped {
        val fd = socket(if (ip.size == 4) AF_INET else AF_INET6, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: errno $errno" }
        val one = alloc<IntVar>().apply { value = 1 }
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
        if (ip.size == 16) krtc_set_ipv6_only(fd)
        val addr = allocArray<ByteVar>(SOCKADDR_SIZE)
        val len = ip.usePinned { krtc_sockaddr_make(addr, it.addressOf(0).reinterpret(), ip.size, port) }
        if (bind(fd, addr.reinterpret(), len.convert()) != 0 || listen(fd, 128) != 0) {
            val e = errno
            close(fd)
            error("bind/listen failed: errno $e")
        }
        fd
    }

    internal actual fun start(): TcpServer = apply {
        listeners.forEachIndexed { g, fd -> CoroutineScope(newSingleThreadContext("tcp-accept-$g")).launch { acceptLoop(fd, g) } }
    }

    private fun acceptLoop(listener: Int, group: Int) = memScoped {
        val from = allocArray<ByteVar>(SOCKADDR_SIZE)
        val fromLen = alloc<socklen_tVar>()
        val ip = allocArray<ByteVar>(16)
        val port = alloc<IntVar>()
        while (!closed) {
            fromLen.value = SOCKADDR_SIZE.convert()
            val fd = accept(listener, from.reinterpret(), fromLen.ptr)
            if (fd < 0) continue
            val ipLen = krtc_sockaddr_read(from, ip.reinterpret(), port.ptr)
            val address = PeerAddress(ip.readBytes(ipLen), port.value, tcp = true).also { it.via = group }
            val accepted = synchronized(lock) {
                if (connections.size >= MAX_CONNECTIONS) false else { connections[address] = Connection(fd); true }
            }
            if (!accepted) {
                close(fd)
                continue
            }
            krtc_tcp_setup(fd)
            val context = newSingleThreadContext("tcp-$address")
            CoroutineScope(context).launch {
                try {
                    readLoop(fd, address)
                } finally {
                    synchronized(lock) { connections.remove(address) }
                    close(fd)
                    context.close()
                }
            }
        }
    }

    private fun readLoop(fd: Int, address: PeerAddress) {
        val header = ByteArray(2)
        while (!closed) {
            if (header.usePinned { krtc_recv_all(fd, it.addressOf(0).reinterpret(), 2) } != 0) return
            val len = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
            if (len == 0 || len > 2048) return // not RFC 4571 framing we can handle
            val data = ByteArray(len + 16)
            if (data.usePinned { krtc_recv_all(fd, it.addressOf(0).reinterpret(), len) } != 0) return
            try {
                onPacket(data, len, address)
            } catch (t: Throwable) {
                log.warn("TCP packet handling failed: $t")
            }
        }
    }

    internal actual fun send(data: ByteArray, length: Int, to: PeerAddress) {
        val c = synchronized(lock) { connections[to] } ?: return
        if (length > 2048) return
        synchronized(c.writeLock) {
            c.frame[0] = (length ushr 8).toByte()
            c.frame[1] = length.toByte()
            data.copyInto(c.frame, 2, 0, length)
            if (c.frame.usePinned { krtc_send_all(c.fd, it.addressOf(0).reinterpret(), length + 2) } != 0) shutdown(c.fd, SHUT_RDWR)
        }
    }

    internal actual fun close() {
        if (closed) return
        closed = true
        listeners.forEach { shutdown(it, SHUT_RDWR); close(it) }
        synchronized(lock) { connections.values.forEach { shutdown(it.fd, SHUT_RDWR) } }
    }

    private companion object {
        const val SOCKADDR_SIZE = 128
        const val MAX_CONNECTIONS = 4096
    }
}
