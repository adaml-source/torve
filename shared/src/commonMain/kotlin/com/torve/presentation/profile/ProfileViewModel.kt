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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

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
                val id = Clock.System.now().toEpochMilliseconds().toString()
                val profile = UserProfile(
                    id = id,
                    name = name,
                    avatarIndex = avatarIndex,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
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
                    _state.update { it.copy(pinPromptProfileId = id) }
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
                if (profile.pin == pin) {
                    _state.update { it.copy(pinPromptProfileId = null, pinError = null) }
                    profileRepo.setActiveProfile(profileId)
                    loadProfiles()
                } else {
                    _state.update { it.copy(pinError = "Incorrect PIN") }
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
                profileRepo.updateName(id, name)
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.PROFILE_FAILED.messageKey) }
            }
        }
    }

    fun setProfilePin(id: String, pin: String?) {
        scope.launch {
            try {
                profileRepo.updatePin(id, pin?.takeIf { it.isNotBlank() })
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
