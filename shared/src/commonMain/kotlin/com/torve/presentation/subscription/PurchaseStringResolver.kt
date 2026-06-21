package com.torve.presentation.subscription

import com.torve.domain.model.SubscriptionTier

/**
 * Platform-injectable string resolver for legacy access-status messages.
 * Android implementation resolves from string resources; other platforms use English defaults.
 */
interface PurchaseStringResolver {
    // Plan labels
    fun premiumMonthly(): String = "Full access"
    fun premiumLifetime(): String = "Full access"
    fun premiumGeneric(): String = "Full access"
    fun premiumPlanLabel(tier: SubscriptionTier): String = when (tier) {
        SubscriptionTier.MONTHLY -> premiumMonthly()
        SubscriptionTier.LIFETIME -> premiumLifetime()
        SubscriptionTier.FREE -> premiumGeneric()
    }

    // Purchase status titles
    fun purchaseReceivedTitle(): String = "Access refresh received"
    fun purchaseVerifiedTitle(): String = "Access verified"
    fun purchaseRestoredTitle(): String = "Access refreshed"
    fun verificationNotFinishedTitle(): String = "Verification not finished"
    fun verificationUnavailableTitle(): String = "Verification service unavailable"
    fun signInRequiredTitle(): String = "Sign in required"
    fun purchaseConflictTitle(): String = "Account mismatch"
    fun nothingToRestoreTitle(): String = "Nothing to restore"
    fun verificationPendingTitle(): String = "Verification pending"

    // Purchase status messages
    fun amazonCallbackPendingDefault(): String =
        "Amazon purchase state is historical. Torve is refreshing account access."
    fun purchaseReceivedSuffix(): String =
        "If this does not finish shortly, refresh account access."
    fun verificationNotFinishedMessage(): String =
        "Amazon purchase state is historical and does not affect access. Refresh account access if needed."
    fun verificationUnavailableRetry(): String =
        "Amazon purchase state is historical. Torve cannot reach the access service right now."
    fun verificationUnavailableRestore(): String =
        "Torve cannot reach the access service right now. Please refresh again shortly."
    fun signInAmazonRestore(): String =
        "Sign in to Torve before refreshing access on this device."
    fun signInBuy(storeLabel: String): String =
        "Sign in to Torve before continuing on this device."
    fun signInRestore(storeLabel: String): String =
        "Sign in to Torve before refreshing access from $storeLabel on this device."
    fun purchaseConflictMessage(): String =
        "This historical Amazon purchase is linked to a different Torve account. Sign in with the original account or contact support."
    fun nothingToRestoreAmazon(): String =
        "No Amazon purchase record was found for this Torve account. Access is not affected."
    fun nothingToRestoreStore(storeLabel: String): String =
        "No purchase record was found for this Torve account on $storeLabel. Access is not affected."

    // Verified / restored messages
    fun activeOnDevice(planLabel: String): String =
        "Full access is enabled on this device."
    fun activeOnDeviceUntil(planLabel: String, date: String): String =
        "Full access is enabled on this device."
    fun needsDeviceSlot(planLabel: String): String =
        "This account has full access, but this device still needs registration."
    fun restoredActiveOnDevice(planLabel: String): String =
        "Full access is enabled on this device."
    fun restoredActiveOnDeviceUntil(planLabel: String, date: String): String =
        "Full access is enabled on this device."
    fun restoredNeedsDeviceSlot(planLabel: String): String =
        "This account has full access, but this device still needs registration."

    // Error messages
    fun googleVerifyFailed(): String =
        "Google Play purchase state does not affect access. Sign in and refresh if needed."
    fun appleVerifyFailed(): String =
        "Apple purchase state does not affect access. Sign in and refresh if needed."

    // Google Play verify error_code mapping — sanitized, user-safe text.
    // None of these surface internal ops detail (no file paths, env var
    // names, credential state, or exception messages).
    fun googleVerifyConfigMissingTitle(): String = "Verification temporarily unavailable"
    fun googleVerifyConfigMissing(): String =
        "Historical purchase verification is temporarily unavailable. Access is not affected."

