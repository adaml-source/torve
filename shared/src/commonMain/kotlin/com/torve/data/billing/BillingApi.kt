package com.torve.data.billing

import com.torve.domain.model.SubscriptionTier
import com.torve.domain.security.ClientTrustHeaders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class StripePurchaseType(
    val wireValue: String,
    val tier: SubscriptionTier,
) {
    MONTHLY("monthly", SubscriptionTier.MONTHLY),
    LIFETIME("lifetime", SubscriptionTier.LIFETIME),
}

interface BillingApi {
    suspend fun createStripeCheckoutSession(
        accessToken: String,
        purchaseType: StripePurchaseType,
        installationId: String? = null,
    ): StripeCheckoutSessionDto

    suspend fun createStripePortalSession(
        accessToken: String,
        installationId: String? = null,
    ): StripePortalSessionDto
}

class KtorBillingApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val installationIdProvider: () -> String? = { null },
) : BillingApi {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    override suspend fun createStripeCheckoutSession(
        accessToken: String,
        purchaseType: StripePurchaseType,
        installationId: String?,
    ): StripeCheckoutSessionDto {
        val trustHeaders = ClientTrustHeaders.capture()
        return httpClient.post("${baseUrl()}/billing/stripe/checkout-session") {
            bearerAuth(accessToken)
            appendInstallationHeader(installationId ?: installationIdProvider())
            trustHeaders?.appendTo(this)
            contentType(ContentType.Application.Json)
            setBody(StripeCheckoutSessionRequest(purchase_type = purchaseType.wireValue))
        }.body()
    }

    override suspend fun createStripePortalSession(
        accessToken: String,
        installationId: String?,
    ): StripePortalSessionDto {
        val trustHeaders = ClientTrustHeaders.capture()
        return httpClient.post("${baseUrl()}/billing/stripe/portal-session") {
            bearerAuth(accessToken)
            appendInstallationHeader(installationId ?: installationIdProvider())
            trustHeaders?.appendTo(this)
        }.body()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendInstallationHeader(
        installationId: String? = installationIdProvider(),
    ) {
        installationId?.takeIf { it.isNotBlank() }?.let {
            header("X-Torve-Installation-Id", it)
        }
    }
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

@Serializable
private data class StripeCheckoutSessionRequest(
    val purchase_type: String,
)
