package com.streamvault.presentation.detail

import com.streamvault.data.addon.ParsedStream
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.ResolvedStream
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.WatchProgress

data class DetailUiState(
    val isLoading: Boolean = true,
    val mediaItem: MediaItem? = null,
    val similar: List<MediaItem> = emptyList(),
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
    // Season/Episode details
    val selectedSeason: Int = 1,
    val seasonDetail: Season? = null,
    val isLoadingSeasonDetail: Boolean = false,
    // Track what we're fetching streams for (for download labeling)
    val streamContextSeason: Int? = null,
    val streamContextEpisode: Int? = null,
    // Auto-play
    val autoPlayStream: ParsedStream? = null,
    val autoPlayMessage: String? = null,
    val fallbackAttempt: Int = 0,
    val autoPlayFailed: Boolean = false,
)
