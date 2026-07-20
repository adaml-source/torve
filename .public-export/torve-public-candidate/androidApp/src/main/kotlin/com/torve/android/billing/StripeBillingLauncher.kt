package com.torve.android.billing

import android.content.Context
import com.torve.presentation.subscription.SubscriptionViewModel

@Deprecated("Fire TV no longer exposes Stripe checkout; retained for source compatibility.")
fun isStripeFireTvBillingBuild(): Boolean {
    return false
}

@Deprecated("Torve no longer has Stripe checkout in Android TV store builds.")
fun launchStripeCheckout(
    context: Context,
    viewModel: SubscriptionViewModel,
    productType: BillingManager.ProductType,
) {
    viewModel.setPurchaseError("Torve is free software. There are no subscriptions or paid tiers.")
}

@Deprecated("Torve no longer has Stripe billing portal in Android TV store builds.")
fun launchStripePortal(
    context: Context,
    viewModel: SubscriptionViewModel,
) {
    viewModel.setPurchaseError("Torve is free software. There are no subscriptions or paid tiers.")
}
