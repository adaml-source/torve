package com.streamvault.presentation.calendar

import com.streamvault.data.trakt.TraktCalendarEpisode

data class CalendarUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val episodes: List<TraktCalendarEpisode> = emptyList(),
    val groupedEpisodes: Map<String, List<TraktCalendarEpisode>> = emptyMap(),
    val traktConnected: Boolean = false,
)
