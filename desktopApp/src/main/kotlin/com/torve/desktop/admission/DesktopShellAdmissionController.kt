package com.torve.desktop.admission

import com.torve.desktop.auth.DesktopAuthController
import com.torve.desktop.auth.DesktopAuthPhase
import com.torve.desktop.auth.DesktopAuthUiState
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.settings.SettingsUiState
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.setup.SetupUiState
import com.torve.presentation.setup.SetupWizardViewModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DesktopShellAdmissionController(
    private val authController: DesktopAuthController,
    private val settingsViewModel: SettingsViewModel,
    private val channelsViewModel: ChannelsViewModel,
    private val setupWizardViewModel: SetupWizardViewModel,
    private val prefsRepo: PreferencesRepository,
    private val channelRepository: ChannelRepository,
) {
    companion object {
        private const val KEY_DESKTOP_ONBOARDING_COMPLETED_PREFIX = "desktop_onboarding_completed_"
        private const val KEY_DESKTOP_SHELL_ADMITTED_PREFIX = "desktop_shell_admitted_"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)
    private val refreshSignals = MutableStateFlow(0)

    private val _state = MutableStateFlow<DesktopShellState>(DesktopShellState.Resolving)
    val state: StateFlow<DesktopShellState> = _state.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(
                authController.state,
                settingsViewModel.state,
                channelsViewModel.state,
                refreshSignals,
            ) { authState, settingsState, channelsState, _ ->
                Triple(authState, settingsState, channelsState)
            }.collectLatest { (authState, settingsState, channelsState) ->
                _state.value = buildShellState(
                    authState = authState,
                    settingsState = settingsState,
                    channelsState = channelsState,
                )
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    fun refreshAdmission() {
        refreshSignals.update { it + 1 }
    }

    suspend fun completeOnboarding(): Result<Unit> {
        val authState = authController.state.value
        val user = authState.user ?: return Result.failure(
            IllegalStateException("Sign in before completing desktop setup."),
        )
        val setupState = setupWizardViewModel.state.value

        return runCatching {
            val persistedPlaylist = persistPlaylistDraft(setupState)
            val willHaveVodPath = settingsViewModel.state.value.debridConnected || setupState.debridConnected
            val willHaveLivePath = channelsViewModel.state.value.playlists.isNotEmpty() || persistedPlaylist

            if (!willHaveVodPath && !willHaveLivePath) {
                throw IllegalStateException(
                    "Connect a debrid provider or add an IPTV playlist before entering desktop.",
                )
            }

            setupWizardViewModel.completeSetupNow()
            prefsRepo.setString(onboardingCompletedKey(user.id), "true")
            settingsViewModel.refreshSettings()
            channelsViewModel.loadPlaylists()
            refreshAdmission()
        }
    }

    private suspend fun buildShellState(
        authState: DesktopAuthUiState,
        settingsState: SettingsUiState,
        channelsState: ChannelsUiState,
    ): DesktopShellState {
        if (authState.user == null && authState.phase == DesktopAuthPhase.RESTORING_SESSION) {
            return DesktopShellState.Resolving
        }
        if (authState.user == null) {
            return DesktopShellState.SignedOut(authState)
        }

        val admission = buildAdmissionSnapshot(
            userId = authState.user.id,
            settingsState = settingsState,
            channelsState = channelsState,
        )

        if (admission.onboardingCompleted && admission.hasUsablePlaybackPath) {
            if (!admission.wasPreviouslyAdmitted) {
                prefsRepo.setString(shellAdmittedKey(authState.user.id), "true")
            }
            return DesktopShellState.Main(
                authState = authState,
                admission = admission.copy(wasPreviouslyAdmitted = true),
            )
        }

        if (admission.wasPreviouslyAdmitted) {
            return DesktopShellState.Main(
                authState = authState,
                admission = admission.copy(configurationStatus = DesktopConfigurationStatus.RECOVERY_REQUIRED),
            )
        }

        return DesktopShellState.Onboarding(
            authState = authState,
            admission = admission,
        )
    }

    private suspend fun buildAdmissionSnapshot(
        userId: String,
        settingsState: SettingsUiState,
        channelsState: ChannelsUiState,
    ): DesktopAdmissionSnapshot {
        val sharedSetupCompleted = prefsRepo.getString(SetupWizardViewModel.KEY_SETUP_COMPLETED) == "true"
        val userScopedOnboardingCompleted = prefsRepo.getString(onboardingCompletedKey(userId)) == "true"
        val onboardingCompleted = userScopedOnboardingCompleted || sharedSetupCompleted
        if (onboardingCompleted && !userScopedOnboardingCompleted) {
            prefsRepo.setString(onboardingCompletedKey(userId), "true")
        }

        val hasVodPlaybackPath = settingsState.debridConnected
        val hasLivePlaybackPath = channelsState.playlists.isNotEmpty()
        val playbackPathStatus = when {
            hasVodPlaybackPath && hasLivePlaybackPath -> DesktopPlaybackPathStatus.COMBINED
            hasVodPlaybackPath -> DesktopPlaybackPathStatus.VOD_ONLY
            hasLivePlaybackPath -> DesktopPlaybackPathStatus.LIVE_ONLY
            else -> DesktopPlaybackPathStatus.NONE
        }
        val wasPreviouslyAdmitted = prefsRepo.getString(shellAdmittedKey(userId)) == "true"

        val configurationStatus = when {
            onboardingCompleted && hasVodPlaybackPath -> DesktopConfigurationStatus.FULLY_CONFIGURED
            onboardingCompleted && playbackPathStatus != DesktopPlaybackPathStatus.NONE ->
                DesktopConfigurationStatus.MINIMALLY_CONFIGURED
            wasPreviouslyAdmitted -> DesktopConfigurationStatus.RECOVERY_REQUIRED
            else -> DesktopConfigurationStatus.UNCONFIGURED
        }

        val missingRequirements = buildSet {
            if (!onboardingCompleted) {
                add(DesktopAdmissionRequirement.ONBOARDING_COMPLETED)
            }
            if (playbackPathStatus == DesktopPlaybackPathStatus.NONE) {
                add(DesktopAdmissionRequirement.PLAYBACK_PATH)
            }
        }

        return DesktopAdmissionSnapshot(
            configurationStatus = configurationStatus,
            playbackPathStatus = playbackPathStatus,
            onboardingCompleted = onboardingCompleted,
            sharedSetupCompleted = sharedSetupCompleted,
            wasPreviouslyAdmitted = wasPreviouslyAdmitted,
            hasVodPlaybackPath = hasVodPlaybackPath,
            hasLivePlaybackPath = hasLivePlaybackPath,
            missingRequirements = missingRequirements,
        )
    }

    private suspend fun persistPlaylistDraft(
        setupState: SetupUiState,
    ): Boolean {
        return when (setupState.channelPlaylistType.lowercase()) {
            "xtream" -> persistXtreamPlaylist(setupState)
            else -> persistM3uPlaylist(setupState)
        }
    }

    private suspend fun persistM3uPlaylist(
        setupState: SetupUiState,
    ): Boolean {
        val url = setupState.channelPlaylistUrl.trim()
        val name = setupState.channelPlaylistName.trim().ifBlank { "Live TV Playlist" }
        val hasDraft = url.isNotBlank() || setupState.channelPlaylistName.isNotBlank()
        if (!hasDraft) return false
        if (url.isBlank()) {
            throw IllegalStateException("Enter a playlist URL or skip Live TV for now.")
        }
        channelRepository.addPlaylist(
            name = name,
            url = url,
        )
        return true
    }

    private suspend fun persistXtreamPlaylist(
        setupState: SetupUiState,
    ): Boolean {
        val server = setupState.channelXtreamServer.trim().trimEnd('/')
        val username = setupState.channelXtreamUsername.trim()
        val password = setupState.channelXtreamPassword
        val name = setupState.channelPlaylistName.trim().ifBlank { "Live TV Provider" }
        val hasDraft = listOf(
            setupState.channelPlaylistName,
            setupState.channelXtreamServer,
            setupState.channelXtreamUsername,
            setupState.channelXtreamPassword,
        ).any { it.isNotBlank() }
        if (!hasDraft) return false
        if (server.isBlank() || username.isBlank() || password.isBlank()) {
            throw IllegalStateException("Complete provider login fields or skip Live TV for now.")
        }
        channelRepository.addXtreamPlaylist(
            name = name,
            server = server,
            username = username,
            password = password,
        )
        return true
    }

    private fun onboardingCompletedKey(userId: String): String {
        val stableUserId = userId.ifBlank { "anonymous" }
        return "$KEY_DESKTOP_ONBOARDING_COMPLETED_PREFIX$stableUserId"
    }

    private fun shellAdmittedKey(userId: String): String {
        val stableUserId = userId.ifBlank { "anonymous" }
        return "$KEY_DESKTOP_SHELL_ADMITTED_PREFIX$stableUserId"
    }
}
