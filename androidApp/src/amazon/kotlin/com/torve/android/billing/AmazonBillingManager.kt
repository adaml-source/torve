package com.torve.android.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.UserDataResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AmazonBillingManager(private val context: Context) : BillingManager, PurchasingListener {

    companion object {
        private const val TAG = "AmazonBilling"
        private const val MONTHLY_SUBSCRIPTION_PARENT = "com.torve.pro.subscription"
        private const val LIFETIME_PRODUCT_ID = "com.torve.pro.lifetime"
        private const val LIFETIME_PRODUCT_ID_AMAZON = "com.torve.pro.lifetime.amazon"
        private const val SAFE_BILLING_INIT_MESSAGE = "Could not connect to the store. Please try again."
        private const val SAFE_PURCHASE_FAILED_MESSAGE = "Purchase could not be completed. Please try again."
        private const val SAFE_PRODUCT_UNAVAILABLE_MESSAGE = "This product is not available right now."
        private val MONTHLY_PRODUCT_IDS = listOf(
            MONTHLY_SUBSCRIPTION_PARENT,
        )
        private val LIFETIME_PRODUCT_IDS = listOf(
            LIFETIME_PRODUCT_ID,
            LIFETIME_PRODUCT_ID_AMAZON,
        )
        private val PRODUCT_IDS = setOf(
            *MONTHLY_PRODUCT_IDS.toTypedArray(),
            *LIFETIME_PRODUCT_IDS.toTypedArray(),
        )
    }

    private data class PendingAmazonPurchase(
        val receiptId: String,
        val productId: String,
    )

    private inline fun logDebug(message: () -> String) {
        if (com.torve.android.BuildConfig.DEBUG) {
            Log.d(TAG, message())
        }
    }

    private inline fun logWarn(message: () -> String) {
        if (com.torve.android.BuildConfig.DEBUG) {
            Log.w(TAG, message())
        }
    }

    private fun logError(
        throwable: Throwable? = null,
        message: String,
    ) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }

    private val _billingState = MutableStateFlow<BillingManager.BillingState>(
        BillingManager.BillingState.Disconnected,
    )
    override val billingState: StateFlow<BillingManager.BillingState> = _billingState.asStateFlow()

    private val _purchaseResult = MutableStateFlow<BillingManager.PurchaseResult?>(null)
    override val purchaseResult: StateFlow<BillingManager.PurchaseResult?> = _purchaseResult.asStateFlow()

    private val offersByType = mutableMapOf<BillingManager.ProductType, BillingManager.BillingOffer>()
    private val productIdByType = mutableMapOf<BillingManager.ProductType, String>()
    private var cachedAmazonUserId: String? = null
    private var pendingAmazonPurchase: PendingAmazonPurchase? = null
    private val productDataRequestPlan = listOf(
        PRODUCT_IDS,
        LIFETIME_PRODUCT_IDS.toSet(),
        setOf(LIFETIME_PRODUCT_ID),
        setOf(LIFETIME_PRODUCT_ID_AMAZON),
    )
    private var productDataRequestIndex = 0
    @Volatile private var isInitialized = false

    private fun emitAmazonSuccess(receiptId: String, productId: String, amazonUserId: String) {
        _purchaseResult.value = BillingManager.PurchaseResult.Success(
            purchaseToken = receiptId,
            store = BillingManager.Store.AMAZON_APPSTORE,
            productId = productId,
            amazonUserId = amazonUserId,
        )
    }

    private fun queuePendingAmazonPurchase(receiptId: String, productId: String) {
        pendingAmazonPurchase = PendingAmazonPurchase(
            receiptId = receiptId,
            productId = productId,
        )
        logDebug {
            "Queued pending Amazon purchase receiptIdPrefix=${receiptId.take(8)}..., productId=$productId while waiting for user data"
        }
        PurchasingService.getUserData()
        _purchaseResult.value = BillingManager.PurchaseResult.Pending(
            message = "Purchase received. Waiting for Amazon account details to finish verification.",
            productId = productId,
        )
    }

    private fun requestCurrentProductDataSet() {
        val skuSet = productDataRequestPlan[productDataRequestIndex]
        PurchasingService.getProductData(skuSet)
        logDebug { "Requested Amazon product data for SKUs=$skuSet attempt=${productDataRequestIndex + 1}/${productDataRequestPlan.size}" }
    }

    private fun tryNextProductDataFallback(reason: String): Boolean {
        if (productDataRequestIndex >= productDataRequestPlan.lastIndex) {
            return false
        }
        productDataRequestIndex += 1
        logWarn {
            "Amazon product data request fallback after $reason. Retrying with SKUs=${productDataRequestPlan[productDataRequestIndex]}"
        }
        requestCurrentProductDataSet()
        return true
    }

    override fun initialize() {
        // Allow re-init when stuck in Error state (e.g. Amazon SDK returned FAILED
        // during LAT catalog propagation). Skip only if already connecting or ready.
        if (isInitialized && _billingState.value !is BillingManager.BillingState.Error) return
        isInitialized = true
        productDataRequestIndex = 0
        _billingState.value = BillingManager.BillingState.Connecting
        val init = Runnable {
            try {
                PurchasingService.registerListener(context, this)
                runCatching { PurchasingService.getUserData() }
                requestCurrentProductDataSet()
            } catch (e: Exception) {
                logError(e, "Failed to initialize Amazon IAP")
                _billingState.value = BillingManager.BillingState.Error(
                    SAFE_BILLING_INIT_MESSAGE,
                )
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            init.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(init)
        }
    }

    override fun launchPurchase(activity: Activity, productType: BillingManager.ProductType) {
        val productId = productIdByType[productType] ?: run {
            _purchaseResult.value = BillingManager.PurchaseResult.Error("Product not available")
            return
        }
        try {
            Log.i(TAG, "launchPurchase productId=$productId type=$productType")
            PurchasingService.getUserData()
            PurchasingService.purchase(productId)
        } catch (e: Exception) {
            logError(e, "Failed to launch purchase")
            _purchaseResult.value = BillingManager.PurchaseResult.Error(
                SAFE_PURCHASE_FAILED_MESSAGE,
            )
        }
    }

    override fun queryExistingPurchases() {
        try {
            PurchasingService.getUserData()
            PurchasingService.getPurchaseUpdates(true)
            logDebug { "Querying existing purchases" }
        } catch (e: Exception) {
            logError(e, "Failed to query existing purchases")
        }
    }

    /**
     * Amazon's Appstore SDK uses an event-callback model rather than a
     * suspend-friendly query, so this surface — used by the Google Play
     * client-driven restore flow — is intentionally a no-op for the
     * Amazon variant. The Amazon restore path stays on
     * [com.torve.presentation.subscription.SubscriptionViewModel.restoreAmazonPurchases]
     * which already drives PurchasingService.getPurchaseUpdates and
     * routes responses through the receipt callback.
     */
    override suspend fun queryActivePurchases(): List<BillingManager.ActivePurchase> = emptyList()

    override fun getOffer(productType: BillingManager.ProductType): BillingManager.BillingOffer? {
        return offersByType[productType]
    }

    private fun resolveMonthlySku(productData: Map<String, com.amazon.device.iap.model.Product>): String? {
        return MONTHLY_PRODUCT_IDS.firstOrNull { sku -> productData.containsKey(sku) }
    }

    private fun resolveLifetimeSku(productData: Map<String, com.amazon.device.iap.model.Product>): String? {
        return LIFETIME_PRODUCT_IDS.firstOrNull { sku -> productData.containsKey(sku) }
    }

    override fun clearPurchaseResult() {
        _purchaseResult.value = null
    }

    override fun onUserDataResponse(response: UserDataResponse) {
        when (response.requestStatus) {
            UserDataResponse.RequestStatus.SUCCESSFUL -> {
                val userId = response.userData?.userId?.takeIf { it.isNotBlank() }
                cachedAmazonUserId = userId
                val pending = pendingAmazonPurchase
                if (pending != null && !userId.isNullOrBlank()) {
                    pendingAmazonPurchase = null
                    emitAmazonSuccess(
                        receiptId = pending.receiptId,
                        productId = pending.productId,
                        amazonUserId = userId,
                    )
                }
            }

            else -> {
                logWarn { "onUserDataResponse: ${response.requestStatus}" }
            }
        }
    }

    override fun onProductDataResponse(response: ProductDataResponse) {
        logDebug { "onProductDataResponse status=${response.requestStatus} keys=${response.productData?.keys} unavailable=${response.unavailableSkus}" }
        when (response.requestStatus) {
            ProductDataResponse.RequestStatus.SUCCESSFUL -> {
                offersByType.clear()
                productIdByType.clear()

                resolveMonthlySku(response.productData)?.let { sku ->
                    val product = response.productData[sku] ?: return@let
                    offersByType[BillingManager.ProductType.MONTHLY] = BillingManager.BillingOffer(
                        productType = BillingManager.ProductType.MONTHLY,
                        productId = sku,
                        formattedPrice = product.price,
                        billingDetails = "Recurring billing",
                    )
                    productIdByType[BillingManager.ProductType.MONTHLY] = sku
                }

                val lifetimeSku = resolveLifetimeSku(response.productData)
                lifetimeSku?.let { sku ->
                    val product = response.productData[sku] ?: return@let
                    offersByType[BillingManager.ProductType.LIFETIME] = BillingManager.BillingOffer(
                        productType = BillingManager.ProductType.LIFETIME,
                        productId = sku,
                        formattedPrice = product.price,
                        billingDetails = "One-time purchase",
                    )
                    productIdByType[BillingManager.ProductType.LIFETIME] = sku
                }

                val offers = offersByType.values.toList()
                if (offers.isEmpty()) {
                    val unavailableSkus = response.unavailableSkus.orEmpty().sorted()
                    val reason = if (unavailableSkus.isNotEmpty()) {
                        "no supported SKUs from response unavailable=${unavailableSkus.joinToString(", ")}"
                    } else {
                        "empty product data response"
                    }
                    if (tryNextProductDataFallback(reason)) {
                        return
                    }
                    if (unavailableSkus.isNotEmpty()) {
                        logError(message = "Amazon Appstore did not return product data for: ${unavailableSkus.joinToString(", ")}.")
                    } else {
                        logError(message = "Amazon Appstore returned no product data. Check Live App Testing and Amazon SKU setup.")
                    }
                    _billingState.value = BillingManager.BillingState.Error(SAFE_PRODUCT_UNAVAILABLE_MESSAGE)
                } else {
                    _billingState.value = BillingManager.BillingState.Ready(offers = offers)
                }
            }

            ProductDataResponse.RequestStatus.FAILED,
            ProductDataResponse.RequestStatus.NOT_SUPPORTED,
            -> {
                if (tryNextProductDataFallback("status=${response.requestStatus}")) {
                    return
                }
                logError(message = "Amazon product data request failed: ${response.requestStatus}")
                _billingState.value = BillingManager.BillingState.Error(SAFE_BILLING_INIT_MESSAGE)
            }

            else -> {
                logWarn { "Amazon product data returned unexpected status: ${response.requestStatus}" }
                _billingState.value = BillingManager.BillingState.Error(SAFE_BILLING_INIT_MESSAGE)
            }
        }
    }

    override fun onPurchaseResponse(response: PurchaseResponse) {
        Log.i(TAG, "onPurchaseResponse status=${response.requestStatus} sku=${response.receipt?.sku}")
        when (response.requestStatus) {
            PurchaseResponse.RequestStatus.SUCCESSFUL -> {
                val receipt = response.receipt
                val amazonUserId = response.userData?.userId
                    ?.takeIf { it.isNotBlank() }
                    ?: cachedAmazonUserId
                val productId = receipt.sku?.takeIf { it.isNotBlank() } ?: LIFETIME_PRODUCT_ID
                PurchasingService.notifyFulfillment(
                    receipt.receiptId,
                    com.amazon.device.iap.model.FulfillmentResult.FULFILLED,
                )
                if (amazonUserId.isNullOrBlank()) {
                    queuePendingAmazonPurchase(
                        receiptId = receipt.receiptId,
                        productId = productId,
                    )
                } else {
                    emitAmazonSuccess(
                        receiptId = receipt.receiptId,
                        productId = productId,
                        amazonUserId = amazonUserId,
                    )
                }
            }

            PurchaseResponse.RequestStatus.ALREADY_PURCHASED -> {
                PurchasingService.getUserData()
                PurchasingService.getPurchaseUpdates(true)
                _purchaseResult.value = BillingManager.PurchaseResult.AlreadyOwned()
            }

            PurchaseResponse.RequestStatus.FAILED -> {
                _purchaseResult.value = BillingManager.PurchaseResult.Error("Purchase failed")
            }

            PurchaseResponse.RequestStatus.NOT_SUPPORTED -> {
                _purchaseResult.value = BillingManager.PurchaseResult.Error(
                    "In-app purchases are not supported on this device",
                )
            }

            else -> {
                _purchaseResult.value = BillingManager.PurchaseResult.Error("Purchase failed")
            }
        }
    }

    override fun onPurchaseUpdatesResponse(response: PurchaseUpdatesResponse) {
        when (response.requestStatus) {
            PurchaseUpdatesResponse.RequestStatus.SUCCESSFUL -> {
                val amazonUserId = response.userData?.userId
                    ?.takeIf { it.isNotBlank() }
                    ?: cachedAmazonUserId
                val matchingReceipt = response.receipts
                    .filter { receipt ->
                        receipt.sku in PRODUCT_IDS && !receipt.isCanceled
                    }
                    .maxByOrNull { receipt ->
                        when (receipt.sku) {
                            in LIFETIME_PRODUCT_IDS -> 2
                            in MONTHLY_PRODUCT_IDS -> 1
                            else -> 0
                        }
                    }

                if (matchingReceipt != null) {
                    val productId = matchingReceipt.sku?.takeIf { it.isNotBlank() } ?: LIFETIME_PRODUCT_ID
                    if (amazonUserId.isNullOrBlank()) {
                        queuePendingAmazonPurchase(
                            receiptId = matchingReceipt.receiptId,
                            productId = productId,
                        )
                    } else {
                        emitAmazonSuccess(
                            receiptId = matchingReceipt.receiptId,
                            productId = productId,
                            amazonUserId = amazonUserId,
                        )
                    }
                }

                if (response.hasMore()) {
                    PurchasingService.getPurchaseUpdates(false)
                }
            }

            PurchaseUpdatesResponse.RequestStatus.FAILED,
            PurchaseUpdatesResponse.RequestStatus.NOT_SUPPORTED,
            -> {
                logError(message = "Purchase updates failed: ${response.requestStatus}")
            }

            else -> {
                logWarn { "Unknown purchase updates status: ${response.requestStatus}" }
            }
        }
    }
}
