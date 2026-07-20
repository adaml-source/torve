package com.torve.data.billing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull

class BillingApiContractTest {

    @Test
    fun checkoutSessionReturnsNoUrlBecauseCheckoutIsNotRequired() = runTest {
        val result = api().createStripeCheckoutSession("token", StripePurchaseType.MONTHLY)

        assertNull(result.resolvedUrl())
    }

    @Test
    fun portalSessionReturnsNoUrlBecauseBillingIsNotRequired() = runTest {
        val result = api().createStripePortalSession("token")

        assertNull(result.resolvedUrl())
    }

    private fun api(): KtorBillingApi {
        val client = HttpClient(MockEngine { request -> error("Unexpected request ${request.url}") })
        return KtorBillingApi(
            httpClient = client,
            baseUrlProvider = { "https://api.torve.app" },
            installationIdProvider = { "install-billing" },
        )
    }
}
