package com.torve.android.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GooglePlayBillingManager(context: Context) : BillingManager {
    private val _billingState = MutableStateFlow<BillingManager.BillingState>(BillingManager.BillingState.Disconnected)
    override val billingState: StateFlow<BillingManager.BillingState> = _billingState.asStateFlow()

    private val _purchaseResult = MutableStateFlow<BillingManager.PurchaseResult?>(null)
    override val purchaseResult: StateFlow<BillingManager.PurchaseResult?> = _purchaseResult.asStateFlow()

    override fun initialize() = Unit

    override fun launchPurchase(activity: Activity, productType: BillingManager.ProductType) {
        _purchaseResult.value = BillingManager.PurchaseResult.Error(
            "Torve is free software. There are no subscriptions or paid tiers.",
        )
    }

    override fun queryExistingPurchases() = Unit

    override suspend fun queryActivePurchases(): List<BillingManager.ActivePurchase> = emptyList()

    override fun getOffer(productType: BillingManager.ProductType): BillingManager.BillingOffer? = null

    override fun clearPurchaseResult() {
        _purchaseResult.value = null
    }
}
