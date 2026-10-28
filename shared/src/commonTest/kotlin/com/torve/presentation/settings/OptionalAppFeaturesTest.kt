package com.torve.presentation.settings

import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.providerhealth.ProviderHealthCategory
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OptionalAppFeaturesTest {
    private class Prefs : PreferencesRepository {
        val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String) = values[key]
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    @Test fun existingProfilesKeepTheirFeaturesAndDisablingPreservesConfiguration() = runTest {
        val prefs = Prefs()
        assertEquals(OptionalAppFeatures(), prefs.readOptionalAppFeatures())
        prefs.setString("jellyfin_server_url", "http://library")
        prefs.setString(OptionalAppFeature.JELLYFIN.preferenceKey, "false")
        assertFalse(prefs.readOptionalAppFeatures().jellyfin)
        assertFalse(prefs.isHealthProviderEnabled("library:plex_jellyfin"))
        assertEquals("http://library", prefs.getString("jellyfin_server_url"))
        prefs.setString(OptionalAppFeature.JELLYFIN.preferenceKey, "true")
        assertTrue(prefs.readOptionalAppFeatures().jellyfin)
    }
    @Test fun allDisabledFeaturesBlockTheirHealthChecksIncludingLegacyKeys() = runTest {
        val prefs = Prefs()
        OptionalAppFeature.entries.forEach { prefs.setString(it.preferenceKey, "false") }
        listOf("iptv:active", "iptv:epg", "sports:indexer", "automation:admin", "seerr:request_manager", "library:plex_jellyfin")
            .forEach { assertFalse(prefs.isHealthProviderEnabled(it), it) }
        assertFalse(prefs.isHealthProviderEnabled("old-channels", ProviderHealthCategory.IPTV))
        assertFalse(prefs.isHealthProviderEnabled("old-seerr", ProviderHealthCategory.REQUEST_MANAGER))
        assertTrue(prefs.isHealthProviderEnabled("debrid:real_debrid"))
        assertTrue(prefs.isHealthProviderEnabled("panda:usenet_indexers"))
    }
    @Test fun disablingJellyfinDoesNotDisableAnIndependentPlexConnection() = runTest {
        val prefs = Prefs()
        prefs.setString(OptionalAppFeature.JELLYFIN.preferenceKey, "false")
        prefs.setString("plex_server_url", "http://plex")
        assertTrue(prefs.isHealthProviderEnabled("library:plex_jellyfin"))
    }
    @Test fun eachSwitchIsIndependent() {
        OptionalAppFeature.availableEntries.forEach { disabled ->
            val flags = OptionalAppFeatures().withEnabled(disabled, false)
            OptionalAppFeature.entries.forEach { assertEquals(it != disabled && it != OptionalAppFeature.SPORTS, flags.isEnabled(it)) }
        }
    }
    @Test fun retiredSportsCannotBeRestoredByLegacyPreferences() = runTest {
        val prefs = Prefs()
        prefs.setString(OptionalAppFeature.SPORTS.preferenceKey, "true")
        assertFalse(prefs.isAppFeatureEnabled(OptionalAppFeature.SPORTS))
        assertFalse(prefs.readOptionalAppFeatures().sports)
        assertFalse(OptionalAppFeatures(sports = true).isEnabled(OptionalAppFeature.SPORTS))
        assertFalse(OptionalAppFeatures().withEnabled(OptionalAppFeature.SPORTS, true).sports)
        assertFalse(OptionalAppFeature.SPORTS in OptionalAppFeature.availableEntries)
        assertFalse(prefs.isHealthProviderEnabled("sports:indexer"))
        assertTrue(prefs.isHealthProviderEnabled("panda:usenet_indexers"))
    }
    @Test fun disabledSavedServicesAreOffInsteadOfNeedsAttention() {
        val state = SettingsUiState(optionalAppFeatures = OptionalAppFeatures(jellyfin = false, arrStack = false),
            jellyfinServerUrl = "http://library", jellyfinApiKey = "saved", jellyfinConnected = true,
            seerrServerUrl = "http://seerr", seerrApiKey = "saved", seerrConnected = true)
        val summary = buildIntegrationReadinessSummary(state, emptyList())
        for (workflow in listOf(IntegrationWorkflow.PERSONAL_LIBRARY, IntegrationWorkflow.SAVE_TO_LIBRARY, IntegrationWorkflow.AUTOMATION)) {
            assertEquals("Off", summary.item(workflow).statusLabel)
            assertFalse(summary.item(workflow).enabled)
        }
        assertTrue(summary.compactStatusLine.contains("Automation off"))
    }
}
