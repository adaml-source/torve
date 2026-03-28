package com.torve.android.premium

import com.torve.domain.model.SubscriptionTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumAccessTest {

    @Test
    fun tierFromPremiumEntitlementUnlocksPremiumFeatures() {
        val tier = PremiumAccess.tierFrom(isLifetimeEntitled = true)

        assertTrue(PremiumAccess.canAccess(PremiumFeature.STREAM_PLAYBACK, tier))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.ACCOUNT_SETUP, tier))
        assertFalse(PremiumAccess.isPremiumLocked(PremiumFeature.STREAM_PLAYBACK, tier))
    }

    @Test
    fun monthlyTierUnlocksPremiumFeatures() {
        val tier = PremiumAccess.tierFrom(
            subscriptionTier = SubscriptionTier.MONTHLY,
            isPremiumActive = true,
        )

        assertTrue(PremiumAccess.canAccess(PremiumFeature.STREAM_PLAYBACK, tier))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.DEVICE_LINKING, tier))
        assertFalse(PremiumAccess.isPremiumLocked(PremiumFeature.STREAM_PLAYBACK, tier))
    }

    @Test
    fun freeTierKeepsPremiumFeaturesLocked() {
        val tier = PremiumAccess.tierFrom(isLifetimeEntitled = false)

        assertFalse(PremiumAccess.canAccess(PremiumFeature.STREAM_PLAYBACK, tier))
        assertFalse(PremiumAccess.canAccess(PremiumFeature.DEVICE_LINKING, tier))
    }
}
