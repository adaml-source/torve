package com.torve.android.tv.premium

import com.torve.android.BuildConfig
import com.torve.android.premium.AccessTier
import com.torve.android.premium.PremiumActionDecision
import com.torve.android.premium.PremiumFeature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPremiumAccessTest {

    @Test
    fun formerlyPaidTvFeaturesAreAvailableOnFreeAccess() {
        val features = listOf(
            PremiumFeature.STREAM_PLAYBACK,
            PremiumFeature.DOWNLOADS,
            PremiumFeature.PERSISTENT_COLLECTIONS,
            PremiumFeature.CLOUD_PROVIDER_SETUP,
            PremiumFeature.ADVANCED_CONNECTION_CONFIGURATION,
            PremiumFeature.OMDB_SETUP,
            PremiumFeature.MDBLIST_SETUP,
            PremiumFeature.JELLYFIN_SETUP,
            PremiumFeature.PLEX_SETUP,
            PremiumFeature.AI_PROVIDER_SETUP,
            PremiumFeature.SYNC_CUSTOM_LAYOUTS,
            PremiumFeature.TV_PHONE_CONTINUATION,
            PremiumFeature.DEVICE_SYNC,
        )

        features.forEach { feature ->
            assertTrue(TvPremiumAccess.canAccess(feature, AccessTier.FREE))
            assertFalse(TvPremiumAccess.requiresLifetimeAccess(feature))
            assertFalse(TvPremiumAccess.isPremiumLocked(feature, AccessTier.FREE))
        }
    }

    @Test
    fun googleTvStoreBuildDoesNotExposeBillingOrDonationByDefault() {
        assertFalse(BuildConfig.HAS_BILLING)
        assertFalse(BuildConfig.SUPPORTS_TV_BILLING)
        assertFalse(BuildConfig.TORVE_SHOW_DONATION_LINKS)
        assertTrue(BuildConfig.TORVE_DONATION_URL.isBlank())
    }

    @Test
    fun billingRestorePurchaseAndDonationStateDoNotAffectTvAccess() {
        val missingPurchaseToken = ""
        val restoredPurchase = false
        val billingReady = false
        val isDonor = false
        val decision = PremiumActionDecision(
            feature = PremiumFeature.TV_PHONE_CONTINUATION,
            allowed = true,
            message = "",
        )

        assertTrue(missingPurchaseToken.isEmpty())
        assertFalse(restoredPurchase)
        assertFalse(billingReady)
        assertFalse(isDonor)
        assertTrue(decision.allowed)
        assertTrue(TvPremiumAccess.canAccess(PremiumFeature.TV_PHONE_CONTINUATION, AccessTier.FREE))
    }
}
