package com.torve.presentation.subscription

import com.torve.data.subscription.RebateCodeApi
import com.torve.domain.model.Subscription
import kotlinx.serialization.Serializable

enum class PurchaseVerificationState {
    IDLE,
    PENDING,
    VERIFIED,
    RESTORED,
    FAILED,
}

enum class PurchaseStatusTone {
    INFO,
    SUCCESS,
    ERROR,
}

enum class PurchaseStatusKind {
    PENDING_VERIFICATION,
    SIGN_IN_REQUIRED,
    VERIFICATION_FAILED_TEMPORARILY,
    PURCHASE_CONFLICT,
    RESTORE_FOUND_NOTHING,
    BACKEND_UNAVAILABLE,
    VERIFIED,
    RESTORED,
}

data class PurchaseStatusMessage(
    val kind: PurchaseStatusKind,
    val title: String,
    val message: String,
    val tone: PurchaseStatusTone,
    val showRetryVerification: Boolean = false,
)

@Serializable
enum class PendingAmazonVerificationReason {
    RETRY_VERIFICATION,
    TEMPORARY_FAILURE,
    BACKEND_UNAVAILABLE,
}

@Serializable
data class PendingAmazonVerification(
    val receiptId: String,
    val amazonUserId: String,
    val productId: String,
    val platform: String,
    val reason: PendingAmazonVerificationReason = PendingAmazonVerificationReason.RETRY_VERIFICATION,
    val attemptCount: Int = 0,
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val lastMessage: String? = null,
)

fun PendingAmazonVerification.toPurchaseStatusMessage(isLoggedIn: Boolean): PurchaseStatusMessage {
    return when {
        !isLoggedIn -> PurchaseStatusMessage(
            kind = PurchaseStatusKind.SIGN_IN_REQUIRED,
            title = "Sign in required",
            message = "Your Amazon purchase is saved. Sign in to Torve, then choose Retry Verification to finish Lifetime Access activation.",
            tone = PurchaseStatusTone.INFO,
            showRetryVerification = true,
        )
        reason == PendingAmazonVerificationReason.BACKEND_UNAVAILABLE -> PurchaseStatusMessage(
            kind = PurchaseStatusKind.BACKEND_UNAVAILABLE,
            title = "Verification service unavailable",
            message = "Your Amazon purchase info is saved, but Torve cannot reach the verification service right now. Choose Retry Verification again shortly.",
            tone = PurchaseStatusTone.ERROR,
            showRetryVerification = true,
        )
        reason == PendingAmazonVerificationReason.TEMPORARY_FAILURE -> PurchaseStatusMessage(
            kind = PurchaseStatusKind.VERIFICATION_FAILED_TEMPORARILY,
            title = "Verification not finished",
            message = "Amazon completed the purchase, but Torve could not confirm it yet. Choose Retry Verification or Restore Purchase.",
            tone = PurchaseStatusTone.ERROR,
            showRetryVerification = true,
        )
        else -> PurchaseStatusMessage(
            kind = PurchaseStatusKind.PENDING_VERIFICATION,
            title = "Verification pending",
            message = "Your Amazon purchase is waiting to be verified. Choose Retry Verification to finish Lifetime Access activation.",
            tone = PurchaseStatusTone.INFO,
            showRetryVerification = true,
        )
    }
}

data class SubscriptionUiState(
    val subscription: Subscription? = null,
    val isPro: Boolean = false,
    val isLoading: Boolean = false,
    val isPurchasing: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val showPaywall: Boolean = false,
    val paywallFeature: String? = null,
    val rebateCode: String = "",
    val isRedeeming: Boolean = false,
    val rebateSuccess: Boolean = false,
    val rebateCodesEnabled: Boolean = RebateCodeApi.ENABLED,
    // Device governance
    val hasEntitlement: Boolean = false,
    val deviceCapReached: Boolean = false,
    val showDeviceLimitReached: Boolean = false,
    val purchaseVerificationState: PurchaseVerificationState = PurchaseVerificationState.IDLE,
    val purchaseStatus: PurchaseStatusMessage? = null,
    val pendingAmazonVerification: PendingAmazonVerification? = null,
)
