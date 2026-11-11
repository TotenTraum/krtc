package krtc.sfu.server

internal actual fun environment(name: String): String? = System.getenv(name)

internal actual fun exitWithError(message: String): Nothing {
    System.err.println(message)
    kotlin.system.exitProcess(1)
}

/**
 * Tries an IPv4 and an IPv6 listener on [port], each on a socket of its own family: the JVM's default sockets are
 * IPv6 even for 0.0.0.0 and would not notice an IPv4 listener.
 */
/**
 * Whether nothing listens on TCP [port], on the wildcard or loopback addresses. With SO_REUSEADDR (not on Windows,
 * where it lets a socket take over a port) so connections of a server that just stopped (TIME_WAIT, FIN_WAIT) do not
 * count: the HTTP server binds with it too.
 */
internal actual fun tcpPortFree(port: Int): Boolean = listOf(
    java.net.StandardProtocolFamily.INET to "0.0.0.0",
    java.net.StandardProtocolFamily.INET6 to "::",
    java.net.StandardProtocolFamily.INET to "127.0.0.1",
    java.net.StandardProtocolFamily.INET6 to "::1",
).all { (family, host) ->
    try {
        java.nio.channels.ServerSocketChannel.open(family).use { channel ->
            channel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, !System.getProperty("os.name").startsWith("Windows"))
            channel.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName(host), port))
        }
        true
    } catch (_: java.io.IOException) {
        false
    }
}
