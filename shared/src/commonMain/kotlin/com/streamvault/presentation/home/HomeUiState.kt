package com.streamvault.presentation.home

import com.streamvault.domain.model.CatalogShelf
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.WatchProgress
import com.streamvault.domain.recommendation.ScoredMediaItem

data class HomeUiState(
    val isLoading: Boolean = true,
    val shelves: List<CatalogShelf> = emptyList(),
    val heroItem: MediaItem? = null,
    val continueWatching: List<WatchProgress> = emptyList(),
    val recommendedItems: List<ScoredMediaItem> = emptyList(),
    val watchlistShelf: CatalogShelf? = null,
    val becauseYouWatched: List<CatalogShelf> = emptyList(),
    val hiddenGemsShelf: CatalogShelf? = null,
    val error: String? = null,
)
