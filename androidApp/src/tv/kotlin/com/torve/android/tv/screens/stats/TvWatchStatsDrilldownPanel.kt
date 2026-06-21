package com.torve.android.tv.screens.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchStatsSummary
import com.torve.presentation.stats.WatchStatsUiText

@Composable
internal fun TvWatchStatsDrilldownPanel(
    selectedKey: String?,
    summary: WatchStatsSummary,
    recentActivity: List<WatchSession>,
    isPremium: Boolean,
    drilldownRequester: FocusRequester,
    mainStageRequester: FocusRequester,
    onFocused: (FocusRequester) -> Unit,
    onOpenDetails: (WatchSession) -> Unit,
    onUpgrade: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .width(330.dp)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "drilldown_header") {
            TvStatsFocusCard(
                title = selectedKey?.let(::drilldownTitle) ?: "Drilldown",
                subtitle = selectedKey?.let { "Focused insight details" }
                    ?: "Focus a stat card to inspect source and runtime confidence.",
                focusRequester = drilldownRequester,
                left = mainStageRequester,
                onFocused = { onFocused(drilldownRequester) },
                onClick = {},
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (selectedKey == null) {
            item(key = "drilldown_empty") {
                TvStatsFocusCard(
                    title = "Truthful by design",
                    subtitle = "Unknown runtimes are labeled and excluded from total watch time.",
                    left = mainStageRequester,
                    onFocused = {},
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else if (selectedKey.startsWith("advanced") && !isPremium) {
            item(key = "drilldown_locked") {
                TvStatsFocusCard(
                    title = "Unlock deeper watch insights",
                    subtitle = "See advanced trends by genre, rating, year, studio, and source.",
                    meta = "Go Premium",
                    locked = true,
                    left = mainStageRequester,
                    onFocused = {},
                    onClick = onUpgrade,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            if (selectedKey.startsWith("advanced")) {
                item(key = "drilldown_advanced") {
                    TvStatsFocusCard(
                        title = advancedDrilldownTitle(selectedKey),
                        subtitle = advancedDrilldownSubtitle(selectedKey, summary),
                        left = mainStageRequester,
                        onFocused = {},
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        when (selectedKey) {
                            "advanced_genres" -> {
                                if (summary.advanced.genreGroups.isEmpty()) {
                                    TvStatsMetricLine("Unavailable", "No metadata")
                                } else {
                                    summary.advanced.genreGroups.take(6).forEach {
                                        TvStatsMetricLine(
                                            it.label,
                                            "${it.titleCount} titles - ${WatchStatsUiText.formatDuration(it.attributedWatchMs)}",
                                        )
                                    }
                                }
                            }
                            "advanced_ratings" -> {
                                if (summary.advanced.ratingDistribution.isEmpty()) {
                                    TvStatsMetricLine("Unavailable", "No rating data")
                                } else {
                                    summary.advanced.ratingDistribution.take(4).forEach {
                                        TvStatsMetricLine(
                                            it.band.label,
                                            "${it.titleCount} titles - ${WatchStatsUiText.formatDuration(it.attributedWatchMs)}",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "drilldown_totals") {
                TvStatsFocusCard(
                    title = "Current totals",
                    subtitle = WatchStatsUiText.formatDuration(summary.totalWatchMs),
                    left = mainStageRequester,
                    onFocused = {},
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TvStatsMetricLine("Completed movies", summary.completedMovies.toString())
                    TvStatsMetricLine("Completed episodes", summary.completedEpisodes.toString())
                    TvStatsMetricLine("Partial sessions", summary.partialCount.toString())
                    TvStatsMetricLine("Measured", WatchStatsUiText.formatDuration(summary.measuredWatchMs))
                    TvStatsMetricLine("Estimated", WatchStatsUiText.formatDuration(summary.estimatedWatchMs))
                }
            }

            item(key = "drilldown_source") {
                TvStatsFocusCard(
                    title = "Source split",
                    left = mainStageRequester,
                    onFocused = {},
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    summary.sourceBreakdown
                        .filter { it.sessionCount > 0 }
                        .forEach {
                            TvStatsMetricLine(
                                WatchStatsUiText.sourceLabel(it.source),
                                "${it.sessionCount} • ${WatchStatsUiText.formatDuration(it.countedWatchMs)}",
                            )
                        }
                }
            }

            item(key = "drilldown_confidence") {
                TvStatsFocusCard(
                    title = "Runtime confidence",
                    left = mainStageRequester,
                    onFocused = {},
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    summary.runtimeConfidenceBreakdown
                        .filter { it.sessionCount > 0 }
                        .forEach {
                            TvStatsMetricLine(
                                WatchStatsUiText.confidenceLabel(it.confidence),
                                "${it.sessionCount} • ${WatchStatsUiText.formatDuration(it.countedWatchMs)}",
                            )
                        }
                }
            }
        }

        item(key = "drilldown_recent_label") {
            Text(
                text = "Recent activity",
                style = MaterialTheme.typography.labelLarge,
                color = Ash,
                fontWeight = FontWeight.SemiBold,
            )
        }

        items(
            items = recentActivity.take(6),
            key = { "drilldown_recent_${it.id}" },
        ) { session ->
            val requester = remember(session.id) { FocusRequester() }
            TvStatsFocusCard(
                title = session.title,
                subtitle = session.watchStatsSecondaryTitle(),
                meta = "${WatchStatsUiText.sourceLabel(session.source)} • ${WatchStatsUiText.statusLabel(session.status)}",
                focusRequester = requester,
                left = mainStageRequester,
                onFocused = { onFocused(requester) },
                onClick = { onOpenDetails(session) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvStatsSourceChip(WatchStatsUiText.confidenceLabel(session.runtimeConfidence))
                    Text(
                        text = WatchStatsUiText.formatDuration(session.countedWatchMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Silver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item(key = "drilldown_bottom_space") {
            Column(Modifier.height(32.dp)) {}
        }
    }
}

private fun drilldownTitle(key: String): String = when (key) {
    "time" -> "Time watched"
    "completed" -> "Completed"
    "partial" -> "Partial sessions"
    "sources" -> "Source breakdown"
    "confidence" -> "Runtime confidence"
    "recent" -> "Recent activity"
    "advanced_trends" -> "Advanced trends"
    "advanced_genres" -> "Genre trends"
    "advanced_ratings" -> "Rating distribution"
    else -> "Insight"
}

private fun advancedDrilldownTitle(key: String): String = when (key) {
    "advanced_genres" -> "Genre attribution"
    "advanced_ratings" -> "Rating bands"
    else -> "Advanced metadata"
}

private fun advancedDrilldownSubtitle(key: String, summary: WatchStatsSummary): String = when (key) {
    "advanced_genres" -> if (summary.advanced.genreGroups.isEmpty()) {
        "Genre filters appear after Torve has local genre metadata."
    } else {
        "Genre watch time is attributed and does not change headline totals."
    }
    "advanced_ratings" -> if (summary.advanced.ratingDistribution.isEmpty()) {
        "Rating distribution appears after cached rating metadata exists."
    } else {
        "Missing ratings are ignored instead of treated as zero."
    }
    else -> "Advanced metadata sections stay hidden when data is unavailable."
}

internal fun WatchSession.watchStatsSecondaryTitle(): String {
    val episode = seasonNumber?.let { season ->
        episodeNumber?.let { episode -> "S${season} E${episode}" }
    }
    val safeShowTitle = showTitle?.takeIf { it.isNotBlank() }
    return when {
        safeShowTitle != null && episode != null -> "$safeShowTitle • $episode"
        safeShowTitle != null -> safeShowTitle
        episode != null -> episode
        else -> WatchStatsUiText.statusLabel(status)
    }
}
