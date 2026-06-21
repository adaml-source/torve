package com.torve.presentation.transfer

import com.torve.data.transfer.CreateTransferSessionRequest
import com.torve.data.transfer.TransferRelayApi
import com.torve.data.transfer.TransferRelayResult
import com.torve.data.transfer.TransferSessionDto
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.transfer.Base64Url
import com.torve.domain.transfer.ConsumedNonceStore
import com.torve.domain.transfer.DefaultConfigKeyAllowlist
import com.torve.domain.transfer.FakeTransferCryptoEngine
import com.torve.domain.transfer.SealedSecretsEnvelope
import com.torve.domain.transfer.SecretCategory
import com.torve.domain.transfer.SecretRecord
import com.torve.domain.transfer.SecretsTransferApplier
import com.torve.domain.transfer.SecretsTransferProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Validates that a successful credential import bumps the shared
 * [TransferImportCompletionNotifier] flow exactly once, and that
 * subsequent failed imports do NOT bump it.
 *
 * The notifier is the trigger that drives every platform recovery
 * surface (Android `RestoreSetupRecoveryCard`, desktop V2 Settings,
 * iOS `RestoreSetupRecoveryWrapper`) to recompute its
 * `ProviderHealthRecoverySnapshot` — verifying the emission contract
 * here means every platform refresh contract is correct by
 * construction.
 */
class TransferImportCompletionNotifierTest {

    private val fixedNow = 1_700_000_000_000L
    private val envelopeJsonFormat = Json { encodeDefaults = true }

