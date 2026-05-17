package com.torve.android.billing

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.torve.data.billing.StripePurchaseType
import com.torve.presentation.subscription.SubscriptionViewModel

fun isStripeFireTvBillingBuild(): Boolean {
    return com.torve.android.BuildConfig.FLAVOR_store.equals("amazon", ignoreCase = true) &&
        com.torve.android.BuildConfig.FLAVOR_formFactor.equals("tv", ignoreCase = true)
}

fun launchStripeCheckout(
    context: Context,
    viewModel: SubscriptionViewModel,
    productType: BillingManager.ProductType,
) {
    val purchaseType = when (productType) {
        BillingManager.ProductType.MONTHLY -> StripePurchaseType.MONTHLY
        BillingManager.ProductType.LIFETIME -> StripePurchaseType.LIFETIME
    }
    viewModel.beginStripeCheckout(purchaseType) { url ->
        openStripeUrl(context, viewModel, url)
    }
}

fun launchStripePortal(
    context: Context,
    viewModel: SubscriptionViewModel,
) {
    viewModel.beginStripePortal { url ->
        openStripeUrl(context, viewModel, url)
    }
}

private fun openStripeUrl(context: Context, viewModel: SubscriptionViewModel, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        viewModel.setPurchaseError("Could not open browser for Stripe checkout.")
    }
}
