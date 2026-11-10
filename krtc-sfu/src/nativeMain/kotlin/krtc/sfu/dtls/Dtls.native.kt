package krtc.sfu.dtls

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import krtc.sfu.openssl.BIO
import krtc.sfu.openssl.BIO_write
import krtc.sfu.openssl.EVP_PKEY
import krtc.sfu.openssl.SSL
import krtc.sfu.openssl.SSL_CTX
import krtc.sfu.openssl.SSL_ERROR_WANT_READ
import krtc.sfu.openssl.SSL_ERROR_WANT_WRITE
import krtc.sfu.openssl.SSL_ERROR_ZERO_RETURN
import krtc.sfu.openssl.SSL_do_handshake
import krtc.sfu.openssl.SSL_free
import krtc.sfu.openssl.SSL_get_error
import krtc.sfu.openssl.SSL_read
import krtc.sfu.openssl.SSL_write
import krtc.sfu.openssl.X509
import krtc.sfu.openssl.krtc_cert_sha256
import krtc.sfu.openssl.krtc_dtls_ctx
import krtc.sfu.openssl.krtc_dtls_handle_timeout
import krtc.sfu.openssl.krtc_dtls_new
import krtc.sfu.openssl.krtc_dtls_timeout_ms
import krtc.sfu.openssl.krtc_ec_p256_key
import krtc.sfu.openssl.krtc_export_srtp
import krtc.sfu.openssl.krtc_last_error
import krtc.sfu.openssl.krtc_peer_sha256
import krtc.sfu.openssl.krtc_self_signed
import krtc.sfu.openssl.krtc_srtp_profile_id
import krtc.sfu.platform.logger
import krtc.sfu.platform.withPointer
import krtc.sfu.srtp.SrtpProfile
import kotlin.concurrent.Volatile

/** Kotlin/Native: ECDSA P-256 key, self-signed certificate and a shared DTLS server SSL_CTX (OpenSSL 3). */
internal actual class DtlsIdentity private constructor(
    internal val ctx: CPointer<SSL_CTX>,
    certificate: CPointer<X509>,
) {
    internal actual val fingerprint: String = "sha-256 " + memScoped {
        val out = allocArray<UByteVar>(32)
        check(krtc_cert_sha256(certificate, out) == 32) { "certificate digest failed" }
        fingerprintHex(out.readBytes(32))
    }

    internal actual companion object {
        internal actual fun generate(): DtlsIdentity {
            val key: CPointer<EVP_PKEY> = krtc_ec_p256_key() ?: error("EC key generation failed: ${lastError()}")
            val cert = krtc_self_signed(key, "krtc-sfu") ?: error("certificate generation failed: ${lastError()}")
            val ctx = krtc_dtls_ctx(cert, key) ?: error("DTLS context failed: ${lastError()}")
            return DtlsIdentity(ctx, cert)
        }
    }
}

internal fun lastError(): String = memScoped {
    val buf = allocArray<ByteVar>(256)
    krtc_last_error(buf, 256)
    buf.toKString()
}

/**
 * Kotlin/Native DTLS-SRTP server endpoint over OpenSSL. One coroutine per endpoint owns the SSL
 * object: it feeds incoming records, drives the retransmission timer and, after the handshake,
 * keeps servicing the association (retransmitting our last flight if the client resends its own).
 */
