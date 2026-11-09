package krtc.sfu.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVBUF
import platform.posix.SO_REUSEADDR
import platform.posix.SO_REUSEPORT
import platform.posix.SO_SNDBUF
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import krtc.sfu.openssl.krtc_set_recv_timeout_ms
import krtc.sfu.openssl.krtc_sockaddr_ipv4
import kotlinx.cinterop.IntVar
import kotlin.concurrent.Volatile

/**
 * Kotlin/Native: POSIX UDP sockets bound to one port with SO_REUSEPORT, one per shard (send lane).
 * Each socket has a receive thread; on Linux the kernel spreads incoming datagrams across them.
 * IPv4 only for now.
 */
internal actual class UdpServer actual constructor(
    bindHost: String,
    port: Int,
    sockets: Int,
    private val onPacket: (data: ByteArray, length: Int, from: PeerAddress) -> Unit,
) {
    private val log = logger("krtc.sfu.udp")
    private val fds: List<Int>

    @Volatile private var closed = false

    init {
        val first = open(bindHost, port, reusePort = sockets > 1)
        val bound = boundPort(first)
        fds = listOf(first) + List(sockets - 1) { open(bindHost, bound, reusePort = true) }
    }

    internal actual val localPort: Int = boundPort(fds[0])
    internal actual val socketCount: Int get() = fds.size

    private fun open(host: String, port: Int, reusePort: Boolean): Int = memScoped {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "socket() failed: errno $errno" }
        val one = alloc<IntVar>().apply { value = 1 }
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
        if (reusePort) setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<IntVar>().convert())
        val bufSize = alloc<IntVar>().apply { value = 8 shl 20 }
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, bufSize.ptr, sizeOf<IntVar>().convert())
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, bufSize.ptr, sizeOf<IntVar>().convert())
        // A receive timeout lets the receive threads notice close().
        krtc_set_recv_timeout_ms(fd, 500)
        val addr = alloc<sockaddr_in>()
        val ip = if (host == "0.0.0.0" || host.isEmpty()) 0u else ipv4ToNet(parseIpv4(host))
        krtc_sockaddr_ipv4(addr.ptr, ip, port)
        check(bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind($host:$port) failed: errno $errno" }
        fd
    }

    private fun boundPort(fd: Int): Int = memScoped {
        val addr = alloc<sockaddr_in>()
        val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
        getsockname(fd, addr.ptr.reinterpret<sockaddr>(), len.ptr)
        netToHost16(addr.sin_port)
    }

    internal actual fun start(): UdpServer = apply {
        fds.forEachIndexed { i, fd ->
            CoroutineScope(newSingleThreadContext("udp-rx-$i")).launch { receiveLoop(fd) }
        }
    }

    private fun receiveLoop(fd: Int) {
        val buf = ByteArray(2048)
        memScoped {
            val from = alloc<sockaddr_in>()
            val fromLen = alloc<socklen_tVar>()
            while (!closed) {
                fromLen.value = sizeOf<sockaddr_in>().convert()
                val n = buf.withPointer { recvfrom(fd, it, buf.size.convert(), 0, from.ptr.reinterpret(), fromLen.ptr) }.toInt()
                if (n <= 0) continue // timeout or interrupted
                val data = buf.copyOf(n + 16)
                val ip = from.sin_addr.s_addr.toLong()
                val address = PeerAddress(
                    byteArrayOf(ip.toByte(), (ip ushr 8).toByte(), (ip ushr 16).toByte(), (ip ushr 24).toByte()),
                    netToHost16(from.sin_port),
                )
                try {
                    onPacket(data, n, address)
                } catch (t: Throwable) {
                    log.warn("UDP packet handling failed: $t")
                }
            }
        }
    }

    internal actual fun send(lane: Int, data: ByteArray, length: Int, to: PeerAddress) {
        if (closed || to.ip.size != 4) return
        memScoped {
            val addr = alloc<sockaddr_in>()
            krtc_sockaddr_ipv4(addr.ptr, ipv4ToNet(to.ip), to.port)
            data.withPointer { sendto(fds[lane % fds.size], it, length.convert(), 0, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) }
        }
    }

    internal actual fun close() {
        if (closed) return
        closed = true
        fds.forEach { close(it) }
    }

    private fun parseIpv4(host: String): ByteArray {
        val parts = host.split('.').map { it.toIntOrNull() }
        require(parts.size == 4 && parts.all { it != null && it in 0..255 }) { "bad IPv4 address $host" }
        return ByteArray(4) { parts[it]!!.toByte() }
    }

    /** Network-order IPv4 address as the in-memory value of `s_addr` on a little-endian host. */
    private fun ipv4ToNet(b: ByteArray): UInt = (b[0].toUInt() and 0xFFu) or ((b[1].toUInt() and 0xFFu) shl 8) or
        ((b[2].toUInt() and 0xFFu) shl 16) or ((b[3].toUInt() and 0xFFu) shl 24)

    // All supported native targets (x86_64, arm64) are little-endian.
    private fun netToHost16(v: UShort): Int = v.toInt().let { ((it and 0xFF) shl 8) or ((it ushr 8) and 0xFF) }
}
