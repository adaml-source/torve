package com.torve.desktop.transfer

import com.torve.data.transfer.CreateTransferSessionRequest
import com.torve.data.transfer.TransferRelayApi
import com.torve.data.transfer.TransferRelayResult
import com.torve.data.transfer.TransferSessionDto
import com.torve.desktop.security.JvmTransferCryptoEngine
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.transfer.ConsumedNonceStore
import com.torve.domain.transfer.SealedSecretsEnvelope
import com.torve.domain.transfer.SecretCategory
import com.torve.domain.transfer.SecretsTransferApplier
import com.torve.domain.transfer.SecretsTransferProtocol
import com.torve.presentation.transfer.ReceiverState
import com.torve.presentation.transfer.RelayDeliveryState
import com.torve.presentation.transfer.RelayStatus
import com.torve.presentation.transfer.SecretsTransferReceiverViewModel
import com.torve.presentation.transfer.SecretsTransferSenderViewModel
import com.torve.presentation.transfer.SenderStatus
import com.torve.presentation.transfer.TransferSecretCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the receiver and sender VMs handle every relay outcome
 * without ever simulating a successful delivery. The TransferRelayApi
 * fake returns canned [TransferRelayResult] values so the state machine
 * can be exercised against [TransferRelayResult.Unavailable],
 * [TransferRelayResult.Unauthorized], [TransferRelayResult.NetworkError]
 * and friends without touching a real network.
 *
 * The receiver fakes a polling step that always returns "pending" so the
 * test does not assert "delivered → applied" — that path is intentionally
 * out-of-scope until the backend ships the `/transfer/...` family.
 */
class SecretsTransferRelayTest {

    @Test
    fun receiverShowsRegisteredWhenRelayCreatesSessionAndKeepsAdvancedPasteAvailable() = runBlocking {
        val api = StubRelayApi(createResult = TransferRelayResult.Success(
            TransferSessionDto(
                sessionId = "relay-session-id",
                expiresAtEpochMs = 9_999_999_999L,
                state = "pending",
                envelope = null,
            )
        ))
        val receiver = receiverVm(api = api, token = "valid-token")
        receiver.start()
        // The launch in scope is on Unconfined; let it run and settle.
        repeat(5) { yield() }

        val active = assertIs<ReceiverState.Active>(receiver.state.value)
        val status = assertIs<RelayStatus.Registered>(active.relayStatus)
        assertEquals("relay-session-id", status.sessionId)
        assertEquals(1, api.createCalls)
        // Session string is rebuilt with relaySessionId so the sender knows where to post.
        assertTrue(active.sessionString.contains("relay-session-id").let { _ ->
            // sessionString is base64url(JSON), so just check the round-trip handshake carries it.
            active.handshake.relaySessionId == "relay-session-id"
        })
        receiver.cancel()
    }

    @Test
    fun receiverShowsUnavailableWhenRelayReturns404() = runBlocking {
        val api = StubRelayApi(createResult = TransferRelayResult.Unavailable)
        val receiver = receiverVm(api = api, token = "valid-token")
        receiver.start()
        repeat(5) { yield() }

        val active = assertIs<ReceiverState.Active>(receiver.state.value)
        val status = assertIs<RelayStatus.Unavailable>(active.relayStatus)
        assertTrue(status.reason.contains("not deployed", ignoreCase = true), status.reason)
        receiver.cancel()
    }

    @Test
    fun receiverShowsUnavailableOnNetworkError() = runBlocking {
        val api = StubRelayApi(
            createResult = TransferRelayResult.NetworkError("connection refused"),
        )
        val receiver = receiverVm(api = api, token = "valid-token")
        receiver.start()
        repeat(5) { yield() }

        val active = assertIs<ReceiverState.Active>(receiver.state.value)
        val status = assertIs<RelayStatus.Unavailable>(active.relayStatus)
        // We deliberately don't surface the raw NetworkError message in the
        // user-facing reason — assert the generic "Network unreachable" copy
        // and verify the API was called once.
        assertTrue(status.reason.contains("Network unreachable", ignoreCase = true), status.reason)
        assertEquals(1, api.createCalls)
        receiver.cancel()
    }

    @Test
    fun receiverShowsUnavailableOnUnauthorized() = runBlocking {
        val api = StubRelayApi(createResult = TransferRelayResult.Unauthorized)
        val receiver = receiverVm(api = api, token = "valid-token")
        receiver.start()
        repeat(5) { yield() }

        val status = assertIs<RelayStatus.Unavailable>(
            assertIs<ReceiverState.Active>(receiver.state.value).relayStatus
        )
        assertTrue(status.reason.contains("Re-sign in", ignoreCase = true), status.reason)
        receiver.cancel()
    }

