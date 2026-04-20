package com.torve.android.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
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
        private const val TAG = "Billing"
        private const val MONTHLY_PRODUCT_ID = "com.torve.pro.monthly"
        private const val MONTHLY_PRODUCT_ID_SUBSCRIPTION = "com.torve.pro.subscription"
        private const val LIFETIME_PRODUCT_ID = "com.torve.pro.lifetime"
        private const val SETUP_TIMEOUT_MS = 15_000L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private val MONTHLY_PRODUCT_IDS = listOf(
            MONTHLY_PRODUCT_ID_SUBSCRIPTION,
            MONTHLY_PRODUCT_ID,
        )
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var setupTimeoutRunnable: Runnable? = null
    private var reconnectAttempts = 0

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
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build(),
        )
        .build()

    private val _billingState = MutableStateFlow<BillingManager.BillingState>(BillingManager.BillingState.Disconnected)
    override val billingState: StateFlow<BillingManager.BillingState> = _billingState.asStateFlow()

    private val _purchaseResult = MutableStateFlow<BillingManager.PurchaseResult?>(null)
    override val purchaseResult: StateFlow<BillingManager.PurchaseResult?> = _purchaseResult.asStateFlow()

    private val offersByType = mutableMapOf<BillingManager.ProductType, GooglePlayOffer>()
    @Volatile private var isInitialized = false

    override fun initialize() {
        // Allow re-init when stuck in Disconnected or Error — only short-circuit
        // while actively Connecting / Connected / Ready so we don't double-start.
        val current = _billingState.value
        if (isInitialized &&
            current !is BillingManager.BillingState.Disconnected &&
            current !is BillingManager.BillingState.Error
        ) return
        isInitialized = true
        _billingState.value = BillingManager.BillingState.Connecting
        Log.d(TAG, "initialize: starting BillingClient connection (ready=${billingClient.isReady})")
        scheduleSetupTimeout()
        try {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        reconnectAttempts = 0
                        _billingState.value = BillingManager.BillingState.Connected
                        queryProductDetails()
                    } else {
                        Log.w(TAG, "onBillingSetupFinished failed: code=${result.responseCode} msg=${result.debugMessage}")
                        clearSetupTimeout()
                        isInitialized = false
                        _billingState.value = BillingManager.BillingState.Error(
                            safeBillingMessage(result.responseCode),
                        )
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "onBillingServiceDisconnected")
                    clearSetupTimeout()
                    isInitialized = false
                    _billingState.value = BillingManager.BillingState.Disconnected
                    scheduleReconnect()
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "startConnection threw", t)
            clearSetupTimeout()
            isInitialized = false
            _billingState.value = BillingManager.BillingState.Error(
                "Could not connect to the store. Please try again.",
            )
        }
    }

    private fun scheduleSetupTimeout() {
        setupTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            val state = _billingState.value
            if (state is BillingManager.BillingState.Connecting ||
                state is BillingManager.BillingState.Connected
            ) {
                Log.w(TAG, "Billing setup timed out after ${SETUP_TIMEOUT_MS}ms in state=$state")
                isInitialized = false
                _billingState.value = BillingManager.BillingState.Error(
                    "Could not connect to the store. Please try again.",
                )
            }
        }
        setupTimeoutRunnable = r
        mainHandler.postDelayed(r, SETUP_TIMEOUT_MS)
    }

    private fun clearSetupTimeout() {
        setupTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        setupTimeoutRunnable = null
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached; leaving state=Disconnected")
            return
        }
        reconnectAttempts += 1
        val delay = RECONNECT_DELAY_MS * reconnectAttempts
        Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempts in ${delay}ms")
        mainHandler.postDelayed({ initialize() }, delay)
    }

    private fun queryProductDetails() {
        // Play Billing Library 7+ requires every queryProductDetailsAsync call
        // to contain products of a single type. We query SUBS and INAPP
        // separately and only emit Ready once both have completed.
        offersByType.clear()
        var subsDone = false
        var inappDone = false

        fun maybeEmitReady() {
            if (!subsDone || !inappDone) return
            clearSetupTimeout()
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

        val subsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                MONTHLY_PRODUCT_IDS.map { sku ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(sku)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()

        billingClient.queryProductDetailsAsync(subsParams) { result, detailsList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryProductDetails(SUBS) failed: code=${result.responseCode} msg=${result.debugMessage}")
            } else {
                Log.d(TAG, "queryProductDetails(SUBS) OK: ${detailsList.size} products (${detailsList.map { it.productId }})")
                detailsList.forEach { details ->
                    if (details.productId in MONTHLY_PRODUCT_IDS) {
                        val phase = details.subscriptionOfferDetails
                            ?.firstOrNull()
                            ?.pricingPhases
                            ?.pricingPhaseList
                            ?.firstOrNull()
                        offersByType[BillingManager.ProductType.MONTHLY] = GooglePlayOffer(
                            productType = BillingManager.ProductType.MONTHLY,
                            productId = details.productId,
                            billingProductType = BillingClient.ProductType.SUBS,
                            productDetails = details,
                            offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken,
                            formattedPrice = phase?.formattedPrice,
                            billingDetails = "Recurring billing",
                        )
                    }
                }
            }
            subsDone = true
            maybeEmitReady()
        }

        val inappParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(LIFETIME_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()

        billingClient.queryProductDetailsAsync(inappParams) { result, detailsList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryProductDetails(INAPP) failed: code=${result.responseCode} msg=${result.debugMessage}")
            } else {
                Log.d(TAG, "queryProductDetails(INAPP) OK: ${detailsList.size} products (${detailsList.map { it.productId }})")
                detailsList.forEach { details ->
                    if (details.productId == LIFETIME_PRODUCT_ID) {
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
            inappDone = true
            maybeEmitReady()
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
                safeBillingMessage(result.responseCode),
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
                    purchase.products.any { it in MONTHLY_PRODUCT_IDS || it == LIFETIME_PRODUCT_ID }
            }?.let { pendingPurchase ->
                _purchaseResult.value = BillingManager.PurchaseResult.Pending(
                    message = "Your Google Play purchase is still pending. Finish it in Google Play to unlock Premium.",
                    productId = pendingPurchase.products.firstOrNull(),
                )
                return@queryPurchasesAsync
            }

            purchases.firstOrNull { purchase ->
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    purchase.products.any { it in MONTHLY_PRODUCT_IDS || it == LIFETIME_PRODUCT_ID }
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
                    safeBillingMessage(result.responseCode),
                )
            }
        }
    }

    private fun safeBillingMessage(responseCode: Int): String = when (responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "In-app purchases are not available right now."
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
            "Could not connect to the store. Please try again."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
            "This product is not available right now."
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
            "You already own this product."
        BillingClient.BillingResponseCode.NETWORK_ERROR ->
            "Could not connect. Please check your internet connection."
        else ->
            "Purchase could not be completed. Please try again."
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { }
    }
}
