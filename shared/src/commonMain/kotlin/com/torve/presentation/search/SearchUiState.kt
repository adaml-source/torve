package com.torve.presentation.search

import com.torve.domain.model.MediaItem
import com.torve.domain.model.PersonSummary
import com.torve.presentation.catalog.RuntimeFilter
import com.torve.presentation.catalog.SortOption

data class SearchFilter(
    val mediaType: String? = null, // "movie", "tv", or null for both
    val providersAvailabilityOnly: Boolean = false, // placeholder; provider wiring lands later
    val genreIds: List<Int> = emptyList(),
    val minRating: Float? = null,
    val minImdbScore: Float? = null,
    val minTmdbScore: Float? = null,
    val minTorveScore: Float? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val runtimeFilter: RuntimeFilter? = null,
    val sortBy: SortOption = SortOption.POPULARITY_DESC,
) {
    // Keep backward compat for single-genre access
    val genreId: Int? get() = genreIds.firstOrNull()

    val isActive: Boolean
        get() = mediaType != null || providersAvailabilityOnly || genreIds.isNotEmpty() || minRating != null ||
            minImdbScore != null || minTmdbScore != null || minTorveScore != null ||
            yearFrom != null || yearTo != null || runtimeFilter != null ||
            sortBy != SortOption.POPULARITY_DESC

    val activeCount: Int
        get() {
            var count = 0
            if (mediaType != null) count++
            if (providersAvailabilityOnly) count++
            if (genreIds.isNotEmpty()) count++
            if (minRating != null) count++
            if (minImdbScore != null) count++
            if (minTmdbScore != null) count++
            if (minTorveScore != null) count++
            if (yearFrom != null || yearTo != null) count++
            if (runtimeFilter != null) count++
            if (sortBy != SortOption.POPULARITY_DESC) count++
            return count
        }
}

data class SearchUiState(
    val query: String = "",
    val results: List<MediaItem> = emptyList(),
    val hiddenResultsCount: Int = 0,
    val isSearching: Boolean = false,
    val error: String? = null,
    val filter: SearchFilter = SearchFilter(),
    val showFilterSheet: Boolean = false,
    val discoverResults: List<MediaItem> = emptyList(),
    val peopleResults: List<PersonSummary> = emptyList(),
    val userLists: List<String> = emptyList(),
    val isDiscovering: Boolean = false,
    val hasActiveSearch: Boolean = false,
)
