package com.torve.data.subscription

import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.BackendPremiumResult
import com.torve.presentation.subscription.resolveSubscriptionEntitlementUiDecision
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionEntitlementHardeningTest {

    @Test
    fun expiredSubscriptionDoesNotBlockFreeAccessCompatibility() {
        val now = 10_000L

        assertTrue(
            isLocallyVerifiedPremiumAccessActive(
                currentPrincipal = "user_a",
                snapshot = VerifiedPremiumSnapshot(principal = "user_a", verifiedAtMs = now - 1_000L),
                activeSubscription = historicalSubscription(expiresAt = now - 1L),
                nowMs = now,
            ),
        )
    }

    @Test
    fun missingEntitlementDoesNotBlockFreeAccessCompatibility() {
        assertTrue(
            isLocallyVerifiedPremiumAccessActive(
                currentPrincipal = "user_a",
                snapshot = null,
                activeSubscription = null,
                nowMs = 10_000L,
            ),
        )
    }

    @Test
    fun deviceCapSnapshotDoesNotBlockFreeAccessCompatibility() {
        val now = 10_000L

        assertTrue(
            isLocallyVerifiedPremiumAccessActive(
                currentPrincipal = "user_a",
                snapshot = VerifiedPremiumSnapshot(
                    principal = "user_a",
                    verifiedAtMs = now - 1_000L,
                    hasPremiumEntitlement = false,
                    isDeviceActivated = false,
                    deviceBlockReason = "device_cap_reached",
                ),
                activeSubscription = historicalSubscription(expiresAt = now + 60_000L),
                nowMs = now,
            ),
        )
    }

    @Test
    fun noEntitlementBackendStateStillPresentsFreeAccess() {
        val decision = resolveSubscriptionEntitlementUiDecision(
            backendResult = BackendPremiumResult.NoEntitlement,
        )

        assertTrue(decision.isPro)
        assertFalse(decision.hasEntitlement)
        assertTrue(decision.isDeviceActivated)
    }

    @Test
    fun deviceBlockedBackendStateDoesNotCreatePaidDeviceCapGate() {
        val decision = resolveSubscriptionEntitlementUiDecision(
            backendResult = BackendPremiumResult.DeviceBlocked(reason = "device_cap_reached"),
        )

        assertTrue(decision.isPro)
        assertFalse(decision.hasEntitlement)
        assertTrue(decision.isDeviceActivated)
        assertFalse(decision.deviceCapReached)
    }

    @Test
    fun offlineBackendStateUsesRepositoryLocalAccessAsTechnicalState() {
        val decision = resolveSubscriptionEntitlementUiDecision(
            backendResult = BackendPremiumResult.Offline(
                localIsPro = true,
                localHasEntitlement = false,
                localIsDeviceActivated = false,
                localDeviceBlockReason = "device_cap_reached",
            ),
        )

        assertTrue(decision.isPro)
        assertFalse(decision.hasEntitlement)
        assertTrue(decision.isDeviceActivated)
        assertFalse(decision.deviceCapReached)
    }

    @Test
    fun activeBackendResultPresentsFreeAccessWithoutPaidEntitlement() {
        val decision = resolveSubscriptionEntitlementUiDecision(
            backendResult = BackendPremiumResult.Active,
        )

        assertTrue(decision.isPro)
        assertFalse(decision.hasEntitlement)
        assertTrue(decision.isDeviceActivated)
        assertFalse(decision.deviceCapReached)
    }

    private fun historicalSubscription(expiresAt: Long?): Subscription = Subscription(
        id = "sub-1",
        tier = SubscriptionTier.MONTHLY,
        purchaseToken = "historical",
        expiresAt = expiresAt,
        isActive = false,
        platform = "historical",
        purchasedAt = 1L,
    )
}
