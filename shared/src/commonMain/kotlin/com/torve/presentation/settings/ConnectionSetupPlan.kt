package com.torve.presentation.settings

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry

enum class ConnectionDestination(val routeKey: String) {
    STREAMING_SETUP("streaming_setup"), ADDONS("addons"), DEBRID("debrid"),
    JELLYFIN("jellyfin"), PLEX("plex"), SEERR("seerr"), TRAKT("trakt"), SIMKL("simkl"), AUTOMATION("automation")
}

/** Choose the service that needs repair, not whichever tracker happens to exist. */
fun integrationRepairDestination(workflow: IntegrationWorkflow, settings: SettingsUiState,
    providerHealth: List<ProviderHealthEntry> = emptyList()): ConnectionDestination {
    val problems = currentConnectionHealthProblems(settings, providerHealth)
    return when (workflow) {
    IntegrationWorkflow.WATCH_NOW -> when {
        problems.any { it.category == ProviderHealthCategory.DEBRID } &&
            (settings.debridApiKey.isNotBlank() || settings.configuredDebridProviders.isNotEmpty()) -> ConnectionDestination.DEBRID
        problems.any { it.category == ProviderHealthCategory.ADDON } && settings.hasStreamAddon -> ConnectionDestination.ADDONS
        settings.debridApiKey.isNotBlank() || settings.configuredDebridProviders.isNotEmpty() -> ConnectionDestination.DEBRID
        settings.hasStreamAddon -> ConnectionDestination.ADDONS
        else -> ConnectionDestination.STREAMING_SETUP
    }
    IntegrationWorkflow.PERSONAL_LIBRARY -> if (settings.optionalAppFeatures.jellyfin && (settings.jellyfinServerUrl.isNotBlank() || settings.jellyfinApiKey.isNotBlank() ||
        (settings.plexServerUrl.isBlank() && settings.plexAccessToken.isBlank() && !settings.plexConnected))) ConnectionDestination.JELLYFIN else ConnectionDestination.PLEX
    IntegrationWorkflow.SAVE_TO_LIBRARY -> ConnectionDestination.SEERR
    IntegrationWorkflow.TRACKING -> when {
        !settings.traktError.isNullOrBlank() || (settings.traktAccessToken.isNotBlank() && !settings.traktConnected) -> ConnectionDestination.TRAKT
        !settings.simklError.isNullOrBlank() || (settings.simklAccessToken.isNotBlank() && !settings.simklConnected) -> ConnectionDestination.SIMKL
        problems.any { it.category == ProviderHealthCategory.TRAKT } && settings.traktConnected -> ConnectionDestination.TRAKT
        problems.any { it.category == ProviderHealthCategory.SIMKL } && settings.simklConnected -> ConnectionDestination.SIMKL
        settings.traktConnected -> ConnectionDestination.TRAKT
        settings.simklConnected -> ConnectionDestination.SIMKL
        else -> ConnectionDestination.TRAKT
    }
    IntegrationWorkflow.AUTOMATION -> ConnectionDestination.AUTOMATION
}
}

data class ConnectionSetupPlan(val playbackConfigured: Boolean, val title: String, val detail: String)
fun connectionSetupPlan(summary: IntegrationReadinessSummary): ConnectionSetupPlan {
    val playback = summary.item(IntegrationWorkflow.WATCH_NOW).status == IntegrationReadinessStatus.READY ||
        summary.item(IntegrationWorkflow.PERSONAL_LIBRARY).status == IntegrationReadinessStatus.READY
    return if (playback) ConnectionSetupPlan(true, "Your playback setup is ready", "Use your connected sources. Tracking and library requests below are optional.")
    else if (listOf(IntegrationWorkflow.WATCH_NOW, IntegrationWorkflow.PERSONAL_LIBRARY).any {
        summary.item(it).status == IntegrationReadinessStatus.NEEDS_ATTENTION
    }) ConnectionSetupPlan(true, "Check your playback connections", "Your sources are saved, but a connection needs attention. Review its settings below. Tracking and library requests are optional.")
    else ConnectionSetupPlan(false, "Choose how you want to watch", "Connect streaming sources or your own media server. One working playback route is enough.")
}
