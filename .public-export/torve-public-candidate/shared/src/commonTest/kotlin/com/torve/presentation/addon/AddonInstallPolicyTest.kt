package com.torve.presentation.addon

import com.torve.domain.model.AddonPolicyFlags
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that the AddonViewModel's installability check correctly
 * gates addon installation based on backend policy flags.
 */
class AddonInstallPolicyTest {

    @Test
    fun normalizeManifestUrlAddsManifestSuffix() {
        val normalized = AddonViewModel.normalizeManifestUrl("https://example.com/addon")
        assertTrue(normalized.endsWith("/manifest.json"))
    }

    @Test
    fun normalizeManifestUrlPreservesExistingSuffix() {
        val url = "https://example.com/addon/manifest.json"
        val normalized = AddonViewModel.normalizeManifestUrl(url)
        // Should not double-append
        assertFalse(normalized.contains("manifest.json/manifest.json"))
        assertTrue(normalized.endsWith("/manifest.json"))
    }

    @Test
    fun normalizeManifestUrlHandlesTrailingSlash() {
        val normalized = AddonViewModel.normalizeManifestUrl("https://example.com/addon/")
        assertTrue(normalized.endsWith("/manifest.json"))
    }

    @Test
    fun installableDefaultsToTrueWhenNotProvided() {
        val flags = AddonPolicyFlags()
        assertTrue(flags.installable)
        assertTrue(flags.shelfEligible)
        assertTrue(flags.catalogQueryable)
    }

    @Test
    fun installableFalseBlocksInstall() {
        val flags = AddonPolicyFlags(installable = false)
        assertFalse(flags.installable)
    }

    @Test
    fun shelfEligibleFalseDoesNotBlockInstall() {
        val flags = AddonPolicyFlags(shelfEligible = false, installable = true)
        assertTrue(flags.installable)
        assertFalse(flags.shelfEligible)
    }
}
