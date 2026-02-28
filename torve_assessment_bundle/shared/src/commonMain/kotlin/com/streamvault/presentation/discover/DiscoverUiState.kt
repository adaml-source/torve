package com.streamvault.presentation.discover

data class DiscoverUiState(
    val selectedTab: DiscoverTab = DiscoverTab.MOVIES,
    val genres: List<GenreDisplay> = emptyList(),
)

enum class DiscoverTab(val label: String) {
    MOVIES("Movies"),
    TV_SHOWS("TV Shows"),
    LIVE_TV("Live TV"),
}

data class GenreDisplay(val id: Int, val name: String)
