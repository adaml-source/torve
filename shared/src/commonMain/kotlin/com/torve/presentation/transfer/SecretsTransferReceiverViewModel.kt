package com.torve.presentation.transfer

import com.torve.data.transfer.CreateTransferSessionRequest
import com.torve.data.transfer.TransferRelayApi
import com.torve.data.transfer.TransferRelayResult
import com.torve.domain.device.DeviceIdProvider
import com.torve.domain.telemetry.NoOpTelemetryEmitter
import com.torve.domain.telemetry.TelemetryEmitter
import com.torve.domain.telemetry.TransferRole
import com.torve.domain.telemetry.TransferTelemetryErrorCategory
import com.torve.domain.telemetry.TransferTelemetryEvents
import com.torve.domain.telemetry.TransferTelemetryState
import com.torve.domain.telemetry.transferAttrs
import com.torve.domain.transfer.Base64Url
import com.torve.domain.transfer.ConsumedNonceStore
import com.torve.domain.transfer.EphemeralKeyPair
import com.torve.domain.transfer.SealedSecretsEnvelope
import com.torve.domain.transfer.SecretsTransferApplier
import com.torve.domain.transfer.SecretsTransferProtocol
import com.torve.domain.transfer.TransferReceiverHandshake
import com.torve.domain.transfer.TransferApplyResult
import com.torve.domain.transfer.TransferDecryptResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * Cross-platform receive-side VM for credential transfer.
 *
 * Lifecycle:
 * - [start] generates an ephemeral X25519 key pair via the protocol,
 *   builds the handshake string the UI shows as a QR, and starts both
 *   a 1 Hz countdown ticker and (when wired) a backend-relay session
 *   registration that polls for envelope arrival.
 * - [cancel] wipes the in-memory private-key reference and returns to
 *   [ReceiverState.Idle].
 * - Expiry transitions to [ReceiverState.Expired] automatically.
 *
 * The private-key half never leaves this object — no DI, no logs, no
 * persistence. Cancel/expiry drops the only reference and lets GC
 * collect it. (Kotlin can't reliably wipe heap byte arrays.)
 *
 * The session-id used in the handshake is **only** an opaque local
 * identifier; the relay-issued id (if registration succeeds) is what
 * the sender posts envelopes to. So [randomBytes] uses the platform's
 * default `kotlin.random.Random` rather than burning a coroutine on
 * `engine.secureRandom` for what isn't a crypto-sensitive value.
 */
class SecretsTransferReceiverViewModel(
    private val protocol: SecretsTransferProtocol,
    private val applier: SecretsTransferApplier,
    private val nonceStore: ConsumedNonceStore,
    private val relayApi: TransferRelayApi? = null,
    private val accessTokenProvider: (suspend () -> String?)? = null,
    private val deviceIdProvider: DeviceIdProvider? = null,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val telemetry: TelemetryEmitter = NoOpTelemetryEmitter(),
    private val attemptTracker: TransferAttemptTracker? = null,
    private val completionNotifier: TransferImportCompletionNotifier? = null,
    private val platform: String = "unknown",
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val randomBytes: (Int) -> ByteArray = { kotlin.random.Random.Default.nextBytes(it) },
) {
    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private var privateKey: ByteArray? = null
    private var tickerJob: Job? = null
    private var pollJob: Job? = null

    suspend fun start() {
        if (_state.value is ReceiverState.Active) return

        val createdAt = nowMs()
        val keyPair: EphemeralKeyPair = protocol.generateReceiverKeyPair()
        val sessionId = freshSessionId()
        val expiresAt = createdAt + ttlMs
        val initialHandshake = TransferReceiverHandshake(
            sessionId = sessionId,
            receiverEphemeralPublicKey = Base64Url.encode(keyPair.publicKey),
            expiresAtEpochMs = expiresAt,
        )
        privateKey = keyPair.privateKey

        val initialString = TransferSessionCodec.encode(initialHandshake)
        _state.value = ReceiverState.Active(
            handshake = initialHandshake,
            sessionString = initialString,
            createdAtEpochMs = createdAt,
            expiresAtEpochMs = expiresAt,
            remainingSeconds = ((expiresAt - createdAt) / 1000L).coerceAtLeast(0L),
            relayStatus = if (relayApi == null) RelayStatus.NotConfigured else RelayStatus.Registering,
        )

        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                val current = _state.value
                if (current !is ReceiverState.Active) break

                val remaining = ((current.expiresAtEpochMs - nowMs()) / 1000L)
                    .coerceAtLeast(0L)
                if (remaining <= 0L) {
                    expireInternal()
                    break
                }
                _state.value = current.copy(remainingSeconds = remaining)
            }
        }

        // Try the backend relay if we have one. On Unavailable / network
        // errors we degrade to manual paste — never simulate success.
        if (relayApi != null) {
            scope.launch { tryRegisterRelay(initialHandshake) }
        }
    }

    private suspend fun tryRegisterRelay(initialHandshake: TransferReceiverHandshake) {
        val api = relayApi ?: return
        val token = runCatching { accessTokenProvider?.invoke() }.getOrNull()
        if (token.isNullOrBlank()) {
            updateRelayStatus(RelayStatus.Unavailable("Sign in to enable relay delivery."))
            return
        }
        val deviceId = deviceIdProvider?.getDeviceId() ?: "unknown"
        val deviceName = deviceIdProvider?.getDeviceName()
        val result = api.createSession(
            accessToken = token,
            request = CreateTransferSessionRequest(
                receiverPublicKey = initialHandshake.receiverEphemeralPublicKey,
                expiresAtEpochMs = initialHandshake.expiresAtEpochMs,
                receiverDeviceId = deviceId,
                receiverDeviceName = deviceName,
            ),
        )
        when (result) {
            is TransferRelayResult.Success -> {
                val active = _state.value as? ReceiverState.Active ?: return
                val updatedHandshake = initialHandshake.copy(relaySessionId = result.value.sessionId)
                val updatedString = TransferSessionCodec.encode(updatedHandshake)
                _state.value = active.copy(
                    handshake = updatedHandshake,
                    sessionString = updatedString,
                    relayStatus = RelayStatus.Registered(result.value.sessionId),
                )
                telemetry.emit(
                    TransferTelemetryEvents.RECEIVE_SESSION_CREATED,
                    transferAttrs(
                        platform = platform,
                        role = TransferRole.RECEIVER,
                        state = TransferTelemetryState.REGISTERED,
                    ),
                )
                attemptTracker?.record(AttemptRole.RECEIVER, AttemptOutcome.REGISTERED)
                startRelayPolling(result.value.sessionId)
            }
            TransferRelayResult.Unavailable -> {
                updateRelayStatus(
                    RelayStatus.Unavailable("Backend relay endpoints not deployed. Use the paste flow.")
                )
                emitRelayUnavailable(result)
            }
            TransferRelayResult.NotFound -> {
                updateRelayStatus(
                    RelayStatus.Unavailable("Relay refused this session. Use the paste flow.")
                )
                emitRelayUnavailable(result)
            }
            TransferRelayResult.Unauthorized -> {
                updateRelayStatus(
                    RelayStatus.Unavailable("Auth rejected. Re-sign in to enable relay delivery.")
                )
                emitRelayUnavailable(result)
            }
            TransferRelayResult.Forbidden -> {
                updateRelayStatus(
                    RelayStatus.Unavailable("Relay rejected this session.")
                )
                emitRelayUnavailable(result)
            }
            TransferRelayResult.Expired,
            TransferRelayResult.Consumed -> {
                updateRelayStatus(
                    RelayStatus.Unavailable("Relay session ended unexpectedly. Try again.")
                )
                emitRelayUnavailable(result)
            }
            TransferRelayResult.PayloadTooLarge -> {
                updateRelayStatus(
                    RelayStatus.Unavailable("Relay rejected the request payload size.")
                )
                emitRelayUnavailable(result)
            }
            is TransferRelayResult.NetworkError -> {
                // Suppress the raw message so we don't surface backend
                // hostnames or low-level reason strings in the UI.
                updateRelayStatus(
                    RelayStatus.Unavailable("Network unreachable.")
                )
                emitRelayUnavailable(result)
            }
            TransferRelayResult.EmailNotVerified -> {
                updateRelayStatus(
                    RelayStatus.Unavailable(
                        "Verify your email address first — we just sent a confirmation link to your inbox. Until then, use the manual paste field below.",
                    ),
                )
                emitRelayUnavailable(result)
            }
            is TransferRelayResult.ServerError -> {
                // Prefer the backend's parsed `detail` over a bare status
                // code — "(400)" is meaningless to a user.
                val human = result.detail?.takeIf { it.isNotBlank() }
                    ?: "Couldn't reach the auto-import service (status ${result.statusCode})."
                updateRelayStatus(RelayStatus.Unavailable(human))
                emitRelayUnavailable(result)
            }
        }
    }

    private fun emitRelayUnavailable(result: TransferRelayResult<*>) {
        val category = result.toErrorCategory() ?: TransferTelemetryErrorCategory.OTHER
        telemetry.emit(
            TransferTelemetryEvents.RELAY_UNAVAILABLE,
            transferAttrs(
                platform = platform,
                role = TransferRole.RECEIVER,
                state = TransferTelemetryState.UNAVAILABLE,
                errorCategory = category,
            ),
        )
        attemptTracker?.record(
            AttemptRole.RECEIVER,
            AttemptOutcome.RELAY_UNAVAILABLE,
            errorCategory = category,
        )
    }

    private fun startRelayPolling(sessionId: String) {
        val api = relayApi ?: return
        pollJob?.cancel()
        // Diagnostic — every poll outcome goes to logcat under
        // [TransferPoll] so we can see why a TV receiver "isn't picking
        // up" without needing remote debug. Strip after the
        // receive-poll bug is reproduced and root-caused.
        println("[TransferPoll] start sessionId=$sessionId intervalMs=$pollIntervalMs")
        pollJob = scope.launch {
            var iteration = 0
            while (isActive) {
                delay(pollIntervalMs)
                iteration++
                val active = _state.value as? ReceiverState.Active ?: run {
                    println("[TransferPoll] halt iter=$iteration reason=state_not_active")
                    break
                }
                if (active.relayStatus !is RelayStatus.Registered) {
                    println("[TransferPoll] halt iter=$iteration reason=relayStatus_not_registered current=${active.relayStatus::class.simpleName}")
                    break
                }
                val token = runCatching { accessTokenProvider?.invoke() }.getOrNull()
                if (token.isNullOrBlank()) {
                    println("[TransferPoll] halt iter=$iteration reason=auth_lost")
                    updateRelayStatus(RelayStatus.Unavailable("Auth lost while polling."))
                    break
                }
                val poll = api.getSession(token, sessionId)
                if (poll is TransferRelayResult.Success) {
                    val dto = poll.value
                    println("[TransferPoll] iter=$iteration result=success state=${dto.state} delivered=${dto.isDelivered} expired=${dto.isExpired} consumed=${dto.isConsumed} envelopePresent=${dto.envelope != null}")
                    if (dto.isDelivered) {
                        val envelope = dto.envelope
                        if (envelope != null) {
                            println("[TransferPoll] applying envelope")
                            applyRelayEnvelope(envelope, sessionId, token)
                        } else {
                            println("[TransferPoll] delivered but envelope null — backend bug?")
                        }
                        break
                    }
                    if (dto.isExpired || dto.isConsumed) {
                        println("[TransferPoll] halt iter=$iteration reason=session_terminal")
                        break
                    }
                    // pending → keep polling
                } else if (poll is TransferRelayResult.Unavailable
                    || poll is TransferRelayResult.NotFound
                    || poll is TransferRelayResult.Unauthorized
                    || poll is TransferRelayResult.Forbidden) {
                    println("[TransferPoll] halt iter=$iteration reason=fatal_${poll::class.simpleName}")
                    // Don't hammer the backend if we know it's never going to answer.
                    updateRelayStatus(RelayStatus.Unavailable("Polling halted: ${poll::class.simpleName}"))
                    break
                } else {
                    println("[TransferPoll] iter=$iteration result=transient_${poll::class.simpleName} — retry")
                }
                // NetworkError/ServerError → keep retrying until expiry.
            }
            println("[TransferPoll] end iterations=$iteration")
        }
    }

    private suspend fun applyRelayEnvelope(
        envelope: SealedSecretsEnvelope,
        sessionId: String,
        token: String,
    ) {
        val active = _state.value as? ReceiverState.Active ?: return
        val receiverPriv = privateKey ?: return
        val decrypt = protocol.open(
            envelope = envelope,
            receiverPrivateKey = receiverPriv,
            consumedNonceCheck = { nonce -> nonceStore.isConsumed(nonce) },
        )
        val outcome = when (decrypt) {
            is TransferDecryptResult.Success -> {
                when (val applied = applier.apply(decrypt.payload)) {
                    is TransferApplyResult.Success -> TransferImportResult.Success(applied)
                    else -> TransferImportResult.ApplyFailure(applied)
                }
            }
            else -> TransferImportResult.DecryptFailure(decrypt)
        }
        if (outcome is TransferImportResult.Success) {
            // Best-effort consume; ignore the result. If it fails the
            // backend's TTL will clean up.
            relayApi?.consumeSession(token, sessionId)
            privateKey = null
            tickerJob?.cancel(); tickerJob = null
            pollJob = null
            _state.value = ReceiverState.Imported(outcome)
            emitImportOutcome(outcome, manualPaste = false)
        } else {
            _state.value = active.copy(importResult = outcome)
            emitImportOutcome(outcome, manualPaste = false)
        }
    }

    private fun emitImportOutcome(result: TransferImportResult, manualPaste: Boolean) {
        // Single source of truth for "an import just landed" — fires
        // before telemetry so platform recovery surfaces refresh in the
        // same frame the success banner appears.
        if (result is TransferImportResult.Success) {
            completionNotifier?.notifyImportSuccess()
        }
        when (result) {
            is TransferImportResult.Success -> {
                println(
                    "[TransferImport] success secrets=${result.applyResult.applied} " +
                        "config=${result.applyResult.configApplied} " +
                        "playlists=${result.applyResult.playlistsApplied} " +
                        "skippedSecrets=${result.applyResult.skippedKeyNames.size} " +
                        "skippedPlaylists=${result.applyResult.skippedPlaylistIds.size}",
                )
                if (manualPaste) {
                    telemetry.emit(
                        TransferTelemetryEvents.MANUAL_PASTE_USED,
                        transferAttrs(
                            platform = platform,
                            role = TransferRole.RECEIVER,
                            state = TransferTelemetryState.APPLIED,
                            secretCount = result.applyResult.applied.toInt(),
                            configCount = result.applyResult.configApplied.toInt(),
                        ),
                    )
                }
                telemetry.emit(
                    TransferTelemetryEvents.IMPORT_SUCCESS,
                    transferAttrs(
                        platform = platform,
                        role = TransferRole.RECEIVER,
                        state = TransferTelemetryState.APPLIED,
                        secretCount = result.applyResult.applied.toInt(),
                        configCount = result.applyResult.configApplied.toInt(),
                    ),
                )
                attemptTracker?.record(AttemptRole.RECEIVER, AttemptOutcome.IMPORTED)
            }
            else -> {
                val category = importErrorCategory(result)
                if (manualPaste) {
                    telemetry.emit(
                        TransferTelemetryEvents.MANUAL_PASTE_USED,
                        transferAttrs(
                            platform = platform,
                            role = TransferRole.RECEIVER,
                            state = TransferTelemetryState.FAILED,
                            errorCategory = category,
                        ),
                    )
                }
                telemetry.emit(
                    TransferTelemetryEvents.IMPORT_FAILED,
                    transferAttrs(
                        platform = platform,
                        role = TransferRole.RECEIVER,
                        state = TransferTelemetryState.FAILED,
                        errorCategory = category,
                    ),
                )
                attemptTracker?.record(
                    AttemptRole.RECEIVER,
                    AttemptOutcome.FAILED,
                    errorCategory = category,
                )
            }
        }
    }

    private fun importErrorCategory(result: TransferImportResult): TransferTelemetryErrorCategory =
        when (result) {
            is TransferImportResult.Success -> TransferTelemetryErrorCategory.OTHER
            is TransferImportResult.MalformedEnvelope -> TransferTelemetryErrorCategory.DECRYPT_MALFORMED
            is TransferImportResult.DecryptFailure -> when (val d = result.result) {
                TransferDecryptResult.Expired -> TransferTelemetryErrorCategory.DECRYPT_EXPIRED
                TransferDecryptResult.AuthenticationFailure -> TransferTelemetryErrorCategory.DECRYPT_AUTH
                is TransferDecryptResult.UnsupportedVersion -> TransferTelemetryErrorCategory.DECRYPT_VERSION
                TransferDecryptResult.Replayed -> TransferTelemetryErrorCategory.DECRYPT_REPLAY
                TransferDecryptResult.EnvelopePayloadMismatch -> TransferTelemetryErrorCategory.DECRYPT_AUTH
                is TransferDecryptResult.Malformed -> TransferTelemetryErrorCategory.DECRYPT_MALFORMED
                is TransferDecryptResult.Success -> TransferTelemetryErrorCategory.OTHER
            }
            is TransferImportResult.ApplyFailure -> when (result.result) {
                TransferApplyResult.DuplicateNonce -> TransferTelemetryErrorCategory.DECRYPT_REPLAY
                is TransferApplyResult.NothingApplied -> TransferTelemetryErrorCategory.APPLY_NOTHING
                is TransferApplyResult.StoreFailure -> TransferTelemetryErrorCategory.APPLY_STORE
                is TransferApplyResult.Success -> TransferTelemetryErrorCategory.OTHER
            }
            TransferImportResult.NoActiveSession -> TransferTelemetryErrorCategory.OTHER
            TransferImportResult.MissingPrivateKey -> TransferTelemetryErrorCategory.OTHER
        }

    private fun updateRelayStatus(next: RelayStatus) {
        val active = _state.value as? ReceiverState.Active ?: return
        _state.value = active.copy(relayStatus = next)
    }

    fun cancel() {
        if (_state.value is ReceiverState.Idle) return
        privateKey = null
        tickerJob?.cancel()
        tickerJob = null
        pollJob?.cancel()
        pollJob = null
        _state.value = ReceiverState.Idle
    }

    suspend fun restart() {
        cancel()
        start()
    }

    fun updateEnvelopeText(text: String) {
        val current = _state.value
        if (current is ReceiverState.Active) {
            _state.value = current.copy(envelopeText = text, importResult = null)
        }
    }

    suspend fun acceptEnvelopeJson(): TransferImportResult {
        val active = _state.value as? ReceiverState.Active
            ?: return TransferImportResult.NoActiveSession
        val receiverPrivateKey = privateKey
            ?: return setActiveImportResult(active, TransferImportResult.MissingPrivateKey)
        if (active.envelopeText.isBlank()) {
            return setActiveImportResult(
                active,
                TransferImportResult.MalformedEnvelope("Paste a sealed credential code first."),
            )
        }

        _state.value = active.copy(importing = true, importResult = null)
        val envelope = runCatching {
            JSON.decodeFromString(SealedSecretsEnvelope.serializer(), active.envelopeText.trim())
        }.getOrElse { t ->
            return setActiveImportResult(
                active,
                TransferImportResult.MalformedEnvelope(t.message ?: "Envelope JSON is invalid."),
            )
        }

        val decrypt = protocol.open(
            envelope = envelope,
            receiverPrivateKey = receiverPrivateKey,
            consumedNonceCheck = { nonce -> nonceStore.isConsumed(nonce) },
        )
        val result = when (decrypt) {
            is TransferDecryptResult.Success -> {
                when (val applied = applier.apply(decrypt.payload)) {
                    is TransferApplyResult.Success -> TransferImportResult.Success(applied)
                    else -> TransferImportResult.ApplyFailure(applied)
                }
            }
            else -> TransferImportResult.DecryptFailure(decrypt)
        }

        return if (result is TransferImportResult.Success) {
            privateKey = null
            tickerJob?.cancel()
            tickerJob = null
            _state.value = ReceiverState.Imported(result)
            emitImportOutcome(result, manualPaste = true)
            result
        } else {
            emitImportOutcome(result, manualPaste = true)
            setActiveImportResult(active, result)
        }
    }

    private fun setActiveImportResult(
        previous: ReceiverState.Active,
        result: TransferImportResult,
    ): TransferImportResult {
        _state.value = previous.copy(importing = false, importResult = result)
        return result
    }

    private fun expireInternal() {
        privateKey = null
        tickerJob = null
        _state.value = ReceiverState.Expired
    }

    private fun freshSessionId(): String {
        val bytes = randomBytes(SESSION_ID_BYTES)
        return Base64Url.encode(bytes)
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 10L * 60_000L
        const val DEFAULT_POLL_INTERVAL_MS: Long = 3_000L
        const val SESSION_ID_BYTES = 12

        /**
         * QR payload prefix — mirror of [TransferSessionCodec.QR_PREFIX]
         * for legacy desktop call sites.
         */
        const val QR_PREFIX: String = TransferSessionCodec.QR_PREFIX
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}

