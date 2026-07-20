package com.torve.data.entitlement

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntitlementApiContractTest {

    @Test
    fun emptyEntitlementsReturnFreeAccessCompatibilityState() = runTest {
        val result = api().getEntitlements("access-token")

        assertTrue(result.premium_access)
        assertEquals(emptyList(), result.entitlements)
    }

    @Test
    fun storeVerificationDoesNotGrantEntitlementOrBlockAccess() = runTest {
        val api = api()

        val google = api.verifyGooglePurchase(
            accessToken = "access-token",
            productId = "historical-product",
            purchaseToken = "historical-token",
        )
        val apple = api.verifyApplePurchase(
            accessToken = "access-token",
            transactionJws = "historical-jws",
            productId = "historical-product",
        )
        val amazon = api.verifyAmazonPurchase(
            accessToken = "access-token",
            receiptId = "historical-receipt",
            amazonUserId = "historical-user",
            productId = "historical-product",
        )

        listOf(google, apple, amazon).forEach { result ->
            assertTrue(result.verified == true)
            assertFalse(result.entitlement_granted == true)
            assertTrue(result.premium_access)
        }
    }

    @Test
    fun restoreDoesNotRequirePurchasesForAccess() = runTest {
        val api = api()

        val legacy = api.restorePurchases("access-token", store = "google_play", platform = "android")
        val canonical = api.restorePurchasesCanonical("access-token")

        assertTrue(legacy.premium_access)
        assertFalse(canonical.restored)
        assertTrue(canonical.has_premium_access)
        assertEquals(0, canonical.active_entitlements)
    }

    private fun api(): EntitlementApi {
        val client = HttpClient(MockEngine { request -> error("Unexpected request ${request.url}") })
        return EntitlementApi(
            httpClient = client,
            baseUrlProvider = { "https://api.torve.app" },
            installationIdProvider = { "install-entitlement-provider" },
        )
    }
}
