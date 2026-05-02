package com.torve.presentation.providerhealth

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks in the four user-facing status kinds Prompt 16 names plus the
 * "one CTA per card" rule. Each test names the scenario it pins so a
 * regression message points at exactly which Settings-IA invariant was
 * broken.
 */
class ProviderStatusMapperTest {

    private fun entry(
        status: ProviderHealthStatus,
        message: String? = null,
        lastCheckedAt: Long? = null,
    ) = ProviderHealthEntry(
        category = ProviderHealthCategory.DEBRID,
        providerKey = "real-debrid",
        label = "Real-Debrid",
        status = status,
        lastCheckedAt = lastCheckedAt,
        message = message,
    )

    @Test
    fun `GREEN maps to CONNECTED with Check again CTA`() {
        val view = ProviderStatusMapper.map(entry(ProviderHealthStatus.GREEN, "Real-Debrid ready."))
        assertEquals(ProviderStatusKind.CONNECTED, view.kind)
        assertEquals("Connected", view.headline)
        assertEquals("Check again", view.primaryActionLabel)
        assertEquals(ProviderActionKind.REFRESH, view.primaryActionKind)
        // Generic "ready" is suppressed — the "Connected" headline already
        // says it. Detail should only carry surprises.
        assertNull(view.detail, "GREEN with a generic 'ready' message must not surface it as detail")
    }

    @Test
    fun `YELLOW maps to CONFIGURED_NOT_VERIFIED`() {
        val view = ProviderStatusMapper.map(
            entry(ProviderHealthStatus.YELLOW, "Indexer responded but no key on file."),
        )
        assertEquals(ProviderStatusKind.CONFIGURED_NOT_VERIFIED, view.kind)
        assertEquals("Configured but not verified", view.headline)
        assertEquals("Indexer responded but no key on file.", view.detail)
        assertEquals("Check again", view.primaryActionLabel)
        assertEquals(ProviderActionKind.REFRESH, view.primaryActionKind)
    }

    @Test
    fun `UNCONFIGURED maps to NEEDS_CREDENTIALS with Configure CTA`() {
        val view = ProviderStatusMapper.map(entry(ProviderHealthStatus.UNCONFIGURED))
        assertEquals(ProviderStatusKind.NEEDS_CREDENTIALS, view.kind)
        assertEquals("Needs credentials", view.headline)
        assertEquals("Configure", view.primaryActionLabel)
        assertEquals(ProviderActionKind.CONFIGURE, view.primaryActionKind)
    }

    @Test
    fun `RED with prior check maps to LAST_CHECK_FAILED with Re-enter CTA`() {
        val view = ProviderStatusMapper.map(
            entry(
                ProviderHealthStatus.RED,
                message = "401 unauthorized — re-enter API key",
                lastCheckedAt = 1_725_000_000_000L,
            ),
        )
        assertEquals(ProviderStatusKind.LAST_CHECK_FAILED, view.kind)
        assertEquals("Last check failed", view.headline)
        assertEquals("401 unauthorized — re-enter API key", view.detail)
        assertEquals("Re-enter credentials", view.primaryActionLabel)
        assertEquals(ProviderActionKind.REENTER, view.primaryActionKind)
    }

    @Test
    fun `RED with no prior check maps to NEEDS_CREDENTIALS not LAST_CHECK_FAILED`() {
        // Prompt 17 anti-false-alarm rule: "last check failed" must
        // imply a check actually happened. A RED row with
        // lastCheckedAt == null should read as NEEDS_CREDENTIALS so the
        // user isn't told a check failed that never ran.
        val view = ProviderStatusMapper.map(
            entry(
                ProviderHealthStatus.RED,
                lastCheckedAt = null,
                message = "No API key on file.",
            ),
        )
        assertEquals(
            ProviderStatusKind.NEEDS_CREDENTIALS,
            view.kind,
            "RED + no prior check must surface as NEEDS_CREDENTIALS, not LAST_CHECK_FAILED",
        )
        assertEquals("Configure", view.primaryActionLabel)
    }

    @Test
    fun `UNKNOWN maps to CHECKING with no CTA`() {
        val view = ProviderStatusMapper.map(entry(ProviderHealthStatus.UNKNOWN))
        assertEquals(ProviderStatusKind.CHECKING, view.kind)
        assertNull(view.primaryActionLabel, "CHECKING cards must not render a CTA")
        assertEquals(ProviderActionKind.NONE, view.primaryActionKind)
    }

    @Test
    fun `every kind yields exactly one CTA except CHECKING`() {
        // Prompt 16 acceptance: "each provider card has one clear CTA."
        // Locks the contract that a single label is exposed; UI cannot
        // sneak a second button via the message field.
        for (status in ProviderHealthStatus.entries) {
            val view = ProviderStatusMapper.map(entry(status, lastCheckedAt = 1L))
            if (view.kind == ProviderStatusKind.CHECKING) {
                assertNull(view.primaryActionLabel, "CHECKING must have no CTA")
            } else {
                assertNotNull(view.primaryActionLabel, "non-CHECKING kind ${view.kind} must have one CTA")
            }
        }
    }

    @Test
    fun `canRefresh is false only when the user hasn't configured anything`() {
        // Refresh button must be disabled when there are no credentials
        // to check — pressing it would do nothing.
        assertEquals(false, ProviderStatusMapper.map(entry(ProviderHealthStatus.UNCONFIGURED)).canRefresh)
        assertTrue(ProviderStatusMapper.map(entry(ProviderHealthStatus.GREEN)).canRefresh)
        assertTrue(
            ProviderStatusMapper.map(entry(ProviderHealthStatus.RED, lastCheckedAt = 1L)).canRefresh,
            "a failed-but-configured provider must allow refresh",
        )
    }

    @Test
    fun `non-generic GREEN message survives as detail`() {
        // Real-Debrid sometimes returns extra info (e.g. premium expiry)
        // worth surfacing. Only the generic "ready" / "ready." strings
        // should be suppressed.
        val view = ProviderStatusMapper.map(
            entry(
                ProviderHealthStatus.GREEN,
                message = "Premium until 2026-12-31",
            ),
        )
        assertEquals("Premium until 2026-12-31", view.detail)
    }
}
