package krtc.sfu.platform

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import krtc.sfu.openssl.EVP_CIPHER_CTX
import krtc.sfu.openssl.EVP_CIPHER_CTX_free
import krtc.sfu.openssl.EVP_MAC_CTX
import krtc.sfu.openssl.EVP_MAC_CTX_free
import krtc.sfu.openssl.RAND_bytes
import krtc.sfu.openssl.krtc_aes_ctr
import krtc.sfu.openssl.krtc_aes_ctr_new
import krtc.sfu.openssl.krtc_aes_gcm_decrypt
import krtc.sfu.openssl.krtc_aes_gcm_encrypt
import krtc.sfu.openssl.krtc_aes_gcm_new
import krtc.sfu.openssl.krtc_hmac_final
import krtc.sfu.openssl.krtc_hmac_sha1_new
import krtc.sfu.openssl.krtc_hmac_update
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.getenv
import platform.posix.sysconf
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

// Kotlin/Native: OpenSSL 3 EVP (uses AES-NI / ARMv8 crypto extensions).

/** Pins [this] and passes a pointer to element [offset] (valid even for an empty array). */
internal inline fun <R> ByteArray.withPointer(offset: Int = 0, block: (CPointer<UByteVar>) -> R): R =
    if (size == 0) ByteArray(1).usePinned { block(it.addressOf(0).reinterpret()) }
    else usePinned { block(it.addressOf(offset.coerceAtMost(size - 1)).reinterpret()) }

@OptIn(ExperimentalNativeApi::class)
internal actual class AesCtr actual constructor(key: ByteArray) {
    private val ctx: CPointer<EVP_CIPHER_CTX> = key.withPointer { krtc_aes_ctr_new(it, key.size) } ?: error("AES-CTR init failed")

    @Suppress("unused")
    private val cleaner = createCleaner(ctx) { EVP_CIPHER_CTX_free(it) }

    internal actual fun process(iv: ByteArray, input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int) {
        if (length == 0) return
        val n = iv.withPointer { ivp ->
            input.withPointer(inOff) { ip -> output.withPointer(outOff) { op -> krtc_aes_ctr(ctx, ivp, ip, length, op) } }
        }
        check(n == length) { "AES-CTR failed" }
    }
}

@OptIn(ExperimentalNativeApi::class)
internal actual class AesGcm actual constructor(key: ByteArray) {
    private val enc: CPointer<EVP_CIPHER_CTX> = key.withPointer { krtc_aes_gcm_new(it, key.size, 1) } ?: error("AES-GCM init failed")
    private val dec: CPointer<EVP_CIPHER_CTX> = key.withPointer { krtc_aes_gcm_new(it, key.size, 0) } ?: error("AES-GCM init failed")

    @Suppress("unused")
    private val cleaner = createCleaner(enc to dec) { (e, d) -> EVP_CIPHER_CTX_free(e); EVP_CIPHER_CTX_free(d) }

    internal actual fun encrypt(
        iv: ByteArray, aad: ByteArray, aadOff: Int, aadLen: Int,
        input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int,
    ): Int = iv.withPointer { ivp ->
        aad.withPointer(aadOff) { ap ->
            input.withPointer(inOff) { ip -> output.withPointer(outOff) { op -> krtc_aes_gcm_encrypt(enc, ivp, ap, aadLen, ip, length, op) } }
        }
    }.also { check(it >= 0) { "AES-GCM encrypt failed" } }

    internal actual fun decrypt(
        iv: ByteArray, aad: ByteArray, aadOff: Int, aadLen: Int,
        input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int,
    ): Int = if (length < 16) -1 else iv.withPointer { ivp ->
        aad.withPointer(aadOff) { ap ->
            input.withPointer(inOff) { ip -> output.withPointer(outOff) { op -> krtc_aes_gcm_decrypt(dec, ivp, ap, aadLen, ip, length, op) } }
        }
    }
}

@OptIn(ExperimentalNativeApi::class)
internal actual class HmacSha1 actual constructor(key: ByteArray) {
    private val ctx: CPointer<EVP_MAC_CTX> = key.withPointer { krtc_hmac_sha1_new(it, key.size) } ?: error("HMAC init failed")

    @Suppress("unused")
    private val cleaner = createCleaner(ctx) { EVP_MAC_CTX_free(it) }

    internal actual fun update(data: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        check(data.withPointer(offset) { krtc_hmac_update(ctx, it, length) } == 1) { "HMAC update failed" }
    }

    internal actual fun doFinal(output: ByteArray, outOff: Int) {
        require(output.size - outOff >= 20)
        check(output.withPointer(outOff) { krtc_hmac_final(ctx, it) } == 20) { "HMAC final failed" }
    }
}

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    if (size > 0) check(out.withPointer { RAND_bytes(it, size) } == 1) { "RAND_bytes failed" }
    return out
}

internal actual fun availableProcessors(): Int = sysconf(_SC_NPROCESSORS_ONLN).toInt().coerceAtLeast(1)

internal actual fun environment(name: String): String? = getenv(name)?.toKString()
