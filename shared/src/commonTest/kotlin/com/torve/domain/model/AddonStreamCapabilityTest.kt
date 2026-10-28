package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonStreamCapabilityTest {

    @Test
    fun enabledStreamAddonCanResolveStreams() {
        assertTrue(addon(resources = listOf("STREAM")).canResolveStreams())
    }

    @Test
    fun legacyAddonWithNoDeclaredResourcesCanResolveStreams() {
        assertTrue(addon(resources = emptyList()).canResolveStreams())
    }

    @Test
    fun metadataOnlyOrDisabledAddonCannotResolveStreams() {
        assertFalse(addon(resources = listOf("catalog", "meta")).canResolveStreams())
        assertFalse(addon(resources = listOf("stream"), enabled = false).canResolveStreams())
    }

    private fun addon(resources: List<String>, enabled: Boolean = true): InstalledAddon =
        InstalledAddon(
            manifestUrl = "https://example.test/manifest.json",
            manifest = AddonManifest(
                id = "test.addon",
                name = "Test",
                version = "1.0.0",
                resources = resources,
            ),
            isEnabled = enabled,
        )
}
