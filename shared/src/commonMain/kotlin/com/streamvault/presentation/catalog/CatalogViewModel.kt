package com.streamvault.presentation.catalog

import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.WatchProgressRepository
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(CatalogUiState())
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

    fun loadCatalog() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null, currentPage = 1) }
            try {
                val filter = _state.value.filter
                val genreId = _state.value.selectedGenreId

                val result = if (filter.isActive || genreId != null) {
                    // Use discover API when any filter or genre is active
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
                    )
                } else {
                    // Use curated list endpoints
                    when (_state.value.selectedCategory) {
                        CatalogCategory.TRENDING -> metadataRepo.getTrendingPaged(mediaType)
                        CatalogCategory.POPULAR -> metadataRepo.getPopularPaged(mediaType)
                        CatalogCategory.TOP_RATED -> metadataRepo.getTopRatedPaged(mediaType)
                    }
                }

                _state.update {
                    it.copy(
                        items = result.items,
                        isLoading = false,
                        currentPage = result.page,
                        totalPages = result.totalPages,
                        hasMore = result.page < result.totalPages,
                        activeFilterCount = filter.activeCount + (if (genreId != null) 1 else 0),
                    )
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

                val result = if (filter.isActive || genreId != null) {
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
                    )
                } else {
                    when (s.selectedCategory) {
                        CatalogCategory.TRENDING -> metadataRepo.getTrendingPaged(mediaType, nextPage)
                        CatalogCategory.POPULAR -> metadataRepo.getPopularPaged(mediaType, nextPage)
                        CatalogCategory.TOP_RATED -> metadataRepo.getTopRatedPaged(mediaType, nextPage)
                    }
                }

                val existingIds = _state.value.items.map { it.id }.toSet()
                val newItems = result.items.filter { it.id !in existingIds }
                _state.update {
                    it.copy(
                        items = it.items + newItems,
                        isLoadingMore = false,
                        currentPage = result.page,
                        totalPages = result.totalPages,
                        hasMore = result.page < result.totalPages,
                    )
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
                val existingIds = _state.value.searchResults.map { it.id }.toSet()
                val newItems = result.items.filter { it.id !in existingIds }
                _state.update {
                    it.copy(
                        searchResults = it.searchResults + newItems,
                        isSearchingMore = false,
                        searchPage = result.page,
                        searchHasMore = result.page < result.totalPages,
                    )
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
                        _state.update {
                            it.copy(
                                searchResults = result.items,
                                isSearching = false,
                                searchPage = result.page,
                                searchHasMore = result.page < result.totalPages,
                            )
                        }
                    } catch (_: Exception) {
                        _state.update { it.copy(isSearching = false) }
                    }
                }
        }
    }

    fun clearSearch() {
        _state.update { it.copy(searchQuery = "", searchResults = emptyList(), searchPage = 1, searchHasMore = false) }
        searchQueryFlow.value = ""
    }

    fun refresh() {
        loadCatalog()
    }
}
