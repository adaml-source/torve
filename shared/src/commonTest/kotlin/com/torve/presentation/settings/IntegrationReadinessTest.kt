package com.torve.presentation.settings

import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegrationReadinessTest {
    @Test
    fun emptySetupExplainsEachWorkflowWithoutMarkingItReady() {
        val summary = buildIntegrationReadinessSummary(SettingsUiState(), emptyList())
        assertEquals(5, summary.items.size)
        assertTrue(summary.items.all { it.status == IntegrationReadinessStatus.NOT_CONFIGURED })
        assertEquals(
            "Watch now needs setup | Save to library needs setup | Automation not set up",
            summary.compactStatusLine,
        )
    }

    @Test
    fun configuredServicesProduceTheSameOutcomeStatusForEveryClient() {
        val settings = SettingsUiState(
            debridConnected = true,
            hasStreamAddon = true,
            traktConnected = true,
            jellyfinServerUrl = "https://media.example",
            jellyfinApiKey = "jellyfin-secret",
            jellyfinConnected = true,
            seerrServerUrl = "https://requests.example",
            seerrApiKey = "seerr-secret",
            seerrConnected = true,
            seerrStatusMessage = "Connection successful and synced with your account",
        )
        val automation = listOf(
            instance("radarr", AutomationServiceType.RADARR),
            instance("sonarr", AutomationServiceType.SONARR),
            instance("duplicate-radarr", AutomationServiceType.RADARR),
        )
        val summary = buildIntegrationReadinessSummary(settings, automation)
        assertEquals(IntegrationReadinessStatus.READY, summary.item(IntegrationWorkflow.WATCH_NOW).status)
        assertEquals(IntegrationReadinessStatus.READY, summary.item(IntegrationWorkflow.PERSONAL_LIBRARY).status)
        assertEquals(IntegrationReadinessStatus.READY, summary.item(IntegrationWorkflow.SAVE_TO_LIBRARY).status)
        assertEquals(2, summary.item(IntegrationWorkflow.AUTOMATION).configuredCount)
        assertEquals(
            "Watch now ready | Save to library ready | 2 automation services",
            summary.compactStatusLine,
        )
    }

    @Test
    fun readinessUsesTypedConnectionStateInsteadOfTransientStatusCopy() {
        val restored = SettingsUiState(
            jellyfinServerUrl = "https://media.example",
            jellyfinApiKey = "jellyfin-secret",
            jellyfinConnected = true,
            jellyfinStatusMessage = null,
            seerrServerUrl = "https://requests.example",
            seerrApiKey = "seerr-secret",
            seerrConnected = true,
            seerrStatusMessage = null,
        )
        val misleadingCopy = restored.copy(
            jellyfinStatusMessage = "Account sync failed after the connection succeeded",
            seerrStatusMessage = "Saved",
        )

        listOf(restored, misleadingCopy).forEach { settings ->
            val summary = buildIntegrationReadinessSummary(settings, emptyList())
            assertEquals(IntegrationReadinessStatus.READY, summary.item(IntegrationWorkflow.PERSONAL_LIBRARY).status)
            assertEquals(IntegrationReadinessStatus.READY, summary.item(IntegrationWorkflow.SAVE_TO_LIBRARY).status)
        }
    }

    @Test
    fun partialCredentialsNeedAttentionAndSecretsNeverEnterPresentationCopy() {
        val summary = buildIntegrationReadinessSummary(
            SettingsUiState(
                debridApiKey = "debrid-secret",
                jellyfinApiKey = "jellyfin-secret",
                seerrApiKey = "seerr-secret",
                traktAccessToken = "trakt-secret",
            ),
            emptyList(),
        )
        val rendered = summary.items.joinToString { "${it.title} ${it.detail}" }
        assertEquals(IntegrationReadinessStatus.NEEDS_ATTENTION, summary.item(IntegrationWorkflow.WATCH_NOW).status)
        assertEquals(IntegrationReadinessStatus.NEEDS_ATTENTION, summary.item(IntegrationWorkflow.PERSONAL_LIBRARY).status)
        assertEquals(IntegrationReadinessStatus.NEEDS_ATTENTION, summary.item(IntegrationWorkflow.SAVE_TO_LIBRARY).status)
        assertEquals(IntegrationReadinessStatus.NEEDS_ATTENTION, summary.item(IntegrationWorkflow.TRACKING).status)
        assertFalse(rendered.contains("secret", ignoreCase = true))
    }

    private fun instance(id: String, type: AutomationServiceType) = AutomationInstance(
        id = id,
        serviceType = type,
        name = id,
        serverUrl = "http://localhost",
    )
}
