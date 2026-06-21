package com.torve.presentation.transfer

import com.torve.data.auth.AuthClient
import com.torve.data.transfer.TransferRelayApi
import com.torve.data.transfer.TransferRelayResult
import com.torve.domain.telemetry.TransferTelemetryErrorCategory
import com.torve.domain.transfer.TransferCryptoEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

/**
 * Read-only, redaction-safe snapshot of the credential-transfer
 * subsystem. Powers the per-platform diagnostics surfaces.
 *
 * Field invariants — every value is one of:
 *   * a `Boolean` health flag,
 *   * an `Instant`-equivalent epoch ms,
 *   * an enum,
 *   * a coarse counts/category bucket.
 *
 * **Nothing here is or can become a secret, an access token, an
 * envelope, a session string, a public/private key, or a backend body.**
 * The error categories are the same closed enum used by telemetry —
 * stack traces / URLs / opaque tokens cannot reach this struct.
 */
data class TransferDiagnosticsSnapshot(
    val cryptoEngineAvailable: Boolean,
    val signedIn: Boolean,
    val relayReachable: RelayReachability,
    val lastAttempt: TransferAttemptRecord?,
    val collectedAtEpochMs: Long,
)

enum class RelayReachability {
    UNKNOWN,
    REACHABLE,
    UNAVAILABLE,
    UNAUTHORIZED,
    NETWORK_ERROR,
    NOT_SIGNED_IN,
    NO_CRYPTO_ENGINE,
}

/**
 * One closed record of the most recent transfer attempt. Stored in
 * memory only — never persisted. The error category is the same closed
 * enum the telemetry surface uses.
 */
data class TransferAttemptRecord(
    val role: AttemptRole,
    val outcome: AttemptOutcome,
    val errorCategory: TransferTelemetryErrorCategory? = null,
    val recordedAtEpochMs: Long,
)

enum class AttemptRole { SENDER, RECEIVER }
enum class AttemptOutcome { REGISTERED, DELIVERED, IMPORTED, FAILED, RELAY_UNAVAILABLE }

/**
 * Singleton-by-Koin in-memory tracker. The shared sender + receiver
 * VMs feed it on every meaningful state transition; the diagnostics
 * surface reads it back. No secrets cross this boundary because the
 * VMs only emit closed-enum values.
 */
class TransferAttemptTracker(
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _last = MutableStateFlow<TransferAttemptRecord?>(null)
    val last: StateFlow<TransferAttemptRecord?> = _last.asStateFlow()

    fun record(
        role: AttemptRole,
        outcome: AttemptOutcome,
        errorCategory: TransferTelemetryErrorCategory? = null,
    ) {
        _last.value = TransferAttemptRecord(
            role = role,
            outcome = outcome,
            errorCategory = errorCategory,
            recordedAtEpochMs = nowMs(),
        )
    }
}

/**
 * Assembles a one-shot diagnostics snapshot. Calls the relay's
 * `createSession` is intentionally NOT done here — that would consume
 * a session id + leak quota. Instead we do a cheap auth probe via
 * [AuthClient.getValidAccessToken] and rely on observed history from
 * [TransferAttemptTracker].
 */
