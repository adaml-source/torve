package com.torve.presentation.pairing

import com.torve.data.auth.AuthClient
import com.torve.data.auth.AuthResult
import com.torve.data.pairing.PairingApi
import com.torve.data.pairing.PairingStatusDto
import com.torve.data.pairing.PairingUnsupportedException
import com.torve.platform.torveVerboseLog
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

/**
 * Cross-platform VM driving the "Sign in on this TV by scanning a QR
 * with your phone" flow.
 *
 * Lifecycle:
 *  1. [start] — POST /pairing/code (no token), get back a short
 *     human-readable [State.Active.code] and an [State.Active.qrPayload]
 *     to render. Spawns a polling job.
 *  2. The polling job hits POST /pairing/status every [pollIntervalMs].
 *     While the response is `pending`, keeps polling. On `expired`,
 *     transitions to [State.Expired]. On `claimed` with tokens,
 *     persists them via [AuthClient.signInViaPairing] and transitions
 *     to [State.SignedIn].
 *  3. The user can [cancel] at any time, which kills the polling job
 *     and resets to [State.Idle].
 *
 * The QR payload is a `torve-signin:<CODE>` string — the mobile QR
 * scanner detects the prefix and dispatches to the claim flow.
 */
class TvPairingSignInViewModel(
    private val pairingApi: PairingApi,
    private val authClient: AuthClient,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    coroutineScope: CoroutineScope? = null,
) {
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollJob: Job? = null

    suspend fun start() {
        if (_state.value is State.Active) return
        _state.value = State.Generating
        try {
            val device = authClient.currentDeviceRegistration()
            val codeDto = pairingApi.createSigninCode(device = device)
            // installation_id is non-nullable on the registration DTO —
            // backend uses it to bind the pending pairing row to the
            // exact device polling for status, so the same poll from a
            // different device with the same code is rejected as 404.
            val installationId = device.installation_id
            val active = State.Active(
                code = codeDto.code,
                qrPayload = "$QR_PAYLOAD_PREFIX${codeDto.code}",
                installationId = installationId,
                expiresAtIso = codeDto.expiresAt,
            )
            _state.value = active
            startPolling(active.code, active.installationId)
        } catch (e: PairingUnsupportedException) {
            _state.value = State.Error(
                "QR sign-in isn't supported on this server. Use email + password.",
            )
        } catch (e: Exception) {
            _state.value = State.Error(
                "Couldn't start QR sign-in: ${e.message ?: e::class.simpleName}",
            )
        }
    }

    fun cancel() {
        pollJob?.cancel()
        pollJob = null
        _state.value = State.Idle
    }

    suspend fun restart() {
        cancel()
        start()
    }

    private fun startPolling(code: String, installationId: String) {
        pollJob?.cancel()
        torveVerboseLog { "[PairingSignIn] poll start code=$code installationId=$installationId" }
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                val current = _state.value as? State.Active ?: run {
                    torveVerboseLog { "[PairingSignIn] poll halt — state changed" }
                    return@launch
                }
                val poll = runCatching {
                    pairingApi.pollSigninStatus(code = code, installationId = installationId)
                }.getOrElse { t ->
                    torveVerboseLog { "[PairingSignIn] poll error ${t::class.simpleName}: ${t.message}" }
                    null
                } ?: continue
                when (poll.status.lowercase()) {
                    "pending" -> {
                        // Loop another iteration. Keep state as Active so
                        // the QR stays on screen.
                    }
                    "expired" -> {
                        _state.value = State.Expired
                        return@launch
                    }
                    "claimed" -> {
                        if (handleClaimed(poll)) return@launch
                        // Claimed but tokens absent — already consumed by
                        // an earlier observer (rare; means another
                        // device using the same code already handled it).
                        // Treat as expired for the user.
                        _state.value = State.Expired
                        return@launch
                    }
                    else -> {
                        torveVerboseLog { "[PairingSignIn] poll unknown status=${poll.status}" }
                    }
                }
            }
        }
    }

    /**
     * Persist tokens from a claimed pairing status. Returns true when
     * tokens were present and persistence succeeded; false when the
     * caller should treat this status as expired.
     */
    private suspend fun handleClaimed(poll: PairingStatusDto): Boolean {
        val tokens = poll.tokens ?: return false
        val user = poll.user ?: return false
        val result: AuthResult = authClient.signInViaPairing(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresInSeconds = tokens.expiresIn,
            userId = user.id,
            email = user.email,
            displayName = user.displayName,
            isVerified = user.isVerified,
            deviceId = poll.pairedDevice?.id?.takeIf { it.isNotBlank() },
        )
        if (!result.success) {
            _state.value = State.Error(result.error ?: "Couldn't apply pairing tokens.")
            return true
        }
        _state.value = State.SignedIn(
            email = user.email,
            displayName = user.displayName,
        )
        return true
    }

    sealed interface State {
        data object Idle : State
        data object Generating : State
        data class Active(
            val code: String,
            val qrPayload: String,
            val installationId: String,
            val expiresAtIso: String,
        ) : State
        data object Expired : State
        data class SignedIn(val email: String, val displayName: String?) : State
        data class Error(val message: String) : State
    }

    companion object {
        const val QR_PAYLOAD_PREFIX = "torve-signin:"
        const val DEFAULT_POLL_INTERVAL_MS = 2_000L

        /** Recognise a scanned QR string as a Torve sign-in code. */
        fun extractCodeFromQrPayload(payload: String): String? {
            val trimmed = payload.trim()
            if (!trimmed.startsWith(QR_PAYLOAD_PREFIX)) return null
            val code = trimmed.substring(QR_PAYLOAD_PREFIX.length).trim()
            return code.ifBlank { null }
        }
    }
}
