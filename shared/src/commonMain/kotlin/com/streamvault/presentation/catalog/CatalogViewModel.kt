package com.streamvault.presentation.catalog

import com.streamvault.data.ai.AiProvider
import com.streamvault.data.ai.KeywordSearchService
import com.streamvault.data.mdblist.RatingsEnricher
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.dedupeByStableKey
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.domain.repository.WatchProgressRepository
import com.streamvault.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    initialProviderId: Int? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(CatalogUiState(providerId = initialProviderId))
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private val searchQueryFlow = MutableStateFlow("")

    init {
        loadCatalog()
        loadShelves()
        observeSearch()
    }

    private fun loadShelves() {
        if (watchProgressRepo == null) return
        scope.launch {
            try {
                val targetType = if (mediaType == "tv") MediaType.SERIES else MediaType.MOVIE

                val continueDeferred = async { watchProgressRepo.getInProgress(20) }
                val trendingDeferred = async { metadataRepo.getTrending(mediaType) }
                val popularDeferred = async { metadataRepo.getPopular(mediaType) }
                val topRatedDeferred = async { metadataRepo.getTopRated(mediaType) }

                val continueWatching = continueDeferred.await()
                    .filter { it.mediaType == targetType }
                val trending = trendingDeferred.await()
                val popular = popularDeferred.await()
                val topRated = topRatedDeferred.await()

                _state.update {
                    it.copy(
                        continueWatching = continueWatching,
                        trendingItems = trending,
                        popularItems = popular,
                        topRatedItems = topRated,
                        shelvesLoaded = true,
                    )
                }
            } catch (_: Exception) {
                _state.update { it.copy(shelvesLoaded = true) }
            }
        }
    }

    fun setProvider(providerId: Int) {
        _state.update { it.copy(providerId = providerId) }
        loadCatalog()
    }

    fun loadCatalog() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null, currentPage = 1) }
            try {
                val filter = _state.value.filter
                val genreId = _state.value.selectedGenreId
                val providerId = _state.value.providerId

                val result = if (filter.isActive || genreId != null || providerId != null) {
                    // Use discover API when any filter, genre, or provider is active
                    metadataRepo.discover(
                        type = mediaType,
                        page = 1,
                        sortBy = filter.sortBy.apiValue,
                        withGenres = genreId?.toString(),
                        minRating = filter.minRating,
                        year = filter.year,
                        yearTo = filter.yearTo,
                        runtimeGte = filter.runtimeFilter?.minMinutes,
                        runtimeLte = filter.runtimeFilter?.maxMinutes,
                        withWatchProviders = providerId?.toString(),
                        watchRegion = if (providerId != null) "US" else null,
                    )
                } else {
                    // Use curated list endpoints
                    when (_state.value.selectedCategory) {
                        CatalogCategory.TRENDING -> metadataRepo.getTrendingPaged(mediaType)
                        CatalogCategory.POPULAR -> metadataRepo.getPopularPaged(mediaType)
                        CatalogCategory.TOP_RATED -> metadataRepo.getTopRatedPaged(mediaType)
                    }
                }

                val finalItems = if (shouldDedupe()) result.items.dedupeByStableKey() else result.items
                _state.update {
                    it.copy(
                        items = finalItems,
                        isLoading = false,
                        currentPage = result.page,
                        totalPages = result.totalPages,
                        hasMore = result.page < result.totalPages,
                        activeFilterCount = filter.activeCount + (if (genreId != null) 1 else 0),
                    )
                }
                enrichAndUpdateItems(finalItems) { items ->
                    _state.update { it.copy(items = items) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoadingMore || !s.hasMore || s.isLoading) return
        // Don't paginate during search
        if (s.searchQuery.length >= 2) {
            loadMoreSearch()
            return
        }

        scope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            try {
                val nextPage = s.currentPage + 1
                val filter = s.filter
                val genreId = s.selectedGenreId
                val providerId = s.providerId

                val result = if (filter.isActive || genreId != null || providerId != null) {
                    metadataRepo.discover(
                        type = mediaType,
                        page = nextPage,
                        sortBy = filter.sortBy.apiValue,
                        withGenres = genreId?.toString(),
                        minRating = filter.minRating,
                        year = filter.year,
                        yearTo = filter.yearTo,
                        runtimeGte = filter.runtimeFilter?.minMinutes,
                        runtimeLte = filter.runtimeFilter?.maxMinutes,
                        withWatchProviders = providerId?.toString(),
                        watchRegion = if (providerId != null) "US" else null,
                    )
                } else {
                    when (s.selectedCategory) {
                        CatalogCategory.TRENDING -> metadataRepo.getTrendingPaged(mediaType, nextPage)
                        CatalogCategory.POPULAR -> metadataRepo.getPopularPaged(mediaType, nextPage)
                        CatalogCategory.TOP_RATED -> metadataRepo.getTopRatedPaged(mediaType, nextPage)
                    }
                }

                val combined = _state.value.items + result.items
                val newItems = if (shouldDedupe()) combined.dedupeByStableKey() else combined
                _state.update {
                    it.copy(
                        items = newItems,
                        isLoadingMore = false,
                        currentPage = result.page,
                        totalPages = result.totalPages,
                        hasMore = result.page < result.totalPages,
                    )
                }
                enrichAndUpdateItems(newItems) { items ->
                    _state.update { it.copy(items = items) }
                }
            } catch (_: Exception) {
                _state.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    private fun loadMoreSearch() {
        val s = _state.value
        if (s.isSearchingMore || !s.searchHasMore) return

        scope.launch {
            _state.update { it.copy(isSearchingMore = true) }
            try {
                val nextPage = s.searchPage + 1
                val result = metadataRepo.searchMultiPaged(s.searchQuery, nextPage, mediaType)
                val combined = _state.value.searchResults + result.items
                val newItems = if (shouldDedupe()) combined.dedupeByStableKey() else combined
                _state.update {
                    it.copy(
                        searchResults = newItems,
                        isSearchingMore = false,
                        searchPage = result.page,
                        searchHasMore = result.page < result.totalPages,
                    )
                }
                enrichAndUpdateItems(newItems) { items ->
                    _state.update { it.copy(searchResults = items) }
                }
            } catch (_: Exception) {
                _state.update { it.copy(isSearchingMore = false) }
            }
        }
    }

    fun selectCategory(category: CatalogCategory) {
        _state.update { it.copy(selectedCategory = category) }
        loadCatalog()
    }

    fun selectGenre(genreId: Int?) {
        _state.update { it.copy(selectedGenreId = genreId) }
        loadCatalog()
    }

    fun applyFilter(filter: CatalogFilter) {
        _state.update { it.copy(filter = filter, showFilterSheet = false) }
        loadCatalog()
    }

    fun clearFilters() {
        _state.update { it.copy(filter = CatalogFilter(), selectedGenreId = null, showFilterSheet = false) }
        loadCatalog()
    }

    fun toggleFilterSheet() {
        _state.update { it.copy(showFilterSheet = !it.showFilterSheet) }
    }

    fun dismissFilterSheet() {
        _state.update { it.copy(showFilterSheet = false) }
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchQueryFlow.value = query
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        scope.launch {
            searchQueryFlow
                .debounce(300)
                .distinctUntilChanged()
                .filter { it.length >= 2 }
                .collect { query ->
                    _state.update { it.copy(isSearching = true, searchPage = 1) }
                    try {
                        val result = metadataRepo.searchMultiPaged(query, 1, mediaType)
                        val finalResults = if (shouldDedupe()) result.items.dedupeByStableKey() else result.items
                        _state.update {
                            it.copy(
                                searchResults = finalResults,
                                isSearching = false,
                                searchPage = result.page,
                                searchHasMore = result.page < result.totalPages,
                                hasActiveSearch = true,
                            )
                        }
                        enrichAndUpdateItems(finalResults) { items ->
                            _state.update { it.copy(searchResults = items) }
                        }
                    } catch (_: Exception) {
                        _state.update { it.copy(isSearching = false) }
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

        scope.launch {
            _state.update { it.copy(isAiSearching = true, aiSearchError = null) }
            try {
                val result = keywordSearchService.searchWithAi(provider, apiKey, query)

                val items: List<MediaItem> = if (result.mode == "specific" && result.specificItems.isNotEmpty()) {
                    result.specificItems.mapNotNull { specific ->
                        try {
                            metadataRepo.getDetail(specific.mediaType, specific.tmdbId)
                        } catch (_: Exception) {
                            null
                        }
                    }
                } else {
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

                val finalItems = if (shouldDedupe()) items.dedupeByStableKey() else items
                _state.update {
                    it.copy(
                        searchResults = finalItems,
                        isAiSearching = false,
                        aiSearchLabel = result.title,
                        aiSearchError = null,
                        isSearching = false,
                        searchHasMore = false,
                        hasActiveSearch = true,
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
        searchQueryFlow.value = ""
    }

    fun refresh() {
        loadCatalog()
    }

    private suspend fun shouldDedupe(): Boolean {
        return prefsRepo?.getString(SettingsViewModel.KEY_DEDUPE_RESULTS)?.toBooleanStrictOrNull() ?: true
    }

    private fun enrichAndUpdateItems(
        items: List<MediaItem>,
        update: (List<MediaItem>) -> Unit,
    ) {
        val enricher = ratingsEnricher ?: return
        val repo = prefsRepo ?: return
        scope.launch {
            val apiKey = try {
                repo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY) ?: ""
            } catch (_: Exception) { "" }
            if (apiKey.isBlank()) return@launch
            val enriched = enricher.enrichList(items, apiKey)
            update(enriched)
        }
    }
}
