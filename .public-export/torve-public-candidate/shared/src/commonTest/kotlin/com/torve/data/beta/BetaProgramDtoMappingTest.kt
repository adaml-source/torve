package com.torve.data.beta

import com.torve.data.device.AccessStateDto
import com.torve.data.device.resolvedHasPremiumEntitlement
import com.torve.domain.beta.BetaApplicationStatus
import com.torve.domain.beta.BetaBlockedReason
import com.torve.domain.beta.BetaGrantStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BetaProgramDtoMappingTest {
    @Test
    fun parsesAllKnownApplicationStatuses() {
        BetaApplicationStatus.entries
            .filterNot { it == BetaApplicationStatus.UNKNOWN }
            .forEach { expected ->
                val status = DiscordBetaStatusDto(
                    betaApplicationStatus = expected.wireName,
                ).toDomain(signedIn = true)

                assertEquals(expected, status.applicationStatus)
            }
    }

    @Test
    fun parsesAllKnownBetaGrantStatuses() {
        BetaGrantStatus.entries
            .filterNot { it == BetaGrantStatus.UNKNOWN }
            .forEach { expected ->
                val status = DiscordBetaStatusDto(
                    betaAccess = BetaAccessStateDto(status = expected.wireName),
                ).toDomain(signedIn = true)

                assertEquals(expected, status.betaAccess.status)
            }
    }

    @Test
    fun parsesAllKnownBlockedReasons() {
        BetaBlockedReason.entries
            .filterNot { it == BetaBlockedReason.UNKNOWN }
            .forEach { expected ->
                val status = DiscordBetaStatusDto(
                    blockedReason = expected.wireName,
                ).toDomain(signedIn = true)

                assertEquals(expected, status.blockedReason)
            }
    }

    @Test
    fun parsesKnownBetaStatusFields() {
        val status = DiscordBetaStatusDto(
            discordLinked = true,
            betaApplicationStatus = "submitted",
            betaAccessActive = true,
            betaAccessExpiresAt = "2026-07-31T21:59:59Z",
            daysRemaining = 14,
            canApply = false,
            blockedReason = "already_active",
            betaSignupCloseAt = "2026-07-01T21:59:59Z",
            betaFreeAccessEndAt = "2026-07-31T21:59:59Z",
            discordInviteUrl = "https://discord.example/invite",
            betaAccess = BetaAccessStateDto(
                active = true,
                source = "discord_beta",
                expiresAt = "2026-07-31T21:59:59Z",
                status = "active",
            ),
        ).toDomain(signedIn = true)

        assertTrue(status.discordLinked)
        assertEquals(BetaApplicationStatus.SUBMITTED, status.applicationStatus)
        assertTrue(status.betaAccessActive)
        assertEquals(BetaGrantStatus.ACTIVE, status.betaAccess.status)
        assertEquals(BetaBlockedReason.ALREADY_ACTIVE, status.blockedReason)
        assertEquals(14, status.daysRemaining)
        assertEquals("discord_beta", status.betaAccess.source)
        assertEquals("https://discord.example/invite", status.discordInviteUrl)
    }

    @Test
    fun missingOptionalDeadlineFieldsUseSafeDefaults() {
        val status = DiscordBetaStatusDto().toDomain(signedIn = true)

        assertEquals("2026-07-01T21:59:59Z", status.signupCloseAt)
        assertEquals("2026-07-31T21:59:59Z", status.freeAccessEndAt)
        assertFalse(status.betaAccessActive)
    }

    @Test
    fun unknownEnumValuesMapToUnknown() {
        val status = DiscordBetaStatusDto(
            betaApplicationStatus = "staff_queue",
            blockedReason = "some_new_block",
            betaAccess = BetaAccessStateDto(status = "paused"),
        ).toDomain(signedIn = true)

        assertEquals(BetaApplicationStatus.UNKNOWN, status.applicationStatus)
        assertEquals(BetaBlockedReason.UNKNOWN, status.blockedReason)
        assertEquals(BetaGrantStatus.UNKNOWN, status.betaAccess.status)
    }

    @Test
    fun accessStateMissingBetaAccessMapsToInactiveShape() {
        val accessState = AccessStateDto()

        assertNull(accessState.beta_access)
        assertFalse(accessState.resolvedHasPremiumEntitlement())
    }

    @Test
    fun accessStateActiveBetaAccessDoesNotChangePaidPremiumFields() {
        val accessState = AccessStateDto(
            has_premium_access = true,
            access_tier = "premium",
            beta_access = BetaAccessStateDto(
                active = true,
                source = "discord_beta",
                expiresAt = "2026-07-31T21:59:59Z",
                status = "active",
            ),
        )

        assertTrue(accessState.resolvedHasPremiumEntitlement())
        assertTrue(accessState.beta_access?.active == true)
        assertEquals("2026-07-31T21:59:59Z", accessState.beta_access?.expiresAt)
    }

    @Test
    fun canApplyIsDerivedSafelyWhenMissing() {
        val eligible = DiscordBetaStatusDto(
            betaApplicationStatus = "rejected",
            blockedReason = "none",
            canApply = null,
        ).toDomain(signedIn = true)
        val blocked = DiscordBetaStatusDto(
            betaApplicationStatus = "none",
            blockedReason = "email_not_verified",
            canApply = null,
        ).toDomain(signedIn = true)

        assertTrue(eligible.canApply)
        assertFalse(blocked.canApply)
        assertTrue(blocked.isEmailVerificationRequired)
    }
}
