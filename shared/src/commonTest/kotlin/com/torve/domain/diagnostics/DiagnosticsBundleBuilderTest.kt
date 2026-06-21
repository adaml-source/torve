package com.torve.domain.diagnostics

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.transfer.AttemptOutcome
import com.torve.presentation.transfer.AttemptRole
import com.torve.presentation.transfer.RelayReachability
import com.torve.presentation.transfer.TransferAttemptRecord
import com.torve.presentation.transfer.TransferDiagnosticsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks in the Prompt 26 promises:
 *  - bundle is a single text string suitable for a share-sheet,
 *  - structure is stable (## headers grep-able by support tooling),
 *  - missing inputs render as "(not available)" not nulls,
 *  - the redaction pass strips tokens, paths, and authenticated URLs
 *    before anything leaves.
 */
class DiagnosticsBundleBuilderTest {

    private val app = DiagnosticsBundleBuilder.AppInfo(
        versionName = "1.0.58",
        versionCode = "10067",
        storeFlavor = "google",
        activeEngineId = "ExoPlayer",
    )

    private val device = DiagnosticsBundleBuilder.DeviceInfo(
        platform = "Android",
        deviceModel = "Pixel 8 Pro",
        osVersion = "34",
        locale = "en-US",
    )

    @Test
    fun `bundle has the seven stable section headers in order`() {
        val text = DiagnosticsBundleBuilder.build(
            app = app,
            device = device,
            nowEpochMs = 1_700_000_000_000L,
        )
        val expected = listOf(
            "# Torve diagnostics",
            "## App",
            "## Device",
            "## Account",
            "## Provider status",
            "## Transfer (credential transfer / automatic transfer)",
            "## Last failure",
            "## What is NOT in this bundle",
        )
        var lastIdx = -1
        expected.forEach { header ->
            val idx = text.indexOf(header)
            assertTrue(idx >= 0, "missing section header: $header\nbundle was:\n$text")
            assertTrue(idx > lastIdx, "section out of order: $header at $idx vs lastIdx=$lastIdx")
            lastIdx = idx
        }
    }

    @Test
    fun `missing optional sections render placeholder copy`() {
        val text = DiagnosticsBundleBuilder.build(
            app = app,
            device = device,
            nowEpochMs = 1_700_000_000_000L,
        )
        // Account, Provider status, Transfer, Last failure all default
        // to placeholder when their input is null/empty.
        assertTrue(text.contains("(not available)"))
        assertTrue(text.contains("(no provider entries)"))
        assertTrue(text.contains("(no failure recorded)"))
        // No literal "null" leaked.
        assertFalse(text.contains("=null"))
    }

    @Test
    fun `provider entries render label status message and lastChecked`() {
        val entries = listOf(
            ProviderHealthEntry(
                category = ProviderHealthCategory.DEBRID,
                providerKey = "real-debrid",
                label = "Real-Debrid",
                status = ProviderHealthStatus.GREEN,
                lastCheckedAt = 1_700_000_000_000L,
                message = "Connected.",
                nextAction = null,
            ),
            ProviderHealthEntry(
                category = ProviderHealthCategory.IPTV,
                providerKey = "smatv-pro",
                label = "IPTV playlist",
                status = ProviderHealthStatus.YELLOW,
                lastCheckedAt = 1_700_000_001_000L,
                message = "Loaded with 0 channels.",
                nextAction = "Refresh playlist",
            ),
        )
        val text = DiagnosticsBundleBuilder.build(
            app = app,
            device = device,
            providerEntries = entries,
            nowEpochMs = 1_700_000_000_000L,
        )
        assertTrue(text.contains("Real-Debrid [GREEN]"))
        assertTrue(text.contains("IPTV playlist [YELLOW]"))
        assertTrue(text.contains("Loaded with 0 channels."))
        assertTrue(text.contains("Refresh playlist"))
    }

    @Test
    fun `transfer snapshot renders relay state and last attempt outcome`() {
        val snap = TransferDiagnosticsSnapshot(
            cryptoEngineAvailable = true,
            signedIn = true,
            relayReachable = RelayReachability.NETWORK_ERROR,
            lastAttempt = TransferAttemptRecord(
                role = AttemptRole.SENDER,
                outcome = AttemptOutcome.RELAY_UNAVAILABLE,
                errorCategory = null,
                recordedAtEpochMs = 1_700_000_002_000L,
            ),
            collectedAtEpochMs = 1_700_000_003_000L,
        )
        val text = DiagnosticsBundleBuilder.build(
            app = app,
            device = device,
            transfer = snap,
            nowEpochMs = 1_700_000_000_000L,
        )
        assertTrue(text.contains("relay reachable: network_error"))
        assertTrue(text.contains("role=sender"))
        assertTrue(text.contains("outcome=relay_unavailable"))
    }

    @Test
    fun `bundle redacts a Bearer token leaked into a provider message`() {
        // Defense-in-depth: even if a checker accidentally puts a token
        // into the message, the bundle must scrub it.
        val entries = listOf(
            ProviderHealthEntry(
                category = ProviderHealthCategory.DEBRID,
                providerKey = "torbox",
                label = "TorBox",
                status = ProviderHealthStatus.RED,
                lastCheckedAt = 1L,
                message = "Authorization: Bearer abcdef123456 returned 401",
                nextAction = "Re-enter key",
            ),
        )
        val text = DiagnosticsBundleBuilder.build(
            app = app,
            device = device,
            providerEntries = entries,
            nowEpochMs = 1_700_000_000_000L,
        )
        assertFalse(text.contains("abcdef123456"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun `bundle redacts an Xtream-style query token leaked into a message`() {
        val entries = listOf(
            ProviderHealthEntry(
                category = ProviderHealthCategory.IPTV,
                providerKey = "smatv",
                label = "IPTV",
                status = ProviderHealthStatus.RED,
                lastCheckedAt = 1L,
                message = "GET https://smatv.pro/xmltv.php?username=alice&password=secret123 returned 401",
                nextAction = null,
            ),
        )
        val text = DiagnosticsBundleBuilder.build(
            app = app,
            device = device,
            providerEntries = entries,
            nowEpochMs = 1_700_000_000_000L,
        )
        assertFalse(text.contains("alice"))
        assertFalse(text.contains("secret123"))
        // URL stem still visible so support can see WHICH provider broke.
        assertTrue(text.contains("smatv.pro"))
    }

    @Test
    fun `account section reports signedIn verified and tier when provided`() {
        val text = DiagnosticsBundleBuilder.build(
            app = app,
            device = device,
            account = DiagnosticsBundleBuilder.AccountInfo(
                signedIn = true,
                emailVerified = true,
                accessTier = "premium_lifetime",
                deviceActivated = true,
            ),
            nowEpochMs = 1_700_000_000_000L,
        )
        assertTrue(text.contains("- signed in: true"))
        assertTrue(text.contains("- email verified: true"))
        assertTrue(text.contains("- access tier: premium_lifetime"))
        assertTrue(text.contains("- device activated: true"))
    }

    @Test
    fun `bundle ends with the explicit non-inclusion list`() {
        val text = DiagnosticsBundleBuilder.build(
            app = app,
            device = device,
            nowEpochMs = 1_700_000_000_000L,
        )
        assertTrue(text.contains("API keys, OAuth tokens, refresh tokens"))
        assertTrue(text.contains("Stream URLs with credentials"))
        assertTrue(text.contains("Email address, password, payment info"))
    }

    @Test
    fun `header contains the timestamp so two bundles can be compared`() {
        val text = DiagnosticsBundleBuilder.build(
            app = app,
            device = device,
            nowEpochMs = 1_700_000_000_000L,
        )
        assertTrue(text.contains("epoch_ms=1700000000000"))
    }

    @Test
    fun `redactor strips windows user-home paths`() {
        // Direct redactor test: messages that quote a path get the
        // username masked but the structure preserved.
        val redacted = DiagnosticsRedactor.redact("file at C:\\Users\\Anwender\\Downloads\\foo.mp4")
        assertFalse(redacted.contains("Anwender"))
        assertTrue(redacted.contains("C:\\Users\\<redacted>"))
    }

    @Test
    fun `redactor strips token from stremio addon manifest URL`() {
        val redacted = DiagnosticsRedactor
            .redact("addon at https://addons.example/u/abc123def456ghi789jkl/manifest.json")
        assertFalse(redacted.contains("abc123def456ghi789jkl"))
        assertTrue(redacted.contains("/u/<redacted>/manifest.json"))
    }

    @Test
    fun `redactor strips source key values from backend diagnostics`() {
        val redacted = DiagnosticsRedactor.redact(
            """{"source_key":"https://provider.example/stream?token=abc123","api_key":"secret"}""",
        )

        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("secret"))
        assertTrue(redacted.contains(""""source_key": "<redacted>""""))
    }

    @Test
    fun `redactor strips integrity and playback token fields`() {
        val redacted = DiagnosticsRedactor.redact(
            """
            {
              "integrity_token": "play-integrity-secret",
              "provider_token": "provider-secret",
              "playback_url": "https://cdn.example/movie.mp4?token=stream-secret",
              "stream_url": "https://debrid.example/file?api_key=debrid-secret"
            }
            """.trimIndent(),
        )

        assertFalse(redacted.contains("play-integrity-secret"))
        assertFalse(redacted.contains("provider-secret"))
        assertFalse(redacted.contains("stream-secret"))
        assertFalse(redacted.contains("debrid-secret"))
        assertTrue(redacted.contains(""""integrity_token": "<redacted>""""))
        assertTrue(redacted.contains(""""playback_url": "<redacted>""""))
    }
}
