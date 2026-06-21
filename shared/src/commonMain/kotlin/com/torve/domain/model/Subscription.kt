package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class SubscriptionTier {
    FREE,
    MONTHLY,
    LIFETIME;

    val label: String
        get() = when (this) {
            FREE -> "Free"
            MONTHLY -> "Monthly (deprecated)"
            LIFETIME -> "Lifetime (deprecated)"
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
    @Deprecated("Torve no longer has paid tiers; do not use this to gate features.")
    val isPro: Boolean
        get() = isActive && (tier == SubscriptionTier.LIFETIME || tier == SubscriptionTier.MONTHLY)
}

/**
 * Historical feature identifiers retained for source compatibility.
 * These features are no longer gated by subscription, purchase, donation, or entitlement state.
 */
enum class PremiumFeature {
    STREAM_PLAYBACK,
    DOWNLOAD,
    CHANNELS,
    MULTI_DEBRID,
    ADVANCED_FILTERS,
}
