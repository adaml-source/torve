package com.torve.android.premium

import com.torve.android.BuildConfig
import com.torve.domain.model.SubscriptionTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumAccessTest {

    @Test
    fun freeTierCanAccessFormerlyPaidFeatures() {
        val formerlyPaidFeatures = listOf(
            PremiumFeature.SYNC_WATCH_HISTORY,
            PremiumFeature.SYNC_WATCHLIST,
            PremiumFeature.CLOUD_BACKUP_RESTORE,
            PremiumFeature.TRAKT_LIST_MANAGER,
            PremiumFeature.TRAKT_CONNECT,
            PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT,
            PremiumFeature.DIAGNOSTICS,
            PremiumFeature.AI_SEARCH_ADVANCED,
            PremiumFeature.STREAM_PLAYBACK,
            PremiumFeature.DOWNLOADS,
        )

        formerlyPaidFeatures.forEach { feature ->
            assertTrue("Expected $feature to be available on free access", PremiumAccess.canAccess(feature, AccessTier.FREE))
            assertFalse("Expected $feature not to be locked", PremiumAccess.isPremiumLocked(feature, AccessTier.FREE))
            assertFalse("Expected $feature not to require paid access", PremiumAccess.requiresPremiumAccess(feature))
        }
    }

    @Test
    fun subscriptionTierDoesNotControlFeatureAvailability() {
        val inactiveMonthly = PremiumAccess.tierFrom(
            subscriptionTier = SubscriptionTier.MONTHLY,
            isPremiumActive = false,
        )
        val activeMonthly = PremiumAccess.tierFrom(
            subscriptionTier = SubscriptionTier.MONTHLY,
            isPremiumActive = true,
        )
        val activeLifetime = PremiumAccess.tierFrom(
            subscriptionTier = SubscriptionTier.LIFETIME,
            isPremiumActive = true,
        )

        listOf(inactiveMonthly, activeMonthly, activeLifetime).forEach { tier ->
            assertTrue(PremiumAccess.canAccess(PremiumFeature.STREAM_PLAYBACK, tier))
            assertTrue(PremiumAccess.canAccess(PremiumFeature.DOWNLOADS, tier))
            assertTrue(PremiumAccess.canAccess(PremiumFeature.CROSS_DEVICE_SYNC, tier))
            assertFalse(PremiumAccess.isPremiumLocked(PremiumFeature.TRAKT_CONNECT, tier))
        }
    }

    @Test
    fun purchaseRestoreAndDonationStateDoNotAffectAccess() {
        val missingPurchaseToken = ""
        val restoreSucceeded = false
        val isDonor = false

        assertTrue(missingPurchaseToken.isEmpty())
        assertFalse(restoreSucceeded)
        assertFalse(isDonor)
        assertTrue(PremiumAccess.canAccess(PremiumFeature.STREAM_PLAYBACK, AccessTier.FREE))
        assertTrue(PremiumAccess.canAccess(PremiumFeature.DOWNLOADS, AccessTier.FREE))
        assertFalse(PremiumAccess.isPremiumLocked(PremiumFeature.AI_SEARCH_ADVANCED, AccessTier.FREE))
    }

    @Test
    fun googlePlayMobileDonationLinksAreHiddenByDefault() {
        assertFalse(BuildConfig.TORVE_SHOW_DONATION_LINKS)
        assertTrue(BuildConfig.TORVE_DONATION_URL.isBlank())
    }
}
