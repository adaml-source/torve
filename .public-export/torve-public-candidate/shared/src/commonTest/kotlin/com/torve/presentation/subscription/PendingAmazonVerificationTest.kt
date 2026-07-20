package com.torve.presentation.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingAmazonVerificationTest {

    private fun pending(reason: PendingAmazonVerificationReason) = PendingAmazonVerification(
        receiptId = "receipt-123",
        amazonUserId = "amzn-user-123",
        productId = "historical-product",
        platform = "amazon_fire_tv",
        reason = reason,
    )

    @Test
    fun signedOutPendingDoesNotOfferPurchaseRetry() {
        val status = pending(PendingAmazonVerificationReason.RETRY_VERIFICATION)
            .toPurchaseStatusMessage(isLoggedIn = false)

        assertEquals(PurchaseStatusKind.SIGN_IN_REQUIRED, status.kind)
        assertFalse(status.showRetryVerification)
        assertTrue(status.message.contains("Sign in to Torve"))
    }

    @Test
    fun signedInPendingReportsRestoreNotNeeded() {
        val status = pending(PendingAmazonVerificationReason.BACKEND_UNAVAILABLE)
            .toPurchaseStatusMessage(isLoggedIn = true)

        assertEquals(PurchaseStatusKind.RESTORE_FOUND_NOTHING, status.kind)
        assertFalse(status.showRetryVerification)
        assertTrue(status.message.contains("Torve access is free"))
    }
}
