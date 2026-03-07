package com.torve.domain.repository

import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier

interface SubscriptionRepository {
    suspend fun getActiveSubscription(): Subscription?
    suspend fun isPro(): Boolean
    suspend fun hasAccess(feature: PremiumFeature): Boolean
    suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String)
    suspend fun ensureFreeTier()
    suspend fun restorePurchase(purchaseToken: String): Subscription?
}
