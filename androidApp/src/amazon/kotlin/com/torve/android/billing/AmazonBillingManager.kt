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
        private const val MONTHLY_SUBSCRIPTION_TERM = "com.torve.pro.monthly"
        private const val LIFETIME_PRODUCT_ID = "com.torve.pro.lifetime"
        private val MONTHLY_PRODUCT_IDS = listOf(
            MONTHLY_SUBSCRIPTION_TERM,
            MONTHLY_SUBSCRIPTION_PARENT,
        )
        private val PRODUCT_IDS = setOf(
            *MONTHLY_PRODUCT_IDS.toTypedArray(),
            LIFETIME_PRODUCT_ID,
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

    private inline fun logError(
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        if (!com.torve.android.BuildConfig.DEBUG) return
        if (throwable != null) {
            Log.e(TAG, message(), throwable)
        } else {
            Log.e(TAG, message())
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

    override fun initialize() {
        if (isInitialized) return
        isInitialized = true
        _billingState.value = BillingManager.BillingState.Connecting
        val init = Runnable {
            try {
                PurchasingService.registerListener(context, this)
                // SDK 3.0.x may auto-fetch user data inside registerListener();
                // a duplicate getUserData() call would throw "resource already registered".
                runCatching { PurchasingService.getUserData() }
                PurchasingService.getProductData(PRODUCT_IDS)
                logDebug { "Registered listener and requested product data for SKUs=$PRODUCT_IDS" }
            } catch (e: Exception) {
                logError(e) { "Failed to initialize Amazon IAP" }
                _billingState.value = BillingManager.BillingState.Error(
                    e.message ?: "Failed to initialize Amazon IAP",
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
            PurchasingService.getUserData()
            PurchasingService.purchase(productId)
            logDebug { "Launched purchase for $productId" }
        } catch (e: Exception) {
            logError(e) { "Failed to launch purchase" }
            _purchaseResult.value = BillingManager.PurchaseResult.Error(
                e.message ?: "Failed to launch purchase",
            )
        }
    }

    override fun queryExistingPurchases() {
        try {
            PurchasingService.getUserData()
            PurchasingService.getPurchaseUpdates(true)
            logDebug { "Querying existing purchases" }
        } catch (e: Exception) {
            logError(e) { "Failed to query existing purchases" }
        }
    }

    override fun getOffer(productType: BillingManager.ProductType): BillingManager.BillingOffer? {
        return offersByType[productType]
    }

    private fun resolveMonthlySku(productData: Map<String, com.amazon.device.iap.model.Product>): String? {
        return MONTHLY_PRODUCT_IDS.firstOrNull { sku -> productData.containsKey(sku) }
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

                val lifetimeSku = if (response.productData.containsKey(LIFETIME_PRODUCT_ID)) LIFETIME_PRODUCT_ID else null
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
                    val message = if (unavailableSkus.isNotEmpty()) {
                        "Amazon Appstore did not return product data for: ${unavailableSkus.joinToString(", ")}."
                    } else {
                        "Amazon Appstore returned no product data. Check Live App Testing and Amazon SKU setup."
                    }
                    logError { message }
                    _billingState.value = BillingManager.BillingState.Error(message)
                } else {
                    _billingState.value = BillingManager.BillingState.Ready(offers = offers)
                }
            }

            ProductDataResponse.RequestStatus.FAILED,
            ProductDataResponse.RequestStatus.NOT_SUPPORTED,
            -> {
                val message = "Amazon product data request failed: ${response.requestStatus}"
                logError { message }
                _billingState.value = BillingManager.BillingState.Error(message)
            }

            else -> {
                val message = "Amazon product data returned unexpected status: ${response.requestStatus}"
                logWarn { message }
                _billingState.value = BillingManager.BillingState.Error(message)
            }
        }
    }

    override fun onPurchaseResponse(response: PurchaseResponse) {
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
                            LIFETIME_PRODUCT_ID -> 2
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
                logError { "Purchase updates failed: ${response.requestStatus}" }
            }

            else -> {
                logWarn { "Unknown purchase updates status: ${response.requestStatus}" }
            }
        }
    }
}
