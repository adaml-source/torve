package com.torve.presentation.subscription

import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import kotlin.test.Test
import kotlin.test.assertFalse

class SubscriptionUiStatePolicyTest {

    @Test
    fun billingPolicyNeverRequiresCheckoutForHistoricalMonthlySubscription() {
        val state = SubscriptionUiState(
            subscription = Subscription(
                tier = SubscriptionTier.MONTHLY,
                isActive = false,
                platform = "stripe",
            ),
            hasEntitlement = false,
        ).withDerivedBillingPolicy()

        assertFalse(state.isStripeMonthly)
        assertFalse(state.canBuyMonthly)
        assertFalse(state.canBuyLifetime)
        assertFalse(state.canManageStripeBilling)
        assertFalse(state.accessPresentation().shouldShowBuy)
    }

    @Test
    fun billingPolicyNeverRequiresPortalForHistoricalLifetimeSubscription() {
        val state = SubscriptionUiState(
            subscription = Subscription(
                tier = SubscriptionTier.LIFETIME,
                isActive = true,
                platform = "stripe",
            ),
            hasEntitlement = true,
        ).withDerivedBillingPolicy()

        assertFalse(state.isLifetime)
        assertFalse(state.canBuyMonthly)
        assertFalse(state.canBuyLifetime)
        assertFalse(state.canManageStripeBilling)
    }
}
