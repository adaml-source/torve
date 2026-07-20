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
import com.torve.domain.telemetry.TelemetryEmitter
import com.torve.domain.telemetry.TransferTelemetryEvents
import com.torve.domain.telemetry.TransferTelemetryKeys
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Asserts that **no** transfer-telemetry emission ever carries a
 * credential value, envelope JSON, session string, public key, access
 * token, or other sensitive fragment in its event name or attribute
 * values.
 *
 * Strategy: drive every emission path the receiver + sender VMs can
 * fire (relay-unavailable, manual-paste success, manual-paste decrypt
 * failure, send delivered, send failed). Capture every emission via a
 * spy [TelemetryEmitter] and scan attributes against a denylist of
 * shapes that would indicate a leak.
 */
class TransferTelemetryRedactionTest {

    private val fixedNow = 1_700_000_000_000L
    private val envelopeJsonFormat = Json { encodeDefaults = true }

    @Test
    fun receiverEmitsForRelayUnavailableWithoutLeakingTokensOrSessions() = runTest {
        val spy = SpyTelemetry()
        val tracker = TransferAttemptTracker(nowMs = { fixedNow })
        val vm = newReceiver(
            api = RedactionStubRelayApi(createResult = TransferRelayResult.Unavailable),
            token = "super-secret-token-DO-NOT-LEAK",
            telemetry = spy,
            tracker = tracker,
        )
        vm.start()
        repeat(5) { yield() }

        val unavailable = spy.findOne(TransferTelemetryEvents.RELAY_UNAVAILABLE)
        assertEquals("relay_not_deployed", unavailable[TransferTelemetryKeys.ERROR_CATEGORY])
        assertEquals("receiver", unavailable[TransferTelemetryKeys.ROLE])
        assertEquals("test", unavailable[TransferTelemetryKeys.PLATFORM])
        spy.assertNoLeaks(extra = listOf("super-secret-token-DO-NOT-LEAK"))
        vm.cancel()
    }

    @Test
    fun receiverEmitsImportSuccessForManualPasteWithBucketedCounts() = runTest {
        val spy = SpyTelemetry()
        val tracker = TransferAttemptTracker(nowMs = { fixedNow })
        val engine = FakeTransferCryptoEngine()
        val protocol = SecretsTransferProtocol(engine, nowMs = { fixedNow })
        val secretStore = RedactionFakeSecretStore()
        val prefs = RedactionFakePrefs()
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
            deviceIdProvider = RedactionFakeDeviceIdProvider(),
            ttlMs = 60_000L,
            pollIntervalMs = 60_000L,
            telemetry = spy,
            attemptTracker = tracker,
            platform = "test",
            nowMs = { fixedNow },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        vm.start()
        repeat(3) { yield() }

        val active = vm.state.value as ReceiverState.Active
        val pubKey = com.torve.domain.transfer.Base64Url
            .decodeOrNull(active.handshake.receiverEphemeralPublicKey)!!
        val envelope = protocol.seal(
            receiverPublicKey = pubKey,
            senderDeviceId = "tester",
            senderDeviceName = "Tester",
            categories = listOf(SecretCategory.DEBRID),
            secrets = listOf(
                SecretRecord(
                    SecretCategory.DEBRID,
                    "DEBRID_API_KEY",
                    "ZZZ-CREDENTIAL-VALUE-NEVER-IN-TELEMETRY-ZZZ",
                ),
            ),
            expiresAtEpochMs = active.handshake.expiresAtEpochMs,
        )
        val envelopeJson = envelopeJsonFormat.encodeToString(SealedSecretsEnvelope.serializer(), envelope)
        vm.updateEnvelopeText(envelopeJson)
        vm.acceptEnvelopeJson()

        val pasted = spy.findOne(TransferTelemetryEvents.MANUAL_PASTE_USED)
        assertEquals("applied", pasted[TransferTelemetryKeys.STATE])
        assertEquals("1_3", pasted[TransferTelemetryKeys.SECRET_COUNT])

        val success = spy.findOne(TransferTelemetryEvents.IMPORT_SUCCESS)
        assertEquals("applied", success[TransferTelemetryKeys.STATE])

        spy.assertNoLeaks(
            extra = listOf(
                "ZZZ-CREDENTIAL-VALUE-NEVER-IN-TELEMETRY-ZZZ",
                envelopeJson,
                envelope.senderEphemeralPublicKey,
                envelope.aeadNonce,
                envelope.ciphertext,
                active.sessionString,
                active.handshake.receiverEphemeralPublicKey,
            )
        )
    }

    @Test
    fun senderEmitsDeliveredWithBucketsAndNoEnvelopeBytes() = runTest {
        val spy = SpyTelemetry()
        val tracker = TransferAttemptTracker(nowMs = { fixedNow })
        val (sender, receiver, _) = senderHookedToReceiver(
            createResult = TransferRelayResult.Success(dummyDto("R")),
            postResult = TransferRelayResult.Success(Unit),
            telemetry = spy,
            tracker = tracker,
        )
        sender.generateEnvelope()
        repeat(5) { yield() }

        val delivered = spy.findOne(TransferTelemetryEvents.SEND_DELIVERED)
        assertEquals("sender", delivered[TransferTelemetryKeys.ROLE])
        assertEquals("delivered", delivered[TransferTelemetryKeys.STATE])

        val readyStatus = sender.state.value.status as SenderStatus.Ready
        spy.assertNoLeaks(
            extra = listOf(
                readyStatus.envelopeJson,
                "rd-token-not-in-telemetry",
            )
        )
        receiver.cancel()
    }

