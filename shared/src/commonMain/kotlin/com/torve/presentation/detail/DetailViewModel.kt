package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import com.torve.data.addon.StreamSelector
import com.torve.data.contentpolicy.ContentPolicyCacheInvalidationCoordinator
import com.torve.data.contentpolicy.ContentPolicyRepository
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
import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentFilterAction
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.ContentSourceType
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.DeviceCodecCaps
import com.torve.domain.model.ContentWarmupTrigger
import com.torve.domain.model.MediaType
import com.torve.domain.model.SensitiveClassification
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.model.StreamFetchPolicy
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
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
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
    private val contentPolicyRepository: ContentPolicyRepository? = null,
    private val contentPolicyFilter: ContentPolicyFilter = ContentPolicyFilter(),
    invalidationCoordinator: ContentPolicyCacheInvalidationCoordinator? = null,
) {
    private data class StreamPresentationResult(
        val ordered: List<ParsedStream>,
        val rankedPlayable: List<ParsedStream>,
    )

    private data class AutoResolveResult(
        val resolved: Boolean,
        val attemptedKeys: Set<String> = emptySet(),
    )

    private enum class AutoResolveFailureBehavior {
        SHOW_PICKER,
        DEFER_TO_FULL_FETCH,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()
    private var warmupJob: Job? = null
    private var currentType: String? = null
    private var currentId: Int? = null

    init {
        if (invalidationCoordinator != null) {
            scope.launch {
                invalidationCoordinator.events.collectLatest {
                    val type = currentType
                    val id = currentId
                    if (type != null && id != null) {
                        _state.value = DetailUiState(isLoading = true)
                        loadDetail(type, id)
                    }
                }
            }
        }
        // Relock hardening: observe content policy state directly. When policy
        // transitions to locked, immediately replace the current item with a stub
        // so that sensitive artwork/metadata disappears without waiting for reload.
        if (contentPolicyRepository != null) {
            scope.launch {
                var wasLocked = contentPolicyRepository.state.value.isLocked
                contentPolicyRepository.state.collectLatest { policy ->
                    val nowLocked = policy.isLocked
                    if (nowLocked && !wasLocked) {
                        val currentItem = _state.value.mediaItem
                        if (currentItem != null && !currentItem.isStubDetail) {
                            val classification = contentPolicyFilter.classify(currentItem, ContentSourceType.TMDB)
                            if (classification != SensitiveClassification.SAFE) {
                                _state.update {
                                    it.copy(mediaItem = contentPolicyFilter.run { currentItem.asStubDetail() }, similar = emptyList())
                                }
                            }
                        }
                    }
                    wasLocked = nowLocked
                }
            }
        }
    }

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
        warmupJob?.cancel()
        currentType = type
        currentId = id
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val rawItem = ratingsEnricher.hydrateFromCache(metadataRepo.getDetail(type, id))
                val policy = currentPolicy()
                val item = when (contentPolicyFilter.decide(
                    policy = policy,
                    context = ContentAccessContext.DETAIL_PAGE,
                    item = rawItem,
                    sourceType = ContentSourceType.TMDB,
                    addonPolicyFlags = null,
                    allowSensitiveBecauseUserReachedSensitiveParent = false,
                ).action) {
                    ContentFilterAction.ALLOW_FULL -> rawItem
                    else -> contentPolicyFilter.run { rawItem.asStubDetail() }
                }
                _state.update { it.copy(mediaItem = item, similar = emptyList(), isLoading = false) }

                if (item.isStubDetail) {
                    return@launch
                }

                // Load similar items
                val similar = ratingsEnricher.hydrateListFromCache(metadataRepo.getSimilar(type, id))
                val allowSensitiveFromParent = contentPolicyFilter.classify(rawItem, ContentSourceType.TMDB) == SensitiveClassification.SENSITIVE &&
                    policy.adultEnabled
                val filteredSimilar = contentPolicyFilter.filterItems(
                    policy = policy,
                    context = ContentAccessContext.SIMILAR_OR_MORE_LIKE_THIS,
                    items = similar,
                    sourceType = ContentSourceType.TMDB,
                    allowSensitiveBecauseUserReachedSensitiveParent = allowSensitiveFromParent,
                ).items
                _state.update { it.copy(similar = filteredSimilar) }

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
                warmupLikelyPlaybackTarget(ContentWarmupTrigger.DETAIL_OPEN)
                loadAvailability(item)
                loadLibraryStatus(item)
                enrichRatings(item)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = com.torve.presentation.error.UserFacingError.CONTENT_LOAD_FAILED.messageKey) }
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
                        availabilityError = com.torve.presentation.error.UserFacingError.CONTENT_LOAD_FAILED.messageKey,
                    )
                }
            }
        }
    }

    private fun enrichRatings(item: com.torve.domain.model.MediaItem) {
        if (item.isStubDetail) return
        scope.launch {
            val apiKey = try {
                integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            } catch (_: Exception) { MdbListApi.DEFAULT_API_KEY }
            if (apiKey.isBlank()) return@launch
            try {
                val enriched = ratingsEnricher.enrichSingle(item, apiKey)
                val filtered = when (contentPolicyFilter.decide(
                    policy = currentPolicy(),
                    context = ContentAccessContext.DETAIL_PAGE,
                    item = enriched,
                    sourceType = ContentSourceType.TMDB,
                    addonPolicyFlags = null,
                    allowSensitiveBecauseUserReachedSensitiveParent = false,
                ).action) {
                    ContentFilterAction.ALLOW_FULL -> enriched
                    else -> contentPolicyFilter.run { enriched.asStubDetail() }
                }
                _state.update { it.copy(mediaItem = filtered) }
            } catch (_: Exception) { }
        }
    }

    private fun currentPolicy(): ContentPolicyState {
        return contentPolicyRepository?.state?.value ?: ContentPolicyState.unrestricted()
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
     * Resolve which episode the play button should target.
     * Priority: 1) resume partially-watched, 2) first unwatched, 3) S01E01.
     * Updates [DetailUiState.nextEpisode] — the single source of truth for both
     * the play button label and the playback target.
     */
    private suspend fun resolveNextEpisode() {
        val item = _state.value.mediaItem ?: return
        if (item.type != MediaType.SERIES) {
            _state.update { it.copy(nextEpisode = null) }
            return
        }

        // 1. Check for a partially-watched episode (2%–90% progress)
        val allProgress = try { watchProgressRepo.getAllProgress() } catch (_: Exception) { emptyList() }
        val inProgress = allProgress
            .filter { it.seasonNumber != null && it.episodeNumber != null }
            .filter { it.showTitle == item.title || it.mediaId == item.id.toString() }
            .filter { it.progressPercent > 0.02f && it.progressPercent < 0.9f }
            .maxByOrNull { it.updatedAt }

        if (inProgress != null) {
            _state.update {
                it.copy(nextEpisode = NextEpisodeInfo(
                    season = inProgress.seasonNumber!!,
                    episode = inProgress.episodeNumber!!,
                    progressPercent = inProgress.progressPercent,
                    mode = NextEpisodeMode.RESUME_IN_PROGRESS,
                ))
            }
            return
        }

        // 2. Find next unwatched episode across all seasons
        val watched = _state.value.watchedEpisodes
        val seasons = item.seasons
            .filter { it.seasonNumber > 0 }
            .sortedBy { it.seasonNumber }

        for (season in seasons) {
            for (ep in 1..season.episodeCount) {
                if (episodeKey(season.seasonNumber, ep) !in watched) {
                    _state.update {
                        it.copy(nextEpisode = NextEpisodeInfo(
                            season = season.seasonNumber,
                            episode = ep,
                            mode = NextEpisodeMode.PLAY_FIRST_UNWATCHED,
                        ))
                    }
                    return
                }
            }
        }

        // 3. All episodes watched — restart from S01E01
        val firstSeason = seasons.firstOrNull()
        if (firstSeason != null) {
            _state.update {
                it.copy(nextEpisode = NextEpisodeInfo(
                    season = firstSeason.seasonNumber,
                    episode = 1,
                    mode = NextEpisodeMode.PLAY_FROM_START,
                ))
            }
        }
    }

    /**
     * For TV shows: play the resolved next episode.
     * For movies: delegates straight to fetchStreams().
     */
    fun playNextEpisode() {
        val item = _state.value.mediaItem ?: return
        if (item.type != MediaType.SERIES) {
            fetchStreams()
            return
        }
        val next = _state.value.nextEpisode
        if (next != null) {
            fetchStreams(season = next.season, episode = next.episode)
        } else {
            // Fallback — resolve inline if not yet computed
            scope.launch {
                resolveNextEpisode()
                val resolved = _state.value.nextEpisode
                if (resolved != null) {
                    fetchStreams(season = resolved.season, episode = resolved.episode)
                }
            }
        }
    }

    fun warmupLikelyPlaybackTarget(
        trigger: ContentWarmupTrigger = ContentWarmupTrigger.DETAIL_OPEN,
    ) {
        warmupJob?.cancel()
        warmupJob = scope.launch {
            val uiState = _state.value
            if (uiState.isLoadingStreams || uiState.isResolving) return@launch

            val item = uiState.mediaItem ?: return@launch
            val request = buildStartupRequest(
                item = item,
                season = when {
                    uiState.streamContextSeason != null && uiState.streamContextEpisode != null -> uiState.streamContextSeason
                    item.type == MediaType.SERIES -> uiState.nextEpisode?.season
                    else -> null
                },
                episode = when {
                    uiState.streamContextSeason != null && uiState.streamContextEpisode != null -> uiState.streamContextEpisode
                    item.type == MediaType.SERIES -> uiState.nextEpisode?.episode
                    else -> null
                },
            ) ?: return@launch

            streamRepo.warmupStartupCandidates(
                request = request,
                trigger = trigger,
            )
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
                    isLoadingMoreSources = false,
                    streamsError = null,
                    streams = emptyList(),
                    startupCandidates = emptyList(),
                    streamContextSeason = season,
                    streamContextEpisode = episode,
                    autoPlayStream = null,
                    autoPlayMessage = null,
                    autoPlayFailed = false,
                    fallbackAttempt = 0,
                    playbackStartupStatus = DetailPlaybackStartupOrchestrator.reduce(
                        it.playbackStartupStatus,
                        PlaybackStartupEvent.LoadingStartupCandidates,
                    ),
                )
            }
            try {
                val settings = settingsProvider?.invoke()
                val preferences = settings?.buildStreamPreferences() ?: StreamPreferences()
                val addons = try { addonRepo.getInstalledAddons() } catch (_: Exception) { emptyList() }
                println("TORVE_STREAMS: ${addons.size} addons installed: ${addons.map { "${it.manifest.name}(${it.isEnabled},res=${it.manifest.resources})" }}")
                val debridAccounts = settings?.getDebridAccounts() ?: emptyMap()
                val request = buildStartupRequest(
                    item = item,
                    season = season,
                    episode = episode,
                    preferences = preferences,
                    addons = addons,
                    debridAccounts = debridAccounts,
                ) ?: return@launch
                val startupSnapshot = runCatching {
                    streamRepo.getWarmStartupCandidates(request)
                        ?: streamRepo.getStartupCandidates(request)
                }.getOrDefault(
                    com.torve.domain.model.StartupCandidatesSnapshot(
                        request = request,
                        readinessState = com.torve.domain.model.ReadinessState.EMPTY,
                        candidates = emptyList(),
                    ),
                )
                val startupStreams = runCatching {
                    streamRepo.fetchStreams(
                        type = item.type,
                        imdbId = imdbId,
                        contentId = item.tmdbId?.let { "tmdb:$it" },
                        title = item.title,
                        season = season,
                        episode = episode,
                        addons = addons,
                        debridAccounts = debridAccounts,
                        preferences = preferences,
                        fetchPolicy = StreamFetchPolicy.PLAYBACK_STARTUP,
                    )
                }.getOrDefault(emptyList())
                val startupPresentation = prioritizeStreamsForPresentation(
                    streams = startupStreams,
                    preferences = preferences,
                )

                if (startupPresentation.ordered.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            streams = startupPresentation.ordered,
                            startupCandidates = startupSnapshot.candidates,
                            isLoadingStreams = false,
                            showStreamPicker = forceManualPick || !preferences.autoPlayEnabled,
                            playbackStartupStatus = DetailPlaybackStartupOrchestrator.reduce(
                                it.playbackStartupStatus,
                                PlaybackStartupEvent.StartupCandidatesAvailable(startupPresentation.ordered.size),
                            ),
                        )
                    }
                }

                val shouldAutoPlay = preferences.autoPlayEnabled && !forceManualPick
                var startupResolveResult = AutoResolveResult(resolved = false)

                if (shouldAutoPlay && startupPresentation.rankedPlayable.isNotEmpty()) {
                    val bestStartup = startupPresentation.rankedPlayable.first()
                    _state.update {
                        it.copy(
                            autoPlayStream = bestStartup,
                            autoPlayMessage = "Trying ready-now source...",
                            playbackStartupStatus = DetailPlaybackStartupOrchestrator.reduce(
                                it.playbackStartupStatus,
                                PlaybackStartupEvent.AttemptingStartupAutoplay,
                            ),
                        )
                    }
                    startupResolveResult = autoResolveStreamProgressive(
                        streams = startupPresentation.rankedPlayable,
                        attemptIndex = 0,
                        preferences = preferences,
                        failureBehavior = AutoResolveFailureBehavior.DEFER_TO_FULL_FETCH,
                    )
                    if (startupResolveResult.resolved) {
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            playbackStartupStatus = DetailPlaybackStartupOrchestrator.reduce(
                                it.playbackStartupStatus,
                                PlaybackStartupEvent.StartupCandidateFailed,
                            ),
                            autoPlayMessage = "Ready-now source failed, loading more options...",
                        )
                    }
                }

                _state.update {
                    it.copy(
                        isLoadingStreams = startupPresentation.ordered.isEmpty(),
                        isLoadingMoreSources = startupPresentation.ordered.isNotEmpty(),
                        playbackStartupStatus = DetailPlaybackStartupOrchestrator.reduce(
                            it.playbackStartupStatus,
                            PlaybackStartupEvent.FallingBackToFullFetch,
                        ),
                    )
                }

                val fullStreams = streamRepo.fetchStreams(
                    type = item.type,
                    imdbId = imdbId,
                    contentId = item.tmdbId?.let { "tmdb:$it" },
                    title = item.title,
                    season = season,
                    episode = episode,
                    addons = addons,
                    debridAccounts = debridAccounts,
                    preferences = preferences,
                    fetchPolicy = StreamFetchPolicy.FULL,
                )
                val fullPresentation = prioritizeStreamsForPresentation(
                    streams = fullStreams,
                    preferences = preferences,
                )
                val mergedPresentation = mergePresentations(
                    startupPresentation = startupPresentation,
                    fullPresentation = fullPresentation,
                    preferences = preferences,
                )

                _state.update {
                    it.copy(
                        streams = mergedPresentation.ordered,
                        startupCandidates = startupSnapshot.candidates,
                        isLoadingStreams = false,
                        isLoadingMoreSources = false,
                        playbackStartupStatus = DetailPlaybackStartupOrchestrator.reduce(
                            it.playbackStartupStatus,
                            PlaybackStartupEvent.FullResultsAvailable(mergedPresentation.ordered.size),
                        ),
                    )
                }

                if (mergedPresentation.ordered.isEmpty()) {
                    _state.update { it.copy(streamsError = "No streams found") }
                    return@launch
                }

                if (shouldAutoPlay) {
                    val playable = mergedPresentation.rankedPlayable
                        .filterNot { candidate ->
                            candidate.presentationKey() in startupResolveResult.attemptedKeys
                        }

                    if (playable.isEmpty()) {
                        _state.update {
                            it.copy(
                                showStreamPicker = true,
                                streamsError = "No compatible streams found for this device - pick manually or try a different quality",
                            )
                        }
                    } else {
                        val best = playable.first()
                        _state.update {
                            it.copy(
                                autoPlayStream = best,
                                autoPlayMessage = buildAutoPlayMessage(best),
                            )
                        }
                        autoResolveStreamProgressive(
                            streams = playable,
                            attemptIndex = 0,
                            preferences = preferences,
                        )
                    }
                } else {
                    _state.update { it.copy(showStreamPicker = true) }
                }
                return@launch

                val streams = streamRepo.fetchStreams(
                    type = item.type,
                    imdbId = imdbId,
                    contentId = item.tmdbId?.let { "tmdb:$it" },
                    title = item.title,
                    season = season,
                    episode = episode,
                    addons = addons,
                    debridAccounts = debridAccounts,
                    preferences = preferences,
                )
                val presentation = prioritizeStreamsForPresentation(
                    streams = streams,
                    preferences = preferences,
                )

                _state.update {
                    it.copy(
                        streams = presentation.ordered,
                        isLoadingStreams = false,
                    )
                }

                if (presentation.ordered.isEmpty()) {
                    _state.update { it.copy(streamsError = "No streams found") }
                    return@launch
                }

                if (preferences.autoPlayEnabled && !forceManualPick) {
                    val playable = presentation.rankedPlayable

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
                    it.copy(
                        isLoadingStreams = false,
                        isLoadingMoreSources = false,
                        streamsError = com.torve.presentation.error.UserFacingError.STREAMS_LOAD_FAILED.messageKey,
                    )
                }
            }
        }
    }

    private fun prioritizeStreamsForPresentation(
        streams: List<ParsedStream>,
        preferences: StreamPreferences,
    ): StreamPresentationResult {
        if (streams.isEmpty()) return StreamPresentationResult(emptyList(), emptyList())

        val rankedPlayable = streamSelector.rankPlayableVariants(
            streams = streams,
            preferences = preferences,
            deviceCaps = deviceCodecCaps,
        )
        val rankedKeys = rankedPlayable.mapTo(linkedSetOf()) { it.presentationKey() }
        val remaining = streams
            .filterNot { it.presentationKey() in rankedKeys }
            .sortedWith(
                compareByDescending<ParsedStream> { it.isInstantPlaybackCandidate() }
                    .thenByDescending { it.isCached }
                    .thenByDescending { it.seeds ?: -1 }
                    .thenBy { StreamQuality.fromString(it.quality).rank }
                    .thenBy { it.addonName.lowercase() },
            )

        return StreamPresentationResult(
            ordered = rankedPlayable + remaining,
            rankedPlayable = rankedPlayable,
        )
    }

    private fun mergePresentations(
        startupPresentation: StreamPresentationResult,
        fullPresentation: StreamPresentationResult,
        preferences: StreamPreferences,
    ): StreamPresentationResult {
        val mergedOrdered = DetailPlaybackStartupOrchestrator.mergeStreams(
            startupStreams = startupPresentation.ordered,
            fullStreams = fullPresentation.ordered,
            keySelector = { it.presentationKey() },
        )
        val mergedRankedPlayable = DetailPlaybackStartupOrchestrator.mergeStreams(
            startupStreams = startupPresentation.rankedPlayable,
            fullStreams = fullPresentation.rankedPlayable,
            keySelector = { it.presentationKey() },
        )

        if (mergedOrdered.isEmpty()) return StreamPresentationResult(emptyList(), emptyList())

        return StreamPresentationResult(
            ordered = mergedOrdered,
            rankedPlayable = if (mergedRankedPlayable.isNotEmpty()) {
                mergedRankedPlayable
            } else {
                prioritizeStreamsForPresentation(mergedOrdered, preferences).rankedPlayable
            },
        )
    }

    private fun ParsedStream.presentationKey(): String {
        return accelerationSourceKey
            ?: infoHash
            ?: directUrl
            ?: "${addonName}|${title}|${quality}|${source.orEmpty()}"
    }

    private fun ParsedStream.isInstantPlaybackCandidate(): Boolean {
        return isCached || (directUrl != null && infoHash == null)
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
                streamRepo.reportPlaybackOutcome(stream, provider, success = false)
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
            streamRepo.reportPlaybackOutcome(stream, provider, success = false)
            _state.update {
                it.copy(
                    autoPlayMessage = "Stream failed, trying next...",
                    fallbackAttempt = attemptIndex + 1,
                )
            }
            autoResolveStream(streams, attemptIndex + 1, preferences)
        }
    }

    private suspend fun autoResolveStreamProgressive(
        streams: List<ParsedStream>,
        attemptIndex: Int,
        preferences: StreamPreferences,
        failureBehavior: AutoResolveFailureBehavior = AutoResolveFailureBehavior.SHOW_PICKER,
        attemptedKeys: Set<String> = emptySet(),
    ): AutoResolveResult {
        val maxAttempts = min(preferences.maxFallbackAttempts, streams.size)
        if (attemptIndex >= maxAttempts) {
            if (failureBehavior == AutoResolveFailureBehavior.SHOW_PICKER) {
                _state.update {
                    it.copy(
                        autoPlayFailed = true,
                        autoPlayMessage = null,
                        isResolving = false,
                        showStreamPicker = true,
                        streamsError = if (_state.value.streams.isNotEmpty()) "Auto-play failed - pick a stream manually" else null,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        autoPlayMessage = null,
                        isResolving = false,
                    )
                }
            }
            return AutoResolveResult(
                resolved = false,
                attemptedKeys = attemptedKeys,
            )
        }

        val stream = streams[attemptIndex]
        val currentAttemptedKeys = attemptedKeys + stream.presentationKey()
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
            return AutoResolveResult(
                resolved = false,
                attemptedKeys = currentAttemptedKeys,
            )
        }

        _state.update {
            it.copy(
                isResolving = true,
                resolveError = null,
                autoPlayStream = stream,
                fallbackAttempt = attemptIndex,
            )
        }

        return try {
            val resolved = withTimeoutOrNull(90_000L) {
                streamRepo.resolveStream(stream, provider, apiKey)
            }
            if (resolved != null) {
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
                AutoResolveResult(
                    resolved = true,
                    attemptedKeys = currentAttemptedKeys,
                )
            } else {
                com.torve.data.addon.StreamRuntimeTelemetry.recordStartupTimeout(hostKey, 90_000L)
                streamRepo.reportPlaybackOutcome(stream, provider, success = false)
                _state.update {
                    it.copy(
                        autoPlayMessage = "Stream timed out, trying next...",
                        fallbackAttempt = attemptIndex + 1,
                    )
                }
                autoResolveStreamProgressive(
                    streams = streams,
                    attemptIndex = attemptIndex + 1,
                    preferences = preferences,
                    failureBehavior = failureBehavior,
                    attemptedKeys = currentAttemptedKeys,
                )
            }
        } catch (_: Exception) {
            com.torve.data.addon.StreamRuntimeTelemetry.recordFatalError(hostKey)
            streamRepo.reportPlaybackOutcome(stream, provider, success = false)
            _state.update {
                it.copy(
                    autoPlayMessage = "Stream failed, trying next...",
                    fallbackAttempt = attemptIndex + 1,
                )
            }
            autoResolveStreamProgressive(
                streams = streams,
                attemptIndex = attemptIndex + 1,
                preferences = preferences,
                failureBehavior = failureBehavior,
                attemptedKeys = currentAttemptedKeys,
            )
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
                    streamRepo.reportPlaybackOutcome(stream, provider, success = false)
                    _state.update {
                        it.copy(isResolving = false, resolveError = com.torve.presentation.error.UserFacingError.STREAM_RESOLVE_TIMEOUT.messageKey)
                    }
                }
            } catch (e: Exception) {
                println("TORVE_RESOLVE: Exception ${e::class.simpleName}: ${e.message}")
                streamRepo.reportPlaybackOutcome(stream, provider, success = false)
                _state.update {
                    it.copy(isResolving = false, resolveError = com.torve.presentation.error.UserFacingError.STREAM_RESOLVE_FAILED.messageKey)
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
                    streamRepo.reportPlaybackOutcome(fallback, provider, success = false)
                    // Silently give up — show stream picker as last resort
                    _state.update {
                        it.copy(isResolving = false, showStreamPicker = true)
                    }
                }
            } catch (_: Exception) {
                com.torve.data.addon.StreamRuntimeTelemetry.recordFatalError(hostKey)
                streamRepo.reportPlaybackOutcome(fallback, provider, success = false)
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
            val tmdbId = item.tmdbId ?: return@launch
            // Trakt
            try {
                val ids = TraktIds(tmdb = tmdbId)
                if (item.type == MediaType.MOVIE) {
                    traktApi.removeFromHistory(TraktRemoveHistoryBody(movies = listOf(TraktHistoryMovie(ids))))
                } else {
                    traktApi.removeFromHistory(TraktRemoveHistoryBody(shows = listOf(TraktHistoryShow(ids))))
                }
            } catch (_: Exception) {
                traktSyncRepo.enqueueHistoryRemove(tmdbId, item.type, item.imdbId)
            }
            // Simkl — no remove API available; SIMKL is push-only for history
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
                        id = "${item.id}_${episodeKey(seasonNumber, ep)}",
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
                val newWatched = _state.value.watchedEpisodes.toMutableSet()
                for (ep in 1..episodeCount) {
                    newWatched.add(episodeKey(seasonNumber, ep))
                }
                _state.update { it.copy(watchedEpisodes = newWatched) }
                resolveNextEpisode()
            } catch (_: Exception) { }
        }
    }

    /**
     * Toggle watched state for a single episode.
     * Unwatching also clears any partial playback progress for that episode.
     */
    fun toggleEpisodeWatched(seasonNumber: Int, episodeNumber: Int) {
        val key = episodeKey(seasonNumber, episodeNumber)
        if (key in _state.value.watchedEpisodes) {
            unmarkEpisodeWatched(seasonNumber, episodeNumber)
        } else {
            markEpisodeWatched(seasonNumber, episodeNumber)
        }
    }

    private fun markEpisodeWatched(seasonNumber: Int, episodeNumber: Int) {
        val item = _state.value.mediaItem ?: return
        scope.launch {
            try {
                val epTitle = _state.value.seasonDetail?.episodes
                    ?.getOrNull(episodeNumber - 1)?.name ?: "Episode $episodeNumber"
                val entry = WatchHistoryEntry(
                    id = "${item.id}_${episodeKey(seasonNumber, episodeNumber)}",
                    mediaId = item.id,
                    mediaType = MediaType.SERIES.name,
                    title = epTitle,
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    watchedAt = Clock.System.now().toEpochMilliseconds(),
                    durationWatchedMs = 0,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    showTitle = item.title,
                )
                watchHistoryRepo.record(entry)
                val newWatched = _state.value.watchedEpisodes + episodeKey(seasonNumber, episodeNumber)
                _state.update { it.copy(watchedEpisodes = newWatched) }
                resolveNextEpisode()
            } catch (_: Exception) { }
        }
    }

    private fun unmarkEpisodeWatched(seasonNumber: Int, episodeNumber: Int) {
        val item = _state.value.mediaItem ?: return
        scope.launch {
            try {
                // Remove watch history entry
                val historyId = "${item.id}_${episodeKey(seasonNumber, episodeNumber)}"
                watchHistoryRepo.delete(historyId)
                // Clear partial playback progress for this episode so the resolver
                // does not immediately surface it as "Resume".
                watchProgressRepo.deleteProgress(item.id)
                // Update UI state
                val newWatched = _state.value.watchedEpisodes - episodeKey(seasonNumber, episodeNumber)
                _state.update { it.copy(watchedEpisodes = newWatched, watchProgress = null) }
                resolveNextEpisode()
            } catch (_: Exception) { }
        }
    }

    /**
     * Refresh watch state from repositories (media-scoped, not full-table).
     * Call after returning from player, after toggling watched, or after sync.
     */
    fun refreshWatchState() {
        val item = _state.value.mediaItem ?: return
        scope.launch {
            try {
                // Refresh watched episodes (media-scoped)
                val history = watchHistoryRepo.getForMedia(item.id)
                val watched = history
                    .filter { it.seasonNumber != null && it.episodeNumber != null }
                    .map { episodeKey(it.seasonNumber!!, it.episodeNumber!!) }
                    .toSet()
                // Refresh playback progress
                val progress = watchProgressRepo.getProgress(item.id)
                _state.update { it.copy(watchedEpisodes = watched, watchProgress = progress) }
                resolveNextEpisode()
            } catch (_: Exception) { }
        }
    }

    fun loadWatchedEpisodes() {
        val item = _state.value.mediaItem ?: return
        scope.launch {
            try {
                val history = watchHistoryRepo.getForMedia(item.id)
                val watched = history
                    .filter { it.seasonNumber != null && it.episodeNumber != null }
                    .map { episodeKey(it.seasonNumber!!, it.episodeNumber!!) }
                    .toSet()
                _state.update { it.copy(watchedEpisodes = watched) }
                resolveNextEpisode()
                warmupLikelyPlaybackTarget(ContentWarmupTrigger.DETAIL_OPEN)
            } catch (_: Exception) { }
        }
    }

    private suspend fun buildStartupRequest(
        item: com.torve.domain.model.MediaItem,
        season: Int?,
        episode: Int?,
        preferences: StreamPreferences? = null,
        addons: List<com.torve.domain.model.InstalledAddon>? = null,
        debridAccounts: Map<DebridServiceType, String>? = null,
    ): SourceAccelerationRequest? {
        val imdbId = item.imdbId ?: return null
        val settings = settingsProvider?.invoke()
        val resolvedPreferences = preferences ?: settings?.buildStreamPreferences() ?: StreamPreferences()
        val resolvedAddons = addons ?: try {
            addonRepo.getInstalledAddons()
        } catch (_: Exception) {
            emptyList()
        }
        val resolvedDebridAccounts = debridAccounts ?: settings?.getDebridAccounts() ?: emptyMap()

        return SourceAccelerationRequest(
            mediaType = item.type,
            imdbId = imdbId,
            contentId = item.tmdbId?.let { "tmdb:$it" },
            title = item.title,
            seasonNumber = season,
            episodeNumber = episode,
            context = com.torve.domain.model.SourceAccelerationContext(
                addons = resolvedAddons,
                debridAccounts = resolvedDebridAccounts,
                preferences = resolvedPreferences,
                startupFetchPolicy = StreamFetchPolicy.PLAYBACK_STARTUP,
            ),
        )
    }
}
