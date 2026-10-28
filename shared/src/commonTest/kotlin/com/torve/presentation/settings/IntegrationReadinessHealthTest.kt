package com.torve.presentation.settings

import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.model.DebridServiceType
import com.torve.domain.providerhealth.*
import kotlin.test.*

class IntegrationReadinessHealthTest {
    private val seerr = SettingsUiState(seerrServerUrl = "https://requests.example", seerrApiKey = "stored-key", seerrConnected = true)
    private fun health(category: ProviderHealthCategory, key: String, status: ProviderHealthStatus = ProviderHealthStatus.RED, checked: Long = 20) =
        ProviderHealthEntry(category, key, "Private endpoint", status, checked, "https://private.example?api_key=super-secret")

    @Test fun aFailedSeerrCheckOverridesAnEarlierSuccessfulConnectionWithoutLeakingEndpoints() {
        val summary = buildIntegrationReadinessSummary(seerr, emptyList(), listOf(health(ProviderHealthCategory.REQUEST_MANAGER, "seerr:request_manager")))
        val item = summary.item(IntegrationWorkflow.SAVE_TO_LIBRARY)
        assertEquals(IntegrationReadinessStatus.NEEDS_ATTENTION, item.status)
        assertTrue(item.detail.contains("Save and test"))
        assertFalse(summary.items.joinToString { it.detail }.contains("super-secret"))
        assertFalse(item.detail.contains("private.example"))
    }

    @Test fun automationAndSeerrShareAHealthCategoryButHaveIndependentReadiness() {
        val admin = AutomationInstance("radarr", AutomationServiceType.RADARR, "Radarr", "http://localhost")
        val summary = buildIntegrationReadinessSummary(seerr, listOf(admin), listOf(health(ProviderHealthCategory.REQUEST_MANAGER, "automation:admin")))
        assertEquals(IntegrationReadinessStatus.NEEDS_ATTENTION, summary.item(IntegrationWorkflow.AUTOMATION).status)
        assertEquals(IntegrationReadinessStatus.READY, summary.item(IntegrationWorkflow.SAVE_TO_LIBRARY).status)
        assertEquals(1, summary.item(IntegrationWorkflow.AUTOMATION).configuredCount)
    }

    @Test fun aFailedMediaServerCheckOffersRepairOfTheSavedSetup() {
        val state = SettingsUiState(jellyfinServerUrl = "https://media.example", jellyfinApiKey = "stored", jellyfinConnected = true)
        val summary = buildIntegrationReadinessSummary(state, emptyList(), listOf(health(ProviderHealthCategory.PLEX_JELLYFIN, "library:plex_jellyfin")))
        assertEquals(IntegrationReadinessStatus.NEEDS_ATTENTION, summary.item(IntegrationWorkflow.PERSONAL_LIBRARY).status)
        val plan = connectionSetupPlan(summary)
        assertTrue(plan.playbackConfigured)
        assertEquals("Check your playback connections", plan.title)
    }

    @Test fun newerSuccessClearsAnOlderFailureButCannotHideATrackerSyncError() {
        val checks = listOf(health(ProviderHealthCategory.REQUEST_MANAGER, "seerr:request_manager", checked = 10),
            health(ProviderHealthCategory.REQUEST_MANAGER, "seerr:request_manager", ProviderHealthStatus.GREEN, 30),
            health(ProviderHealthCategory.TRAKT, "trakt:account", ProviderHealthStatus.GREEN, 30))
        val summary = buildIntegrationReadinessSummary(seerr.copy(traktConnected = true, traktError = "Sync failed"), emptyList(), checks)
        assertEquals(IntegrationReadinessStatus.READY, summary.item(IntegrationWorkflow.SAVE_TO_LIBRARY).status)
        assertEquals(IntegrationReadinessStatus.NEEDS_ATTENTION, summary.item(IntegrationWorkflow.TRACKING).status)
    }

    @Test fun removedOrUnconfiguredConnectionsDoNotInheritOldHealthErrors() {
        val old = listOf(health(ProviderHealthCategory.REQUEST_MANAGER, "seerr:request_manager"),
            health(ProviderHealthCategory.SIMKL, "simkl:account"), health(ProviderHealthCategory.ADDON, "addon:stremio_manifest"))
        assertTrue(buildIntegrationReadinessSummary(SettingsUiState(), emptyList(), old).items.all {
            it.status == IntegrationReadinessStatus.NOT_CONFIGURED
        })
        val current = buildIntegrationReadinessSummary(SettingsUiState(traktConnected = true), emptyList(), old)
        assertEquals(IntegrationReadinessStatus.READY, current.item(IntegrationWorkflow.TRACKING).status)
    }

    @Test fun aBrokenAddonHasItsOwnRepairDestinationEvenWithStoredDebridCredentials() {
        val state = SettingsUiState(hasStreamAddon = true, debridApiKey = "stored", connectedDebridProviders = mapOf(DebridServiceType.REAL_DEBRID to "stored"))
        val checks = listOf(health(ProviderHealthCategory.ADDON, "addon:stremio_manifest"))
        val summary = buildIntegrationReadinessSummary(state, emptyList(), checks)
        assertEquals(IntegrationReadinessStatus.NEEDS_ATTENTION, summary.item(IntegrationWorkflow.WATCH_NOW).status)
        assertEquals(ConnectionDestination.ADDONS, integrationRepairDestination(IntegrationWorkflow.WATCH_NOW, state, checks))
        assertEquals("Check your playback connections", connectionSetupPlan(summary).title)
    }

    @Test fun aFailedSecondaryTrackerOpensThatTrackerWithoutDisconnectingTheHealthyOne() {
        val state = SettingsUiState(traktConnected = true, simklConnected = true)
        val checks = listOf(health(ProviderHealthCategory.SIMKL, "simkl:account"))
        val item = buildIntegrationReadinessSummary(state, emptyList(), checks).item(IntegrationWorkflow.TRACKING)
        assertEquals(IntegrationReadinessStatus.NEEDS_ATTENTION, item.status)
        assertTrue(item.detail.startsWith("SIMKL needs attention"))
        assertEquals(ConnectionDestination.SIMKL, integrationRepairDestination(IntegrationWorkflow.TRACKING, state, checks))
    }
}
