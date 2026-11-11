@file:OptIn(ExperimentalForeignApi::class)

package krtc.sfu.server

import kotlinx.cinterop.ExperimentalForeignApi
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

internal actual fun tcpPortFree(port: Int): Boolean = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return true // cannot tell: let the server try
    val addr = alloc<sockaddr_in>()
    memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
    addr.sin_family = AF_INET.convert()
    addr.sin_port = (((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)).convert() // network byte order
    val ok = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
    close(fd)
    ok
}
