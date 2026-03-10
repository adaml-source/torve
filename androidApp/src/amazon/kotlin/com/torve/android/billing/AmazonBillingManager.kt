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
        private const val PRODUCT_ID = "com.torve.pro.lifetime"
        private const val PRODUCT_ID_AMAZON = "com.torve.pro.lifetime.amazon"
        private val PRODUCT_IDS = setOf(PRODUCT_ID, PRODUCT_ID_AMAZON)
    }

    private data class PendingAmazonPurchase(
        val receiptId: String,
        val productId: String,
    )

    private val _billingState = MutableStateFlow<BillingManager.BillingState>(
        BillingManager.BillingState.Disconnected,
    )
    override val billingState: StateFlow<BillingManager.BillingState> = _billingState.asStateFlow()

    private val _purchaseResult = MutableStateFlow<BillingManager.PurchaseResult?>(null)
    override val purchaseResult: StateFlow<BillingManager.PurchaseResult?> = _purchaseResult.asStateFlow()
    private var cachedAmazonUserId: String? = null
    private var selectedProductId: String = PRODUCT_ID
    private var pendingAmazonPurchase: PendingAmazonPurchase? = null

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
        Log.d(
            TAG,
            "Queued pending Amazon purchase receiptIdPrefix=${receiptId.take(8)}..., productId=$productId while waiting for user data",
        )
        PurchasingService.getUserData()
        _purchaseResult.value = BillingManager.PurchaseResult.Pending(
            "Purchase received. Waiting for Amazon account details to finish verification.",
        )
    }

    override fun initialize() {
        _billingState.value = BillingManager.BillingState.Connecting
        try {
            PurchasingService.registerListener(context, this)
            PurchasingService.getUserData()
            // Query product data to get the store-formatted price
            PurchasingService.getProductData(PRODUCT_IDS)
            Log.d(TAG, "Registered listener and requested product data for SKUs=$PRODUCT_IDS")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Amazon IAP", e)
            _billingState.value = BillingManager.BillingState.Error(
                e.message ?: "Failed to initialize Amazon IAP",
            )
        }
    }

    override fun launchPurchase(activity: Activity) {
        val state = _billingState.value
        if (state !is BillingManager.BillingState.Ready || state.formattedPrice == null) {
            _purchaseResult.value = BillingManager.PurchaseResult.Error("Product not available")
            return
        }
        try {
            PurchasingService.getUserData()
            PurchasingService.purchase(selectedProductId)
            Log.d(TAG, "Launched purchase for $selectedProductId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch purchase", e)
            _purchaseResult.value = BillingManager.PurchaseResult.Error(
                e.message ?: "Failed to launch purchase",
            )
        }
    }

    override fun queryExistingPurchases() {
        try {
            PurchasingService.getUserData()
            PurchasingService.getPurchaseUpdates(true)
            Log.d(TAG, "Querying existing purchases")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query existing purchases", e)
        }
    }

    override fun getFormattedPrice(): String? {
        val state = _billingState.value
        return if (state is BillingManager.BillingState.Ready) state.formattedPrice else null
    }

    override fun clearPurchaseResult() {
        _purchaseResult.value = null
    }

    // ── PurchasingListener callbacks ──

    override fun onUserDataResponse(response: UserDataResponse) {
        when (response.requestStatus) {
            UserDataResponse.RequestStatus.SUCCESSFUL -> {
                val userId = response.userData?.userId?.takeIf { it.isNotBlank() }
                cachedAmazonUserId = userId
                Log.d(TAG, "onUserDataResponse: SUCCESSFUL hasUserId=${!userId.isNullOrBlank()}")
                val pending = pendingAmazonPurchase
                if (pending != null && !userId.isNullOrBlank()) {
                    pendingAmazonPurchase = null
                    Log.d(
                        TAG,
                        "Resolved pending purchase after user data: receiptIdPrefix=${pending.receiptId.take(8)}..., productId=${pending.productId}",
                    )
                    emitAmazonSuccess(
                        receiptId = pending.receiptId,
                        productId = pending.productId,
                        amazonUserId = userId,
                    )
                }
            }
            else -> {
                Log.w(TAG, "onUserDataResponse: ${response.requestStatus}")
            }
        }
    }

    override fun onProductDataResponse(response: ProductDataResponse) {
        when (response.requestStatus) {
            ProductDataResponse.RequestStatus.SUCCESSFUL -> {
                val selectedSku = when {
                    response.productData.containsKey(PRODUCT_ID_AMAZON) -> PRODUCT_ID_AMAZON
                    response.productData.containsKey(PRODUCT_ID) -> PRODUCT_ID
                    else -> null
                }
                val product = selectedSku?.let { response.productData[it] }
                if (product != null) {
                    selectedProductId = selectedSku ?: PRODUCT_ID
                    val price = product.price
                    Log.d(TAG, "Product loaded: $selectedProductId -> price=$price")
                    _billingState.value = BillingManager.BillingState.Ready(formattedPrice = price)
                } else {
                    Log.w(TAG, "No supported SKU found. Available: ${response.productData.keys}")
                    _billingState.value = BillingManager.BillingState.Ready(formattedPrice = null)
                }
            }
            ProductDataResponse.RequestStatus.FAILED,
            ProductDataResponse.RequestStatus.NOT_SUPPORTED,
            -> {
                Log.e(TAG, "Product data request failed: ${response.requestStatus}")
                _billingState.value = BillingManager.BillingState.Ready(formattedPrice = null)
            }
            else -> {
                Log.w(TAG, "Unknown product data status: ${response.requestStatus}")
                _billingState.value = BillingManager.BillingState.Ready(formattedPrice = null)
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
                val productId = receipt.sku?.takeIf { it.isNotBlank() } ?: PRODUCT_ID
                Log.d(
                    TAG,
                    "Purchase successful: receiptIdPrefix=${receipt.receiptId.take(8)}..., productId=$productId, hasUserId=${!amazonUserId.isNullOrBlank()}",
                )
                PurchasingService.notifyFulfillment(
                    receipt.receiptId,
                    com.amazon.device.iap.model.FulfillmentResult.FULFILLED,
                )
                if (amazonUserId.isNullOrBlank()) {
                    Log.w(
                        TAG,
                        "Purchase succeeded but user ID unavailable; pending verification receiptIdPrefix=${receipt.receiptId.take(8)}...",
                    )
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
                Log.d(TAG, "Already purchased")
                PurchasingService.getUserData()
                PurchasingService.getPurchaseUpdates(true)
                _purchaseResult.value = BillingManager.PurchaseResult.AlreadyOwned
            }
            PurchaseResponse.RequestStatus.FAILED -> {
                Log.e(TAG, "Purchase failed")
                _purchaseResult.value = BillingManager.PurchaseResult.Error("Purchase failed")
            }
            PurchaseResponse.RequestStatus.NOT_SUPPORTED -> {
                Log.e(TAG, "Purchase not supported")
                _purchaseResult.value = BillingManager.PurchaseResult.Error(
                    "In-app purchases are not supported on this device",
                )
            }
            else -> {
                Log.w(TAG, "Unknown purchase status: ${response.requestStatus}")
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
                val lifetimeReceipt = response.receipts.firstOrNull { receipt ->
                    (receipt.sku == PRODUCT_ID || receipt.sku == PRODUCT_ID_AMAZON) && !receipt.isCanceled
                }
                if (lifetimeReceipt != null) {
                    Log.d(
                        TAG,
                        "Found existing purchase: receiptIdPrefix=${lifetimeReceipt.receiptId.take(8)}..., hasUserId=${!amazonUserId.isNullOrBlank()}",
                    )
                    if (amazonUserId.isNullOrBlank()) {
                        queuePendingAmazonPurchase(
                            receiptId = lifetimeReceipt.receiptId,
                            productId = lifetimeReceipt.sku?.takeIf { it.isNotBlank() } ?: PRODUCT_ID,
                        )
                    } else {
                        emitAmazonSuccess(
                            receiptId = lifetimeReceipt.receiptId,
                            productId = lifetimeReceipt.sku?.takeIf { it.isNotBlank() } ?: PRODUCT_ID,
                            amazonUserId = amazonUserId,
                        )
                    }
                }
                if (lifetimeReceipt == null) {
                    Log.d(TAG, "No active Amazon lifetime purchase found in purchase updates")
                }
                if (response.hasMore()) {
                    PurchasingService.getPurchaseUpdates(false)
                }
            }
            PurchaseUpdatesResponse.RequestStatus.FAILED,
            PurchaseUpdatesResponse.RequestStatus.NOT_SUPPORTED,
            -> {
                Log.e(TAG, "Purchase updates failed: ${response.requestStatus}")
            }
            else -> {
                Log.w(TAG, "Unknown purchase updates status: ${response.requestStatus}")
            }
        }
    }
}

