package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class SubscriptionTier {
    FREE,
    LIFETIME;

    val label: String
        get() = when (this) {
            FREE -> "Free"
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
        get() = isActive && tier == SubscriptionTier.LIFETIME
}

/**
 * Features gated behind subscription.
 * Free users can only search and browse.
 */
enum class PremiumFeature {
    STREAM_PLAYBACK,
    DOWNLOAD,
    CHANNELS,
    MULTI_DEBRID,
    ADVANCED_FILTERS,
}
