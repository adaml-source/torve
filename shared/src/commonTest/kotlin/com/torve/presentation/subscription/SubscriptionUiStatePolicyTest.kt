package com.torve.presentation.subscription

import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionUiStatePolicyTest {

    @Test
    fun stripeMonthlyCanUpgradeToLifetimeButCannotBuyAnotherMonthly() {
        val state = SubscriptionUiState(
            subscription = Subscription(
                tier = SubscriptionTier.MONTHLY,
                isActive = true,
                platform = "stripe",
            ),
            hasEntitlement = true,
        ).withDerivedBillingPolicy()
        val access = state.accessPresentation()

        assertTrue(state.isStripeMonthly)
        assertFalse(state.canBuyMonthly)
        assertTrue(state.canBuyLifetime)
        assertFalse(access.shouldShowBuyMonthly)
        assertTrue(access.shouldShowBuyLifetime)
    }

    @Test
    fun nonStripePremiumCannotStartStripePurchase() {
        val state = SubscriptionUiState(
            subscription = Subscription(
                tier = SubscriptionTier.MONTHLY,
                isActive = true,
                platform = "google_play",
            ),
            hasEntitlement = true,
        ).withDerivedBillingPolicy()
        val access = state.accessPresentation()

        assertFalse(state.isStripeMonthly)
        assertFalse(state.canBuyMonthly)
        assertFalse(state.canBuyLifetime)
        assertFalse(access.shouldShowBuy)
    }

    @Test
    fun stripeLifetimeCannotBuyMoreButCanManageStripeBilling() {
        val state = SubscriptionUiState(
            subscription = Subscription(
                tier = SubscriptionTier.LIFETIME,
                isActive = true,
                platform = "stripe",
            ),
            hasEntitlement = true,
        ).withDerivedBillingPolicy()

        assertTrue(state.isLifetime)
        assertFalse(state.canBuyMonthly)
        assertFalse(state.canBuyLifetime)
        assertTrue(state.canManageStripeBilling)
    }
}
