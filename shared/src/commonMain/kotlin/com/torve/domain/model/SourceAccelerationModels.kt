package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SourceAccelerationRequest(
    val mediaType: MediaType,
    val imdbId: String,
    val contentId: String? = null,
    val title: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val context: SourceAccelerationContext = SourceAccelerationContext(),
) {
    val contentKey: String
        get() = buildString {
            append(mediaType.name)
            append(':')
            append(imdbId)
            append(':')
            append(seasonNumber ?: -1)
            append(':')
            append(episodeNumber ?: -1)
        }

    val isEpisodeRequest: Boolean
        get() = seasonNumber != null && episodeNumber != null

    val resolvedContentId: String?
        get() = contentId ?: imdbId.takeIf { it.isNotBlank() }?.let { "imdb:$it" }
}

@Serializable
data class SourceAccelerationContext(
    val addons: List<InstalledAddon> = emptyList(),
    val debridAccounts: Map<DebridServiceType, String> = emptyMap(),
    val preferences: StreamPreferences = StreamPreferences(),
    val startupFetchPolicy: StreamFetchPolicy = StreamFetchPolicy.PLAYBACK_STARTUP,
)

@Serializable
enum class ReadinessState {
    READY_NOW,
    READY_WITH_RESOLVE,
    LOOKUP_ONLY,
    EMPTY,
    UNAVAILABLE,
}

@Serializable
enum class CandidateProvenanceKind {
    STARTUP_FETCH,
    RECENT_SUCCESS,
    INVENTORY_MATCH,
    HASH_AVAILABILITY,
    LOCAL_MEMORY,
    /**
     * Source row is a backend-managed NzbDAV candidate. `accelerationSourceKey`
     * carries the backend-issued candidate_id that /resolver/usenet/warm and
     * /resolver/usenet/resolve consume. Surfaced in the same ranked list as
     * Panda/debrid rows; the source sheet reads a sidecar map keyed by
     * candidate_id to render Ready / Preparing / Unavailable pills.
     */
    USENET_NZBDAV,
    UNKNOWN,
}

@Serializable
data class CandidateProvenance(
    val kind: CandidateProvenanceKind,
    val providerLabel: String? = null,
    val sourceLabel: String? = null,
)

@Serializable
enum class StartupConfidenceReasonCode {
    RECENT_SUCCESS,
    DIRECT_PLAYABLE_URL,
    HASH_CACHED,
    KNOWN_INFO_HASH,
    CONNECTED_SERVICE_MATCH,
    EXACT_CONTENT_MATCH,
    EXACT_EPISODE_MATCH,
    PROVIDER_SIGNAL,
    NO_ACCELERATION_SIGNAL,
}

@Serializable
data class StartupCandidate(
    val streamKey: String,
    val title: String,
    val qualityLabel: String,
    val quality: StreamQuality = StreamQuality.UNKNOWN,
    val addonName: String,
    val sourceLabel: String? = null,
    val readinessState: ReadinessState,
    val provenance: CandidateProvenance,
    val confidenceReasons: List<StartupConfidenceReasonCode> = emptyList(),
    val isDirectPlayback: Boolean = false,
    val isKnownCached: Boolean = false,
    val sizeBytes: Long? = null,
    val seeds: Int? = null,
    val score: Double? = null,
    val scoreBreakdown: Map<String, Double> = emptyMap(),
    val memoryId: String? = null,
)

@Serializable
enum class InventoryMatchType {
    EXACT_CONTENT,
    EXACT_EPISODE,
    TITLE_AND_YEAR,
    TITLE_ONLY,
    UNKNOWN,
}

@Serializable
data class InventoryMatch(
    val matchKey: String,
    val providerLabel: String,
    val displayTitle: String,
    val matchType: InventoryMatchType = InventoryMatchType.UNKNOWN,
    val readinessState: ReadinessState = ReadinessState.LOOKUP_ONLY,
    val confidenceReasons: List<StartupConfidenceReasonCode> = emptyList(),
    val qualityLabel: String? = null,
    val sizeBytes: Long? = null,
    val lastSeenAt: Long? = null,
)

@Serializable
enum class HashAvailabilityState {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

@Serializable
data class KnownHashAvailabilityObservation(
    val infoHash: String,
    val providerLabel: String,
    val availabilityState: HashAvailabilityState,
    val readinessState: ReadinessState,
    val confidenceReasons: List<StartupConfidenceReasonCode> = emptyList(),
    val observedAt: Long? = null,
    val expiresAt: Long? = null,
)

@Serializable
data class RecentSuccessCandidate(
    val streamKey: String,
    val title: String,
    val providerLabel: String? = null,
    val addonName: String,
    val qualityLabel: String,
    val quality: StreamQuality = StreamQuality.UNKNOWN,
    val sourceLabel: String? = null,
    val readinessState: ReadinessState = ReadinessState.READY_NOW,
    val confidenceReasons: List<StartupConfidenceReasonCode> = emptyList(),
    val lastSuccessfulAt: Long,
    val successCount: Int,
)

@Serializable
data class StartupCandidatesSnapshot(
    val request: SourceAccelerationRequest,
    val readinessState: ReadinessState,
    val candidates: List<StartupCandidate> = emptyList(),
) {
    val isEmpty: Boolean
        get() = candidates.isEmpty()
}

@Serializable
data class RecentSuccessfulSourcesSnapshot(
    val request: SourceAccelerationRequest,
    val readinessState: ReadinessState,
    val candidates: List<RecentSuccessCandidate> = emptyList(),
) {
    val isEmpty: Boolean
        get() = candidates.isEmpty()
}

@Serializable
data class InventoryMatchesSnapshot(
    val request: SourceAccelerationRequest,
    val readinessState: ReadinessState,
    val matches: List<InventoryMatch> = emptyList(),
) {
    val isEmpty: Boolean
        get() = matches.isEmpty()
}

@Serializable
data class KnownHashAvailabilitySnapshot(
    val request: SourceAccelerationRequest,
    val readinessState: ReadinessState,
    val observations: List<KnownHashAvailabilityObservation> = emptyList(),
) {
    val isEmpty: Boolean
        get() = observations.isEmpty()
}

@Serializable
enum class ContentWarmupTrigger {
    DETAIL_OPEN,
    TV_PLAY_ACTION_FOCUS,
    NEXT_EPISODE_AUTOPLAY,
}

@Serializable
enum class ContentWarmupDisposition {
    WARMED,
    REUSED_RECENT,
    SKIPPED_NO_CONTEXT,
    SKIPPED_IN_FLIGHT,
    FAILED,
}

@Serializable
data class ContentWarmupResult(
    val request: SourceAccelerationRequest,
    val trigger: ContentWarmupTrigger,
    val disposition: ContentWarmupDisposition,
    val snapshot: StartupCandidatesSnapshot? = null,
    val completedAt: Long? = null,
) {
    val isUsable: Boolean
        get() = snapshot != null && disposition != ContentWarmupDisposition.FAILED
}
