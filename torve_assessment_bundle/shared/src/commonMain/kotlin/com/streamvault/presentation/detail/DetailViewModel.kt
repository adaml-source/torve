package com.streamvault.presentation.detail

import com.streamvault.data.addon.ParsedStream
import com.streamvault.data.addon.StreamSelector
import com.streamvault.data.trakt.api.TraktAuthorizedApi
import com.streamvault.data.trakt.TraktHistoryBody
import com.streamvault.data.trakt.TraktHistoryMovie
import com.streamvault.data.trakt.TraktHistoryShow
import com.streamvault.data.trakt.TraktIds
import com.streamvault.data.trakt.TraktRemoveHistoryBody
import com.streamvault.data.trakt.repo.TraktSyncRepository
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.DeviceCodecCaps
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.StreamPreferences
import com.streamvault.domain.model.StreamQuality
import com.streamvault.domain.model.WatchHistoryEntry
import com.streamvault.domain.integrations.LibraryOverlayService
import com.streamvault.domain.repository.AddonRepository
import com.streamvault.domain.repository.AvailabilityRepository
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.StreamRepository
import com.streamvault.domain.repository.WatchHistoryRepository
import com.streamvault.domain.repository.WatchProgressRepository
import com.streamvault.data.mdblist.MdbListApi
import com.streamvault.data.mdblist.RatingsEnricher
import com.streamvault.domain.integrations.IntegrationSecretKey
import com.streamvault.domain.integrations.IntegrationSecretStore
import com.streamvault.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

