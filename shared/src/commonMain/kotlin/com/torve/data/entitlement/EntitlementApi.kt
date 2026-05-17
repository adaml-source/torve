package com.torve.data.entitlement

import com.torve.domain.security.ClientTrustHeaders
import com.torve.domain.security.ClientIntegrityAttestation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Backend API client for Torve entitlements.
 * Communicates with the Torve sync server to verify purchases
 * and fetch entitlement state.
 */
class EntitlementApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val installationIdProvider: () -> String? = { null },
) {
    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    // ── Entitlement Queries ──

    suspend fun getEntitlements(accessToken: String): EntitlementStateDto {
        val trustHeaders = ClientTrustHeaders.capture()
        return httpClient.get("${baseUrl()}/me/entitlements") {
            bearerAuth(accessToken)
            appendInstallationHeader()
            trustHeaders?.appendTo(this)
        }.body()
    }

    // ── Store Verification ──

    suspend fun verifyApplePurchase(
        accessToken: String,
        transactionJws: String,
        productId: String,
        platform: String = "ios",
    ): PurchaseVerifyDto {
        val trustHeaders = ClientTrustHeaders.capture()
        return httpClient.post("${baseUrl()}/purchases/apple/verify") {
            bearerAuth(accessToken)
            appendInstallationHeader()
            contentType(ContentType.Application.Json)
            trustHeaders?.appendTo(this)
            setBody(AppleVerifyDto(transactionJws, productId, platform))
        }.body()
    }

    suspend fun verifyGooglePurchase(
        accessToken: String,
        productId: String,
        purchaseToken: String,
        platform: String = "google_play_mobile",
        installationId: String? = null,
    ): PurchaseVerifyDto {
        // Prod routing uses the /me/ prefix for authenticated purchase
        // verification and the "google-play" slug (not "google").
        // Evidence: Sentry transaction id
        // "/me/purchases/google-play/verify" on commit 063c936.
        val trustHeaders = ClientTrustHeaders.capture()
        val clientIntegrity = ClientTrustHeaders.captureIntegrityAttestation()
        return httpClient.post("${baseUrl()}/me/purchases/google-play/verify") {
            bearerAuth(accessToken)
            appendInstallationHeader(installationId)
            trustHeaders?.appendTo(this)
            contentType(ContentType.Application.Json)
            setBody(GooglePlayVerifyRequest(productId, purchaseToken, platform, installationId, clientIntegrity))
        }.body()
    }

    suspend fun verifyAmazonPurchase(
        accessToken: String,
        receiptId: String,
        amazonUserId: String,
        productId: String,
        platform: String = "amazon_fire_tv",
        installationId: String? = null,
    ): PurchaseVerifyDto {
        val trustHeaders = ClientTrustHeaders.capture()
        return httpClient.post("${baseUrl()}/me/purchases/amazon/verify") {
            bearerAuth(accessToken)
            appendInstallationHeader(installationId)
            trustHeaders?.appendTo(this)
            contentType(ContentType.Application.Json)
            setBody(AmazonVerifyRequest(receiptId, amazonUserId, productId, platform, installationId))
        }.body()
    }

    suspend fun restorePurchases(
        accessToken: String,
        store: String,
        platform: String,
    ): EntitlementStateDto {
        val trustHeaders = ClientTrustHeaders.capture()
        return httpClient.post("${baseUrl()}/purchases/restore") {
            bearerAuth(accessToken)
            appendInstallationHeader()
            trustHeaders?.appendTo(this)
            contentType(ContentType.Application.Json)
            setBody(RestoreDto(store, platform))
        }.body()
    }

    /**
     * Canonical "recompute premium flags" endpoint. The backend pulls
     * the current entitlement set, plus any ledger-only lifetime grants
     * (admin-issued, rebate codes, etc.), and updates the user's
     * premium booleans. Does NOT re-verify any Play tokens — that's
     * the client's job (call [verifyGooglePurchase] for each owned
     * token first, then this).
     */
    suspend fun restorePurchasesCanonical(accessToken: String): RestorePurchasesDto {
        val trustHeaders = ClientTrustHeaders.capture()
        return httpClient.post("${baseUrl()}/me/purchases/restore") {
            bearerAuth(accessToken)
            appendInstallationHeader()
            trustHeaders?.appendTo(this)
        }.body()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendInstallationHeader(installationId: String?) {
        (installationId ?: installationIdProvider())?.takeIf { it.isNotBlank() }?.let {
            header("X-Torve-Installation-Id", it)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendInstallationHeader() {
        appendInstallationHeader(null)
    }
}

@Serializable
data class RestorePurchasesDto(
    val restored: Boolean = false,
    val has_premium_access: Boolean = false,
    val has_lifetime_access: Boolean = false,
    val is_verified: Boolean = false,
    val active_entitlements: Int = 0,
    val message: String? = null,
)

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
    /**
     * Authoritative success flag from the new backend (2026-04-26).
     * `true` means the purchase is recorded and entitlement is granted —
     * including idempotent replays (in which case [error_code] will be
     * "already_verified"). The client MUST treat verified == true as
     * success regardless of error_code.
     */
    val verified: Boolean? = null,
    /**
     * Whether the verification call resulted in a new entitlement grant
     * (vs an idempotent no-op replay of a prior verify).
     */
    val entitlement_granted: Boolean? = null,
    /** Human-readable summary; safe to log, NOT to render to the user. */
    val message: String? = null,
    /**
     * Standardized error code from the backend verify response. Null on
     * fresh-verify success paths; "already_verified" on idempotent
     * replays (still a SUCCESS — see [verified]). Other known values
     * (see [PurchaseVerifyErrorCode]): `config_missing`,
     * `product_mismatch`, `service_account_failure`,
     * `upstream_unreachable`, `not_verified`.
     */
    val error_code: String? = null,
    // ── Legacy fields (older backend versions) — kept optional so the
    // client deserialises whichever shape the server actually sends.
    // Authoritative success/refresh logic must use the fields above plus
    // a follow-up /me/access-state fetch, NOT these.
    val status: String? = null,
    val purchase: PurchaseDto? = null,
    val entitlements: List<EntitlementDto> = emptyList(),
    val premium_access: Boolean = false,
)

/**
 * Stable set of error codes the production backend returns in
 * [PurchaseVerifyDto.error_code]. The client never shows the raw code to
 * users — it's used only to pick which sanitized message to display.
 */
object PurchaseVerifyErrorCode {
    /** Purchase has already been verified previously; treat as success. */
    const val ALREADY_VERIFIED = "already_verified"

    /** Backend missing required configuration (service account JSON etc). */
    const val CONFIG_MISSING = "config_missing"

    /** Product ID does not map to a known Torve entitlement. */
    const val PRODUCT_MISMATCH = "product_mismatch"

    /** Service account present but can't authenticate with Google. */
    const val SERVICE_ACCOUNT_FAILURE = "service_account_failure"

    /** Upstream store reachable-but-returning-errors or unreachable. */
    const val UPSTREAM_UNREACHABLE = "upstream_unreachable"

    /** Verification ran and returned a negative result. */
    const val NOT_VERIFIED = "not_verified"

    /** Backend found an existing active entitlement for this account. */
    const val DUPLICATE_ACTIVE_ENTITLEMENT = "duplicate_active_entitlement"

    /** Account already has active premium through a different store. */
    const val CROSS_STORE_CONFLICT = "cross_store_conflict"

    /** Backend reports this entitlement has expired. */
    const val ENTITLEMENT_EXPIRED = "entitlement_expired"

    /** Backend reports this entitlement has been revoked. */
    const val ENTITLEMENT_REVOKED = "entitlement_revoked"
}

@Serializable
private data class AppleVerifyDto(
    val transaction_jws: String,
    val product_id: String,
    val platform: String,
)

@Serializable
private data class GooglePlayVerifyRequest(
    val product_id: String,
    val purchase_token: String,
    val platform: String,
    val installation_id: String? = null,
    @SerialName("client_integrity")
    val clientIntegrity: ClientIntegrityAttestation? = null,
)

@Serializable
private data class AmazonVerifyRequest(
    val receipt_id: String,
    val amazon_user_id: String,
    val product_id: String,
    val platform: String,
    val installation_id: String? = null,
)

@Serializable
private data class RestoreDto(
    val store: String,
    val platform: String,
)
