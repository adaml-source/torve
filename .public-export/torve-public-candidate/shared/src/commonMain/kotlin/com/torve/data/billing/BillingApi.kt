package com.torve.data.billing

import com.torve.domain.model.SubscriptionTier
import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Deprecated("Torve no longer has Stripe purchase types; retained for platform compatibility.")
enum class StripePurchaseType(
    val wireValue: String,
    val tier: SubscriptionTier,
) {
    MONTHLY("monthly", SubscriptionTier.MONTHLY),
    LIFETIME("lifetime", SubscriptionTier.LIFETIME),
}

/**
 * Deprecated compatibility surface for historical Stripe billing.
 *
 * Full product access is free by default. Checkout and portal calls must not be
 * used to grant, revoke, or check feature access. Donation flows should live
 * outside this billing API and must never unlock features.
 */
interface BillingApi {
    @Deprecated("Checkout is not required for Torve access; returns no URL.")
    suspend fun createStripeCheckoutSession(
        accessToken: String,
        purchaseType: StripePurchaseType,
        installationId: String? = null,
    ): StripeCheckoutSessionDto

    @Deprecated("Billing portal is not required for Torve access; returns no URL.")
    suspend fun createStripePortalSession(
        accessToken: String,
        installationId: String? = null,
    ): StripePortalSessionDto
}

class KtorBillingApi(
    @Suppress("unused") private val httpClient: HttpClient,
    @Suppress("unused") private val baseUrlProvider: () -> String,
    @Suppress("unused") private val installationIdProvider: () -> String? = { null },
) : BillingApi {
    override suspend fun createStripeCheckoutSession(
        accessToken: String,
        purchaseType: StripePurchaseType,
        installationId: String?,
    ): StripeCheckoutSessionDto = StripeCheckoutSessionDto()

    override suspend fun createStripePortalSession(
        accessToken: String,
        installationId: String?,
    ): StripePortalSessionDto = StripePortalSessionDto()
}

@Serializable
data class StripeCheckoutSessionDto(
    @SerialName("checkout_url")
    val checkoutUrl: String? = null,
    val url: String? = null,
) {
    fun resolvedUrl(): String? = checkoutUrl?.takeIf { it.isNotBlank() }
        ?: url?.takeIf { it.isNotBlank() }
}

@Serializable
data class StripePortalSessionDto(
    @SerialName("portal_url")
    val portalUrl: String? = null,
    val url: String? = null,
) {
    fun resolvedUrl(): String? = portalUrl?.takeIf { it.isNotBlank() }
        ?: url?.takeIf { it.isNotBlank() }
}
