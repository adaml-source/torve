package com.torve.domain.repository

import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier

/**
 * Result of refreshing premium state from the backend.
 * Distinguishes between "has premium on this device" and "has entitlement but device blocked".
 */
sealed interface BackendPremiumResult {
    /** Device has active premium access. */
    data object Active : BackendPremiumResult

    /** User has entitlement but device is blocked (e.g., cap reached). */
    data class DeviceBlocked(
        val reason: String? = null,
        val needsVerification: Boolean = false,
    ) : BackendPremiumResult

    /** No premium entitlement at all. */
    data object NoEntitlement : BackendPremiumResult

    /** Could not reach backend; local state used. */
    data class Offline(
        val localIsPro: Boolean,
        val localHasEntitlement: Boolean = localIsPro,
        val localIsDeviceActivated: Boolean = localIsPro,
        val localDeviceBlockReason: String? = null,
    ) : BackendPremiumResult
}

interface SubscriptionRepository {
    suspend fun getActiveSubscription(): Subscription?
    suspend fun isPro(): Boolean
    suspend fun hasAccess(feature: PremiumFeature): Boolean
    /**
     * Legacy local activation hook.
     * Must not be treated as backend-authoritative entitlement on its own.
     */
    suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String)
    suspend fun ensureFreeTier()
    /**
     * Legacy restore hook kept for backward compatibility with older sync/import code.
     * Must not grant premium unless backend entitlement is confirmed.
     */
    suspend fun restorePurchase(purchaseToken: String): Subscription?

    /** Refresh entitlements from backend. Returns true if premium on this device. */
    suspend fun refreshFromBackend(): Boolean

    /**
     * Refresh entitlements from backend with full result.
     * Returns a [BackendPremiumResult] distinguishing active, device-blocked, and no-entitlement.
     */
    suspend fun refreshFromBackendDetailed(): BackendPremiumResult

    /** Called after store purchase is verified by backend. */
    suspend fun onBackendEntitlementGranted(isPremium: Boolean)
}
