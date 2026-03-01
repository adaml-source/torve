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
import kotlinx.coroutines.flow.asStateFlow

class GooglePlayBillingManager(context: Context) : PurchasesUpdatedListener {

    companion object {
        private const val PRODUCT_ID = "torve_pro_lifetime"
    }

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

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private val _billingState = MutableStateFlow<BillingState>(BillingState.Disconnected)
    val billingState = _billingState.asStateFlow()

    private val _purchaseResult = MutableStateFlow<PurchaseResult?>(null)
    val purchaseResult = _purchaseResult.asStateFlow()

    private var productDetails: com.android.billingclient.api.ProductDetails? = null

    fun initialize() {
        _billingState.value = BillingState.Connecting
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _billingState.value = BillingState.Connected
                    queryProductDetails()
                } else {
                    _billingState.value = BillingState.Error(
                        result.debugMessage ?: "Billing setup failed"
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                _billingState.value = BillingState.Disconnected
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
                _billingState.value = BillingState.Ready(formattedPrice = price)
            } else {
                _billingState.value = BillingState.Ready(formattedPrice = null)
            }
        }
    }

    fun launchPurchase(activity: Activity) {
        val details = productDetails ?: run {
            _purchaseResult.value = PurchaseResult.Error("Product not available")
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
            _purchaseResult.value = PurchaseResult.Error(
                result.debugMessage ?: "Failed to launch billing flow"
            )
        }
    }

    fun queryExistingPurchases() {
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
                    _purchaseResult.value = PurchaseResult.AlreadyOwned
                }
            }
        }
    }

    fun getFormattedPrice(): String? {
        val state = _billingState.value
        return if (state is BillingState.Ready) state.formattedPrice else null
    }

    fun clearPurchaseResult() {
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
                        _purchaseResult.value = PurchaseResult.Success(purchase.purchaseToken)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _purchaseResult.value = PurchaseResult.Cancelled
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _purchaseResult.value = PurchaseResult.AlreadyOwned
            }
            else -> {
                _purchaseResult.value = PurchaseResult.Error(
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
