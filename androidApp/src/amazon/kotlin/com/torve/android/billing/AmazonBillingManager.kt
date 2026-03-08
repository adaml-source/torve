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
    }

    private val _billingState = MutableStateFlow<BillingManager.BillingState>(
        BillingManager.BillingState.Disconnected,
    )
    override val billingState: StateFlow<BillingManager.BillingState> = _billingState.asStateFlow()

    private val _purchaseResult = MutableStateFlow<BillingManager.PurchaseResult?>(null)
    override val purchaseResult: StateFlow<BillingManager.PurchaseResult?> = _purchaseResult.asStateFlow()

    override fun initialize() {
        _billingState.value = BillingManager.BillingState.Connecting
        try {
            PurchasingService.registerListener(context, this)
            // Query product data to get the store-formatted price
            PurchasingService.getProductData(setOf(PRODUCT_ID))
            Log.d(TAG, "Registered listener and requested product data for $PRODUCT_ID")
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
            PurchasingService.purchase(PRODUCT_ID)
            Log.d(TAG, "Launched purchase for $PRODUCT_ID")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch purchase", e)
            _purchaseResult.value = BillingManager.PurchaseResult.Error(
                e.message ?: "Failed to launch purchase",
            )
        }
    }

    override fun queryExistingPurchases() {
        try {
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
        Log.d(TAG, "onUserDataResponse: ${response.requestStatus}")
    }

    override fun onProductDataResponse(response: ProductDataResponse) {
        when (response.requestStatus) {
            ProductDataResponse.RequestStatus.SUCCESSFUL -> {
                val product = response.productData[PRODUCT_ID]
                if (product != null) {
                    val price = product.price
                    Log.d(TAG, "Product loaded: $PRODUCT_ID -> price=$price")
                    _billingState.value = BillingManager.BillingState.Ready(formattedPrice = price)
                } else {
                    Log.w(TAG, "Product $PRODUCT_ID not found in response. Available: ${response.productData.keys}")
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
                Log.d(TAG, "Purchase successful: receiptId=${receipt.receiptId}")
                PurchasingService.notifyFulfillment(
                    receipt.receiptId,
                    com.amazon.device.iap.model.FulfillmentResult.FULFILLED,
                )
                _purchaseResult.value = BillingManager.PurchaseResult.Success(
                    purchaseToken = receipt.receiptId,
                )
            }
            PurchaseResponse.RequestStatus.ALREADY_PURCHASED -> {
                Log.d(TAG, "Already purchased")
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
                val lifetimeReceipt = response.receipts.firstOrNull { receipt ->
                    receipt.sku == PRODUCT_ID && !receipt.isCanceled
                }
                if (lifetimeReceipt != null) {
                    Log.d(TAG, "Found existing purchase: ${lifetimeReceipt.receiptId}")
                    _purchaseResult.value = BillingManager.PurchaseResult.AlreadyOwned
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
