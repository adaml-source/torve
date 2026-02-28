package com.streamvault.presentation.stats

import com.streamvault.domain.model.MediaType
import com.streamvault.domain.repository.WatchHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class StatsViewModel(
    private val watchHistoryRepo: WatchHistoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        loadStats()
    }

    fun loadStats() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val history = watchHistoryRepo.getAll()

                val movies = history.count { it.mediaType == MediaType.MOVIE.name || it.mediaType == "movie" }
                val episodes = history.count { it.mediaType == MediaType.SERIES.name || it.mediaType == "tv" }
                val totalMinutes = history.sumOf { it.durationWatchedMs } / 60_000

                // Week / month watch time
                val now = Clock.System.now().toEpochMilliseconds()
                val weekAgo = now - 7 * 24 * 3600 * 1000L
                val monthAgo = now - 30 * 24 * 3600 * 1000L
                val thisWeekMinutes = history
                    .filter { it.watchedAt >= weekAgo }
                    .sumOf { it.durationWatchedMs } / 60_000
                val thisMonthMinutes = history
                    .filter { it.watchedAt >= monthAgo }
                    .sumOf { it.durationWatchedMs } / 60_000

                // Longest consecutive-day streak
                val longestStreak = computeLongestStreak(history.map { it.watchedAt })

                _state.update {
                    it.copy(
                        isLoading = false,
                        totalMovies = movies,
                        totalEpisodes = episodes,
                        totalMinutes = totalMinutes,
                        thisWeekMinutes = thisWeekMinutes,
                        thisMonthMinutes = thisMonthMinutes,
                        longestStreak = longestStreak,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load stats") }
            }
        }
    }

    private fun computeLongestStreak(timestamps: List<Long>): Int {
        if (timestamps.isEmpty()) return 0
        val tz = TimeZone.currentSystemDefault()
        val days = timestamps
            .map { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date }
            .distinct()
            .sorted()
        if (days.isEmpty()) return 0

        var longest = 1
        var current = 1
        for (i in 1 until days.size) {
            val diff = days[i].toEpochDays() - days[i - 1].toEpochDays()
            if (diff == 1) {
                current++
                if (current > longest) longest = current
            } else {
                current = 1
            }
        }
        return longest
    }
}
