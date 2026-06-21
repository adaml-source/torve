package com.torve.domain.repository

import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier

/**
 * Compatibility result for the legacy account-access refresh path.
 *
 * Free-software builds treat [Active] as normal authenticated access. The
 * remaining states are retained so platform clients can compile while their
 * UI is refactored; they must not be used to restrict product features based
 * on payment, entitlement, purchase, donation, or subscription state.
 */
sealed interface BackendPremiumResult {
    /** Account has normal free-software product access. */
    data object Active : BackendPremiumResult

    /** Historical compatibility state; must not represent a paid device cap. */
    data class DeviceBlocked(
        val reason: String? = null,
        val needsVerification: Boolean = false,
    ) : BackendPremiumResult

    /** Historical compatibility state; absence of entitlements must not block features. */
    data object NoEntitlement : BackendPremiumResult

    /** Could not reach backend; this is a technical/network state, not a paid-access state. */
    data class Offline(
        val localIsPro: Boolean,
        val localHasEntitlement: Boolean = localIsPro,
        val localIsDeviceActivated: Boolean = localIsPro,
        val localDeviceBlockReason: String? = null,
    ) : BackendPremiumResult
}

interface SubscriptionRepository {
    suspend fun getActiveSubscription(): Subscription?
    @Deprecated("Torve no longer has paid tiers; this returns free-software access compatibility.")
    suspend fun isPro(): Boolean
    @Deprecated("Torve no longer gates features by paid status; this returns free-software access compatibility.")
    suspend fun hasAccess(feature: PremiumFeature): Boolean
    @Deprecated("Local premium verification no longer controls access; this returns free-software access compatibility.")
    suspend fun hasLocallyVerifiedPremiumAccess(): Boolean = isPro()
    /**
     * Legacy local activation hook retained for client source compatibility.
     * Must not grant or remove product access.
     */
    @Deprecated("Purchases no longer activate features; retained as a no-op compatibility hook.")
    suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String)
    suspend fun ensureFreeTier()
    /**
     * Legacy restore hook kept for backward compatibility with older sync/import code.
     * Must not grant or remove product access.
     */
    @Deprecated("Purchase restore no longer controls access; retained for compatibility.")
    suspend fun restorePurchase(purchaseToken: String): Subscription?

    /** Refresh free-software access state from backend. Returns true for normal authenticated access. */
    suspend fun refreshFromBackend(): Boolean

    /**
     * Refresh access from backend with compatibility result.
     * Paid entitlement fields are historical and must not block features.
     */
    suspend fun refreshFromBackendDetailed(): BackendPremiumResult

    /** Legacy purchase callback; must not grant or remove product access. */
    @Deprecated("Store purchase verification no longer controls access; retained as a compatibility hook.")
    suspend fun onBackendEntitlementGranted(isPremium: Boolean)
}
