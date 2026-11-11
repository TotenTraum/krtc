@file:OptIn(ExperimentalForeignApi::class)

package krtc.sfu.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.value
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.setsockopt
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.close
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.memset
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.stderr
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
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
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
