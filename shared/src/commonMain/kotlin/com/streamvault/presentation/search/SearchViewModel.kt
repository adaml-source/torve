package com.streamvault.presentation.search

import com.streamvault.domain.model.dedupeByStableKey
import com.streamvault.domain.model.calculateTorveScore
import com.streamvault.domain.model.defaultTorveWeights
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.PreferencesRepository
import com.streamvault.presentation.catalog.SortOption
import com.streamvault.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    private val metadataRepo: MetadataRepository,
    private val prefsRepo: PreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        observeQuery()
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
        queryFlow.value = query
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        scope.launch {
            queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .filter { it.length >= 2 }
                .collect { query ->
                    performSearch(query)
                }
        }
    }

    private suspend fun performSearch(query: String) {
        _state.update { it.copy(isSearching = true, error = null) }
        try {
            val filter = _state.value.filter
            val results = if (filter.mediaType != null) {
                // Search within specific type
                metadataRepo.searchMultiPaged(query, 1, filter.mediaType).items
            } else {
                metadataRepo.searchMulti(query)
            }
            val people = try {
                metadataRepo.searchPerson(query, page = 1)
            } catch (_: Exception) {
                emptyList()
            }

            // Apply client-side filters
            val filtered = results.filter { item ->
                val genreMatch = filter.genreIds.isEmpty() || filter.genreIds.any { it in item.genreIds }
                val ratingMatch = filter.minRating == null || (item.rating ?: 0.0) >= filter.minRating
                val imdbMatch = filter.minImdbScore == null || ((item.ratings?.imdbScore ?: 0f) >= filter.minImdbScore)
                val tmdbMatch = filter.minTmdbScore == null || ((item.ratings?.tmdbScore ?: 0f) >= filter.minTmdbScore)
                val torveMatch = filter.minTorveScore == null || (
                    (item.ratings?.let { calculateTorveScore(it, defaultTorveWeights()) } ?: 0f) >= filter.minTorveScore
                )
                val yearFromMatch = filter.yearFrom == null || (item.year ?: 0) >= filter.yearFrom
                val yearToMatch = filter.yearTo == null || (item.year ?: Int.MAX_VALUE) <= filter.yearTo
                genreMatch && ratingMatch && imdbMatch && tmdbMatch && torveMatch && yearFromMatch && yearToMatch
            }

            val deduped = if (shouldDedupe()) filtered.dedupeByStableKey() else filtered
            val finalResults = when (filter.sortBy) {
                SortOption.TORVE_SCORE_DESC -> deduped.sortedByDescending { item ->
                    item.ratings?.let { calculateTorveScore(it, defaultTorveWeights()) } ?: Float.MIN_VALUE
                }
                else -> deduped
            }
            _state.update {
                it.copy(
                    results = finalResults,
                    peopleResults = people,
                    userLists = buildUserListPlaceholders(query),
                    isSearching = false,
                    hasActiveSearch = true,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(isSearching = false, error = e.message) }
        }
    }

    fun applyFilter(filter: SearchFilter) {
        _state.update { it.copy(filter = filter, showFilterSheet = false) }
        // Re-run search with filters if there's a query
        if (_state.value.query.length >= 2) {
            scope.launch { performSearch(_state.value.query) }
        } else if (filter.isActive) {
            // No text query but filters active — use discover API
            discoverWithFilters(filter)
        }
    }

    private fun discoverWithFilters(filter: SearchFilter) {
        scope.launch {
            _state.update { it.copy(isDiscovering = true, error = null) }
            try {
                val type = filter.mediaType ?: "movie"
                val genresParam = filter.genreIds.takeIf { it.isNotEmpty() }
                    ?.joinToString(",")
                val result = metadataRepo.discover(
                    type = type,
                    sortBy = filter.sortBy.apiValue,
                    withGenres = genresParam,
                    minRating = filter.minRating,
                    year = filter.yearFrom,
                    yearTo = filter.yearTo,
                    runtimeGte = filter.runtimeFilter?.minMinutes,
                    runtimeLte = filter.runtimeFilter?.maxMinutes,
                )
                val preFiltered = result.items.filter { item ->
                    val imdbMatch = filter.minImdbScore == null || ((item.ratings?.imdbScore ?: 0f) >= filter.minImdbScore)
                    val tmdbMatch = filter.minTmdbScore == null || ((item.ratings?.tmdbScore ?: 0f) >= filter.minTmdbScore)
                    val torveMatch = filter.minTorveScore == null || (
                        (item.ratings?.let { calculateTorveScore(it, defaultTorveWeights()) } ?: 0f) >= filter.minTorveScore
                    )
                    imdbMatch && tmdbMatch && torveMatch
                }
                val deduped = if (shouldDedupe()) preFiltered.dedupeByStableKey() else preFiltered
                val finalResults = when (filter.sortBy) {
                    SortOption.TORVE_SCORE_DESC -> deduped.sortedByDescending { item ->
                        item.ratings?.let { calculateTorveScore(it, defaultTorveWeights()) } ?: Float.MIN_VALUE
                    }
                    else -> deduped
                }
                _state.update {
                    it.copy(
                        discoverResults = finalResults,
                        isDiscovering = false,
                        hasActiveSearch = true,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isDiscovering = false, error = e.message) }
            }
        }
    }

    fun toggleFilterSheet() {
        _state.update { it.copy(showFilterSheet = !it.showFilterSheet) }
    }

    fun dismissFilterSheet() {
        _state.update { it.copy(showFilterSheet = false) }
    }

    fun clearFilters() {
        _state.update { it.copy(filter = SearchFilter(), discoverResults = emptyList()) }
        if (_state.value.query.length >= 2) {
            scope.launch { performSearch(_state.value.query) }
        }
    }

    fun clearSearch() {
        _state.update { SearchUiState() }
        queryFlow.value = ""
    }

    private suspend fun shouldDedupe(): Boolean {
        return prefsRepo.getString(SettingsViewModel.KEY_DEDUPE_RESULTS)?.toBooleanStrictOrNull() ?: true
    }

    private fun buildUserListPlaceholders(query: String): List<String> = listOf(
        "My Watchlist matches for \"$query\" (coming soon)",
        "Trakt lists for \"$query\" (coming soon)",
    )
}
