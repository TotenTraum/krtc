package krtc.sfu.platform

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// JVM: JCE providers use AES-NI / ARMv8 crypto intrinsics.

public actual class AesCtr actual constructor(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")

    public actual fun process(iv: ByteArray, input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int) {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
        cipher.doFinal(input, inOff, length, output, outOff)
    }
}

public actual class AesGcm actual constructor(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    public actual fun encrypt(
        iv: ByteArray, aad: ByteArray, aadOff: Int, aadLen: Int,
        input: ByteArray, inOff: Int, length: Int, output: ByteArray, outOff: Int,
    ): Int {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad, aadOff, aadLen)
        return cipher.doFinal(input, inOff, length, output, outOff)
    }

    public actual fun decrypt(
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

public actual class HmacSha1 actual constructor(key: ByteArray) {
    private val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }

    public actual fun update(data: ByteArray, offset: Int, length: Int) {
        mac.update(data, offset, length)
    }

    public actual fun doFinal(output: ByteArray, outOff: Int) {
        mac.doFinal(output, outOff)
    }
}

private val random = SecureRandom()

public actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

public actual fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()

public actual fun environment(name: String): String? = System.getenv(name)
