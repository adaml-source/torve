package com.torve.android.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

interface BillingManager {
    enum class Store {
        GOOGLE_PLAY,
        AMAZON_APPSTORE,
    }

    sealed class BillingState {
        data object Disconnected : BillingState()
        data object Connecting : BillingState()
        data object Connected : BillingState()
        data class Ready(val formattedPrice: String?) : BillingState()
        data class Error(val message: String) : BillingState()
    }

    sealed class PurchaseResult {
        data class Success(
            val purchaseToken: String,
            val store: Store = Store.GOOGLE_PLAY,
            val productId: String = "",
            val amazonUserId: String? = null,
        ) : PurchaseResult()
        data class Pending(val message: String) : PurchaseResult()
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
