package com.torve.presentation.subscription

import com.torve.domain.model.SubscriptionTier

/**
 * Platform-injectable string resolver for purchase/subscription UI messages.
 * Android implementation resolves from string resources; other platforms use English defaults.
 */
interface PurchaseStringResolver {
    // Plan labels
    fun premiumMonthly(): String = "Premium Monthly"
    fun premiumLifetime(): String = "Premium Lifetime"
    fun premiumGeneric(): String = "Premium"
    fun premiumPlanLabel(tier: SubscriptionTier): String = when (tier) {
        SubscriptionTier.MONTHLY -> premiumMonthly()
        SubscriptionTier.LIFETIME -> premiumLifetime()
        SubscriptionTier.FREE -> premiumGeneric()
    }

    // Purchase status titles
    fun purchaseReceivedTitle(): String = "Purchase received"
    fun purchaseVerifiedTitle(): String = "Purchase verified"
    fun purchaseRestoredTitle(): String = "Purchase restored"
    fun verificationNotFinishedTitle(): String = "Verification not finished"
    fun verificationUnavailableTitle(): String = "Verification service unavailable"
    fun signInRequiredTitle(): String = "Sign in required"
    fun purchaseConflictTitle(): String = "Purchase linked to another account"
    fun nothingToRestoreTitle(): String = "Nothing to restore"
    fun verificationPendingTitle(): String = "Verification pending"

    // Purchase status messages
    fun amazonCallbackPendingDefault(): String =
        "Amazon completed the purchase. Torve is waiting for account details to finish verification."
    fun purchaseReceivedSuffix(): String =
        "If this does not finish shortly, choose Restore Purchase."
    fun verificationNotFinishedMessage(): String =
        "Amazon completed the purchase, but Torve could not confirm it yet. Choose Retry Verification or Restore Purchase."
    fun verificationUnavailableRetry(): String =
        "Your Amazon purchase info is saved, but Torve cannot reach the verification service right now. Choose Retry Verification again shortly."
    fun verificationUnavailableRestore(): String =
        "Torve cannot reach the verification service right now. Please try Restore Purchase again shortly."
    fun signInAmazonRestore(): String =
        "Sign in to Torve before restoring Amazon Premium on this device."
    fun signInBuy(storeLabel: String): String =
        "Sign in to Torve before buying Premium through $storeLabel on this device."
    fun signInRestore(storeLabel: String): String =
        "Sign in to Torve before restoring Premium from $storeLabel on this device."
    fun purchaseConflictMessage(): String =
        "This Amazon purchase is already linked to a different Torve account. Sign in with the original account or contact support."
    fun nothingToRestoreAmazon(): String =
        "No Amazon premium purchase was found for this Torve account."
    fun nothingToRestoreStore(storeLabel: String): String =
        "No premium purchase was found for this Torve account on $storeLabel."

    // Verified / restored messages
    fun activeOnDevice(planLabel: String): String =
        "$planLabel is active on this device."
    fun activeOnDeviceUntil(planLabel: String, date: String): String =
        "$planLabel is active on this device until $date."
    fun needsDeviceSlot(planLabel: String): String =
        "$planLabel is linked to this account, but this device still needs an available slot."
    fun restoredActiveOnDevice(planLabel: String): String =
        "$planLabel was restored and is active on this device."
    fun restoredActiveOnDeviceUntil(planLabel: String, date: String): String =
        "$planLabel was restored and is active on this device until $date."
    fun restoredNeedsDeviceSlot(planLabel: String): String =
        "$planLabel was restored for this account, but this device still needs an available slot."

    // Error messages
    fun googleVerifyFailed(): String =
        "Google Play purchase could not be verified. Try Restore Purchase again after signing in."
    fun appleVerifyFailed(): String =
        "Apple purchase could not be verified. Try Restore Purchase again after signing in."
    fun storeRestoreFailed(storeLabel: String): String =
        "$storeLabel restore could not be completed. Try again after signing in."
    fun localPurchaseDisabled(): String =
        "Local purchase activation is disabled. Verify purchases through the store restore flow."
    fun rebateCodeBackendPending(): String =
        "Code accepted, but premium access must still be confirmed by the Torve backend."

    // Feature names (shared domain PremiumFeature)
    fun featureStreamPlayback(): String = "Stream Playback"
    fun featureDownloads(): String = "Downloads"
    fun featureChannels(): String = "Channels"
    fun featureMultiCloud(): String = "Multi-Cloud"
    fun featureAdvancedFilters(): String = "Advanced Filters"

    // Amazon pending verification messages (SubscriptionUiState)
    fun pendingSignInMessage(): String =
        "Your Amazon purchase is saved. Sign in to Torve, then choose Retry Verification to finish Premium activation."
    fun pendingBackendUnavailableMessage(): String =
        "Your Amazon purchase info is saved, but Torve cannot reach the verification service right now. Choose Retry Verification again shortly."
    fun pendingVerificationNotFinishedMessage(): String =
        "Amazon completed the purchase, but Torve could not confirm it yet. Choose Retry Verification or Restore Purchase."
    fun pendingVerificationWaitingMessage(): String =
        "Your Amazon purchase is waiting to be verified. Choose Retry Verification to finish Premium activation."
    fun pendingRetryMessage(): String =
        "Sign in to Torve, then choose Retry Verification to finish Premium activation."

    // Access presentation
    fun monthlyActiveUntil(date: String): String = "Premium Monthly active until $date"
    fun monthlyActive(): String = "Premium Monthly active"
    fun lifetimeActive(): String = "Premium Lifetime active"
    fun premiumActive(): String = "Premium active"
    fun monthlyOnAccount(): String = "Premium Monthly on account"
    fun lifetimeOnAccount(): String = "Premium Lifetime on account"
    fun premiumOnAccount(): String = "Premium active on account"
    fun freeLabel(): String = "Free"
    fun monthlyActiveOnDevice(date: String?): String =
        date?.let { "Premium Monthly is active on this device until $it." }
            ?: "Premium Monthly is active on this device."
    fun lifetimeActiveOnDevice(): String = "Premium Lifetime is active on this device."
    fun premiumActiveOnDevice(): String = "Premium is active on this device."
    fun monthlyOnAccountHelper(date: String?): String = buildString {
        append("Premium Monthly is active on your account")
        date?.let { append(" until $it") }
        append(".")
    }
    fun lifetimeOnAccountHelper(blockMessage: String): String =
        "Premium Lifetime is active on your account. $blockMessage"
    fun premiumOnAccountHelper(blockMessage: String): String =
        "Premium is active on your account. $blockMessage"
    fun freeHelperText(): String =
        "Torve is free to download. Choose monthly or lifetime access."
    fun deviceNeedsSlot(): String = "This device needs an available activation slot."
    fun deviceNeedsActivation(): String = "This device needs activation."
}

/** Default implementation using English strings. Used by desktop and as fallback. */
class DefaultPurchaseStringResolver : PurchaseStringResolver
