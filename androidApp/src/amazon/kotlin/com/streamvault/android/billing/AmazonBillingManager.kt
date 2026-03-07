package com.streamvault.android.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AmazonBillingManager(context: Context) : BillingManager {
    private val _billingState = MutableStateFlow<BillingManager.BillingState>(
        BillingManager.BillingState.Ready(formattedPrice = null)
    )
    override val billingState: StateFlow<BillingManager.BillingState> = _billingState.asStateFlow()

    private val _purchaseResult = MutableStateFlow<BillingManager.PurchaseResult?>(null)
    override val purchaseResult: StateFlow<BillingManager.PurchaseResult?> = _purchaseResult.asStateFlow()

    override fun initialize() { /* no-op */ }
    override fun launchPurchase(activity: Activity) {
        _purchaseResult.value = BillingManager.PurchaseResult.Error("Purchases not available on this platform")
    }
    override fun queryExistingPurchases() { /* no-op */ }
    override fun getFormattedPrice(): String? = null
    override fun clearPurchaseResult() { _purchaseResult.value = null }
}
