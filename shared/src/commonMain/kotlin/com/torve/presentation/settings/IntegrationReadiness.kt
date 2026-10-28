package com.torve.presentation.settings

import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus

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
            val automation = if (!item(IntegrationWorkflow.AUTOMATION).enabled) {
                "Automation off"
            } else if (automationCount == 0) {
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
    val enabled: Boolean = true,
) {
    val statusLabel: String
        get() = if (!enabled) "Off" else when (status) {
            IntegrationReadinessStatus.READY -> when (workflow) {
                IntegrationWorkflow.AUTOMATION -> "Configured"
                else -> "Ready"
            }
            IntegrationReadinessStatus.NEEDS_ATTENTION -> "Needs attention"
            IntegrationReadinessStatus.NOT_CONFIGURED -> "Not set up"
        }

    val shortLabel: String
        get() = if (!enabled) "$title off" else when (workflow) {
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
    providerHealth: List<ProviderHealthEntry> = emptyList(),
): IntegrationReadinessSummary {
    val hasEnabledDebrid = settings.connectedDebridProviders.isNotEmpty()
    val watchNow = when {
        hasEnabledDebrid && settings.hasStreamAddon -> readiness(
            IntegrationWorkflow.WATCH_NOW,
            "Watch now",
            "Streaming add-on and debrid are connected.",
            IntegrationReadinessStatus.READY,
        )
        hasEnabledDebrid -> readiness(
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
        settings.debridApiKey.isNotBlank() || settings.configuredDebridProviders.isNotEmpty() -> readiness(
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
        if (settings.traktConnected && settings.traktError.isNullOrBlank()) add("Trakt")
        if (settings.simklConnected && settings.simklError.isNullOrBlank()) add("SIMKL")
    }
    val trackingProblems = buildList {
        if (!settings.traktError.isNullOrBlank() ||
            (settings.traktAccessToken.isNotBlank() && !settings.traktConnected && !settings.traktLoading)
        ) add("Trakt")
        if (!settings.simklError.isNullOrBlank() ||
            (settings.simklAccessToken.isNotBlank() && !settings.simklConnected && !settings.simklLoading)
        ) add("SIMKL")
    }
    val trackingPartial = settings.traktAccessToken.isNotBlank() || settings.simklAccessToken.isNotBlank()
    val tracking = when {
        trackingProblems.isNotEmpty() -> readiness(
            IntegrationWorkflow.TRACKING,
            "Tracking and sync",
            buildString {
                append("${trackingProblems.joinToString(" and ")} ${if (trackingProblems.size == 1) "needs" else "need"} attention.")
                if (trackerNames.isNotEmpty()) append(" ${trackerNames.joinToString(" and ")} connected.")
            },
            IntegrationReadinessStatus.NEEDS_ATTENTION,
            (trackerNames + trackingProblems).distinct().size,
        )
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

    val problems = currentConnectionHealthProblems(settings, providerHealth).groupBy(::healthWorkflow)
    return IntegrationReadinessSummary(listOf(watchNow, personalLibrary, saveToLibrary, tracking, automation).map { item ->
        val enabled = when (item.workflow) {
            IntegrationWorkflow.SAVE_TO_LIBRARY, IntegrationWorkflow.AUTOMATION -> settings.optionalAppFeatures.arrStack
            IntegrationWorkflow.PERSONAL_LIBRARY -> settings.optionalAppFeatures.jellyfin || settings.plexConnected ||
                settings.plexServerUrl.isNotBlank() || settings.plexAccessToken.isNotBlank()
            else -> true
        }
        if (!enabled) return@map item.copy(enabled = false, status = IntegrationReadinessStatus.NOT_CONFIGURED,
            configuredCount = 0, detail = "Turned off in App features. Saved connections are kept.")
        val failed = problems[item.workflow].orEmpty()
        if (failed.isEmpty() || item.status == IntegrationReadinessStatus.NOT_CONFIGURED) item
        else item.copy(status = IntegrationReadinessStatus.NEEDS_ATTENTION, detail = when (item.workflow) {
            IntegrationWorkflow.WATCH_NOW -> "A streaming connection failed its latest check. Review your configured sources and credentials."
            IntegrationWorkflow.PERSONAL_LIBRARY -> "Your media server failed its latest connection check. Review the server and credentials."
            IntegrationWorkflow.SAVE_TO_LIBRARY -> "Seerr is unreachable or needs its credentials checked. Open library request settings and select Save and test."
            IntegrationWorkflow.TRACKING -> trackingHealthProblemDetail(failed)
            IntegrationWorkflow.AUTOMATION -> "One or more automation services failed their latest check. Review their connections and credentials."
        })
    })
}

/** A newer successful check supersedes an older error; health messages may contain endpoint details, so use fixed presentation copy. */
internal fun currentConnectionHealthProblems(settings: SettingsUiState, entries: List<ProviderHealthEntry>): List<ProviderHealthEntry> =
    entries.groupBy { it.providerKey }.values.map { records -> records.maxBy { it.lastCheckedAt ?: 0L } }
        .filter { it.status == ProviderHealthStatus.RED || it.status == ProviderHealthStatus.YELLOW }
        .filter { when (it.category) {
            ProviderHealthCategory.TRAKT -> settings.traktConnected || settings.traktAccessToken.isNotBlank()
            ProviderHealthCategory.SIMKL -> settings.simklConnected || settings.simklAccessToken.isNotBlank()
            ProviderHealthCategory.IPTV, ProviderHealthCategory.EPG -> settings.optionalAppFeatures.channels
            ProviderHealthCategory.REQUEST_MANAGER -> settings.optionalAppFeatures.arrStack
            ProviderHealthCategory.PLEX_JELLYFIN -> settings.optionalAppFeatures.jellyfin || settings.plexConnected ||
                settings.plexServerUrl.isNotBlank() || settings.plexAccessToken.isNotBlank()
            ProviderHealthCategory.ADDON -> settings.hasStreamAddon
            ProviderHealthCategory.DEBRID -> (settings.configuredDebridProviders.keys + settings.connectedDebridProviders.keys +
                if (settings.debridApiKey.isNotBlank()) setOf(settings.debridProvider) else emptySet())
                .any { provider -> it.providerKey == "debrid:${provider.name.lowercase()}" }
            else -> true
        } }

private fun trackingHealthProblemDetail(failed: List<ProviderHealthEntry>): String {
    val names = failed.mapNotNull { when (it.category) {
        ProviderHealthCategory.TRAKT -> "Trakt"
        ProviderHealthCategory.SIMKL -> "SIMKL"
        else -> null
    } }.distinct()
    return "${names.joinToString(" and ")} ${if (names.size == 1) "needs" else "need"} attention. Review the tracking connection."
}

private fun healthWorkflow(entry: ProviderHealthEntry): IntegrationWorkflow? = when {
    entry.providerKey == "automation:admin" -> IntegrationWorkflow.AUTOMATION
    entry.category == ProviderHealthCategory.REQUEST_MANAGER && entry.providerKey.startsWith("seerr:") -> IntegrationWorkflow.SAVE_TO_LIBRARY
    entry.category == ProviderHealthCategory.PLEX_JELLYFIN -> IntegrationWorkflow.PERSONAL_LIBRARY
    entry.category == ProviderHealthCategory.DEBRID || entry.category == ProviderHealthCategory.ADDON -> IntegrationWorkflow.WATCH_NOW
    entry.category == ProviderHealthCategory.TRAKT || entry.category == ProviderHealthCategory.SIMKL -> IntegrationWorkflow.TRACKING
    else -> null
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
