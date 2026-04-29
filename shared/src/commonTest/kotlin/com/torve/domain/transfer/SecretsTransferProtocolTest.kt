package com.torve.domain.transfer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

/**
 * Exercises the full envelope codec against a deterministic fake engine.
 * The fake's "encryption" is XOR with the derived key + a 16-byte HMAC-
 * style auth tag — enough to prove the protocol-layer guarantees
 * (round-trip, AAD binding, version mismatch, replay, expiry, tamper
 * rejection) without bringing platform crypto into commonTest.
 *
 * A real-crypto round-trip lives in desktopApp/src/test against the JVM
 * engine to confirm AES-256-GCM + X25519 + HKDF behave the same way.
 */
class SecretsTransferProtocolTest {

    private val futureExpiry = 10_000_000L
    private val testNow = 1_000_000L

    private fun proto(engine: TransferCryptoEngine = FakeTransferCryptoEngine()): SecretsTransferProtocol =
        SecretsTransferProtocol(engine = engine, nowMs = { testNow })

    private fun sampleSecrets(): List<SecretRecord> = listOf(
        SecretRecord(SecretCategory.DEBRID, "DEBRID_API_KEY_REAL_DEBRID", "rd_xyz"),
        SecretRecord(SecretCategory.PLEX_JELLYFIN, "PLEX_ACCESS_TOKEN", "plex_abc"),
    )

    @Test
    fun `round-trip succeeds and preserves payload`() = runTest {
        val engine = FakeTransferCryptoEngine()
        val protocol = proto(engine)
        val receiverKp = protocol.generateReceiverKeyPair()

        val envelope = protocol.seal(
            receiverPublicKey = receiverKp.publicKey,
            senderDeviceId = "desktop-abc",
            senderDeviceName = "Adam's MBP",
            categories = listOf(SecretCategory.DEBRID, SecretCategory.PLEX_JELLYFIN),
            secrets = sampleSecrets(),
            expiresAtEpochMs = futureExpiry,
        )
        val result = protocol.open(envelope, receiverKp.privateKey)
        val success = assertIs<TransferDecryptResult.Success>(result)
        assertEquals(2, success.payload.secrets.size)
        assertContentEquals(sampleSecrets(), success.payload.secrets)
        assertEquals("desktop-abc", envelope.senderDeviceId)
        assertEquals("Adam's MBP", success.payload.senderDeviceName)
    }

    @Test
    fun `expired envelope rejected before key agreement`() = runTest {
        val engine = FakeTransferCryptoEngine()
        val protocol = proto(engine)
        val receiverKp = protocol.generateReceiverKeyPair()
        val envelope = protocol.seal(
            receiverPublicKey = receiverKp.publicKey,
            senderDeviceId = "desktop-abc",
            senderDeviceName = null,
            categories = listOf(SecretCategory.DEBRID),
            secrets = sampleSecrets(),
            expiresAtEpochMs = futureExpiry,
        )
        // Move "now" past expiry by injecting a different protocol clock.
        val expired = SecretsTransferProtocol(engine = engine, nowMs = { futureExpiry + 1 })
            .open(envelope, receiverKp.privateKey)
        assertEquals(TransferDecryptResult.Expired, expired)
    }

    @Test
    fun `tampered ciphertext fails AEAD verify`() = runTest {
        val engine = FakeTransferCryptoEngine()
        val protocol = proto(engine)
        val receiverKp = protocol.generateReceiverKeyPair()
        val envelope = protocol.seal(
            receiverPublicKey = receiverKp.publicKey,
            senderDeviceId = "desktop-abc",
            senderDeviceName = null,
            categories = listOf(SecretCategory.DEBRID),
            secrets = sampleSecrets(),
            expiresAtEpochMs = futureExpiry,
        )
        // Flip one byte of ciphertext (decode → mutate → re-encode).
        val ct = Base64Url.decodeOrNull(envelope.ciphertext)!!.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tampered = envelope.copy(ciphertext = Base64Url.encode(ct))
        val result = protocol.open(tampered, receiverKp.privateKey)
        assertEquals(TransferDecryptResult.AuthenticationFailure, result)
    }

    @Test
    fun `tampered AAD field fails AEAD verify`() = runTest {
        val engine = FakeTransferCryptoEngine()
        val protocol = proto(engine)
        val receiverKp = protocol.generateReceiverKeyPair()
        val envelope = protocol.seal(
            receiverPublicKey = receiverKp.publicKey,
            senderDeviceId = "desktop-abc",
            senderDeviceName = null,
            categories = listOf(SecretCategory.DEBRID),
            secrets = sampleSecrets(),
            expiresAtEpochMs = futureExpiry,
        )
        // Mutate expiresAtEpochMs (it's part of AAD). Decryption MUST fail
        // even though the new value is still in the future.
        val tampered = envelope.copy(expiresAtEpochMs = envelope.expiresAtEpochMs - 1)
        val result = protocol.open(tampered, receiverKp.privateKey)
        assertEquals(TransferDecryptResult.AuthenticationFailure, result)
    }

