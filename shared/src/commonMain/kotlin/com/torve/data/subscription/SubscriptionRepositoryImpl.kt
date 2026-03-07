package com.torve.data.subscription

import com.torve.db.TorveDatabase
import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.SubscriptionRepository
import kotlinx.datetime.Clock

class SubscriptionRepositoryImpl(
    private val database: TorveDatabase,
) : SubscriptionRepository {

    override suspend fun getActiveSubscription(): Subscription? {
        val row = database.torveQueries.getActiveSubscription().executeAsOneOrNull()
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
        return sub.isActive && sub.tier == SubscriptionTier.LIFETIME
    }

    override suspend fun hasAccess(feature: PremiumFeature): Boolean {
        return isPro()
    }

    override suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        database.torveQueries.deactivateAllSubscriptions()
        database.torveQueries.insertSubscription(
            id = "sub_$now",
            tier = tier.name,
            purchase_token = purchaseToken,
            expires_at = null,
            is_active = 1,
            platform = "android",
            purchased_at = now,
        )
    }

    override suspend fun ensureFreeTier() {
        val existing = getActiveSubscription()
        if (existing == null) {
            val now = Clock.System.now().toEpochMilliseconds()
            database.torveQueries.insertSubscription(
                id = "sub_free",
                tier = SubscriptionTier.FREE.name,
                purchase_token = null,
                expires_at = null,
                is_active = 1,
                platform = "android",
                purchased_at = now,
            )
        }
    }

    override suspend fun restorePurchase(purchaseToken: String): Subscription? {
        activateSubscription(SubscriptionTier.LIFETIME, purchaseToken)
        return getActiveSubscription()
    }
}