    @Test
    fun receiverShowsUnavailableWhenSignedOut() = runBlocking {
        val api = StubRelayApi(createResult = TransferRelayResult.Success(dummyDto()))
        // accessTokenProvider returns null → "Sign in to enable relay delivery."
        val receiver = receiverVm(api = api, token = null)
        receiver.start()
        repeat(5) { yield() }

        val active = assertIs<ReceiverState.Active>(receiver.state.value)
        val status = assertIs<RelayStatus.Unavailable>(active.relayStatus)
        assertTrue(status.reason.contains("Sign in", ignoreCase = true), status.reason)
        // We never reached createSession.
        assertEquals(0, api.createCalls)
        receiver.cancel()
    }

    @Test
    fun receiverWithoutRelayApiReportsNotConfigured() = runBlocking {
        val receiver = receiverVm(api = null, token = null)
        receiver.start()
        repeat(2) { yield() }

        val active = assertIs<ReceiverState.Active>(receiver.state.value)
        assertEquals(RelayStatus.NotConfigured, active.relayStatus)
        receiver.cancel()
    }

    @Test
    fun senderShowsNotAttemptedWhenReceiverHasNoRelaySessionId() = runBlocking {
        // Receiver uses no relay; its session string carries no relaySessionId.
        val protocol = SecretsTransferProtocol(JvmTransferCryptoEngine(), nowMs = { 1_000L })
        val receiver = receiverVm(protocol = protocol, api = null, token = null)
        receiver.start()
        val sessionString = assertIs<ReceiverState.Active>(receiver.state.value).sessionString

        val sender = senderVm(
            protocol = protocol,
            api = StubRelayApi(),
            token = "valid-token",
        )
        sender.updateReceiverSessionString(sessionString)
        TransferSecretCatalog.specs.forEach { sender.setCategoryEnabled(it.category, false) }
        sender.setCategoryEnabled(SecretCategory.DEBRID, true)
        sender.generateEnvelope()

        val ready = assertIs<SenderStatus.Ready>(sender.state.value.status)
        assertEquals(RelayDeliveryState.NotAttempted, ready.relayDelivery)
        receiver.cancel()
    }

    @Test
    fun senderTransitionsPostingToFailedOnUnavailable() = runBlocking {
        val (sender, receiver, api) = senderHookedToReceiver(
            relayCreateResult = TransferRelayResult.Success(dummyDto("relay-id-A")),
            postEnvelopeResult = TransferRelayResult.Unavailable,
        )
        sender.generateEnvelope()
        repeat(5) { yield() }

        val ready = assertIs<SenderStatus.Ready>(sender.state.value.status)
        val failed = assertIs<RelayDeliveryState.Failed>(ready.relayDelivery)
        assertTrue(failed.reason.contains("not deployed", ignoreCase = true), failed.reason)
        // The paste path is still primary — envelopeJson is intact.
        assertTrue(ready.envelopeJson.isNotBlank())
        assertEquals(1, api.postCalls)
        receiver.cancel()
    }

    @Test
    fun senderTransitionsPostingToFailedOnNotFound() = runBlocking {
        val (sender, receiver, api) = senderHookedToReceiver(
            relayCreateResult = TransferRelayResult.Success(dummyDto("relay-id-NF")),
            postEnvelopeResult = TransferRelayResult.NotFound,
        )
        sender.generateEnvelope()
        repeat(5) { yield() }

        val ready = assertIs<SenderStatus.Ready>(sender.state.value.status)
        val failed = assertIs<RelayDeliveryState.Failed>(ready.relayDelivery)
        assertTrue(failed.reason.contains("session not found", ignoreCase = true), failed.reason)
        // Paste fallback intact.
        assertTrue(ready.envelopeJson.isNotBlank())
        assertEquals(1, api.postCalls)
        receiver.cancel()
    }

    @Test
    fun senderTransitionsPostingToFailedOnConsumedFor410Delivered() = runBlocking {
        // 410 with body { "state": "delivered" } maps to Consumed via the
        // Ktor layer; the sender should surface a non-misleading reason.
        val (sender, receiver, _) = senderHookedToReceiver(
            relayCreateResult = TransferRelayResult.Success(dummyDto("relay-id-D")),
            postEnvelopeResult = TransferRelayResult.Consumed,
        )
        sender.generateEnvelope()
        repeat(5) { yield() }

        val ready = assertIs<SenderStatus.Ready>(sender.state.value.status)
        val failed = assertIs<RelayDeliveryState.Failed>(ready.relayDelivery)
        assertTrue(
            failed.reason.contains("already used") || failed.reason.contains("already delivered"),
            failed.reason,
        )
        receiver.cancel()
    }

