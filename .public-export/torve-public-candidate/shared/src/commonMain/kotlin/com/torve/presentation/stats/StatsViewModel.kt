package com.torve.presentation.stats

import com.torve.domain.stats.WatchStatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Compatibility stats view model used by the existing mobile stats page and TV
 * Settings > About card. It now reads the watch-session source instead of the
 * misleading legacy watch_history.durationWatchedMs field.
 */
class StatsViewModel(
    private val watchStatsRepository: WatchStatsRepository,
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
            runCatching {
                val summary = watchStatsRepository.getAggregation()
                val sessions = watchStatsRepository.getSessions()
                val now = Clock.System.now().toEpochMilliseconds()
                val weekAgo = now - 7 * 24 * 3600 * 1000L
                val monthAgo = now - 30 * 24 * 3600 * 1000L
                val thisWeekMinutes = sessions
                    .filter { it.startedAt >= weekAgo }
                    .sumOf { it.countedWatchMsForLegacyUi() } / 60_000
                val thisMonthMinutes = sessions
                    .filter { it.startedAt >= monthAgo }
                    .sumOf { it.countedWatchMsForLegacyUi() } / 60_000

                _state.update {
                    it.copy(
                        isLoading = false,
                        totalMovies = summary.completedMovies,
                        totalEpisodes = summary.completedEpisodes,
                        totalMinutes = summary.totalWatchMs / 60_000,
                        thisWeekMinutes = thisWeekMinutes,
                        thisMonthMinutes = thisMonthMinutes,
                        longestStreak = 0,
                        topGenres = emptyList(),
                        activityByDay = emptyMap(),
                        error = null,
                    )
                }
            }.onFailure {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = com.torve.presentation.error.UserFacingError.STATS_LOAD_FAILED.messageKey,
                    )
                }
            }
        }
    }

    private fun com.torve.domain.stats.WatchSession.countedWatchMsForLegacyUi(): Long {
        return if (runtimeConfidence == com.torve.domain.stats.RuntimeConfidence.UNKNOWN) {
            0L
        } else {
            countedWatchMs.coerceAtLeast(0L)
        }
    }
}
