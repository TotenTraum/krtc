package krtc.sfu.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVBUF
import platform.posix.SO_REUSEADDR
import platform.posix.EADDRINUSE
import platform.posix.SO_REUSEPORT
import platform.posix.SO_SNDBUF
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.socklen_tVar
import krtc.sfu.openssl.krtc_local_addresses
import krtc.sfu.openssl.krtc_parse_ip
import krtc.sfu.openssl.krtc_set_ipv6_only
import krtc.sfu.openssl.krtc_set_recv_timeout_ms
import krtc.sfu.openssl.krtc_sockaddr_make
import krtc.sfu.openssl.krtc_sockaddr_read
import kotlin.concurrent.Volatile

/**
 * Kotlin/Native: POSIX UDP sockets. A wildcard bind (`0.0.0.0` for IPv4, `::` for IPv4 + IPv6) opens one
 * group of sockets per local interface address, so every reply leaves from the address the peer sent to
 * (what IP_PKTINFO gives, without per-packet control messages). Each group has [sockets] SO_REUSEPORT
 * sockets (one send lane per shard), each with a receive thread.
 */
internal actual class UdpServer actual constructor(
    bindHost: String,
    port: Int,
    sockets: Int,
    private val onPacket: (data: ByteArray, length: Int, from: PeerAddress) -> Unit,
) {
    private val log = logger("krtc.sfu.udp")
    private val groups: List<List<Int>> // per local address: its sockets (lanes)
    internal actual val localAddresses: List<ByteArray>

    @Volatile private var closed = false

    init {
        val addresses = resolveBind(bindHost)
        // The shard sockets share the port with SO_REUSEPORT, which the OS also allows across processes: a second
        // SFU on the same port would silently split the packets with the first. A plain bind first fails if anyone
        // else holds the port.
        if (port != 0) for (ip in addresses) {
            if (exclusiveBindErrno(ip, port) == EADDRINUSE) {
                throw IllegalArgumentException("UDP port $port is already in use on ${PeerAddress(ip, port)} (another krtc-sfu?)")
            }
        }
        var boundPort = port
        val opened = ArrayList<List<Int>>()
        val used = ArrayList<ByteArray>()
        for (ip in addresses) {
            try {
                val first = open(ip, boundPort, reusePort = sockets > 1)
                if (boundPort == 0) boundPort = boundPort(first)
                opened += listOf(first) + List(sockets - 1) { open(ip, boundPort, reusePort = true) }
                used += ip
            } catch (t: IllegalStateException) {
                if (addresses.size == 1) throw t
                log.warn("skipping ${PeerAddress(ip, boundPort)}: ${t.message}")
            }
        }
        check(opened.isNotEmpty()) { "no UDP socket could be bound for $bindHost:$port" }
        groups = opened
        localAddresses = used
    }

    internal actual val localPort: Int = boundPort(groups[0][0])
    internal actual val socketCount: Int get() = groups.sumOf { it.size }

    /** Wildcards expand to the interface addresses; anything else is one literal address. */
    private fun resolveBind(host: String): List<ByteArray> {
        val want = when (host) {
            "", "0.0.0.0" -> 4
            "::" -> 0
            else -> return listOf(parseIp(host))
        }
        val raw = ByteArray(4096)
        val n = raw.usePinned { krtc_local_addresses(want, it.addressOf(0).reinterpret(), raw.size) }
        val out = ArrayList<ByteArray>()
        var p = 0
        while (p < n) {
            val len = raw[p].toInt()
            out += raw.copyOfRange(p + 1, p + 1 + len)
            p += 1 + len
        }
        return out.ifEmpty { listOf(parseIp(if (want == 0) "::" else "0.0.0.0")) }
    }

    private fun parseIp(text: String): ByteArray = memScoped {
        val buf = allocArray<ByteVar>(16)
        val len = krtc_parse_ip(text, buf.reinterpret())
        require(len == 4 || len == 16) { "bad IP address $text" }
        buf.readBytes(len)
    }

    private fun open(ip: ByteArray, port: Int, reusePort: Boolean): Int = memScoped {
        val fd = socket(if (ip.size == 4) AF_INET else AF_INET6, SOCK_DGRAM, 0)
        check(fd >= 0) { "socket() failed: errno $errno" }
        val one = alloc<IntVar>().apply { value = 1 }
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
        if (reusePort) setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<IntVar>().convert())
        if (ip.size == 16) krtc_set_ipv6_only(fd) // IPv4 has its own sockets
        val bufSize = alloc<IntVar>().apply { value = 8 shl 20 }
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, bufSize.ptr, sizeOf<IntVar>().convert())
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, bufSize.ptr, sizeOf<IntVar>().convert())
        krtc_set_recv_timeout_ms(fd, 500) // lets the receive threads notice close()
        val addr = allocArray<ByteVar>(SOCKADDR_SIZE)
        val len = ip.usePinned { krtc_sockaddr_make(addr, it.addressOf(0).reinterpret(), ip.size, port) }
        if (bind(fd, addr.reinterpret(), len.convert()) != 0) {
            val e = errno
            close(fd)
            error("bind(${PeerAddress(ip, port)}) failed: errno $e")
        }
        fd
    }

    /** Binds [port] without SO_REUSEADDR/SO_REUSEPORT and releases it at once: 0, or the errno of the bind. */
    private fun exclusiveBindErrno(ip: ByteArray, port: Int): Int = memScoped {
        val fd = socket(if (ip.size == 4) AF_INET else AF_INET6, SOCK_DGRAM, 0)
        if (fd < 0) return errno
        if (ip.size == 16) krtc_set_ipv6_only(fd)
        val addr = allocArray<ByteVar>(SOCKADDR_SIZE)
        val len = ip.usePinned { krtc_sockaddr_make(addr, it.addressOf(0).reinterpret(), ip.size, port) }
        val e = if (bind(fd, addr.reinterpret(), len.convert()) != 0) errno else 0
        close(fd)
        e
    }

    private fun boundPort(fd: Int): Int = memScoped {
        val addr = allocArray<ByteVar>(SOCKADDR_SIZE)
        val len = alloc<socklen_tVar>().apply { value = SOCKADDR_SIZE.convert() }
        getsockname(fd, addr.reinterpret(), len.ptr)
        val ip = allocArray<ByteVar>(16)
        val port = alloc<IntVar>()
        krtc_sockaddr_read(addr, ip.reinterpret(), port.ptr)
        port.value
    }

    internal actual fun start(): UdpServer = apply {
        groups.forEachIndexed { g, fds ->
            fds.forEachIndexed { i, fd -> CoroutineScope(newSingleThreadContext("udp-rx-$g-$i")).launch { receiveLoop(fd, g) } }
        }
    }

    private fun receiveLoop(fd: Int, group: Int) {
        val buf = ByteArray(2048)
        memScoped {
            val from = allocArray<ByteVar>(SOCKADDR_SIZE)
            val fromLen = alloc<socklen_tVar>()
            val ip = allocArray<ByteVar>(16)
            val port = alloc<IntVar>()
            while (!closed) {
                fromLen.value = SOCKADDR_SIZE.convert()
                val n = buf.usePinned { recvfrom(fd, it.addressOf(0), buf.size.convert(), 0, from.reinterpret(), fromLen.ptr) }.toInt()
                if (n <= 0) continue // timeout or interrupted
                val ipLen = krtc_sockaddr_read(from, ip.reinterpret(), port.ptr)
                if (ipLen == 0) continue
                val data = buf.copyOf(n + 16)
                val address = PeerAddress(ip.readBytes(ipLen), port.value).also { it.via = group }
                try {
                    onPacket(data, n, address)
                } catch (t: Throwable) {
                    log.warn("UDP packet handling failed: $t")
                }
            }
        }
    }

    internal actual fun send(lane: Int, data: ByteArray, length: Int, to: PeerAddress) {
        if (closed) return
        val fds = groups.getOrNull(to.via) ?: return
        memScoped {
            val addr = allocArray<ByteVar>(SOCKADDR_SIZE)
            val len = to.ip.usePinned { krtc_sockaddr_make(addr, it.addressOf(0).reinterpret(), to.ip.size, to.port) }
            if (len == 0) return
            data.usePinned { sendto(fds[lane % fds.size], it.addressOf(0), length.convert(), 0, addr.reinterpret(), len.convert()) }
        }
    }

    internal actual fun close() {
        if (closed) return
        closed = true
        groups.flatten().forEach { close(it) }
    }

    private companion object {
        const val SOCKADDR_SIZE = 128 // sizeof(struct sockaddr_storage)
    }
}
