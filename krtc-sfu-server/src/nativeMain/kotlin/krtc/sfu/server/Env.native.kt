@file:OptIn(ExperimentalForeignApi::class)

package krtc.sfu.server

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.system.exitProcess

internal actual fun environment(name: String): String? = getenv(name)?.toKString()

internal actual fun exitWithError(message: String): Nothing {
    fputs(message + "\n", stderr)
    exitProcess(1)
}

/**
 * Whether nothing listens on TCP [port], on the wildcard or loopback address. With SO_REUSEADDR (not on Windows,
 * where it lets a socket take over a port) so connections of a server that just stopped (TIME_WAIT, FIN_WAIT) do not
 * count: the HTTP server binds with it too.
 */
internal actual fun tcpPortFree(port: Int): Boolean = listOf(0u, 0x7F000001u).all { host -> probe(port, host) }

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun probe(port: Int, host: UInt): Boolean = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return true // cannot tell: let the server try
    if (Platform.osFamily != OsFamily.WINDOWS) {
        val on = alloc<IntVar>()
        on.value = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, on.ptr, sizeOf<IntVar>().convert())
    }
    val addr = alloc<sockaddr_in>()
    memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
    addr.sin_family = AF_INET.convert()
    addr.sin_port = (((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)).convert() // network byte order
    val h = host.toInt()
    addr.sin_addr.s_addr = (((h and 0xFF) shl 24) or (((h ushr 8) and 0xFF) shl 16) or (((h ushr 16) and 0xFF) shl 8) or ((h ushr 24) and 0xFF)).toUInt().convert()
    val ok = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
    close(fd)
    ok
}
