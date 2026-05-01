package com.torve.presentation.transfer

import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.transfer.DefaultConfigKeyAllowlist
import com.torve.domain.transfer.FakeTransferCryptoEngine
import com.torve.domain.transfer.SecretsTransferProtocol
import com.torve.domain.transfer.TransferReceiverHandshake
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the empty-receiver-code error path on the sender VM.
 *
 * The sender's `generateEnvelope()` is the moment the user finds out
 * their flow is wrong. The error message decides whether they say
 * "ok, I open Receive credentials on my TV" or "what's a session
 * string?" — so this test guards the new copy explicitly.
 */
class SecretsTransferSenderViewModelEmptyReceiverTest {

    @Test
    fun `generateEnvelope with empty receiver code surfaces the new error copy`() = runTest {
        val vm = makeSender()
        // Default state: at least one category is selected, but
        // receiverSessionString is empty.
        vm.generateEnvelope()
        val status = vm.state.value.status
        val err = assertIs<SenderStatus.Error>(status)
        assertEquals(TransferCopy.SEND_RECEIVER_REQUIRED_ERROR, err.message)
    }

    @Test
    fun `error copy mentions the other-device source so the user knows where to look`() = runTest {
        // Belt-and-braces alongside the TransferCopy unit test — the
        // VM is the surface that emits this string at runtime.
        val vm = makeSender()
        vm.generateEnvelope()
        val err = assertIs<SenderStatus.Error>(vm.state.value.status)
        assertEquals(true, err.message.contains("other device"))
        assertEquals(true, err.message.contains("receiver code"))
    }

    @Test
    fun `whitespace-only receiver code is treated as empty`() = runTest {
        val vm = makeSender()
        vm.updateReceiverSessionString("   \n  ")
        vm.generateEnvelope()
        val err = assertIs<SenderStatus.Error>(vm.state.value.status)
        // Decoder normalizes whitespace to empty → same error path.
        assertEquals(TransferCopy.SEND_RECEIVER_REQUIRED_ERROR, err.message)
    }

    // ── S4 invalid receiver code ─────────────────────────────────────

    @Test
    fun `wrong-prefix paste tells the user where to find the right code`() = runTest {
        val vm = makeSender()
        // Garbage that doesn't start with the Torve receive-code prefix.
        vm.updateReceiverSessionString("https://google.com/?q=hello")
        vm.generateEnvelope()
        val err = assertIs<SenderStatus.Error>(vm.state.value.status)
        assertEquals(TransferCopy.SEND_RECEIVER_NOT_TORVE_ERROR, err.message)
        // No crypto jargon — the user should never see "base64" / "JSON"
        // in the primary flow.
        assertTrue(!err.message.contains("base64", ignoreCase = true))
        assertTrue(!err.message.contains("JSON", ignoreCase = true))
    }

    @Test
    fun `corrupted Torve-shaped code lands on the unified corrupted error`() = runTest {
        val vm = makeSender()
        // Right prefix, wrong payload — simulates a truncated paste.
        vm.updateReceiverSessionString("torve://transfer/receive/!!!not-base64-url!!!")
        vm.generateEnvelope()
        val err = assertIs<SenderStatus.Error>(vm.state.value.status)
        assertEquals(TransferCopy.SEND_RECEIVER_CORRUPTED_ERROR, err.message)
        // Tells the user what to do next.
        assertTrue(err.message.contains("fresh"))
    }

    // ── S6 expired receiver code ─────────────────────────────────────

    @Test
    fun `expired handshake reports the expiry-specific error`() = runTest {
        val nowMs = 1_700_000_000_000L
        val vm = SecretsTransferSenderViewModel(
            protocol = SecretsTransferProtocol(engine = FakeTransferCryptoEngine()),
            secretStore = FakeSecretStore2(),
            deviceIdProvider = FakeDeviceIdProvider2(),
            prefsRepo = FakePrefs2(),
            configKeyAllowlist = DefaultConfigKeyAllowlist(),
            relayApi = null,
            accessTokenProvider = null,
            platform = "test",
            nowMs = { nowMs },
        )
        // Build a handshake whose expiry has already passed.
        val pubKey = ByteArray(32) { 1 }
        val expiredHandshake = TransferReceiverHandshake(
            sessionId = "test-session",
            receiverEphemeralPublicKey = com.torve.domain.transfer.Base64Url.encode(pubKey),
            expiresAtEpochMs = nowMs - 60_000L, // 1 minute in the past
        )
        val encoded = TransferSessionCodec.encode(expiredHandshake)
        vm.updateReceiverSessionString(encoded)
        vm.generateEnvelope()
        val err = assertIs<SenderStatus.Error>(vm.state.value.status)
        assertEquals(TransferCopy.SEND_RECEIVER_EXPIRED_ERROR, err.message)
        // Tells the user which device to act on.
        assertTrue(err.message.contains("receiving device"))
    }

    private fun makeSender(): SecretsTransferSenderViewModel {
        return SecretsTransferSenderViewModel(
            protocol = SecretsTransferProtocol(engine = FakeTransferCryptoEngine()),
            secretStore = FakeSecretStore2(),
            deviceIdProvider = FakeDeviceIdProvider2(),
            prefsRepo = FakePrefs2(),
            configKeyAllowlist = DefaultConfigKeyAllowlist(),
            relayApi = null,
            accessTokenProvider = null,
            platform = "test",
            nowMs = { 1_700_000_000_000L },
        )
    }
}

private class FakePrefs2 : PreferencesRepository {
    private val store = mutableMapOf<String, String>()
    override suspend fun getString(key: String): String? = store[key]
    override suspend fun setString(key: String, value: String) { store[key] = value }
    override suspend fun remove(key: String) { store.remove(key) }
}

private class FakeSecretStore2 : IntegrationSecretStore {
    private val backing = mutableMapOf<String, String>()
    private fun addr(key: IntegrationSecretKey, subKey: String?): String = "${key.name}|${subKey.orEmpty()}"
    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) { backing[addr(key, subKey)] = value }
    override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? = backing[addr(key, subKey)]
    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) { backing.remove(addr(key, subKey)) }
    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
    override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode = IntegrationStorageMode.DEVICE_ONLY
    override suspend fun clearAllSecrets() { backing.clear() }
}

private class FakeDeviceIdProvider2 : DeviceIdProvider {
    override fun getDeviceId(): String = "test-device"
    override fun getDeviceName(): String = "Test"
    override fun getDeviceType(): String = "test"
    override fun getPlatform(): String = "test"
}
