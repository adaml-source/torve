package com.torve.presentation.discover

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DiscoverViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    companion object {
        val MOVIE_GENRES = listOf(
            GenreDisplay(28, "Action"),
            GenreDisplay(12, "Adventure"),
            GenreDisplay(16, "Animation"),
            GenreDisplay(35, "Comedy"),
            GenreDisplay(80, "Crime"),
            GenreDisplay(99, "Documentary"),
            GenreDisplay(18, "Drama"),
            GenreDisplay(10751, "Family"),
            GenreDisplay(14, "Fantasy"),
            GenreDisplay(36, "History"),
            GenreDisplay(27, "Horror"),
            GenreDisplay(10402, "Music"),
            GenreDisplay(9648, "Mystery"),
            GenreDisplay(10749, "Romance"),
            GenreDisplay(878, "Sci-Fi"),
            GenreDisplay(53, "Thriller"),
            GenreDisplay(10752, "War"),
            GenreDisplay(37, "Western"),
        )

        val TV_GENRES = listOf(
            GenreDisplay(10759, "Action & Adventure"),
            GenreDisplay(16, "Animation"),
            GenreDisplay(35, "Comedy"),
            GenreDisplay(80, "Crime"),
            GenreDisplay(99, "Documentary"),
            GenreDisplay(18, "Drama"),
            GenreDisplay(10751, "Family"),
            GenreDisplay(10762, "Kids"),
            GenreDisplay(9648, "Mystery"),
            GenreDisplay(10764, "Reality"),
            GenreDisplay(10765, "Sci-Fi & Fantasy"),
            GenreDisplay(53, "Thriller"),
            GenreDisplay(10768, "War & Politics"),
            GenreDisplay(37, "Western"),
        )
    }

    init {
        updateGenres()
    }

    fun selectTab(tab: DiscoverTab) {
        _state.update { it.copy(selectedTab = tab) }
        updateGenres()
    }

    private fun updateGenres() {
        val genres = when (_state.value.selectedTab) {
            DiscoverTab.MOVIES -> MOVIE_GENRES
            DiscoverTab.TV_SHOWS -> TV_GENRES
            DiscoverTab.LIVE_TV -> emptyList()
        }
        _state.update { it.copy(genres = genres) }
    }
}
