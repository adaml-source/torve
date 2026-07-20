package com.torve.presentation.subscription

import com.torve.domain.model.Subscription

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

/**
 * Historical Google Play purchase token shape retained for platform source compatibility.
 * Purchase state no longer grants or removes Torve features.
 */
data class GooglePlayActivePurchase(
    val productId: String,
    val purchaseToken: String,
    val isAcknowledged: Boolean,
)

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

@kotlinx.serialization.Serializable
enum class PendingAmazonVerificationReason {
    RETRY_VERIFICATION,
    TEMPORARY_FAILURE,
    BACKEND_UNAVAILABLE,
}

@kotlinx.serialization.Serializable
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

fun PendingAmazonVerification.toPurchaseStatusMessage(
    isLoggedIn: Boolean,
    strings: PurchaseStringResolver = DefaultPurchaseStringResolver(),
): PurchaseStatusMessage {
    return if (!isLoggedIn) {
        PurchaseStatusMessage(
            kind = PurchaseStatusKind.SIGN_IN_REQUIRED,
            title = strings.signInRequiredTitle(),
            message = strings.pendingSignInMessage(),
            tone = PurchaseStatusTone.INFO,
            showRetryVerification = false,
        )
    } else {
        PurchaseStatusMessage(
            kind = PurchaseStatusKind.RESTORE_FOUND_NOTHING,
            title = strings.nothingToRestoreTitle(),
            message = "Purchase verification is deprecated; Torve access is free.",
            tone = PurchaseStatusTone.INFO,
            showRetryVerification = false,
        )
    }
}

data class SubscriptionUiState(
    val subscription: Subscription? = null,
    val isPro: Boolean = true,
    val premiumSource: String? = null,
    val isLifetime: Boolean = false,
    val isStripeMonthly: Boolean = false,
    val canBuyMonthly: Boolean = false,
    val canBuyLifetime: Boolean = false,
    val canManageStripeBilling: Boolean = false,
    val isLoading: Boolean = false,
    val isPurchasing: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val showPaywall: Boolean = false,
    val paywallFeature: String? = null,
    val rebateCode: String = "",
    val isRedeeming: Boolean = false,
    val rebateSuccess: Boolean = false,
    val rebateCodesEnabled: Boolean = false,
    val hasEntitlement: Boolean = false,
    val isDeviceActivated: Boolean = true,
    val deviceBlockReason: String? = null,
    val deviceCapReached: Boolean = false,
    val needsVerification: Boolean = false,
    val isSendingVerificationEmail: Boolean = false,
    val verificationEmailMessage: String? = null,
    val showDeviceLimitReached: Boolean = false,
    val purchaseVerificationState: PurchaseVerificationState = PurchaseVerificationState.IDLE,
    val purchaseStatus: PurchaseStatusMessage? = null,
    val pendingAmazonVerification: PendingAmazonVerification? = null,
)

data class SubscriptionBillingPolicy(
    val premiumSource: String?,
    val isLifetime: Boolean,
    val isStripeMonthly: Boolean,
    val canBuyMonthly: Boolean,
    val canBuyLifetime: Boolean,
    val canManageStripeBilling: Boolean,
)

internal fun deriveSubscriptionBillingPolicy(
    subscription: Subscription?,
    hasEntitlement: Boolean,
): SubscriptionBillingPolicy {
    return SubscriptionBillingPolicy(
        premiumSource = subscription?.platform,
        isLifetime = false,
        isStripeMonthly = false,
        canBuyMonthly = false,
        canBuyLifetime = false,
        canManageStripeBilling = false,
    )
}

internal fun SubscriptionUiState.withDerivedBillingPolicy(
    subscription: Subscription? = this.subscription,
    hasEntitlement: Boolean = this.hasEntitlement,
): SubscriptionUiState {
    val policy = deriveSubscriptionBillingPolicy(subscription, hasEntitlement)
    return copy(
        premiumSource = policy.premiumSource,
        isLifetime = policy.isLifetime,
        isStripeMonthly = policy.isStripeMonthly,
        canBuyMonthly = policy.canBuyMonthly,
        canBuyLifetime = policy.canBuyLifetime,
        canManageStripeBilling = policy.canManageStripeBilling,
    )
}

enum class PremiumSurfaceAction {
    BUY_MONTHLY,
    BUY_LIFETIME,
    MANAGE_DEVICES,
    REFRESH_ACCESS,
    RESTORE_PURCHASES,
    RETRY_VERIFICATION,
}

data class SubscriptionAccessPresentation(
    val hasPremiumEntitlement: Boolean,
    val isDeviceActivated: Boolean,
    val isUsablePremiumOnThisDevice: Boolean,
    val isPremiumButBlockedOnThisDevice: Boolean,
    val shouldShowBuy: Boolean,
    val shouldShowBuyMonthly: Boolean,
    val shouldShowBuyLifetime: Boolean,
    val shouldShowRestore: Boolean,
    val shouldShowManageDevices: Boolean,
    val accessStatusLabel: String,
    val accessHelperText: String,
    val needsVerification: Boolean = false,
    val deviceBlockReason: String? = null,
)

fun SubscriptionUiState.accessPresentation(
    strings: PurchaseStringResolver = DefaultPurchaseStringResolver(),
): SubscriptionAccessPresentation {
    val isCheckingAccess = isLoggedIn && isLoading && !isPro
    return SubscriptionAccessPresentation(
        hasPremiumEntitlement = false,
        isDeviceActivated = true,
        isUsablePremiumOnThisDevice = true,
        isPremiumButBlockedOnThisDevice = false,
        shouldShowBuy = false,
        shouldShowBuyMonthly = false,
        shouldShowBuyLifetime = false,
        shouldShowRestore = false,
        shouldShowManageDevices = false,
        accessStatusLabel = if (isCheckingAccess) strings.checkingAccessLabel() else strings.freeLabel(),
        accessHelperText = if (isCheckingAccess) strings.checkingAccessHelperText() else strings.freeHelperText(),
        needsVerification = false,
        deviceBlockReason = null,
    )
}

fun SubscriptionUiState.recommendedPremiumActions(): List<PremiumSurfaceAction> {
    return if (isLoggedIn) listOf(PremiumSurfaceAction.REFRESH_ACCESS) else emptyList()
}
