package com.torve.android.security

import com.torve.domain.transfer.SecretCategory
import com.torve.domain.transfer.SecretRecord
import com.torve.domain.transfer.SecretsTransferProtocol
import com.torve.domain.transfer.TransferDecryptResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates that [AndroidTransferCryptoEngine] is a working
 * [com.torve.domain.transfer.TransferCryptoEngine] and that
 * envelopes seal-and-open round-trip via [SecretsTransferProtocol].
 *
 * Note: this test runs on the host JVM — Tink's `subtle.X25519` is a
 * pure Java implementation, so no Android device is required. The same
 * 32-byte little-endian wire format is used by both this engine and the
 * desktop `JvmTransferCryptoEngine`, which is what makes cross-platform
 * envelopes byte-for-byte compatible.
 */
class AndroidTransferCryptoEngineTest {

    @Test
    fun keypairGenProducesCorrectByteSizes() = runBlocking {
        val engine = AndroidTransferCryptoEngine()
        val pair = engine.generateEphemeralKeyPair()
        assertEquals("X25519 public key must be 32 bytes", 32, pair.publicKey.size)
        assertEquals("X25519 private key must be 32 bytes", 32, pair.privateKey.size)
    }

    @Test
    fun ecdhAgreesBetweenTwoIndependentlyGeneratedKeyPairs() = runBlocking {
        val engine = AndroidTransferCryptoEngine()
        val a = engine.generateEphemeralKeyPair()
        val b = engine.generateEphemeralKeyPair()
        val info = "torve-test-info".encodeToByteArray()
        val sharedFromA = engine.deriveSharedKey(a.privateKey, b.publicKey, info)
        val sharedFromB = engine.deriveSharedKey(b.privateKey, a.publicKey, info)
        assertEquals(
            "ECDH must agree from both sides",
            sharedFromA.toList(),
            sharedFromB.toList(),
        )
        assertEquals("HKDF-SHA256 must produce 32-byte AES key", 32, sharedFromA.size)
    }

    @Test
    fun aeadEncryptDecryptRoundTrips() = runBlocking {
        val engine = AndroidTransferCryptoEngine()
        val key = engine.secureRandom(32)
        val nonce = engine.secureRandom(12)
        val plaintext = "torve-secret-payload".encodeToByteArray()
        val aad = "v=1|x=test".encodeToByteArray()

        val ciphertext = engine.encryptAead(key, nonce, plaintext, aad)
        val recovered = engine.decryptAead(key, nonce, ciphertext, aad)
        assertNotNull(recovered)
        assertEquals(plaintext.toList(), recovered!!.toList())

        // Tamper with AAD — decrypt MUST fail.
        val tampered = engine.decryptAead(key, nonce, ciphertext, "v=1|x=other".encodeToByteArray())
        assertNull("AAD tamper must fail AEAD verification", tampered)
    }

    @Test
    fun protocolSealAndOpenRoundTripsOnAndroidEngineEndToEnd() = runBlocking {
        val engine = AndroidTransferCryptoEngine()
        val now = 1_700_000_000_000L
        val protocol = SecretsTransferProtocol(engine, nowMs = { now })

        val receiverKey = protocol.generateReceiverKeyPair()
        val expiresAt = now + 60_000L
        val secret = SecretRecord(
            category = SecretCategory.DEBRID,
            key = "DEBRID_API_KEY_REAL_DEBRID",
            value = "rd-token-hidden",
        )

        val envelope = protocol.seal(
            receiverPublicKey = receiverKey.publicKey,
            senderDeviceId = "test-device",
            senderDeviceName = "Test",
            categories = listOf(SecretCategory.DEBRID),
            secrets = listOf(secret),
            expiresAtEpochMs = expiresAt,
        )

        val opened = protocol.open(
            envelope = envelope,
            receiverPrivateKey = receiverKey.privateKey,
        )
        assertTrue("expected Success, got $opened", opened is TransferDecryptResult.Success)
        val success = opened as TransferDecryptResult.Success
        assertEquals(1, success.payload.secrets.size)
        assertEquals(secret, success.payload.secrets.single())
    }

    @Test
    fun openWithWrongReceiverKeyFailsAuthentication() = runBlocking {
        val engine = AndroidTransferCryptoEngine()
        val now = 1_700_000_000_000L
        val protocol = SecretsTransferProtocol(engine, nowMs = { now })

        val realReceiver = protocol.generateReceiverKeyPair()
        val attackerReceiver = protocol.generateReceiverKeyPair()
        val expiresAt = now + 60_000L

        val envelope = protocol.seal(
            receiverPublicKey = realReceiver.publicKey,
            senderDeviceId = "test-device",
            senderDeviceName = "Test",
            categories = listOf(SecretCategory.DEBRID),
            secrets = listOf(
                SecretRecord(SecretCategory.DEBRID, "DEBRID_API_KEY", "rd-secret"),
            ),
            expiresAtEpochMs = expiresAt,
        )

        // The attacker holds a different X25519 private key. They should
        // never be able to open an envelope sealed for the real receiver.
        val opened = protocol.open(
            envelope = envelope,
            receiverPrivateKey = attackerReceiver.privateKey,
        )
        assertTrue(
            "expected AuthenticationFailure, got $opened",
            opened is TransferDecryptResult.AuthenticationFailure,
        )
    }
}
