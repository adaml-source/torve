package com.torve.presentation.catalog

import com.torve.domain.discovery.DiscoveryRatingSource
import com.torve.domain.model.MediaItem
import com.torve.domain.model.WatchProgress

enum class CatalogViewMode { LIST, GRID_SMALL, GRID_MEDIUM, GRID_LARGE }

data class CatalogUiState(
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val selectedCategory: CatalogCategory = CatalogCategory.TRENDING,
    val selectedGenreId: Int? = null,
    val searchQuery: String = "",
    val searchResults: List<MediaItem> = emptyList(),
    val isSearching: Boolean = false,
    // Pagination
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val hasMore: Boolean = false,
    // Filters
    val filter: CatalogFilter = CatalogFilter(),
    val showFilterSheet: Boolean = false,
    val activeFilterCount: Int = 0,
    // Search pagination
    val searchPage: Int = 1,
    val searchHasMore: Boolean = false,
    val isSearchingMore: Boolean = false,
    // Home-style shelves
    val continueWatching: List<WatchProgress> = emptyList(),
    val trendingItems: List<MediaItem> = emptyList(),
    val popularItems: List<MediaItem> = emptyList(),
    val topRatedItems: List<MediaItem> = emptyList(),
    val shelvesLoaded: Boolean = false,
    // Provider filter (TMDB watch provider ID)
    val providerId: Int? = null,
    // View mode selector
    val viewMode: CatalogViewMode = CatalogViewMode.GRID_MEDIUM,
    // AI search
    val isAiSearching: Boolean = false,
    val aiSearchLabel: String? = null,
    val aiSearchError: String? = null,
    // Active search indicator — true = results showing from a previous search
    val hasActiveSearch: Boolean = false,
)

enum class CatalogCategory(val label: String) {
    TRENDING("Trending"),
    POPULAR("Popular"),
    TOP_RATED("Top Rated"),
    IN_PROGRESS("In Progress"),
}

data class CatalogFilter(
    val minRating: Float? = null,
    val ratingSource: DiscoveryRatingSource = DiscoveryRatingSource.TMDB,
    val year: Int? = null,
    val yearTo: Int? = null,
    val runtimeFilter: RuntimeFilter? = null,
    val sortBy: SortOption = SortOption.POPULARITY_DESC,
) {
    val isActive: Boolean
        get() = minRating != null || year != null || yearTo != null ||
            runtimeFilter != null || sortBy != SortOption.POPULARITY_DESC

    val activeCount: Int
        get() {
            var count = 0
            if (minRating != null) count++
            if (year != null || yearTo != null) count++
            if (runtimeFilter != null) count++
            if (sortBy != SortOption.POPULARITY_DESC) count++
            return count
        }
}

enum class RuntimeFilter(val label: String, val minMinutes: Int?, val maxMinutes: Int?) {
    SHORT("Short (<90min)", null, 90),
    STANDARD("Standard (90-150min)", 90, 150),
    LONG("Long (>150min)", 150, null),
}

enum class SortOption(val apiValue: String, val label: String) {
    POPULARITY_DESC("popularity.desc", "Most Popular"),
    TORVE_SCORE_DESC("popularity.desc", "Torve Score"),
    VOTE_AVERAGE_DESC("vote_average.desc", "Highest TMDB Rating"),
    IMDB_SCORE_DESC("popularity.desc", "Highest IMDB Rating"),
    VOTE_COUNT_DESC("vote_count.desc", "Most Votes"),
    RELEASE_DATE_DESC("primary_release_date.desc", "Newest First"),
    RELEASE_DATE_ASC("primary_release_date.asc", "Oldest First"),
    REVENUE_DESC("revenue.desc", "Highest Revenue"),
}
