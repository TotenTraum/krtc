package krtc.sfu.dtls

import krtc.sfu.srtp.SrtpProfile

/** Self-signed ECDSA P-256 identity shared by all DTLS sessions of one SFU process. */
internal expect class DtlsIdentity {
    /** `sha-256 AB:CD:...` value for the SDP `a=fingerprint` attribute. */
    internal val fingerprint: String

    internal companion object {
        internal fun generate(): DtlsIdentity
    }
}

/** Result of a completed DTLS-SRTP handshake: the negotiated profile and RFC 5764 keying material. */
internal class DtlsSrtpKeys(public val profile: SrtpProfile, public val material: ByteArray)

/**
 * Server side of DTLS-SRTP for one participant (RFC 5763/5764). The browser is the DTLS client
 * (the SFU offers `a=setup:passive`); its certificate must match [expectedFingerprint] from the SDP.
 *
 * Records arrive through [deliver] (any thread); outgoing records go to `sender`.
 * Exactly one of `onConnected` / `onFailed` is called, from an internal thread.
 * After the handshake the association carries application data both ways (SCTP for data channels,
 * RFC 8261): received plaintext goes to `onData` (internal thread), [send] encrypts one record.
 */
internal expect class DtlsEndpoint(
    identity: DtlsIdentity,
    expectedFingerprint: String,
    sender: (ByteArray, Int, Int) -> Unit,
    onConnected: (DtlsSrtpKeys) -> Unit,
    onFailed: (Throwable) -> Unit,
    onData: (ByteArray) -> Unit,
) {
    internal fun start()
    internal fun deliver(data: ByteArray)

    /** Sends [data] as one application-data record; any thread. Ignored before the handshake completes. */
    internal fun send(data: ByteArray)
    internal fun close()
}

/** Uppercase colon-separated hex, as used in SDP fingerprints. */
internal fun fingerprintHex(digest: ByteArray): String =
    digest.joinToString(":") { b -> (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
