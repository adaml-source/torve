package com.streamvault.android.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetailsResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GooglePlayBillingManager(context: Context) : BillingManager, PurchasesUpdatedListener {

    companion object {
        private const val PRODUCT_ID = "torve_pro_lifetime"
    }

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private val _billingState = MutableStateFlow<BillingManager.BillingState>(BillingManager.BillingState.Disconnected)
    override val billingState: StateFlow<BillingManager.BillingState> = _billingState.asStateFlow()

    private val _purchaseResult = MutableStateFlow<BillingManager.PurchaseResult?>(null)
    override val purchaseResult: StateFlow<BillingManager.PurchaseResult?> = _purchaseResult.asStateFlow()

    private var productDetails: com.android.billingclient.api.ProductDetails? = null

    override fun initialize() {
        _billingState.value = BillingManager.BillingState.Connecting
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _billingState.value = BillingManager.BillingState.Connected
                    queryProductDetails()
                } else {
                    _billingState.value = BillingManager.BillingState.Error(
                        result.debugMessage ?: "Billing setup failed"
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                _billingState.value = BillingManager.BillingState.Disconnected
            }
        })
    }

    private fun queryProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(params) { result: BillingResult, detailsList: List<com.android.billingclient.api.ProductDetails> ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && detailsList.isNotEmpty()) {
                productDetails = detailsList.first()
                val price = detailsList.first().oneTimePurchaseOfferDetails?.formattedPrice
                _billingState.value = BillingManager.BillingState.Ready(formattedPrice = price)
            } else {
                _billingState.value = BillingManager.BillingState.Ready(formattedPrice = null)
            }
        }
    }

    override fun launchPurchase(activity: Activity) {
        val details = productDetails ?: run {
            _purchaseResult.value = BillingManager.PurchaseResult.Error("Product not available")
            return
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _purchaseResult.value = BillingManager.PurchaseResult.Error(
                result.debugMessage ?: "Failed to launch billing flow"
            )
        }
    }

    override fun queryExistingPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { result: BillingResult, purchases: List<Purchase> ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val lifetimePurchase = purchases.firstOrNull { purchase ->
                    purchase.products.contains(PRODUCT_ID) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                if (lifetimePurchase != null) {
                    if (!lifetimePurchase.isAcknowledged) {
                        acknowledgePurchase(lifetimePurchase)
                    }
                    _purchaseResult.value = BillingManager.PurchaseResult.AlreadyOwned
                }
            }
        }
    }

    override fun getFormattedPrice(): String? {
        val state = _billingState.value
        return if (state is BillingManager.BillingState.Ready) state.formattedPrice else null
    }

    override fun clearPurchaseResult() {
        _purchaseResult.value = null
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        if (!purchase.isAcknowledged) {
                            acknowledgePurchase(purchase)
                        }
                        _purchaseResult.value = BillingManager.PurchaseResult.Success(purchase.purchaseToken)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _purchaseResult.value = BillingManager.PurchaseResult.Cancelled
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _purchaseResult.value = BillingManager.PurchaseResult.AlreadyOwned
            }
            else -> {
                _purchaseResult.value = BillingManager.PurchaseResult.Error(
                    result.debugMessage ?: "Purchase failed"
                )
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { /* acknowledged */ }
    }
}
