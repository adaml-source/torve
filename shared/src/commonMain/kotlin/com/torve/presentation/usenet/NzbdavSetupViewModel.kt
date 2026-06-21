package com.torve.presentation.usenet

import com.torve.data.usenet.UsenetApiException
import com.torve.data.usenet.UsenetRepository
import com.torve.data.usenet.model.NzbdavStatusResponseDto
import com.torve.data.usenet.model.NzbdavTestResponseDto
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Compact settings-card VM for the NzbDAV integration. Follows the
 * existing integrations pattern: form state + backend-owned status +
 * test/save/remove actions. The backend remains authoritative; the
 * local secret mirror is only a convenience for prefill.
 *
 * Boundary rules (enforced by tests):
 *  - Raw backend reason tokens, version strings, or exception bodies
 *    MUST NOT flow into any user-facing field. This VM maps everything
 *    through the [NzbdavStatus] / [NzbdavTestResult] enums, which carry
 *    only the minimum neutral copy the Settings card is allowed to
 *    render.
 *  - Button-level busy states ([isTesting], [isSaving], [isRemoving])
 *    gate per-action spinners. There is no screen-wide blocking state.
 */
class NzbdavSetupViewModel(
    private val repository: UsenetRepository,
    private val secretStore: IntegrationSecretStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(NzbdavSetupUiState())
    val state: StateFlow<NzbdavSetupUiState> = _state.asStateFlow()

    init {
        // Prefill from the existing local mirror — pattern matches MDBList /
        // Trakt / Panda. Backend `GET /status` races and overrides if it
        // disagrees (authoritative).
        scope.launch {
            val mirroredBaseUrl = runCatching {
                secretStore.get(IntegrationSecretKey.NZBDAV_BASE_URL)
            }.getOrNull().orEmpty()
            val mirroredApiKey = runCatching {
                secretStore.get(IntegrationSecretKey.NZBDAV_API_KEY)
            }.getOrNull().orEmpty()
            _state.update { it.copy(baseUrl = mirroredBaseUrl, apiKey = mirroredApiKey) }
            refreshStatus()
        }
    }

    // ── Form field updates ──────────────────────────────────────────────

    fun updateBaseUrl(value: String) {
        _state.update { it.copy(baseUrl = value) }
    }

    fun updateApiKey(value: String) {
        _state.update { it.copy(apiKey = value) }
    }

    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled) }
    }

    /** Dismiss a transient test-connection inline result. */
    fun clearLastTestResult() {
        _state.update { it.copy(lastTestResult = null) }
    }

    // ── Actions ─────────────────────────────────────────────────────────

    fun refreshStatus() {
        scope.launch {
            _state.update { it.copy(status = NzbdavStatus.Loading) }
            val status = fetchStatusOrFailure()
            _state.update {
                // If the backend says Not Configured, drop the local
                // mirror preview so the form doesn't pretend a config
                // exists. The saved-field prefill stays for the user
                // to edit in-place without re-typing.
                val preview = if (status is NzbdavStatus.NotConfigured) {
                    it.copy(status = status)
                } else it.copy(status = status)
                preview
            }
        }
    }

    fun test() {
        val current = _state.value
        if (current.isTesting) return
        if (current.baseUrl.isBlank() || current.apiKey.isBlank()) {
            _state.update { it.copy(lastTestResult = NzbdavTestResult.MissingFields) }
            return
        }
        scope.launch {
            _state.update { it.copy(isTesting = true, lastTestResult = null) }
            val result = try {
                val response = repository.testNzbdavIntegration(
                    baseUrl = current.baseUrl.trim(),
                    apiKey = current.apiKey.trim(),
                )
                mapTestResult(response)
            } catch (_: UsenetApiException) {
                NzbdavTestResult.Failed
            } catch (_: Exception) {
                NzbdavTestResult.Failed
            }
            _state.update { it.copy(isTesting = false, lastTestResult = result) }
        }
    }

    fun save() {
        val current = _state.value
        if (current.isSaving) return
        if (current.baseUrl.isBlank() || current.apiKey.isBlank()) {
            _state.update { it.copy(lastTestResult = NzbdavTestResult.MissingFields) }
            return
        }
        scope.launch {
            _state.update { it.copy(isSaving = true, lastTestResult = null) }
            val saveOk = try {
                val status = repository.saveNzbdavIntegration(
                    baseUrl = current.baseUrl.trim(),
                    apiKey = current.apiKey.trim(),
                    enabled = current.enabled,
                    storageMode = IntegrationStorageMode.ACCOUNT,
                )
                _state.update { it.copy(status = mapStatus(status)) }
                true
            } catch (_: Exception) {
                _state.update { it.copy(status = NzbdavStatus.ConnectionFailed) }
                false
            }
            _state.update {
                it.copy(
                    isSaving = false,
                    lastTestResult = if (saveOk) NzbdavTestResult.Saved else NzbdavTestResult.Failed,
                )
            }
        }
    }

    fun remove() {
        if (_state.value.isRemoving) return
        scope.launch {
            _state.update { it.copy(isRemoving = true, lastTestResult = null) }
            val removedOk = try {
                repository.deleteNzbdavIntegration()
                true
            } catch (_: Exception) {
                false
            }
            if (removedOk) {
                _state.update {
                    it.copy(
                        baseUrl = "",
                        apiKey = "",
                        enabled = true,
                        status = NzbdavStatus.NotConfigured,
                        lastTestResult = NzbdavTestResult.Removed,
                        isRemoving = false,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        isRemoving = false,
                        lastTestResult = NzbdavTestResult.Failed,
                    )
                }
            }
        }
    }

    // ── Internal mapping ────────────────────────────────────────────────

    private suspend fun fetchStatusOrFailure(): NzbdavStatus = try {
        mapStatus(repository.getNzbdavStatus())
    } catch (_: Exception) {
        NzbdavStatus.ConnectionFailed
    }

    private fun mapStatus(dto: NzbdavStatusResponseDto): NzbdavStatus = when {
        !dto.configured -> NzbdavStatus.NotConfigured
        dto.degraded -> NzbdavStatus.Connected(degraded = true)
        else -> NzbdavStatus.Connected(degraded = false)
    }

    private fun mapTestResult(dto: NzbdavTestResponseDto): NzbdavTestResult = when {
        !dto.ok -> NzbdavTestResult.Failed
        dto.degraded -> NzbdavTestResult.DegradedOk
        else -> NzbdavTestResult.Ok
    }

    /** Test hook — commonly the test body awaits the init-load to finish. */
    internal fun scopeForTest(): CoroutineScope = scope
}

