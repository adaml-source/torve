package com.torve.data.entitlement

import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

/**
 * Deprecated compatibility surface for historical entitlement and purchase APIs.
 *
 * Torve no longer grants or removes product features through purchases,
 * subscriptions, entitlements, restores, donations, rebates, or store receipts.
 * These methods intentionally return free-software-safe results and do not call
 * billing or store-verification endpoints.
 */
class EntitlementApi(
    @Suppress("unused") private val httpClient: HttpClient,
    @Suppress("unused") private val baseUrlProvider: () -> String,
    @Suppress("unused") private val installationIdProvider: () -> String? = { null },
) {
    @Deprecated("Paid entitlements no longer control access; returns an empty compatibility state.")
    suspend fun getEntitlements(accessToken: String): EntitlementStateDto {
        return EntitlementStateDto(
            user = UserDto(id = "", email = ""),
            entitlements = emptyList(),
            premium_access = true,
        )
    }

    @Deprecated("Apple purchases no longer control access; retained for client compatibility.")
    suspend fun verifyApplePurchase(
        accessToken: String,
        transactionJws: String,
        productId: String,
        platform: String = "ios",
    ): PurchaseVerifyDto = compatibilityVerifyResult()

    @Deprecated("Google Play purchases no longer control access; retained for client compatibility.")
    suspend fun verifyGooglePurchase(
        accessToken: String,
        productId: String,
        purchaseToken: String,
        platform: String = "google_play_mobile",
        installationId: String? = null,
    ): PurchaseVerifyDto = compatibilityVerifyResult()

    @Deprecated("Amazon purchases no longer control access; retained for client compatibility.")
    suspend fun verifyAmazonPurchase(
        accessToken: String,
        receiptId: String,
        amazonUserId: String,
        productId: String,
        platform: String = "amazon_fire_tv",
        installationId: String? = null,
    ): PurchaseVerifyDto = compatibilityVerifyResult()

    @Deprecated("Purchase restore no longer controls access; returns an empty compatibility state.")
    suspend fun restorePurchases(
        accessToken: String,
        store: String,
        platform: String,
    ): EntitlementStateDto {
        return EntitlementStateDto(
            user = UserDto(id = "", email = ""),
            entitlements = emptyList(),
            premium_access = true,
        )
    }

    @Deprecated("Purchase restore no longer controls access; returns a compatibility result.")
    suspend fun restorePurchasesCanonical(accessToken: String): RestorePurchasesDto {
        return RestorePurchasesDto(
            restored = false,
            has_premium_access = true,
            has_lifetime_access = false,
            is_verified = true,
            active_entitlements = 0,
            message = "Purchase restore is deprecated; Torve access is free.",
        )
    }

    private fun compatibilityVerifyResult(): PurchaseVerifyDto {
        return PurchaseVerifyDto(
            verified = true,
            entitlement_granted = false,
            message = "Purchase verification is deprecated; Torve access is free.",
            error_code = null,
            premium_access = true,
        )
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
    /** Compatibility success flag; purchase verification no longer controls access. */
    val verified: Boolean? = null,
    /** Historical field retained for deserialization/source compatibility. */
    val entitlement_granted: Boolean? = null,
    /** Human-readable compatibility summary; safe to log, not required for access. */
    val message: String? = null,
    /** Historical standardized error code retained for old UI message mapping. */
    val error_code: String? = null,
    val status: String? = null,
    val purchase: PurchaseDto? = null,
    val entitlements: List<EntitlementDto> = emptyList(),
    val premium_access: Boolean = false,
)

/**
 * Historical backend error codes retained only so old platform UI can compile.
 * None of these codes should be used to grant or remove product features.
 */
object PurchaseVerifyErrorCode {
    const val ALREADY_VERIFIED = "already_verified"
    const val CONFIG_MISSING = "config_missing"
    const val PRODUCT_MISMATCH = "product_mismatch"
    const val SERVICE_ACCOUNT_FAILURE = "service_account_failure"
    const val UPSTREAM_UNREACHABLE = "upstream_unreachable"
    const val NOT_VERIFIED = "not_verified"
    const val DUPLICATE_ACTIVE_ENTITLEMENT = "duplicate_active_entitlement"
    const val CROSS_STORE_CONFLICT = "cross_store_conflict"
    const val ENTITLEMENT_EXPIRED = "entitlement_expired"
    const val ENTITLEMENT_REVOKED = "entitlement_revoked"
}
