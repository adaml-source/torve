package com.streamvault.presentation.detail

import com.streamvault.data.addon.ParsedStream
import com.streamvault.data.trakt.TraktClient
import com.streamvault.data.trakt.TraktHistoryBody
import com.streamvault.data.trakt.TraktHistoryMovie
import com.streamvault.data.trakt.TraktHistoryShow
import com.streamvault.data.trakt.TraktIds
import com.streamvault.data.trakt.TraktRemoveHistoryBody
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.StreamPreferences
import com.streamvault.domain.model.WatchHistoryEntry
import com.streamvault.domain.repository.AddonRepository
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.StreamRepository
import com.streamvault.domain.repository.WatchHistoryRepository
import com.streamvault.domain.repository.WatchProgressRepository
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
import kotlin.math.min

class DetailViewModel(
    private val metadataRepo: MetadataRepository,
    private val streamRepo: StreamRepository,
    private val watchProgressRepo: WatchProgressRepository,
    private val traktClient: TraktClient,
    private val prefsRepo: PreferencesRepository,
    private val addonRepo: AddonRepository,
    private val watchHistoryRepo: WatchHistoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    // Injected by the UI layer (Android Compose / iOS) since SettingsViewModel is a singleton
    private var settingsProvider: (() -> SettingsViewModel)? = null

    fun setSettingsProvider(provider: () -> SettingsViewModel) {
        settingsProvider = provider
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
                    _state.update { it.copy(watchProgress = progress) }
                }

                // Auto-load first season for TV shows
                if (type == "tv" && item.seasons.isNotEmpty()) {
                    val firstReal = item.seasons.firstOrNull { it.seasonNumber > 0 }
                    if (firstReal != null) {
                        loadSeasonDetail(id, firstReal.seasonNumber)
                    }
                    loadWatchedEpisodes()
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
            }
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
                    val best = streams.first()
                    val info = buildAutoPlayMessage(best)
                    _state.update {
                        it.copy(
                            autoPlayStream = best,
                            autoPlayMessage = info,
                        )
                    }
                    autoResolveStream(streams, 0, preferences)
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
            val resolved = streamRepo.resolveStream(stream, provider, apiKey)
            _state.update {
                it.copy(
                    resolvedStream = resolved,
                    isResolving = false,
                    showStreamPicker = false,
                    autoPlayMessage = buildAutoPlayMessage(stream),
                )
            }
        } catch (_: Exception) {
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
                val resolved = streamRepo.resolveStream(stream, provider, apiKey)
                _state.update {
                    it.copy(
                        resolvedStream = resolved,
                        isResolving = false,
                        showStreamPicker = false,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isResolving = false, resolveError = e.message ?: "Failed to resolve stream")
                }
            }
        }
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
            try {
                val token = prefsRepo.getString("trakt_access_token") ?: return@launch
                val tmdbId = item.tmdbId ?: return@launch
                val ids = TraktIds(tmdb = tmdbId)
                if (item.type == MediaType.MOVIE) {
                    traktClient.addToHistory(token, TraktHistoryBody(movies = listOf(TraktHistoryMovie(ids))))
                } else {
                    traktClient.addToHistory(token, TraktHistoryBody(shows = listOf(TraktHistoryShow(ids))))
                }
                _state.update { it.copy(isMarkedWatched = true) }
            } catch (_: Exception) { }
        }
    }

    fun markUnwatched() {
        val item = _state.value.mediaItem ?: return
        scope.launch {
            try {
                val token = prefsRepo.getString("trakt_access_token") ?: return@launch
                val tmdbId = item.tmdbId ?: return@launch
                val ids = TraktIds(tmdb = tmdbId)
                if (item.type == MediaType.MOVIE) {
                    traktClient.removeFromHistory(token, TraktRemoveHistoryBody(movies = listOf(TraktHistoryMovie(ids))))
                } else {
                    traktClient.removeFromHistory(token, TraktRemoveHistoryBody(shows = listOf(TraktHistoryShow(ids))))
                }
                _state.update { it.copy(isMarkedWatched = false) }
            } catch (_: Exception) { }
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