class DetailViewModel(
    private val metadataRepo: MetadataRepository,
    private val streamRepo: StreamRepository,
    private val watchProgressRepo: WatchProgressRepository,
    private val traktApi: TraktAuthorizedApi,
    private val traktSyncRepo: TraktSyncRepository,
    private val addonRepo: AddonRepository,
    private val watchHistoryRepo: WatchHistoryRepository,
    private val availabilityRepo: AvailabilityRepository,
    private val prefsRepo: PreferencesRepository,
    private val libraryOverlayService: LibraryOverlayService,
    private val streamSelector: StreamSelector,
    private val ratingsEnricher: RatingsEnricher,
    private val integrationSecretStore: IntegrationSecretStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    // Injected by the UI layer (Android Compose / iOS) since SettingsViewModel is a singleton
    private var settingsProvider: (() -> SettingsViewModel)? = null

    /** Device codec capabilities — set by the platform layer at init time. */
    private var deviceCodecCaps: DeviceCodecCaps = DeviceCodecCaps.SAFE_BASELINE

    fun setSettingsProvider(provider: () -> SettingsViewModel) {
        settingsProvider = provider
    }

    fun setDeviceCodecCaps(caps: DeviceCodecCaps) {
        deviceCodecCaps = caps
    }

    fun loadDetail(type: String, id: Int) {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val item = metadataRepo.getDetail(type, id)
                _state.update { it.copy(mediaItem = item, isLoading = false) }

                // Load similar items
                val similar = metadataRepo.getSimilar(type, id)
                _state.update { it.copy(similar = similar) }

                // Load watch progress
                if (item != null) {
                    val progress = watchProgressRepo.getProgress(item.id)
                    val rating = item.tmdbId?.let { tmdbId ->
                        runCatching { traktSyncRepo.getUserRating(tmdbId, item.type) }.getOrNull()
                    }
                    _state.update { it.copy(watchProgress = progress, userRating = rating) }
                }

                // Auto-load first season for TV shows
                if (type == "tv" && item.seasons.isNotEmpty()) {
                    val firstReal = item.seasons.firstOrNull { it.seasonNumber > 0 }
                    if (firstReal != null) {
                        loadSeasonDetail(id, firstReal.seasonNumber)
                    }
                    loadWatchedEpisodes()
                }
                loadAvailability(item)
                loadLibraryStatus(item)
                enrichRatings(item)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    private fun loadAvailability(item: com.streamvault.domain.model.MediaItem) {
        val tmdbId = item.tmdbId ?: return
        scope.launch {
            _state.update { it.copy(isLoadingAvailability = true, availabilityError = null) }
            try {
                val region = (prefsRepo.getString("content_region_code") ?: "US").ifBlank { "US" }
                val result = availabilityRepo.getAvailability(
                    tmdbId = tmdbId,
                    mediaType = item.type,
                    region = region,
                )
                _state.update {
                    it.copy(
                        availability = result,
                        isLoadingAvailability = false,
                        availabilityError = null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoadingAvailability = false,
                        availabilityError = e.message ?: "Failed to load availability",
                    )
                }
            }
        }
    }

    private fun enrichRatings(item: com.streamvault.domain.model.MediaItem) {
        scope.launch {
            val apiKey = try {
                integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            } catch (_: Exception) { MdbListApi.DEFAULT_API_KEY }
            if (apiKey.isBlank()) return@launch
            try {
                val enriched = ratingsEnricher.enrichSingle(item, apiKey)
                _state.update { it.copy(mediaItem = enriched) }
            } catch (_: Exception) { }
        }
    }

    private fun loadLibraryStatus(item: com.streamvault.domain.model.MediaItem) {
        val tmdbId = item.tmdbId ?: return
        scope.launch {
            val inLibrary = runCatching {
                libraryOverlayService.isInLibrary(tmdbId, item.type)
            }.getOrDefault(false)
            _state.update { it.copy(isInLibrary = inLibrary) }
        }
    }

    fun loadSeasonDetail(tvId: Int, seasonNumber: Int) {
        scope.launch {
            _state.update { it.copy(selectedSeason = seasonNumber, isLoadingSeasonDetail = true) }
            try {
                val season = metadataRepo.getSeasonDetail(tvId, seasonNumber)
                _state.update { it.copy(seasonDetail = season, isLoadingSeasonDetail = false) }
            } catch (_: Exception) {
                _state.update { it.copy(isLoadingSeasonDetail = false) }
            }
        }
    }

    /**
     * For TV shows: auto-start the next unwatched or in-progress episode.
     * Priority: 1) resume partially-watched episode, 2) first unwatched episode, 3) S01E01.
     * For movies: delegates straight to fetchStreams().
     */
    fun playNextEpisode() {
        val item = _state.value.mediaItem ?: return
        if (item.type != MediaType.SERIES) {
            fetchStreams()
            return
        }

        scope.launch {
            // 1. Check for a partially-watched episode (2%–90% progress)
            val allProgress = try { watchProgressRepo.getAllProgress() } catch (_: Exception) { emptyList() }
            val inProgress = allProgress
                .filter { it.seasonNumber != null && it.episodeNumber != null }
                .filter { it.showTitle == item.title || it.mediaId == item.id.toString() }
                .filter { it.progressPercent > 0.02f && it.progressPercent < 0.9f }
                .maxByOrNull { it.updatedAt }

            if (inProgress != null) {
                fetchStreams(season = inProgress.seasonNumber, episode = inProgress.episodeNumber)
                return@launch
            }

            // 2. Find next unwatched episode across all seasons
            val watched = _state.value.watchedEpisodes
            val seasons = item.seasons
                .filter { it.seasonNumber > 0 }
                .sortedBy { it.seasonNumber }

            for (season in seasons) {
                for (ep in 1..season.episodeCount) {
                    if ("s${season.seasonNumber}e$ep" !in watched) {
                        fetchStreams(season = season.seasonNumber, episode = ep)
                        return@launch
                    }
                }
            }

            // 3. All episodes watched — restart from S01E01
            val firstSeason = seasons.firstOrNull()
            if (firstSeason != null) {
                fetchStreams(season = firstSeason.seasonNumber, episode = 1)
            }
        }
    }

    fun fetchStreams(season: Int? = null, episode: Int? = null) {
        val item = _state.value.mediaItem ?: return
        val imdbId = item.imdbId ?: return

        scope.launch {
            _state.update {
                it.copy(
                    isLoadingStreams = true,
                    streamsError = null,
                    streams = emptyList(),
                    streamContextSeason = season,
                    streamContextEpisode = episode,
                    autoPlayStream = null,
                    autoPlayMessage = null,
                    autoPlayFailed = false,
                    fallbackAttempt = 0,
                )
            }
            try {
                val settings = settingsProvider?.invoke()
                val preferences = settings?.buildStreamPreferences() ?: StreamPreferences()
                val addons = try { addonRepo.getInstalledAddons() } catch (_: Exception) { emptyList() }
                val debridAccounts = settings?.getDebridAccounts() ?: emptyMap()

                val streams = streamRepo.fetchStreams(
                    type = item.type,
                    imdbId = imdbId,
                    season = season,
                    episode = episode,
                    addons = addons,
                    debridAccounts = debridAccounts,
                    preferences = preferences,
                )

                _state.update {
                    it.copy(
                        streams = streams,
                        isLoadingStreams = false,
                    )
                }

                if (streams.isEmpty()) {
                    _state.update { it.copy(streamsError = "No streams found") }
                    return@launch
                }

                if (preferences.autoPlayEnabled) {
                    // Pre-filter streams by device codec caps to avoid codec errors.
                    // On capable devices this is a no-op (HEVC/VP9/AV1 all pass).
                    // On weak HEVC devices (emulators, low-end) this filters out
                    // unsupported HEVC streams BEFORE they reach the player.
                    val playable = streams.filter { s ->
                        deviceCodecCaps.canDecode(s.codec, title = s.title)
                    }.ifEmpty { streams } // fallback to unfiltered if all rejected

                    val best = playable.first()
                    val info = buildAutoPlayMessage(best)
                    _state.update {
                        it.copy(
                            autoPlayStream = best,
                            autoPlayMessage = info,
                        )
                    }
                    autoResolveStream(playable, 0, preferences)
                } else {
                    _state.update { it.copy(showStreamPicker = true) }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoadingStreams = false, streamsError = e.message ?: "Failed to fetch streams")
                }
            }
        }
    }

    private suspend fun autoResolveStream(
        streams: List<ParsedStream>,
        attemptIndex: Int,
        preferences: StreamPreferences,
    ) {
        val maxAttempts = min(preferences.maxFallbackAttempts, streams.size)
        if (attemptIndex >= maxAttempts) {
            _state.update {
                it.copy(
                    autoPlayFailed = true,
                    autoPlayMessage = null,
                    isResolving = false,
                    showStreamPicker = true,
                    streamsError = if (_state.value.streams.isNotEmpty()) "Auto-play failed — pick a stream manually" else null,
                )
            }
            return
        }

        val stream = streams[attemptIndex]
        val settings = settingsProvider?.invoke()
        val provider = settings?.getDebridProvider() ?: DebridServiceType.REAL_DEBRID
        val apiKey = settings?.getDebridApiKey() ?: ""

        if (apiKey.isBlank()) {
            _state.update {
                it.copy(
                    autoPlayFailed = true,
                    autoPlayMessage = null,
                    isResolving = false,
                    streamsError = "No cloud service configured",
                )
            }
            return
        }

        _state.update {
            it.copy(
                isResolving = true,
                resolveError = null,
                autoPlayStream = stream,
                fallbackAttempt = attemptIndex,
            )
        }

        try {
            // Cached streams resolve in seconds; 30s is generous.
            val resolved = withTimeoutOrNull(30_000L) {
                streamRepo.resolveStream(stream, provider, apiKey)
            }
            if (resolved != null) {
                _state.update {
                    it.copy(
                        resolvedStream = resolved,
                        isResolving = false,
                        showStreamPicker = false,
                        autoPlayMessage = buildAutoPlayMessage(stream),
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        autoPlayMessage = "Stream timed out, trying next...",
                        fallbackAttempt = attemptIndex + 1,
                    )
                }
                autoResolveStream(streams, attemptIndex + 1, preferences)
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    autoPlayMessage = "Stream failed, trying next...",
                    fallbackAttempt = attemptIndex + 1,
                )
            }
            autoResolveStream(streams, attemptIndex + 1, preferences)
        }
    }

    private fun buildAutoPlayMessage(stream: ParsedStream): String {
        val parts = mutableListOf<String>()
        parts.add(stream.quality)
        if (!stream.codec.isNullOrBlank()) parts.add(stream.codec)
        if (stream.hdr != null) parts.add(stream.hdr)
        if (stream.size != null) parts.add(stream.size)
        return "Playing: ${parts.joinToString(" · ")}"
    }

    fun resolveStream(stream: ParsedStream, provider: DebridServiceType, apiKey: String) {
        scope.launch {
            _state.update { it.copy(isResolving = true, resolveError = null) }
            try {
                val resolved = withTimeoutOrNull(30_000L) {
                    streamRepo.resolveStream(stream, provider, apiKey)
                }
                if (resolved != null) {
                    _state.update {
                        it.copy(
                            resolvedStream = resolved,
                            isResolving = false,
                            showStreamPicker = false,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(isResolving = false, resolveError = "Stream resolution timed out — try another stream")
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isResolving = false, resolveError = e.message ?: "Failed to resolve stream")
                }
            }
        }
    }

    /**
     * Called by the player layer when a codec error occurs at runtime.
     * Silently falls back to the next best stream — never shows errors to the user.
     * Returns a non-null string if a fallback was found (player should switch).
     */
    fun onCodecError(failedStream: ParsedStream): String? {
        val streams = _state.value.streams
        if (streams.isEmpty()) return null

        // Find the next stream after the failed one in score order
        val fallback = streams.firstOrNull { it != failedStream }
            ?: return null

        val settings = settingsProvider?.invoke()
        val provider = settings?.getDebridProvider() ?: DebridServiceType.REAL_DEBRID
        val apiKey = settings?.getDebridApiKey() ?: ""

        scope.launch {
            _state.update {
                it.copy(
                    autoPlayStream = fallback,
                    isResolving = true,
                )
            }
            try {
                val resolved = withTimeoutOrNull(30_000L) {
                    streamRepo.resolveStream(fallback, provider, apiKey)
                }
                if (resolved != null) {
                    _state.update {
                        it.copy(
                            resolvedStream = resolved,
                            isResolving = false,
                            autoPlayMessage = buildAutoPlayMessage(fallback),
                        )
                    }
                } else {
                    // Silently give up — show stream picker as last resort
                    _state.update {
                        it.copy(isResolving = false, showStreamPicker = true)
                    }
                }
            } catch (_: Exception) {
                _state.update {
                    it.copy(isResolving = false, showStreamPicker = true)
                }
            }
        }

        return "switching"
    }

    fun toggleStreamPicker() {
        _state.update { it.copy(showStreamPicker = !it.showStreamPicker) }
    }

    fun dismissStreamPicker() {
        _state.update { it.copy(showStreamPicker = false) }
    }

    fun clearResolvedStream() {
        _state.update { it.copy(resolvedStream = null, autoPlayMessage = null) }
    }

    fun showManualPicker() {
        _state.update {
            it.copy(
                autoPlayFailed = false,
                autoPlayMessage = null,
                showStreamPicker = true,
            )
        }
    }

    fun markWatched() {
        val item = _state.value.mediaItem ?: return
        scope.launch {
            _state.update { it.copy(isMarkedWatched = true) }
            try {
                val tmdbId = item.tmdbId ?: return@launch
                val ids = TraktIds(tmdb = tmdbId)
                if (item.type == MediaType.MOVIE) {
                    traktApi.addToHistory(TraktHistoryBody(movies = listOf(TraktHistoryMovie(ids))))
                } else {
                    traktApi.addToHistory(TraktHistoryBody(shows = listOf(TraktHistoryShow(ids))))
                }
            } catch (_: Exception) {
                val tmdbId = item.tmdbId ?: return@launch
                traktSyncRepo.enqueueHistoryAdd(tmdbId, item.type, item.imdbId)
            }
        }
    }

    fun markUnwatched() {
        val item = _state.value.mediaItem ?: return
        scope.launch {
            _state.update { it.copy(isMarkedWatched = false) }
            try {
                val tmdbId = item.tmdbId ?: return@launch
                val ids = TraktIds(tmdb = tmdbId)
                if (item.type == MediaType.MOVIE) {
                    traktApi.removeFromHistory(TraktRemoveHistoryBody(movies = listOf(TraktHistoryMovie(ids))))
                } else {
                    traktApi.removeFromHistory(TraktRemoveHistoryBody(shows = listOf(TraktHistoryShow(ids))))
                }
            } catch (_: Exception) {
                val tmdbId = item.tmdbId ?: return@launch
                traktSyncRepo.enqueueHistoryRemove(tmdbId, item.type, item.imdbId)
            }
        }
    }

    fun setUserRating(rating: Int?) {
        val item = _state.value.mediaItem ?: return
        val tmdbId = item.tmdbId ?: return
        scope.launch {
            _state.update { it.copy(userRating = rating?.coerceIn(1, 10)) }
            traktSyncRepo.setUserRating(
                tmdbId = tmdbId,
                mediaType = item.type,
                imdbId = item.imdbId,
                rating = rating,
            )
        }
    }

    fun markSeasonWatched(seasonNumber: Int) {
        val item = _state.value.mediaItem ?: return
        val seasonDetail = _state.value.seasonDetail
        val episodeCount = seasonDetail?.episodes?.size
            ?: item.seasons.find { it.seasonNumber == seasonNumber }?.episodeCount
            ?: return

        scope.launch {
            try {
                val now = Clock.System.now().toEpochMilliseconds()
                for (ep in 1..episodeCount) {
                    val entry = WatchHistoryEntry(
                        id = "${item.id}_s${seasonNumber}e$ep",
                        mediaId = item.id,
                        mediaType = MediaType.SERIES.name,
                        title = seasonDetail?.episodes?.getOrNull(ep - 1)?.name ?: "Episode $ep",
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        watchedAt = now,
                        durationWatchedMs = 0,
                        seasonNumber = seasonNumber,
                        episodeNumber = ep,
                        showTitle = item.title,
                    )
                    watchHistoryRepo.record(entry)
                }
                // Update watched episodes set
                val newWatched = _state.value.watchedEpisodes.toMutableSet()
                for (ep in 1..episodeCount) {
                    newWatched.add("s${seasonNumber}e$ep")
                }
                _state.update { it.copy(watchedEpisodes = newWatched) }
            } catch (_: Exception) { }
        }
    }

    fun loadWatchedEpisodes() {
        val item = _state.value.mediaItem ?: return
        scope.launch {
            try {
                val history = watchHistoryRepo.getAll()
                val watched = history
                    .filter { it.mediaId == item.id && it.seasonNumber != null && it.episodeNumber != null }
                    .map { "s${it.seasonNumber}e${it.episodeNumber}" }
                    .toSet()
                _state.update { it.copy(watchedEpisodes = watched) }
            } catch (_: Exception) { }
        }
    }
}
