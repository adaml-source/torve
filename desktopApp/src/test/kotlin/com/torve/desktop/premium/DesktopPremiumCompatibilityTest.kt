package com.torve.desktop.premium

import com.torve.data.billing.StripePurchaseType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPremiumCompatibilityTest {

    @Test
    fun legacyPremiumGateAlwaysRunsAction() {
        var actionRan = false
        var upgradeRequested = false

        val gated = premiumGated(
            onUpgradeRequired = { upgradeRequested = true },
            action = { actionRan = true },
        )

        gated()

        assertTrue(actionRan)
        assertFalse(upgradeRequested)
    }

    @Test
    fun desktopPremiumHolderReportsFreeCompatibleAccess() {
        DesktopPremiumStateHolder.pushHasPremium(false)

        assertTrue(DesktopPremiumStateHolder.isPremium())

        DesktopPremiumStateHolder.pushHasPremium(true)
    }

    @Test
    fun deprecatedStripeActionsDoNotChangeAccess() {
        startDesktopStripeCheckout(StripePurchaseType.MONTHLY)
        startDesktopStripeCheckout(StripePurchaseType.LIFETIME)
        startDesktopStripePortal()

        assertTrue(DesktopPremiumStateHolder.isPremium())
    }
}