/** UI state for the receive surface. */
sealed interface ReceiverState {
    data object Idle : ReceiverState

    data class Active(
        val handshake: TransferReceiverHandshake,
        /** What the QR encodes and what the user can copy/paste. */
        val sessionString: String,
        val createdAtEpochMs: Long,
        val expiresAtEpochMs: Long,
        /** Updated 1 Hz by the controller for the countdown widget. */
        val remainingSeconds: Long,
        val envelopeText: String = "",
        val importing: Boolean = false,
        val importResult: TransferImportResult? = null,
        /** Where the relay backend stands for this session, if at all. */
        val relayStatus: RelayStatus = RelayStatus.NotConfigured,
    ) : ReceiverState

    data class Imported(val result: TransferImportResult.Success) : ReceiverState

    data object Expired : ReceiverState
}

/**
 * Where the relay backend stands for an active receive session.
 *
 *   - [NotConfigured] — no relay client was injected; manual paste only.
 *   - [Registering] — `createSession` is in flight.
 *   - [Registered] — relay session id is in hand; envelope polling is on.
 *   - [Unavailable] — relay said no (404 / unauthorized / network) or
 *     the user isn't signed in. UI surfaces the reason and the manual
 *     paste field becomes the primary action.
 */
sealed interface RelayStatus {
    data object NotConfigured : RelayStatus
    data object Registering : RelayStatus
    data class Registered(val sessionId: String) : RelayStatus
    data class Unavailable(val reason: String) : RelayStatus
}

sealed interface TransferImportResult {
    data class Success(val applyResult: TransferApplyResult.Success) : TransferImportResult
    data class MalformedEnvelope(val reason: String) : TransferImportResult
    data class DecryptFailure(val result: TransferDecryptResult) : TransferImportResult
    data class ApplyFailure(val result: TransferApplyResult) : TransferImportResult
    data object NoActiveSession : TransferImportResult
    data object MissingPrivateKey : TransferImportResult
}
