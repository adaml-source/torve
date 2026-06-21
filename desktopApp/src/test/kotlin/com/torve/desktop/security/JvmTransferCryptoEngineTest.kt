package com.torve.desktop.security

import com.torve.domain.transfer.SecretCategory
import com.torve.domain.transfer.SecretRecord
import com.torve.domain.transfer.SecretsTransferProtocol
import com.torve.domain.transfer.TransferDecryptResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Real-crypto round-trip against the JVM engine. Confirms that
 * X25519 + HKDF-SHA256 + AES-256-GCM glue produces an envelope a
 * matching receiver can open and rejects the obvious negative cases.
 */
class JvmTransferCryptoEngineTest {

    private val now = 1_000_000L
    private val expiry = now + 600_000L

    private fun proto(): SecretsTransferProtocol = SecretsTransferProtocol(
        engine = JvmTransferCryptoEngine(),
        nowMs = { now },
    )

    private val sample = listOf(
        SecretRecord(SecretCategory.DEBRID, "DEBRID_API_KEY_REAL_DEBRID", "rd_xyz"),
        SecretRecord(SecretCategory.PLEX_JELLYFIN, "PLEX_ACCESS_TOKEN", "plex_abc"),
        SecretRecord(SecretCategory.PANDA, "PANDA_INDEXER_API_KEY", "key", subKey = "scenenzbs|https://x"),
    )

    @Test
    fun `real crypto round-trip`() = runBlocking {
        val protocol = proto()
        val receiverKp = protocol.generateReceiverKeyPair()
        val envelope = protocol.seal(
            receiverPublicKey = receiverKp.publicKey,
            senderDeviceId = "desktop-jvm",
            senderDeviceName = "JVM test",
            categories = listOf(SecretCategory.DEBRID, SecretCategory.PLEX_JELLYFIN, SecretCategory.PANDA),
            secrets = sample,
            expiresAtEpochMs = expiry,
        )
        val result = protocol.open(envelope, receiverKp.privateKey)
        val payload = assertIs<TransferDecryptResult.Success>(result).payload
        assertContentEquals(sample, payload.secrets)
        assertEquals("JVM test", payload.senderDeviceName)
    }

    @Test
    fun `real crypto rejects wrong receiver`() = runBlocking {
        val protocol = proto()
        val realReceiver = protocol.generateReceiverKeyPair()
        val envelope = protocol.seal(
            receiverPublicKey = realReceiver.publicKey,
            senderDeviceId = "desktop-jvm",
            senderDeviceName = null,
            categories = listOf(SecretCategory.DEBRID),
            secrets = sample.take(1),
            expiresAtEpochMs = expiry,
        )
        val attacker = protocol.generateReceiverKeyPair()
        val result = protocol.open(envelope, attacker.privateKey)
        assertEquals(TransferDecryptResult.AuthenticationFailure, result)
    }
}
