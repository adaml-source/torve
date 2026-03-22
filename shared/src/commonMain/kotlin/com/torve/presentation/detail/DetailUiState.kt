package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import com.torve.domain.model.AvailabilityResult
import com.torve.domain.model.Episode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.Season
import com.torve.domain.model.WatchProgress

/** Canonical key for episode tracking — single source of truth. */
fun episodeKey(season: Int, episode: Int): String = "s${season}e${episode}"

enum class NextEpisodeMode {
    RESUME_IN_PROGRESS,
    PLAY_FIRST_UNWATCHED,
    PLAY_FROM_START,
}

data class NextEpisodeInfo(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val progressPercent: Float? = null,
    val mode: NextEpisodeMode,
) {
    val label: String
        get() = when (mode) {
            NextEpisodeMode.RESUME_IN_PROGRESS -> {
                val pct = progressPercent?.let { " (${(it * 100).toInt()}%)" } ?: ""
                "Resume S${season} E${episode}$pct"
            }
            NextEpisodeMode.PLAY_FIRST_UNWATCHED -> "Play S${season} E${episode}"
            NextEpisodeMode.PLAY_FROM_START -> "Play S${season} E${episode}"
        }
}

data class DetailUiState(
    val isLoading: Boolean = true,
    val mediaItem: MediaItem? = null,
    val similar: List<MediaItem> = emptyList(),
    val availability: AvailabilityResult? = null,
    val isLoadingAvailability: Boolean = false,
    val availabilityError: String? = null,
    val error: String? = null,
    // Streams
    val streams: List<ParsedStream> = emptyList(),
    val isLoadingStreams: Boolean = false,
    val streamsError: String? = null,
    // Resolved playback URL
    val resolvedStream: ResolvedStream? = null,
    val isResolving: Boolean = false,
    val resolveError: String? = null,
    // Watch progress
    val watchProgress: WatchProgress? = null,
    // Stream picker visibility
    val showStreamPicker: Boolean = false,
    // Watched status
    val isMarkedWatched: Boolean = false,
    // Trakt user rating (1..10)
    val userRating: Int? = null,
    // Season/Episode details
    val selectedSeason: Int = 1,
    val seasonDetail: Season? = null,
    val isLoadingSeasonDetail: Boolean = false,
    // Episode tracking: keys like "s1e1", "s1e2", etc.
    val watchedEpisodes: Set<String> = emptySet(),
    val isInLibrary: Boolean = false,
    // Track what we're fetching streams for (for download labeling)
    val streamContextSeason: Int? = null,
    val streamContextEpisode: Int? = null,
    // Next episode resolution — drives play button label and action
    val nextEpisode: NextEpisodeInfo? = null,
    // Auto-play
    val autoPlayStream: ParsedStream? = null,
    val autoPlayMessage: String? = null,
    val fallbackAttempt: Int = 0,
    val autoPlayFailed: Boolean = false,
    val kodiSendResult: String? = null,
) {
    /** Play button label — derived from nextEpisode for TV shows, static for movies. */
    val primaryPlayLabel: String
        get() = when {
            mediaItem?.type == com.torve.domain.model.MediaType.SERIES && nextEpisode != null -> nextEpisode.label
            watchProgress != null && watchProgress.progressPercent > 0.02f && watchProgress.progressPercent < 0.9f -> "Resume"
            else -> "Play"
        }
}
