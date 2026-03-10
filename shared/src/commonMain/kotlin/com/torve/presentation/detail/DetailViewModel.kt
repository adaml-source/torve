package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import com.torve.data.addon.StreamSelector
import com.torve.data.kodi.KodiClient
import com.torve.data.kodi.KodiHost
import com.torve.data.simkl.SimklClient
import com.torve.data.simkl.SimklIds
import com.torve.data.simkl.SimklSyncBody
import com.torve.data.simkl.SimklSyncItem
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.TraktHistoryBody
import com.torve.data.trakt.TraktHistoryMovie
import com.torve.data.trakt.TraktHistoryShow
import com.torve.data.trakt.TraktIds
import com.torve.data.trakt.TraktRemoveHistoryBody
import com.torve.data.trakt.repo.TraktSyncRepository
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.DeviceCodecCaps
import com.torve.domain.model.MediaType
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.AvailabilityRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.StreamRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.presentation.settings.SettingsViewModel
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
    private val kodiClient: KodiClient,
    private val simklClient: SimklClient,
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

    private fun loadAvailability(item: com.torve.domain.model.MediaItem) {
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

    private fun enrichRatings(item: com.torve.domain.model.MediaItem) {
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

    private fun loadLibraryStatus(item: com.torve.domain.model.MediaItem) {
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

    fun fetchStreams(season: Int? = null, episode: Int? = null, forceManualPick: Boolean = false) {
        val item = _state.value.mediaItem ?: return
        val imdbId = item.imdbId
        if (imdbId == null) {
            _state.update { it.copy(streamsError = "No IMDB ID — cannot fetch streams for this title") }
            return
        }

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

                if (preferences.autoPlayEnabled && !forceManualPick) {
                    val playable = streamSelector.rankPlayableVariants(
                        streams = streams,
                        preferences = preferences,
                        deviceCaps = deviceCodecCaps,
                    )

                    if (playable.isEmpty()) {
                        // No codec-compatible streams — show picker so user can choose
                        _state.update {
                            it.copy(
                                showStreamPicker = true,
                                streamsError = "No compatible streams found for this device — pick manually or try a different quality",
                            )
                        }
                    } else {
                        val best = playable.first()
                        val info = buildAutoPlayMessage(best)
                        _state.update {
                            it.copy(
                                autoPlayStream = best,
                                autoPlayMessage = info,
                            )
                        }
                        autoResolveStream(playable, 0, preferences)
                    }
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
        val hostKey = com.torve.data.addon.StreamRuntimeTelemetry.keyForStream(stream)
        com.torve.data.addon.StreamRuntimeTelemetry.recordPlayAttempt(hostKey)
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
            println("TORVE_AUTORESOLVE: attempt=$attemptIndex hash=${stream.infoHash} provider=$provider keyLen=${apiKey.length}")
            val resolved = withTimeoutOrNull(90_000L) {
                streamRepo.resolveStream(stream, provider, apiKey)
            }
            if (resolved != null) {
                println("TORVE_AUTORESOLVE: Success url=${resolved.url?.take(80)}")
                com.torve.data.addon.StreamRuntimeTelemetry.recordStartupSuccess(hostKey, 0L)
                _state.update {
                    it.copy(
                        resolvedStream = resolved,
                        isResolving = false,
                        showStreamPicker = false,
                        autoPlayMessage = if (attemptIndex > 0) {
                            "Switched to a more stable source"
                        } else {
                            buildAutoPlayMessage(stream)
                        },
                    )
                }
            } else {
                com.torve.data.addon.StreamRuntimeTelemetry.recordStartupTimeout(hostKey, 90_000L)
                _state.update {
                    it.copy(
                        autoPlayMessage = "Stream timed out, trying next...",
                        fallbackAttempt = attemptIndex + 1,
                    )
                }
                autoResolveStream(streams, attemptIndex + 1, preferences)
            }
        } catch (e: Exception) {
            com.torve.data.addon.StreamRuntimeTelemetry.recordFatalError(hostKey)
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
            println("TORVE_RESOLVE: Starting resolve hash=${stream.infoHash} url=${stream.directUrl} provider=$provider keyLen=${apiKey.length}")
            try {
                val resolved = withTimeoutOrNull(90_000L) {
                    streamRepo.resolveStream(stream, provider, apiKey)
                }
                if (resolved != null) {
                    println("TORVE_RESOLVE: Success url=${resolved.url?.take(80)}")
                    _state.update {
                        it.copy(
                            resolvedStream = resolved,
                            isResolving = false,
                            showStreamPicker = false,
                        )
                    }
                } else {
                    println("TORVE_RESOLVE: Timed out after 90s")
                    _state.update {
                        it.copy(isResolving = false, resolveError = "Stream resolution timed out — try another stream")
                    }
                }
            } catch (e: Exception) {
                println("TORVE_RESOLVE: Exception ${e::class.simpleName}: ${e.message}")
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

        val preferences = settingsProvider?.invoke()?.buildStreamPreferences() ?: StreamPreferences()
        val fallback = streamSelector.selectFallbackAfterCodecError(
            failedStream = failedStream,
            allStreams = streams,
            preferences = preferences,
            deviceCaps = deviceCodecCaps,
        ) ?: return null

        val settings = settingsProvider?.invoke()
        val provider = settings?.getDebridProvider() ?: DebridServiceType.REAL_DEBRID
        val apiKey = settings?.getDebridApiKey() ?: ""
        val hostKey = com.torve.data.addon.StreamRuntimeTelemetry.keyForStream(fallback)
        com.torve.data.addon.StreamRuntimeTelemetry.recordPlayAttempt(hostKey)

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
                            autoPlayMessage = "Switched to a more stable source",
                        )
                    }
                } else {
                    com.torve.data.addon.StreamRuntimeTelemetry.recordStartupTimeout(hostKey, 30_000L)
                    // Silently give up — show stream picker as last resort
                    _state.update {
                        it.copy(isResolving = false, showStreamPicker = true)
                    }
                }
            } catch (_: Exception) {
                com.torve.data.addon.StreamRuntimeTelemetry.recordFatalError(hostKey)
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
            val tmdbId = item.tmdbId ?: return@launch
            // Trakt
            try {
                val ids = TraktIds(tmdb = tmdbId)
                if (item.type == MediaType.MOVIE) {
                    traktApi.addToHistory(TraktHistoryBody(movies = listOf(TraktHistoryMovie(ids))))
                } else {
                    traktApi.addToHistory(TraktHistoryBody(shows = listOf(TraktHistoryShow(ids))))
                }
            } catch (_: Exception) {
                traktSyncRepo.enqueueHistoryAdd(tmdbId, item.type, item.imdbId)
            }
            // Simkl
            runCatching {
                val token = integrationSecretStore.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)
                if (!token.isNullOrBlank()) {
                    val simklIds = SimklIds(tmdb = tmdbId, imdb = item.imdbId)
                    val body = if (item.type == MediaType.MOVIE) {
                        SimklSyncBody(movies = listOf(SimklSyncItem(simklIds)))
                    } else {
                        SimklSyncBody(shows = listOf(SimklSyncItem(simklIds)))
                    }
                    simklClient.addToHistory(token, body)
                }
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

    fun playOnKodi(host: KodiHost, url: String) {
        scope.launch {
            _state.update { it.copy(kodiSendResult = null) }
            val success = kodiClient.playUrl(host, url)
            _state.update {
                it.copy(kodiSendResult = if (success) "Sent to ${host.name}" else "Failed to send to ${host.name}")
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
