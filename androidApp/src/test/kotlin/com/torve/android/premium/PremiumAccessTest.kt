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
        assertFalse(PremiumAccess.isPremiumLocked(PremiumFeature.STREAM_PLAYBACK, tier))
    }

    @Test
    fun freeTierKeepsPremiumFeaturesLocked() {
        val tier = PremiumAccess.tierFrom(isLifetimeEntitled = false)

        assertFalse(PremiumAccess.canAccess(PremiumFeature.STREAM_PLAYBACK, tier))
        assertTrue(PremiumAccess.isPremiumLocked(PremiumFeature.STREAM_PLAYBACK, tier))
    }

    // --- Device management is free for all authenticated users ---

    @Test
    fun deviceLinkingIsFreeForAllTiers() {
        assertTrue(PremiumAccess.canAccess(PremiumFeature.DEVICE_LINKING, AccessTier.FREE))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.DEVICE_LINKING, AccessTier.MONTHLY))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.DEVICE_LINKING, AccessTier.LIFETIME))
        assertFalse(PremiumAccess.isPremiumLocked(PremiumFeature.DEVICE_LINKING, AccessTier.FREE))
    }

    @Test
    fun phonePairingScreenIsFreeForAllTiers() {
        assertTrue(PremiumAccess.canAccess(PremiumFeature.PHONE_PAIRING, AccessTier.FREE))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.PHONE_PAIRING, AccessTier.MONTHLY))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.PHONE_PAIRING, AccessTier.LIFETIME))
        assertFalse(PremiumAccess.isPremiumLocked(PremiumFeature.PHONE_PAIRING, AccessTier.FREE))
    }

    @Test
    fun requiresPremiumAccessReturnsFalseForFreeFeatures() {
        assertFalse(PremiumAccess.requiresPremiumAccess(PremiumFeature.DEVICE_LINKING))
        assertFalse(PremiumAccess.requiresPremiumAccess(PremiumFeature.PHONE_PAIRING))
        assertFalse(PremiumAccess.requiresPremiumAccess(PremiumFeature.BROWSE_HOME))
    }

    @Test
    fun requiresPremiumAccessReturnsTrueForPremiumFeatures() {
        assertTrue(PremiumAccess.requiresPremiumAccess(PremiumFeature.STREAM_PLAYBACK))
        assertTrue(PremiumAccess.requiresPremiumAccess(PremiumFeature.DOWNLOADS))
        assertTrue(PremiumAccess.requiresPremiumAccess(PremiumFeature.CROSS_DEVICE_SYNC))
    }

    // --- Canonical premium: both monthly and lifetime unlock all premium features ---

    @Test
    fun monthlyTierUnlocksAllPremiumFeatures() {
        val tier = AccessTier.MONTHLY
        assertTrue(PremiumAccess.canAccess(PremiumFeature.STREAM_PLAYBACK, tier))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.DOWNLOADS, tier))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.WATCHLIST_EDIT, tier))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.TRAKT_CONNECT, tier))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.DIAGNOSTICS, tier))
    }

    @Test
    fun lifetimeTierUnlocksAllPremiumFeatures() {
        val tier = AccessTier.LIFETIME
        assertTrue(PremiumAccess.canAccess(PremiumFeature.STREAM_PLAYBACK, tier))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.DOWNLOADS, tier))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.WATCHLIST_EDIT, tier))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.TRAKT_CONNECT, tier))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.DIAGNOSTICS, tier))
    }

    @Test
    fun tierFromMapsBackendContractCorrectly() {
        // Monthly subscription from backend → MONTHLY access tier
        val monthly = PremiumAccess.tierFrom(
            subscriptionTier = SubscriptionTier.MONTHLY,
            isPremiumActive = true,
        )
        assertTrue(monthly == AccessTier.MONTHLY)

        // Lifetime from backend → LIFETIME access tier
        val lifetime = PremiumAccess.tierFrom(
            subscriptionTier = SubscriptionTier.LIFETIME,
            isPremiumActive = true,
        )
        assertTrue(lifetime == AccessTier.LIFETIME)

        // Premium active but null tier → defaults to LIFETIME
        val nullTier = PremiumAccess.tierFrom(
            subscriptionTier = null,
            isPremiumActive = true,
        )
        assertTrue(nullTier == AccessTier.LIFETIME)

        // Not premium → always FREE regardless of tier field
        val notActive = PremiumAccess.tierFrom(
            subscriptionTier = SubscriptionTier.LIFETIME,
            isPremiumActive = false,
        )
        assertTrue(notActive == AccessTier.FREE)
    }
}
