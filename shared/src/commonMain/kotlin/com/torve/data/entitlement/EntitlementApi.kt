package com.torve.data.entitlement

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/**
 * Backend API client for Torve entitlements.
 * Communicates with the Torve sync server to verify purchases
 * and fetch entitlement state.
 */
class EntitlementApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    // ── Entitlement Queries ──

    suspend fun getEntitlements(accessToken: String): EntitlementStateDto {
        return httpClient.get("${baseUrl()}/me/entitlements") {
            bearerAuth(accessToken)
        }.body()
    }

    // ── Store Verification ──

    suspend fun verifyApplePurchase(
        accessToken: String,
        transactionJws: String,
        productId: String,
        platform: String = "ios",
    ): PurchaseVerifyDto {
        return httpClient.post("${baseUrl()}/purchases/apple/verify") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(AppleVerifyDto(transactionJws, productId, platform))
        }.body()
    }

    suspend fun verifyGooglePurchase(
        accessToken: String,
        productId: String,
        purchaseToken: String,
        platform: String = "google_play_mobile",
    ): PurchaseVerifyDto {
        return httpClient.post("${baseUrl()}/purchases/google/verify") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(GoogleVerifyDto(productId, purchaseToken, platform))
        }.body()
    }

    suspend fun verifyAmazonPurchase(
        accessToken: String,
        receiptId: String,
        amazonUserId: String,
        productId: String,
        platform: String = "amazon_fire_tv",
    ): PurchaseVerifyDto {
        return httpClient.post("${baseUrl()}/purchases/amazon/verify") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(AmazonVerifyDto(receiptId, amazonUserId, productId, platform))
        }.body()
    }

    suspend fun restorePurchases(
        accessToken: String,
        store: String,
        platform: String,
    ): EntitlementStateDto {
        return httpClient.post("${baseUrl()}/purchases/restore") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(RestoreDto(store, platform))
        }.body()
    }
}

// ── DTOs ──

@Serializable
data class EntitlementDto(
    val key: String,
    val status: String,
    val source_store: String,
    val starts_at: String,
    val ends_at: String? = null,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
)

@Serializable
data class EntitlementStateDto(
    val user: UserDto,
    val entitlements: List<EntitlementDto>,
    val premium_access: Boolean,
)

@Serializable
data class PurchaseDto(
    val id: String,
    val store: String,
    val product_id: String,
    val purchase_type: String,
    val verification_status: String,
)

@Serializable
data class PurchaseVerifyDto(
    val status: String,
    val purchase: PurchaseDto,
    val entitlements: List<EntitlementDto>,
    val premium_access: Boolean,
)

@Serializable
private data class AppleVerifyDto(
    val transaction_jws: String,
    val product_id: String,
    val platform: String,
)

@Serializable
private data class GoogleVerifyDto(
    val product_id: String,
    val purchase_token: String,
    val platform: String,
)

@Serializable
private data class AmazonVerifyDto(
    val receipt_id: String,
    val amazon_user_id: String,
    val product_id: String,
    val platform: String,
)

@Serializable
private data class RestoreDto(
    val store: String,
    val platform: String,
)
