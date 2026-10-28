package com.torve.android.tv.screens

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvProviderAttentionTest {
    private fun entry(category: ProviderHealthCategory, key: String, status: ProviderHealthStatus = ProviderHealthStatus.RED) =
        ProviderHealthEntry(category, key, key, status)

    @Test
    fun optionalUnconfiguredAndPendingProvidersDoNotRaiseAnAlert() {
        val entries = ProviderHealthStatus.entries.map { entry(ProviderHealthCategory.TRAKT, it.name, it) }
        assertEquals(listOf(ProviderHealthStatus.RED, ProviderHealthStatus.YELLOW), providerAttentionEntries(entries).map { it.status })
    }

    @Test
    fun failuresComeBeforeWarningsWithStableOrderingAcrossHealthRefreshes() {
        val red = entry(ProviderHealthCategory.TRAKT, "Trakt")
        val warning = entry(ProviderHealthCategory.SIMKL, "SIMKL", ProviderHealthStatus.YELLOW)
        assertEquals(listOf(red, warning), providerAttentionEntries(listOf(warning, red)))
        assertEquals(providerAttentionItemId(red), providerAttentionItemId(red.copy(lastCheckedAt = 42, message = "Updated check")))
    }

    @Test
    fun seerrAndArrAlertsOpenTheirOwnConnectionSetup() {
        assertEquals(TvProviderAttentionDestination.SEERR, providerAttentionDestination(entry(ProviderHealthCategory.REQUEST_MANAGER, "seerr:request_manager")))
        assertEquals(TvProviderAttentionDestination.AUTOMATION, providerAttentionDestination(entry(ProviderHealthCategory.REQUEST_MANAGER, "automation:admin")))
    }

    @Test
    fun providerAlertsMarkTheOverviewAndTheirActualSettingsTab() {
        val entries = listOf(entry(ProviderHealthCategory.USENET_INDEXER, "indexer"), entry(ProviderHealthCategory.EPG, "epg"))
        assertEquals(setOf(TvSettingsCategory.CONNECTIONS, TvSettingsCategory.ADVANCED, TvSettingsCategory.LIBRARY), providerAttentionCategories(entries))
        assertTrue(providerAttentionCategories(emptyList()).isEmpty())
    }

    @Test
    fun mediaServersAndAddonsUseTheirActualAdvancedSettings() {
        assertEquals(TvProviderAttentionDestination.PLEX, providerAttentionDestination(entry(ProviderHealthCategory.PLEX_JELLYFIN, "plex")))
        assertEquals(TvProviderAttentionDestination.JELLYFIN, providerAttentionDestination(entry(ProviderHealthCategory.PLEX_JELLYFIN, "jellyfin")))
        assertEquals(TvProviderAttentionDestination.ADDONS, providerAttentionDestination(entry(ProviderHealthCategory.ADDON, "addon")))
        assertEquals(TvProviderAttentionDestination.CHANNELS, providerAttentionDestination(entry(ProviderHealthCategory.IPTV, "playlist")))
    }
}
