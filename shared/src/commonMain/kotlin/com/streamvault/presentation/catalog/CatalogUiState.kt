package com.streamvault.presentation.catalog

import com.streamvault.domain.model.MediaItem

data class CatalogUiState(
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedCategory: CatalogCategory = CatalogCategory.TRENDING,
    val selectedGenreId: Int? = null,
    val searchQuery: String = "",
    val searchResults: List<MediaItem> = emptyList(),
    val isSearching: Boolean = false,
)

enum class CatalogCategory(val label: String) {
    TRENDING("Trending"),
    POPULAR("Popular"),
    TOP_RATED("Top Rated"),
}
