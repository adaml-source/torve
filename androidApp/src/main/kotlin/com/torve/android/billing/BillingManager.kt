package com.torve.android.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

interface BillingManager {
    enum class ProductType {
        MONTHLY,
        LIFETIME,
    }

    enum class Store {
        GOOGLE_PLAY,
        AMAZON_APPSTORE,
    }

    data class BillingOffer(
        val productType: ProductType,
        val productId: String,
        val formattedPrice: String?,
        val billingDetails: String,
    )

    sealed class BillingState {
        data object Disconnected : BillingState()
        data object Connecting : BillingState()
        data object Connected : BillingState()
        data class Ready(val offers: List<BillingOffer>) : BillingState()
        data class Error(val message: String) : BillingState()
    }

    sealed class PurchaseResult {
        data class Success(
            val purchaseToken: String,
            val store: Store = Store.GOOGLE_PLAY,
            val productId: String = "",
            val amazonUserId: String? = null,
        ) : PurchaseResult()
        data class Pending(
            val message: String,
            val productId: String? = null,
        ) : PurchaseResult()
        data class AlreadyOwned(
            val productId: String? = null,
        ) : PurchaseResult()
        data object Cancelled : PurchaseResult()
        data class Error(val message: String) : PurchaseResult()
    }

    /**
     * One owned purchase as reported by the store. Used by the
     * client-driven restore flow to feed each token to the backend
     * verify endpoint before recomputing premium flags.
     */
    data class ActivePurchase(
        val productId: String,
        val purchaseToken: String,
        val isAcknowledged: Boolean,
    )

    val billingState: StateFlow<BillingState>
    val purchaseResult: StateFlow<PurchaseResult?>

    fun initialize()
    fun launchPurchase(activity: Activity, productType: ProductType)
    fun queryExistingPurchases()
    /**
     * Suspend query of all active (PURCHASED) purchases the store knows
     * about for this user — both subscriptions (SUBS) and one-time
     * purchases (INAPP). Returns an empty list if billing isn't ready
     * or the store reports no purchases. Never throws.
     *
     * Restore flow: client iterates these, POSTs each token to
     * /me/purchases/google-play/verify, then calls
     * /me/purchases/restore on the backend, then refreshes
     * /me/access-state.
     */
    suspend fun queryActivePurchases(): List<ActivePurchase>
    fun getOffer(productType: ProductType): BillingOffer?
    fun clearPurchaseResult()
}
