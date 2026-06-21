package com.torve.android.billing

import com.torve.android.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonBillingManagerTest {

    @Test
    fun amazonTvStoreBuildDoesNotExposeBillingOrDonationByDefault() {
        assertFalse(BuildConfig.HAS_BILLING)
        assertFalse(BuildConfig.SUPPORTS_TV_BILLING)
        assertFalse(BuildConfig.TORVE_SHOW_DONATION_LINKS)
        assertTrue(BuildConfig.TORVE_DONATION_URL.isBlank())
    }

    @Test
    fun amazonIapStateDoesNotAffectAccess() = runBlocking {
        val manager = AmazonBillingManager()

        manager.initialize()
        manager.queryExistingPurchases()

        assertTrue(manager.billingState.value is BillingManager.BillingState.Ready)
        assertTrue(manager.queryActivePurchases().isEmpty())
        assertNull(manager.getOffer(BillingManager.ProductType.MONTHLY))
        assertNull(manager.getOffer(BillingManager.ProductType.LIFETIME))
    }
}
