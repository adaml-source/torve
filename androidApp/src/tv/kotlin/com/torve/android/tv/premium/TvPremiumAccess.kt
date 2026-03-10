package com.torve.android.tv.premium

import com.torve.android.premium.PremiumAccess
import com.torve.android.premium.PremiumFeature
import com.torve.android.premium.PremiumFeatureAccess
import com.torve.android.premium.PremiumFeaturePolicy

typealias AccessTier = com.torve.android.premium.AccessTier
typealias TvFeatureAccess = PremiumFeatureAccess
typealias TvEntitledFeature = PremiumFeature
typealias TvFeaturePolicy = PremiumFeaturePolicy

object TvPremiumAccess {
    const val LOCKED_LABEL = PremiumAccess.LOCKED_LABEL
    const val LIFETIME_REQUIRED_LABEL = PremiumAccess.LIFETIME_REQUIRED_LABEL
    const val UNLOCK_WITH_LIFETIME_LABEL = PremiumAccess.UNLOCK_WITH_LIFETIME_LABEL

    val lifetimeBenefits: List<String> = PremiumAccess.lifetimeBenefits

    fun tierFrom(isLifetimeEntitled: Boolean): AccessTier {
        return PremiumAccess.tierFrom(isLifetimeEntitled)
    }

    fun requiresLifetimeAccess(feature: TvEntitledFeature): Boolean {
        return PremiumAccess.requiresLifetimeAccess(feature)
    }

    fun canAccess(feature: TvEntitledFeature, tier: AccessTier): Boolean {
        return PremiumAccess.canAccess(feature, tier)
    }

    fun isPremiumLocked(feature: TvEntitledFeature, tier: AccessTier): Boolean {
        return PremiumAccess.isPremiumLocked(feature, tier)
    }

    fun titleFor(feature: TvEntitledFeature): String = PremiumAccess.titleFor(feature)

    fun unlockSummaryFor(feature: TvEntitledFeature): String = PremiumAccess.unlockSummaryFor(feature)
}
