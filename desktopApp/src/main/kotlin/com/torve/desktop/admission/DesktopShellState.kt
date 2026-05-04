package com.torve.desktop.admission

import com.torve.desktop.auth.DesktopAuthUiState

enum class DesktopPlaybackPathStatus {
    NONE,
    VOD_ONLY,
    LIVE_ONLY,
    COMBINED,
}

enum class DesktopConfigurationStatus {
    UNCONFIGURED,
    MINIMALLY_CONFIGURED,
    FULLY_CONFIGURED,
    RECOVERY_REQUIRED,
}

enum class DesktopAdmissionRequirement {
    ONBOARDING_COMPLETED,
    PLAYBACK_PATH,
}

data class DesktopAdmissionSnapshot(
    val configurationStatus: DesktopConfigurationStatus,
    val playbackPathStatus: DesktopPlaybackPathStatus,
    val onboardingCompleted: Boolean,
    val sharedSetupCompleted: Boolean,
    val wasPreviouslyAdmitted: Boolean,
    val hasVodPlaybackPath: Boolean,
    val hasLivePlaybackPath: Boolean,
    val missingRequirements: Set<DesktopAdmissionRequirement>,
) {
    val hasUsablePlaybackPath: Boolean
        get() = playbackPathStatus != DesktopPlaybackPathStatus.NONE

    val requiresSetupRecovery: Boolean
        get() = configurationStatus == DesktopConfigurationStatus.RECOVERY_REQUIRED

    val setupRecoveryMessage: String?
        get() = if (!requiresSetupRecovery) {
            null
        } else {
            "Playback setup needs attention. Reconnect a VOD source or add an IPTV playlist in Settings to restore watch flow."
        }
}

sealed interface DesktopShellState {
    data object Resolving : DesktopShellState

    data class SignedOut(
        val authState: DesktopAuthUiState,
    ) : DesktopShellState

    /**
     * Signed in but `isVerified == false`. Mirrors Android's
     * VerifyEmailGateScreen behaviour: the user must confirm their
     * email before reaching Onboarding or Main. The shell shows a
     * dedicated "confirm your email" surface with Resend / I've
     * confirmed buttons; the SSE EMAIL_VERIFIED event auto-advances
     * the user once the inbox link is clicked.
     */
    data class VerifyEmail(
        val authState: DesktopAuthUiState,
    ) : DesktopShellState

    data class Onboarding(
        val authState: DesktopAuthUiState,
        val admission: DesktopAdmissionSnapshot,
    ) : DesktopShellState

    data class Main(
        val authState: DesktopAuthUiState,
        val admission: DesktopAdmissionSnapshot,
    ) : DesktopShellState
}
