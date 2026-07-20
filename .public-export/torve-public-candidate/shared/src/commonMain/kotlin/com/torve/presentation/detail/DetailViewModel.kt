package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import com.torve.data.addon.StreamSelector
import com.torve.data.addon.isAddonHostedUrl
import com.torve.data.acceleration.StreamHandoffApiException
import com.torve.data.debrid.DebridMissingException
import com.torve.data.debrid.DebridNeedsReconnectException
import com.torve.data.debrid.DebridNoCachedStreamException
import com.torve.data.debrid.DebridServiceUnavailableException
import com.torve.data.debrid.DebridSourceBlockedException
import com.torve.data.contentpolicy.ContentPolicyCacheInvalidationCoordinator
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.kodi.KodiClient
import com.torve.data.kodi.KodiHost
import com.torve.data.simkl.SimklClient
import com.torve.data.simkl.SimklIds
import com.torve.data.simkl.SimklSyncBody
import com.torve.data.simkl.SimklSyncItem
import com.torve.data.stats.WatchSessionMediaIdentity
import com.torve.data.stats.WatchSessionRecorder
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
import com.torve.domain.model.Episode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.Season
import com.torve.domain.model.SensitiveClassification
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.model.WatchProgress
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
import com.torve.data.usenet.UsenetMapper
import com.torve.data.usenet.model.UsenetAvailability
import com.torve.data.usenet.model.UsenetCandidatePayload
import com.torve.data.usenet.model.UsenetCandidateStates
import com.torve.data.usenet.model.UsenetCandidateUiModel
import com.torve.data.usenet.model.UsenetUserMessageKey
import com.torve.domain.streams.PollOutcome
import com.torve.domain.streams.ResolveOutcome
import com.torve.domain.streams.UsenetJobPoller
import com.torve.domain.streams.UsenetResolveCoordinator
import com.torve.domain.streams.UsenetWarmCoordinator
import com.torve.domain.streams.formatUsenetContentId
import com.torve.domain.streams.usenetCandidateIdOrNull
import com.torve.domain.streams.usenetCandidatePayloadOrNull
import com.torve.domain.telemetry.TelemetryEmitter
import com.torve.domain.telemetry.UsenetFallbackReason
import com.torve.domain.telemetry.UsenetTelemetryEvents
import com.torve.domain.telemetry.UsenetTelemetryKeys
import com.torve.domain.telemetry.UsenetTelemetryState
import com.torve.domain.telemetry.contentCandidateAttrs
import com.torve.domain.telemetry.timeBucket
import com.torve.platform.torveVerboseLog
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
import kotlinx.coroutines.delay
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
    /**
     * Optional so the existing test fixtures that build DetailViewModel with
     * positional args don't have to plumb a new dep in this sprint. A null
     * coordinator means no prewarm is fired — same as if no Usenet
     * candidates were present. Koin wires the real instance (see
     * [com.torve.di.SharedModule]).
     */
    private val usenetWarmCoordinator: UsenetWarmCoordinator? = null,
    private val usenetResolveCoordinator: UsenetResolveCoordinator? = null,
    private val usenetJobPoller: UsenetJobPoller? = null,
    private val telemetry: TelemetryEmitter? = null,
    /**
     * Cross-device resume source. When non-null, `loadDetail` queries the
     * backend for the most recent watch-state report across all the user's
     * devices and merges it with the local SQLDelight row, preferring
     * whichever has the newer timestamp. Nullable so existing test fixtures
     * keep compiling; Koin wires the real implementation on Android.
     */
    private val watchStateRemoteSource: com.torve.domain.integrations.WatchStateRemoteSource? = null,
    private val watchSessionRecorder: WatchSessionRecorder? = null,
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

    private companion object {
        // Preparing loop budget — matches Panda's 5-minute background poll
        // window. 20 attempts × 15s = 300s. If the cloud client hasn't
        // resolved by then, the user is far better served by picking an
        // alternate source than by watching a spinner indefinitely.
        const val PREPARING_PROBE_INTERVAL_MS = 15_000L
        const val PREPARING_MAX_ATTEMPTS = 20
        const val MARKED_WATCHED_PROGRESS_RATIO = 0.9f
        const val MARKED_WATCHED_DURATION_MS = 1_000_000L
        val STREAM_HANDOFF_BLOCKING_ERROR_CODES = setOf(
            "device_required",
            "device_not_registered",
            "device_not_authorized",
            "premium_required",
            "rate_limited",
        )
    }
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()
    private var warmupJob: Job? = null
    private var currentType: String? = null
    private var currentId: Int? = null
    /**
     * Tracks the in-flight Usenet resolve chain so a fresh user tap can
     * pre-empt a stale one. Cancelled on content switch / VM clear.
     */
    private var currentUsenetResolveJob: Job? = null

    /**
     * Candidate the user last tapped through the Usenet resolver.
     * Poll-tick results that don't match this id are dropped as stale —
     * if the user moved to another row while a poll was in flight, we
     * MUST NOT auto-play the old candidate. Also cleared on content
     * switch, sheet dismiss, and VM clear.
     */
    private var activeUsenetCandidateId: String? = null

    /**
     * Timestamp of the most recent [selectUsenetSource] call. Drives
     * the `time_to_play_bucket` attribute on `usenet_playback_started`.
     */
    private var activeUsenetSelectionStartedAt: Long? = null

    /**
     * Zero-based index of the current fallback attempt within one user
     * tap. `0` = the row the user tapped; `1+` = auto-advanced
     * candidates. Used by [UsenetTelemetryKeys.FALLBACK_ATTEMPT_INDEX].
     */
    private var activeUsenetFallbackIndex: Int = 0

    /**
     * Initial candidate for the current resolve chain (the row the user
     * actually tapped). Used on fallback_succeeded so the telemetry
     * attribute reflects "user picked X, we eventually played Y."
     */
    private var initialUsenetCandidateId: String? = null

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
        // Content-switch cleanup: drop any Usenet prewarm state for the
        // outgoing content so a later revisit can re-warm, and ask the
        // backend to cancel outstanding warm jobs for that content.
        // Best-effort — no-op if the coordinator isn't wired or the
        // previous content identity can't be formatted.
        val prevType = currentType
        val prevId = currentId
        if (prevType != null && prevId != null && (prevType != type || prevId != id)) {
            val coordinator = usenetWarmCoordinator
            val prevContentId = formatUsenetContentId(type = prevType, tmdbId = prevId)
            if (coordinator != null && prevContentId != null) {
                scope.launch { coordinator.clearForContent(prevContentId) }
            }
            // Also tear down any in-flight resolve/poll for the outgoing
            // content. The poller's internal cancel also calls the
            // backend cancel endpoint for the old jobId.
            cancelUsenetResolveAndPoll()
        }
        currentType = type
        currentId = id
        scope.launch {
            detailDiagLog("loadDetail enter type=$type id=$id")
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    streams = emptyList(),
                    startupCandidates = emptyList(),
                    isLoadingStreams = false,
                    isLoadingMoreSources = false,
                    streamsError = null,
                    streamsErrorHint = null,
                    streamFilterHiddenCount = 0,
                    showStreamPicker = false,
                    autoPlayStream = null,
                    autoPlayMessage = null,
                    autoPlayFailed = false,
                    fallbackAttempt = 0,
                )
            }
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
                _state.update {
                    it.copy(
                        mediaItem = item,
                        similar = emptyList(),
                        similarError = null,
                        isLoadingSimilar = false,
                        seasonDetail = null,
                        seasonDetailError = null,
                        isLoadingSeasonDetail = false,
                        isLoading = false,
                    )
                }

                if (item.isStubDetail) {
                    return@launch
                }

                // TV pages should expose seasons/episodes immediately. Similar
                // titles and other enrichment can be slower; they must not block
                // the core episode picker workflow.
                if (type == "tv" && item.seasons.isNotEmpty()) {
                    val firstReal = item.seasons.firstOrNull { it.seasonNumber > 0 }
                    if (firstReal != null) {
                        loadSeasonDetail(id, firstReal.seasonNumber)
                    }
                    loadWatchedEpisodes()
                }

                val allowSensitiveFromParent = contentPolicyFilter.classify(rawItem, ContentSourceType.TMDB) == SensitiveClassification.SENSITIVE &&
                    policy.adultEnabled
                _state.update { it.copy(isLoadingSimilar = true, similarError = null) }
                try {
                    val similarCandidates = mutableListOf<MediaItem>()
                    similarCandidates += runCatching { metadataRepo.getSimilar(type, id) }.getOrDefault(emptyList())
                    if (similarCandidates.size < 12) {
                        similarCandidates += runCatching { metadataRepo.getRecommendations(type, id) }.getOrDefault(emptyList())
                    }
                    if (similarCandidates.size < 12) {
                        val genreFallback = item.genreIds.firstOrNull()?.toString()
                        similarCandidates += runCatching {
                            metadataRepo.discover(
                                type = type,
                                sortBy = "popularity.desc",
                                withGenres = genreFallback,
                            ).items
                        }.getOrDefault(emptyList())
                    }
                    if (similarCandidates.size < 12) {
                        similarCandidates += runCatching { metadataRepo.getPopular(type) }.getOrDefault(emptyList())
                    }
                    if (similarCandidates.size < 12) {
                        similarCandidates += runCatching { metadataRepo.getPopular(type, page = 2) }.getOrDefault(emptyList())
                    }
                    if (similarCandidates.size < 12) {
                        similarCandidates += runCatching { metadataRepo.getTrending(type) }.getOrDefault(emptyList())
                    }
                    val similar = ratingsEnricher.hydrateListFromCache(
                        similarCandidates
                            .asSequence()
                            .filterNot { candidate ->
                                candidate.id == item.id ||
                                    (candidate.tmdbId != null && item.tmdbId != null && candidate.tmdbId == item.tmdbId)
                            }
                            .distinctBy { candidate -> "${candidate.type}:${candidate.tmdbId ?: candidate.id}" }
                            .take(24)
                            .toList(),
                    )
                    val filteredSimilar = contentPolicyFilter.filterItems(
                        policy = policy,
                        context = ContentAccessContext.SIMILAR_OR_MORE_LIKE_THIS,
                        items = similar,
                        sourceType = ContentSourceType.TMDB,
                        allowSensitiveBecauseUserReachedSensitiveParent = allowSensitiveFromParent,
                    ).items
                    _state.update {
                        it.copy(
                            similar = filteredSimilar,
                            isLoadingSimilar = false,
                            similarError = null,
                        )
                    }
                    // Enrich similar (Related rail) the same way CatalogViewModel
                    // enriches its first three rails. Previously this only
                    // hydrated from the local cache, so newly-fetched related
                    // items showed TMDB-only pills until the user had
                    // navigated to each individually.
                    enrichSimilarRatings(filteredSimilar)
                } catch (_: Exception) {
                    _state.update {
                        it.copy(
                            similar = emptyList(),
                            isLoadingSimilar = false,
                            similarError = "recommendations_failed",
                        )
                    }
                }

                // Load watch progress — merge local SQLDelight row with the
                // backend's latest report (cross-device resume). Whichever has
                // the newer timestamp wins. Remote fetch is best-effort; if
                // it's null/fails we fall through to local-only behavior, so
                // users without an account or with flaky connectivity see no
                // regression.
                val local = watchProgressRepo.getProgress(item.id)
                val remote = watchStateRemoteSource?.getLatest(item.id)
                val merged = mergeWatchProgress(local = local, remote = remote, item = item)
                // If remote wins, persist to local DB so the next cold
                // start shows the right Resume position even offline.
                if (merged != null && merged !== local) {
                    runCatching { watchProgressRepo.saveProgress(merged) }
                }
                val rating = item.tmdbId?.let { tmdbId ->
                    runCatching { traktSyncRepo.getUserRating(tmdbId, item.type) }.getOrNull()
                }
                val localWholeItemWatched = runCatching {
                    watchHistoryRepo.getForMedia(item.id).any { history ->
                        history.seasonNumber == null && history.episodeNumber == null
                    }
                }.getOrDefault(false)
                val progressWatched = (merged?.progressPercent ?: 0f) >= MARKED_WATCHED_PROGRESS_RATIO
                _state.update {
                    it.copy(
                        watchProgress = merged,
                        userRating = rating,
                        isMarkedWatched = localWholeItemWatched || progressWatched,
                    )
                }

                warmupLikelyPlaybackTarget(ContentWarmupTrigger.DETAIL_OPEN)
                loadAvailability(item)
                loadLibraryStatus(item)
                detailDiagLog("loadDetail about to call enrichRatings id=${item.id}")
                enrichRatings(item)
            } catch (e: Exception) {
                detailDiagLog("loadDetail FAILED id=$id error=${e.message}")
                _state.update { current ->
                    if (current.mediaItem != null) {
                        current.copy(isLoading = false, error = null)
                    } else {
                        current.copy(
                            isLoading = false,
                            error = com.torve.presentation.error.UserFacingError.CONTENT_LOAD_FAILED.messageKey,
                        )
                    }
                }
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
                val r = filtered.ratings
                val msg = "enrichRatings done id=${item.id} tmdbId=${filtered.tmdbId} " +
                    "imdbId=${filtered.imdbId} ratings=" +
                    "(tmdb=${r?.tmdbScore} imdb=${r?.imdbScore} rt=${r?.rottenTomatoesScore} " +
                    "trakt=${r?.traktScore} mc=${r?.metacriticScore} mdb=${r?.mdblistScore})"
                println("TORVE_DETAIL: $msg")
                detailDiagLog(msg)
                _state.update { it.copy(mediaItem = filtered) }
            } catch (e: Exception) {
                val msg = "enrichRatings FAILED id=${item.id} error=${e.message}"
                println("TORVE_DETAIL: $msg")
                detailDiagLog(msg)
            }
        }
    }

    /**
     * Asynchronously enriches the Related rail items via MDBList/OMDB/Trakt
     * after the cache hydrate. Mirrors CatalogViewModel.enrichAndUpdateItems().
     * Replaces state.similar with the enriched list so the rail's pills
     * match the detail page itself instead of staying TMDB-only.
     */
    private fun enrichSimilarRatings(items: List<com.torve.domain.model.MediaItem>) {
        if (items.isEmpty()) return
        scope.launch {
            val apiKey = try {
                integrationSecretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            } catch (_: Exception) { MdbListApi.DEFAULT_API_KEY }
            try {
                val enriched = ratingsEnricher.enrichList(items, apiKey)
                val withRatingsCount = enriched.count {
                    val r = it.ratings ?: return@count false
                    r.imdbScore != null || r.rottenTomatoesScore != null ||
                        r.traktScore != null || r.metacriticScore != null
                }
                detailDiagLog(
                    "enrichSimilar done size=${items.size} withRatings=$withRatingsCount apiKey=${apiKeySummary(apiKey)}",
                )
                _state.update { it.copy(similar = enriched) }
            } catch (e: Exception) {
                val msg = "enrichSimilar FAILED size=${items.size} error=${e.message}"
                println("TORVE_DETAIL: $msg")
                detailDiagLog(msg)
            }
        }
    }

    private fun apiKeySummary(apiKey: String): String = when {
        apiKey.isBlank() -> "<blank>"
        apiKey == MdbListApi.DEFAULT_API_KEY -> "<DEFAULT_PLACEHOLDER>"
        apiKey.contains("INSERT") -> "<placeholder>"
        else -> "<set:${apiKey.length}chars>"
    }

    private fun detailDiagLog(message: String) {
        torveVerboseLog { "TorveDetail $message" }
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
            _state.update {
                it.copy(
                    selectedSeason = seasonNumber,
                    seasonDetail = null,
                    seasonDetailError = null,
                    isLoadingSeasonDetail = true,
                )
            }
            try {
                val season = metadataRepo.getSeasonDetail(tvId, seasonNumber)
                _state.update {
                    it.copy(
                        seasonDetail = season,
                        seasonDetailError = null,
                        isLoadingSeasonDetail = false,
                    )
                }
            } catch (e: Exception) {
                detailDiagLog("loadSeasonDetail FAILED tvId=$tvId season=$seasonNumber error=${e::class.simpleName}")
                val fallbackSeason = fallbackSeasonFromShowMetadata(seasonNumber)
                _state.update {
                    it.copy(
                        seasonDetail = fallbackSeason,
                        seasonDetailError = if (fallbackSeason == null) "season_metadata_failed" else null,
                        isLoadingSeasonDetail = false,
                    )
                }
            }
        }
    }

    private fun fallbackSeasonFromShowMetadata(seasonNumber: Int): Season? {
        val season = _state.value.mediaItem
            ?.seasons
            ?.firstOrNull { it.seasonNumber == seasonNumber }
            ?: return null
        if (season.episodeCount <= 0) return null
        return season.copy(
            episodes = (1..season.episodeCount).map { episode ->
                Episode(
                    episodeNumber = episode,
                    name = "Episode $episode",
                )
            },
        )
    }

    /**
     * Merge the local SQLDelight row with the backend's latest watch-state
     * report. Whichever has the newer timestamp wins. If only local exists,
     * returns local unchanged. If only remote exists, synthesizes a
     * [WatchProgress] from it using metadata from [item] (title/poster/etc.
     * which aren't stored server-side).
     */
    private fun mergeWatchProgress(
        local: com.torve.domain.model.WatchProgress?,
        remote: com.torve.domain.integrations.RemoteWatchState?,
        item: com.torve.domain.model.MediaItem,
    ): com.torve.domain.model.WatchProgress? {
        if (remote == null) return local
        if (local != null && local.updatedAt >= remote.reportedAtMs) return local
        // Remote wins. Keep metadata from the item (the backend doesn't
        // round-trip title/poster) and position/timestamp from remote.
        val base = local ?: com.torve.domain.model.WatchProgress(
            mediaId = item.id,
            mediaType = item.type,
            title = item.title,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            durationMs = 0L,
        )
        return base.copy(
            positionMs = remote.positionMs,
            updatedAt = remote.reportedAtMs,
        )
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
            _state.update {
                it.copy(
                    streamsError = "No IMDB ID — cannot fetch streams for this title",
                    streamsErrorHint = null,
                    streamFilterHiddenCount = 0,
                )
            }
            return
        }

        scope.launch {
            _state.update {
                it.copy(
                    isLoadingStreams = true,
                    isLoadingMoreSources = false,
                    streamsError = null,
                    streamsErrorHint = null,
                    streamFilterHiddenCount = 0,
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
                val startupFetch = runCatching {
                    streamRepo.fetchStreamsWithFeedback(
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
                }
                val startupResult = startupFetch.getOrNull()
                val startupStreams = startupResult?.streams.orEmpty()
                val startupFilterHiddenCount = startupResult?.filterFeedback?.hiddenCount ?: 0
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
                            streamFilterHiddenCount = startupFilterHiddenCount,
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

                val fullFetch = streamRepo.fetchStreamsWithFeedback(
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
                val fullStreams = fullFetch.streams
                val fullFilterHiddenCount = fullFetch.filterFeedback.hiddenCount
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
                        streamsError = null,
                        streamsErrorHint = null,
                        streamFilterHiddenCount = fullFilterHiddenCount,
                        playbackStartupStatus = DetailPlaybackStartupOrchestrator.reduce(
                            it.playbackStartupStatus,
                            PlaybackStartupEvent.FullResultsAvailable(mergedPresentation.ordered.size),
                        ),
                    )
                }

                if (mergedPresentation.ordered.isEmpty()) {
                    val filteredMessage = StreamFilterUiText.allHiddenMessage(
                        visibleCount = mergedPresentation.ordered.size,
                        hiddenCount = fullFilterHiddenCount,
                    )
                    _state.update {
                        it.copy(
                            streamsError = filteredMessage ?: "No streams found",
                            streamsErrorHint = StreamFilterUiText.allHiddenHint(
                                visibleCount = mergedPresentation.ordered.size,
                                hiddenCount = fullFilterHiddenCount,
                            ),
                        )
                    }
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
                        streamsErrorHint = null,
                        streamFilterHiddenCount = 0,
                    )
                }
            } finally {
                // Finally — not an else — because several startup-path
                // branches `return@launch` before reaching the full-fetch
                // update and we still want to warm whatever Usenet rows
                // the startup snapshot surfaced. No-op when no Usenet
                // candidates are present.
                maybePrewarmUsenetCandidates(season = season, episode = episode)
            }
        }
    }

    /**
     * UI hook — call when the source sheet is explicitly opened (not
     * merely when `showStreamPicker = true` flips via the auto-resolve
     * error path, though wiring both is safe; the coordinator dedupes).
     *
     * Fires an expanded warmup for the top 3–5 Usenet candidates in the
     * current stream list and merges the response into the sidecar
     * `usenetCandidates` map so the source sheet can render Ready /
     * Preparing / Unavailable pills per row. Fire-and-forget on [scope];
     * the sheet opens immediately with whatever sidecar state already
     * exists, and rows update incrementally as the warm resolves.
     *
     * No-op when: no Usenet candidates, no content identity, no coordinator
     * wired. No resolve, no polling — those land in Prompts 5–6.
     */
    fun onSourceSheetOpened() {
        val coordinator = usenetWarmCoordinator ?: return
        val type = currentType ?: return
        val tmdbId = currentId ?: return
        val contentId = formatUsenetContentId(
            type = type,
            tmdbId = tmdbId,
            season = _state.value.streamContextSeason,
            episode = _state.value.streamContextEpisode,
        ) ?: return
        val candidates = _state.value.streams
            .asSequence()
            .mapNotNull { it.usenetCandidatePayloadOrNull() }
            .distinctBy { it.candidateId }
            .take(UsenetWarmCoordinator.MAX_SHEET_WARM_CANDIDATES)
            .toList()
        emitUsenetTelemetry(
            event = UsenetTelemetryEvents.SOURCE_SHEET_OPENED,
            attributes = contentCandidateAttrs(contentId, null) + mapOf(
                UsenetTelemetryKeys.CANDIDATE_COUNT to candidates.size.toString(),
            ),
        )
        if (candidates.isEmpty()) return
        scope.launch {
            val response = coordinator.prewarmForSheet(
                contentId = contentId,
                candidates = candidates,
            ) ?: return@launch
            val now = Clock.System.now().toEpochMilliseconds()
            _state.update { state ->
                state.copy(
                    usenetCandidates = UsenetMapper.mergeWarmResponse(
                        existing = state.usenetCandidates,
                        response = response,
                        now = now,
                    ),
                )
            }
        }
    }

    /**
     * User-initiated resolve for a Usenet source row.
     *
     * Routing contract:
     *  - **Ready** → populate [DetailUiState.usenetPlaybackIntent]; the UI
     *    layer observes it, stages the handoff headers, and launches the
     *    existing `onPlayClick(url, …)` path with the handoff URL
     *    byte-for-byte.
     *  - **Warming** → sidecar row flips to Preparing + jobId captured.
     *    Sheet stays open. No poller yet (Prompt 6).
     *  - **Failed** → sidecar row flips to Unavailable and the VM auto-
     *    advances to the next-best Usenet candidate in the ranked
     *    `streams` list. The failed row's copy briefly shows
     *    "Trying next source" when a next candidate exists.
     *
     * Non-Usenet rows must NOT be routed here — the existing
     * `resolveStream(...)` path handles them. The UI layer enforces the
     * split by switching on [ParsedStream.accelerationProvenanceKind].
     */
    fun selectUsenetSource(stream: ParsedStream) {
        val coordinator = usenetResolveCoordinator ?: return
        val tappedPayload = stream.usenetCandidatePayloadOrNull() ?: return
        val candidateId = tappedPayload.candidateId
        val type = currentType ?: return
        val tmdbId = currentId ?: return
        val contentId = formatUsenetContentId(
            type = type,
            tmdbId = tmdbId,
            season = _state.value.streamContextSeason,
            episode = _state.value.streamContextEpisode,
        ) ?: return

        // Pre-empt any in-flight chain + any active poll. A fresh user
        // tap is the single most authoritative signal; nothing the
        // backend says about the previous candidate matters after this.
        currentUsenetResolveJob?.cancel()
        scope.launch { usenetJobPoller?.cancelActive() }

        activeUsenetSelectionStartedAt = Clock.System.now().toEpochMilliseconds()
        initialUsenetCandidateId = candidateId
        activeUsenetFallbackIndex = 0
        activeUsenetCandidateId = candidateId

        _state.update { state ->
            val now = Clock.System.now().toEpochMilliseconds()
            val previous = state.usenetCandidates[candidateId]
            val preparingRow = previous?.copy(
                availabilityState = UsenetAvailability.PREPARING,
                isSelected = true,
                jobId = null,
                resolvedStream = null,
                displayMessageKey = UsenetUserMessageKey.PREPARING,
                failureMessageKey = null,
                lastStateChangeAt = now,
            ) ?: UsenetCandidateUiModel(
                candidateId = candidateId,
                title = stream.title,
                qualityLabel = stream.quality.takeIf { it.isNotBlank() },
                sizeLabel = stream.size,
                badge = "Usenet",
                availabilityState = UsenetAvailability.PREPARING,
                isSelected = true,
                displayMessageKey = UsenetUserMessageKey.PREPARING,
                lastStateChangeAt = now,
            )
            state.copy(
                isResolving = true,
                resolveError = null,
                autoPlayMessage = resolvingStatusMessage(stream, provider = null),
                usenetCandidates = mergeSidecarRow(state.usenetCandidates, preparingRow),
            )
        }

        val selectionInitialState = UsenetTelemetryState.from(
            _state.value.usenetCandidates[candidateId]?.availabilityState,
        )
        emitUsenetTelemetry(
            event = UsenetTelemetryEvents.SOURCE_SELECTED,
            attributes = contentCandidateAttrs(contentId, candidateId) + mapOf(
                UsenetTelemetryKeys.INITIAL_STATE to selectionInitialState.name,
            ),
        )

        currentUsenetResolveJob = scope.launch {
            val orderedCandidates = _state.value.streams
                .asSequence()
                .mapNotNull { it.usenetCandidatePayloadOrNull() }
                .distinctBy { it.candidateId }
                .toList()
            val attempted = mutableSetOf<String>()
            var nextToTry: UsenetCandidatePayload? = tappedPayload
            while (nextToTry != null) {
                val tryingThis = nextToTry
                attempted += tryingThis.candidateId
                activeUsenetCandidateId = tryingThis.candidateId

                // Resolve once. If backend returns warming, the helper
                // below awaits poll terminal and collapses it back into
                // the same Ready/Failed shape the outer loop expects.
                val terminal = resolveAndMaybePoll(
                    coordinator = coordinator,
                    contentId = contentId,
                    candidate = tryingThis,
                )

                when (terminal) {
                    is ResolveOutcome.Ready -> {
                        // Stale-tick guard: if the user tapped another
                        // row while we were polling, a late Ready must
                        // NOT auto-play. activeUsenetCandidateId is
                        // updated by every iteration of this loop and
                        // also by fresh taps; if it's drifted, drop.
                        if (activeUsenetCandidateId == tryingThis.candidateId) {
                            _state.update {
                                it.copy(
                                    isResolving = false,
                                    autoPlayMessage = null,
                                    resolveError = null,
                                    usenetPlaybackIntent = terminal.stream,
                                )
                            }
                            // If we got here via fallback (not the row
                            // the user originally tapped), emit the
                            // paired "fallback_succeeded" event.
                            if (activeUsenetFallbackIndex > 0) {
                                emitUsenetTelemetry(
                                    event = UsenetTelemetryEvents.FALLBACK_SUCCEEDED,
                                    attributes = contentCandidateAttrs(contentId, tryingThis.candidateId) + mapOf(
                                        UsenetTelemetryKeys.FALLBACK_ATTEMPT_INDEX to
                                            activeUsenetFallbackIndex.toString(),
                                    ),
                                )
                            }
                        }
                        return@launch
                    }
                    is ResolveOutcome.Warming -> {
                        // Exit path: initial resolve returned warming AND
                        // no jobId / poller was available. Row is already
                        // PREPARING via the mapper; the user re-taps to
                        // retry. Not an auto-advance case.
                        _state.update {
                            it.copy(
                                isResolving = false,
                                autoPlayMessage = "Usenet is still preparing this stream.",
                            )
                        }
                        return@launch
                    }
                    is ResolveOutcome.Failed -> {
                        val failedId = tryingThis.candidateId
                        nextToTry = orderedCandidates.firstOrNull { it.candidateId !in attempted }
                        if (nextToTry != null) {
                            activeUsenetFallbackIndex += 1
                            emitUsenetTelemetry(
                                event = UsenetTelemetryEvents.FALLBACK_ATTEMPTED,
                                attributes = contentCandidateAttrs(contentId, nextToTry.candidateId) + mapOf(
                                    UsenetTelemetryKeys.FALLBACK_ATTEMPT_INDEX to
                                        activeUsenetFallbackIndex.toString(),
                                    UsenetTelemetryKeys.FALLBACK_REASON to
                                        UsenetFallbackReason.RESOLVE_FAILED.value,
                                ),
                            )
                            _state.update { state ->
                                val failed = state.usenetCandidates[failedId] ?: return@update state
                                state.copy(
                                    usenetCandidates = mergeSidecarRow(
                                        state.usenetCandidates,
                                        failed.copy(
                                            displayMessageKey = UsenetUserMessageKey.TRYING_NEXT_SOURCE,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    isResolving = false,
                    autoPlayMessage = null,
                    resolveError = "Usenet source is unavailable. Try another source.",
                )
            }
        }
    }

    /**
     * Resolve [candidateId] and, if the backend says warming, await the
     * bounded poller until a terminal outcome. Normalized return: a
     * resolve-flavored Ready or Failed (never Warming) so the chain
     * outside doesn't have to distinguish "didn't poll" from "polled
     * and got answer."
     *
     * When the poller is wired, the initial Warming response's `jobId`
     * is captured and the poller is handed the row as the seed snapshot
     * for subsequent tick applications.
     */
    private suspend fun resolveAndMaybePoll(
        coordinator: UsenetResolveCoordinator,
        contentId: String,
        candidate: UsenetCandidatePayload,
    ): ResolveOutcome {
        val outcome = coordinator.resolve(
            contentId = contentId,
            candidate = candidate,
            existingSidecar = _state.value.usenetCandidates,
        )
        _state.update { state ->
            state.copy(usenetCandidates = mergeSidecarRow(state.usenetCandidates, outcome.row))
        }
        if (outcome !is ResolveOutcome.Warming) return outcome
        val jobId = outcome.jobId ?: return outcome
        val poller = usenetJobPoller ?: return outcome

        val terminal = awaitPollTerminal(
            poller = poller,
            contentId = contentId,
            candidate = candidate,
            jobId = jobId,
            seedRow = outcome.row,
        )
        _state.update { state ->
            state.copy(usenetCandidates = mergeSidecarRow(state.usenetCandidates, terminal.row))
        }
        return when (terminal) {
            is PollOutcome.Ready -> ResolveOutcome.Ready(row = terminal.row, stream = terminal.stream)
            is PollOutcome.Failed, is PollOutcome.TimedOut -> ResolveOutcome.Failed(row = terminal.row)
            // StillWarming isn't terminal; awaitPollTerminal filters it
            // out before returning. Defensive branch only.
            is PollOutcome.StillWarming -> ResolveOutcome.Failed(row = terminal.row)
        }
    }

    /**
     * Bridge between the callback-driven poller and the suspend-based
     * chain loop. Sidecar is updated on every tick (including
     * StillWarming). Only terminal ticks complete the deferred.
     *
     * Stale-tick guard: if the user has moved to a different candidate,
     * the tick is dropped without updating state or completing. The
     * chain's own cancellation handles cleanup.
     */
    private suspend fun awaitPollTerminal(
        poller: UsenetJobPoller,
        contentId: String,
        candidate: UsenetCandidatePayload,
        jobId: String,
        seedRow: UsenetCandidateUiModel,
    ): PollOutcome {
        val deferred = kotlinx.coroutines.CompletableDeferred<PollOutcome>()
        val pollJob = poller.startPolling(
            contentId = contentId,
            candidate = candidate,
            jobId = jobId,
            scope = scope,
            seedRow = seedRow,
            onOutcome = { outcome ->
                if (activeUsenetCandidateId != outcome.row.candidateId) {
                    // User has moved on; suppress every emission for
                    // this stale candidate, including StillWarming so
                    // the sidecar doesn't churn pointlessly.
                    return@startPolling
                }
                _state.update { s ->
                    s.copy(usenetCandidates = mergeSidecarRow(s.usenetCandidates, outcome.row))
                }
                if (outcome !is PollOutcome.StillWarming && !deferred.isCompleted) {
                    deferred.complete(outcome)
                }
            },
        )
        return try {
            deferred.await()
        } finally {
            pollJob.cancel()
        }
    }

    /**
     * UI layer calls this after it has staged [usenetPlaybackIntent]'s
     * headers + handed the opaque URL to the player. Resetting the field
     * prevents a recomposition from re-launching the player for the same
     * intent. Also tears down the resolve chain / any active poll — the
     * user is leaving the sheet for playback, so nothing on the sheet
     * still needs polling work.
     */
    fun consumeUsenetPlaybackIntent() {
        val hadIntent = _state.value.usenetPlaybackIntent != null
        if (hadIntent) {
            val contentId = currentUsenetContentIdOrNull()
            val candidateId = activeUsenetCandidateId
            val startedAt = activeUsenetSelectionStartedAt
            val timeToPlayMs = if (startedAt != null) {
                Clock.System.now().toEpochMilliseconds() - startedAt
            } else -1L
            emitUsenetTelemetry(
                event = UsenetTelemetryEvents.PLAYBACK_STARTED,
                attributes = contentCandidateAttrs(contentId, candidateId) + mapOf(
                    UsenetTelemetryKeys.TIME_TO_PLAY_BUCKET to timeBucket(timeToPlayMs),
                ),
            )
        }
        _state.update { if (it.usenetPlaybackIntent == null) it else it.copy(usenetPlaybackIntent = null) }
        if (hadIntent) cancelUsenetResolveAndPoll(cancelBackend = false)
    }

    /**
     * Explicit VM-clear hook for platforms/call-sites that can invoke
     * it on screen destroy (Android: `DisposableEffect`, iOS: the
     * wrapper's deinit path). No existing lifecycle-adjacent signature
     * is widened — this is a standalone method call-sites can opt into.
     *
     * Cancels any active Usenet work on the backend and locally. Does
     * not touch other VM state so the method is safe to invoke multiple
     * times.
     */
    fun clear() {
        cancelUsenetResolveAndPoll()
    }

    private fun cancelUsenetResolveAndPoll(cancelBackend: Boolean = true) {
        currentUsenetResolveJob?.cancel()
        currentUsenetResolveJob = null
        activeUsenetCandidateId = null
        activeUsenetSelectionStartedAt = null
        activeUsenetFallbackIndex = 0
        initialUsenetCandidateId = null
        if (usenetJobPoller != null) {
            scope.launch { usenetJobPoller.cancelActive(cancelOnBackend = cancelBackend) }
        }
    }

    private fun currentUsenetContentIdOrNull(): String? {
        val type = currentType ?: return null
        val tmdbId = currentId ?: return null
        return formatUsenetContentId(
            type = type,
            tmdbId = tmdbId,
            season = _state.value.streamContextSeason,
            episode = _state.value.streamContextEpisode,
        )
    }

    private fun emitUsenetTelemetry(event: String, attributes: Map<String, String>) {
        val sink = telemetry ?: return
        runCatching { sink.emit(event, attributes) }
    }

    private fun mergeSidecarRow(
        existing: UsenetCandidateStates,
        row: UsenetCandidateUiModel,
    ): UsenetCandidateStates {
        if (existing[row.candidateId] === row) return existing
        return existing + (row.candidateId to row)
    }

    /**
     * Fire a single-shot prewarm for the top Usenet candidates currently
     * in state. Dedup lives inside [UsenetWarmCoordinator]; this function
     * can safely be called from multiple paths within fetchStreams. Runs
     * as fire-and-forget on [scope] so it never blocks the stream flow.
     */
    private fun maybePrewarmUsenetCandidates(season: Int?, episode: Int?) {
        val coordinator = usenetWarmCoordinator ?: return
        val type = currentType ?: return
        val tmdbId = currentId ?: return
        val contentId = formatUsenetContentId(
            type = type,
            tmdbId = tmdbId,
            season = season,
            episode = episode,
        ) ?: return
        val candidates = _state.value.streams
            .asSequence()
            .mapNotNull { it.usenetCandidatePayloadOrNull() }
            .distinctBy { it.candidateId }
            .take(UsenetWarmCoordinator.MAX_PREWARM_CANDIDATES)
            .toList()
        if (candidates.isEmpty()) return
        scope.launch {
            coordinator.prewarm(contentId = contentId, candidates = candidates)
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
            ?: directUrl
            ?: magnetUrl
            ?: infoHash
            ?: "${addonName}|${title}|${quality}|${source.orEmpty()}"
    }

    private fun ParsedStream.isInstantPlaybackCandidate(): Boolean {
        return isCached || directUrl != null
    }

    private fun removeFailedStreamCandidate(stream: ParsedStream) {
        val failedKey = stream.presentationKey()
        _state.update { current ->
            current.copy(
                streams = current.streams.filterNot { it.presentationKey() == failedKey },
            )
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
        val apiKey = settings?.getDebridApiKey() ?: ""
        // Nullable provider: addon-hosted streams (Panda + cloud download
        // client) play without any local debrid key. The stream repo decides
        // whether a provider is required based on the stream shape and
        // throws if one is missing for a hoster URL or torrent.
        val provider: DebridServiceType? = if (apiKey.isBlank()) null else settings?.getDebridProvider()

        _state.update {
            it.copy(
                isResolving = true,
                resolveError = null,
                autoPlayStream = stream,
                autoPlayMessage = resolvingStatusMessage(stream, provider),
                fallbackAttempt = attemptIndex,
            )
        }

        try {
            println("TORVE_AUTORESOLVE: attempt=$attemptIndex hasHash=${stream.infoHash != null} provider=$provider keyLen=${apiKey.length}")
            val resolved = withTimeoutOrNull(90_000L) {
                streamRepo.resolveStream(stream, provider, apiKey)
            }
            if (resolved == null) {
                com.torve.data.addon.StreamRuntimeTelemetry.recordStartupTimeout(hostKey, 90_000L)
                streamRepo.reportPlaybackOutcome(stream, provider, success = false)
                _state.update {
                    it.copy(
                        autoPlayMessage = "Stream timed out, trying next...",
                        fallbackAttempt = attemptIndex + 1,
                    )
                }
                autoResolveStream(streams, attemptIndex + 1, preferences)
                return
            }
            println("TORVE_AUTORESOLVE: Success resolvedUrl=${resolved.url.isNotBlank()}")
            com.torve.data.addon.StreamRuntimeTelemetry.recordStartupSuccess(hostKey, 0L)
            val url = resolved.url.orEmpty()
            // Non-addon URLs are playable immediately — same as the fast
            // path below. Addon URLs go through probe; Ready plays, Preparing
            // locks in on this stream (no fallback), Failed falls through.
            if (!stream.isAddonHostedUrl() || url.isBlank()) {
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
                return
            }
            when (val readiness = streamRepo.probeStreamReadiness(url)) {
                is com.torve.domain.repository.StreamReadiness.Ready -> {
                    _state.update {
                        it.copy(
                            resolvedStream = resolved.copy(url = readiness.finalUrl),
                            isResolving = false,
                            showStreamPicker = false,
                            autoPlayMessage = if (attemptIndex > 0) {
                                "Switched to a more stable source"
                            } else {
                                buildAutoPlayMessage(stream)
                            },
                        )
                    }
                }
                com.torve.domain.repository.StreamReadiness.Preparing -> {
                    _state.update { it.copy(autoPlayMessage = null) }
                    startPreparingLoop(stream, url, resolved)
                }
                is com.torve.domain.repository.StreamReadiness.Failed -> {
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
        } catch (e: Exception) {
            com.torve.data.addon.StreamRuntimeTelemetry.recordFatalError(hostKey)
            streamRepo.reportPlaybackOutcome(stream, provider, success = false)
            // Auth failure: retrying with different streams won't help — surface
            // immediately so the user knows to reconnect their debrid account.
            if (e.shouldStopAutoResolveAndShowUser()) {
                _state.update {
                    it.copy(
                        isResolving = false,
                        autoPlayMessage = null,
                        showStreamPicker = true,
                        streamsError = streamResolveErrorMessage(e),
                    )
                }
                return
            }
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
        val apiKey = settings?.getDebridApiKey() ?: ""
        // Nullable provider — addon-hosted streams resolve without a local key.
        val provider: DebridServiceType? = if (apiKey.isBlank()) null else settings?.getDebridProvider()

        _state.update {
            it.copy(
                isResolving = true,
                resolveError = null,
                autoPlayStream = stream,
                autoPlayMessage = resolvingStatusMessage(stream, provider),
                fallbackAttempt = attemptIndex,
            )
        }

        return try {
            val resolved = withTimeoutOrNull(90_000L) {
                streamRepo.resolveStream(stream, provider, apiKey)
            }
            if (resolved == null) {
                com.torve.data.addon.StreamRuntimeTelemetry.recordStartupTimeout(hostKey, 90_000L)
                streamRepo.reportPlaybackOutcome(stream, provider, success = false)
                _state.update {
                    it.copy(
                        autoPlayMessage = "Stream timed out, trying next...",
                        fallbackAttempt = attemptIndex + 1,
                    )
                }
                return autoResolveStreamProgressive(
                    streams = streams,
                    attemptIndex = attemptIndex + 1,
                    preferences = preferences,
                    failureBehavior = failureBehavior,
                    attemptedKeys = currentAttemptedKeys,
                )
            }
            com.torve.data.addon.StreamRuntimeTelemetry.recordStartupSuccess(hostKey, 0L)
            val url = resolved.url.orEmpty()
            if (!stream.isAddonHostedUrl() || url.isBlank()) {
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
                return AutoResolveResult(resolved = true, attemptedKeys = currentAttemptedKeys)
            }
            when (val readiness = streamRepo.probeStreamReadiness(url)) {
                is com.torve.domain.repository.StreamReadiness.Ready -> {
                    _state.update {
                        it.copy(
                            resolvedStream = resolved.copy(url = readiness.finalUrl),
                            isResolving = false,
                            showStreamPicker = false,
                            autoPlayMessage = if (attemptIndex > 0) {
                                "Switched to a more stable source"
                            } else {
                                buildAutoPlayMessage(stream)
                            },
                        )
                    }
                    AutoResolveResult(resolved = true, attemptedKeys = currentAttemptedKeys)
                }
                com.torve.domain.repository.StreamReadiness.Preparing -> {
                    _state.update { it.copy(autoPlayMessage = null) }
                    startPreparingLoop(stream, url, resolved)
                    AutoResolveResult(resolved = false, attemptedKeys = currentAttemptedKeys)
                }
                is com.torve.domain.repository.StreamReadiness.Failed -> {
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
        } catch (e: Exception) {
            com.torve.data.addon.StreamRuntimeTelemetry.recordFatalError(hostKey)
            streamRepo.reportPlaybackOutcome(stream, provider, success = false)
            if (e.shouldStopAutoResolveAndShowUser()) {
                _state.update {
                    it.copy(
                        autoPlayMessage = null,
                        isResolving = false,
                        showStreamPicker = true,
                        streamsError = streamResolveErrorMessage(e),
                    )
                }
                return AutoResolveResult(
                    resolved = false,
                    attemptedKeys = currentAttemptedKeys,
                )
            }
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

    private fun Exception.shouldStopAutoResolveAndShowUser(): Boolean {
        val message = this.message.orEmpty()
        return (this is StreamHandoffApiException && errorCode in STREAM_HANDOFF_BLOCKING_ERROR_CODES) ||
            this is DebridMissingException ||
            this is DebridNeedsReconnectException ||
            this is DebridServiceUnavailableException ||
            message.contains("Session expired", ignoreCase = true) ||
            message.contains("reconnect", ignoreCase = true) ||
            message.contains("infringing_file", ignoreCase = true)
    }

    private fun resolvingStatusMessage(stream: ParsedStream, provider: DebridServiceType?): String {
        return when {
            stream.accelerationProvenanceKind == com.torve.domain.model.CandidateProvenanceKind.USENET_NZBDAV ->
                "Preparing Usenet stream..."
            stream.isAddonHostedUrl() -> "Checking cloud stream readiness..."
            provider == DebridServiceType.REAL_DEBRID -> "Resolving with Real-Debrid..."
            provider != null -> "Resolving with ${provider.label}..."
            stream.infoHash != null -> "Connect Real-Debrid in Panda to resolve this torrent stream."
            else -> "Resolving stream..."
        }
    }

    private fun streamResolveErrorKey(error: Exception): String {
        if (error is StreamHandoffApiException) {
            return when (error.errorCode) {
                "device_required", "device_not_registered" ->
                    com.torve.presentation.error.UserFacingError.DEVICE_REQUIRED.messageKey
                "device_not_authorized" ->
                    com.torve.presentation.error.UserFacingError.DEVICE_NOT_AUTHORIZED.messageKey
                "premium_required" ->
                    com.torve.presentation.error.UserFacingError.PREMIUM_REQUIRED.messageKey
                "rate_limited" ->
                    com.torve.presentation.error.UserFacingError.RATE_LIMITED.messageKey
                "stream_expired", "invalid_handoff" ->
                    com.torve.presentation.error.UserFacingError.PLAYBACK_LINK_EXPIRED.messageKey
                "stream_reference_required",
                "stream_reference_not_found",
                -> com.torve.presentation.error.UserFacingError.STREAM_REFERENCE_UNAVAILABLE.messageKey
                "stream_handoff_unavailable",
                -> com.torve.presentation.error.UserFacingError.STREAM_RESOLVE_FAILED.messageKey
                else -> com.torve.presentation.error.UserFacingError.STREAM_RESOLVE_FAILED.messageKey
            }
        }
        val message = error.message.orEmpty()
        return when {
            error is DebridMissingException ->
                com.torve.presentation.error.UserFacingError.STREAM_REAL_DEBRID_MISSING.messageKey
            error is DebridNeedsReconnectException ->
                com.torve.presentation.error.UserFacingError.STREAM_REAL_DEBRID_RECONNECT.messageKey
            error is DebridSourceBlockedException ->
                com.torve.presentation.error.UserFacingError.STREAM_REAL_DEBRID_SOURCE_BLOCKED.messageKey
            error is DebridNoCachedStreamException ->
                com.torve.presentation.error.UserFacingError.STREAM_NO_CACHED_SOURCE.messageKey
            error is DebridServiceUnavailableException ->
                com.torve.presentation.error.UserFacingError.INTEGRATION_SERVICE_UNAVAILABLE.messageKey
            message.contains("token refresh failed", ignoreCase = true) ||
                message.contains("refresh failed", ignoreCase = true) ->
                com.torve.presentation.error.UserFacingError.STREAM_REAL_DEBRID_REFRESH_FAILED.messageKey
            message.contains("401", ignoreCase = true) ||
                message.contains("403", ignoreCase = true) ||
                message.contains("invalid api key", ignoreCase = true) ->
                com.torve.presentation.error.UserFacingError.STREAM_REAL_DEBRID_RECONNECT.messageKey
            message.contains("Session expired", ignoreCase = true) ||
                message.contains("re-authenticate", ignoreCase = true) ||
                message.contains("reconnect", ignoreCase = true) ->
                com.torve.presentation.error.UserFacingError.STREAM_REAL_DEBRID_RECONNECT.messageKey
            message.contains("not cached", ignoreCase = true) ||
                message.contains("isn't cached", ignoreCase = true) ||
                message.contains("none produced a playable URL", ignoreCase = true) ->
                com.torve.presentation.error.UserFacingError.STREAM_NO_CACHED_SOURCE.messageKey
            message.contains("blocked this source", ignoreCase = true) ||
                message.contains("infringing_file", ignoreCase = true) ->
                com.torve.presentation.error.UserFacingError.STREAM_REAL_DEBRID_SOURCE_BLOCKED.messageKey
            else -> com.torve.presentation.error.UserFacingError.STREAM_RESOLVE_FAILED.messageKey
        }
    }

    private fun streamResolveErrorMessage(error: Exception): String {
        return when (streamResolveErrorKey(error)) {
            com.torve.presentation.error.UserFacingError.STREAM_REAL_DEBRID_MISSING.messageKey ->
                "Connect Real-Debrid in Panda to use this stream."
            com.torve.presentation.error.UserFacingError.STREAM_REAL_DEBRID_RECONNECT.messageKey ->
                "Real-Debrid needs reconnecting. Open Settings > Advanced > Panda."
            com.torve.presentation.error.UserFacingError.STREAM_REAL_DEBRID_SOURCE_BLOCKED.messageKey ->
                "Real-Debrid blocked this source. Try another source."
            com.torve.presentation.error.UserFacingError.STREAM_NO_CACHED_SOURCE.messageKey ->
                "No cached stream is available for this title."
            com.torve.presentation.error.UserFacingError.DEVICE_REQUIRED.messageKey ->
                "This device needs to be set up before playback."
            com.torve.presentation.error.UserFacingError.DEVICE_NOT_AUTHORIZED.messageKey ->
                "This device is not authorized for playback. Manage your devices in account settings."
            com.torve.presentation.error.UserFacingError.PREMIUM_REQUIRED.messageKey ->
                "This feature is available to everyone."
            com.torve.presentation.error.UserFacingError.RATE_LIMITED.messageKey ->
                "Too many requests. Please wait and try again."
            com.torve.presentation.error.UserFacingError.PLAYBACK_LINK_EXPIRED.messageKey ->
                "Playback link expired. Try playing this source again."
            com.torve.presentation.error.UserFacingError.STREAM_REFERENCE_UNAVAILABLE.messageKey ->
                "This source is no longer available. Refresh sources or try another one."
            else -> "Could not resolve this stream."
        }
    }

    fun resolveStream(stream: ParsedStream, provider: DebridServiceType?, apiKey: String) {
        scope.launch {
            cancelPreparingLoop()
            _state.update {
                it.copy(
                    isResolving = true,
                    resolveError = null,
                    autoPlayMessage = resolvingStatusMessage(stream, provider),
                    preparing = null,
                )
            }
            println("TORVE_RESOLVE: Starting resolve hasHash=${stream.infoHash != null} hasUrl=${stream.directUrl != null} provider=$provider keyLen=${apiKey.length}")
            try {
                val resolved = withTimeoutOrNull(90_000L) {
                    streamRepo.resolveStream(stream, provider, apiKey)
                }
                if (resolved == null) {
                    println("TORVE_RESOLVE: Timed out after 90s")
                    streamRepo.reportPlaybackOutcome(stream, provider, success = false)
                    _state.update {
                        it.copy(
                            isResolving = false,
                            autoPlayMessage = null,
                            resolveError = com.torve.presentation.error.UserFacingError.STREAM_RESOLVE_TIMEOUT.messageKey,
                        )
                    }
                    return@launch
                }
                dispatchResolved(stream, provider, apiKey, resolved)
            } catch (e: Exception) {
                println("TORVE_RESOLVE: Exception ${e::class.simpleName}")
                streamRepo.reportPlaybackOutcome(stream, provider, success = false)
                if (e is DebridSourceBlockedException || e is DebridNoCachedStreamException) {
                    removeFailedStreamCandidate(stream)
                }
                _state.update {
                    it.copy(
                        isResolving = false,
                        autoPlayMessage = null,
                        resolveError = streamResolveErrorKey(e),
                    )
                }
            }
        }
    }

    /**
     * Route a freshly-resolved stream based on whether it needs a readiness
     * probe. Addon-hosted URLs (Panda's `/u/<token>/…`) go through the
     * probe; torrent/debrid URLs launch the player immediately — they're
     * already playable the moment the debrid client hands them back.
     *
     * Must NEVER set [DetailUiState.resolvedStream] before the probe says
     * Ready. That's the invariant that keeps ExoPlayer from ever seeing
     * Panda's 504 nzb_not_ready response.
     */
    private suspend fun dispatchResolved(
        stream: ParsedStream,
        provider: DebridServiceType?,
        apiKey: String,
        resolved: com.torve.domain.model.ResolvedStream,
    ) {
        val url = resolved.url.orEmpty()
        if (url.isBlank()) {
            _state.update {
                it.copy(
                    isResolving = false,
                    autoPlayMessage = null,
                    resolveError = com.torve.presentation.error.UserFacingError.STREAM_RESOLVE_FAILED.messageKey,
                )
            }
            return
        }
        if (!stream.isAddonHostedUrl()) {
            _state.update {
                it.copy(
                    resolvedStream = resolved,
                    isResolving = false,
                    autoPlayMessage = null,
                    showStreamPicker = false,
                    preparing = null,
                )
            }
            return
        }
        when (val readiness = streamRepo.probeStreamReadiness(url)) {
            is com.torve.domain.repository.StreamReadiness.Ready -> {
                _state.update {
                    it.copy(
                        resolvedStream = resolved.copy(url = readiness.finalUrl),
                        isResolving = false,
                        autoPlayMessage = null,
                        showStreamPicker = false,
                        preparing = null,
                    )
                }
            }
            com.torve.domain.repository.StreamReadiness.Preparing -> {
                _state.update { it.copy(isResolving = false, autoPlayMessage = null) }
                startPreparingLoop(stream, url, resolved)
            }
            is com.torve.domain.repository.StreamReadiness.Failed -> {
                streamRepo.reportPlaybackOutcome(stream, provider, success = false)
                _state.update {
                    it.copy(
                        isResolving = false,
                        autoPlayMessage = null,
                        resolveError = readiness.reason,
                    )
                }
            }
        }
    }

    /**
     * Coroutine that re-probes the same stream URL every 15s until it's
     * ready, fails, or the 5-minute budget is exhausted. Stored as a Job
     * so [cancelPreparingLoop] can interrupt it mid-delay — which is the
     * mechanism behind the overlay's Cancel button and any re-entry
     * (e.g. the user picks a different stream while the loop is running).
     */
    private var preparingJob: Job? = null

    private fun startPreparingLoop(
        stream: ParsedStream,
        url: String,
        resolved: com.torve.domain.model.ResolvedStream,
    ) {
        preparingJob?.cancel()
        val startedAt = Clock.System.now().toEpochMilliseconds()
        // Best-effort display name. ResolvedStream.service carries the
        // DebridServiceType enum when unrestrict ran, but for Panda addon-
        // hosted URLs it's null — fall back to the stream's source label
        // (Panda writes the indexer name there) or a generic string. The
        // body copy reads fine either way.
        val serviceName = resolved.service?.label?.takeIf { it.isNotBlank() }
            ?: stream.source?.takeIf { it.isNotBlank() }
            ?: "Your cloud service"
        _state.update {
            it.copy(
                preparing = PreparingStreamState(
                    title = stream.title,
                    startedAt = startedAt,
                    attempt = 1,
                    serviceName = serviceName,
                    canCancel = true,
                ),
                resolveError = null,
                autoPlayMessage = null,
                // Close the source picker so the user doesn't accidentally
                // pick a different stream while the current one is warming
                // up. The preparing overlay is the full-screen surface now.
                showStreamPicker = false,
            )
        }
        preparingJob = scope.launch {
            repeat(PREPARING_MAX_ATTEMPTS) { attemptIdx ->
                delay(PREPARING_PROBE_INTERVAL_MS)
                // Re-read latest PreparingStreamState each tick so the attempt
                // counter can advance independently of the loop's local idx.
                val result = try {
                    streamRepo.probeStreamReadiness(url)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    com.torve.domain.repository.StreamReadiness.Failed(
                        com.torve.presentation.error.UserFacingError.STREAM_RESOLVE_FAILED.messageKey,
                    )
                }
                when (result) {
                    is com.torve.domain.repository.StreamReadiness.Ready -> {
                        _state.update {
                            it.copy(
                                preparing = null,
                                resolvedStream = resolved.copy(url = result.finalUrl),
                                showStreamPicker = false,
                                resolveError = null,
                            )
                        }
                        return@launch
                    }
                    com.torve.domain.repository.StreamReadiness.Preparing -> {
                        _state.update { s ->
                            val current = s.preparing ?: return@update s
                            s.copy(preparing = current.copy(attempt = attemptIdx + 2))
                        }
                    }
                    is com.torve.domain.repository.StreamReadiness.Failed -> {
                        _state.update {
                            it.copy(
                                preparing = null,
                                resolveError = result.reason,
                            )
                        }
                        return@launch
                    }
                }
            }
            // Budget exhausted — the cloud client hasn't produced a URL in
            // 5 min. Clear preparing, surface a specific timeout message so
            // the user can pick an alternate source.
            _state.update {
                it.copy(
                    preparing = null,
                    resolveError = "error_stream_preparing_timeout",
                )
            }
        }
    }

    /** Overlay Cancel / back navigation entry point. Safe to call when idle. */
    fun cancelPreparing() {
        cancelPreparingLoop()
        _state.update { state ->
            state.copy(
                preparing = null,
                isResolving = false,
                autoPlayMessage = null,
                showStreamPicker = state.showStreamPicker || state.streams.isNotEmpty(),
            )
        }
    }

    private fun cancelPreparingLoop() {
        preparingJob?.cancel()
        preparingJob = null
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
        val apiKey = settings?.getDebridApiKey() ?: ""
        // Nullable provider — addon-hosted streams resolve without a local key.
        val provider: DebridServiceType? = if (apiKey.isBlank()) null else settings?.getDebridProvider()
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
            } catch (e: Exception) {
                com.torve.data.addon.StreamRuntimeTelemetry.recordFatalError(hostKey)
                streamRepo.reportPlaybackOutcome(fallback, provider, success = false)
                _state.update {
                    it.copy(
                        isResolving = false,
                        showStreamPicker = true,
                        resolveError = if (e.shouldStopAutoResolveAndShowUser()) {
                            streamResolveErrorKey(e)
                        } else {
                            it.resolveError
                        },
                    )
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
        // If a Usenet row was mid-resolve (PREPARING / never reached
        // Ready) when the user dismissed, emit the abandon event. Play
        // already fires via consumeUsenetPlaybackIntent; no Ready →
        // no abandon.
        val active = activeUsenetCandidateId
        if (active != null) {
            val row = _state.value.usenetCandidates[active]
            if (row?.availabilityState != com.torve.data.usenet.model.UsenetAvailability.READY) {
                val contentId = currentUsenetContentIdOrNull()
                emitUsenetTelemetry(
                    event = UsenetTelemetryEvents.USER_ABANDONED_BEFORE_READY,
                    attributes = contentCandidateAttrs(contentId, active) + mapOf(
                        UsenetTelemetryKeys.ABANDON_STATE to
                            UsenetTelemetryState.from(row?.availabilityState).name,
                    ),
                )
            }
        }
        // Sheet dismiss is an unambiguous "done" — stop any active
        // Usenet poll and cancel the resolve chain. Active selection is
        // cleared so a stale poll tick can't re-enter the VM via the
        // sidecar update path.
        cancelUsenetResolveAndPoll()
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
            val now = Clock.System.now().toEpochMilliseconds()
            recordManualWatchedSession(
                item = item,
                eventAt = now,
                title = item.title,
                runtimeMinutes = item.runtime,
            )
            val tmdbId = item.tmdbId ?: return@launch
            val localProgressPersisted = saveWholeItemWatchedProgress(item)
            if (item.type == MediaType.MOVIE && localProgressPersisted) {
                return@launch
            }
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
            clearWholeItemWatchedState(item)
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
        val seasonDetail = _state.value.seasonDetail?.takeIf { it.seasonNumber == seasonNumber }
        val episodeCount = seasonDetail?.episodes?.size
            ?: item.seasons.find { it.seasonNumber == seasonNumber }?.episodeCount
            ?: return

        scope.launch {
            try {
                val now = Clock.System.now().toEpochMilliseconds()
                for (ep in 1..episodeCount) {
                    val episode = seasonDetail?.episodes?.getOrNull(ep - 1)
                    val entry = WatchHistoryEntry(
                        id = "${item.id}_${episodeKey(seasonNumber, ep)}",
                        mediaId = item.id,
                        mediaType = MediaType.SERIES.name,
                        title = episode?.name ?: "Episode $ep",
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        watchedAt = now,
                        durationWatchedMs = 0,
                        seasonNumber = seasonNumber,
                        episodeNumber = ep,
                        showTitle = item.title,
                    )
                    watchHistoryRepo.record(entry)
                    recordManualWatchedSession(
                        item = item,
                        eventAt = now,
                        seasonNumber = seasonNumber,
                        episodeNumber = ep,
                        title = episode?.name ?: "Episode $ep",
                        runtimeMinutes = episode?.runtime,
                    )
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

    private suspend fun saveWholeItemWatchedProgress(item: com.torve.domain.model.MediaItem): Boolean {
        return try {
            watchProgressRepo.saveProgress(
                WatchProgress(
                    mediaId = item.id,
                    mediaType = item.type,
                    title = item.title,
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    positionMs = (MARKED_WATCHED_DURATION_MS * MARKED_WATCHED_PROGRESS_RATIO).toLong(),
                    durationMs = MARKED_WATCHED_DURATION_MS,
                    showTitle = if (item.type == MediaType.SERIES) item.title else null,
                ),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun recordManualWatchedSession(
        item: MediaItem,
        eventAt: Long,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        title: String,
        runtimeMinutes: Int?,
    ) {
        watchSessionRecorder?.recordManualCompleted(
            identity = WatchSessionMediaIdentity(
                mediaId = item.id,
                mediaType = item.type,
                title = title,
                showId = if (item.type == MediaType.SERIES) item.id else null,
                showTitle = if (item.type == MediaType.SERIES) item.title else null,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                tmdbId = item.tmdbId,
                imdbId = item.imdbId,
            ),
            eventAt = eventAt,
            runtimeMs = runtimeMinutes?.takeIf { it > 0 }?.toLong()?.times(60_000L),
        )
    }

    private suspend fun clearWholeItemWatchedState(item: com.torve.domain.model.MediaItem) {
        try {
            watchProgressRepo.deleteProgress(item.id)
            watchHistoryRepo.getForMedia(item.id)
                .filter { it.seasonNumber == null && it.episodeNumber == null }
                .forEach { watchHistoryRepo.delete(it.id) }
        } catch (_: Exception) {
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
                val episodeRuntime = _state.value.seasonDetail?.episodes
                    ?.getOrNull(episodeNumber - 1)?.runtime
                val now = Clock.System.now().toEpochMilliseconds()
                val entry = WatchHistoryEntry(
                    id = "${item.id}_${episodeKey(seasonNumber, episodeNumber)}",
                    mediaId = item.id,
                    mediaType = MediaType.SERIES.name,
                    title = epTitle,
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    watchedAt = now,
                    durationWatchedMs = 0,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    showTitle = item.title,
                )
                watchHistoryRepo.record(entry)
                recordManualWatchedSession(
                    item = item,
                    eventAt = now,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    title = epTitle,
                    runtimeMinutes = episodeRuntime,
                )
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
                // Update UI state immediately so it doesn't flicker back on reload
                val newWatched = _state.value.watchedEpisodes - episodeKey(seasonNumber, episodeNumber)
                _state.update { it.copy(watchedEpisodes = newWatched, watchProgress = null) }
                resolveNextEpisode()
            } catch (_: Exception) { }
            // Remove from Trakt so syncFromTrakt cannot re-import this episode.
            val tmdbId = item.tmdbId ?: return@launch
            val ids = com.torve.data.trakt.TraktIds(tmdb = tmdbId)
            val season = com.torve.data.trakt.TraktHistorySeasonEntry(
                number = seasonNumber,
                episodes = listOf(com.torve.data.trakt.TraktHistoryEpisodeEntry(number = episodeNumber)),
            )
            val show = com.torve.data.trakt.TraktHistoryShow(ids = ids, seasons = listOf(season))
            try {
                traktApi.removeFromHistory(com.torve.data.trakt.TraktRemoveHistoryBody(shows = listOf(show)))
            } catch (_: Exception) {
                traktSyncRepo.enqueueEpisodeHistoryRemove(
                    tmdbId = tmdbId,
                    imdbId = item.imdbId,
                    season = seasonNumber,
                    episode = episodeNumber,
                )
            }
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