    fun googleVerifyProductMismatchTitle(): String = "Purchase couldn't be matched"
    fun googleVerifyProductMismatch(): String =
        "This historical purchase record does not match a Torve product. Access is not affected."

    fun googleVerifyServiceAccountFailureTitle(): String = "Verification temporarily unavailable"
    fun googleVerifyServiceAccountFailure(): String =
        "Historical purchase verification is temporarily unavailable. Access is not affected."

    fun googleVerifyUpstreamUnreachableTitle(): String = "Google Play unreachable"
    fun googleVerifyUpstreamUnreachable(): String =
        "Could not reach Google Play. Purchase state does not affect access."

    fun googleVerifyNotVerifiedTitle(): String = "Purchase not verified"
    fun googleVerifyNotVerified(): String =
        "Google Play did not confirm this historical purchase. Access is not affected."
    fun storeRestoreFailed(storeLabel: String): String =
        "$storeLabel access refresh could not be completed. Try again after signing in."
    fun localPurchaseDisabled(): String =
        "Local purchase activation is disabled and not required for access."
    fun rebateCodeBackendPending(): String =
        "Code accepted as a historical record. Product access is free by default."

    // Feature names (shared domain PremiumFeature)
    fun featureStreamPlayback(): String = "Stream Playback"
    fun featureDownloads(): String = "Downloads"
    fun featureChannels(): String = "Channels"
    fun featureMultiCloud(): String = "Multi-Cloud"
    fun featureAdvancedFilters(): String = "Advanced Filters"

    // Amazon pending verification messages (SubscriptionUiState)
    fun pendingSignInMessage(): String =
        "Your Amazon purchase state is historical. Sign in to Torve, then refresh account access."
    fun pendingBackendUnavailableMessage(): String =
        "Amazon purchase state is historical. Torve cannot reach the access service right now."
    fun pendingVerificationNotFinishedMessage(): String =
        "Amazon purchase state is historical and does not affect access. Refresh account access if needed."
    fun pendingVerificationWaitingMessage(): String =
        "Your Amazon purchase state is historical. Refresh account access if needed."
    fun pendingRetryMessage(): String =
        "Sign in to Torve, then refresh account access."

    // Verification resend UX
    fun verificationEmailSent(): String = "Verification email sent!"
    fun verificationEmailWaitBeforeResend(): String = "Please wait before resending."
    fun verificationEmailSendFailed(): String = "Failed to send verification email."
    fun verificationSignInRequired(): String = "Sign in to Torve before requesting a verification email."

    // Access presentation
    fun monthlyActiveUntil(date: String): String = "Full access enabled"
    fun monthlyActive(): String = "Full access enabled"
    fun lifetimeActive(): String = "Full access enabled"
    fun premiumActive(): String = "Full access enabled"
    fun monthlyOnAccount(): String = "Full access on account"
    fun lifetimeOnAccount(): String = "Full access on account"
    fun premiumOnAccount(): String = "Full access on account"
    fun freeLabel(): String = "Free"
    fun checkingAccessLabel(): String = "Checking access..."
    fun monthlyActiveOnDevice(date: String?): String =
        "Full access is enabled on this device."
    fun lifetimeActiveOnDevice(): String = "Full access is enabled on this device."
    fun premiumActiveOnDevice(): String = "Full access is enabled on this device."
    fun monthlyOnAccountHelper(date: String?): String = buildString {
        append("Full access is enabled on your account")
        date?.let { append(" until $it") }
        append(".")
    }
    fun lifetimeOnAccountHelper(blockMessage: String): String =
        "Full access is enabled on your account. $blockMessage"
    fun premiumOnAccountHelper(blockMessage: String): String =
        "Full access is enabled on your account. $blockMessage"
    fun freeHelperText(): String =
        "Torve is free software. No subscriptions. No paid tiers."
    fun checkingAccessHelperText(): String =
        "Refreshing your account access from Torve."
    fun deviceNeedsSlot(): String = "This device needs an available activation slot."
    fun deviceNeedsActivation(): String = "This device needs activation."
    fun premiumRequired(): String = "This feature is available to everyone."
}

/** Default implementation using English strings. Used by desktop and as fallback. */
class DefaultPurchaseStringResolver : PurchaseStringResolver
