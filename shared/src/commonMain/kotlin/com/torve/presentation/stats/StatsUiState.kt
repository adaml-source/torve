package com.torve.presentation.stats

data class StatsUiState(
    val isLoading: Boolean = true,
    val totalMovies: Int = 0,
    val totalEpisodes: Int = 0,
    val totalMinutes: Long = 0,
    val thisWeekMinutes: Long = 0,
    val thisMonthMinutes: Long = 0,
    val longestStreak: Int = 0,
    val topGenres: List<GenreStat> = emptyList(),
    val activityByDay: Map<String, Int> = emptyMap(),
    val error: String? = null,
)

data class GenreStat(
    val name: String,
    val count: Int,
)
