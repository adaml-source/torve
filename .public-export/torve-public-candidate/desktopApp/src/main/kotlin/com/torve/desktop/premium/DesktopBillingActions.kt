package com.torve.desktop.premium

import com.torve.data.billing.StripePurchaseType

@Deprecated("Desktop no longer exposes Stripe checkout; retained for source compatibility.")
fun startDesktopStripeCheckout(
    purchaseType: StripePurchaseType = StripePurchaseType.MONTHLY,
) {
    DesktopPremiumStateHolder.refreshNow()
}

@Deprecated("Desktop no longer exposes Stripe customer portal; retained for source compatibility.")
fun startDesktopStripePortal() {
    DesktopPremiumStateHolder.refreshNow()
}
