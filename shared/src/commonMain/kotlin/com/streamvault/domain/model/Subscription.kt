package com.streamvault.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class SubscriptionTier {
    FREE,
    MONTHLY,
    LIFETIME;

    val label: String
        get() = when (this) {
            FREE -> "Free"
            MONTHLY -> "Monthly"
            LIFETIME -> "Lifetime"
        }
}

@Serializable
data class Subscription(
    val id: String = "",
    val tier: SubscriptionTier = SubscriptionTier.FREE,
    val purchaseToken: String? = null,
    val expiresAt: Long? = null,
    val isActive: Boolean = false,
    val platform: String = "android",
    val purchasedAt: Long = 0,
) {
    val isPro: Boolean
        get() = isActive && tier != SubscriptionTier.FREE
}

/**
 * Features gated behind subscription.
 * Free users can only search and browse.
 */
enum class PremiumFeature {
    STREAM_PLAYBACK,
    DOWNLOAD,
    IPTV,
    MULTI_DEBRID,
    ADVANCED_FILTERS,
}
