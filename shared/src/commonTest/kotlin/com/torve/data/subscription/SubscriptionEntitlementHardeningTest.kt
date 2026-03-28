package com.torve.data.subscription

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
}
