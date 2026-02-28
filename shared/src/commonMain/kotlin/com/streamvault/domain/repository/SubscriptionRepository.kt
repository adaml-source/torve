package com.streamvault.domain.repository

import com.streamvault.domain.model.PremiumFeature
import com.streamvault.domain.model.Subscription
import com.streamvault.domain.model.SubscriptionTier

interface SubscriptionRepository {
    suspend fun getActiveSubscription(): Subscription?
    suspend fun isPro(): Boolean
    suspend fun hasAccess(feature: PremiumFeature): Boolean
    suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String)
    suspend fun ensureFreeTier()
    suspend fun restorePurchase(purchaseToken: String): Subscription?
}
