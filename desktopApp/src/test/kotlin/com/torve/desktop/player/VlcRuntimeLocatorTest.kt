package com.torve.desktop.player

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VlcRuntimeLocatorTest {

    @Test
    fun `discover returns result with attempted paths`() {
        val result = VlcRuntimeLocator.discover()
        // Even if VLC is not installed, we should get attempted paths
        assertNotNull(result)
        assertTrue(result.attemptedPaths.isNotEmpty(), "Should have attempted at least one path")
        assertTrue(result.diagnosticMessage.isNotBlank(), "Should have a diagnostic message")
    }

    @Test
    fun `discover prefers bundled runtime first`() {
        val result = VlcRuntimeLocator.discover()
        // If found from bundled runtime, the source should say so
        if (result.found && result.discoverySource?.contains("Bundled") == true) {
            assertTrue(result.attemptedPaths.first().contains("runtime"))
        }
    }

    @Test
    fun `discovery result diagnostic message is human-readable`() {
        val result = VlcRuntimeLocator.discover()
        if (!result.found) {
            assertTrue(result.diagnosticMessage.contains("VLC"), "Missing message should mention VLC")
            assertTrue(result.diagnosticMessage.contains("not found") || result.diagnosticMessage.contains("Install"),
                "Missing message should hint at resolution")
        }
    }

    @Test
    fun `applyDiscovery does not crash on empty result`() {
        val emptyResult = VlcRuntimeLocator.DiscoveryResult(
            found = false,
            diagnosticMessage = "Test: VLC not found",
        )
        // Should not throw
        VlcRuntimeLocator.applyDiscovery(emptyResult)
    }
}
