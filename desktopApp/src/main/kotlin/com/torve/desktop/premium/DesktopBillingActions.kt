package com.torve.desktop.premium

import com.torve.data.billing.StripePurchaseType
import com.torve.presentation.subscription.SubscriptionViewModel
import org.koin.mp.KoinPlatform

fun startDesktopStripeCheckout(
    purchaseType: StripePurchaseType = StripePurchaseType.MONTHLY,
) {
    KoinPlatform.getKoin()
        .get<SubscriptionViewModel>()
        .beginStripeCheckout(purchaseType) { url ->
            openDesktopBillingUrl(url)
            DesktopPremiumStateHolder.pollAggressivelyFor(seconds = 300)
        }
}

fun startDesktopStripePortal() {
    KoinPlatform.getKoin()
        .get<SubscriptionViewModel>()
        .beginStripePortal(::openDesktopBillingUrl)
}

private fun openDesktopBillingUrl(url: String) {
    val opened = runCatching {
        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    }.isSuccess
    if (!opened) error("desktop_browser_open_failed")
}
