package com.torve.presentation.beta

import com.torve.domain.beta.BetaApplicationStatus
import com.torve.domain.beta.BetaBlockedReason
import com.torve.domain.beta.BetaGrantStatus

data class BetaProgramUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isGeneratingCode: Boolean = false,
    val generatedCode: String? = null,
    val generatedCodeExpiresAt: String? = null,
    val applicationStatus: BetaApplicationStatus = BetaApplicationStatus.NONE,
    val betaGrantStatus: BetaGrantStatus = BetaGrantStatus.NONE,
    val betaAccessActive: Boolean = false,
    val betaAccessExpiresAt: String? = null,
    val daysRemaining: Int? = null,
    val canApply: Boolean = false,
    val blockedReason: BetaBlockedReason = BetaBlockedReason.AUTH_REQUIRED,
    val isEmailVerificationRequired: Boolean = false,
    val signupCloseAt: String? = null,
    val freeAccessEndAt: String? = null,
    val discordInviteUrl: String? = null,
    val errorMessage: String? = null,
    val copySuccess: Boolean = false,
    val openDiscordAvailable: Boolean = false,
    val lastUpdatedAt: Long? = null,
    val isSignedIn: Boolean = false,
    val isEmailVerified: Boolean = false,
    val primaryBadge: String = "Sign in required",
    val title: String = "Torve Beta Program",
    val body: String = "Sign in to apply for the Torve Beta Program.",
    val primaryActionLabel: String = "Sign In",
    val secondaryActionLabel: String? = null,
    val showGenerateCode: Boolean = false,
    val showVerifyEmail: Boolean = false,
    val showRefresh: Boolean = false,
    val showCopyCode: Boolean = false,
    val showOpenDiscord: Boolean = false,
)

data class BetaProgramSettingsCardState(
    val title: String = "Torve Beta Program",
    val subtitle: String = "Join optional testing for upcoming Torve builds.",
    val badge: String? = null,
)

object BetaProgramCopy {
    const val SETTINGS_TITLE = "Torve Beta Program"
    const val SETTINGS_DEFAULT_SUBTITLE = "Join optional testing for upcoming Torve builds."
    const val INTRO = "Want to test upcoming builds? Apply for the Torve Beta Program."
    const val DETAIL_INTRO = "Generate a one-time link code here, then paste it into the Apply for Beta form in the Torve Discord."
    const val DEADLINE = "Beta testing is optional and does not change access to Torve features."
    const val EMAIL_VERIFICATION = "Beta applications require a verified Torve account email."
    const val PREMIUM_TESTER_APPLICATION = "You can apply to test upcoming beta builds and features."
    const val FREE_PREMIUM_NON_PREMIUM_ONLY = "Beta builds are only for approved testers and may be unstable."
    const val DISCORD_INSTRUCTION = "Open the Torve Discord, go to #beta-info, press Apply for Beta, and paste this code."
    const val SAFETY = "Do not share credentials, playlist links, provider names, tokens, passwords, or private account details in Discord."
}
