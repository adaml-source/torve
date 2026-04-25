package com.torve.presentation.subscription

import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionAccessPresentationTest {

    @Test
    fun freeStateShowsBuyAndRestoreActions() {
        val state = SubscriptionUiState(
            subscription = subscription(tier = SubscriptionTier.FREE),
            isLoggedIn = true,
        )

        val access = state.accessPresentation()
        val actions = state.recommendedPremiumActions()

        assertFalse(access.hasPremiumEntitlement)
        assertTrue(access.shouldShowBuy)
        assertTrue(access.shouldShowBuyMonthly)
        assertTrue(access.shouldShowBuyLifetime)
        assertTrue(access.shouldShowRestore)
        assertFalse(access.shouldShowManageDevices)
        assertEquals(
            listOf(
                PremiumSurfaceAction.BUY_MONTHLY,
                PremiumSurfaceAction.BUY_LIFETIME,
                PremiumSurfaceAction.REFRESH_ACCESS,
                PremiumSurfaceAction.RESTORE_PURCHASES,
            ),
            actions,
        )
    }

    @Test
    fun activeMonthlyCanStillUpgradeToLifetime() {
        // Monthly subscribers must keep the lifetime buy button visible —
        // it's a legitimate upgrade path. The monthly button is hidden
        // (re-buying monthly is meaningless) but lifetime stays.
        val state = SubscriptionUiState(
            subscription = subscription(
                tier = SubscriptionTier.MONTHLY,
                expiresAt = 1_776_988_800_000L,
            ),
            isPro = true,
            hasEntitlement = true,
            isDeviceActivated = true,
            isLoggedIn = true,
        )

        val access = state.accessPresentation()
        val actions = state.recommendedPremiumActions()

        assertTrue(access.isUsablePremiumOnThisDevice)
        assertFalse(access.shouldShowBuyMonthly)
        assertTrue(access.shouldShowBuyLifetime)
        assertTrue(access.shouldShowBuy, "Buy section must remain visible so monthly users can upgrade")
        assertFalse(access.shouldShowManageDevices)
        assertTrue(access.accessStatusLabel.startsWith("Premium Monthly active"))
        assertTrue(actions.contains(PremiumSurfaceAction.BUY_LIFETIME))
        assertFalse(actions.contains(PremiumSurfaceAction.BUY_MONTHLY))
    }

    @Test
    fun activeLifetimeHidesAllBuyButtons() {
        // Already-lifetime users have nothing to upgrade to — both buy
        // buttons hide.
        val state = SubscriptionUiState(
            subscription = subscription(tier = SubscriptionTier.LIFETIME),
            isPro = true,
            hasEntitlement = true,
            isDeviceActivated = true,
            isLoggedIn = true,
        )

        val access = state.accessPresentation()
        val actions = state.recommendedPremiumActions()

        assertTrue(access.isUsablePremiumOnThisDevice)
        assertFalse(access.shouldShowBuyMonthly)
        assertFalse(access.shouldShowBuyLifetime)
        assertFalse(access.shouldShowBuy)
        assertFalse(actions.contains(PremiumSurfaceAction.BUY_MONTHLY))
        assertFalse(actions.contains(PremiumSurfaceAction.BUY_LIFETIME))
    }

    @Test
    fun blockedLifetimeDirectsUserToManageDevicesFirst() {
        val state = SubscriptionUiState(
            subscription = subscription(tier = SubscriptionTier.LIFETIME),
            hasEntitlement = true,
            isDeviceActivated = false,
            deviceBlockReason = "device_cap_reached",
            isLoggedIn = true,
        )

        val access = state.accessPresentation()
        val actions = state.recommendedPremiumActions()

        assertTrue(access.isPremiumButBlockedOnThisDevice)
        assertFalse(access.shouldShowBuy)
        assertTrue(access.shouldShowManageDevices)
        assertEquals(PremiumSurfaceAction.MANAGE_DEVICES, actions.first())
        assertFalse(actions.contains(PremiumSurfaceAction.BUY_MONTHLY))
        assertFalse(actions.contains(PremiumSurfaceAction.BUY_LIFETIME))
    }

    private fun subscription(
        tier: SubscriptionTier,
        expiresAt: Long? = null,
    ): Subscription {
        return Subscription(
            id = "sub-1",
            tier = tier,
            purchaseToken = "token",
            expiresAt = expiresAt,
            isActive = tier != SubscriptionTier.FREE,
            platform = "google_play",
            purchasedAt = 1L,
        )
    }
}
