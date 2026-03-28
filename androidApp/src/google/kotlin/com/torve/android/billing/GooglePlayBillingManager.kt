package com.torve.android.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GooglePlayBillingManager(context: Context) : BillingManager, PurchasesUpdatedListener {

    companion object {
        private const val MONTHLY_PRODUCT_ID = "com.torve.pro.monthly"
        private const val LIFETIME_PRODUCT_ID = "com.torve.pro.lifetime"
    }

    private data class GooglePlayOffer(
        val productType: BillingManager.ProductType,
        val productId: String,
        val billingProductType: String,
        val productDetails: ProductDetails,
        val offerToken: String? = null,
        val formattedPrice: String?,
        val billingDetails: String,
    )

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private val _billingState = MutableStateFlow<BillingManager.BillingState>(BillingManager.BillingState.Disconnected)
    override val billingState: StateFlow<BillingManager.BillingState> = _billingState.asStateFlow()

    private val _purchaseResult = MutableStateFlow<BillingManager.PurchaseResult?>(null)
    override val purchaseResult: StateFlow<BillingManager.PurchaseResult?> = _purchaseResult.asStateFlow()

    private val offersByType = mutableMapOf<BillingManager.ProductType, GooglePlayOffer>()
    private var isInitialized = false

    override fun initialize() {
        if (isInitialized) return
        isInitialized = true
        _billingState.value = BillingManager.BillingState.Connecting
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _billingState.value = BillingManager.BillingState.Connected
                    queryProductDetails()
                } else {
                    _billingState.value = BillingManager.BillingState.Error(
                        result.debugMessage ?: "Billing setup failed",
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                isInitialized = false
                _billingState.value = BillingManager.BillingState.Disconnected
            }
        })
    }

    private fun queryProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(MONTHLY_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(LIFETIME_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()

        billingClient.queryProductDetailsAsync(params) { result: BillingResult, detailsList: List<ProductDetails> ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                _billingState.value = BillingManager.BillingState.Ready(offers = emptyList())
                return@queryProductDetailsAsync
            }

            offersByType.clear()
            detailsList.forEach { details ->
                when (details.productId) {
                    MONTHLY_PRODUCT_ID -> {
                        val offer = details.subscriptionOfferDetails
                            ?.firstOrNull()
                            ?.pricingPhases
                            ?.pricingPhaseList
                            ?.firstOrNull()
                        offersByType[BillingManager.ProductType.MONTHLY] = GooglePlayOffer(
                            productType = BillingManager.ProductType.MONTHLY,
                            productId = MONTHLY_PRODUCT_ID,
                            billingProductType = BillingClient.ProductType.SUBS,
                            productDetails = details,
                            offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken,
                            formattedPrice = offer?.formattedPrice,
                            billingDetails = "Recurring billing",
                        )
                    }

                    LIFETIME_PRODUCT_ID -> {
                        offersByType[BillingManager.ProductType.LIFETIME] = GooglePlayOffer(
                            productType = BillingManager.ProductType.LIFETIME,
                            productId = LIFETIME_PRODUCT_ID,
                            billingProductType = BillingClient.ProductType.INAPP,
                            productDetails = details,
                            formattedPrice = details.oneTimePurchaseOfferDetails?.formattedPrice,
                            billingDetails = "One-time purchase",
                        )
                    }
                }
            }
            _billingState.value = BillingManager.BillingState.Ready(
                offers = offersByType.values.map { offer ->
                    BillingManager.BillingOffer(
                        productType = offer.productType,
                        productId = offer.productId,
                        formattedPrice = offer.formattedPrice,
                        billingDetails = offer.billingDetails,
                    )
                },
            )
        }
    }

    override fun launchPurchase(activity: Activity, productType: BillingManager.ProductType) {
        val offer = offersByType[productType] ?: run {
            _purchaseResult.value = BillingManager.PurchaseResult.Error("Product not available")
            return
        }

        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(offer.productDetails)
        offer.offerToken?.let(productParamsBuilder::setOfferToken)

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _purchaseResult.value = BillingManager.PurchaseResult.Error(
                result.debugMessage ?: "Failed to launch billing flow",
            )
        }
    }

    override fun queryExistingPurchases() {
        queryExistingPurchasesFor(BillingClient.ProductType.SUBS)
        queryExistingPurchasesFor(BillingClient.ProductType.INAPP)
    }

    private fun queryExistingPurchasesFor(productType: String) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(productType)
            .build()

        billingClient.queryPurchasesAsync(params) { result: BillingResult, purchases: List<Purchase> ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync

            purchases.firstOrNull { purchase ->
                purchase.purchaseState == Purchase.PurchaseState.PENDING &&
                    purchase.products.any { it == MONTHLY_PRODUCT_ID || it == LIFETIME_PRODUCT_ID }
            }?.let { pendingPurchase ->
                _purchaseResult.value = BillingManager.PurchaseResult.Pending(
                    message = "Your Google Play purchase is still pending. Finish it in Google Play to unlock Premium.",
                    productId = pendingPurchase.products.firstOrNull(),
                )
                return@queryPurchasesAsync
            }

            purchases.firstOrNull { purchase ->
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    purchase.products.any { it == MONTHLY_PRODUCT_ID || it == LIFETIME_PRODUCT_ID }
            }?.let { ownedPurchase ->
                if (!ownedPurchase.isAcknowledged) {
                    acknowledgePurchase(ownedPurchase)
                }
                _purchaseResult.value = BillingManager.PurchaseResult.AlreadyOwned(
                    productId = ownedPurchase.products.firstOrNull(),
                )
            }
        }
    }

    override fun getOffer(productType: BillingManager.ProductType): BillingManager.BillingOffer? {
        val offer = offersByType[productType] ?: return null
        return BillingManager.BillingOffer(
            productType = offer.productType,
            productId = offer.productId,
            formattedPrice = offer.formattedPrice,
            billingDetails = offer.billingDetails,
        )
    }

    override fun clearPurchaseResult() {
        _purchaseResult.value = null
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    when (purchase.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> {
                            if (!purchase.isAcknowledged) {
                                acknowledgePurchase(purchase)
                            }
                            _purchaseResult.value = BillingManager.PurchaseResult.Success(
                                purchaseToken = purchase.purchaseToken,
                                store = BillingManager.Store.GOOGLE_PLAY,
                                productId = purchase.products.firstOrNull().orEmpty(),
                            )
                        }

                        Purchase.PurchaseState.PENDING -> {
                            _purchaseResult.value = BillingManager.PurchaseResult.Pending(
                                message = "Your Google Play purchase is pending. Complete the payment in Google Play to unlock Premium.",
                                productId = purchase.products.firstOrNull(),
                            )
                        }

                        else -> Unit
                    }
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _purchaseResult.value = BillingManager.PurchaseResult.Cancelled
            }

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _purchaseResult.value = BillingManager.PurchaseResult.AlreadyOwned()
            }

            else -> {
                _purchaseResult.value = BillingManager.PurchaseResult.Error(
                    result.debugMessage ?: "Purchase failed",
                )
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { }
    }
}
