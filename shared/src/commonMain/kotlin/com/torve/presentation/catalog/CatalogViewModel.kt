package com.torve.presentation.catalog

import com.torve.data.ai.AiProvider
import com.torve.data.ai.KeywordSearchService
import com.torve.data.contentpolicy.ContentPolicyCacheInvalidationCoordinator
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.data.metadata.TmdbGenres
import com.torve.data.network.catalogContentLoadErrorMessage
import com.torve.data.network.sanitizeNetworkDiagnosticText
import com.torve.domain.model.ContentAccessContext
import com.torve.domain.model.ContentPolicyState
import com.torve.domain.model.ContentSourceType
import com.torve.domain.model.MediaItem
import com.torve.domain.model.Genre
import com.torve.domain.model.MediaType
import com.torve.domain.model.PagedResult
import com.torve.domain.model.StreamingProviderCandidate
import com.torve.domain.model.dedupeByStableKey
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.model.resolveStreamingProviderIds
import com.torve.domain.discovery.tmdbDiscoverRating
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.platform.torveVerboseLog
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Shared catalog ViewModel used by both Movies and TV Shows screens.
 * The [mediaType] determines whether we query "movie" or "tv".
 */
class CatalogViewModel(
    private val metadataRepo: MetadataRepository,
    private val mediaType: String, // "movie" or "tv"
    private val watchProgressRepo: WatchProgressRepository? = null,
    private val keywordSearchService: KeywordSearchService? = null,
    private val prefsRepo: PreferencesRepository? = null,
    private val ratingsEnricher: RatingsEnricher? = null,
    private val integrationSecretStore: IntegrationSecretStore? = null,
    private val contentPolicyRepository: ContentPolicyRepository? = null,
    private val contentPolicyFilter: ContentPolicyFilter = ContentPolicyFilter(),
    invalidationCoordinator: ContentPolicyCacheInvalidationCoordinator? = null,
    private val initialProviderId: Int? = null,
    private val initialProviderName: String? = null,
    private val initialProviderGroup: Map<Int, String>? = null,
    /**
     * Optional pre-paginated top-1000 cache. When present and populated for
     * the active genre + media type, the catalog grid is rendered straight
     * from this local SQL store on click, no network round-trip. Falls back
     * to live discover() when the cache is empty (first launch before the
     * background worker has populated, or for niche/no-genre filters).
     */
    private val catalogTopCache: com.torve.data.catalog.CatalogTopCacheRepository? = null,
) {
    private data class CatalogShelvesLoad(
        val continueWatching: List<com.torve.domain.model.WatchProgress>,
        val trending: List<MediaItem>,
        val popular: List<MediaItem>,
        val topRated: List<MediaItem>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = contentPolicyFilter.profileState(CatalogUiState(providerId = initialProviderId))
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()
    val genres: List<Genre> = (if (mediaType == "tv") TmdbGenres.TV_GENRES else TmdbGenres.MOVIE_GENRES)
        .map { (id, name) -> Genre(id, name) }

    // Cancellation handles for loadCatalog + its enrichment pass.
    // Without these, rapid genre-pill clicks (Comedy → Drama → Action)
    // produced several races: the slowest fetch finished last and
    // overwrote whatever the user actually wanted to see, and stale
    // enrichment reorders kept rewriting state for a previous genre.
    // We keep separate refs because enrichment runs after loadCatalog
    // returns; cancelling loadCatalog alone wouldn't stop in-flight
    // enrichment from a previous genre.
    private var catalogLoadJob: kotlinx.coroutines.Job? = null
    private var catalogEnrichJob: kotlinx.coroutines.Job? = null
    private var catalogPageJob: kotlinx.coroutines.Job? = null
    private var searchPageJob: kotlinx.coroutines.Job? = null
    private var shelvesJob: kotlinx.coroutines.Job? = null
    private val discoverySnapshots = prefsRepo?.let { CatalogDiscoverySnapshotStore(it, mediaType) }
    private val providerSnapshots = prefsRepo?.takeIf { initialProviderGroup == null }?.let { ProviderCatalogSnapshotStore(it, mediaType) }
    private var resolvedProvider: Triple<Int, String, String>? = null
    private var discoverySnapshotJob: kotlinx.coroutines.Job? = null

    // A filter/provider change must rerun the same query, too.
    private val searchRequests = MutableStateFlow(0L)

    init {
        scope.launch {
            contentPolicyFilter.awaitProfileBoundary()
            loadCatalog()
            loadShelves()
        }
        scope.launch {
            contentPolicyFilter.profileOwners().distinctUntilChanged().drop(1).collect {
                _state.value = CatalogUiState(providerId = initialProviderId)
                loadCatalog(); loadShelves()
            }
        }
        scope.launch {
            contentPolicyFilter.discoveryChanges().collectLatest {
                contentPolicyFilter.awaitProfileBoundary()
                loadCatalog(); loadShelves()
            }
        }
        observeSearch()
        if (invalidationCoordinator != null) {
            scope.launch {
                invalidationCoordinator.events.collectLatest {
                    _state.value = CatalogUiState(providerId = _state.value.providerId)
                    loadCatalog()
                    loadShelves()
                }
            }
        }
    }

    private fun saveDiscoverySnapshot() {
        discoverySnapshotJob?.cancel()
        discoverySnapshotJob = scope.launch {
            kotlinx.coroutines.delay(100)
            discoverySnapshots?.write(_state.value)
        }
    }

    private fun loadShelves() {
        if (watchProgressRepo == null || _state.value.providerId != null) return
        shelvesJob?.cancel()
        shelvesJob = scope.launch {
            try {
                if (_state.value.trendingItems.isEmpty() && _state.value.popularItems.isEmpty() && _state.value.topRatedItems.isEmpty()) {
                    discoverySnapshots?.read()?.let { saved ->
                        _state.update { applyContentPolicy(it.copy(continueWatching = saved.continueWatching,
                            trendingItems = saved.trending, popularItems = saved.popular, topRatedItems = saved.topRated,
                            shelvesLoaded = true)) }
                    }
                }
                val targetType = if (mediaType == "tv") MediaType.SERIES else MediaType.MOVIE
                // Each ready row publishes independently. A slow/failing request must
                // not hide another row or erase its saved presentation.
                supervisorScope {
                    suspend fun attempt(block: suspend () -> Unit) {
                        try { block(); saveDiscoverySnapshot() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Retain the previous version of this row. */ }
                    }
                    launch { attempt {
                        val progress = watchProgressRepo.getInProgress(20).filter { it.mediaType == targetType }
                        _state.update { applyContentPolicy(it.copy(continueWatching = progress)) }
                    } }
                    suspend fun row(fetch: suspend (Int) -> com.torve.domain.model.PagedResult, update: (CatalogUiState, List<MediaItem>) -> CatalogUiState) {
                        attempt {
                            var cursor = 1
                            var items = emptyList<MediaItem>()
                            do {
                                val result = fetch(cursor)
                                val pageItems = kotlinx.coroutines.withContext(com.torve.util.ioDispatcher) { hydrateCachedRatings(result.items) }
                                items = (items + pageItems).dedupeByStableKey().take(20)
                                _state.update { applyContentPolicy(update(it, items)) }
                                saveDiscoverySnapshot()
                                cursor = maxOf(cursor + 1, result.page + 1)
                                if (!contentPolicyFilter.familyKidsProfile || result.page >= result.totalPages) break
                            } while (items.size < 20 && cursor <= 20)
                            // Enrich this rail independently; another rail cannot
                            // cancel it or replace an already visible prefix.
                            ratingsEnricher?.let { enricher ->
                                val key = integrationSecretStore?.get(IntegrationSecretKey.MDBLIST_API_KEY)
                                    ?: prefsRepo?.getString(SettingsViewModel.KEY_MDBLIST_API_KEY) ?: MdbListApi.DEFAULT_API_KEY
                                val enriched = enricher.enrichList(items, key)
                                _state.update { applyContentPolicy(update(it, enriched)) }
                            }
                        }
                    }
                    launch { row({ page -> if (contentPolicyFilter.familyKidsProfile) metadataRepo.getTrendingPaged(mediaType, page)
                        else metadataRepo.getTrending(mediaType, page).let { com.torve.domain.model.PagedResult(it, page, page, it.size) } }) { state, items -> state.copy(trendingItems = items) } }
                    launch { row({ page -> if (contentPolicyFilter.familyKidsProfile) metadataRepo.getPopularPaged(mediaType, page)
                        else metadataRepo.getPopular(mediaType, page).let { com.torve.domain.model.PagedResult(it, page, page, it.size) } }) { state, items -> state.copy(popularItems = items) } }
                    launch { row({ page -> if (contentPolicyFilter.familyKidsProfile) metadataRepo.getTopRatedPaged(mediaType, page)
                        else metadataRepo.getTopRated(mediaType, page).let { com.torve.domain.model.PagedResult(it, page, page, it.size) } }) { state, items -> state.copy(topRatedItems = items) } }
                }
                _state.update { it.copy(shelvesLoaded = true) }
                saveDiscoverySnapshot()
                launchHeroArtworkBackfill()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(shelvesLoaded = true) } }
        }
    }

    fun setProvider(providerId: Int) {
        catalogDiagLog("ACTION setProvider($providerId)")
        shelvesJob?.cancel()
        _state.update {
            it.copy(providerId = providerId, providerRegion = null, continueWatching = emptyList(),
                trendingItems = emptyList(), popularItems = emptyList(), topRatedItems = emptyList(),
                shelvesLoaded = false)
        }
        loadCatalog()
    }

    /** Reset the watch-provider filter back to "All providers". */
    fun clearProvider() {
        catalogDiagLog("ACTION clearProvider()")
        _state.update { it.copy(providerId = null, providerRegion = null) }
        loadCatalog()
        loadShelves()
    }

    fun setViewMode(mode: CatalogViewMode) {
        _state.update { it.copy(viewMode = mode) }
    }

    fun loadCatalog() = loadCatalog(retainItems = false)

    private fun loadCatalog(retainItems: Boolean) {
        // Cancel any prior in-flight load + enrichment so a slow
        // previous-genre fetch can't land after a faster newer-genre
        // fetch has already updated the grid.
        catalogLoadJob?.cancel()
        catalogEnrichJob?.cancel()
        catalogPageJob?.cancel()
        restartSearch()
        catalogLoadJob = scope.launch {
            torveVerboseLog {
                "CATALOG_TAB bootstrap_start mediaType=$mediaType category=${_state.value.selectedCategory}"
            }
            // Keep the current page mounted during filter changes; replace results in one update.
            _state.update {
                it.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    error = null,
                    currentPage = 1,
                    items = if (retainItems) it.items else emptyList(),
                    hasMore = false,
                )
            }
            try {
                val filter = _state.value.filter
                val genreId = _state.value.selectedGenreId
                val providerId = _state.value.providerId
                val region = providerId?.let { providerRegion() }
                val providerFingerprint = if (providerId != null && region != null) providerSnapshots?.fingerprint(_state.value, region) else null
                if (providerId != null && region != null && providerFingerprint != null) {
                    providerSnapshots?.read(providerId, providerFingerprint)?.let { saved ->
                        currentCoroutineContext().ensureActive()
                        resolvedProvider = Triple(providerId, region, saved.providerIds)
                        _state.update { applyContentPolicy(it.copy(items = saved.items, providerRegion = region,
                            currentPage = 1, totalPages = saved.totalPages, hasMore = false)) }
                    }
                }
                val providerFilter = resolveProviderFilter(providerId)
                val category = _state.value.selectedCategory

                // Pre-cache fast path DISABLED 2026-05-08. SQLite's
                // process-wide write lock blocks the cache reads while
                // the worker is mid-pass, and user-visible behavior
                // was that genre clicks (which would have used the
                // cache) fired but loadCatalog never reached its
                // diagnostic log line -- rapid clicks then cancelled
                // each other in a chain. The metadata cache keeps
                // populating in the background; we just don't read
                // from it from the catalog VM until SQLite is reworked
                // to WAL mode (concurrent reads during writes) or the
                // worker breaks its writes into smaller transactions.
                // Reads always go through discover() which is fast and
                // works -- the user-visible behavior matches what they
                // had before the pre-cache work landed.
                val cachedTop: List<MediaItem>? = null
                catalogDiagLog(
                    "load mediaType=$mediaType category=$category genreId=$genreId " +
                        "providerId=$providerId filterActive=${filter.isActive} " +
                        "sortBy=${filter.sortBy} cache=disabled",
                )

                val result = when {
                    cachedTop != null -> com.torve.domain.model.PagedResult(
                        items = cachedTop,
                        page = 1,
                        totalPages = 1,
                        totalResults = cachedTop.size,
                    )
                    // In Progress: always local data, never discovery endpoints
                    category == CatalogCategory.IN_PROGRESS && providerId == null -> {
                        loadInProgressItems(genreId)
                    }
                    // No genre/filter/provider active: use curated list endpoints
                    !filter.isActive && genreId == null && providerId == null -> {
                        when (category) {
                            CatalogCategory.TRENDING -> metadataRepo.getTrendingPaged(mediaType)
                            CatalogCategory.POPULAR -> metadataRepo.getPopularPaged(mediaType)
                            CatalogCategory.TOP_RATED -> metadataRepo.getTopRatedPaged(mediaType)
                            CatalogCategory.IN_PROGRESS -> error("unreachable")
                        }
                    }
                    // Genre/filter/provider active: use discover API with chip-specific sort
                    else -> {
                        val chipSort = if (filter.sortBy != SortOption.POPULARITY_DESC) {
                            filter.sortBy.apiValue
                        } else {
                            chipSortBy(category)
                        }
                        val chipMinRating = tmdbDiscoverRating(filter.minRating, filter.ratingSource)
                            ?: chipMinRating(category)
                        metadataRepo.discover(
                            type = mediaType,
                            page = 1,
                            sortBy = chipSort,
                            withGenres = genreId?.toString(),
                            minRating = chipMinRating,
                            year = filter.year,
                            yearTo = filter.yearTo,
                            runtimeGte = filter.runtimeFilter?.minMinutes,
                            runtimeLte = filter.runtimeFilter?.maxMinutes,
                            withWatchProviders = providerFilter.first,
                            watchRegion = providerFilter.second,
                        )
                    }
                }

                currentCoroutineContext().ensureActive()
                val finalItems = if (shouldDedupe()) result.items.dedupeByStableKey() else result.items
                // Posters do not depend on a remote ratings batch. Enforce a
                // requested minimum before display; otherwise rank cached IMDb
                // values now and finish rating/order updates in the background.
                val cachedItems = hydrateCachedRatings(finalItems, resolvePrimary = providerId == null || filter.minRating != null)
                catalogDiagLog(
                    "load result.items=${result.items.size} finalItems=${finalItems.size} " +
                        "cachedItems=${cachedItems.size}",
                )
                _state.update {
                    val withItems = it.copy(
                        items = cachedItems,
                        isLoading = false,
                        currentPage = result.page,
                        totalPages = result.totalPages,
                        hasMore = result.page < result.totalPages,
                        activeFilterCount = filter.activeCount + (if (genreId != null) 1 else 0),
                    )
                    val filtered = applyContentPolicy(withItems)
                    catalogDiagLog("load post-policy items=${filtered.items.size}")
                    filtered
                }
                torveVerboseLog {
                    "CATALOG_TAB state_transition state=success mediaType=$mediaType category=$category items=${cachedItems.size} page=${result.page}/${result.totalPages}"
                }
                if (providerId != null && providerFingerprint != null) {
                    providerSnapshots?.write(providerId, providerFingerprint, providerFilter.first.orEmpty(), _state.value)
                }
                enrichAndUpdateItems(cachedItems) { enriched ->
                    // Patch only rating fields, preserving visible titles and IDs.
                    // Provider pages finish their IMDb ranking in one batch;
                    // discovery shelves keep their original presentation order.
                    _state.update { current ->
                        val byId = enriched.associateBy { it.id }
                        val merged = current.items.map { existing ->
                            byId[existing.id]?.let { e ->
                                existing.copy(
                                    ratings = e.ratings,
                                    imdbId = e.imdbId ?: existing.imdbId,
                                )
                            } ?: existing
                        }
                        current.copy(items = merged)
                    }
                    if (providerId != null && providerFingerprint != null) {
                        val snapshot = _state.value
                        scope.launch { providerSnapshots?.write(providerId, providerFingerprint, providerFilter.first.orEmpty(), snapshot) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                torveVerboseLog {
                    "CATALOG_TAB state_transition state=error mediaType=$mediaType category=${_state.value.selectedCategory} ${e::class.simpleName}: ${sanitizeNetworkDiagnosticText(e.message)}"
                }
                _state.update { it.copy(isLoading = false, error = catalogContentLoadErrorMessage(mediaType)) }
            }
        }
    }

    /** Sort parameter for the TMDB discover API, differentiated by chip. */
    private fun chipSortBy(category: CatalogCategory): String = when (category) {
        CatalogCategory.TRENDING -> "popularity.desc"
        CatalogCategory.POPULAR -> "vote_count.desc"
        CatalogCategory.TOP_RATED -> "vote_average.desc"
        CatalogCategory.IN_PROGRESS -> "popularity.desc"
    }

    /** Minimum rating guard — prevents Top Rated from surfacing obscure 1-vote titles. */
    private fun chipMinRating(category: CatalogCategory): Float? = when (category) {
        CatalogCategory.TOP_RATED -> 6.0f
        else -> null
    }

    /** Build In Progress results from local watch history, optionally filtered by genre. */
    private suspend fun loadInProgressItems(genreId: Int?): PagedResult {
        val progressItems = watchProgressRepo?.getInProgress(50) ?: emptyList()
        val targetType = if (mediaType == "movie") MediaType.MOVIE else MediaType.SERIES
        val items = progressItems
            .filter { it.mediaType == targetType }
            .mapNotNull { wp ->
                val tmdbId = wp.mediaId.extractTmdbIdOrNull() ?: return@mapNotNull null
                MediaItem(
                    id = wp.mediaId,
                    tmdbId = tmdbId,
                    title = wp.showTitle ?: wp.title,
                    posterUrl = wp.posterUrl,
                    type = wp.mediaType,
                    year = null,
                )
            }
        // If genre selected, enrich items to get genreIds then filter
        val filtered = if (genreId != null && items.isNotEmpty()) {
            val enriched = items.mapNotNull { item ->
                runCatching {
                    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
                    metadataRepo.getDetail(type, item.tmdbId ?: return@mapNotNull null)
                }.getOrNull()
            }
            enriched.filter { genreId in it.genreIds }
        } else {
            items
        }
        return PagedResult(items = filtered, page = 1, totalPages = 1, totalResults = filtered.size)
    }

    fun loadMore() {
        val s = _state.value
        // Don't paginate during search
        if (s.searchQuery.length >= 2) {
            loadMoreSearch()
            return
        }
        if (s.isLoadingMore || !s.hasMore || s.isLoading) return

        catalogPageJob = scope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            try {
                val nextPage = s.currentPage + 1
                val filter = s.filter
                val genreId = s.selectedGenreId
                val providerId = s.providerId
                val providerFilter = resolveProviderFilter(providerId)
                val category = s.selectedCategory

                if (category == CatalogCategory.IN_PROGRESS && providerId == null) return@launch

                val result = if (!filter.isActive && genreId == null && providerId == null) {
                    when (category) {
                        CatalogCategory.TRENDING -> metadataRepo.getTrendingPaged(mediaType, nextPage)
                        CatalogCategory.POPULAR -> metadataRepo.getPopularPaged(mediaType, nextPage)
                        CatalogCategory.TOP_RATED -> metadataRepo.getTopRatedPaged(mediaType, nextPage)
                        CatalogCategory.IN_PROGRESS -> return@launch
                    }
                } else {
                    val chipSort = if (filter.sortBy != SortOption.POPULARITY_DESC) {
                        filter.sortBy.apiValue
                    } else {
                        chipSortBy(category)
                    }
                    val chipMin = tmdbDiscoverRating(filter.minRating, filter.ratingSource)
                        ?: chipMinRating(category)
                    metadataRepo.discover(
                        type = mediaType,
                        page = nextPage,
                        sortBy = chipSort,
                        withGenres = genreId?.toString(),
                        minRating = chipMin,
                        year = filter.year,
                        yearTo = filter.yearTo,
                        runtimeGte = filter.runtimeFilter?.minMinutes,
                        runtimeLte = filter.runtimeFilter?.maxMinutes,
                        withWatchProviders = providerFilter.first,
                        watchRegion = providerFilter.second,
                    )
                }

                currentCoroutineContext().ensureActive()
                // Rank only the incoming page. Re-sorting the entire grid here
                // moves cards above the scroll anchor whenever new scores arrive.
                val pageItems = kotlinx.coroutines.withContext(com.torve.util.ioDispatcher) {
                    hydrateCachedRatings(result.items, resolvePrimary = false)
                }
                val combined = _state.value.items + pageItems
                val cachedItems = if (shouldDedupe()) combined.dedupeByStableKey() else combined
                _state.update {
                    applyContentPolicy(
                        it.copy(
                            items = cachedItems,
                            isLoadingMore = false,
                            currentPage = result.page,
                            totalPages = result.totalPages,
                            hasMore = result.page < result.totalPages,
                        ),
                    )
                }
                enrichAndUpdateItems(result.items) { enriched ->
                    // Same in-place merge as loadCatalog -- patch ratings only.
                    _state.update { current ->
                        val byId = enriched.associateBy { it.id }
                        val merged = current.items.map { existing ->
                            byId[existing.id]?.let { e ->
                                existing.copy(
                                    ratings = e.ratings,
                                    imdbId = e.imdbId ?: existing.imdbId,
                                )
                            } ?: existing
                        }
                        current.copy(items = merged)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                torveVerboseLog {
                    "Catalog loadMore failed mediaType=$mediaType category=${_state.value.selectedCategory}"
                }
                _state.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    private fun loadMoreSearch() {
        val s = _state.value
        if (s.isSearching || s.isSearchingMore || !s.searchHasMore) return

        searchPageJob = scope.launch {
            _state.update { it.copy(isSearchingMore = true) }
            try {
                val nextPage = s.searchPage + 1
                val result = metadataRepo.searchMultiPaged(s.searchQuery, nextPage, mediaType)
                val scopedItems = filterProviderSearchResults(result.items, s)
                currentCoroutineContext().ensureActive()
                val combined = _state.value.searchResults + scopedItems
                val newItems = if (shouldDedupe()) combined.dedupeByStableKey() else combined
                val cachedItems = hydrateCachedRatings(newItems)
                _state.update {
                    applyContentPolicy(
                        it.copy(
                            searchResults = cachedItems,
                            isSearchingMore = false,
                            searchPage = result.page,
                            searchHasMore = result.page < result.totalPages,
                        ),
                    )
                }
                enrichAndUpdateItems(cachedItems) { items ->
                    _state.update { current -> applyContentPolicy(current.copy(searchResults = items)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                torveVerboseLog {
                    "Catalog loadMoreSearch failed mediaType=$mediaType query=${s.searchQuery}"
                }
                _state.update { it.copy(isSearchingMore = false) }
            }
        }
    }

    fun selectCategory(category: CatalogCategory) {
        if (_state.value.selectedCategory == category) return
        _state.update { it.copy(selectedCategory = category) }
        loadCatalog(retainItems = true)
    }

    fun selectGenre(genreId: Int?) {
        if (_state.value.selectedGenreId == genreId) return
        catalogDiagLog("ACTION selectGenre($genreId)")
        _state.update { it.copy(selectedGenreId = genreId) }
        loadCatalog(retainItems = true)
    }

    fun applyFilter(filter: CatalogFilter) {
        applyFilters(filter, _state.value.selectedGenreId)
    }

    fun applyFilters(filter: CatalogFilter, genreId: Int?) {
        _state.update { it.copy(filter = filter, selectedGenreId = genreId, showFilterSheet = false) }
        loadCatalog(retainItems = true)
    }

    fun clearFilters() {
        _state.update { it.copy(filter = CatalogFilter(), selectedGenreId = null, showFilterSheet = false) }
        loadCatalog(retainItems = true)
    }

    fun toggleFilterSheet() {
        _state.update { it.copy(showFilterSheet = !it.showFilterSheet) }
    }

    fun dismissFilterSheet() {
        _state.update { it.copy(showFilterSheet = false) }
    }

    fun updateSearchQuery(query: String) {
        _state.update {
            if (query.length < 2 && it.searchQuery.length >= 2) {
                // Transition from searchable → too short: clear search state
                // so previous results don't linger when the user deletes text.
                it.copy(
                    searchQuery = query,
                    searchResults = emptyList(),
                    hasActiveSearch = false,
                    aiSearchLabel = null,
                    aiSearchError = null,
                )
            } else {
                it.copy(searchQuery = query)
            }
        }
        restartSearch()
    }

    private fun restartSearch() {
        searchPageJob?.cancel()
        catalogEnrichJob?.cancel()
        _state.update {
            it.copy(searchResults = emptyList(), isSearching = it.searchQuery.length >= 2,
                searchHasMore = false, isSearchingMore = false, hasActiveSearch = false, error = null)
        }
        searchRequests.value += 1
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        scope.launch {
            searchRequests
                .debounce(300)
                .collectLatest {
                    val searchState = _state.value
                    val query = searchState.searchQuery
                    if (query.length < 2) return@collectLatest
                    // Sensitive-query gate. When the policy is locked AND the
                    // typed query itself contains an explicit keyword
                    // (e.g. "porn"), commit empty results instead of fetching.
                    // TMDB's `/search/multi` returns non-adult-flagged
                    // documentaries with explicit titles/posters for these
                    // queries; per-item classify() can't catch them because
                    // their `adult=false` and titles don't trip the keyword
                    // list. Pre-empting at the query level is the only way to
                    // prevent leakage. Single atomic empty commit also stops
                    // the visible flicker between fetch + enrichment passes.
                    val policy = currentPolicy()
                    if (policy.enforcementEnabled && policy.isLocked &&
                        contentPolicyFilter.isSensitiveQuery(query)
                    ) {
                        _state.update {
                            it.copy(
                                searchResults = emptyList(),
                                isSearching = false,
                                searchPage = 1,
                                searchHasMore = false,
                                hasActiveSearch = true,
                            )
                        }
                        return@collectLatest
                    }
                    _state.update { it.copy(isSearching = true, searchPage = 1) }
                    try {
                        val result = metadataRepo.searchMultiPaged(query, 1, mediaType)
                        val scopedItems = filterProviderSearchResults(result.items, searchState)
                        currentCoroutineContext().ensureActive()
                        if (_state.value.searchQuery != query || !sameSearchFilters(searchState)) return@collectLatest
                        val finalResults = if (shouldDedupe()) scopedItems.dedupeByStableKey() else scopedItems
                        val cachedResults = hydrateCachedRatings(finalResults)
                        _state.update {
                            applyContentPolicy(
                                it.copy(
                                    searchResults = cachedResults,
                                    isSearching = false,
                                    searchPage = result.page,
                                    searchHasMore = result.page < result.totalPages,
                                    hasActiveSearch = true,
                                ),
                            )
                        }
                        enrichAndUpdateItems(cachedResults) { items ->
                            if (_state.value.searchQuery == query && sameSearchFilters(searchState)) {
                                _state.update { current -> applyContentPolicy(current.copy(searchResults = items)) }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        torveVerboseLog {
                            "Catalog search failed mediaType=$mediaType query=$query"
                        }
                        _state.update { it.copy(isSearching = false, error = "Search could not be loaded. Please try again.") }
                    }
                }
        }
    }

    fun searchWithAi(provider: AiProvider, apiKey: String) {
        val query = _state.value.searchQuery
        if (query.isBlank() || keywordSearchService == null) return

        if (apiKey.isBlank()) {
            _state.update {
                it.copy(aiSearchError = "Set a ${provider.label} API key in Settings to use AI search")
            }
            return
        }

        // Mirror the sensitive-query gate from observeSearch — block AI
        // search of explicitly-sensitive queries when the policy is locked.
        val policy = currentPolicy()
        if (policy.enforcementEnabled && policy.isLocked &&
            contentPolicyFilter.isSensitiveQuery(query)
        ) {
            _state.update {
                it.copy(
                    searchResults = emptyList(),
                    isAiSearching = false,
                    aiSearchLabel = null,
                    aiSearchError = null,
                    isSearching = false,
                    searchHasMore = false,
                    hasActiveSearch = true,
                )
            }
            return
        }

        scope.launch {
            _state.update { it.copy(isAiSearching = true, aiSearchError = null) }
            try {
                val result = keywordSearchService.searchWithAi(provider, apiKey, query)

                val items: List<MediaItem> = when {
                    result.mode == "specific" && result.specificItems.isNotEmpty() -> {
                        result.specificItems.mapNotNull { specific ->
                            try {
                                metadataRepo.getDetail(specific.mediaType, specific.tmdbId)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    result.mode == "person_credits" && result.personId != null -> {
                        metadataRepo.getPersonCredits(result.personId!!)
                    }
                    result.mode == "person_filtered" && result.specificItems.isNotEmpty() -> {
                        // AI identified specific titles — resolve each via TMDB
                        result.specificItems.mapNotNull { specific ->
                            try {
                                metadataRepo.getDetail(specific.mediaType, specific.tmdbId)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    result.mode == "person_filtered" && result.personId != null -> {
                        // Fallback: AI didn't return specific titles, use discover with cast filter
                        val type = result.mediaType ?: mediaType
                        val castParam = if (!result.isDirector) result.personId.toString() else null
                        val crewParam = if (result.isDirector) result.personId.toString() else null
                        metadataRepo.discover(
                            type = type,
                            page = 1,
                            sortBy = result.sortBy,
                            withGenres = result.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                            minRating = result.minRating,
                            year = result.yearFrom,
                            yearTo = result.yearTo,
                            withCast = castParam,
                            withCrew = crewParam,
                        ).items
                    }
                    else -> {
                        val type = result.mediaType ?: mediaType
                        metadataRepo.discover(
                            type = type,
                            page = 1,
                            sortBy = result.sortBy,
                            withGenres = result.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                            minRating = result.minRating,
                            year = result.yearFrom,
                            yearTo = result.yearTo,
                            withKeywords = result.keywordIds.takeIf { it.isNotEmpty() }?.joinToString("|"),
                        ).items
                    }
                }

                val scopedItems = filterProviderSearchResults(items, _state.value)
                val finalItems = if (shouldDedupe()) scopedItems.dedupeByStableKey() else scopedItems
                val cachedItems = hydrateCachedRatings(finalItems)
                _state.update {
                    applyContentPolicy(
                        it.copy(
                            searchResults = cachedItems,
                            isAiSearching = false,
                            aiSearchLabel = result.title,
                            aiSearchError = null,
                            isSearching = false,
                            searchHasMore = false,
                            hasActiveSearch = true,
                        ),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isAiSearching = false, aiSearchError = e.message ?: "AI search failed")
                }
            }
        }
    }

    fun clearSearch() {
        _state.update {
            it.copy(
                searchQuery = "", searchResults = emptyList(),
                searchPage = 1, searchHasMore = false,
                aiSearchLabel = null, aiSearchError = null,
                hasActiveSearch = false,
            )
        }
        restartSearch()
    }

    fun refresh() {
        loadCatalog(retainItems = true)
        loadShelves()
    }

    fun refreshLocalizedContent() {
        // Retain the current provider, filters and query while replacing all rails.
        loadCatalog()
        loadShelves()
    }

    fun close() { scope.cancel() }

    private fun sameSearchFilters(previous: CatalogUiState): Boolean = _state.value.let {
        it.providerId == previous.providerId && it.selectedGenreId == previous.selectedGenreId && it.filter == previous.filter
    }

    /** TMDB text search is global: verify each hit against the selected service and country. */
    private suspend fun filterProviderSearchResults(items: List<MediaItem>, state: CatalogUiState): List<MediaItem> {
        if (state.providerId == null) return items
        val (providers, region) = resolveProviderFilter(state.providerId)
        val providerIds = providers.orEmpty().split('|').mapNotNull(String::toIntOrNull).toSet()
        val permits = Semaphore(4)
        return supervisorScope {
            items.map { item -> async {
                permits.withPermit {
                    val id = item.tmdbId ?: item.id.extractTmdbIdOrNull() ?: return@withPermit null
                    val filterItem = if (state.filter.runtimeFilter != null && item.runtime == null) {
                        metadataRepo.getDetail(mediaType, id)
                    } else item
                    if (!ProviderCatalogPolicy.matchesFilters(filterItem, mediaType, state.selectedGenreId, state.filter)) return@withPermit null
                    val availability = metadataRepo.getTitleWatchProviderCandidates(mediaType, id)
                    item.takeIf { availability.any { it.id in providerIds && region in it.regions } }
                }
            } }.mapNotNull { it.await() }
        }
    }

    private suspend fun shouldDedupe(): Boolean {
        return prefsRepo?.getString(SettingsViewModel.KEY_DEDUPE_RESULTS)?.toBooleanStrictOrNull() ?: true
    }

    /**
     * File-based diagnostic logger that writes to the same launch-guard.log
     * the desktop launch-guard uses. Compose Desktop's jpackage launcher
     * swallows stdout, so println-based debugging is invisible; this gives
     * the user a place to read VM-side logs from PowerShell.
     * Kotlin Multiplatform: silently no-ops on platforms without a writable
     * %LOCALAPPDATA% / user.home directory (mobile etc).
     */
    private fun catalogDiagLog(message: String) {
        torveVerboseLog { "CATALOG mediaType=$mediaType $message" }
    }

    private suspend fun providerRegion(): String = prefsRepo?.getString(SettingsViewModel.KEY_REGION_CODE)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: "US"

    private suspend fun resolveProviderFilter(providerId: Int?): Pair<String?, String?> {
        if (providerId == null) return null to null
        val region = providerRegion()
        _state.update { it.copy(providerRegion = region) }
        resolvedProvider?.takeIf { it.first == providerId && it.second == region }?.let { return it.third to region }
        if (initialProviderGroup != null) {
            // An empty favourite group must never turn into unscoped discovery.
            check(initialProviderGroup.isNotEmpty()) { "Choose a streaming service first." }
            val names = metadataRepo.getWatchProviderNames(mediaType, region)
            currentCoroutineContext().ensureActive()
            val available = names.map { StreamingProviderCandidate(it.key, it.value) }
            val identifiers = initialProviderGroup.flatMap { (id, name) ->
                resolveStreamingProviderIds(name, id, region, available)
            }.distinct().sorted().joinToString("|")
            check(identifiers.isNotEmpty())
            resolvedProvider = Triple(providerId, region, identifiers)
            return identifiers to region
        }
        val providerName = initialProviderName
            ?.takeIf { providerId == initialProviderId }
            ?.takeIf { it.isNotBlank() }
            ?: return providerId.toString() to region
        val providerNames = runCatching {
            metadataRepo.getWatchProviderNames(mediaType, region)
        }.getOrDefault(emptyMap())
        currentCoroutineContext().ensureActive()
        val resolved = resolveStreamingProviderIds(
            requestedName = providerName,
            configuredProviderId = providerId,
            region = region,
            availableProviders = providerNames.map { (id, name) ->
                StreamingProviderCandidate(id = id, name = name)
            },
        )
        val identifiers = resolved.joinToString("|").ifBlank { providerId.toString() }
        resolvedProvider = Triple(providerId, region, identifiers)
        return identifiers to region
    }

    private suspend fun hydrateCachedRatings(items: List<MediaItem>, resolvePrimary: Boolean = true): List<MediaItem> {
        val enricher = ratingsEnricher ?: return _state.value.filter.applyRatingOrder(items)
        val filter = _state.value.filter
        var hydrated = enricher.hydrateListFromCache(items)
        if (resolvePrimary && filter.minRating != null &&
            filter.ratingSource != com.torve.domain.discovery.DiscoveryRatingSource.TMDB) {
            val key = integrationSecretStore?.get(IntegrationSecretKey.MDBLIST_API_KEY)
                ?: prefsRepo?.getString(SettingsViewModel.KEY_MDBLIST_API_KEY) ?: MdbListApi.DEFAULT_API_KEY
            // Resolve a ranked page before committing it, so the initial
            // order and minimum filter agree with the displayed pills.
            hydrated = kotlinx.coroutines.withTimeoutOrNull(8_000) { enricher.enrichList(hydrated, key, requirePrimaryProviders = true) } ?: hydrated
            currentCoroutineContext().ensureActive()
        }
        return filter.applyRatingOrder(hydrated)
    }

    private fun enrichAndUpdateItems(
        items: List<MediaItem>,
        update: (List<MediaItem>) -> Unit,
    ) {
        val enricher = ratingsEnricher ?: return
        // Cancel any prior enrichment (e.g. from a previous genre) so
        // its `update` callback can't land after the user already
        // moved on. Each loadCatalog cancels this same handle at the
        // start; here we cancel for safety in case enrichment is
        // triggered without a fresh loadCatalog.
        catalogEnrichJob?.cancel()
        catalogEnrichJob = scope.launch {
            val apiKey = try {
                integrationSecretStore?.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo?.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            } catch (_: Exception) { MdbListApi.DEFAULT_API_KEY }
            // Enrich even without an MDBList key — OMDB/Trakt fallbacks still provide
            // IMDB ratings so pills render on every card.
            val enriched = enricher.enrichList(items, apiKey)
            currentCoroutineContext().ensureActive()
            // Publish once after the complete batch; the caller controls whether
            // this grid needs a final IMDb order or a stable shelf order.
            update(enriched)
        }
    }

    /**
     * Shelf items come from TMDB list endpoints, which omit logo/image data.
     * Fetch full details for the top items of each shelf so the hero banner
     * can render a logo instead of plain title text.
     */
    private fun launchHeroArtworkBackfill() {
        scope.launch {
            val snapshot = _state.value
            val candidates = buildList {
                addAll(snapshot.trendingItems.take(3))
                addAll(snapshot.popularItems.take(2))
                addAll(snapshot.topRatedItems.take(2))
            }.mapNotNull { it.tmdbId?.let { id -> id to it } }
                .distinctBy { it.first }
                .take(8)
            if (candidates.isEmpty()) return@launch

            val backfilled: Map<Int, MediaItem> = supervisorScope {
                candidates.map { (id, _) ->
                    async {
                        runCatching { id to metadataRepo.getDetail(mediaType, id) }.getOrNull()
                    }
                }.mapNotNull { it.await() }.toMap()
            }
            if (backfilled.isEmpty()) return@launch

            fun List<MediaItem>.apply(): List<MediaItem> = map { item ->
                val detail = item.tmdbId?.let { backfilled[it] } ?: return@map item
                item.copy(
                    posterUrl = item.posterUrl?.takeIf { it.isNotBlank() } ?: detail.posterUrl,
                    backdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() } ?: detail.backdropUrl,
                    logoUrl = item.logoUrl?.takeIf { it.isNotBlank() } ?: detail.logoUrl,
                )
            }

            _state.update { current ->
                current.copy(
                    trendingItems = current.trendingItems.apply(),
                    popularItems = current.popularItems.apply(),
                    topRatedItems = current.topRatedItems.apply(),
                )
            }
        }
    }

    private fun currentPolicy(): ContentPolicyState {
        return contentPolicyRepository?.state?.value ?: ContentPolicyState.unrestricted()
    }

    private fun applyContentPolicy(state: CatalogUiState): CatalogUiState {
        val policy = currentPolicy()
        return state.copy(
            items = contentPolicyFilter.filterItems(
                policy = policy,
                context = ContentAccessContext.DEFAULT_DISCOVERY,
                items = state.items,
                sourceType = ContentSourceType.TMDB,
            ).items,
            searchResults = contentPolicyFilter.filterItems(
                policy = policy,
                context = ContentAccessContext.DIRECT_SEARCH,
                items = state.searchResults,
                sourceType = ContentSourceType.TMDB,
                // Search is the highest-leak surface — TMDB returns
                // adult==null items for explicit queries that classify as
                // SAFE under the permissive default. Strict mode pushes
                // those into UNKNOWN → HIDE.
                strictUnknown = true,
            ).items,
            continueWatching = contentPolicyFilter.filterWatchProgress(
                policy = policy,
                context = ContentAccessContext.HISTORY_DERIVED,
                items = state.continueWatching,
            ).items,
            trendingItems = contentPolicyFilter.filterItems(
                policy = policy,
                context = ContentAccessContext.DEFAULT_DISCOVERY,
                items = state.trendingItems,
                sourceType = ContentSourceType.TMDB,
            ).items,
            popularItems = contentPolicyFilter.filterItems(
                policy = policy,
                context = ContentAccessContext.DEFAULT_DISCOVERY,
                items = state.popularItems,
                sourceType = ContentSourceType.TMDB,
            ).items,
            topRatedItems = contentPolicyFilter.filterItems(
                policy = policy,
                context = ContentAccessContext.DEFAULT_DISCOVERY,
                items = state.topRatedItems,
                sourceType = ContentSourceType.TMDB,
            ).items,
        )
    }
}
