package com.torve.android.security

import com.google.crypto.tink.subtle.X25519
import com.torve.domain.transfer.EphemeralKeyPair
import com.torve.domain.transfer.TransferCryptoEngine
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android implementation of [TransferCryptoEngine].
 *
 * X25519 keypair gen + ECDH come from Tink's `subtle.X25519` because
 * `java.security.spec.NamedParameterSpec.X25519` only landed in JDK 11
 * / Android API 33 — Torve targets minSdk 24. Tink's wire format is
 * the same 32-byte little-endian RFC 7748 §5 representation that the
 * desktop [com.torve.desktop.security.JvmTransferCryptoEngine] uses,
 * so envelopes round-trip byte-for-byte across platforms.
 *
 * AES-256-GCM and HMAC-SHA256 (used inside HKDF) come from the
 * platform JCA — both available on every supported API.
 *
 * No private-key material is ever logged or persisted; it lives in
 * caller-owned ByteArrays and is dropped when those go out of scope.
 */
class AndroidTransferCryptoEngine : TransferCryptoEngine {

    private val secureRandom: SecureRandom = SecureRandom()

    override suspend fun generateEphemeralKeyPair(): EphemeralKeyPair {
        val privateKey = X25519.generatePrivateKey()
        val publicKey = X25519.publicFromPrivate(privateKey)
        return EphemeralKeyPair(publicKey = publicKey, privateKey = privateKey)
    }

    override suspend fun deriveSharedKey(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        info: ByteArray,
    ): ByteArray {
        require(peerPublicKey.size == X25519_KEY_BYTES) {
            "peer public key must be ${X25519_KEY_BYTES} bytes; got ${peerPublicKey.size}"
        }
        require(privateKey.size == X25519_KEY_BYTES) {
            "private key must be ${X25519_KEY_BYTES} bytes; got ${privateKey.size}"
        }
        val sharedSecret = X25519.computeSharedSecret(privateKey, peerPublicKey)
        return hkdfSha256(
            inputKeyingMaterial = sharedSecret,
            salt = ByteArray(32), // RFC 5869 § 2.2 default zero-salt
            info = info,
            length = AES_KEY_BYTES,
        )
    }

    override suspend fun encryptAead(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        require(key.size == AES_KEY_BYTES) { "AES key must be 32 bytes" }
        require(nonce.size == GCM_NONCE_BYTES) { "AES-GCM nonce must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(plaintext)
    }

    override suspend fun decryptAead(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray? {
        if (key.size != AES_KEY_BYTES || nonce.size != GCM_NONCE_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(associatedData)
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    override suspend fun secureRandom(byteCount: Int): ByteArray {
        require(byteCount >= 0)
        val out = ByteArray(byteCount)
        secureRandom.nextBytes(out)
        return out
    }

    /** RFC 5869 HKDF-SHA256, inline so we don't pull a second crypto dep. */
    private fun hkdfSha256(
        inputKeyingMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..(255 * 32)) { "HKDF output length out of range" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(inputKeyingMaterial)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var offset = 0
        var counter: Byte = 1
        var prev = ByteArray(0)
        while (offset < length) {
            mac.reset()
            mac.update(prev)
            mac.update(info)
            mac.update(counter)
            prev = mac.doFinal()
            val take = minOf(prev.size, length - offset)
            System.arraycopy(prev, 0, out, offset, take)
            offset += take
            counter = (counter + 1).toByte()
        }
        return out
    }

    companion object {
        const val X25519_KEY_BYTES = 32
        const val AES_KEY_BYTES = 32
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
