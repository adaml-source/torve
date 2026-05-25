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
    fun offlinePremiumGraceRequiresMatchingPrincipal() {
        val snapshot = VerifiedPremiumSnapshot(
            principal = "user_a",
            verifiedAtMs = 1_000L,
        )

        assertFalse(
            isVerifiedOfflinePremiumAccessActive(
                currentPrincipal = "user_b",
                snapshot = snapshot,
                nowMs = 2_000L,
            ),
        )
    }

    @Test
    fun offlinePremiumGraceExpiresAfterWindow() {
        val snapshot = VerifiedPremiumSnapshot(
            principal = "user_a",
            verifiedAtMs = 1_000L,
        )

        assertFalse(
            isVerifiedOfflinePremiumAccessActive(
                currentPrincipal = "user_a",
                snapshot = snapshot,
                nowMs = 1_000L + 72L * 60L * 60L * 1000L + 1L,
            ),
        )
    }

    @Test
    fun offlinePremiumGraceAllowsRecentBackendVerifiedAccess() {
        val snapshot = VerifiedPremiumSnapshot(
            principal = "user_a",
            verifiedAtMs = 1_000L,
        )

        assertTrue(
            isVerifiedOfflinePremiumAccessActive(
                currentPrincipal = "user_a",
                snapshot = snapshot,
                nowMs = 1_000L + 60_000L,
            ),
        )
    }

    @Test
    fun locallyVerifiedPremiumAccessRequiresActiveUnexpiredPremiumRow() {
        val now = 10_000L
        val snapshot = VerifiedPremiumSnapshot(
            principal = "user_a",
            verifiedAtMs = now - 1_000L,
        )
        val subscription = premiumSubscription(expiresAt = now + 60_000L)

        assertTrue(
            isLocallyVerifiedPremiumAccessActive(
                currentPrincipal = "user_a",
                snapshot = snapshot,
                activeSubscription = subscription,
                nowMs = now,
            ),
        )
    }

    @Test
    fun expiredLocalSubscriptionBlocksLocallyVerifiedPremiumAccess() {
        val now = 10_000L
        val snapshot = VerifiedPremiumSnapshot(
            principal = "user_a",
            verifiedAtMs = now - 1_000L,
        )
        val subscription = premiumSubscription(expiresAt = now - 1L)

        assertFalse(
            isLocallyVerifiedPremiumAccessActive(
                currentPrincipal = "user_a",
                snapshot = snapshot,
                activeSubscription = subscription,
                nowMs = now,
            ),
        )
    }

    @Test
    fun staleVerifiedSnapshotWithExpiredLocalActiveRowReturnsFalse() {
        val now = 10_000L
        val snapshot = VerifiedPremiumSnapshot(
            principal = "user_a",
            verifiedAtMs = now - 1_000L,
            hasPremiumEntitlement = true,
            isDeviceActivated = true,
        )
        val expiredActiveRow = premiumSubscription(expiresAt = now)

        assertFalse(
            isLocallyVerifiedPremiumAccessActive(
                currentPrincipal = "user_a",
                snapshot = snapshot,
                activeSubscription = expiredActiveRow,
                nowMs = now,
            ),
        )
    }

    @Test
    fun wrongPrincipalBlocksLocallyVerifiedPremiumAccess() {
        val now = 10_000L
        val snapshot = VerifiedPremiumSnapshot(
            principal = "user_a",
            verifiedAtMs = now - 1_000L,
        )

        assertFalse(
            isLocallyVerifiedPremiumAccessActive(
                currentPrincipal = "user_b",
                snapshot = snapshot,
                activeSubscription = premiumSubscription(expiresAt = now + 60_000L),
                nowMs = now,
            ),
        )
    }

    @Test
    fun clearedLocalEntitlementBlocksLocallyVerifiedPremiumAccess() {
        assertFalse(
            isLocallyVerifiedPremiumAccessActive(
                currentPrincipal = "user_a",
                snapshot = null,
                activeSubscription = premiumSubscription(expiresAt = null),
                nowMs = 10_000L,
            ),
        )
    }

    @Test
    fun locallyStoredDeviceDeactivationBlocksPremiumAccess() {
        val now = 10_000L
        val snapshot = VerifiedPremiumSnapshot(
            principal = "user_a",
            verifiedAtMs = now - 1_000L,
            hasPremiumEntitlement = true,
            isDeviceActivated = false,
            deviceBlockReason = "device_cap_reached",
        )

        assertFalse(
            isLocallyVerifiedPremiumAccessActive(
                currentPrincipal = "user_a",
                snapshot = snapshot,
                activeSubscription = premiumSubscription(expiresAt = now + 60_000L),
                nowMs = now,
            ),
        )
    }

    @Test
    fun noEntitlementDoesNotTrustLocalPremiumUiState() {
        val decision = resolveSubscriptionEntitlementUiDecision(
            backendResult = BackendPremiumResult.NoEntitlement,
        )

        assertFalse(decision.isPro)
        assertFalse(decision.hasEntitlement)
    }

    @Test
    fun loggedOutStateDoesNotTrustLocalPremiumUiState() {
        val decision = resolveSubscriptionEntitlementUiDecision(
            backendResult = null,
        )

        assertFalse(decision.isPro)
        assertFalse(decision.hasEntitlement)
    }

    @Test
    fun deviceBlockedStateKeepsEntitlementButBlocksDeviceAccess() {
        val decision = resolveSubscriptionEntitlementUiDecision(
            backendResult = BackendPremiumResult.DeviceBlocked(reason = "device_cap_reached"),
        )

        assertFalse(decision.isPro)
        assertTrue(decision.hasEntitlement)
        assertFalse(decision.isDeviceActivated)
        assertTrue(decision.deviceCapReached)
    }

    @Test
    fun offlineBlockedSnapshotKeepsEntitlementWithoutGrantingUsablePremium() {
        val decision = resolveSubscriptionEntitlementUiDecision(
            backendResult = BackendPremiumResult.Offline(
                localIsPro = false,
                localHasEntitlement = true,
                localIsDeviceActivated = false,
                localDeviceBlockReason = "device_cap_reached",
            ),
        )

        assertFalse(decision.isPro)
        assertFalse(decision.hasEntitlement)
        assertFalse(decision.isDeviceActivated)
        assertFalse(decision.deviceCapReached)
    }

    @Test
    fun offlineVerifiedSnapshotStillFailsClosedForPremiumUi() {
        val decision = resolveSubscriptionEntitlementUiDecision(
            backendResult = BackendPremiumResult.Offline(
                localIsPro = true,
                localHasEntitlement = true,
                localIsDeviceActivated = true,
            ),
        )

        assertFalse(decision.isPro)
        assertFalse(decision.hasEntitlement)
        assertFalse(decision.isDeviceActivated)
    }

    @Test
    fun activeBackendResultGrantsFullPremiumAccess() {
        val decision = resolveSubscriptionEntitlementUiDecision(
            backendResult = BackendPremiumResult.Active,
        )

        assertTrue(decision.isPro)
        assertTrue(decision.hasEntitlement)
        assertTrue(decision.isDeviceActivated)
        assertFalse(decision.deviceCapReached)
    }

    @Test
    fun postLoginRefreshUpdatesStateCorrectly() {
        // Simulates: user was logged out (null), then logs in and gets Active
        val loggedOut = resolveSubscriptionEntitlementUiDecision(backendResult = null)
        assertFalse(loggedOut.isPro)

        val loggedIn = resolveSubscriptionEntitlementUiDecision(
            backendResult = BackendPremiumResult.Active,
        )
        assertTrue(loggedIn.isPro)
        assertTrue(loggedIn.hasEntitlement)
    }

    private fun premiumSubscription(expiresAt: Long?): Subscription = Subscription(
        id = "sub-1",
        tier = SubscriptionTier.MONTHLY,
        purchaseToken = "backend_entitlement",
        expiresAt = expiresAt,
        isActive = true,
        platform = "backend",
        purchasedAt = 1L,
    )
}
