package com.torve.presentation.beta

import com.torve.domain.beta.BetaAccessState
import com.torve.domain.beta.BetaApplicationStatus
import com.torve.domain.beta.BetaBlockedReason
import com.torve.domain.beta.BetaEligibilityState
import com.torve.domain.beta.BetaGrantStatus
import com.torve.domain.beta.BetaProgramError
import com.torve.domain.beta.BetaProgramStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BetaProgramPresentationMappingTest {
    @Test
    fun signedOutStateShowsSignInRequirement() {
        val ui = BetaProgramStatus.SIGNED_OUT.toUiState(BetaProgramUiState())

        assertFalse(ui.isSignedIn)
        assertEquals("Sign in required", ui.primaryBadge)
        assertEquals("Sign In", ui.primaryActionLabel)
        assertFalse(ui.showGenerateCode)
    }

    @Test
    fun emailNotVerifiedShowsVerificationAction() {
        val ui = BetaProgramStatus(
            signedIn = true,
            eligibility = BetaEligibilityState(
                canApply = false,
                blockedReason = BetaBlockedReason.EMAIL_NOT_VERIFIED,
                isEmailVerificationRequired = true,
            ),
        ).toUiState(BetaProgramUiState(), localEmailVerified = false)

        assertTrue(ui.isEmailVerificationRequired)
        assertEquals("Email verification required", ui.primaryBadge)
        assertTrue(ui.showVerifyEmail)
        assertFalse(ui.showGenerateCode)
        assertEquals("Verify Email", ui.primaryActionLabel)
    }

    @Test
    fun activeBetaShowsActiveExpiryWithoutChangingPaidState() {
        val ui = BetaProgramStatus(
            signedIn = true,
            applicationStatus = BetaApplicationStatus.APPROVED,
            betaAccess = BetaAccessState(
                active = true,
                source = "discord_beta",
                expiresAt = "2026-07-31T21:59:59Z",
                status = BetaGrantStatus.ACTIVE,
            ),
            eligibility = BetaEligibilityState(
                canApply = false,
                blockedReason = BetaBlockedReason.ALREADY_ACTIVE,
            ),
        ).toUiState(BetaProgramUiState(), localEmailVerified = true)

        assertTrue(ui.betaAccessActive)
        assertEquals(BetaGrantStatus.ACTIVE, ui.betaGrantStatus)
        assertEquals("Beta Active", ui.primaryBadge)
        assertTrue(ui.body.contains("Free beta access is active until"))
        assertFalse(ui.showGenerateCode)
    }

    @Test
    fun signupClosedAndAccessEndedUseClosedStates() {
        val closed = BetaProgramStatus(
            signedIn = true,
            eligibility = BetaEligibilityState(
                canApply = false,
                blockedReason = BetaBlockedReason.BETA_SIGNUP_CLOSED,
            ),
        ).toUiState(BetaProgramUiState(), localEmailVerified = true)
        val ended = BetaProgramStatus(
            signedIn = true,
            eligibility = BetaEligibilityState(
                canApply = false,
                blockedReason = BetaBlockedReason.BETA_ACCESS_ENDED,
            ),
        ).toUiState(BetaProgramUiState(), localEmailVerified = true)

        assertEquals("Applications Closed", closed.primaryBadge)
        assertEquals("Beta Access Ended", ended.primaryBadge)
        assertFalse(closed.showGenerateCode)
        assertFalse(ended.showGenerateCode)
    }

    @Test
    fun settingsCardReflectsPendingAndActiveStates() {
        val pending = BetaProgramStatus(
            signedIn = true,
            applicationStatus = BetaApplicationStatus.SUBMITTED,
        ).toSettingsCardState()
        val active = BetaProgramStatus(
            signedIn = true,
            betaAccess = BetaAccessState(
                active = true,
                expiresAt = "2026-07-31T21:59:59Z",
                status = BetaGrantStatus.ACTIVE,
            ),
        ).toSettingsCardState()

        assertEquals("Pending", pending.badge)
        assertEquals("Beta active", active.badge)
        assertTrue(active.subtitle.contains("Free beta access active until"))
    }

    @Test
    fun settingsEntryStaysVisibleAfterBetaSignupDates() {
        val open = BetaProgramUiState(
            isSignedIn = true,
            signupCloseAt = "2026-07-01T21:59:59Z",
        )
        val afterClose = "2026-07-02T00:00:00Z"
        val beforeClose = "2026-07-01T21:00:00Z"

        assertTrue(
            shouldShowBetaProgramSettingsEntry(
                state = open,
                hasPremiumAccess = true,
                nowEpochMs = kotlinx.datetime.Instant.parse(beforeClose).toEpochMilliseconds(),
            ),
        )
        assertTrue(
            shouldShowBetaProgramSettingsEntry(
                state = open,
                hasPremiumAccess = false,
                nowEpochMs = kotlinx.datetime.Instant.parse(afterClose).toEpochMilliseconds(),
            ),
        )
        assertTrue(
            shouldShowBetaProgramSettingsEntry(
                state = open,
                hasPremiumAccess = false,
                nowEpochMs = kotlinx.datetime.Instant.parse(beforeClose).toEpochMilliseconds(),
            ),
        )
    }

    @Test
    fun betaCopyExplainsTesterApplicationWithoutPaidGrant() {
        assertEquals(
            "You already have full access. You can still apply to test upcoming beta builds and features.",
            BetaProgramCopy.PREMIUM_TESTER_APPLICATION,
        )
        assertEquals(
            "Beta access is only for approved testers and ends July 31, 2026.",
            BetaProgramCopy.FREE_PREMIUM_NON_PREMIUM_ONLY,
        )
    }

    @Test
    fun alreadyActiveGenerationErrorDoesNotBlockTesterApplicationsByCopy() {
        assertEquals(
            "A beta application or beta access is already active for this account. Refresh status to continue.",
            mapBetaError(BetaProgramError.AlreadyActive),
        )
    }
}
