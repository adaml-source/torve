package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class CatalogueViewMode(val label: String) {
    GRID("Grid"),
    LIST("List"),
    SHELF("Shelf"),
    COVER("Cover"),
}

@Serializable
enum class CatalogueSortBy(val label: String) {
    RECENTLY_DOWNLOADED("Recently Downloaded"),
    RECENTLY_WATCHED("Recently Watched"),
    TITLE_AZ("Title A\u2013Z"),
    TITLE_ZA("Title Z\u2013A"),
    YEAR_NEWEST("Year (Newest)"),
    YEAR_OLDEST("Year (Oldest)"),
    RATING_HIGHEST("Rating (Highest)"),
    RATING_LOWEST("Rating (Lowest)"),
    FILE_SIZE_LARGEST("Size (Largest)"),
    FILE_SIZE_SMALLEST("Size (Smallest)"),
    EPISODE_COUNT("Episode Count"),
    WATCH_PROGRESS("Watch Progress"),
}

@Serializable
enum class CatalogueFilterType(val label: String) {
    ALL("All"),
    MOVIES_ONLY("Movies"),
    SHOWS_ONLY("TV Shows"),
}

@Serializable
enum class CatalogueWatchFilter(val label: String) {
    ALL("All"),
    UNWATCHED("Unwatched"),
    IN_PROGRESS("In Progress"),
    WATCHED("Watched"),
}

@Serializable
enum class CatalogueGrouping(val label: String) {
    NONE("No Grouping"),
    BY_TYPE("Movies / TV Shows"),
    BY_GENRE("Genre"),
    BY_YEAR("Year"),
    BY_QUALITY("Quality"),
    BY_SOURCE("Download Source"),
    BY_DOWNLOAD_DATE("Date Added"),
}

@Serializable
data class DownloadCataloguePrefs(
    val viewMode: CatalogueViewMode = CatalogueViewMode.GRID,
    val gridColumns: Int = 0,
    val gridColumnsManual: Int = 3,
    val sortBy: CatalogueSortBy = CatalogueSortBy.RECENTLY_DOWNLOADED,
    val sortAscending: Boolean = false,
    val filterType: CatalogueFilterType = CatalogueFilterType.ALL,
    val filterWatchState: CatalogueWatchFilter = CatalogueWatchFilter.ALL,
    val filterGenre: String? = null,
    val filterQuality: String? = null,
    val groupingMode: CatalogueGrouping = CatalogueGrouping.BY_TYPE,
    val showStorageInfo: Boolean = true,
    val showQualityBadges: Boolean = true,
    val showEpisodeCount: Boolean = true,
    val showFileSize: Boolean = true,
    val showDownloadDate: Boolean = false,
    val showWatchProgress: Boolean = true,
    val showContinueWatchingSection: Boolean = true,
    val showRecentlyAddedSection: Boolean = true,
    val autoDeleteWatched: Boolean = false,
    val autoDeleteAfterDays: Int = 30,
)
