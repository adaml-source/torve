package com.torve.data.addon

import com.torve.domain.model.*
import kotlin.test.*

class StreamingCataloguesTest {
    private fun addon(url: String = "https://personal.example/config/manifest.json", priority: Int = 0) = InstalledAddon(url,
        AddonManifest("pw.ers.netflix-catalog", "Streaming Catalogs", "1", resources = listOf("catalog"),
            catalogs = listOf(AddonCatalog("movie", "nfx", "Netflix"), AddonCatalog("series", "nfx", "Netflix"),
                AddonCatalog("movie", "amp", "Prime Video"), AddonCatalog("movie", "unknown", "Other"))), priority = priority)
    @Test fun preservesConfiguredTransportAndUsesExtensionHostedLogos() {
        val source = streamingCatalogueSources(listOf(addon())).first()
        assertEquals("https://personal.example/config/manifest.json", source.addon.manifestUrl)
        assertEquals("https://personal.example/netflix.webp", source.service.logoUrl)
        assertEquals(8, source.service.id)
        assertEquals(3, streamingCatalogueSources(listOf(addon())).size)
        assertEquals(3, streamingCatalogueSources(listOf(addon())).map { it.key }.distinct().size)
    }
    @Test fun criterionKeepsTheExistingFavouriteIdentifier() {
        val installed = addon().let { it.copy(manifest = it.manifest.copy(catalogs = listOf(AddonCatalog("movie", "crc")))) }
        assertEquals(258, streamingCatalogueSources(listOf(installed)).single().service.id)
    }
    @Test fun neverRecognizesUninstalledDisabledOrUnrelatedCatalogues() {
        assertTrue(streamingCatalogueSources(emptyList()).isEmpty())
        assertTrue(streamingCatalogueSources(listOf(addon().copy(isEnabled = false))).isEmpty())
        assertTrue(streamingCatalogueSources(listOf(addon().let { it.copy(manifest = it.manifest.copy(id = "unrelated")) })).isEmpty())
    }
    @Test fun sourcePriorityAndConfigurationScopeStayDistinct() {
        val first = addon(priority = 5)
        val preferred = addon("https://other.example/german/manifest.json", priority = 0)
        assertTrue(streamingCatalogueSources(listOf(first, preferred)).all { it.addon == preferred })
        assertNotEquals(streamingCatalogueSources(listOf(first)).first().key, streamingCatalogueSources(listOf(preferred)).first().key)
    }
    @Test fun searchAndRequiredExtraCataloguesDoNotBecomeProviderFeeds() {
        val original = addon()
        val invalid = original.copy(manifest = original.manifest.copy(catalogs = listOf(
            AddonCatalog("movie", "nfx", extra = listOf(AddonExtra("search", isRequired = true))),
            AddonCatalog("channel", "amp"))))
        assertTrue(streamingCatalogueSources(listOf(invalid)).isEmpty())
    }
}
