package com.torve.presentation.stats

import com.torve.domain.stats.RuntimeConfidence
import com.torve.domain.stats.WatchSessionSource
import com.torve.domain.stats.WatchSessionStatus
import com.torve.domain.stats.WatchStatsSummary
import kotlin.math.roundToLong

data class WatchStatsAboutCardCopy(
    val title: String = "Watch Stats",
    val body: String,
    val cta: String = "Open full stats",
)

object WatchStatsUiText {
    fun aboutCard(summary: WatchStatsSummary): WatchStatsAboutCardCopy {
        val completed = summary.completedMovies + summary.completedEpisodes
        val knownActivity = summary.recentActivity.isNotEmpty() ||
            completed > 0 ||
            summary.partialCount > 0 ||
            summary.startedCount > 0 ||
            summary.abandonedCount > 0 ||
            summary.manualCompletedCount > 0 ||
            summary.importedCompletedCount > 0

        if (!knownActivity) {
            return WatchStatsAboutCardCopy(body = "Start watching to build your stats")
        }

        val rebuilding = summary.hasLegacyUnknownRuntime ||
            summary.runtimeConfidenceBreakdown.any {
                it.confidence == RuntimeConfidence.UNKNOWN && it.sessionCount > 0
            }

        val body = when {
            summary.totalWatchMs > 0L && completed > 0 ->
                "$completed completed - ${formatDuration(summary.totalWatchMs)} watched"
            summary.totalWatchMs > 0L ->
                "${summary.partialCount} partial - ${formatDuration(summary.totalWatchMs)} watched"
            rebuilding ->
                "History available - watch time being rebuilt"
            completed > 0 ->
                "$completed completed - runtime unknown"
            summary.partialCount > 0 ->
                "${summary.partialCount} partial - runtime unknown"
            else ->
                "History available - watch time being rebuilt"
        }
        return WatchStatsAboutCardCopy(body = body)
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalMinutes = (ms / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours <= 0L -> "${minutes}m"
            minutes == 0L -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    fun formatPercent(value: Double): String {
        if (value <= 0.0) return "0%"
        return "${(value * 100.0).roundToLong().coerceIn(0L, 100L)}%"
    }

    fun statusLabel(status: WatchSessionStatus): String = when (status) {
        WatchSessionStatus.STARTED -> "Started"
        WatchSessionStatus.PARTIAL -> "Partial"
        WatchSessionStatus.COMPLETED -> "Completed"
        WatchSessionStatus.MANUAL_COMPLETED -> "Manual completed"
        WatchSessionStatus.IMPORTED_COMPLETED -> "Imported completed"
        WatchSessionStatus.ABANDONED -> "Abandoned"
    }

    fun sourceLabel(source: WatchSessionSource): String = when (source) {
        WatchSessionSource.TORVE_PLAYER -> "Torve"
        WatchSessionSource.TRAKT -> "Trakt"
        WatchSessionSource.SIMKL -> "SIMKL"
        WatchSessionSource.MANUAL -> "Manual"
        WatchSessionSource.MIGRATED_HISTORY -> "Migrated"
    }

    fun confidenceLabel(confidence: RuntimeConfidence): String = when (confidence) {
        RuntimeConfidence.MEASURED -> "Measured"
        RuntimeConfidence.ESTIMATED -> "Estimated"
        RuntimeConfidence.UNKNOWN -> "Unknown runtime"
    }
}
