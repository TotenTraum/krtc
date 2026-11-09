package krtc.sfu.platform

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// JVM: JCE providers use AES-NI / ARMv8 crypto intrinsics.

internal actual class AesCtr actual constructor(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")

    internal actual fun process(iv: ByteArray, input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int) {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
        cipher.doFinal(input, inOff, length, output, outOff)
    }
}

internal actual class AesGcm actual constructor(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    internal actual fun encrypt(
        iv: ByteArray, aad: ByteArray, aadOff: Int, aadLen: Int,
        input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int,
    ): Int {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad, aadOff, aadLen)
        return cipher.doFinal(input, inOff, length, output, outOff)
    }

    internal actual fun decrypt(
        iv: ByteArray, aad: ByteArray, aadOff: Int, aadLen: Int,
        input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int,
    ): Int {
        if (length < 16) return -1
        return try {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad, aadOff, aadLen)
            cipher.doFinal(input, inOff, length, output, outOff)
        } catch (_: AEADBadTagException) {
            -1
        }
    }
}

internal actual class HmacSha1 actual constructor(key: ByteArray) {
    private val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }

    internal actual fun update(data: ByteArray, offset: Int, length: Int) {
        mac.update(data, offset, length)
    }

    internal actual fun doFinal(output: ByteArray, outOff: Int) {
        mac.doFinal(output, outOff)
    }
}

private val random = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

internal actual fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()

internal actual fun environment(name: String): String? = System.getenv(name)
