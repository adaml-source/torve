package com.streamvault.android.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

interface BillingManager {
    sealed class BillingState {
        data object Disconnected : BillingState()
        data object Connecting : BillingState()
        data object Connected : BillingState()
        data class Ready(val formattedPrice: String?) : BillingState()
        data class Error(val message: String) : BillingState()
    }

    sealed class PurchaseResult {
        data class Success(val purchaseToken: String) : PurchaseResult()
        data object AlreadyOwned : PurchaseResult()
        data object Cancelled : PurchaseResult()
        data class Error(val message: String) : PurchaseResult()
    }

    val billingState: StateFlow<BillingState>
    val purchaseResult: StateFlow<PurchaseResult?>

    fun initialize()
    fun launchPurchase(activity: Activity)
    fun queryExistingPurchases()
    fun getFormattedPrice(): String?
    fun clearPurchaseResult()
}