    @Test
    fun `wrong receiver private key fails verification`() = runTest {
        val engine = FakeTransferCryptoEngine()
        val protocol = proto(engine)
        val realKp = protocol.generateReceiverKeyPair()
        val envelope = protocol.seal(
            receiverPublicKey = realKp.publicKey,
            senderDeviceId = "desktop-abc",
            senderDeviceName = null,
            categories = listOf(SecretCategory.DEBRID),
            secrets = sampleSecrets(),
            expiresAtEpochMs = futureExpiry,
        )
        val attackerKp = protocol.generateReceiverKeyPair()
        val result = protocol.open(envelope, attackerKp.privateKey)
        assertEquals(TransferDecryptResult.AuthenticationFailure, result)
    }

    @Test
    fun `unsupported envelope version rejected up front`() = runTest {
        val engine = FakeTransferCryptoEngine()
        val protocol = proto(engine)
        val receiverKp = protocol.generateReceiverKeyPair()
        val envelope = protocol.seal(
            receiverPublicKey = receiverKp.publicKey,
            senderDeviceId = "desktop-abc",
            senderDeviceName = null,
            categories = listOf(SecretCategory.DEBRID),
            secrets = sampleSecrets(),
            expiresAtEpochMs = futureExpiry,
        )
        val bumped = envelope.copy(version = 99)
        val result = protocol.open(bumped, receiverKp.privateKey)
        assertEquals(TransferDecryptResult.UnsupportedVersion(99), result)
    }

    @Test
    fun `replay rejected when nonce already consumed`() = runTest {
        val engine = FakeTransferCryptoEngine()
        val protocol = proto(engine)
        val receiverKp = protocol.generateReceiverKeyPair()
        val envelope = protocol.seal(
            receiverPublicKey = receiverKp.publicKey,
            senderDeviceId = "desktop-abc",
            senderDeviceName = null,
            categories = listOf(SecretCategory.DEBRID),
            secrets = sampleSecrets(),
            expiresAtEpochMs = futureExpiry,
        )
        val seen = mutableSetOf<String>()
        val first = protocol.open(envelope, receiverKp.privateKey, consumedNonceCheck = { it in seen })
        val payload = assertIs<TransferDecryptResult.Success>(first).payload
        seen += payload.transferNonce
        val second = protocol.open(envelope, receiverKp.privateKey, consumedNonceCheck = { it in seen })
        assertEquals(TransferDecryptResult.Replayed, second)
    }

    @Test
    fun `malformed base64 reports Malformed`() = runTest {
        val engine = FakeTransferCryptoEngine()
        val protocol = proto(engine)
        val receiverKp = protocol.generateReceiverKeyPair()
        val envelope = protocol.seal(
            receiverPublicKey = receiverKp.publicKey,
            senderDeviceId = "desktop-abc",
            senderDeviceName = null,
            categories = listOf(SecretCategory.DEBRID),
            secrets = sampleSecrets(),
            expiresAtEpochMs = futureExpiry,
        )
        val broken = envelope.copy(ciphertext = "not!base64!")
        val result = protocol.open(broken, receiverKp.privateKey)
        assertIs<TransferDecryptResult.Malformed>(result)
    }

    @Test
    fun `seal rejects empty secrets`() = runTest {
        val protocol = proto()
        val receiverKp = protocol.generateReceiverKeyPair()
        val ex = runCatching {
            protocol.seal(
                receiverPublicKey = receiverKp.publicKey,
                senderDeviceId = "x",
                senderDeviceName = null,
                categories = emptyList(),
                secrets = emptyList(),
                expiresAtEpochMs = futureExpiry,
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IAE, got $ex")
    }

    @Test
    fun `seal rejects already-expired envelope`() = runTest {
        val protocol = proto()
        val receiverKp = protocol.generateReceiverKeyPair()
        val ex = runCatching {
            protocol.seal(
                receiverPublicKey = receiverKp.publicKey,
                senderDeviceId = "x",
                senderDeviceName = null,
                categories = listOf(SecretCategory.DEBRID),
                secrets = sampleSecrets(),
                expiresAtEpochMs = testNow - 1,
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IAE, got $ex")
    }

    // ── Base64Url ────────────────────────────────────────────────

    @Test
    fun `base64url round-trips arbitrary bytes`() {
        val cases = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(0, 1, 2, 3, 4, 5, 6),
            ByteArray(64) { it.toByte() },
            ByteArray(255) { (255 - it).toByte() },
        )
        for (b in cases) {
            val encoded = Base64Url.encode(b)
            assertTrue(encoded.none { it == '+' || it == '/' || it == '=' }, "url-safe alphabet only")
            assertContentEquals(b, Base64Url.decodeOrNull(encoded), "round-trip mismatch")
        }
    }

    @Test
    fun `base64url decode rejects out-of-alphabet input`() {
        assertNull(Base64Url.decodeOrNull("abc!"))
        assertNull(Base64Url.decodeOrNull("space here"))
    }
}
