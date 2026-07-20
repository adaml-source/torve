package com.torve.android.tv.premium

import android.content.Context
import androidx.annotation.StringRes
import com.torve.android.R
import com.torve.android.premium.PremiumAccess
import com.torve.android.premium.PremiumFeature
import com.torve.android.premium.PremiumFeatureAccess
import com.torve.android.premium.PremiumFeaturePolicy

typealias AccessTier = com.torve.android.premium.AccessTier
typealias TvFeatureAccess = PremiumFeatureAccess
typealias TvEntitledFeature = PremiumFeature
typealias TvFeaturePolicy = PremiumFeaturePolicy

object TvPremiumAccess {
    // Legacy string constants kept for backward compatibility
    const val LOCKED_LABEL = PremiumAccess.LOCKED_LABEL
    const val LIFETIME_REQUIRED_LABEL = PremiumAccess.LIFETIME_REQUIRED_LABEL
    const val UNLOCK_WITH_LIFETIME_LABEL = PremiumAccess.UNLOCK_WITH_LIFETIME_LABEL

    // Localized resource IDs
    @StringRes val LOCKED_LABEL_RES = R.string.premium_locked
    @StringRes val LIFETIME_REQUIRED_LABEL_RES = R.string.premium_requires_lifetime
    @StringRes val UNLOCK_WITH_LIFETIME_LABEL_RES = R.string.premium_unlock_with_lifetime

    val lifetimeBenefits: List<String> = PremiumAccess.lifetimeBenefits

    fun lifetimeBenefits(context: Context): List<String> = PremiumAccess.lifetimeBenefits(context)

    fun tierFrom(isLifetimeEntitled: Boolean): AccessTier {
        return PremiumAccess.tierFrom(isLifetimeEntitled)
    }

    @Deprecated("Torve TV no longer has paid access requirements; retained for source compatibility.")
    fun requiresLifetimeAccess(feature: TvEntitledFeature): Boolean {
        return false
    }

    @Deprecated("Torve TV features are no longer payment-gated; retained for source compatibility.")
    fun canAccess(feature: TvEntitledFeature, tier: AccessTier): Boolean {
        return true
    }

    @Deprecated("Torve TV features are no longer payment-gated; retained for source compatibility.")
    fun isPremiumLocked(feature: TvEntitledFeature, tier: AccessTier): Boolean {
        return false
    }

    fun titleFor(context: Context, feature: TvEntitledFeature): String = PremiumAccess.titleFor(context, feature)

    fun titleFor(feature: TvEntitledFeature): String = PremiumAccess.titleFor(feature)

    fun unlockSummaryFor(feature: TvEntitledFeature): String = PremiumAccess.unlockSummaryFor(feature)
}