internal actual class DtlsEndpoint actual constructor(
    private val identity: DtlsIdentity,
    private val expectedFingerprint: String,
    private val sender: (ByteArray, Int, Int) -> Unit,
    private val onConnected: (DtlsSrtpKeys) -> Unit,
    private val onFailed: (Throwable) -> Unit,
    private val onData: (ByteArray) -> Unit,
) {
    private val log = logger("krtc.sfu.dtls")
    private val inbox = Channel<ByteArray>(64)
    private val outbox = Channel<ByteArray>(256)
    @Volatile private var connected = false
    private var job: Job? = null

    @Volatile private var closed = false

    internal actual fun start() {
        job = CoroutineScope(Dispatchers.Default).launch { run() }
    }

    internal actual fun deliver(data: ByteArray) {
        if (!closed) inbox.trySend(data)
    }

    internal actual fun send(data: ByteArray) {
        if (connected && !closed) outbox.trySend(data) // full: dropped, SCTP retransmits
    }

    internal actual fun close() {
        closed = true
        inbox.close()
        outbox.close()
    }

    internal fun emit(data: ByteArray) {
        if (!closed) sender(data, 0, data.size)
    }

    private suspend fun run() {
        val self = StableRef.create(this)
        val (ssl, rbio) = memScoped {
            val rbioVar = alloc<CPointerVar<BIO>>()
            val s = krtc_dtls_new(identity.ctx, sendCallback, self.asCPointer(), rbioVar.ptr)
            s to rbioVar.value
        }
        try {
            if (ssl == null || rbio == null) throw IllegalStateException("SSL_new failed: ${lastError()}")
            handshake(ssl, rbio)
            val keys = exportKeys(ssl)
            onConnected(keys)
            connected = true
            service(ssl, rbio)
        } catch (t: Throwable) {
            if (!closed) {
                log.debug("DTLS failed: $t")
                onFailed(t)
            }
        } finally {
            if (ssl != null) SSL_free(ssl) // also frees both BIOs
            self.dispose()
        }
    }

    private suspend fun handshake(ssl: CPointer<SSL>, rbio: CPointer<BIO>) {
        while (true) {
            val r = SSL_do_handshake(ssl)
            if (r == 1) return
            when (val e = SSL_get_error(ssl, r)) {
                SSL_ERROR_WANT_READ, SSL_ERROR_WANT_WRITE -> {}
                else -> throw IllegalStateException("handshake error $e: ${lastError()}")
            }
            val timeout = krtc_dtls_timeout_ms(ssl).let { if (it < 0) 1000L else it.coerceAtLeast(1) }
            val record = withTimeoutOrNull(timeout) { inbox.receiveCatching().getOrNull() ?: throw IllegalStateException("closed") }
            if (record == null) {
                if (krtc_dtls_handle_timeout(ssl) < 0) throw IllegalStateException("handshake timeout: ${lastError()}")
            } else {
                record.withPointer { BIO_write(rbio, it, record.size) }
            }
        }
    }

    private fun exportKeys(ssl: CPointer<SSL>): DtlsSrtpKeys {
        val actual = memScoped {
            val out = allocArray<UByteVar>(32)
            if (krtc_peer_sha256(ssl, out) != 32) throw IllegalStateException("no client certificate")
            fingerprintHex(out.readBytes(32))
        }
        if (!actual.equals(expectedFingerprint, ignoreCase = true)) {
            throw IllegalStateException("client certificate does not match the SDP fingerprint")
        }
        val profile = SrtpProfile.byId(krtc_srtp_profile_id(ssl)) ?: throw IllegalStateException("no SRTP profile negotiated")
        val material = ByteArray(2 * (profile.keyLength + profile.saltLength))
        check(material.withPointer { krtc_export_srtp(ssl, it, material.size) } == 1) { "keying material export failed" }
        return DtlsSrtpKeys(profile, material)
    }

    /**
     * After the handshake: incoming records (application data → [onData], our final flight again if the client
     * resends its own, close_notify) and outgoing application data, both on this coroutine, which owns the SSL.
     */
    private suspend fun service(ssl: CPointer<SSL>, rbio: CPointer<BIO>) {
        val buf = ByteArray(1500)
        while (true) {
            val stop = select {
                inbox.onReceiveCatching { r ->
                    val record = r.getOrNull() ?: return@onReceiveCatching true
                    record.withPointer { BIO_write(rbio, it, record.size) }
                    readAll(ssl, buf)
                }
                outbox.onReceiveCatching { r ->
                    val data = r.getOrNull() ?: return@onReceiveCatching true
                    data.withPointer { SSL_write(ssl, it, data.size) }
                    false
                }
            }
            if (stop) return
        }
    }

    /** Reads every application record buffered in [ssl]; true on close_notify. */
    private fun readAll(ssl: CPointer<SSL>, buf: ByteArray): Boolean {
        while (true) {
            val n = buf.withPointer { SSL_read(ssl, it, buf.size) }
            if (n <= 0) return SSL_get_error(ssl, n) == SSL_ERROR_ZERO_RETURN
            onData(buf.copyOf(n))
        }
    }

    private companion object {
        val sendCallback = staticCFunction { user: COpaquePointer?, data: CPointer<UByteVar>?, len: Int ->
            if (user != null && data != null && len > 0) {
                user.asStableRef<DtlsEndpoint>().get().emit(data.readBytes(len))
            }
        }
    }
}
