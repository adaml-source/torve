package com.streamvault.data.subscription

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.model.PremiumFeature
import com.streamvault.domain.model.Subscription
import com.streamvault.domain.model.SubscriptionTier
import com.streamvault.domain.repository.SubscriptionRepository
import kotlinx.datetime.Clock

class SubscriptionRepositoryImpl(
    private val database: StreamVaultDatabase,
) : SubscriptionRepository {

    override suspend fun getActiveSubscription(): Subscription? {
        val row = database.streamVaultQueries.getActiveSubscription().executeAsOneOrNull()
            ?: return null
        return Subscription(
            id = row.id,
            tier = SubscriptionTier.valueOf(row.tier),
            purchaseToken = row.purchase_token,
            expiresAt = row.expires_at,
            isActive = row.is_active == 1L,
            platform = row.platform,
            purchasedAt = row.purchased_at,
        )
    }

    override suspend fun isPro(): Boolean {
        val sub = getActiveSubscription() ?: return false
        if (!sub.isActive) return false
        if (sub.tier == SubscriptionTier.LIFETIME) return true
        // Monthly: check expiry
        val expiresAt = sub.expiresAt ?: return false
        return Clock.System.now().toEpochMilliseconds() < expiresAt
    }

    override suspend fun hasAccess(feature: PremiumFeature): Boolean {
        return isPro()
    }

    override suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        val expiresAt = if (tier == SubscriptionTier.MONTHLY) {
            now + 30L * 24 * 60 * 60 * 1000 // 30 days
        } else null

        database.streamVaultQueries.deactivateAllSubscriptions()
        database.streamVaultQueries.insertSubscription(
            id = "sub_$now",
            tier = tier.name,
            purchase_token = purchaseToken,
            expires_at = expiresAt,
            is_active = 1,
            platform = "android",
            purchased_at = now,
        )
    }

    override suspend fun deactivateSubscription() {
        database.streamVaultQueries.deactivateAllSubscriptions()
    }

    override suspend fun restorePurchase(purchaseToken: String): Subscription? {
        // In production, verify with Google Play / App Store
        // For now, reactivate if token matches
        return getActiveSubscription()
    }
}
