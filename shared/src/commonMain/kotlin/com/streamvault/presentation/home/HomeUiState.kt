package com.streamvault.presentation.home

import com.streamvault.domain.model.CatalogShelf
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.PersonSummary
import com.streamvault.domain.model.WatchProgress
import com.streamvault.domain.recommendation.ScoredMediaItem

data class HomeUiState(
    val isLoading: Boolean = true,
    val shelves: List<CatalogShelf> = emptyList(),
    val heroItem: MediaItem? = null,
    val continueWatching: List<WatchProgress> = emptyList(),
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
    val error: String? = null,
)
