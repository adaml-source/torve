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
    fun activePremiumStateResolvesToUsablePremium() {
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

        assertTrue(access.isUsablePremiumOnThisDevice)
        assertFalse(access.shouldShowBuy)
        assertFalse(access.shouldShowManageDevices)
        assertTrue(access.accessStatusLabel.startsWith("Premium Monthly active"))
    }

    @Test
    fun blockedPremiumStateDirectsUserToManageDevicesFirst() {
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