    @Test
    fun senderTransitionsPostingToFailedOnNetworkError() = runBlocking {
        val (sender, receiver, _) = senderHookedToReceiver(
            relayCreateResult = TransferRelayResult.Success(dummyDto("relay-id-B")),
            postEnvelopeResult = TransferRelayResult.NetworkError("dns blackhole"),
        )
        sender.generateEnvelope()
        repeat(5) { yield() }

        val ready = assertIs<SenderStatus.Ready>(sender.state.value.status)
        val failed = assertIs<RelayDeliveryState.Failed>(ready.relayDelivery)
        // Sender copy is now generic too; we don't surface the raw network
        // reason. Confirm the message keeps the paste-fallback nudge.
        assertTrue(failed.reason.contains("Network unreachable", ignoreCase = true), failed.reason)
        assertTrue(failed.reason.contains("sealed code", ignoreCase = true), failed.reason)
        receiver.cancel()
    }

    @Test
    fun senderFailsWhenAccessTokenMissingEvenIfReceiverRegistered() = runBlocking {
        val (sender, receiver, api) = senderHookedToReceiver(
            relayCreateResult = TransferRelayResult.Success(dummyDto("relay-id-C")),
            postEnvelopeResult = TransferRelayResult.Success(Unit), // never reached
            senderToken = null,
        )
        sender.generateEnvelope()
        repeat(5) { yield() }

        val ready = assertIs<SenderStatus.Ready>(sender.state.value.status)
        val failed = assertIs<RelayDeliveryState.Failed>(ready.relayDelivery)
        assertTrue(failed.reason.contains("Sign in", ignoreCase = true), failed.reason)
        // We must NOT post without a token.
        assertEquals(0, api.postCalls)
        receiver.cancel()
    }

    // ── helpers ─────────────────────────────────────────────────

    private fun receiverVm(
        protocol: SecretsTransferProtocol = SecretsTransferProtocol(JvmTransferCryptoEngine(), nowMs = { 1_000L }),
        store: FakeTransferSecretStore = FakeTransferSecretStore(),
        nonces: ConsumedNonceStore = ConsumedNonceStore(FakeTransferPrefs(), nowMs = { 1_000L }),
        prefs: FakeTransferPrefs = FakeTransferPrefs(),
        api: TransferRelayApi?,
        token: String?,
    ): SecretsTransferReceiverViewModel = SecretsTransferReceiverViewModel(
        protocol = protocol,
        applier = SecretsTransferApplier(store, nonces, prefs),
        nonceStore = nonces,
        relayApi = api,
        accessTokenProvider = { token },
        deviceIdProvider = FakeTransferDeviceIdProvider(),
        // Long poll interval so the test never observes a poll round-trip.
        pollIntervalMs = 60_000L,
        nowMs = { 1_000L },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private fun senderVm(
        protocol: SecretsTransferProtocol,
        store: FakeTransferSecretStore = FakeTransferSecretStore().apply {
            seed(IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID, "rd")
        },
        prefs: FakeTransferPrefs = FakeTransferPrefs(),
        api: TransferRelayApi?,
        token: String?,
    ): SecretsTransferSenderViewModel = SecretsTransferSenderViewModel(
        protocol = protocol,
        secretStore = store,
        deviceIdProvider = FakeTransferDeviceIdProvider(),
        prefsRepo = prefs,
        relayApi = api,
        accessTokenProvider = { token },
        nowMs = { 1_000L },
    )

    private suspend fun senderHookedToReceiver(
        relayCreateResult: TransferRelayResult<TransferSessionDto>,
        postEnvelopeResult: TransferRelayResult<Unit>,
        senderToken: String? = "sender-token",
    ): Triple<SecretsTransferSenderViewModel, SecretsTransferReceiverViewModel, StubRelayApi> {
        val protocol = SecretsTransferProtocol(JvmTransferCryptoEngine(), nowMs = { 1_000L })
        val sharedApi = StubRelayApi(
            createResult = relayCreateResult,
            postResult = postEnvelopeResult,
        )
        val receiver = receiverVm(protocol = protocol, api = sharedApi, token = "receiver-token")
        receiver.start()
        repeat(5) { yield() }

        val active = assertIs<ReceiverState.Active>(receiver.state.value)
        val sender = senderVm(protocol = protocol, api = sharedApi, token = senderToken)
        sender.updateReceiverSessionString(active.sessionString)
        TransferSecretCatalog.specs.forEach { sender.setCategoryEnabled(it.category, false) }
        sender.setCategoryEnabled(SecretCategory.DEBRID, true)
        return Triple(sender, receiver, sharedApi)
    }

    private fun dummyDto(id: String = "dummy"): TransferSessionDto = TransferSessionDto(
        sessionId = id,
        expiresAtEpochMs = 9_999_999_999L,
        state = "pending",
        envelope = null,
    )
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
                expiresAtEpochMs = 9_999_999_999L,
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
