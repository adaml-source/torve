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

/**
 * Single Google Play purchase token surfaced from BillingClient for the
 * client-driven restore flow. The Android `BillingManager.ActivePurchase`
 * type is mapped into this shared shape before hitting
 * [SubscriptionViewModel.restoreGooglePlayPurchases] so the view-model
 * stays platform-agnostic (no androidx.billingclient import in commonMain).
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

fun PendingAmazonVerification.toPurchaseStatusMessage(
    isLoggedIn: Boolean,
    strings: PurchaseStringResolver = DefaultPurchaseStringResolver(),
): PurchaseStatusMessage {
    return when {
        !isLoggedIn -> PurchaseStatusMessage(
            kind = PurchaseStatusKind.SIGN_IN_REQUIRED,
            title = strings.signInRequiredTitle(),
            message = strings.pendingSignInMessage(),
            tone = PurchaseStatusTone.INFO,
            showRetryVerification = true,
        )
        reason == PendingAmazonVerificationReason.BACKEND_UNAVAILABLE -> PurchaseStatusMessage(
            kind = PurchaseStatusKind.BACKEND_UNAVAILABLE,
            title = strings.verificationUnavailableTitle(),
            message = strings.pendingBackendUnavailableMessage(),
            tone = PurchaseStatusTone.ERROR,
            showRetryVerification = true,
        )
        reason == PendingAmazonVerificationReason.TEMPORARY_FAILURE -> PurchaseStatusMessage(
            kind = PurchaseStatusKind.VERIFICATION_FAILED_TEMPORARILY,
            title = strings.verificationNotFinishedTitle(),
            message = strings.pendingVerificationNotFinishedMessage(),
            tone = PurchaseStatusTone.ERROR,
            showRetryVerification = true,
        )
        else -> PurchaseStatusMessage(
            kind = PurchaseStatusKind.PENDING_VERIFICATION,
            title = strings.verificationPendingTitle(),
            message = strings.pendingVerificationWaitingMessage(),
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
    val needsVerification: Boolean = false,
    val isSendingVerificationEmail: Boolean = false,
    val verificationEmailMessage: String? = null,
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
    /**
     * True when the buy section should render at all — i.e. there is at
     * least one product the user can still purchase. Equal to
     * [shouldShowBuyMonthly] OR [shouldShowBuyLifetime].
     */
    val shouldShowBuy: Boolean,
    /**
     * Show the monthly buy button. Hidden once the user has any active
     * entitlement (re-buying monthly when already premium is pointless).
     */
    val shouldShowBuyMonthly: Boolean,
    /**
     * Show the lifetime buy button. Hidden ONLY when the user already
     * owns lifetime — a monthly subscriber can (and should) be able to
     * upgrade to lifetime at any time.
     */
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
    val tier = subscription?.tier ?: SubscriptionTier.FREE
    val hasPremiumEntitlement = hasEntitlement
    val deviceActivated = isDeviceActivated
    val isUsablePremiumOnThisDevice = isPro && hasPremiumEntitlement && deviceActivated
    val isPremiumButBlockedOnThisDevice = hasPremiumEntitlement && !deviceActivated
    val expiryText = subscription?.expiresAt?.let(::formatSubscriptionAccessDate)
    val blockMessage = deviceBlockMessage(deviceBlockReason, strings)

    val accessStatusLabel = when {
        isUsablePremiumOnThisDevice && tier == SubscriptionTier.LIFETIME -> strings.lifetimeActive()
        isUsablePremiumOnThisDevice && tier == SubscriptionTier.MONTHLY ->
            expiryText?.let { strings.monthlyActiveUntil(it) } ?: strings.monthlyActive()
        // Tier didn't classify (backend returned premium_access=true but no
        // recognised entitlement key / access_tier), but we DO have an
        // expiry — surface that to the user as "active until <date>"
        // instead of the bare "Premium active" catch-all.
        isUsablePremiumOnThisDevice && expiryText != null ->
            strings.monthlyActiveUntil(expiryText)
        isUsablePremiumOnThisDevice -> strings.premiumActive()
        isPremiumButBlockedOnThisDevice && tier == SubscriptionTier.MONTHLY -> strings.monthlyOnAccount()
        isPremiumButBlockedOnThisDevice && tier == SubscriptionTier.LIFETIME -> strings.lifetimeOnAccount()
        isPremiumButBlockedOnThisDevice -> strings.premiumOnAccount()
        else -> strings.freeLabel()
    }

    val accessHelperText = when {
        isUsablePremiumOnThisDevice && tier == SubscriptionTier.LIFETIME ->
            strings.lifetimeActiveOnDevice()
        isUsablePremiumOnThisDevice && tier == SubscriptionTier.MONTHLY ->
            strings.monthlyActiveOnDevice(expiryText)
        isUsablePremiumOnThisDevice && expiryText != null ->
            strings.monthlyActiveOnDevice(expiryText)
        isUsablePremiumOnThisDevice ->
            strings.premiumActiveOnDevice()
        isPremiumButBlockedOnThisDevice && tier == SubscriptionTier.MONTHLY ->
            "${strings.monthlyOnAccountHelper(expiryText)} $blockMessage"
        isPremiumButBlockedOnThisDevice && tier == SubscriptionTier.LIFETIME ->
            strings.lifetimeOnAccountHelper(blockMessage)
        isPremiumButBlockedOnThisDevice ->
            strings.premiumOnAccountHelper(blockMessage)
        else ->
            strings.freeHelperText()
    }

    val isAlreadyLifetime = tier == SubscriptionTier.LIFETIME && hasPremiumEntitlement
    val isKnownMonthly = tier == SubscriptionTier.MONTHLY && hasPremiumEntitlement
    val showBuyMonthly = !hasPremiumEntitlement
    // Lifetime is the only product that meaningfully upgrades over an
    // existing monthly entitlement, so show its button whenever the user
    // is known to have monthly. Unknown premium entitlement states hide
    // buy buttons to avoid prompting already-paying users to re-buy.
    val showBuyLifetime = !hasPremiumEntitlement || (isKnownMonthly && !isAlreadyLifetime)

    return SubscriptionAccessPresentation(
        hasPremiumEntitlement = hasPremiumEntitlement,
        isDeviceActivated = deviceActivated,
        isUsablePremiumOnThisDevice = isUsablePremiumOnThisDevice,
        isPremiumButBlockedOnThisDevice = isPremiumButBlockedOnThisDevice,
        shouldShowBuy = showBuyMonthly || showBuyLifetime,
        shouldShowBuyMonthly = showBuyMonthly,
        shouldShowBuyLifetime = showBuyLifetime,
        shouldShowRestore = true,
        shouldShowManageDevices = isPremiumButBlockedOnThisDevice && !needsVerification,
        accessStatusLabel = accessStatusLabel,
        accessHelperText = accessHelperText,
        needsVerification = needsVerification,
        deviceBlockReason = deviceBlockReason,
    )
}

fun SubscriptionUiState.recommendedPremiumActions(): List<PremiumSurfaceAction> {
    val access = accessPresentation()
    val actions = mutableListOf<PremiumSurfaceAction>()

    if (access.shouldShowBuyMonthly) {
        actions += PremiumSurfaceAction.BUY_MONTHLY
    }
    if (access.shouldShowBuyLifetime) {
        actions += PremiumSurfaceAction.BUY_LIFETIME
    }
    if (!access.shouldShowBuy && access.shouldShowManageDevices) {
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

private fun deviceBlockMessage(reason: String?, strings: PurchaseStringResolver): String {
    return when (reason?.trim()?.lowercase()) {
        "device_cap_reached",
        "activation_slot_exhausted",
        "no_activation_slots",
        -> strings.deviceNeedsSlot()
        "premium_required",
        "no_entitlement",
        -> strings.premiumRequired()
        else -> strings.deviceNeedsActivation()
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
