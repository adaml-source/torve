package com.torve.android.billing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePlayBillingManagerTest {

    @Test
    fun googlePlayBillingStateDoesNotExposePaidOffers() = runBlocking {
        val manager = GooglePlayBillingManager()

        manager.initialize()
        manager.queryExistingPurchases()

        assertTrue(manager.billingState.value is BillingManager.BillingState.Ready)
        assertTrue(manager.queryActivePurchases().isEmpty())
        assertNull(manager.getOffer(BillingManager.ProductType.MONTHLY))
        assertNull(manager.getOffer(BillingManager.ProductType.LIFETIME))
    }
}
