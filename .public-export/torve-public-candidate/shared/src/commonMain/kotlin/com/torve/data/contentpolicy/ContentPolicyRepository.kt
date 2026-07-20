package com.torve.data.contentpolicy

import com.torve.data.auth.AuthClient
import com.torve.domain.model.ContentAgeBand
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface ContentPolicyRepository {
    val state: StateFlow<ContentPolicyState>

    suspend fun refresh()
    suspend fun submitDob(dateOfBirth: String)
    /**
     * Accept the current policy version and turn sensitive material on.
     * The [policyVersion] must be the `current_policy_version` from the
     * most recent [refresh] / GET — the backend uses it to pin the
     * consent to the exact text the user saw.
     */
    suspend fun enableSensitive(policyVersion: String)
    suspend fun disableSensitive()
}

class ContentPolicyRepositoryImpl(
    private val api: ContentPolicyApi,
    private val authClient: AuthClient,
    private val prefsRepo: PreferencesRepository,
    private val json: Json,
    private val channelProvider: ContentChannelProvider,
    private val invalidationCoordinator: ContentPolicyCacheInvalidationCoordinator,
) : ContentPolicyRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<ContentPolicyState> = _state.asStateFlow()

    init {
        if (!channelProvider.isGooglePlayChannel()) {
            _state.value = ContentPolicyState.unrestricted()
        } else {
            scope.launch {
                val signedIn = authClient.isLoggedIn()
                _state.value = ContentPolicyState.lockedBootstrap(
                    enforcementEnabled = true,
                    isSignedIn = signedIn,
                )
                if (signedIn) {
                    runCatching { refresh() }
                }
            }

            scope.launch {
                authClient.authUserFlow.collectLatest { user ->
                    if (!channelProvider.isGooglePlayChannel()) {
                        _state.value = ContentPolicyState.unrestricted()
                        return@collectLatest
                    }
                    val signedIn = user != null || authClient.isLoggedIn()
                    if (!signedIn) {
                        clearPersistedSnapshot()
                        _state.value = ContentPolicyState.lockedBootstrap(enforcementEnabled = true, isSignedIn = false)
                        invalidationCoordinator.invalidate(policyStateVersion = null, force = true)
                    } else {
                        refresh()
                    }
                }
            }
        }
    }

    override suspend fun refresh() {
        if (!channelProvider.isGooglePlayChannel()) {
            _state.value = ContentPolicyState.unrestricted()
            return
        }
        if (!authClient.isLoggedIn()) {
            _state.value = ContentPolicyState.lockedBootstrap(enforcementEnabled = true, isSignedIn = false)
            return
        }

        _state.update {
            it.copy(
                enforcementEnabled = true,
                isSignedIn = true,
                isLoading = true,
                lastError = null,
            )
        }

        val previousVersion = _state.value.policyStateVersion
        runCatching { api.getPolicy() }
            .onSuccess { dto ->
                val resolved = dto.toState(
                    enforcementEnabled = true,
                    isSignedIn = true,
                    previous = _state.value,
                )
                persistSnapshot(dto)
                _state.value = resolved
                if (previousVersion != resolved.policyStateVersion) {
                    invalidationCoordinator.invalidate(resolved.policyStateVersion, force = previousVersion != null)
                }
            }
            .onFailure { error ->
                invalidationCoordinator.invalidate(policyStateVersion = null, force = true)
                _state.update {
                    ContentPolicyState.lockedBootstrap(
                        enforcementEnabled = true,
                        isSignedIn = true,
                    ).copy(
                        isLoading = false,
                        lastError = error.message ?: "Content policy could not be refreshed.",
                    )
                }
            }
    }

    override suspend fun submitDob(dateOfBirth: String) {
        api.submitDob(dateOfBirth)
        refresh()
    }

    override suspend fun enableSensitive(policyVersion: String) {
        api.enableSensitive(policyVersion)
        refresh()
    }

    override suspend fun disableSensitive() {
        api.disableSensitive()
        refresh()
    }

    private fun initialState(): ContentPolicyState {
        return if (channelProvider.isGooglePlayChannel()) {
            ContentPolicyState.lockedBootstrap(enforcementEnabled = true)
        } else {
            ContentPolicyState.unrestricted()
        }
    }

    private suspend fun persistSnapshot(dto: ContentPolicyResponseDto) {
        prefsRepo.setString(
            KEY_POLICY_SNAPSHOT,
            json.encodeToString(
                PersistedContentPolicySnapshot(
                    ageBand = dto.age_band,
                    adultEligible = dto.adult_eligible,
                    sensitiveMaterialEnabled = dto.sensitive_material_enabled,
                    sensitiveMaterialPolicyVersion = dto.sensitive_material_policy_version,
                    currentPolicyVersion = dto.current_policy_version,
                    policyStateVersion = dto.policy_state_version,
                ),
            ),
        )
    }

    private suspend fun clearPersistedSnapshot() {
        prefsRepo.remove(KEY_POLICY_SNAPSHOT)
    }

    private fun ContentPolicyResponseDto.toState(
        enforcementEnabled: Boolean,
        isSignedIn: Boolean,
        previous: ContentPolicyState,
    ): ContentPolicyState = ContentPolicyState(
        enforcementEnabled = enforcementEnabled,
        isSignedIn = isSignedIn,
        isLoading = false,
        ageBand = ContentAgeBand.fromBackend(age_band),
        adultEligible = adult_eligible,
        sensitiveMaterialEnabled = sensitive_material_enabled,
        sensitiveMaterialPolicyVersion = sensitive_material_policy_version,
        currentPolicyVersion = current_policy_version,
        policyStateVersion = policy_state_version,
        lastError = null,
    )

    private companion object {
        const val KEY_POLICY_SNAPSHOT = "content_policy_snapshot_v1"
    }
}

@Serializable
private data class PersistedContentPolicySnapshot(
    val ageBand: String? = null,
    val adultEligible: Boolean = false,
    val sensitiveMaterialEnabled: Boolean = false,
    val sensitiveMaterialPolicyVersion: String? = null,
    val currentPolicyVersion: String? = null,
    val policyStateVersion: String? = null,
)
