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
internal actual fun tcpPortFree(port: Int): Boolean = listOf(
    java.net.StandardProtocolFamily.INET to "0.0.0.0",
    java.net.StandardProtocolFamily.INET6 to "::",
).all { (family, host) ->
    try {
        java.nio.channels.ServerSocketChannel.open(family).use { channel ->
            channel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, false)
            channel.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName(host), port))
        }
        true
    } catch (_: java.io.IOException) {
        false
    }
}
