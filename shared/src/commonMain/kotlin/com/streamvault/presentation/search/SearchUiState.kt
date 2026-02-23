package com.streamvault.presentation.search

import com.streamvault.domain.model.MediaItem

data class SearchFilter(
    val mediaType: String? = null, // "movie", "tv", or null for both
    val genreId: Int? = null,
    val minRating: Float? = null,
    val year: Int? = null,
) {
    val isActive: Boolean get() = mediaType != null || genreId != null || minRating != null || year != null
    val activeCount: Int get() = listOfNotNull(mediaType, genreId, minRating, year).size
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
