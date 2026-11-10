package krtc.sfu.platform

import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.logging.Logger
import kotlin.time.TimeSource

// Platform seams of the SFU. Everything else is common code.
//   jvm    — JCE (AES-NI) and NIO
//   native — OpenSSL 3 EVP via cinterop and POSIX sockets

/** AES-128/256 in counter mode with a full 16-byte IV (SRTP AES-CM and the SRTP KDF). Not thread-safe. */
internal expect class AesCtr(key: ByteArray) {
    internal fun process(iv: ByteArray, input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int)
}

/** AES-GCM with a 12-byte IV and 16-byte tag (RFC 7714). Not thread-safe. In-place operation is allowed. */
internal expect class AesGcm(key: ByteArray) {
    /** Writes ciphertext followed by the tag at [outOff]; returns the number of bytes written (length + 16). */
    internal fun encrypt(
        iv: ByteArray, aad: ByteArray, aadOff: Int, aadLen: Int,
        input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int,
    ): Int

    /** [length] includes the tag. Returns the plaintext length, or -1 if authentication fails. */
    internal fun decrypt(
        iv: ByteArray, aad: ByteArray, aadOff: Int, aadLen: Int,
        input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int,
    ): Int
}

/** HMAC-SHA1 with a fixed key (SRTP authentication). Not thread-safe. */
internal expect class HmacSha1(key: ByteArray) {
    internal fun update(data: ByteArray, offset: Int, length: Int)

    /** Writes the 20-byte MAC at [outOff] and resets for the next message. */
    internal fun doFinal(output: ByteArray, outOff: Int)
}

/** Bytes from the platform's cryptographically secure generator. */
internal expect fun secureRandomBytes(size: Int): ByteArray

internal fun secureRandomLong(): Long {
    val b = secureRandomBytes(8)
    var v = 0L
    for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
    return v
}

internal fun secureRandomInt(bound: Int): Int = ((secureRandomLong() ushr 1) % bound).toInt()

private val processStart = TimeSource.Monotonic.markNow()

/** Monotonic milliseconds since process start (for timeouts, never for wall-clock time). */
internal fun nowMillis(): Long = processStart.elapsedNow().inWholeMilliseconds

/** Monotonic microseconds since process start (packet arrival and send times for congestion control). */
internal fun nowMicros(): Long = processStart.elapsedNow().inWholeMicroseconds

internal fun logger(name: String): Logger = KtorSimpleLogger(name)

/**
 * A UDP peer: IPv4 (4 bytes) or IPv6 (16 bytes) address and port. Used as a map key (by address and port).
 * [via] says which of the server's local addresses the peer talks to, so replies leave from that address;
 * it is not part of the identity.
 */
internal class PeerAddress(public val ip: ByteArray, public val port: Int, public val tcp: Boolean = false) {
    private val hash = (ip.contentHashCode() * 31 + port) * 2 + (if (tcp) 1 else 0)

    /** Index of the [UdpServer] socket group (local address) that received this peer's packet. */
    internal var via: Int = 0

    override fun equals(other: Any?): Boolean = other is PeerAddress && other.port == port && other.tcp == tcp && other.ip.contentEquals(ip)
    override fun hashCode(): Int = hash
    override fun toString(): String = (if (tcp) "tcp:" else "") +
        if (ip.size == 4) ip.joinToString(".") { (it.toInt() and 0xFF).toString() } + ":$port"
        else "[" + ip.toList().chunked(2).joinToString(":") { ((it[0].toInt() and 0xFF) shl 8 or (it[1].toInt() and 0xFF)).toString(16) } + "]:$port"
}

/**
 * One UDP port for all participants. A wildcard [bindHost] (`0.0.0.0`: IPv4, `::`: IPv4 and IPv6) opens a socket
 * group per local interface address so replies leave from the address the peer used; each group has [sockets]
 * SO_REUSEPORT sockets (one send lane per shard).
 * [onPacket] runs on a receive thread with a fresh array (with 16 spare bytes) per datagram.
 */
internal expect class UdpServer(
    bindHost: String,
    port: Int,
    sockets: Int,
    onPacket: (data: ByteArray, length: Int, from: PeerAddress) -> Unit,
) {
    internal val localPort: Int
    internal val socketCount: Int

    /** The local addresses actually bound (wildcards expanded), IPv4 as 4 bytes and IPv6 as 16. */
    internal val localAddresses: List<ByteArray>
    internal fun start(): UdpServer

    /** Thread-safe. [lane] picks the socket (a shard index) so shards never contend on one socket. */
    internal fun send(lane: Int, data: ByteArray, length: Int, to: PeerAddress)
    internal fun close()
}

internal expect fun availableProcessors(): Int

internal expect fun environment(name: String): String?

/**
 * ICE-TCP (RFC 6544): passive TCP listeners on [port] at each of [addresses], for clients whose UDP is blocked.
 * Packets are framed with a 16-bit length (RFC 4571); each connection is a [PeerAddress] with `tcp = true`.
 * [onPacket] runs on the connection's reader thread with a fresh array (with 16 spare bytes) per packet.
 */
internal expect class TcpServer(
    addresses: List<ByteArray>,
    port: Int,
    onPacket: (data: ByteArray, length: Int, from: PeerAddress) -> Unit,
) {
    internal fun start(): TcpServer

    /** Thread-safe; drops the packet if the connection is gone. */
    internal fun send(data: ByteArray, length: Int, to: PeerAddress)
    internal fun close()
}

/** Sends over whichever transport a peer uses. */
internal class Transports(internal val udp: UdpServer, internal val tcp: TcpServer?) {
    internal fun send(lane: Int, data: ByteArray, length: Int, to: PeerAddress) {
        if (to.tcp) tcp?.send(data, length, to) else udp.send(lane, data, length, to)
    }
}
