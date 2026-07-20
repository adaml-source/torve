package com.torve.presentation.stats

import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchStatsSummary

data class WatchStatsUiState(
    val isLoading: Boolean = true,
    val filters: WatchStatsFilterState = WatchStatsFilterState(),
    val summary: WatchStatsSummary = WatchStatsSummary(),
    val recentActivity: List<WatchSession> = emptyList(),
    val selectedInsightKey: String? = null,
    val error: String? = null,
) {
    val isEmpty: Boolean
        get() = !isLoading && recentActivity.isEmpty() && summary.totalWatchMs == 0L

    val hasPartialLegacyData: Boolean
        get() = summary.hasLegacyUnknownRuntime
}
