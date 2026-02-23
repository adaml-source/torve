package com.streamvault.presentation.search

import com.streamvault.domain.model.MediaItem
import com.streamvault.presentation.catalog.RuntimeFilter
import com.streamvault.presentation.catalog.SortOption

data class SearchFilter(
    val mediaType: String? = null, // "movie", "tv", or null for both
    val genreIds: List<Int> = emptyList(),
    val minRating: Float? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val runtimeFilter: RuntimeFilter? = null,
    val sortBy: SortOption = SortOption.POPULARITY_DESC,
) {
    // Keep backward compat for single-genre access
    val genreId: Int? get() = genreIds.firstOrNull()

    val isActive: Boolean
        get() = mediaType != null || genreIds.isNotEmpty() || minRating != null ||
            yearFrom != null || yearTo != null || runtimeFilter != null ||
            sortBy != SortOption.POPULARITY_DESC

    val activeCount: Int
        get() {
            var count = 0
            if (mediaType != null) count++
            if (genreIds.isNotEmpty()) count++
            if (minRating != null) count++
            if (yearFrom != null || yearTo != null) count++
            if (runtimeFilter != null) count++
            if (sortBy != SortOption.POPULARITY_DESC) count++
            return count
        }
}

data class SearchUiState(
    val query: String = "",
    val results: List<MediaItem> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val filter: SearchFilter = SearchFilter(),
    val showFilterSheet: Boolean = false,
    val discoverResults: List<MediaItem> = emptyList(),
    val isDiscovering: Boolean = false,
)
