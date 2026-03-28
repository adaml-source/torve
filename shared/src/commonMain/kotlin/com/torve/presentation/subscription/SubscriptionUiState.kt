package com.torve.presentation.subscription

import com.torve.data.subscription.RebateCodeApi
import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
            message = "Your Amazon purchase is saved. Sign in to Torve, then choose Retry Verification to finish Premium activation.",
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
            message = "Your Amazon purchase is waiting to be verified. Choose Retry Verification to finish Premium activation.",
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
    val isDeviceActivated: Boolean = false,
    val deviceBlockReason: String? = null,
    val deviceCapReached: Boolean = false,
    val showDeviceLimitReached: Boolean = false,
    val purchaseVerificationState: PurchaseVerificationState = PurchaseVerificationState.IDLE,
    val purchaseStatus: PurchaseStatusMessage? = null,
    val pendingAmazonVerification: PendingAmazonVerification? = null,
)

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
    val shouldShowRestore: Boolean,
    val shouldShowManageDevices: Boolean,
    val accessStatusLabel: String,
    val accessHelperText: String,
    val deviceBlockReason: String? = null,
)

fun SubscriptionUiState.accessPresentation(): SubscriptionAccessPresentation {
    val tier = subscription?.tier ?: SubscriptionTier.FREE
    val hasPremiumEntitlement = hasEntitlement
    val deviceActivated = isDeviceActivated
    val isUsablePremiumOnThisDevice = isPro && hasPremiumEntitlement && deviceActivated
    val isPremiumButBlockedOnThisDevice = hasPremiumEntitlement && !deviceActivated
    val expiryText = subscription?.expiresAt?.let(::formatSubscriptionAccessDate)
    val premiumName = when (tier) {
        SubscriptionTier.MONTHLY -> "Premium Monthly"
        SubscriptionTier.LIFETIME -> "Premium Lifetime"
        SubscriptionTier.FREE -> "Premium"
    }
    val blockMessage = deviceBlockMessage(deviceBlockReason)

    val accessStatusLabel = when {
        isUsablePremiumOnThisDevice && tier == SubscriptionTier.MONTHLY ->
            expiryText?.let { "Premium Monthly active until $it" } ?: "Premium Monthly active"
        isUsablePremiumOnThisDevice && tier == SubscriptionTier.LIFETIME -> "Premium Lifetime active"
        isUsablePremiumOnThisDevice -> "Premium active"
        isPremiumButBlockedOnThisDevice && tier == SubscriptionTier.MONTHLY -> "Premium Monthly on account"
        isPremiumButBlockedOnThisDevice && tier == SubscriptionTier.LIFETIME -> "Premium Lifetime on account"
        isPremiumButBlockedOnThisDevice -> "Premium active on account"
        else -> "Free"
    }

    val accessHelperText = when {
        isUsablePremiumOnThisDevice && tier == SubscriptionTier.MONTHLY ->
            expiryText?.let { "Premium Monthly is active on this device until $it." }
                ?: "Premium Monthly is active on this device."
        isUsablePremiumOnThisDevice && tier == SubscriptionTier.LIFETIME ->
            "Premium Lifetime is active on this device."
        isUsablePremiumOnThisDevice ->
            "Premium is active on this device."
        isPremiumButBlockedOnThisDevice && tier == SubscriptionTier.MONTHLY ->
            buildString {
                append("Premium Monthly is active on your account")
                expiryText?.let { append(" until $it") }
                append(". ")
                append(blockMessage)
            }
        isPremiumButBlockedOnThisDevice && tier == SubscriptionTier.LIFETIME ->
            "Premium Lifetime is active on your account. $blockMessage"
        isPremiumButBlockedOnThisDevice ->
            "Premium is active on your account. $blockMessage"
        else ->
            "Torve is free to download. Choose monthly or lifetime access."
    }

    return SubscriptionAccessPresentation(
        hasPremiumEntitlement = hasPremiumEntitlement,
        isDeviceActivated = deviceActivated,
        isUsablePremiumOnThisDevice = isUsablePremiumOnThisDevice,
        isPremiumButBlockedOnThisDevice = isPremiumButBlockedOnThisDevice,
        shouldShowBuy = !hasPremiumEntitlement,
        shouldShowRestore = true,
        shouldShowManageDevices = isPremiumButBlockedOnThisDevice,
        accessStatusLabel = accessStatusLabel,
        accessHelperText = accessHelperText,
        deviceBlockReason = deviceBlockReason,
    )
}

fun SubscriptionUiState.recommendedPremiumActions(): List<PremiumSurfaceAction> {
    val access = accessPresentation()
    val actions = mutableListOf<PremiumSurfaceAction>()

    if (access.shouldShowBuy) {
        actions += PremiumSurfaceAction.BUY_MONTHLY
        actions += PremiumSurfaceAction.BUY_LIFETIME
    } else if (access.shouldShowManageDevices) {
        actions += PremiumSurfaceAction.MANAGE_DEVICES
    }

    if (isLoggedIn) {
        actions += PremiumSurfaceAction.REFRESH_ACCESS
    }

    if (purchaseStatus?.showRetryVerification == true) {
        actions += PremiumSurfaceAction.RETRY_VERIFICATION
    }

    if (access.shouldShowRestore) {
        actions += PremiumSurfaceAction.RESTORE_PURCHASES
    }

    return actions.distinct()
}

private fun deviceBlockMessage(reason: String?): String {
    return when (reason?.trim()?.lowercase()) {
        "device_cap_reached",
        "activation_slot_exhausted",
        "no_activation_slots",
        -> "This device needs an available activation slot."
        "device_not_activated",
        "device_removed",
        "activation_required",
        -> "This device needs activation."
        else -> "This device needs activation."
    }
}

private fun formatSubscriptionAccessDate(epochMs: Long): String {
    return runCatching {
        val date = Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
        "${date.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${date.dayOfMonth}, ${date.year}"
    }.getOrElse { "later" }
}
