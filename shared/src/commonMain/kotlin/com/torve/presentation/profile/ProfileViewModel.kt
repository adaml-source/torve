package com.torve.presentation.profile

import com.torve.domain.model.ContentRating
import com.torve.domain.model.UserProfile
import com.torve.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

class ProfileViewModel(
    private val profileRepo: ProfileRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    private val failedPinAttempts = mutableMapOf<String, Int>()
    private val pinLockedUntilMs = mutableMapOf<String, Long>()

    init {
        loadProfiles()
    }

    fun loadProfiles() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val profiles = profileRepo.getAllProfiles()
                val active = profiles.find { it.isActive }

                // Auto-create default profile if none exist
                if (profiles.isEmpty()) {
                    val defaultProfile = UserProfile(
                        id = "default",
                        name = "Default",
                        avatarIndex = 0,
                        isActive = true,
                        createdAt = Clock.System.now().toEpochMilliseconds(),
                    )
                    profileRepo.createProfile(defaultProfile)
                    _state.update {
                        it.copy(
                            profiles = listOf(defaultProfile),
                            activeProfile = defaultProfile,
                            isLoading = false,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            profiles = profiles,
                            activeProfile = active,
                            isLoading = false,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = com.torve.presentation.error.UserFacingError.PROFILE_FAILED.messageKey) }
            }
        }
    }

    fun createProfile(name: String, avatarIndex: Int = 0) {
        scope.launch {
            try {
                val safeName = name.trim()
                if (safeName.isBlank()) {
                    _state.update { it.copy(error = "Enter a profile name") }
                    return@launch
                }
                if (_state.value.profiles.size >= MAX_PROFILES) {
                    _state.update { it.copy(error = "You can create up to $MAX_PROFILES profiles") }
                    return@launch
                }
                val id = nowMs().toString()
                val profile = UserProfile(
                    id = id,
                    name = safeName.take(MAX_PROFILE_NAME_LENGTH),
                    avatarIndex = avatarIndex,
                    createdAt = nowMs(),
                )
                profileRepo.createProfile(profile)
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.PROFILE_FAILED.messageKey) }
            }
        }
    }

    fun switchProfile(id: String) {
        scope.launch {
            try {
                val profile = profileRepo.getProfile(id) ?: return@launch
                // Check if PIN is required
                if (!profile.pin.isNullOrBlank()) {
                    val remainingMs = (pinLockedUntilMs[id] ?: 0L) - nowMs()
                    _state.update {
                        it.copy(
                            pinPromptProfileId = id,
                            pinError = if (remainingMs > 0L) pinLockMessage(remainingMs) else null,
                        )
                    }
                    return@launch
                }
                profileRepo.setActiveProfile(id)
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.PROFILE_FAILED.messageKey) }
            }
        }
    }

    fun verifyPinAndSwitch(profileId: String, pin: String) {
        scope.launch {
            try {
                val profile = profileRepo.getProfile(profileId) ?: return@launch
                val remainingMs = (pinLockedUntilMs[profileId] ?: 0L) - nowMs()
                if (remainingMs > 0L) {
                    _state.update { it.copy(pinError = pinLockMessage(remainingMs)) }
                    return@launch
                }
                if (profile.pin == pin.trim()) {
                    failedPinAttempts.remove(profileId)
                    pinLockedUntilMs.remove(profileId)
                    _state.update { it.copy(pinPromptProfileId = null, pinError = null) }
                    profileRepo.setActiveProfile(profileId)
                    loadProfiles()
                } else {
                    val attempts = (failedPinAttempts[profileId] ?: 0) + 1
                    if (attempts >= MAX_PIN_ATTEMPTS) {
                        failedPinAttempts.remove(profileId)
                        pinLockedUntilMs[profileId] = nowMs() + PIN_LOCKOUT_MS
                        _state.update { it.copy(pinError = pinLockMessage(PIN_LOCKOUT_MS)) }
                    } else {
                        failedPinAttempts[profileId] = attempts
                        _state.update {
                            it.copy(pinError = "Incorrect PIN. ${MAX_PIN_ATTEMPTS - attempts} attempts remaining")
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.PROFILE_FAILED.messageKey) }
            }
        }
    }

    fun dismissPinPrompt() {
        _state.update { it.copy(pinPromptProfileId = null, pinError = null) }
    }

    fun updateProfileName(id: String, name: String) {
        scope.launch {
            try {
                val safeName = name.trim()
                if (safeName.isBlank()) {
                    _state.update { it.copy(error = "Enter a profile name") }
                    return@launch
                }
                profileRepo.updateName(id, safeName.take(MAX_PROFILE_NAME_LENGTH))
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.PROFILE_FAILED.messageKey) }
            }
        }
    }

    fun setProfilePin(id: String, pin: String?) {
        scope.launch {
            try {
                val safePin = pin?.trim()?.takeIf(String::isNotBlank)
                if (safePin != null && !safePin.matches(Regex("[0-9]{4}"))) {
                    _state.update { it.copy(error = "PIN must be exactly 4 digits") }
                    return@launch
                }
                profileRepo.updatePin(id, safePin)
                failedPinAttempts.remove(id)
                pinLockedUntilMs.remove(id)
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.PROFILE_FAILED.messageKey) }
            }
        }
    }

    fun setContentRating(id: String, rating: ContentRating?) {
        scope.launch {
            try {
                profileRepo.updateContentRating(id, rating)
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.PROFILE_FAILED.messageKey) }
            }
        }
    }

    fun deleteProfile(id: String) {
        scope.launch {
            try {
                // Don't delete the last profile
                if (_state.value.profiles.size <= 1) return@launch
                val wasActive = _state.value.activeProfile?.id == id
                profileRepo.deleteProfile(id)
                if (wasActive) {
                    val remaining = profileRepo.getAllProfiles()
                    remaining.firstOrNull()?.let {
                        profileRepo.setActiveProfile(it.id)
                    }
                }
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.PROFILE_FAILED.messageKey) }
            }
        }
    }

    fun showEditDialog(profile: UserProfile) {
        _state.update { it.copy(editingProfile = profile) }
    }

    fun dismissEditDialog() {
        _state.update { it.copy(editingProfile = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun pinLockMessage(remainingMs: Long): String {
        val seconds = ((remainingMs + 999L) / 1_000L).coerceAtLeast(1L)
        return "Too many incorrect attempts. Try again in $seconds seconds"
    }

    companion object {
        const val MAX_PROFILES = 8
        const val MAX_PROFILE_NAME_LENGTH = 32
        const val MAX_PIN_ATTEMPTS = 5
        const val PIN_LOCKOUT_MS = 30_000L
    }
}

data class ProfileUiState(
    val profiles: List<UserProfile> = emptyList(),
    val activeProfile: UserProfile? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val pinPromptProfileId: String? = null,
    val pinError: String? = null,
    val editingProfile: UserProfile? = null,
)
