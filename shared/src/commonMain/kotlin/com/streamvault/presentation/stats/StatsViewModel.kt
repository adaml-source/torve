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

                _state.update {
                    it.copy(
                        isLoading = false,
                        totalMovies = movies,
                        totalEpisodes = episodes,
                        totalMinutes = totalMinutes,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load stats") }
            }
        }
    }
}