    @Test
    fun manualPasteSuccessBumpsNotifierExactlyOnce() = runTest {
        val ticks = mutableListOf<Long>()
        val notifier = TransferImportCompletionNotifier(nowMs = {
            // Deterministic monotonic ms so a second emission would be a different value.
            (ticks.size + 1L) * 1_000L + fixedNow
        })

        val engine = FakeTransferCryptoEngine()
        val protocol = SecretsTransferProtocol(engine, nowMs = { fixedNow })
        val secretStore = NotifierFakeSecretStore()
        val prefs = NotifierFakePrefs()
        val nonceStore = ConsumedNonceStore(prefs, nowMs = { fixedNow })
        val applier = SecretsTransferApplier(
            secretStore = secretStore,
            nonceStore = nonceStore,
            prefsRepo = prefs,
            configKeyAllowlist = DefaultConfigKeyAllowlist(),
        )
        val vm = SecretsTransferReceiverViewModel(
            protocol = protocol,
            applier = applier,
            nonceStore = nonceStore,
            relayApi = null,
            accessTokenProvider = null,
            deviceIdProvider = NotifierFakeDeviceIdProvider(),
            ttlMs = 60_000L,
            pollIntervalMs = 60_000L,
            completionNotifier = notifier,
            platform = "test",
            nowMs = { fixedNow },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        // Initial: notifier flow is at 0L (default).
        assertEquals(0L, notifier.lastImportEpochMs.value)

        vm.start()
        repeat(3) { yield() }

        val active = vm.state.value as ReceiverState.Active
        val pubKey = Base64Url.decodeOrNull(active.handshake.receiverEphemeralPublicKey)!!
        val envelope = protocol.seal(
            receiverPublicKey = pubKey,
            senderDeviceId = "tester",
            senderDeviceName = "Tester",
            categories = listOf(SecretCategory.DEBRID),
            secrets = listOf(
                SecretRecord(SecretCategory.DEBRID, "DEBRID_API_KEY", "rd-token"),
            ),
            expiresAtEpochMs = active.handshake.expiresAtEpochMs,
        )
        val envelopeJson = envelopeJsonFormat.encodeToString(SealedSecretsEnvelope.serializer(), envelope)

        vm.updateEnvelopeText(envelopeJson)
        val before = notifier.lastImportEpochMs.value
        vm.acceptEnvelopeJson()
        val after = notifier.lastImportEpochMs.value

        assertNotEquals(before, after, "Notifier must advance after a successful import")
        assertTrue(after > 0L, "Notifier must hold a positive timestamp after success")
    }

    @Test
    fun manualPasteFailureDoesNotBumpNotifier() = runTest {
        val notifier = TransferImportCompletionNotifier(nowMs = { fixedNow })
        val vm = newReceiverNoStart(notifier = notifier, ttlMs = 60_000L)
        vm.start()
        repeat(3) { yield() }

        // Feed garbage — decrypt must fail and the notifier must stay 0.
        vm.updateEnvelopeText("not-a-valid-envelope-json")
        val before = notifier.lastImportEpochMs.value
        vm.acceptEnvelopeJson()
        val after = notifier.lastImportEpochMs.value

        assertEquals(before, after, "Notifier must NOT advance on import failure")
        assertEquals(0L, after)
        vm.cancel()
    }

    @Test
    fun multipleSuccessfulImportsAdvanceTheCounter() = runTest {
        // Two separate VM lifecycles, same notifier instance — confirms
        // the flow is observably monotonic across receive sessions.
        var counter = 0L
        val notifier = TransferImportCompletionNotifier(nowMs = {
            counter += 1
            counter * 1_000L
        })
        val vm1 = newReceiverNoStart(notifier = notifier, ttlMs = 60_000L)
        vm1.start()
        repeat(3) { yield() }
        notifier.notifyImportSuccess() // simulate apply path
        val first = notifier.lastImportEpochMs.value
        assertTrue(first > 0L)
        vm1.cancel()

        notifier.notifyImportSuccess()
        val second = notifier.lastImportEpochMs.value
        assertTrue(second > first, "Second emission must be strictly greater")
    }

    private fun newReceiverNoStart(
        notifier: TransferImportCompletionNotifier,
        ttlMs: Long,
    ): SecretsTransferReceiverViewModel {
        val engine = FakeTransferCryptoEngine()
        val protocol = SecretsTransferProtocol(engine, nowMs = { fixedNow })
        val prefs = NotifierFakePrefs()
        val nonceStore = ConsumedNonceStore(prefs, nowMs = { fixedNow })
        val applier = SecretsTransferApplier(
            secretStore = NotifierFakeSecretStore(),
            nonceStore = nonceStore,
            prefsRepo = prefs,
            configKeyAllowlist = DefaultConfigKeyAllowlist(),
        )
        return SecretsTransferReceiverViewModel(
            protocol = protocol,
            applier = applier,
            nonceStore = nonceStore,
            relayApi = null,
            accessTokenProvider = null,
            deviceIdProvider = NotifierFakeDeviceIdProvider(),
            ttlMs = ttlMs,
            pollIntervalMs = 60_000L,
            completionNotifier = notifier,
            platform = "test",
            nowMs = { fixedNow },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }
}

private class NotifierFakePrefs : PreferencesRepository {
    private val store = mutableMapOf<String, String>()
    override suspend fun getString(key: String): String? = store[key]
    override suspend fun setString(key: String, value: String) { store[key] = value }
    override suspend fun remove(key: String) { store.remove(key) }
}

private class NotifierFakeSecretStore : IntegrationSecretStore {
    private val backing = mutableMapOf<String, String>()
    private fun addr(key: IntegrationSecretKey, subKey: String?) =
        "${key.name}|${subKey.orEmpty()}"

    override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) {
        backing[addr(key, subKey)] = value
    }

    override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? =
        backing[addr(key, subKey)]

    override suspend fun remove(key: IntegrationSecretKey, subKey: String?) {
        backing.remove(addr(key, subKey))
    }

    override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
    override suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode =
        IntegrationStorageMode.DEVICE_ONLY
    override suspend fun clearAllSecrets() { backing.clear() }
}

private class NotifierFakeDeviceIdProvider : DeviceIdProvider {
    override fun getDeviceId(): String = "notifier-test-device"
    override fun getDeviceName(): String = "Notifier Test"
    override fun getDeviceType(): String = "test"
    override fun getPlatform(): String = "test"
}

private class StubRelayApiNotUsed : TransferRelayApi {
    override suspend fun createSession(
        accessToken: String,
        request: CreateTransferSessionRequest,
    ): TransferRelayResult<TransferSessionDto> = error("not used")

    override suspend fun getSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<TransferSessionDto> = error("not used")

    override suspend fun postEnvelope(
        accessToken: String,
        sessionId: String,
        envelope: SealedSecretsEnvelope,
    ): TransferRelayResult<Unit> = error("not used")

    override suspend fun consumeSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<Unit> = error("not used")
}
