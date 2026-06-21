package com.torve.domain.stats

import com.torve.domain.model.MediaType

const val WATCHED_THRESHOLD_PERCENT = 0.85
const val ABANDONED_MIN_WATCH_MS = 2 * 60 * 1000L
const val ABANDONED_MIN_PROGRESS_PERCENT = 0.05

enum class WatchSessionStatus {
    STARTED,
    PARTIAL,
    COMPLETED,
    MANUAL_COMPLETED,
    IMPORTED_COMPLETED,
    ABANDONED,
}

enum class WatchSessionSource {
    TORVE_PLAYER,
    TRAKT,
    SIMKL,
    MANUAL,
    MIGRATED_HISTORY,
}

enum class RuntimeConfidence {
    MEASURED,
    ESTIMATED,
    UNKNOWN,
}

enum class WatchStatsAdvancedSection {
    GENRE,
    YEAR,
    DECADE,
    RATING,
    ACTOR,
    DIRECTOR,
    STUDIO,
    NETWORK,
    PROVIDER,
    CERTIFICATION,
}

enum class WatchStatsRatingBand(val label: String) {
    EXCELLENT("8+"),
    GOOD("7-7.9"),
    OK("6-6.9"),
    LOW("Under 6"),
}

data class WatchSession(
    val id: String,
    val userId: String,
    val mediaId: String,
    val mediaType: MediaType,
    val title: String,
    val showId: String? = null,
    val showTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val source: WatchSessionSource,
    val status: WatchSessionStatus,
    val durationMs: Long? = null,
    val maxPositionMs: Long = 0L,
    val countedWatchMs: Long = 0L,
    val completionPercent: Double = 0.0,
    val watchedThresholdPercent: Double = WATCHED_THRESHOLD_PERCENT,
    val runtimeConfidence: RuntimeConfidence = RuntimeConfidence.UNKNOWN,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isEpisode: Boolean
        get() = mediaType == MediaType.SERIES && seasonNumber != null && episodeNumber != null
}

data class WatchStatsSummary(
    val totalWatchMs: Long = 0L,
    val measuredWatchMs: Long = 0L,
    val estimatedWatchMs: Long = 0L,
    val partialWatchMs: Long = 0L,
    val completedMovies: Int = 0,
    val completedEpisodes: Int = 0,
    val startedCount: Int = 0,
    val partialCount: Int = 0,
    val abandonedCount: Int = 0,
    val manualCompletedCount: Int = 0,
    val importedCompletedCount: Int = 0,
    val sourceBreakdown: List<WatchStatsSourceBreakdown> = emptyList(),
    val runtimeConfidenceBreakdown: List<WatchStatsRuntimeConfidenceBreakdown> = emptyList(),
    val recentActivity: List<WatchSession> = emptyList(),
    val hasLegacyUnknownRuntime: Boolean = false,
    val advanced: WatchStatsAdvancedSummary = WatchStatsAdvancedSummary(),
)

data class WatchStatsSourceBreakdown(
    val source: WatchSessionSource,
    val sessionCount: Int,
    val countedWatchMs: Long,
)

data class WatchStatsRuntimeConfidenceBreakdown(
    val confidence: RuntimeConfidence,
    val sessionCount: Int,
    val countedWatchMs: Long,
)

data class WatchStatsMetadata(
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val providers: List<String> = emptyList(),
    val certification: String? = null,
    val ratingImdb: Double? = null,
    val ratingRt: Double? = null,
    val ratingTmdb: Double? = null,
) {
    val hasAnyMetadata: Boolean
        get() = year != null ||
            genres.isNotEmpty() ||
            actors.isNotEmpty() ||
            directors.isNotEmpty() ||
            studios.isNotEmpty() ||
            networks.isNotEmpty() ||
            providers.isNotEmpty() ||
            !certification.isNullOrBlank() ||
            ratingImdb != null ||
            ratingRt != null ||
            ratingTmdb != null
}

data class WatchStatsAdvancedAvailability(
    val hasGenres: Boolean = false,
    val hasYears: Boolean = false,
    val hasRatings: Boolean = false,
    val hasActors: Boolean = false,
    val hasDirectors: Boolean = false,
    val hasStudios: Boolean = false,
    val hasNetworks: Boolean = false,
    val hasProviders: Boolean = false,
    val hasCertifications: Boolean = false,
) {
    val hasAnyMetadata: Boolean
        get() = hasGenres ||
            hasYears ||
            hasRatings ||
            hasActors ||
            hasDirectors ||
            hasStudios ||
            hasNetworks ||
            hasProviders ||
            hasCertifications
}

data class WatchStatsAdvancedSummary(
    val availability: WatchStatsAdvancedAvailability = WatchStatsAdvancedAvailability(),
    val genreGroups: List<WatchStatsAdvancedGroup> = emptyList(),
    val yearGroups: List<WatchStatsAdvancedGroup> = emptyList(),
    val decadeGroups: List<WatchStatsAdvancedGroup> = emptyList(),
    val ratingDistribution: List<WatchStatsRatingGroup> = emptyList(),
    val actorGroups: List<WatchStatsAdvancedGroup> = emptyList(),
    val directorGroups: List<WatchStatsAdvancedGroup> = emptyList(),
    val studioGroups: List<WatchStatsAdvancedGroup> = emptyList(),
    val networkGroups: List<WatchStatsAdvancedGroup> = emptyList(),
    val providerGroups: List<WatchStatsAdvancedGroup> = emptyList(),
    val certificationGroups: List<WatchStatsAdvancedGroup> = emptyList(),
)

data class WatchStatsAdvancedGroup(
    val section: WatchStatsAdvancedSection,
    val key: String,
    val label: String,
    val sessionCount: Int,
    val titleCount: Int,
    val completedCount: Int,
    val partialCount: Int,
    val attributedWatchMs: Long,
    val recentActivity: List<WatchSession> = emptyList(),
)

data class WatchStatsRatingGroup(
    val band: WatchStatsRatingBand,
    val sessionCount: Int,
    val titleCount: Int,
    val attributedWatchMs: Long,
    val recentActivity: List<WatchSession> = emptyList(),
)