class TransferDiagnosticsCollector(
    private val cryptoEngine: TransferCryptoEngine?,
    private val authClient: AuthClient?,
    private val relayApi: TransferRelayApi?,
    private val tracker: TransferAttemptTracker,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /**
     * Builds a snapshot. If [probeRelay] is true and the user is signed
     * in, do a single live probe — otherwise infer reachability from
     * the most recent attempt the tracker has on file.
     *
     * Never calls a destructive endpoint. Never logs anything.
     */
    suspend fun collect(probeRelay: Boolean = false): TransferDiagnosticsSnapshot {
        val token = runCatching { authClient?.getValidAccessToken() }.getOrNull()
        val signedIn = !token.isNullOrBlank()
        val cryptoOk = cryptoEngine != null
        val relayState = when {
            !cryptoOk -> RelayReachability.NO_CRYPTO_ENGINE
            !signedIn -> RelayReachability.NOT_SIGNED_IN
            !probeRelay || relayApi == null -> inferFromHistory(tracker.last.value)
            else -> probeOnce(relayApi, token!!)
        }
        return TransferDiagnosticsSnapshot(
            cryptoEngineAvailable = cryptoOk,
            signedIn = signedIn,
            relayReachable = relayState,
            lastAttempt = tracker.last.value,
            collectedAtEpochMs = nowMs(),
        )
    }

    private fun inferFromHistory(last: TransferAttemptRecord?): RelayReachability =
        when (last?.outcome) {
            AttemptOutcome.REGISTERED, AttemptOutcome.DELIVERED, AttemptOutcome.IMPORTED ->
                RelayReachability.REACHABLE
            AttemptOutcome.RELAY_UNAVAILABLE -> RelayReachability.UNAVAILABLE
            AttemptOutcome.FAILED -> when (last.errorCategory) {
                TransferTelemetryErrorCategory.UNAUTHORIZED -> RelayReachability.UNAUTHORIZED
                TransferTelemetryErrorCategory.NETWORK -> RelayReachability.NETWORK_ERROR
                TransferTelemetryErrorCategory.RELAY_NOT_DEPLOYED -> RelayReachability.UNAVAILABLE
                else -> RelayReachability.UNKNOWN
            }
            null -> RelayReachability.UNKNOWN
        }

    private suspend fun probeOnce(api: TransferRelayApi, token: String): RelayReachability {
        // We don't have a "ping" endpoint, and we can't actually create
        // a session here without consuming quota. So we send a request
        // to a deliberately bogus session id and treat 404 (NotFound)
        // as proof the route family is mounted, while Unavailable is
        // proof it isn't. Any other outcome maps to its closed enum.
        val result = api.getSession(accessToken = token, sessionId = "diag-probe-nonexistent")
        return when (result) {
            is TransferRelayResult.Success -> RelayReachability.REACHABLE
            TransferRelayResult.NotFound -> RelayReachability.REACHABLE
            TransferRelayResult.Unavailable -> RelayReachability.UNAVAILABLE
            TransferRelayResult.Unauthorized -> RelayReachability.UNAUTHORIZED
            TransferRelayResult.Forbidden -> RelayReachability.UNAUTHORIZED
            TransferRelayResult.Expired,
            TransferRelayResult.Consumed -> RelayReachability.REACHABLE
            TransferRelayResult.PayloadTooLarge -> RelayReachability.REACHABLE
            TransferRelayResult.EmailNotVerified -> RelayReachability.UNAUTHORIZED
            is TransferRelayResult.NetworkError -> RelayReachability.NETWORK_ERROR
            is TransferRelayResult.ServerError -> RelayReachability.NETWORK_ERROR
        }
    }
}

/**
 * Maps a [TransferRelayResult] failure into the closed
 * [TransferTelemetryErrorCategory] enum so VMs can record + emit
 * without ever touching backend body strings.
 */
fun TransferRelayResult<*>.toErrorCategory(): TransferTelemetryErrorCategory? = when (this) {
    is TransferRelayResult.Success -> null
    TransferRelayResult.Unavailable -> TransferTelemetryErrorCategory.RELAY_NOT_DEPLOYED
    TransferRelayResult.NotFound -> TransferTelemetryErrorCategory.RELAY_NOT_FOUND
    TransferRelayResult.Unauthorized -> TransferTelemetryErrorCategory.UNAUTHORIZED
    TransferRelayResult.Forbidden -> TransferTelemetryErrorCategory.FORBIDDEN
    TransferRelayResult.Expired -> TransferTelemetryErrorCategory.EXPIRED
    TransferRelayResult.Consumed -> TransferTelemetryErrorCategory.CONSUMED
    TransferRelayResult.PayloadTooLarge -> TransferTelemetryErrorCategory.PAYLOAD_TOO_LARGE
    TransferRelayResult.EmailNotVerified -> TransferTelemetryErrorCategory.FORBIDDEN
    is TransferRelayResult.NetworkError -> TransferTelemetryErrorCategory.NETWORK
    is TransferRelayResult.ServerError -> TransferTelemetryErrorCategory.SERVER
}
