package com.torve.data.subscription

import com.torve.data.auth.AuthClient
import com.torve.data.device.DeviceApi
import com.torve.data.entitlement.EntitlementApi
import com.torve.db.TorveDatabase
import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.BackendPremiumResult
import com.torve.domain.repository.SubscriptionRepository
import kotlinx.datetime.Clock

class SubscriptionRepositoryImpl(
    private val database: TorveDatabase,
    private val authClient: AuthClient,
    private val entitlementApi: EntitlementApi,
    private val deviceApi: DeviceApi,
) : SubscriptionRepository {

    override suspend fun getActiveSubscription(): Subscription? {
        val row = database.torveQueries.getActiveSubscription().executeAsOneOrNull()
            ?: return null
        return Subscription(
            id = row.id,
            tier = SubscriptionTier.valueOf(row.tier),
            purchaseToken = row.purchase_token,
            expiresAt = row.expires_at,
            isActive = row.is_active == 1L,
            platform = row.platform,
            purchasedAt = row.purchased_at,
        )
    }

    override suspend fun isPro(): Boolean {
        // First check backend entitlements if logged in
        if (authClient.isLoggedIn()) {
            try {
                return refreshFromBackend()
            } catch (_: Exception) {
                // Fall back to local
            }
        }
        val sub = getActiveSubscription() ?: return false
        return sub.isPro
    }

    override suspend fun hasAccess(feature: PremiumFeature): Boolean {
        return isPro()
    }

    override suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        database.torveQueries.deactivateAllSubscriptions()
        database.torveQueries.insertSubscription(
            id = "sub_$now",
            tier = tier.name,
            purchase_token = purchaseToken,
            expires_at = null,
            is_active = 1,
            platform = "android",
            purchased_at = now,
        )
    }

    override suspend fun ensureFreeTier() {
        val existing = getActiveSubscription()
        if (existing == null) {
            val now = Clock.System.now().toEpochMilliseconds()
            database.torveQueries.insertSubscription(
                id = "sub_free",
                tier = SubscriptionTier.FREE.name,
                purchase_token = null,
                expires_at = null,
                is_active = 1,
                platform = "android",
                purchased_at = now,
            )
        }
    }

    override suspend fun restorePurchase(purchaseToken: String): Subscription? {
        if (authClient.isLoggedIn()) {
            when (refreshFromBackendDetailed()) {
                BackendPremiumResult.Active -> return getActiveSubscription()
                is BackendPremiumResult.DeviceBlocked -> return getActiveSubscription()
                is BackendPremiumResult.Offline -> {
                    if (getActiveSubscription()?.isPro == true) {
                        return getActiveSubscription()
                    }
                }
                BackendPremiumResult.NoEntitlement -> {
                    // Fall through to local restore fallback.
                }
            }
        }

        // Fallback: allow local restore when backend entitlement cannot be resolved.
        activateSubscription(SubscriptionTier.LIFETIME, purchaseToken)
        return getActiveSubscription()
    }

    override suspend fun refreshFromBackend(): Boolean {
        val token = authClient.getValidAccessToken() ?: return false
        return try {
            // Use device-aware access state instead of just entitlements
            val accessState = deviceApi.getAccessState(token)
            val devicePremium = accessState?.premium?.premium_access ?: false
            if (devicePremium) {
                onBackendEntitlementGranted(true)
            }
            devicePremium
        } catch (_: Exception) {
            // Fallback: try entitlement-only check
            try {
                val state = entitlementApi.getEntitlements(token)
                state.premium_access
            } catch (_: Exception) {
                getActiveSubscription()?.isPro == true
            }
        }
    }

    override suspend fun refreshFromBackendDetailed(): BackendPremiumResult {
        val token = authClient.getValidAccessToken()
            ?: return BackendPremiumResult.Offline(getActiveSubscription()?.isPro == true)
        return try {
            val accessState = deviceApi.getAccessState(token)
            if (accessState == null) {
                // Endpoint unavailable — fall back to entitlement-only check
                val state = entitlementApi.getEntitlements(token)
                return if (state.premium_access) {
                    onBackendEntitlementGranted(true)
                    BackendPremiumResult.Active
                } else {
                    BackendPremiumResult.NoEntitlement
                }
            }
            val hasEntitlement = accessState.premium.has_entitlement
            val devicePremium = accessState.premium.premium_access
            val reason = accessState.premium.reason
            when {
                devicePremium -> {
                    onBackendEntitlementGranted(true)
                    BackendPremiumResult.Active
                }
                hasEntitlement -> BackendPremiumResult.DeviceBlocked(reason)
                else -> BackendPremiumResult.NoEntitlement
            }
        } catch (_: Exception) {
            BackendPremiumResult.Offline(getActiveSubscription()?.isPro == true)
        }
    }

    override suspend fun onBackendEntitlementGranted(isPremium: Boolean) {
        if (isPremium) {
            val existing = getActiveSubscription()
            if (existing == null || !existing.isPro) {
                activateSubscription(SubscriptionTier.LIFETIME, "backend_entitlement")
            }
        }
    }
}
