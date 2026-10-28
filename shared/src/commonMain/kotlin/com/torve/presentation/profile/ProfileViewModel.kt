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
    private val family: com.torve.data.profile.FamilyControls? = null,
) {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    private val failedPinAttempts = mutableMapOf<String, Int>()
    private val pinLockedUntilMs = mutableMapOf<String, Long>()
    private val managementGrants = mutableSetOf<String>()

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
        if (_state.value.isSavingProfile) return
        _state.update { it.copy(isSavingProfile = true, error = null) }
        scope.launch {
            try {
                family?.requireParent()
                val safeName = name.trim()
                if (safeName.isBlank()) {
                    _state.update { it.copy(error = "Enter a profile name") }
                    return@launch
                }
                if (_state.value.profiles.size >= MAX_PROFILES) {
                    _state.update { it.copy(error = "You can create up to $MAX_PROFILES profiles") }
                    return@launch
                }
                val id = nowMs().toString(36) + kotlin.random.Random.nextInt(Int.MAX_VALUE).toString(36).padStart(6, '0')
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
            } finally {
                _state.update { it.copy(isSavingProfile = false) }
            }
        }
    }

    fun switchProfile(id: String) {
        scope.launch {
            try {
                val profile = profileRepo.getProfile(id) ?: return@launch
                if (profile.pin == com.torve.data.profile.HouseholdSyncService.REMOTE_PIN_REQUIRED) {
                    _state.update { it.copy(error = "Connect to your Torve account to load this profile's PIN") }
                    return@launch
                }
                if (family?.state?.value?.configuration?.enabled == true && id !in family.state.value.configuration.kids && !family.canManage()) {
                    _state.update { it.copy(error = "Enter the parent PIN in Settings → Profiles & Family before switching to an adult profile.") }
                    return@launch
                }
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
                if (profile.pin == pin.trim() && profile.pin != com.torve.data.profile.HouseholdSyncService.REMOTE_PIN_REQUIRED) {
                    failedPinAttempts.remove(profileId)
                    pinLockedUntilMs.remove(profileId)
                    if (_state.value.pinPromptForManagement) {
                        managementGrants += profileId
                        _state.update { it.copy(pinPromptProfileId = null, pinError = null,
                            pinPromptForManagement = false, editingProfile = profile) }
                    } else {
                        _state.update { it.copy(pinPromptProfileId = null, pinError = null) }
                        profileRepo.setActiveProfile(profileId)
                        loadProfiles()
                    }
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
        _state.update { it.copy(pinPromptProfileId = null, pinError = null, pinPromptForManagement = false) }
    }

    fun updateProfileName(id: String, name: String) {
        scope.launch {
            try {
                if (!canManage(id)) return@launch
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
                if (!canManage(id)) return@launch
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
                if (!canManage(id)) return@launch
                profileRepo.updateContentRating(id, rating)
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.PROFILE_FAILED.messageKey) }
            }
        }
    }

    fun deleteProfile(id: String) {
        if (_state.value.isSavingProfile) return
        _state.update { it.copy(isSavingProfile = true, error = null) }
        scope.launch {
            try {
                if (id == "default" || !canManage(id)) return@launch
                // Don't delete the last profile
                if (_state.value.profiles.size <= 1) return@launch
                val wasActive = _state.value.activeProfile?.id == id
                if (wasActive) {
                    _state.update { it.copy(error = "Switch to another profile before deleting this one") }
                    return@launch
                }
                profileRepo.deleteProfile(id)
                managementGrants.remove(id)
                _state.update { it.copy(editingProfile = null) }
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = com.torve.presentation.error.UserFacingError.PROFILE_FAILED.messageKey) }
            } finally {
                _state.update { it.copy(isSavingProfile = false) }
            }
        }
    }

    fun showEditDialog(profile: UserProfile) {
        requestProfileManagement(profile.id)
    }

    fun requestProfileManagement(id: String) {
        scope.launch {
            val profile = profileRepo.getProfile(id) ?: return@launch
            if (profile.pin == com.torve.data.profile.HouseholdSyncService.REMOTE_PIN_REQUIRED) {
                _state.update { it.copy(error = "Connect to your Torve account to load this profile's PIN") }
            } else if (!profile.pin.isNullOrBlank() && id !in managementGrants) {
                val remaining = (pinLockedUntilMs[id] ?: 0L) - nowMs()
                _state.update { it.copy(pinPromptProfileId = id, pinPromptForManagement = true,
                    pinError = if (remaining > 0) pinLockMessage(remaining) else null) }
            } else _state.update { it.copy(editingProfile = profile) }
        }
    }

    private suspend fun canManage(id: String): Boolean {
        if (family?.canManage() == false) { _state.update { it.copy(error = "Enter the parent PIN in Family controls before changing profiles.") }; return false }
        val profile = profileRepo.getProfile(id) ?: return false
        return (profile.pin.isNullOrBlank() || id in managementGrants).also { allowed ->
            if (!allowed) _state.update { it.copy(error = "Enter this profile's PIN before changing it") }
        }
    }

    fun saveProfile(id: String, name: String, rating: ContentRating?, newPin: String?, changePin: Boolean) {
        if (_state.value.isSavingProfile) return
        _state.update { it.copy(isSavingProfile = true, error = null) }
        scope.launch {
            if (!canManage(id)) { _state.update { it.copy(isSavingProfile = false) }; return@launch }
            val safeName = name.trim().take(MAX_PROFILE_NAME_LENGTH)
            val safePin = newPin?.trim()?.takeIf(String::isNotBlank)
            if (safeName.isEmpty() || (changePin && safePin != null && !safePin.matches(Regex("[0-9]{4}")))) {
                _state.update { it.copy(isSavingProfile = false, error = "Enter a name and a PIN of exactly 4 digits, or remove the PIN") }
                return@launch
            }
            try {
                profileRepo.updateName(id, safeName)
                profileRepo.updateContentRating(id, rating)
                if (changePin) profileRepo.updatePin(id, safePin)
                managementGrants.remove(id)
                _state.update { it.copy(editingProfile = null, isSavingProfile = false,
                    savedProfileRevision = it.savedProfileRevision + 1) }
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(isSavingProfile = false, error = "Could not save this profile. Try again.") }
            }
        }
    }

    fun dismissEditDialog() {
        _state.value.editingProfile?.id?.let(managementGrants::remove)
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
    val pinPromptForManagement: Boolean = false,
    val isSavingProfile: Boolean = false,
    val savedProfileRevision: Int = 0,
)
