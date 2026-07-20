package com.torve.android.i18n

import android.content.Context
import com.torve.android.R
import com.torve.presentation.subscription.PurchaseStringResolver

/**
 * Android implementation that resolves purchase strings from string resources.
 */
class AndroidPurchaseStringResolver(private val context: Context) : PurchaseStringResolver {
    private fun s(resId: Int): String = context.getString(resId)
    private fun s(resId: Int, vararg args: Any): String = context.getString(resId, *args)

    // Plan labels
    override fun premiumMonthly() = s(R.string.purchase_premium_monthly)
    override fun premiumLifetime() = s(R.string.purchase_premium_lifetime)
    override fun premiumGeneric() = s(R.string.purchase_premium_generic)

    // Purchase status titles
    override fun purchaseReceivedTitle() = s(R.string.purchase_received_title)
    override fun purchaseVerifiedTitle() = s(R.string.purchase_verified_title)
    override fun purchaseRestoredTitle() = s(R.string.purchase_restored_title)
    override fun verificationNotFinishedTitle() = s(R.string.purchase_verification_not_finished_title)
    override fun verificationUnavailableTitle() = s(R.string.purchase_service_unavailable_title)
    override fun signInRequiredTitle() = s(R.string.purchase_sign_in_required_title)
    override fun purchaseConflictTitle() = s(R.string.purchase_conflict_title)
    override fun nothingToRestoreTitle() = s(R.string.purchase_nothing_to_restore_title)

    // Purchase status messages
    override fun purchaseReceivedSuffix() = s(R.string.purchase_received_message, "")
    override fun amazonCallbackPendingDefault() = s(R.string.purchase_received_default)
    override fun verificationNotFinishedMessage() = s(R.string.purchase_verification_not_finished_message)
    override fun verificationUnavailableRetry() = s(R.string.purchase_service_unavailable_retry)
    override fun verificationUnavailableRestore() = s(R.string.purchase_service_unavailable_restore)
    override fun signInAmazonRestore() = s(R.string.purchase_sign_in_amazon_restore)
    override fun signInBuy(storeLabel: String) = s(R.string.purchase_sign_in_buy, storeLabel)
    override fun signInRestore(storeLabel: String) = s(R.string.purchase_sign_in_restore, storeLabel)
    override fun purchaseConflictMessage() = s(R.string.purchase_conflict_message)
    override fun nothingToRestoreAmazon() = s(R.string.purchase_nothing_to_restore_amazon)
    override fun nothingToRestoreStore(storeLabel: String) = s(R.string.purchase_nothing_to_restore_store, storeLabel)

    // Verified / restored messages
    override fun activeOnDevice(planLabel: String) = s(R.string.purchase_active_on_device, planLabel)
    override fun activeOnDeviceUntil(planLabel: String, date: String) = s(R.string.purchase_active_until, planLabel, date)
    override fun needsDeviceSlot(planLabel: String) = s(R.string.purchase_needs_device_slot, planLabel)
    override fun restoredActiveOnDevice(planLabel: String) = s(R.string.purchase_restored_active, planLabel)
    override fun restoredActiveOnDeviceUntil(planLabel: String, date: String) = s(R.string.purchase_restored_active_until, planLabel, date)
    override fun restoredNeedsDeviceSlot(planLabel: String) = s(R.string.purchase_restored_needs_slot, planLabel)

    // Error messages
    override fun googleVerifyFailed() = s(R.string.purchase_google_verify_failed)
    override fun appleVerifyFailed() = s(R.string.purchase_apple_verify_failed)
    override fun storeRestoreFailed(storeLabel: String) = s(R.string.purchase_store_restore_failed, storeLabel)

    // Verification resend UX
    override fun verificationEmailSent() = s(R.string.verify_email_sent)
    override fun verificationEmailWaitBeforeResend() = s(R.string.verify_wait_before_resend)
    override fun verificationEmailSendFailed() = s(R.string.verify_email_not_verified)

    // Access presentation
    override fun freeLabel() = s(R.string.access_free_label)
    override fun freeHelperText() = s(R.string.access_free_helper)
    override fun monthlyActiveUntil(date: String) = s(R.string.access_monthly_active_until, date)
    override fun monthlyActive() = s(R.string.access_monthly_active)
    override fun lifetimeActive() = s(R.string.access_lifetime_active)
    override fun premiumActive() = s(R.string.access_premium_active)
    override fun monthlyOnAccount() = s(R.string.access_monthly_on_account)
    override fun lifetimeOnAccount() = s(R.string.access_lifetime_on_account)
    override fun premiumOnAccount() = s(R.string.access_premium_on_account)
    override fun monthlyActiveOnDevice(date: String?) = if (date != null) {
        s(R.string.access_monthly_active_device, date)
    } else {
        s(R.string.access_monthly_active_device_no_date)
    }
    override fun lifetimeActiveOnDevice() = s(R.string.access_lifetime_active_device)
    override fun premiumActiveOnDevice() = s(R.string.access_premium_active_device)
    override fun monthlyOnAccountHelper(date: String?) = if (date != null) {
        s(R.string.access_monthly_on_account_helper, date)
    } else {
        s(R.string.access_monthly_on_account_helper_no_date)
    }
    override fun lifetimeOnAccountHelper(blockMessage: String) = s(R.string.access_lifetime_on_account_helper, blockMessage)
    override fun premiumOnAccountHelper(blockMessage: String) = s(R.string.access_premium_on_account_helper, blockMessage)
    override fun deviceNeedsSlot() = s(R.string.access_device_needs_slot)
    override fun deviceNeedsActivation() = s(R.string.access_device_needs_activation)
    override fun premiumRequired() = s(R.string.error_premium_required)
}
