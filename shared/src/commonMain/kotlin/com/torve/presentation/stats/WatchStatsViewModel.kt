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

class WatchStatsViewModel(
    private val watchStatsRepository: WatchStatsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(WatchStatsUiState())
    val state: StateFlow<WatchStatsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val filters = _state.value.filters
            runCatching {
                val summary = watchStatsRepository.getAggregation(filters.toDomain())
                _state.update {
                    it.copy(
                        isLoading = false,
                        summary = summary,
                        recentActivity = summary.recentActivity,
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

    fun updateFilters(filters: WatchStatsFilterState) {
        _state.update { it.copy(filters = filters) }
        load()
    }

    fun selectInsight(key: String?) {
        _state.update { it.copy(selectedInsightKey = key) }
    }
}
