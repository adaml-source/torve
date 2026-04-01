package com.torve.presentation.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingAmazonVerificationTest {

    private fun pending(reason: PendingAmazonVerificationReason) = PendingAmazonVerification(
        receiptId = "receipt-123",
        amazonUserId = "amzn-user-123",
        productId = "com.torve.pro.lifetime",
        platform = "amazon_fire_tv",
        reason = reason,
    )

    @Test
    fun signedOutPendingExplainsRetryAfterSignIn() {
        val status = pending(PendingAmazonVerificationReason.RETRY_VERIFICATION)
            .toPurchaseStatusMessage(isLoggedIn = false)

        assertEquals(PurchaseStatusKind.SIGN_IN_REQUIRED, status.kind)
        assertTrue(status.showRetryVerification)
        assertTrue(status.message.contains("Sign in to Torve"))
    }

    @Test
    fun backendUnavailablePendingUsesBackendUnavailableMessage() {
        val status = pending(PendingAmazonVerificationReason.BACKEND_UNAVAILABLE)
            .toPurchaseStatusMessage(isLoggedIn = true)

        assertEquals(PurchaseStatusKind.BACKEND_UNAVAILABLE, status.kind)
        assertTrue(status.showRetryVerification)
        assertTrue(status.message.contains("verification service"))
    }

    @Test
    fun retryPendingUsesPendingVerificationMessage() {
        val status = pending(PendingAmazonVerificationReason.RETRY_VERIFICATION)
            .toPurchaseStatusMessage(isLoggedIn = true)

        assertEquals(PurchaseStatusKind.PENDING_VERIFICATION, status.kind)
        assertTrue(status.showRetryVerification)
        assertFalse(status.message.contains("Sign in required"))
    }
}
