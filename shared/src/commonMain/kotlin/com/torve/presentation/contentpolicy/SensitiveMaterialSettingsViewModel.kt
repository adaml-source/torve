package com.torve.presentation.contentpolicy

import com.torve.domain.model.ContentAgeBand
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.LOCKED_CONTENT_MESSAGE
import com.torve.presentation.error.UserFacingError
import com.torve.presentation.error.defaultMessage
import com.torve.data.contentpolicy.ContentPolicyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SensitiveMaterialSettingsStep {
    OVERVIEW,
    ENTER_DOB,
    CONFIRM_ENABLE,
}

data class SensitiveMaterialSettingsUiState(
    val policy: ContentPolicyState = ContentPolicyState.lockedBootstrap(enforcementEnabled = true),
    val step: SensitiveMaterialSettingsStep = SensitiveMaterialSettingsStep.OVERVIEW,
    val dobInput: String = "",
    val isWorking: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val canOfferEnableAction: Boolean
        get() = policy.enforcementEnabled &&
            policy.isSignedIn &&
            policy.ageBand != ContentAgeBand.UNDER_18 &&
            (policy.adultEligible || policy.ageBand == ContentAgeBand.UNKNOWN)

    val statusSummary: String
        get() = when {
            !policy.enforcementEnabled -> "Unavailable on this build."
            !policy.isSignedIn -> LOCKED_CONTENT_MESSAGE
            policy.ageBand == ContentAgeBand.UNDER_18 -> "This setting is not available for this account."
            policy.adultEnabled -> "Sensitive material is enabled."
            policy.adultEligible -> "Sensitive material is off."
            else -> "Additional verification is required to change this setting."
        }
}

class SensitiveMaterialSettingsViewModel(
    private val repository: ContentPolicyRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SensitiveMaterialSettingsUiState())
    val state: StateFlow<SensitiveMaterialSettingsUiState> = _state.asStateFlow()

    init {
        scope.launch {
            repository.state.collectLatest { policy ->
                _state.update {
                    it.copy(
                        policy = policy,
                        isWorking = false,
                        error = null,
                    )
                }
            }
        }
    }

    fun beginEnableFlow() {
        _state.update {
            it.copy(
                step = SensitiveMaterialSettingsStep.ENTER_DOB,
                error = null,
                notice = null,
            )
        }
    }

    fun updateDobInput(value: String) {
        _state.update { it.copy(dobInput = value, error = null) }
    }

    fun cancelFlow() {
        _state.update {
            it.copy(
                step = SensitiveMaterialSettingsStep.OVERVIEW,
                dobInput = "",
                error = null,
            )
        }
    }

    fun submitDob() {
        val dob = _state.value.dobInput.trim()
        if (!DOB_REGEX.matches(dob)) {
            _state.update { it.copy(error = "Enter a valid date in YYYY-MM-DD format.") }
            return
        }
        scope.launch {
            _state.update { it.copy(isWorking = true, error = null) }
            runCatching { repository.submitDob(dob) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            isWorking = false,
                            step = SensitiveMaterialSettingsStep.CONFIRM_ENABLE,
                            notice = "Date of birth saved.",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isWorking = false,
                            error = error.message ?: UserFacingError.SYNC_FAILED.defaultMessage(),
                        )
                    }
                }
        }
    }

    fun confirmEnable() {
        scope.launch {
            _state.update { it.copy(isWorking = true, error = null) }
            // Pin the consent to the policy version the user was shown.
            // If the snapshot is missing it, refresh once before bailing —
            // better a slight delay than a 422 from the server.
            val policyVersion = _state.value.policy.currentPolicyVersion
                ?: run {
                    runCatching { repository.refresh() }
                    _state.value.policy.currentPolicyVersion
                }
            if (policyVersion.isNullOrBlank()) {
                _state.update {
                    it.copy(
                        isWorking = false,
                        step = SensitiveMaterialSettingsStep.OVERVIEW,
                        error = "Couldn't load the current policy text. Try again.",
                    )
                }
                return@launch
            }
            runCatching { repository.enableSensitive(policyVersion) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            isWorking = false,
                            step = SensitiveMaterialSettingsStep.OVERVIEW,
                            dobInput = "",
                            notice = "Sensitive material is enabled.",
                        )
                    }
                }
                .onFailure { error ->
                    // 403 not_adult_eligible → route to DOB entry rather
                    // than surfacing a confusing generic error.
                    if (error is com.torve.data.contentpolicy.ContentPolicyNotAdultEligibleException) {
                        _state.update {
                            it.copy(
                                isWorking = false,
                                step = SensitiveMaterialSettingsStep.ENTER_DOB,
                                error = "Verify your date of birth first.",
                            )
                        }
                        return@onFailure
                    }
                    _state.update {
                        it.copy(
                            isWorking = false,
                            step = SensitiveMaterialSettingsStep.OVERVIEW,
                            error = error.message ?: "This setting could not be updated.",
                        )
                    }
                }
        }
    }

    fun disableSensitive() {
        scope.launch {
            _state.update { it.copy(isWorking = true, error = null) }
            runCatching { repository.disableSensitive() }
                .onSuccess {
                    _state.update {
                        it.copy(
                            isWorking = false,
                            step = SensitiveMaterialSettingsStep.OVERVIEW,
                            dobInput = "",
                            notice = "Sensitive material is disabled.",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isWorking = false,
                            error = error.message ?: "This setting could not be updated.",
                        )
                    }
                }
        }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    private companion object {
        val DOB_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
