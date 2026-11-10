package krtc.sfu.dtls

import krtc.sfu.srtp.SrtpProfile
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.ClientCertificateType
import org.bouncycastle.tls.DTLSServerProtocol
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.ExporterLabel
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsSRTPUtils
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.UseSRTPData
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.jcajce.JcaDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCrypto
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.Hashtable
import java.util.Vector
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Self-signed ECDSA P-256 identity shared by all DTLS sessions of one SFU process. */
internal actual class DtlsIdentity private constructor(
    internal val privateKey: PrivateKey,
    internal val certificateDer: ByteArray,
) {
    internal val crypto: JcaTlsCrypto = JcaTlsCryptoProvider().create(SecureRandom())

    /** `sha-256 AB:CD:...` value for the SDP `a=fingerprint` attribute. */
    internal actual val fingerprint: String = "sha-256 " + sha256Fingerprint(certificateDer)

    internal actual companion object {
        internal actual fun generate(): DtlsIdentity {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=krtc-sfu")
            val now = System.currentTimeMillis()
            val cert = JcaX509v3CertificateBuilder(
                name, BigInteger(64, SecureRandom()), Date(now - 86_400_000L), Date(now + 30L * 86_400_000L), name, kp.public,
            ).build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private))
            return DtlsIdentity(kp.private, cert.encoded)
        }

        internal fun sha256Fingerprint(der: ByteArray): String =
            fingerprintHex(MessageDigest.getInstance("SHA-256").digest(der))
    }
}

/**
 * Server side of DTLS-SRTP for one participant (RFC 5763/5764). The browser is the DTLS client
 * (we offer `a=setup:passive`). Packets are fed in with [deliver]; outgoing records go to [sender].
 * The handshake runs on a virtual thread so thousands of sessions cost no platform threads.
 */
internal actual class DtlsEndpoint actual constructor(
    private val identity: DtlsIdentity,
    /** Remote certificate fingerprint from the SDP answer, uppercase hex with colons. */
    private val expectedFingerprint: String,
    private val sender: (ByteArray, Int, Int) -> Unit,
    private val onConnected: (DtlsSrtpKeys) -> Unit,
    private val onFailed: (Throwable) -> Unit,
    private val onData: (ByteArray) -> Unit,
) {
    private val log = krtc.sfu.platform.logger("krtc.sfu.dtls")
    @Volatile private var transport: org.bouncycastle.tls.DTLSTransport? = null
    private val sendLock = Any()
    private val inbox = LinkedBlockingQueue<ByteArray>(256)

    @Volatile
    private var closed = false
    private var thread: Thread? = null

    internal actual fun start() {
        thread = Thread.ofVirtual().name("dtls").start { run() }
    }

    /** Called from the UDP thread with a DTLS record (RFC 7983 range 20..63). */
    internal actual fun deliver(data: ByteArray) {
        if (!closed) inbox.offer(data)
    }

    internal actual fun send(data: ByteArray) {
        val t = transport ?: return
        if (closed) return
        // BouncyCastle's record layer keeps separate read and write state; writers only exclude each other.
        try {
            synchronized(sendLock) { t.send(data, 0, data.size) }
        } catch (e: java.io.IOException) {
            log.debug("DTLS send failed: $e")
        }
    }

    internal actual fun close() {
        closed = true
        thread?.interrupt()
    }

    private fun run() {
        val transport = object : DatagramTransport {
            override fun getReceiveLimit(): Int = 1500
            override fun getSendLimit(): Int = 1200
            override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
                if (closed) throw java.io.InterruptedIOException("closed")
                val p = try {
                    inbox.poll(waitMillis.toLong(), TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    throw java.io.InterruptedIOException("closed")
                } ?: return -1
                val n = minOf(len, p.size)
                p.copyInto(buf, off, 0, n)
                return n
            }
            override fun send(buf: ByteArray, off: Int, len: Int) {
                if (!closed) sender(buf, off, len)
            }
            override fun close() {}
        }
        try {
            val server = SrtpTlsServer()
            val dtls = DTLSServerProtocol().accept(server, transport)
            onConnected(server.keys ?: throw IllegalStateException("no keying material"))
            this.transport = dtls
            // Keep servicing the association: application data (SCTP), our final flight again if the
            // client resends its own, and close_notify.
            val buf = ByteArray(1500)
            while (!closed) {
                val n = dtls.receive(buf, 0, buf.size, 1000)
                if (n > 0) onData(buf.copyOf(n)) else if (n < -1) break
            }
        } catch (t: Throwable) {
            if (!closed) {
                log.debug("DTLS failed: $t")
                onFailed(t)
            }
        }
    }

    private inner class SrtpTlsServer : DefaultTlsServer(identity.crypto) {
        var keys: DtlsSrtpKeys? = null
        private var chosen: SrtpProfile? = null

        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()

        override fun getSupportedCipherSuites(): IntArray = intArrayOf(
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
        )

        override fun getECDSASignerCredentials(): TlsCredentialedSigner {
            val cert = Certificate(arrayOf(identity.crypto.createCertificate(identity.certificateDer)))
            return JcaDefaultTlsCredentialedSigner(
                TlsCryptoParameters(context), identity.crypto, identity.privateKey, cert,
                SignatureAndHashAlgorithm.getInstance(org.bouncycastle.tls.HashAlgorithm.sha256, org.bouncycastle.tls.SignatureAlgorithm.ecdsa),
            )
        }

        override fun processClientExtensions(clientExtensions: Hashtable<*, *>?) {
            super.processClientExtensions(clientExtensions)
            val offered = TlsSRTPUtils.getUseSRTPExtension(clientExtensions)?.protectionProfiles
                ?: throw TlsFatalAlert(AlertDescription.handshake_failure)
            chosen = SrtpProfile.preference.firstOrNull { p -> offered.contains(p.id) }
                ?: throw TlsFatalAlert(AlertDescription.handshake_failure)
        }

        @Suppress("UNCHECKED_CAST")
        override fun getServerExtensions(): Hashtable<*, *> {
            val ext = (super.getServerExtensions() ?: Hashtable<Any, Any>()) as Hashtable<Any, Any>
            TlsSRTPUtils.addUseSRTPExtension(ext, UseSRTPData(intArrayOf(chosen!!.id), TlsUtils.EMPTY_BYTES))
            return ext
        }

        /** Request the client certificate so its fingerprint can be checked against the SDP. */
        override fun getCertificateRequest(): CertificateRequest = CertificateRequest(
            shortArrayOf(ClientCertificateType.ecdsa_sign, ClientCertificateType.rsa_sign),
            TlsUtils.getDefaultSupportedSignatureAlgorithms(context) as Vector<*>,
            null,
        )

        override fun notifyClientCertificate(clientCertificate: Certificate) {
            if (clientCertificate.isEmpty) throw TlsFatalAlert(AlertDescription.bad_certificate)
            val actual = DtlsIdentity.sha256Fingerprint(clientCertificate.getCertificateAt(0).encoded)
            if (!actual.equals(expectedFingerprint, ignoreCase = true)) {
                throw TlsFatalAlert(AlertDescription.bad_certificate)
            }
        }

        override fun notifyHandshakeComplete() {
            super.notifyHandshakeComplete()
            val p = chosen!!
            val material = context.exportKeyingMaterial(ExporterLabel.dtls_srtp, null, 2 * (p.keyLength + p.saltLength))
            keys = DtlsSrtpKeys(p, material)
        }
    }
}
