package com.torve.data.subscription

import com.torve.data.auth.AuthClient
import com.torve.data.device.DeviceApi
import com.torve.data.device.resolvedAccessTier
import com.torve.data.device.resolvedExpiresAtEpochMs
import com.torve.data.device.resolvedDeviceBlockReason
import com.torve.data.device.resolvedHasPremiumEntitlement
import com.torve.data.device.resolvedIsDeviceActivated
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
import com.torve.presentation.subscription.PremiumEntitlementRecord
import com.torve.presentation.subscription.resolvePremiumEntitlement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object SubscriptionEntitlementCacheKeys {
    const val VERIFIED_PRINCIPAL = "subscription_backend_verified_principal"
    const val VERIFIED_AT_MS = "subscription_backend_verified_at_ms"
    const val VERIFIED_HAS_ENTITLEMENT = "subscription_backend_verified_has_entitlement"
    const val VERIFIED_IS_DEVICE_ACTIVATED = "subscription_backend_verified_is_device_activated"
    const val VERIFIED_DEVICE_BLOCK_REASON = "subscription_backend_verified_device_block_reason"
}

private const val OFFLINE_PREMIUM_GRACE_MS = 72L * 60L * 60L * 1000L

internal data class VerifiedPremiumSnapshot(
    val principal: String,
    val verifiedAtMs: Long,
    val hasPremiumEntitlement: Boolean = true,
    val isDeviceActivated: Boolean = true,
    val deviceBlockReason: String? = null,
)

internal fun isVerifiedOfflinePremiumAccessActive(
    currentPrincipal: String,
    snapshot: VerifiedPremiumSnapshot?,
    nowMs: Long,
): Boolean {
    val cached = snapshot ?: return false
    if (cached.principal != currentPrincipal) return false
    if (!cached.hasPremiumEntitlement || !cached.isDeviceActivated) return false
    return nowMs - cached.verifiedAtMs <= OFFLINE_PREMIUM_GRACE_MS
}

