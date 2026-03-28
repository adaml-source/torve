package com.torve.presentation.home

import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.WatchProgress
import com.torve.domain.recommendation.ScoredMediaItem

data class HomeUiState(
    val isLoading: Boolean = true,
    val shelves: List<CatalogShelf> = emptyList(),
    val heroItem: MediaItem? = null,
    val continueWatching: List<WatchProgress> = emptyList(),
    val continueWatchingRatings: Map<String, MediaRatings> = emptyMap(),
    val recommendedItems: List<ScoredMediaItem> = emptyList(),
    val watchlistShelf: CatalogShelf? = null,
    val watchlistItems: List<MediaItem> = emptyList(),
    val becauseYouWatched: List<CatalogShelf> = emptyList(),
    val hiddenGemsShelf: CatalogShelf? = null,
    val recentlyWatched: List<MediaItem> = emptyList(),
    val popularActors: List<PersonSummary> = emptyList(),
    val popularDirectors: List<PersonSummary> = emptyList(),
    val customShelves: Map<String, List<MediaItem>> = emptyMap(),
    val addonShelves: List<CatalogShelf> = emptyList(),
    val addonShelfVisibility: Map<String, Boolean> = emptyMap(),
    val mdbListShelves: List<CatalogShelf> = emptyList(),
    val error: String? = null,
    // Search
    val searchQuery: String = "",
    val searchResults: List<MediaItem> = emptyList(),
    val isSearching: Boolean = false,
)
