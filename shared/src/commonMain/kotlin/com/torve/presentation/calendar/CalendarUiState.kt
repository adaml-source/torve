package com.torve.presentation.calendar

import com.torve.data.trakt.TraktCalendarEpisode

data class CalendarUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val episodes: List<TraktCalendarEpisode> = emptyList(),
    val groupedEpisodes: Map<String, List<TraktCalendarEpisode>> = emptyMap(),
    val traktConnected: Boolean = false,
    val requiresTraktReconnect: Boolean = false,
    val refreshWarning: CalendarStaleReason? = null,
    val episodeNotificationsEnabled: Boolean = false,
)

enum class CalendarStaleReason(val messageKey: String) {
    RATE_LIMITED("calendar_stale_rate_limited"),
    NETWORK("calendar_stale_network"),
    SERVER("calendar_stale_server"),
    UNKNOWN("calendar_stale_unknown"),
}
