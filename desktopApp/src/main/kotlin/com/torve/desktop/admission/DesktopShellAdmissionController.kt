package com.torve.desktop.admission

import com.torve.desktop.auth.DesktopAuthController
import com.torve.desktop.auth.DesktopAuthPhase
import com.torve.desktop.auth.DesktopAuthUiState
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.session.AccountSessionCoordinator
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
    private val accountSessionCoordinator: AccountSessionCoordinator,
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
            // Persist any IPTV playlist draft the user typed into the
            // (now legacy) wizard. Harmless when the flow no longer
            // surfaces that step — `persistPlaylistDraft` is a no-op
            // when the draft fields are empty.
            persistPlaylistDraft(setupState)

            // Zero-source admission: per the onboarding simplification
            // plan (docs/onboarding-simplification-plan.md, Fix A), a
            // user who skips source setup can still enter Torve. They
            // get the Home empty-state with a "Set up sources" CTA;
            // built-in addons + Plex / Jellyfin auto-discovery still
            // work without any explicit source config. The previous
            // gate ("Connect a debrid provider or add an IPTV
            // playlist before entering desktop") was the main thing
            // forcing the four-card "pick a category" friction the
            // assessment kept calling out.
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

        // Email verification gate — mirrors Android's
        // VerifyEmailGateScreen routing in NavGraph.kt:1646. A signed-
        // in user with isVerified == false must confirm their email
        // before reaching Onboarding or Main. AuthClient already
        // auto-starts the SSE listener for EMAIL_VERIFIED events when
        // the auth state transitions to "signed in but unverified",
        // so the screen just renders status + Resend / "I've
        // confirmed" controls.
        if (!authState.user.isVerified) {
            return DesktopShellState.VerifyEmail(authState)
        }

        val admission = buildAdmissionSnapshot(
            userId = authState.user.id,
            settingsState = settingsState,
            channelsState = channelsState,
        )

        // Zero-source admission: onboarding completion alone admits
        // the user to Main. The Home empty-state surfaces a "Set up
        // sources" CTA for users who skip during onboarding. The
        // previous gate also required a usable playback path, which
        // forced the four-card "pick a category" friction even
        // though the rest of Torve (addons, Plex auto-discovery)
        // works without any explicit source config.
        if (admission.onboardingCompleted) {
            if (!admission.wasPreviouslyAdmitted) {
                prefsRepo.setString(shellAdmittedKey(authState.user.id), "true")
            }
            return DesktopShellState.Main(
                authState = authState,
                admission = admission.copy(wasPreviouslyAdmitted = true),
            )
        }

        // A previously-admitted user landing in this code path means
        // their onboarding flag got cleared somehow (manual prefs
        // wipe, account switch). Re-admit them in recovery mode so
        // they aren't dumped back into the onboarding shell.
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

        val hasVodPlaybackPath = settingsState.debridConnected
        val hasLivePlaybackPath = channelsState.playlists.isNotEmpty()

        // Returning-user fast-path: if AccountSessionCoordinator's
        // post-sign-in restore has produced any actual setup state
        // (a debrid connection or a synced playlist), there's no
        // point dragging this user through onboarding again on a
        // fresh device. They already finished it on their other
        // device; the sync brought their credentials over. Auto-mark
        // onboarding as completed so the admission flow advances
        // straight into Main without the user clicking "Skip".
        val restoredFromBackend = hasVodPlaybackPath || hasLivePlaybackPath
        val onboardingCompleted =
            userScopedOnboardingCompleted || sharedSetupCompleted || restoredFromBackend
        if (onboardingCompleted && !userScopedOnboardingCompleted) {
            prefsRepo.setString(onboardingCompletedKey(userId), "true")
        }
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
        val playlist = channelRepository.addPlaylist(
            name = name,
            url = url,
        )
        accountSessionCoordinator.savePlaylistToBackend(
            playlistId = playlist.id,
            name = playlist.name,
            url = playlist.url,
            playlistType = "m3u",
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
