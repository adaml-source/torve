package com.torve.presentation.settings

import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationServiceType

/**
 * A credential-free view of the user's media setup.
 *
 * The same model is rendered by phone, TV and desktop so those clients do not
 * disagree about whether a workflow is ready.  It deliberately contains no
 * URLs, account names, tokens or API keys.
 */
data class IntegrationReadinessSummary(
    val items: List<IntegrationReadinessItem>,
) {
    fun item(workflow: IntegrationWorkflow): IntegrationReadinessItem =
        items.first { it.workflow == workflow }

    val compactStatusLine: String
        get() {
            val watch = item(IntegrationWorkflow.WATCH_NOW).shortLabel
            val save = item(IntegrationWorkflow.SAVE_TO_LIBRARY).shortLabel
            val automationCount = item(IntegrationWorkflow.AUTOMATION).configuredCount
            val automation = if (automationCount == 0) {
                "Automation not set up"
            } else {
                "$automationCount automation service${if (automationCount == 1) "" else "s"}"
            }
            return "$watch | $save | $automation"
        }
}

data class IntegrationReadinessItem(
    val workflow: IntegrationWorkflow,
    val title: String,
    val detail: String,
    val status: IntegrationReadinessStatus,
    val configuredCount: Int = 0,
) {
    val statusLabel: String
        get() = when (status) {
            IntegrationReadinessStatus.READY -> when (workflow) {
                IntegrationWorkflow.AUTOMATION -> "Configured"
                else -> "Ready"
            }
            IntegrationReadinessStatus.NEEDS_ATTENTION -> "Needs attention"
            IntegrationReadinessStatus.NOT_CONFIGURED -> "Not set up"
        }

    val shortLabel: String
        get() = when (workflow) {
            IntegrationWorkflow.WATCH_NOW -> when (status) {
                IntegrationReadinessStatus.READY -> "Watch now ready"
                IntegrationReadinessStatus.NEEDS_ATTENTION -> "Watch now needs attention"
                IntegrationReadinessStatus.NOT_CONFIGURED -> "Watch now needs setup"
            }
            IntegrationWorkflow.SAVE_TO_LIBRARY -> when (status) {
                IntegrationReadinessStatus.READY -> "Save to library ready"
                IntegrationReadinessStatus.NEEDS_ATTENTION -> "Save to library needs attention"
                IntegrationReadinessStatus.NOT_CONFIGURED -> "Save to library needs setup"
            }
            else -> "$title: $statusLabel"
        }
}

enum class IntegrationWorkflow {
    WATCH_NOW,
    PERSONAL_LIBRARY,
    SAVE_TO_LIBRARY,
    TRACKING,
    AUTOMATION,
}

enum class IntegrationReadinessStatus {
    READY,
    NEEDS_ATTENTION,
    NOT_CONFIGURED,
}

