package com.torve.presentation.settings

import kotlin.test.*

class ConnectionSetupPlanTest {
    @Test fun optionalAccountsAreNotRequiredToUseAWorkingServer() {
        val state = SettingsUiState(jellyfinServerUrl = "https://server", jellyfinApiKey = "key", jellyfinConnected = true)
        val plan = connectionSetupPlan(buildIntegrationReadinessSummary(state, emptyList()))
        assertTrue(plan.playbackConfigured)
        assertEquals("Your playback setup is ready", plan.title)
    }
    @Test fun newSetupProvidesAChoiceOfPlaybackRoutes() {
        val plan = connectionSetupPlan(buildIntegrationReadinessSummary(SettingsUiState(), emptyList()))
        assertFalse(plan.playbackConfigured)
        assertEquals(ConnectionDestination.STREAMING_SETUP, integrationRepairDestination(IntegrationWorkflow.WATCH_NOW, SettingsUiState()))
    }
    @Test fun aBrokenSimklAccountIsTargetedEvenWhenTraktIsHealthy() {
        val state = SettingsUiState(traktConnected = true, simklConnected = true, simklError = "Expired")
        assertEquals(ConnectionDestination.SIMKL, integrationRepairDestination(IntegrationWorkflow.TRACKING, state))
    }
    @Test fun serverAndRequestActionsOpenTheServiceThatWasConfigured() {
        assertEquals(ConnectionDestination.PLEX, integrationRepairDestination(IntegrationWorkflow.PERSONAL_LIBRARY, SettingsUiState(plexServerUrl = "https://plex")))
        assertEquals(ConnectionDestination.SEERR, integrationRepairDestination(IntegrationWorkflow.SAVE_TO_LIBRARY, SettingsUiState()))
        assertEquals(ConnectionDestination.AUTOMATION, integrationRepairDestination(IntegrationWorkflow.AUTOMATION, SettingsUiState()))
    }
    @Test fun streamingAddonsAndProviderCredentialsHaveDifferentRepairRoutes() {
        assertEquals(ConnectionDestination.ADDONS, integrationRepairDestination(IntegrationWorkflow.WATCH_NOW, SettingsUiState(hasStreamAddon = true)))
        assertEquals(ConnectionDestination.DEBRID, integrationRepairDestination(IntegrationWorkflow.WATCH_NOW, SettingsUiState(debridApiKey = "stored")))
    }
}