class SubscriptionRepositoryImpl(
    private val database: TorveDatabase,
    private val authClient: AuthClient,
    private val entitlementApi: EntitlementApi,
    private val deviceApi: DeviceApi,
    private val localSettingsRepository: DeviceLocalSettingsRepository,
) : SubscriptionRepository {

    /**
     * Subscription rows are scoped per user — when no one is signed in we
     * return an empty string and rely on the empty-state guards below to
     * skip persistence rather than risk leaking another user's cached row.
     */
    private fun uid(): String = authClient.authUserFlow.value?.id ?: ""
    private fun isSignedIn(): Boolean = authClient.authUserFlow.value != null

    override suspend fun getActiveSubscription(): Subscription? {
        return withContext(Dispatchers.IO) {
            if (!isSignedIn()) return@withContext null
            val row = database.torveQueries.getActiveSubscription(userId = uid()).executeAsOneOrNull()
                ?: return@withContext null
            Subscription(
                id = row.id,
                tier = SubscriptionTier.valueOf(row.tier),
                purchaseToken = row.purchase_token,
                expiresAt = row.expires_at,
                isActive = row.is_active == 1L,
                platform = row.platform,
                purchasedAt = row.purchased_at,
            )
        }
    }

    override suspend fun isPro(): Boolean {
        if (!authClient.isLoggedIn()) {
            return false
        }
        return when (val result = refreshFromBackendDetailed()) {
            BackendPremiumResult.Active -> true
            is BackendPremiumResult.DeviceBlocked -> false
            BackendPremiumResult.NoEntitlement -> false
            is BackendPremiumResult.Offline -> false
        }
    }

    override suspend fun hasAccess(feature: PremiumFeature): Boolean {
        return isPro()
    }

    override suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String) {
        if (!isSignedIn()) return
        val uid = uid()
        val now = Clock.System.now().toEpochMilliseconds()
        withContext(Dispatchers.IO) {
            database.torveQueries.deactivateAllSubscriptions(userId = uid)
            database.torveQueries.insertSubscription(
                user_id = uid,
                id = "sub_$now",
                tier = tier.name,
                purchase_token = purchaseToken,
                expires_at = null,
                is_active = 1,
                platform = "android",
                purchased_at = now,
            )
        }
    }

    override suspend fun ensureFreeTier() {
        val existing = getActiveSubscription()
        if (existing == null) {
            persistFreeTier()
        }
    }

    override suspend fun restorePurchase(purchaseToken: String): Subscription? {
        if (!authClient.isLoggedIn()) {
            return null
        }
        return when (refreshFromBackendDetailed()) {
            BackendPremiumResult.Active -> getActiveSubscription()
            is BackendPremiumResult.DeviceBlocked -> getActiveSubscription()
            is BackendPremiumResult.Offline -> null
            BackendPremiumResult.NoEntitlement -> null
        }
    }

    override suspend fun refreshFromBackend(): Boolean {
        return when (val result = refreshFromBackendDetailed()) {
            BackendPremiumResult.Active -> true
            is BackendPremiumResult.DeviceBlocked -> false
            BackendPremiumResult.NoEntitlement -> false
            is BackendPremiumResult.Offline -> false
        }
    }

    override suspend fun refreshFromBackendDetailed(): BackendPremiumResult {
        val token = authClient.getValidAccessToken()
            ?: return offlineBackendResult()
        torveVerboseLog { "SUBSCRIPTION_BACKEND refresh_start" }
        return try {
            val accessState = deviceApi.getAccessState(token)
                ?: return offlineBackendResult()
            val entitlementRecords = accessState.premium?.entitlements?.map { entitlement ->
                PremiumEntitlementRecord(
                    key = entitlement.key,
                    status = entitlement.status,
                    sourceStore = entitlement.source_store,
                    endsAt = entitlement.ends_at,
                )
            } ?: emptyList()
            val resolvedEntitlement = resolvePremiumEntitlement(
                records = entitlementRecords,
                nowEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
            val needsVerification = accessState.needs_verification
            val hasEntitlement = accessState.resolvedHasPremiumEntitlement() ||
                resolvedEntitlement.hasEntitlement ||
                needsVerification
            val isDeviceActivated = accessState.resolvedIsDeviceActivated()
            val usablePremium = hasEntitlement && isDeviceActivated
            val reason = accessState.resolvedDeviceBlockReason()
            val resolvedTier = accessState.resolvedAccessTier() ?: resolvedEntitlement.tier
            // Prefer the flat top-level `expires_at` from the access-state
            // response when present — admin-granted subscriptions don't
            // create per-purchase entitlement records, so the resolver
            // would otherwise have no expiry to surface.
            val resolvedExpiresAt = accessState.resolvedExpiresAtEpochMs()
                ?: resolvedEntitlement.expiresAtEpochMs
            val entitlementForPersistence = resolvedEntitlement.copy(
                tier = if (hasEntitlement) resolvedTier else SubscriptionTier.FREE,
                hasEntitlement = hasEntitlement,
                expiresAtEpochMs = resolvedExpiresAt,
            )
            when {
                usablePremium -> {
                    cacheBackendAccessSnapshot(
                        hasPremiumEntitlement = true,
                        isDeviceActivated = true,
                        deviceBlockReason = null,
                    )
                    persistResolvedTier(entitlementForPersistence)
                    torveVerboseLog {
                        "SUBSCRIPTION_BACKEND refresh_result status=active tier=${entitlementForPersistence.tier} expiresAt=${entitlementForPersistence.expiresAtEpochMs}"
                    }
                    BackendPremiumResult.Active
                }
                hasEntitlement -> {
                    cacheBackendAccessSnapshot(
                        hasPremiumEntitlement = true,
                        isDeviceActivated = false,
                        deviceBlockReason = reason,
                    )
                    persistResolvedTier(entitlementForPersistence)
                    torveVerboseLog {
                        "SUBSCRIPTION_BACKEND refresh_result status=device_blocked reason=$reason needsVerification=$needsVerification tier=${entitlementForPersistence.tier}"
                    }
                    BackendPremiumResult.DeviceBlocked(
                        reason = reason,
                        needsVerification = needsVerification,
                    )
                }
                else -> {
                    clearCachedBackendPremiumAccess()
                    persistFreeTier()
                    torveVerboseLog { "SUBSCRIPTION_BACKEND refresh_result status=no_entitlement" }
                    BackendPremiumResult.NoEntitlement
                }
            }
        } catch (e: Exception) {
            torveVerboseLog {
                "SUBSCRIPTION_BACKEND refresh_failure ${e::class.simpleName}: ${e.message}"
            }
            offlineBackendResult()
        }
    }

    override suspend fun onBackendEntitlementGranted(isPremium: Boolean) {
        if (isPremium) {
            // Store callbacks and Stripe redirects are not entitlement
            // state. Re-read /me/access-state and persist only the
            // backend's current answer.
            refreshFromBackendDetailed()
        } else {
            clearCachedBackendPremiumAccess()
            persistFreeTier()
        }
    }

    private suspend fun persistResolvedTier(resolved: com.torve.presentation.subscription.ResolvedPremiumEntitlement) {
        if (!resolved.hasEntitlement) {
            persistFreeTier()
            return
        }
        if (!isSignedIn()) return
        val uid = uid()
        val now = Clock.System.now().toEpochMilliseconds()
        withContext(Dispatchers.IO) {
            database.torveQueries.deactivateAllSubscriptions(userId = uid)
            database.torveQueries.insertSubscription(
                user_id = uid,
                id = "sub_backend_${resolved.tier.name.lowercase()}",
                tier = resolved.tier.name,
                purchase_token = "backend_entitlement",
                expires_at = resolved.expiresAtEpochMs,
                is_active = 1,
                platform = resolved.sourceStore ?: "backend",
                purchased_at = now,
            )
        }
    }

    private suspend fun persistFreeTier() {
        if (!isSignedIn()) return
        val uid = uid()
        val now = Clock.System.now().toEpochMilliseconds()
        withContext(Dispatchers.IO) {
            database.torveQueries.deactivateAllSubscriptions(userId = uid)
            database.torveQueries.insertSubscription(
                user_id = uid,
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

    private suspend fun cacheBackendAccessSnapshot(
        hasPremiumEntitlement: Boolean,
        isDeviceActivated: Boolean,
        deviceBlockReason: String?,
    ) {
        val principal = currentEntitlementPrincipal() ?: return
        localSettingsRepository.setString(SubscriptionEntitlementCacheKeys.VERIFIED_PRINCIPAL, principal)
        localSettingsRepository.setString(
            SubscriptionEntitlementCacheKeys.VERIFIED_AT_MS,
            Clock.System.now().toEpochMilliseconds().toString(),
        )
        localSettingsRepository.setString(
            SubscriptionEntitlementCacheKeys.VERIFIED_HAS_ENTITLEMENT,
            hasPremiumEntitlement.toString(),
        )
        localSettingsRepository.setString(
            SubscriptionEntitlementCacheKeys.VERIFIED_IS_DEVICE_ACTIVATED,
            isDeviceActivated.toString(),
        )
        if (deviceBlockReason.isNullOrBlank()) {
            localSettingsRepository.remove(SubscriptionEntitlementCacheKeys.VERIFIED_DEVICE_BLOCK_REASON)
        } else {
            localSettingsRepository.setString(
                SubscriptionEntitlementCacheKeys.VERIFIED_DEVICE_BLOCK_REASON,
                deviceBlockReason,
            )
        }
    }

    private suspend fun clearCachedBackendPremiumAccess() {
        localSettingsRepository.remove(SubscriptionEntitlementCacheKeys.VERIFIED_PRINCIPAL)
        localSettingsRepository.remove(SubscriptionEntitlementCacheKeys.VERIFIED_AT_MS)
        localSettingsRepository.remove(SubscriptionEntitlementCacheKeys.VERIFIED_HAS_ENTITLEMENT)
        localSettingsRepository.remove(SubscriptionEntitlementCacheKeys.VERIFIED_IS_DEVICE_ACTIVATED)
        localSettingsRepository.remove(SubscriptionEntitlementCacheKeys.VERIFIED_DEVICE_BLOCK_REASON)
    }

    private suspend fun hasVerifiedOfflinePremiumAccess(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Boolean {
        val currentPrincipal = currentEntitlementPrincipal() ?: return false
        return isVerifiedOfflinePremiumAccessActive(
            currentPrincipal = currentPrincipal,
            snapshot = readVerifiedPremiumSnapshot(),
            nowMs = nowMs,
        )
    }

    private suspend fun readVerifiedPremiumSnapshot(): VerifiedPremiumSnapshot? {
        val principal = localSettingsRepository.getString(SubscriptionEntitlementCacheKeys.VERIFIED_PRINCIPAL)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val verifiedAtMs = localSettingsRepository.getString(SubscriptionEntitlementCacheKeys.VERIFIED_AT_MS)
            ?.toLongOrNull()
            ?: return null
        val hasPremiumEntitlement = localSettingsRepository
            .getString(SubscriptionEntitlementCacheKeys.VERIFIED_HAS_ENTITLEMENT)
            ?.toBooleanStrictOrNull()
            ?: true
        val isDeviceActivated = localSettingsRepository
            .getString(SubscriptionEntitlementCacheKeys.VERIFIED_IS_DEVICE_ACTIVATED)
            ?.toBooleanStrictOrNull()
            ?: hasPremiumEntitlement
        val deviceBlockReason = localSettingsRepository
            .getString(SubscriptionEntitlementCacheKeys.VERIFIED_DEVICE_BLOCK_REASON)
            ?.takeIf { it.isNotBlank() }
        return VerifiedPremiumSnapshot(
            principal = principal,
            verifiedAtMs = verifiedAtMs,
            hasPremiumEntitlement = hasPremiumEntitlement,
            isDeviceActivated = isDeviceActivated,
            deviceBlockReason = deviceBlockReason,
        )
    }

    private suspend fun offlineBackendResult(): BackendPremiumResult {
        val snapshot = readVerifiedPremiumSnapshot()
        val currentPrincipal = currentEntitlementPrincipal()
        if (snapshot == null || currentPrincipal.isNullOrBlank() || snapshot.principal != currentPrincipal) {
            torveVerboseLog {
                "SUBSCRIPTION_BACKEND refresh_result status=offline verifiedSnapshot=false"
            }
            return BackendPremiumResult.Offline(localIsPro = false)
        }
        val withinGrace = Clock.System.now().toEpochMilliseconds() - snapshot.verifiedAtMs <= OFFLINE_PREMIUM_GRACE_MS
        if (!withinGrace) {
            torveVerboseLog {
                "SUBSCRIPTION_BACKEND refresh_result status=offline verifiedSnapshot=expired"
            }
            return BackendPremiumResult.Offline(localIsPro = false)
        }
        val localIsPro = snapshot.hasPremiumEntitlement && snapshot.isDeviceActivated
        torveVerboseLog {
            "SUBSCRIPTION_BACKEND refresh_result status=offline verifiedSnapshot=true localIsPro=$localIsPro"
        }
        return BackendPremiumResult.Offline(
            localIsPro = localIsPro,
            localHasEntitlement = snapshot.hasPremiumEntitlement,
            localIsDeviceActivated = snapshot.isDeviceActivated,
            localDeviceBlockReason = snapshot.deviceBlockReason,
        )
    }

    private suspend fun currentEntitlementPrincipal(): String? {
        val user = authClient.getCurrentUser() ?: return null
        return user.id.ifBlank { user.email.trim().lowercase() }.takeIf { it.isNotBlank() }
    }
}