    @Test
    fun senderEmitsSendFailedWithMappedErrorCategory() = runTest {
        val spy = SpyTelemetry()
        val tracker = TransferAttemptTracker(nowMs = { fixedNow })
        val (sender, receiver, _) = senderHookedToReceiver(
            createResult = TransferRelayResult.Success(dummyDto("R-NF")),
            postResult = TransferRelayResult.NotFound,
            telemetry = spy,
            tracker = tracker,
        )
        sender.generateEnvelope()
        repeat(5) { yield() }

        val failed = spy.findOne(TransferTelemetryEvents.SEND_FAILED)
        assertEquals("relay_not_found", failed[TransferTelemetryKeys.ERROR_CATEGORY])
        spy.assertNoLeaks()
        receiver.cancel()
    }

    @Test
    fun trackerCarriesLatestAttemptForDiagnostics() = runTest {
        val spy = SpyTelemetry()
        val tracker = TransferAttemptTracker(nowMs = { fixedNow })
        val (sender, receiver, _) = senderHookedToReceiver(
            createResult = TransferRelayResult.Success(dummyDto("R")),
            postResult = TransferRelayResult.Success(Unit),
            telemetry = spy,
            tracker = tracker,
        )
        sender.generateEnvelope()
        repeat(5) { yield() }

        // Receiver registered first, sender then delivered → the most recent
        // attempt the diagnostics screen should show is "DELIVERED" / SENDER.
        val last = tracker.last.value
        assertNotNull(last)
        assertEquals(AttemptRole.SENDER, last.role)
        assertEquals(AttemptOutcome.DELIVERED, last.outcome)
        receiver.cancel()
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun newReceiver(
        api: TransferRelayApi?,
        token: String?,
        telemetry: TelemetryEmitter,
        tracker: TransferAttemptTracker,
    ): SecretsTransferReceiverViewModel {
        val engine = FakeTransferCryptoEngine()
        val protocol = SecretsTransferProtocol(engine, nowMs = { fixedNow })
        val prefs = RedactionFakePrefs()
        val nonceStore = ConsumedNonceStore(prefs, nowMs = { fixedNow })
        val applier = SecretsTransferApplier(
            secretStore = RedactionFakeSecretStore(),
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
            deviceIdProvider = RedactionFakeDeviceIdProvider(),
            ttlMs = 60_000L,
            pollIntervalMs = 60_000L,
            telemetry = telemetry,
            attemptTracker = tracker,
            platform = "test",
            nowMs = { fixedNow },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }

    private suspend fun senderHookedToReceiver(
        createResult: TransferRelayResult<TransferSessionDto>,
        postResult: TransferRelayResult<Unit>,
        telemetry: TelemetryEmitter,
        tracker: TransferAttemptTracker,
    ): Triple<SecretsTransferSenderViewModel, SecretsTransferReceiverViewModel, RedactionStubRelayApi> {
        val engine = FakeTransferCryptoEngine()
        val protocol = SecretsTransferProtocol(engine, nowMs = { fixedNow })
        val sharedApi = RedactionStubRelayApi(createResult = createResult, postResult = postResult)
        val secretStore = RedactionFakeSecretStore()
        secretStore.seed(IntegrationSecretKey.DEBRID_API_KEY, "rd-token-not-in-telemetry")
        val prefs = RedactionFakePrefs()
        val nonceStore = ConsumedNonceStore(prefs, nowMs = { fixedNow })
        val applier = SecretsTransferApplier(
            secretStore = secretStore,
            nonceStore = nonceStore,
            prefsRepo = prefs,
            configKeyAllowlist = DefaultConfigKeyAllowlist(),
        )
        val receiver = SecretsTransferReceiverViewModel(
            protocol = protocol,
            applier = applier,
            nonceStore = nonceStore,
            relayApi = sharedApi,
            accessTokenProvider = { "rcv-tok" },
            deviceIdProvider = RedactionFakeDeviceIdProvider(),
            ttlMs = 60_000L,
            pollIntervalMs = 60_000L,
            telemetry = telemetry,
            attemptTracker = tracker,
            platform = "test",
            nowMs = { fixedNow },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        receiver.start()
        repeat(5) { yield() }

        val active = receiver.state.value as ReceiverState.Active
        val sender = SecretsTransferSenderViewModel(
            protocol = protocol,
            secretStore = secretStore,
            deviceIdProvider = RedactionFakeDeviceIdProvider(),
            prefsRepo = prefs,
            configKeyAllowlist = DefaultConfigKeyAllowlist(),
            relayApi = sharedApi,
            accessTokenProvider = { "snd-tok" },
            telemetry = telemetry,
            attemptTracker = tracker,
            platform = "test",
            nowMs = { fixedNow },
        )
        sender.updateReceiverSessionString(active.sessionString)
        TransferSecretCatalog.specs.forEach { sender.setCategoryEnabled(it.category, false) }
        sender.setCategoryEnabled(SecretCategory.DEBRID, true)
        return Triple(sender, receiver, sharedApi)
    }

    private fun dummyDto(id: String): TransferSessionDto = TransferSessionDto(
        sessionId = id,
        expiresAtEpochMs = fixedNow + 60_000L,
        state = "pending",
        envelope = null,
    )
}

/**
 * Spy emitter that captures every emission and exposes a fluent leak-
 * scanning helper. Build this once per test.
 */
private class SpyTelemetry : TelemetryEmitter {
    data class Captured(val event: String, val attributes: Map<String, String>)

    private val _events = mutableListOf<Captured>()
    val events: List<Captured> get() = _events

    override fun emit(event: String, attributes: Map<String, String>) {
        _events += Captured(event, attributes.toMap())
    }

    fun findOne(event: String): Map<String, String> {
        val matches = _events.filter { it.event == event }
        if (matches.size != 1) {
            fail("Expected exactly one '$event' emission, got ${matches.size}: ${_events.map { it.event }}")
        }
        return matches.single().attributes
    }

    /**
     * Asserts every captured emission's attribute values are free of
     * known leak shapes (envelope JSON, session strings, bearer tokens,
     * base64url public-key blobs, etc.) plus any [extra] caller-supplied
     * substrings that came from this specific test run.
     */
    fun assertNoLeaks(extra: List<String> = emptyList()) {
        val builtIn = listOf(
            "torve://transfer/receive/", // session-string prefix
            "Bearer ",                   // any bearer header leak
            "\"version\"",                // envelope JSON shape
            "\"ciphertext\"",             // envelope JSON shape
            "\"senderEphemeralPublicKey\"",
            "\"aeadNonce\"",
        )
        for (captured in _events) {
            for (value in captured.attributes.values) {
                for (needle in builtIn + extra.filter { it.isNotBlank() }) {
                    if (value.contains(needle)) {
                        fail("Telemetry leak in ${captured.event}: '$value' contains '$needle'")
                    }
                }
            }
            // The event NAME itself must also be one of our declared
            // constants; catches future drift where a feature accidentally
            // uses a string literal that includes user data.
            val declared = setOf(
                TransferTelemetryEvents.RECEIVE_SESSION_CREATED,
                TransferTelemetryEvents.SEND_DELIVERED,
                TransferTelemetryEvents.SEND_FAILED,
                TransferTelemetryEvents.IMPORT_SUCCESS,
                TransferTelemetryEvents.IMPORT_FAILED,
                TransferTelemetryEvents.RELAY_UNAVAILABLE,
                TransferTelemetryEvents.MANUAL_PASTE_USED,
            )
            assertTrue(
                captured.event in declared,
                "Unknown transfer telemetry event '${captured.event}'",
            )
        }
    }
}

private class RedactionFakePrefs : PreferencesRepository {
    private val store = mutableMapOf<String, String>()
    override suspend fun getString(key: String): String? = store[key]
    override suspend fun setString(key: String, value: String) { store[key] = value }
    override suspend fun remove(key: String) { store.remove(key) }
}

private class RedactionFakeSecretStore : IntegrationSecretStore {
    private val backing = mutableMapOf<String, String>()
    private fun addr(key: IntegrationSecretKey, subKey: String?) = "${key.name}|${subKey.orEmpty()}"
    fun seed(key: IntegrationSecretKey, value: String, subKey: String? = null) {
        backing[addr(key, subKey)] = value
    }
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

private class RedactionFakeDeviceIdProvider : DeviceIdProvider {
    override fun getDeviceId(): String = "test-device"
    override fun getDeviceName(): String = "Test"
    override fun getDeviceType(): String = "test"
    override fun getPlatform(): String = "test"
}

private class RedactionStubRelayApi(
    val createResult: TransferRelayResult<TransferSessionDto> =
        TransferRelayResult.NetworkError("not stubbed"),
    val postResult: TransferRelayResult<Unit> =
        TransferRelayResult.NetworkError("not stubbed"),
) : TransferRelayApi {
    override suspend fun createSession(
        accessToken: String,
        request: CreateTransferSessionRequest,
    ): TransferRelayResult<TransferSessionDto> = createResult

    override suspend fun getSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<TransferSessionDto> = TransferRelayResult.Success(
        TransferSessionDto(sessionId = sessionId, expiresAtEpochMs = 0L, state = "pending", envelope = null)
    )

    override suspend fun postEnvelope(
        accessToken: String,
        sessionId: String,
        envelope: SealedSecretsEnvelope,
    ): TransferRelayResult<Unit> = postResult

    override suspend fun consumeSession(
        accessToken: String,
        sessionId: String,
    ): TransferRelayResult<Unit> = TransferRelayResult.Success(Unit)
}
