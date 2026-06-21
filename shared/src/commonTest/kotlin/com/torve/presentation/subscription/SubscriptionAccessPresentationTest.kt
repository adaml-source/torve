package com.torve.presentation.subscription

import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionAccessPresentationTest {

    @Test
    fun freeStateShowsNoBuyRestoreOrPaywallActions() {
        val state = SubscriptionUiState(
            subscription = subscription(tier = SubscriptionTier.FREE),
            isLoggedIn = true,
        )

        val access = state.accessPresentation()
        val actions = state.recommendedPremiumActions()

        assertFalse(access.hasPremiumEntitlement)
        assertTrue(access.isUsablePremiumOnThisDevice)
        assertFalse(access.shouldShowBuy)
        assertFalse(access.shouldShowBuyMonthly)
        assertFalse(access.shouldShowBuyLifetime)
        assertFalse(access.shouldShowRestore)
        assertFalse(access.shouldShowManageDevices)
        assertEquals(listOf(PremiumSurfaceAction.REFRESH_ACCESS), actions)
    }

    @Test
    fun historicalSubscriptionStateDoesNotCreatePurchaseActions() {
        val state = SubscriptionUiState(
            subscription = subscription(
                tier = SubscriptionTier.MONTHLY,
                expiresAt = 1L,
                platform = "stripe",
            ),
            isPro = true,
            hasEntitlement = false,
            isDeviceActivated = true,
            isLoggedIn = true,
        ).withDerivedBillingPolicy()

        val access = state.accessPresentation()

        assertTrue(access.isUsablePremiumOnThisDevice)
        assertFalse(access.shouldShowBuy)
        assertFalse(state.canBuyMonthly)
        assertFalse(state.canBuyLifetime)
        assertFalse(state.canManageStripeBilling)
    }

    @Test
    fun historicalDeviceCapStateDoesNotBlockFreeAccess() {
        val state = SubscriptionUiState(
            subscription = subscription(tier = SubscriptionTier.LIFETIME),
            hasEntitlement = true,
            isDeviceActivated = false,
            deviceBlockReason = "device_cap_reached",
            isLoggedIn = true,
        )

        val access = state.accessPresentation()
        val actions = state.recommendedPremiumActions()

        assertFalse(access.isPremiumButBlockedOnThisDevice)
        assertTrue(access.isUsablePremiumOnThisDevice)
        assertFalse(access.shouldShowManageDevices)
        assertEquals(listOf(PremiumSurfaceAction.REFRESH_ACCESS), actions)
    }

    private fun subscription(
        tier: SubscriptionTier,
        expiresAt: Long? = null,
        platform: String = "google_play",
    ): Subscription {
        return Subscription(
            id = "sub-1",
            tier = tier,
            purchaseToken = "token",
            expiresAt = expiresAt,
            isActive = true,
            platform = platform,
            purchasedAt = 1L,
        )
    }
}
