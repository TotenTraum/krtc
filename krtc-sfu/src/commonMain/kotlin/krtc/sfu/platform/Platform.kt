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

internal fun logger(name: String): Logger = KtorSimpleLogger(name)

/** A UDP peer: IPv4 (4 bytes) or IPv6 (16 bytes) address and port. Used as a map key. */
internal class PeerAddress(public val ip: ByteArray, public val port: Int) {
    private val hash = ip.contentHashCode() * 31 + port

    override fun equals(other: Any?): Boolean = other is PeerAddress && other.port == port && other.ip.contentEquals(ip)
    override fun hashCode(): Int = hash
    override fun toString(): String =
        if (ip.size == 4) ip.joinToString(".") { (it.toInt() and 0xFF).toString() } + ":$port"
        else "[" + ip.toList().chunked(2).joinToString(":") { ((it[0].toInt() and 0xFF) shl 8 or (it[1].toInt() and 0xFF)).toString(16) } + "]:$port"
}

/**
 * One UDP port for all participants, served by [sockets] SO_REUSEPORT sockets (one send lane per shard).
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
    internal fun start(): UdpServer

    /** Thread-safe. [lane] picks the socket (a shard index) so shards never contend on one socket. */
    internal fun send(lane: Int, data: ByteArray, length: Int, to: PeerAddress)
    internal fun close()
}

internal expect fun availableProcessors(): Int

internal expect fun environment(name: String): String?
