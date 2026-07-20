package com.torve.data.subscription

import com.torve.data.auth.AuthClient
import com.torve.data.device.DeviceApi
import com.torve.data.entitlement.EntitlementApi
import com.torve.db.TorveDatabase
import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.BackendPremiumResult
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.platform.torveVerboseLog
import kotlinx.datetime.Clock

internal object SubscriptionEntitlementCacheKeys {
    const val VERIFIED_PRINCIPAL = "subscription_backend_verified_principal"
    const val VERIFIED_AT_MS = "subscription_backend_verified_at_ms"
    const val VERIFIED_HAS_ENTITLEMENT = "subscription_backend_verified_has_entitlement"
    const val VERIFIED_IS_DEVICE_ACTIVATED = "subscription_backend_verified_is_device_activated"
    const val VERIFIED_DEVICE_BLOCK_REASON = "subscription_backend_verified_device_block_reason"
}

internal data class VerifiedPremiumSnapshot(
    val principal: String,
    val verifiedAtMs: Long,
    val hasPremiumEntitlement: Boolean = true,
    val isDeviceActivated: Boolean = true,
    val deviceBlockReason: String? = null,
)

@Deprecated("Torve no longer uses offline premium grace; authenticated free access is the compatibility path.")
internal fun isVerifiedOfflinePremiumAccessActive(
    currentPrincipal: String,
    snapshot: VerifiedPremiumSnapshot?,
    nowMs: Long,
): Boolean = currentPrincipal.isNotBlank() && (snapshot == null || nowMs >= snapshot.verifiedAtMs)

@Deprecated("Subscription expiry no longer controls product access.")
internal fun isLocalPremiumSubscriptionActive(
    subscription: Subscription?,
    nowMs: Long,
): Boolean = subscription != null || nowMs >= 0L

@Deprecated("Local premium verification no longer controls product access.")
internal fun isLocallyVerifiedPremiumAccessActive(
    currentPrincipal: String?,
    snapshot: VerifiedPremiumSnapshot?,
    activeSubscription: Subscription?,
    nowMs: Long,
): Boolean = !currentPrincipal.isNullOrBlank() ||
    snapshot != null ||
    isLocalPremiumSubscriptionActive(activeSubscription, nowMs)

class SubscriptionRepositoryImpl(
    private val database: TorveDatabase,
    private val authClient: AuthClient,
    @Suppress("unused") private val entitlementApi: EntitlementApi,
    private val deviceApi: DeviceApi,
    private val localSettingsRepository: DeviceLocalSettingsRepository,
) : SubscriptionRepository {

    private fun uid(): String = authClient.authUserFlow.value?.id ?: ""
    private fun isSignedIn(): Boolean = authClient.authUserFlow.value != null

    override suspend fun getActiveSubscription(): Subscription? {
        if (!isSignedIn()) return null
        val row = database.torveQueries.getActiveSubscription(userId = uid()).executeAsOneOrNull()
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

    override suspend fun isPro(): Boolean = authClient.isLoggedIn()

    override suspend fun hasAccess(feature: PremiumFeature): Boolean = authClient.isLoggedIn()

    override suspend fun hasLocallyVerifiedPremiumAccess(): Boolean = authClient.isLoggedIn()

    override suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String) {
        ensureFreeTier()
    }

    override suspend fun ensureFreeTier() {
        if (getActiveSubscription() == null) {
            persistFreeTier()
        }
    }

    override suspend fun restorePurchase(purchaseToken: String): Subscription? {
        if (!authClient.isLoggedIn()) return null
        ensureFreeTier()
        return getActiveSubscription()
    }

    override suspend fun refreshFromBackend(): Boolean {
        return authClient.isLoggedIn() && refreshFromBackendDetailed() !is BackendPremiumResult.NoEntitlement
    }

    override suspend fun refreshFromBackendDetailed(): BackendPremiumResult {
        val token = authClient.getValidAccessToken()
            ?: return offlineBackendResult()
        torveVerboseLog { "SUBSCRIPTION_BACKEND refresh_start free_software=true" }
        return try {
            val accessState = deviceApi.getAccessState(token)
                ?: return offlineBackendResult()
            cacheBackendAccessSnapshot()
            persistFreeTier()
            torveVerboseLog {
                "SUBSCRIPTION_BACKEND refresh_result status=active accessTier=${accessState.access_tier ?: "free"} free_software=true"
            }
            BackendPremiumResult.Active
        } catch (e: Exception) {
            torveVerboseLog {
                "SUBSCRIPTION_BACKEND refresh_failure ${e::class.simpleName}: ${e.message}"
            }
            offlineBackendResult()
        }
    }

    override suspend fun onBackendEntitlementGranted(isPremium: Boolean) {
        refreshFromBackendDetailed()
    }

    private suspend fun persistFreeTier() {
        if (!isSignedIn()) return
        val uid = uid()
        val now = Clock.System.now().toEpochMilliseconds()
        database.torveQueries.deactivateAllSubscriptions(userId = uid)
        database.torveQueries.insertSubscription(
            user_id = uid,
            id = "sub_free",
            tier = SubscriptionTier.FREE.name,
            purchase_token = null,
            expires_at = null,
            is_active = 1,
            platform = "free_software",
            purchased_at = now,
        )
    }

    private suspend fun cacheBackendAccessSnapshot() {
        val principal = currentEntitlementPrincipal() ?: return
        localSettingsRepository.setString(SubscriptionEntitlementCacheKeys.VERIFIED_PRINCIPAL, principal)
        localSettingsRepository.setString(
            SubscriptionEntitlementCacheKeys.VERIFIED_AT_MS,
            Clock.System.now().toEpochMilliseconds().toString(),
        )
        localSettingsRepository.setString(SubscriptionEntitlementCacheKeys.VERIFIED_HAS_ENTITLEMENT, "true")
        localSettingsRepository.setString(SubscriptionEntitlementCacheKeys.VERIFIED_IS_DEVICE_ACTIVATED, "true")
        localSettingsRepository.remove(SubscriptionEntitlementCacheKeys.VERIFIED_DEVICE_BLOCK_REASON)
    }

    private suspend fun readVerifiedPremiumSnapshot(): VerifiedPremiumSnapshot? {
        val principal = localSettingsRepository.getString(SubscriptionEntitlementCacheKeys.VERIFIED_PRINCIPAL)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val verifiedAtMs = localSettingsRepository.getString(SubscriptionEntitlementCacheKeys.VERIFIED_AT_MS)
            ?.toLongOrNull()
            ?: return null
        return VerifiedPremiumSnapshot(
            principal = principal,
            verifiedAtMs = verifiedAtMs,
            hasPremiumEntitlement = true,
            isDeviceActivated = true,
            deviceBlockReason = null,
        )
    }

    private suspend fun offlineBackendResult(): BackendPremiumResult {
        val snapshot = readVerifiedPremiumSnapshot()
        val currentPrincipal = currentEntitlementPrincipal()
        val localAccess = authClient.isLoggedIn() &&
            (snapshot == null || snapshot.principal == currentPrincipal)
        torveVerboseLog {
            "SUBSCRIPTION_BACKEND refresh_result status=offline localAccess=$localAccess free_software=true"
        }
        return BackendPremiumResult.Offline(
            localIsPro = localAccess,
            localHasEntitlement = localAccess,
            localIsDeviceActivated = localAccess,
            localDeviceBlockReason = null,
        )
    }

    private suspend fun currentEntitlementPrincipal(): String? {
        val user = authClient.getCurrentUser() ?: return null
        return user.id.ifBlank { user.email.trim().lowercase() }.takeIf { it.isNotBlank() }
    }
}
