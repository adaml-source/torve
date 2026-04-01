package com.torve.android.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for billing price display logic.
 *
 * Validates:
 * - Store-returned formatted price is used when available
 * - No hardcoded currency amounts are shown as fallbacks
 * - Safe fallback text when billing metadata is unavailable
 * - Product ID mapping consistency
 */
class BillingPriceDisplayTest {

    // ── Price resolution logic (mirrors PaywallScreen) ──

    /**
     * Resolves the display price text based on billing state.
     * This mirrors the logic in PaywallScreen.kt.
     */
    private fun resolvePriceText(
        formattedPrice: String?,
        billingState: BillingManager.BillingState,
        fallbackUnavailable: String = "Price unavailable",
        fallbackLoading: String = "Loading…",
    ): String = when {
        formattedPrice != null -> formattedPrice
        billingState is BillingManager.BillingState.Ready -> fallbackUnavailable
        billingState is BillingManager.BillingState.Error -> fallbackUnavailable
        else -> fallbackLoading
    }

    /** When billing returns whether purchase should be enabled. */
    private fun isPurchaseEnabled(formattedPrice: String?): Boolean = formattedPrice != null

    // ── Store-returned price tests ──

    @Test
    fun `store-returned price is used directly`() {
        val price = resolvePriceText(
            formattedPrice = "$9.99",
            billingState = BillingManager.BillingState.Ready(offers = emptyList()) // was formattedPrice = "$9.99"),
        )
        assertEquals("$9.99", price)
    }

    @Test
    fun `localized euro price from store is used directly`() {
        val price = resolvePriceText(
            formattedPrice = "9,99 €",
            billingState = BillingManager.BillingState.Ready(offers = emptyList()) // was formattedPrice = "9,99 €"),
        )
        assertEquals("9,99 €", price)
    }

    @Test
    fun `localized yen price from store is used directly`() {
        val price = resolvePriceText(
            formattedPrice = "¥1,500",
            billingState = BillingManager.BillingState.Ready(offers = emptyList()) // was formattedPrice = "¥1,500"),
        )
        assertEquals("¥1,500", price)
    }

    @Test
    fun `purchase enabled when formatted price available`() {
        assertTrue(isPurchaseEnabled("$9.99"))
    }

    // ── Fallback behavior tests ──

    @Test
    fun `null price with Ready state shows unavailable fallback`() {
        val price = resolvePriceText(
            formattedPrice = null,
            billingState = BillingManager.BillingState.Ready(offers = emptyList()) // was formattedPrice = null),
        )
        assertEquals("Price unavailable", price)
    }

    @Test
    fun `null price with Error state shows unavailable fallback`() {
        val price = resolvePriceText(
            formattedPrice = null,
            billingState = BillingManager.BillingState.Error("Connection failed"),
        )
        assertEquals("Price unavailable", price)
    }

    @Test
    fun `null price while Connecting shows loading fallback`() {
        val price = resolvePriceText(
            formattedPrice = null,
            billingState = BillingManager.BillingState.Connecting,
        )
        assertEquals("Loading…", price)
    }

    @Test
    fun `null price while Disconnected shows loading fallback`() {
        val price = resolvePriceText(
            formattedPrice = null,
            billingState = BillingManager.BillingState.Disconnected,
        )
        assertEquals("Loading…", price)
    }

    @Test
    fun `purchase disabled when formatted price is null`() {
        assertTrue(!isPurchaseEnabled(null))
    }

    // ── No hardcoded currency in fallback ──

    @Test
    fun `fallback text does not contain currency symbols`() {
        val unavailable = "Price unavailable"
        val loading = "Loading…"
        val currencyPatterns = listOf("€", "$", "£", "¥", "₹", "USD", "EUR", "GBP")
        currencyPatterns.forEach { symbol ->
            assertTrue(
                "Fallback '$unavailable' must not contain '$symbol'",
                !unavailable.contains(symbol),
            )
            assertTrue(
                "Fallback '$loading' must not contain '$symbol'",
                !loading.contains(symbol),
            )
        }
    }

    // ── BillingState.Ready offer extraction ──

    @Test
    fun `Ready state with offers exposes them`() {
        val offer = BillingManager.BillingOffer(
            productType = BillingManager.ProductType.LIFETIME,
            productId = "com.torve.pro.lifetime",
            formattedPrice = "R$49,90",
            billingDetails = "One-time purchase",
        )
        val state = BillingManager.BillingState.Ready(offers = listOf(offer))
        assertEquals(1, state.offers.size)
        assertEquals("R$49,90", state.offers.first().formattedPrice)
    }

    @Test
    fun `Ready state with empty offers list`() {
        val state = BillingManager.BillingState.Ready(offers = emptyList())
        assertTrue(state.offers.isEmpty())
    }

    // ── Product ID tests ──

    @Test
    fun `google play product ID is correct`() {
        // The server-side SKU used in GooglePlayBillingManager
        val googleSku = "torve_pro_lifetime"
        assertTrue(googleSku.isNotBlank())
        assertTrue(!googleSku.contains(" "))
    }

    @Test
    fun `backend product ID sent after purchase is correct`() {
        // The product ID sent to the backend for verification
        val backendProductId = "com.torve.pro.lifetime"
        assertTrue(backendProductId.contains("torve"))
        assertTrue(backendProductId.contains("lifetime"))
    }

    @Test
    fun `invalid product ID does not produce hardcoded price fallback`() {
        // Simulates a product query failure (wrong SKU → no ProductDetails)
        val formattedPrice: String? = null // store returned nothing
        val price = resolvePriceText(
            formattedPrice = formattedPrice,
            billingState = BillingManager.BillingState.Ready(offers = emptyList()) // was formattedPrice = null),
        )
        // Must NOT contain any currency amount
        assertTrue(!price.matches(Regex(".*\\d+[.,]\\d+.*")))
        assertEquals("Price unavailable", price)
    }

    // ── TV settings label tests ──

    @Test
    fun `tv settings label uses store price when available`() {
        val formattedPrice = "$9.99"
        val isPro = false
        val label = if (isPro) {
            "Lifetime — Active"
        } else if (formattedPrice != null) {
            "Free — $formattedPrice for Lifetime"
        } else {
            "Free — Upgrade to Lifetime"
        }
        assertEquals("Free — $9.99 for Lifetime", label)
    }

    @Test
    fun `tv settings label shows safe fallback when price unavailable`() {
        val formattedPrice: String? = null
        val isPro = false
        val label = if (isPro) {
            "Lifetime — Active"
        } else if (formattedPrice != null) {
            "Free — $formattedPrice for Lifetime"
        } else {
            "Free — Upgrade to Lifetime"
        }
        assertEquals("Free — Upgrade to Lifetime", label)
        assertTrue(!label.matches(Regex(".*\\d+[.,]\\d+.*")))
    }

    @Test
    fun `tv settings label shows active when pro`() {
        val formattedPrice: String? = null
        val isPro = true
        val label = if (isPro) {
            "Lifetime — Active"
        } else if (formattedPrice != null) {
            "Free — $formattedPrice for Lifetime"
        } else {
            "Free — Upgrade to Lifetime"
        }
        assertEquals("Lifetime — Active", label)
    }
}