// ── State model ─────────────────────────────────────────────────────────

data class NzbdavSetupUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true,
    val status: NzbdavStatus = NzbdavStatus.Loading,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val isRemoving: Boolean = false,
    /**
     * Transient inline feedback from the last user-triggered action.
     * Null when idle; Settings UI may auto-clear after a short delay,
     * or on form edit.
     */
    val lastTestResult: NzbdavTestResult? = null,
)

/**
 * User-facing connection status. Exactly four terminal values + Loading.
 * No variant carries a raw backend reason string.
 */
sealed interface NzbdavStatus {
    object Loading : NzbdavStatus
    object NotConfigured : NzbdavStatus
    /**
     * `degraded = true` maps to the "Connected, but backend reported
     * degraded status" copy. The exact policy behind the degraded flag
     * (upstream version floor, health-check fail, …) is deliberately
     * not exposed — the card shows only the neutral label.
     */
    data class Connected(val degraded: Boolean) : NzbdavStatus
    object ConnectionFailed : NzbdavStatus
}

/**
 * Transient per-action feedback for the test / save / remove buttons.
 * Separate from [NzbdavStatus] because Settings typically shows the
 * persisted status alongside a brief inline note about the last
 * action — a Test success shouldn't overwrite a Connected-degraded
 * status line, for example.
 */
sealed interface NzbdavTestResult {
    object Ok : NzbdavTestResult
    object DegradedOk : NzbdavTestResult
    object Failed : NzbdavTestResult
    object MissingFields : NzbdavTestResult
    object Saved : NzbdavTestResult
    object Removed : NzbdavTestResult
}
