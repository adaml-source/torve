package com.torve.desktop.security

import com.torve.domain.transfer.EphemeralKeyPair
import com.torve.domain.transfer.TransferCryptoEngine
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.math.BigInteger

/**
 * Real implementation of [TransferCryptoEngine] backed by Java's built-in
 * crypto. Requires JDK 11+ which has X25519 (`NamedParameterSpec.X25519`)
 * and AES-GCM natively. No external dependencies.
 *
 * Public-key wire format here is the **raw 32-byte little-endian
 * u-coordinate**, matching RFC 7748 §5. JDK exposes that via
 * [XECPublicKey.getU] (a [BigInteger]) - we serialize/deserialize by
 * hand to keep the wire format compatible with non-Java receivers
 * (CryptoKit on iOS, libsodium etc.).
 */
class JvmTransferCryptoEngine : TransferCryptoEngine {

    private val secureRandom: SecureRandom = SecureRandom()

    override suspend fun generateEphemeralKeyPair(): EphemeralKeyPair {
        val gen = KeyPairGenerator.getInstance("X25519")
        gen.initialize(NamedParameterSpec.X25519, secureRandom)
        val pair = gen.generateKeyPair()
        val publicKeyBytes = serializeXecPublicKey(pair.public as XECPublicKey)
        // Private key uses PKCS#8 encoding here (opaque bytes). The
        // protocol layer never inspects the bytes; it just hands them
        // back to deriveSharedKey, which reconstructs the JDK key.
        val privateKeyBytes = pair.private.encoded
            ?: error("X25519 private key encoding unavailable on this JDK")
        return EphemeralKeyPair(publicKey = publicKeyBytes, privateKey = privateKeyBytes)
    }

    override suspend fun deriveSharedKey(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        info: ByteArray,
    ): ByteArray {
        require(peerPublicKey.size == X25519_KEY_BYTES) {
            "peer public key must be ${X25519_KEY_BYTES} bytes; got ${peerPublicKey.size}"
        }
        val keyFactory = KeyFactory.getInstance("X25519")
        val priv = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val peerSpec = XECPublicKeySpec(NamedParameterSpec.X25519, deserializeUCoord(peerPublicKey))
        val peer = keyFactory.generatePublic(peerSpec)

        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(priv)
        agreement.doPhase(peer, true)
        val sharedSecret = agreement.generateSecret()

        return hkdfSha256(
            inputKeyingMaterial = sharedSecret,
            salt = ByteArray(32),  // explicit zero-salt - RFC 5869 § 2.2 default
            info = info,
            length = 32,
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

    // ── helpers ──────────────────────────────────────────────────────

    /** Encode a JDK XECPublicKey as 32 little-endian bytes per RFC 7748 §5. */
    private fun serializeXecPublicKey(key: XECPublicKey): ByteArray {
        val u = key.u  // BigInteger, big-endian
        val bigEndian = u.toByteArray()
        // Strip a leading 0x00 sign byte if present, then left-pad to 32 bytes,
        // then reverse to little-endian.
        val unsigned = if (bigEndian.size > X25519_KEY_BYTES && bigEndian[0] == 0.toByte()) {
            bigEndian.copyOfRange(1, bigEndian.size)
        } else bigEndian
        val padded = ByteArray(X25519_KEY_BYTES)
        val srcStart = if (unsigned.size <= X25519_KEY_BYTES) 0 else unsigned.size - X25519_KEY_BYTES
        val dstStart = X25519_KEY_BYTES - (unsigned.size - srcStart)
        System.arraycopy(unsigned, srcStart, padded, dstStart, unsigned.size - srcStart)
        // big-endian → little-endian for X25519 wire format
        for (i in 0 until X25519_KEY_BYTES / 2) {
            val t = padded[i]
            padded[i] = padded[X25519_KEY_BYTES - 1 - i]
            padded[X25519_KEY_BYTES - 1 - i] = t
        }
        return padded
    }

    private fun deserializeUCoord(littleEndianBytes: ByteArray): BigInteger {
        val be = ByteArray(X25519_KEY_BYTES)
        for (i in 0 until X25519_KEY_BYTES) {
            be[i] = littleEndianBytes[X25519_KEY_BYTES - 1 - i]
        }
        return BigInteger(1, be)
    }

    /** RFC 5869 HKDF-SHA256. Implemented inline to avoid a dependency. */
    private fun hkdfSha256(
        inputKeyingMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..(255 * 32)) { "HKDF output length out of range" }
        // Extract
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(inputKeyingMaterial)
        // Expand
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