fun buildIntegrationReadinessSummary(
    settings: SettingsUiState,
    automationInstances: List<AutomationInstance>,
): IntegrationReadinessSummary {
    val watchNow = when {
        settings.debridConnected && settings.hasStreamAddon -> readiness(
            IntegrationWorkflow.WATCH_NOW,
            "Watch now",
            "Streaming add-on and debrid are connected.",
            IntegrationReadinessStatus.READY,
        )
        settings.debridConnected -> readiness(
            IntegrationWorkflow.WATCH_NOW,
            "Watch now",
            "Debrid is connected for immediate playback.",
            IntegrationReadinessStatus.READY,
        )
        settings.hasStreamAddon -> readiness(
            IntegrationWorkflow.WATCH_NOW,
            "Watch now",
            "A streaming add-on is connected.",
            IntegrationReadinessStatus.READY,
        )
        settings.debridApiKey.isNotBlank() || settings.connectedDebridProviders.isNotEmpty() -> readiness(
            IntegrationWorkflow.WATCH_NOW,
            "Watch now",
            "Playback credentials exist but the connection needs attention.",
            IntegrationReadinessStatus.NEEDS_ATTENTION,
        )
        else -> readiness(
            IntegrationWorkflow.WATCH_NOW,
            "Watch now",
            "Connect a streaming add-on or debrid provider.",
            IntegrationReadinessStatus.NOT_CONFIGURED,
        )
    }

    val jellyfinComplete = settings.jellyfinServerUrl.isNotBlank() && settings.jellyfinApiKey.isNotBlank()
    val jellyfinVerified = jellyfinComplete && settings.jellyfinConnected
    val jellyfinPartial = settings.jellyfinServerUrl.isNotBlank() || settings.jellyfinApiKey.isNotBlank()
    val plexComplete = settings.plexConnected
    val plexPartial = settings.plexServerUrl.isNotBlank() || settings.plexAccessToken.isNotBlank()
    val libraryNames = buildList {
        if (jellyfinVerified) add("Jellyfin")
        if (plexComplete) add("Plex")
    }
    val personalLibrary = when {
        libraryNames.isNotEmpty() -> readiness(
            IntegrationWorkflow.PERSONAL_LIBRARY,
            "My library",
            "${libraryNames.joinToString(" and ")} connected for personal media.",
            IntegrationReadinessStatus.READY,
            libraryNames.size,
        )
        jellyfinComplete || jellyfinPartial || plexPartial -> readiness(
            IntegrationWorkflow.PERSONAL_LIBRARY,
            "My library",
            "A media-server setup is incomplete or needs a connection test.",
            IntegrationReadinessStatus.NEEDS_ATTENTION,
        )
        else -> readiness(
            IntegrationWorkflow.PERSONAL_LIBRARY,
            "My library",
            "Connect Jellyfin or Plex to play personal media.",
            IntegrationReadinessStatus.NOT_CONFIGURED,
        )
    }

    val seerrPartial = settings.seerrServerUrl.isNotBlank() || settings.seerrApiKey.isNotBlank()
    val seerrVerified = settings.seerrConnected
    val saveToLibrary = when {
        seerrVerified -> readiness(
            IntegrationWorkflow.SAVE_TO_LIBRARY,
            "Save to library",
            "Seerr is connected for simple movie and season requests.",
            IntegrationReadinessStatus.READY,
            1,
        )
        settings.seerrConnected || seerrPartial -> readiness(
            IntegrationWorkflow.SAVE_TO_LIBRARY,
            "Save to library",
            "Seerr is configured; select Save and test to verify it on this device.",
            IntegrationReadinessStatus.NEEDS_ATTENTION,
        )
        else -> readiness(
            IntegrationWorkflow.SAVE_TO_LIBRARY,
            "Save to library",
            "Connect Seerr to request movies and selected seasons.",
            IntegrationReadinessStatus.NOT_CONFIGURED,
        )
    }

    val trackerNames = buildList {
        if (settings.traktConnected) add("Trakt")
        if (settings.simklConnected) add("SIMKL")
    }
    val trackingPartial = settings.traktAccessToken.isNotBlank() || settings.simklAccessToken.isNotBlank()
    val tracking = when {
        trackerNames.isNotEmpty() -> readiness(
            IntegrationWorkflow.TRACKING,
            "Tracking and sync",
            "${trackerNames.joinToString(" and ")} connected.",
            IntegrationReadinessStatus.READY,
            trackerNames.size,
        )
        trackingPartial -> readiness(
            IntegrationWorkflow.TRACKING,
            "Tracking and sync",
            "A tracking account needs to be reconnected.",
            IntegrationReadinessStatus.NEEDS_ATTENTION,
        )
        else -> readiness(
            IntegrationWorkflow.TRACKING,
            "Tracking and sync",
            "Optional: connect Trakt or SIMKL for cross-app watch history.",
            IntegrationReadinessStatus.NOT_CONFIGURED,
        )
    }

    val enabledTypes = automationInstances
        .asSequence()
        .filter { it.enabled }
        .map { it.serviceType }
        .distinct()
        .sortedBy(AutomationServiceType::ordinal)
        .toList()
    val automation = if (enabledTypes.isEmpty()) {
        readiness(
            IntegrationWorkflow.AUTOMATION,
            "Advanced automation",
            "Optional: connect *Arr services for administration and diagnostics.",
            IntegrationReadinessStatus.NOT_CONFIGURED,
        )
    } else {
        readiness(
            IntegrationWorkflow.AUTOMATION,
            "Advanced automation",
            "${enabledTypes.joinToString { it.displayName() }} configured.",
            IntegrationReadinessStatus.READY,
            enabledTypes.size,
        )
    }

    return IntegrationReadinessSummary(
        listOf(watchNow, personalLibrary, saveToLibrary, tracking, automation),
    )
}

private fun readiness(
    workflow: IntegrationWorkflow,
    title: String,
    detail: String,
    status: IntegrationReadinessStatus,
    configuredCount: Int = 0,
) = IntegrationReadinessItem(workflow, title, detail, status, configuredCount)

private fun AutomationServiceType.displayName(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }
