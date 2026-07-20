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
import com.torve.domain.transfer.ConfigKeyAllowlist
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for the cross-platform [SecretsTransferReceiverViewModel].
 *
 * Exercises the state machine via the fake crypto engine + a stub relay
 * API; never starts real network or coroutine scheduling. The manual-
 * paste import path is also validated end-to-end (fake-engine seal →
 * receiver VM open → applier writes to fake stores).
 */
class SecretsTransferReceiverViewModelSharedTest {

    private val fixedNow = 1_700_000_000_000L
    private val envelopeJsonFormat = Json { encodeDefaults = true }

    @Test
    fun startWithRegisterSuccessEmbedsRelaySessionIdAndShowsRegistered() = runTest {
        val api = StubRelayApi(
            createResult = TransferRelayResult.Success(dummyDto("relay-A")),
        )
        val vm = newReceiver(api = api, token = "tok")
        vm.start()
        // Let the registration coroutine settle on Dispatchers.Unconfined.
        repeat(5) { yield() }

        val active = assertIs<ReceiverState.Active>(vm.state.value)
        val status = assertIs<RelayStatus.Registered>(active.relayStatus)
        assertEquals("relay-A", status.sessionId)
        assertEquals("relay-A", active.handshake.relaySessionId)
        // Session string must be re-encoded so the sender sees the relay id.
        assertTrue(active.sessionString.startsWith(TransferSessionCodec.QR_PREFIX))
        assertEquals(1, api.createCalls)
        vm.cancel()
    }

    @Test
    fun startWithUnavailableKeepsManualPasteAndStopsAfterOneCall() = runTest {
        val api = StubRelayApi(createResult = TransferRelayResult.Unavailable)
        val vm = newReceiver(api = api, token = "tok")
        vm.start()
        repeat(5) { yield() }

        val active = assertIs<ReceiverState.Active>(vm.state.value)
        val status = assertIs<RelayStatus.Unavailable>(active.relayStatus)
        assertTrue(
            status.reason.contains("not deployed", ignoreCase = true),
            status.reason,
        )
        // No relay session id was issued → handshake stays without one.
        assertNull(active.handshake.relaySessionId)
        // Even on retry, the registration call doesn't loop.
        repeat(5) { yield() }
        assertEquals(1, api.createCalls)
        vm.cancel()
    }

    @Test
    fun startWithoutRelayApiReportsNotConfigured() = runTest {
        val vm = newReceiver(api = null, token = null)
        vm.start()
        repeat(2) { yield() }

        val active = assertIs<ReceiverState.Active>(vm.state.value)
        assertEquals(RelayStatus.NotConfigured, active.relayStatus)
        vm.cancel()
    }

    @Test
    fun signedOutWithRelayApiPresentReportsUnavailable() = runTest {
        val api = StubRelayApi(createResult = TransferRelayResult.Success(dummyDto("never-used")))
        val vm = newReceiver(api = api, token = null) // accessTokenProvider returns null
        vm.start()
        repeat(5) { yield() }

        val active = assertIs<ReceiverState.Active>(vm.state.value)
        val status = assertIs<RelayStatus.Unavailable>(active.relayStatus)
        assertTrue(status.reason.contains("Sign in", ignoreCase = true), status.reason)
        // We never reached createSession.
        assertEquals(0, api.createCalls)
        vm.cancel()
    }

    @Test
    fun cancelClearsStateRegardlessOfRelayState() = runTest {
        val api = StubRelayApi(createResult = TransferRelayResult.Success(dummyDto("R")))
        val vm = newReceiver(api = api, token = "tok")
        vm.start()
        repeat(5) { yield() }
        assertIs<ReceiverState.Active>(vm.state.value)

        vm.cancel()
        assertEquals(ReceiverState.Idle, vm.state.value)
    }

    @Test
    fun manualPasteImportSucceedsEvenWhenRelayIsUnavailable() = runTest {
        // Sender + receiver share the same fake engine so deriveSharedKey
        // can reconstruct ECDH symmetry from their counter-tracked keys.
        val engine = FakeTransferCryptoEngine()
        val protocol = SecretsTransferProtocol(engine, nowMs = { fixedNow })
        val secretStore = FakeSecretStore()
        val prefs = FakePrefs()
        val nonceStore = ConsumedNonceStore(prefs, nowMs = { fixedNow })
        val applier = SecretsTransferApplier(
            secretStore = secretStore,
            nonceStore = nonceStore,
            prefsRepo = prefs,
            configKeyAllowlist = DefaultConfigKeyAllowlist(),
        )
        val api = StubRelayApi(createResult = TransferRelayResult.Unavailable)
        val vm = SecretsTransferReceiverViewModel(
            protocol = protocol,
            applier = applier,
            nonceStore = nonceStore,
            relayApi = api,
            accessTokenProvider = { "tok" },
            deviceIdProvider = FakeDeviceIdProvider(),
            ttlMs = 60_000L,
            pollIntervalMs = 60_000L,
            nowMs = { fixedNow },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        vm.start()
        repeat(5) { yield() }

        // Relay said no — paste fallback must remain primary.
        val active = assertIs<ReceiverState.Active>(vm.state.value)
        assertIs<RelayStatus.Unavailable>(active.relayStatus)

        // Build a sealed envelope with the same fake engine driving the
        // sender side. We seal directly against the receiver's pubkey.
        val pubKey = com.torve.domain.transfer.Base64Url.decodeOrNull(
            active.handshake.receiverEphemeralPublicKey,
        )!!
        val envelope = protocol.seal(
            receiverPublicKey = pubKey,
            senderDeviceId = "tester",
            senderDeviceName = "Tester",
            categories = listOf(SecretCategory.DEBRID),
            secrets = listOf(
                SecretRecord(SecretCategory.DEBRID, "DEBRID_API_KEY", "rd-paste-token"),
            ),
            expiresAtEpochMs = active.handshake.expiresAtEpochMs,
        )
        val envelopeJson = envelopeJsonFormat.encodeToString(SealedSecretsEnvelope.serializer(), envelope)

        vm.updateEnvelopeText(envelopeJson)
        val outcome = vm.acceptEnvelopeJson()
        assertIs<TransferImportResult.Success>(outcome)
        assertEquals(
            "rd-paste-token",
            secretStore.get(IntegrationSecretKey.DEBRID_API_KEY),
        )
        assertIs<ReceiverState.Imported>(vm.state.value)
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun newReceiver(
        api: TransferRelayApi?,
        token: String?,
        ttlMs: Long = 60_000L,
    ): SecretsTransferReceiverViewModel {
        val engine = FakeTransferCryptoEngine()
        val protocol = SecretsTransferProtocol(engine, nowMs = { fixedNow })
        val prefs = FakePrefs()
        val nonceStore = ConsumedNonceStore(prefs, nowMs = { fixedNow })
        val applier = SecretsTransferApplier(
            secretStore = FakeSecretStore(),
            nonceStore = nonceStore,
            prefsRepo = prefs,
            configKeyAllowlist = DefaultConfigKeyAllowlist(),
        )
        return SecretsTransferReceiverViewModel(
            protocol = protocol,
            applier = applier,
            nonceStore = nonceStore,
            relayApi = api,
            accessTokenProvider = { token },
            deviceIdProvider = FakeDeviceIdProvider(),
            ttlMs = ttlMs,
            // Long poll interval so the test never observes a poll round-trip.
            pollIntervalMs = 60_000L,
            nowMs = { fixedNow },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }

    private fun dummyDto(id: String): TransferSessionDto = TransferSessionDto(
        sessionId = id,
        expiresAtEpochMs = fixedNow + 60_000L,
        state = "pending",
        envelope = null,
    )
}

// ── Local fakes (duplicated from SecretsTransferApplierTest where they
// are private; small enough that a copy is cheaper than hoisting). ──

private class FakePrefs : PreferencesRepository {
    private val store = mutableMapOf<String, String>()
    override suspend fun getString(key: String): String? = store[key]
    override suspend fun setString(key: String, value: String) {
        store[key] = value
    }
    override suspend fun remove(key: String) {
        store.remove(key)
    }
}

private class FakeSecretStore : IntegrationSecretStore {
    private val backing = mutableMapOf<String, String>()
    private fun addr(key: IntegrationSecretKey, subKey: String?): String =
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

    override suspend fun clearAllSecrets() {
        backing.clear()
    }
}

private class FakeDeviceIdProvider : DeviceIdProvider {
    override fun getDeviceId(): String = "shared-test-device"
    override fun getDeviceName(): String = "Shared Test"
    override fun getDeviceType(): String = "test"
    override fun getPlatform(): String = "test"
}

private class StubRelayApi(
    val createResult: TransferRelayResult<TransferSessionDto> =
        TransferRelayResult.NetworkError("not stubbed"),
    val postResult: TransferRelayResult<Unit> =
        TransferRelayResult.NetworkError("not stubbed"),
    val getResult: TransferRelayResult<TransferSessionDto> =
        TransferRelayResult.Success(
            TransferSessionDto(
                sessionId = "x",
                expiresAtEpochMs = 0L,
                state = "pending",
                envelope = null,
            )
        ),
    val consumeResult: TransferRelayResult<Unit> = TransferRelayResult.Success(Unit),
) : TransferRelayApi {
    var createCalls: Int = 0
    var postCalls: Int = 0
    var getCalls: Int = 0
    var consumeCalls: Int = 0

    override suspend fun createSession(
        accessToken: String,
        request: CreateTransferSessionRequest,
    ): TransferRelayResult<TransferSessionDto> {
        createCalls += 1
        return createResult
    }

    override suspend fun getSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<TransferSessionDto> {
        getCalls += 1
        return getResult
    }

    override suspend fun postEnvelope(
        accessToken: String,
        sessionId: String,
        envelope: SealedSecretsEnvelope,
    ): TransferRelayResult<Unit> {
        postCalls += 1
        return postResult
    }

    override suspend fun consumeSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<Unit> {
        consumeCalls += 1
        return consumeResult
    }
}
