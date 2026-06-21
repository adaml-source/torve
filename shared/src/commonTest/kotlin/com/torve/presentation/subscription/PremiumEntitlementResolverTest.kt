package com.torve.presentation.subscription

import com.torve.domain.model.SubscriptionTier
import com.torve.domain.subscription.PremiumEntitlementRecord
import com.torve.domain.subscription.resolvePremiumEntitlement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PremiumEntitlementResolverTest {

    @Test
    fun lifetimeWinsWhenBothMonthlyAndLifetimeExist() {
        val resolved = resolvePremiumEntitlement(
            records = listOf(
                PremiumEntitlementRecord(
                    key = "torve_pro_monthly",
                    status = "active",
                    sourceStore = "google_play",
                    endsAt = "2026-04-24T00:00:00Z",
                ),
                PremiumEntitlementRecord(
                    key = "torve_pro_lifetime",
                    status = "active",
                    sourceStore = "amazon",
                ),
            ),
            nowEpochMs = 1_710_000_000_000L,
        )

        assertTrue(resolved.hasEntitlement)
        assertEquals(SubscriptionTier.LIFETIME, resolved.tier)
        assertEquals("amazon", resolved.sourceStore)
    }

    @Test
    fun monthlyEntitlementRetainsExpiryDate() {
        val resolved = resolvePremiumEntitlement(
            records = listOf(
                PremiumEntitlementRecord(
                    key = "torve_pro_monthly",
                    status = "active",
                    sourceStore = "google_play",
                    endsAt = "2026-04-24T00:00:00Z",
                ),
            ),
            nowEpochMs = 1_710_000_000_000L,
        )

        assertTrue(resolved.hasEntitlement)
        assertEquals(SubscriptionTier.MONTHLY, resolved.tier)
        assertEquals("google_play", resolved.sourceStore)
        assertEquals(1_776_988_800_000L, resolved.expiresAtEpochMs)
    }

    @Test
    fun expiredMonthlyEntitlementFallsBackToFree() {
        val resolved = resolvePremiumEntitlement(
            records = listOf(
                PremiumEntitlementRecord(
                    key = "torve_pro_monthly",
                    status = "active",
                    sourceStore = "google_play",
                    endsAt = "2024-01-01T00:00:00Z",
                ),
            ),
            nowEpochMs = 1_710_000_000_000L,
        )

        assertFalse(resolved.hasEntitlement)
        assertEquals(SubscriptionTier.FREE, resolved.tier)
    }
}
