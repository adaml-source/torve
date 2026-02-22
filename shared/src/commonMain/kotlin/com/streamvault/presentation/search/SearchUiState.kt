package com.streamvault.presentation.search

import com.streamvault.domain.model.MediaItem

data class SearchUiState(
    val query: String = "",
    val results: List<MediaItem> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)
