package com.torve.presentation.watchlist

import com.torve.domain.model.WatchlistItem

data class WatchlistUiState(
    val items: List<WatchlistItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val snackbarMessage: String? = null,
    val watchlistIds: Set<String> = emptySet(),
)
