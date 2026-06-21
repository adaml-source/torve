package com.torve.desktop.transfer

import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.transfer.ConfigEntry
import com.torve.domain.transfer.DefaultConfigKeyAllowlist
import com.torve.domain.transfer.SealedSecretsEnvelope
import com.torve.domain.transfer.SecretCategory
import com.torve.domain.transfer.SecretsTransferProtocol
import com.torve.domain.transfer.TransferDecryptResult
import com.torve.domain.transfer.TransferReceiverHandshake
import com.torve.domain.transfer.Base64Url
import com.torve.desktop.security.JvmTransferCryptoEngine
import com.torve.presentation.transfer.SecretsTransferSenderViewModel
import com.torve.presentation.transfer.SenderStatus
import com.torve.presentation.transfer.TransferSecretCatalog
import com.torve.presentation.transfer.TransferSessionCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the sender VM end-to-end against the JVM crypto engine and
 * confirms it pulls Plex / Jellyfin URLs into [ConfigEntry]s when the
 * category is selected.
 *
 * Open the resulting envelope on the receiver side via the same
 * protocol object — verifies the sealed bytes round-trip and the
 * payload carries both the secrets AND companion config.
 */
class SenderCompanionConfigTest {

    private class FakeSecrets : IntegrationSecretStore {
        private val backing = mutableMapOf<String, String>()
        private fun addr(k: IntegrationSecretKey, sub: String?) = "${k.name}|${sub.orEmpty()}"
        fun seed(k: IntegrationSecretKey, value: String, sub: String? = null) {
            backing[addr(k, sub)] = value
        }
        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {
            backing[addr(key, subKey)] = value
        }
        override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
            backing[addr(key, subKey)]
        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {
            backing.remove(addr(key, subKey))
        }
        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) {}
        override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode =
            IntegrationStorageMode.DEVICE_ONLY
        override suspend fun clearAllSecrets() { backing.clear() }
    }

    private class FakePrefs : PreferencesRepository {
        val store = mutableMapOf<String, String>()
        override suspend fun getString(key: String): String? = store[key]
        override suspend fun setString(key: String, value: String) { store[key] = value }
        override suspend fun remove(key: String) { store.remove(key) }
    }

    private class StubDeviceIdProvider : DeviceIdProvider {
        override fun getDeviceId(): String = "test-device-id"
        override fun getDeviceName(): String = "Test Device"
    }

    private val nowFixed = 1_000_000L
    private val expiry = nowFixed + 600_000L

    @Test
    fun `sender includes Plex and Jellyfin URLs when PLEX_JELLYFIN selected`(): Unit = runBlocking {
        val secrets = FakeSecrets().apply {
            seed(IntegrationSecretKey.PLEX_ACCESS_TOKEN, "plex_real")
            seed(IntegrationSecretKey.JELLYFIN_API_KEY, "jelly_real")
        }
        val prefs = FakePrefs().apply {
            store[DefaultConfigKeyAllowlist.PLEX_SERVER_URL] = "https://plex.example"
            store[DefaultConfigKeyAllowlist.JELLYFIN_SERVER_URL] = "https://jelly.example"
        }
        val protocol = SecretsTransferProtocol(
            engine = JvmTransferCryptoEngine(),
            nowMs = { nowFixed },
        )
        val sender = SecretsTransferSenderViewModel(
            protocol = protocol,
            secretStore = secrets,
            deviceIdProvider = StubDeviceIdProvider(),
            prefsRepo = prefs,
            nowMs = { nowFixed },
        )

        // Scope down to PLEX_JELLYFIN so we don't depend on the catalog defaults
        SecretCategory.entries.filter { it != SecretCategory.PLEX_JELLYFIN }.forEach {
            sender.setCategoryEnabled(it, false)
        }
        sender.setCategoryEnabled(SecretCategory.PLEX_JELLYFIN, true)

        // Receiver handshake
        val receiverKp = protocol.generateReceiverKeyPair()
        val handshake = TransferReceiverHandshake(
            sessionId = "s",
            receiverEphemeralPublicKey = Base64Url.encode(receiverKp.publicKey),
            expiresAtEpochMs = expiry,
        )
        sender.updateReceiverSessionString(TransferSessionCodec.encode(handshake))

        sender.generateEnvelope()
        val ready = assertIs<SenderStatus.Ready>(sender.state.first { it.status is SenderStatus.Ready }.status)
        assertEquals(2, ready.secretCount)
        assertEquals(2, ready.configCount)
        assertTrue(ready.categoriesMissingCompanionConfig.isEmpty())

        // Decode the envelope on the receiver side and confirm payload contents.
        val envelope = Json { ignoreUnknownKeys = true }
            .decodeFromString(SealedSecretsEnvelope.serializer(), ready.envelopeJson)
        val opened = protocol.open(envelope, receiverKp.privateKey)
        val payload = assertIs<TransferDecryptResult.Success>(opened).payload
        val configKeys = payload.configEntries.map { it.key }.toSet()
        assertContentEquals(
            listOf(DefaultConfigKeyAllowlist.PLEX_SERVER_URL, DefaultConfigKeyAllowlist.JELLYFIN_SERVER_URL).sorted(),
            configKeys.sorted(),
        )
        assertEquals(
            "https://plex.example",
            payload.configEntries.first { it.key == DefaultConfigKeyAllowlist.PLEX_SERVER_URL }.value,
        )
    }

    @Test
    fun `sender flags missing companion config when token present but URL absent`(): Unit = runBlocking {
        val secrets = FakeSecrets().apply {
            seed(IntegrationSecretKey.PLEX_ACCESS_TOKEN, "plex_real")
        }
        val prefs = FakePrefs() // No URL configured
        val protocol = SecretsTransferProtocol(
            engine = JvmTransferCryptoEngine(),
            nowMs = { nowFixed },
        )
        val sender = SecretsTransferSenderViewModel(
            protocol = protocol,
            secretStore = secrets,
            deviceIdProvider = StubDeviceIdProvider(),
            prefsRepo = prefs,
            nowMs = { nowFixed },
        )
        SecretCategory.entries.filter { it != SecretCategory.PLEX_JELLYFIN }.forEach {
            sender.setCategoryEnabled(it, false)
        }
        sender.setCategoryEnabled(SecretCategory.PLEX_JELLYFIN, true)

        val receiverKp = protocol.generateReceiverKeyPair()
        val handshake = TransferReceiverHandshake(
            sessionId = "s",
            receiverEphemeralPublicKey = Base64Url.encode(receiverKp.publicKey),
            expiresAtEpochMs = expiry,
        )
        sender.updateReceiverSessionString(TransferSessionCodec.encode(handshake))

        sender.generateEnvelope()
        val ready = assertIs<SenderStatus.Ready>(sender.state.first { it.status is SenderStatus.Ready }.status)
        assertEquals(1, ready.secretCount)
        assertEquals(0, ready.configCount)
        assertEquals(listOf(SecretCategory.PLEX_JELLYFIN), ready.categoriesMissingCompanionConfig)
    }

    @Test
    fun `sender omits companion config for unselected categories`(): Unit = runBlocking {
        val secrets = FakeSecrets().apply {
            seed(IntegrationSecretKey.PLEX_ACCESS_TOKEN, "plex_real")
            seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "rd_real")
        }
        val prefs = FakePrefs().apply {
            store[DefaultConfigKeyAllowlist.PLEX_SERVER_URL] = "https://plex.example"
        }
        val protocol = SecretsTransferProtocol(
            engine = JvmTransferCryptoEngine(),
            nowMs = { nowFixed },
        )
        val sender = SecretsTransferSenderViewModel(
            protocol = protocol,
            secretStore = secrets,
            deviceIdProvider = StubDeviceIdProvider(),
            prefsRepo = prefs,
            nowMs = { nowFixed },
        )
        SecretCategory.entries.filter { it != SecretCategory.DEBRID }.forEach {
            sender.setCategoryEnabled(it, false)
        }
        sender.setCategoryEnabled(SecretCategory.DEBRID, true)

        val receiverKp = protocol.generateReceiverKeyPair()
        val handshake = TransferReceiverHandshake(
            sessionId = "s",
            receiverEphemeralPublicKey = Base64Url.encode(receiverKp.publicKey),
            expiresAtEpochMs = expiry,
        )
        sender.updateReceiverSessionString(TransferSessionCodec.encode(handshake))

        sender.generateEnvelope()
        val ready = assertIs<SenderStatus.Ready>(sender.state.first { it.status is SenderStatus.Ready }.status)
        // Plex URL must NOT travel when PLEX_JELLYFIN is not selected.
        assertEquals(0, ready.configCount)
        val envelope = Json { ignoreUnknownKeys = true }
            .decodeFromString(SealedSecretsEnvelope.serializer(), ready.envelopeJson)
        val payload = assertIs<TransferDecryptResult.Success>(
            protocol.open(envelope, receiverKp.privateKey),
        ).payload
        assertTrue(payload.configEntries.isEmpty(), "config entries must be empty for non-PJ categories")
        assertNull(
            payload.configEntries.firstOrNull { it.key == DefaultConfigKeyAllowlist.PLEX_SERVER_URL },
        )
    }
}
