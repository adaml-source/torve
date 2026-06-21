package com.torve.presentation.stats

import com.torve.domain.stats.RuntimeConfidence
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchStatsAdvancedGroup
import com.torve.domain.stats.WatchStatsRatingGroup
import com.torve.domain.stats.WatchStatsSummary

enum class WatchStatsContentScope(
    val id: String,
    val label: String,
) {
    ALL("scope_all", "All"),
    MOVIES("scope_movies", "Movies"),
    SHOWS("scope_shows", "Shows"),
}

enum class WatchStatsInsightMode(
    val id: String,
    val label: String,
) {
    OVERVIEW("mode_overview", "Overview"),
    SOURCE("mode_source", "Source"),
    RATINGS("mode_ratings", "Ratings"),
    GENRES("mode_genres", "Genres"),
    YEARS("mode_years", "Years"),
    ACTIVITY("mode_activity", "Activity"),
}

enum class WatchStatsTvFilter(
    val id: String,
    val label: String,
) {
    ALL("filter_all", "All"),
    MOVIES("filter_movies", "Movies"),
    SHOWS("filter_shows", "Shows"),
    OVERVIEW("filter_insight_all", "All"),
    SOURCE("filter_source", "Source"),
    RATINGS("filter_ratings", "Ratings"),
    GENRES("filter_genres", "Genres"),
    YEARS("filter_years", "Years"),
    ACTIVITY("filter_activity", "Activity"),
}

enum class WatchStatsTvSection(
    val id: String,
    val label: String,
) {
    OVERVIEW("section_overview", "Overview"),
    TIME("section_time", "Time"),
    COMPLETED("section_completed", "Completed"),
    SOURCES("section_sources", "Sources"),
    ACTIVITY("section_activity", "Activity"),
    ADVANCED("section_advanced", "Advanced"),
}

enum class WatchStatsTvFocusableType {
    SECTION,
    STAT_CARD,
    ACTIVITY_ROW,
    LOCKED_CARD,
    UNAVAILABLE_CARD,
}

data class WatchStatsTvFocusableItem(
    val id: String,
    val type: WatchStatsTvFocusableType,
    val label: String,
)

data class WatchStatsTvCardModel(
    val id: String,
    val title: String,
    val value: String,
    val subtitle: String,
    val footer: String,
    val insightKey: String,
    val supportingLines: List<String> = emptyList(),
    val locked: Boolean = false,
    val unavailable: Boolean = false,
)

data class WatchStatsTvDashboardModel(
    val contentScope: WatchStatsContentScope,
    val insightMode: WatchStatsInsightMode,
    val title: String,
    val subtitle: String,
    val metricCards: List<WatchStatsTvCardModel>,
    val primaryTitle: String,
    val secondaryTitle: String,
    val topListTitle: String,
    val detailTitle: String,
    val heroInsight: WatchStatsHeroInsightModel? = null,
    val unavailableTitle: String? = null,
    val unavailableBody: String? = null,
    val lockedTitle: String? = null,
    val lockedBody: String? = null,
) {
    val isUnavailable: Boolean
        get() = unavailableTitle != null

    val isLocked: Boolean
        get() = lockedTitle != null
}

data class WatchStatsHeroInsightModel(
    val id: String,
    val title: String,
    val value: String,
    val body: String,
    val chips: List<String>,
)

object WatchStatsTvUiModel {
    fun sectionItems(): List<WatchStatsTvFocusableItem> =
        WatchStatsTvSection.entries.map {
            WatchStatsTvFocusableItem(
                id = it.id,
                type = WatchStatsTvFocusableType.SECTION,
                label = it.label,
            )
        }

    fun contentScopeItems(): List<WatchStatsTvFocusableItem> =
        WatchStatsContentScope.entries.map {
            WatchStatsTvFocusableItem(
                id = it.id,
                type = WatchStatsTvFocusableType.SECTION,
                label = it.label,
            )
        }

    fun insightModeItems(): List<WatchStatsTvFocusableItem> =
        WatchStatsInsightMode.entries.map {
            WatchStatsTvFocusableItem(
                id = it.id,
                type = WatchStatsTvFocusableType.SECTION,
                label = it.label,
            )
        }

    fun filterItems(): List<WatchStatsTvFocusableItem> =
        WatchStatsTvFilter.entries.map {
            WatchStatsTvFocusableItem(
                id = it.id,
                type = WatchStatsTvFocusableType.SECTION,
                label = it.label,
            )
        }

    fun overviewCards(
        summary: WatchStatsSummary,
        isPremium: Boolean,
    ): List<WatchStatsTvCardModel> {
        val completed = summary.completedMovies + summary.completedEpisodes
        val timeValue = if (summary.totalWatchMs > 0L) {
            WatchStatsUiText.formatDuration(summary.totalWatchMs)
        } else {
            "No time yet"
        }
        val completedValue = when {
            completed > 0 -> countLabel(completed, "item")
            else -> "None yet"
        }
        val sourceValue = sourceHeadline(summary)
        val rebuildValue = if (summary.hasLegacyUnknownRuntime || unknownRuntimeCount(summary) > 0) {
            "Rebuilding"
        } else {
            "Labeled"
        }

        return listOf(
            WatchStatsTvCardModel(
                id = "stat_time",
                title = "Watch time",
                value = timeValue,
                subtitle = "Measured ${WatchStatsUiText.formatDuration(summary.measuredWatchMs)} / Estimated ${WatchStatsUiText.formatDuration(summary.estimatedWatchMs)}",
                supportingLines = listOf(
                    "Measured ${WatchStatsUiText.formatDuration(summary.measuredWatchMs)}",
                    "Estimated ${WatchStatsUiText.formatDuration(summary.estimatedWatchMs)}",
                ),
                footer = "Unknown runtimes excluded",
                insightKey = "time",
            ),
            WatchStatsTvCardModel(
                id = "stat_completed",
                title = "Completed",
                value = completedValue,
                subtitle = "${countLabel(summary.completedMovies, "movie")} / ${countLabel(summary.completedEpisodes, "episode")}",
                supportingLines = listOf(
                    countLabel(summary.completedMovies, "movie"),
                    countLabel(summary.completedEpisodes, "episode"),
                ),
                footer = "${summary.partialCount} partial",
                insightKey = "completed",
            ),
            WatchStatsTvCardModel(
                id = "stat_sources",
                title = "Sources",
                value = sourceValue,
                subtitle = "Torve playback appears after watching in Torve",
                supportingLines = listOf("Real sources only"),
                footer = "Clearly labeled",
                insightKey = "sources",
            ),
            WatchStatsTvCardModel(
                id = "stat_truthful",
                title = "Runtime labels",
                value = rebuildValue,
                subtitle = "Older items without runtime are excluded from totals",
                supportingLines = if (rebuildValue == "Rebuilding") {
                    listOf("Unknown excluded", "Estimated separate")
                } else {
                    listOf("Measured, estimated", "Unknown separate")
                },
                footer = if (isPremium) "Truthful totals" else "Basic stats",
                insightKey = "confidence",
            ),
        )
    }

    fun dashboardModel(
        contentScope: WatchStatsContentScope,
        insightMode: WatchStatsInsightMode,
        summary: WatchStatsSummary,
        isPremium: Boolean,
    ): WatchStatsTvDashboardModel =
        when (insightMode) {
            WatchStatsInsightMode.OVERVIEW -> WatchStatsTvDashboardModel(
                contentScope = contentScope,
                insightMode = insightMode,
                title = scopeTitle(contentScope, "watch stats"),
                subtitle = scopeSubtitle(contentScope, "Overview", "truthful totals and recent activity"),
                metricCards = scopeOverviewCards(contentScope, summary, isPremium),
                primaryTitle = recentRuntimeTitle(summary.recentActivity),
                secondaryTitle = "Source breakdown",
                topListTitle = "Top movies",
                detailTitle = "Recent activity",
                heroInsight = heroInsight(contentScope, summary),
            )
            WatchStatsInsightMode.SOURCE -> WatchStatsTvDashboardModel(
                contentScope = contentScope,
                insightMode = insightMode,
                title = scopeTitle(contentScope, "source breakdown"),
                subtitle = scopeSubtitle(contentScope, "Source", "recorded source labels and runtime confidence"),
                metricCards = sourceCards(contentScope, summary),
                primaryTitle = "Source split",
                secondaryTitle = "Runtime confidence",
                topListTitle = "Source detail",
                detailTitle = "Recent source activity",
                unavailableTitle = if (summary.sourceBreakdown.none { it.sessionCount > 0 }) "No source activity yet" else null,
                unavailableBody = if (summary.sourceBreakdown.none { it.sessionCount > 0 }) "Watch in Torve, mark items watched, or import history to build source stats." else null,
            )
            WatchStatsInsightMode.GENRES -> advancedModel(
                contentScope = contentScope,
                insightMode = insightMode,
                isPremium = isPremium,
                hasData = summary.advanced.availability.hasGenres,
                groups = summary.advanced.genreGroups,
                title = scopeTitle(contentScope, "genre insights"),
                subtitle = scopeSubtitle(contentScope, "Genres", "attributed watch time from local genre metadata"),
                unavailableTitle = "Genre insights unavailable",
                unavailableBody = "No local genre metadata is available for this selection yet. Genre insights use local metadata only.",
            )
            WatchStatsInsightMode.YEARS -> advancedModel(
                contentScope = contentScope,
                insightMode = insightMode,
                isPremium = isPremium,
                hasData = summary.advanced.availability.hasYears,
                groups = summary.advanced.yearGroups,
                title = yearTitle(contentScope),
                subtitle = yearSubtitle(contentScope),
                unavailableTitle = "Year insights unavailable",
                unavailableBody = "This selection does not have reliable local year metadata yet. Year insights use local metadata only and do not trigger network lookups.",
            )
            WatchStatsInsightMode.RATINGS -> ratingModel(contentScope, summary.advanced.ratingDistribution, isPremium)
            WatchStatsInsightMode.ACTIVITY -> WatchStatsTvDashboardModel(
                contentScope = contentScope,
                insightMode = insightMode,
                title = scopeTitle(contentScope, "recent activity"),
                subtitle = scopeSubtitle(contentScope, "Activity", "local sessions with source, status, and runtime labels"),
                metricCards = activityCards(contentScope, summary),
                primaryTitle = "Recent activity",
                secondaryTitle = "Runtime confidence",
                topListTitle = "Activity table",
                detailTitle = "Recent activity",
            )
        }

    fun activityItems(sessions: List<WatchSession>): List<WatchStatsTvFocusableItem> =
        sessions.map {
            WatchStatsTvFocusableItem(
                id = "activity_${it.id}",
                type = WatchStatsTvFocusableType.ACTIVITY_ROW,
                label = it.title,
            )
        }

    fun hasDuplicateIds(items: List<WatchStatsTvFocusableItem>): Boolean =
        items.map { it.id }.distinct().size != items.size

    private fun unknownRuntimeCount(summary: WatchStatsSummary): Int =
        summary.runtimeConfidenceBreakdown
            .filter { it.confidence == RuntimeConfidence.UNKNOWN }
            .sumOf { it.sessionCount }

    private fun scopeOverviewCards(
        contentScope: WatchStatsContentScope,
        summary: WatchStatsSummary,
        isPremium: Boolean,
    ): List<WatchStatsTvCardModel> =
        when (contentScope) {
            WatchStatsContentScope.ALL -> overviewCards(summary, isPremium)
            WatchStatsContentScope.MOVIES -> movieCards(summary)
            WatchStatsContentScope.SHOWS -> showCards(summary)
        }

    private fun movieCards(summary: WatchStatsSummary): List<WatchStatsTvCardModel> =
        listOf(
            WatchStatsTvCardModel(
                id = "stat_movie_time",
                title = "Movie time",
                value = WatchStatsUiText.formatDuration(summary.totalWatchMs),
                subtitle = "Known movie runtime only",
                supportingLines = listOf(
                    "Measured ${WatchStatsUiText.formatDuration(summary.measuredWatchMs)}",
                    "Estimated ${WatchStatsUiText.formatDuration(summary.estimatedWatchMs)}",
                ),
                footer = "Unknown excluded",
                insightKey = "movies_time",
            ),
            WatchStatsTvCardModel(
                id = "stat_movie_completed",
                title = "Movies completed",
                value = countLabel(summary.completedMovies, "movie"),
                subtitle = "Completed movie sessions",
                supportingLines = listOf("${summary.completedMovies} completed"),
                footer = "85% threshold",
                insightKey = "movies_completed",
            ),
            WatchStatsTvCardModel(
                id = "stat_movie_partial",
                title = "Partial movies",
                value = "${summary.partialCount}",
                subtitle = "Partial sessions stay separate",
                supportingLines = listOf("${summary.partialCount} partial", "${summary.abandonedCount} abandoned"),
                footer = "Not completed",
                insightKey = "movies_partial",
            ),
            WatchStatsTvCardModel(
                id = "stat_movie_source",
                title = "Top source",
                value = sourceHeadline(summary),
                subtitle = "Movie source labels",
                supportingLines = listOf("Real sources only"),
                footer = "Labeled",
                insightKey = "movies_source",
            ),
        )

    private fun showCards(summary: WatchStatsSummary): List<WatchStatsTvCardModel> =
        listOf(
            WatchStatsTvCardModel(
                id = "stat_show_time",
                title = "Episode time",
                value = WatchStatsUiText.formatDuration(summary.totalWatchMs),
                subtitle = "Known episode runtime only",
                supportingLines = listOf(
                    "Measured ${WatchStatsUiText.formatDuration(summary.measuredWatchMs)}",
                    "Estimated ${WatchStatsUiText.formatDuration(summary.estimatedWatchMs)}",
                ),
                footer = "Unknown excluded",
                insightKey = "shows_time",
            ),
            WatchStatsTvCardModel(
                id = "stat_show_completed",
                title = "Episodes completed",
                value = countLabel(summary.completedEpisodes, "episode"),
                subtitle = "Episode completions",
                supportingLines = listOf("${summary.completedEpisodes} episodes"),
                footer = "85% threshold",
                insightKey = "shows_completed",
            ),
            WatchStatsTvCardModel(
                id = "stat_show_partial",
                title = "Partial episodes",
                value = "${summary.partialCount}",
                subtitle = "Partial episodes stay separate",
                supportingLines = listOf("${summary.partialCount} partial", "${summary.startedCount} started"),
                footer = "Not completed",
                insightKey = "shows_partial",
            ),
            WatchStatsTvCardModel(
                id = "stat_show_source",
                title = "Top source",
                value = sourceHeadline(summary),
                subtitle = "Show source labels",
                supportingLines = listOf("Real sources only"),
                footer = "Labeled",
                insightKey = "shows_source",
            ),
        )

    private fun sourceCards(
        contentScope: WatchStatsContentScope,
        summary: WatchStatsSummary,
    ): List<WatchStatsTvCardModel> =
        listOf(
            WatchStatsTvCardModel(
                id = "stat_source_dominant",
                title = "Dominant source",
                value = sourceHeadline(summary),
                subtitle = "${contentScope.label} source split",
                supportingLines = listOf("${contentScope.label} activity", "Only recorded sources"),
                footer = "Real labels",
                insightKey = "source_dominant",
            ),
            WatchStatsTvCardModel(
                id = "stat_source_imported",
                title = "Imported",
                value = "${summary.importedCompletedCount}",
                subtitle = "Imported completed items",
                supportingLines = listOf("Completed imports", "Estimated or unknown"),
                footer = "Not measured",
                insightKey = "source_imported",
            ),
            WatchStatsTvCardModel(
                id = "stat_source_measured",
                title = "Measured",
                value = WatchStatsUiText.formatDuration(summary.measuredWatchMs),
                subtitle = "Torve player watch time",
                supportingLines = listOf("Torve player", "Real playback"),
                footer = "Player only",
                insightKey = "source_measured",
            ),
            WatchStatsTvCardModel(
                id = "stat_source_unknown",
                title = "Unknown runtime",
                value = "${unknownRuntimeCount(summary)}",
                subtitle = "Excluded from watch time",
                supportingLines = listOf("Excluded from total", "Needs runtime"),
                footer = "Safe totals",
                insightKey = "source_unknown",
            ),
        )

    private fun activityCards(
        contentScope: WatchStatsContentScope,
        summary: WatchStatsSummary,
    ): List<WatchStatsTvCardModel> =
        listOf(
            WatchStatsTvCardModel(
                id = "stat_activity_count",
                title = "Rows",
                value = "${summary.recentActivity.size}",
                subtitle = "${contentScope.label} sessions in this view",
                supportingLines = listOf("${summary.recentActivity.size} local rows"),
                footer = "Local only",
                insightKey = "activity_rows",
            ),
            WatchStatsTvCardModel(
                id = "stat_activity_time",
                title = "Counted time",
                value = WatchStatsUiText.formatDuration(summary.totalWatchMs),
                subtitle = "Unknown runtimes excluded",
                supportingLines = listOf(
                    "Measured ${WatchStatsUiText.formatDuration(summary.measuredWatchMs)}",
                    "Estimated ${WatchStatsUiText.formatDuration(summary.estimatedWatchMs)}",
                ),
                footer = "Truthful",
                insightKey = "activity_time",
            ),
            WatchStatsTvCardModel(
                id = "stat_activity_completed",
                title = "Completed",
                value = "${summary.completedMovies + summary.completedEpisodes}",
                subtitle = "Completed items",
                supportingLines = listOf("${summary.partialCount} partial"),
                footer = "85% threshold",
                insightKey = "activity_completed",
            ),
            WatchStatsTvCardModel(
                id = "stat_activity_source",
                title = "Top source",
                value = sourceHeadline(summary),
                subtitle = "Source labels visible",
                supportingLines = listOf("Torve, Trakt, Manual"),
                footer = "Labeled",
                insightKey = "activity_source",
            ),
        )

    private fun advancedModel(
        contentScope: WatchStatsContentScope,
        insightMode: WatchStatsInsightMode,
        isPremium: Boolean,
        hasData: Boolean,
        groups: List<WatchStatsAdvancedGroup>,
        title: String,
        subtitle: String,
        unavailableTitle: String,
        unavailableBody: String,
    ): WatchStatsTvDashboardModel =
        WatchStatsTvDashboardModel(
            contentScope = contentScope,
            insightMode = insightMode,
            title = title,
            subtitle = subtitle,
            metricCards = advancedCards(insightMode, title, groups),
            primaryTitle = "$title breakdown",
            secondaryTitle = "Top $title",
            topListTitle = "Watched titles",
            detailTitle = "$title detail",
            unavailableTitle = if (!hasData) unavailableTitle else null,
            unavailableBody = if (!hasData) unavailableBody else null,
            lockedTitle = if (!isPremium) "Deeper watch insights" else null,
            lockedBody = if (!isPremium) "Advanced metadata filters and drilldowns appear when enough watch data exists. Basic truthful stats remain available." else null,
        )

    private fun ratingModel(
        contentScope: WatchStatsContentScope,
        groups: List<WatchStatsRatingGroup>,
        isPremium: Boolean,
    ): WatchStatsTvDashboardModel =
        WatchStatsTvDashboardModel(
            contentScope = contentScope,
            insightMode = WatchStatsInsightMode.RATINGS,
            title = scopeTitle(contentScope, "ratings"),
            subtitle = scopeSubtitle(contentScope, "Ratings", "local rating distribution for watched titles"),
            metricCards = ratingCards(groups),
            primaryTitle = "Rating distribution",
            secondaryTitle = "Top rating bands",
            topListTitle = "Top rated watched titles",
            detailTitle = "Rating detail",
            unavailableTitle = if (groups.isEmpty()) "Ratings unavailable" else null,
            unavailableBody = if (groups.isEmpty()) "No local rating metadata is available for this watch history yet. Ratings appear after titles are enriched locally." else null,
            lockedTitle = if (!isPremium) "Deeper watch insights" else null,
            lockedBody = if (!isPremium) "Rating distribution and richer metadata drilldowns appear when enough watch data exists. No sample data is shown." else null,
        )

    private fun advancedCards(
        insightMode: WatchStatsInsightMode,
        title: String,
        groups: List<WatchStatsAdvancedGroup>,
    ): List<WatchStatsTvCardModel> {
        val top = groups.maxByOrNull { it.attributedWatchMs }
        val titleCount = groups.sumOf { it.titleCount }
        val unknown = if (groups.isEmpty()) "Unavailable" else "${groups.size}"
        return listOf(
            WatchStatsTvCardModel(
                id = "stat_${insightMode.name.lowercase()}_top",
                title = "Top $title",
                value = top?.label ?: "Unavailable",
                subtitle = "Attributed watch time",
                supportingLines = listOf(top?.let { WatchStatsUiText.formatDuration(it.attributedWatchMs) } ?: "No local metadata"),
                footer = "Attributed",
                insightKey = "${insightMode.name.lowercase()}_top",
            ),
            WatchStatsTvCardModel(
                id = "stat_${insightMode.name.lowercase()}_count",
                title = "$title watched",
                value = unknown,
                subtitle = "Groups with local metadata",
                supportingLines = listOf("${groups.size} groups"),
                footer = "Local only",
                insightKey = "${insightMode.name.lowercase()}_count",
            ),
            WatchStatsTvCardModel(
                id = "stat_${insightMode.name.lowercase()}_titles",
                title = "Titles tagged",
                value = "$titleCount",
                subtitle = "Watched titles with metadata",
                supportingLines = listOf("$titleCount titles"),
                footer = "No network fetch",
                insightKey = "${insightMode.name.lowercase()}_titles",
            ),
            WatchStatsTvCardModel(
                id = "stat_${insightMode.name.lowercase()}_source",
                title = "Method",
                value = "Local",
                subtitle = "Only cached metadata is used",
                supportingLines = listOf("No fake data"),
                footer = "Truthful",
                insightKey = "${insightMode.name.lowercase()}_method",
            ),
        )
    }

    private fun ratingCards(groups: List<WatchStatsRatingGroup>): List<WatchStatsTvCardModel> {
        val top = groups.maxByOrNull { it.attributedWatchMs }
        val titleCount = groups.sumOf { it.titleCount }
        return listOf(
            WatchStatsTvCardModel(
                id = "stat_ratings_top",
                title = "Top band",
                value = top?.band?.label ?: "Unavailable",
                subtitle = "Local ratings only",
                supportingLines = listOf(top?.let { WatchStatsUiText.formatDuration(it.attributedWatchMs) } ?: "No local ratings"),
                footer = "Missing ignored",
                insightKey = "ratings_top",
            ),
            WatchStatsTvCardModel(
                id = "stat_ratings_bands",
                title = "Rating bands",
                value = "${groups.size}",
                subtitle = "Bands with watched titles",
                supportingLines = listOf("${groups.size} bands"),
                footer = "IMDb/RT/TMDB local",
                insightKey = "ratings_bands",
            ),
            WatchStatsTvCardModel(
                id = "stat_ratings_titles",
                title = "Rated titles",
                value = "$titleCount",
                subtitle = "Watched titles with ratings",
                supportingLines = listOf("$titleCount titles"),
                footer = "Local only",
                insightKey = "ratings_titles",
            ),
            WatchStatsTvCardModel(
                id = "stat_ratings_missing",
                title = "Missing ratings",
                value = "Hidden",
                subtitle = "Not treated as zero",
                supportingLines = listOf("No fake zeroes"),
                footer = "Truthful",
                insightKey = "ratings_missing",
            ),
        )
    }

    private fun recentRuntimeTitle(sessions: List<WatchSession>): String =
        if (sessions.count { it.countedWatchMs > 0L } > 1) "Watch time trend" else "Recent counted sessions"

    private fun heroInsight(
        contentScope: WatchStatsContentScope,
        summary: WatchStatsSummary,
    ): WatchStatsHeroInsightModel {
        val completed = summary.completedMovies + summary.completedEpisodes
        val topSource = sourceHeadline(summary)
        return when {
            summary.totalWatchMs > 0L -> WatchStatsHeroInsightModel(
                id = "hero_time",
                title = scopeTitle(contentScope, "counted time"),
                value = WatchStatsUiText.formatDuration(summary.totalWatchMs),
                body = when {
                    summary.measuredWatchMs == 0L && summary.estimatedWatchMs > 0L ->
                        "Counted watch time is estimated from imported or manual history. New Torve playback will appear as measured."
                    summary.measuredWatchMs > 0L ->
                        "Measured Torve playback is included separately from estimated imported and manual history."
                    else ->
                        "Unknown runtimes stay excluded so the total remains honest."
                },
                chips = listOf("Estimated", "Unknown excluded"),
            )
            completed > 0 -> WatchStatsHeroInsightModel(
                id = "hero_completed",
                title = scopeTitle(contentScope, "completed"),
                value = "$completed",
                body = "Completed counts use the 85% threshold. Partial and abandoned sessions stay separate.",
                chips = listOf("85% threshold", "Partial separate"),
            )
            topSource != "No source yet" -> WatchStatsHeroInsightModel(
                id = "hero_source",
                title = "Top source",
                value = topSource,
                body = "Most recorded activity currently comes from $topSource.",
                chips = listOf("Source labeled"),
            )
            else -> WatchStatsHeroInsightModel(
                id = "hero_empty",
                title = scopeTitle(contentScope, "watch stats"),
                value = "Ready",
                body = "Start watching or import history to build truthful stats.",
                chips = listOf("Local only"),
            )
        }
    }

    private fun scopeTitle(
        contentScope: WatchStatsContentScope,
        noun: String,
    ): String =
        when (contentScope) {
            WatchStatsContentScope.ALL -> "All $noun"
            WatchStatsContentScope.MOVIES -> "Movie $noun"
            WatchStatsContentScope.SHOWS -> "Show $noun"
        }

    private fun scopeSubtitle(
        contentScope: WatchStatsContentScope,
        mode: String,
        detail: String,
    ): String =
        when (contentScope) {
            WatchStatsContentScope.ALL -> "$mode for all sessions, all selected time. $detail."
            WatchStatsContentScope.MOVIES -> "$mode for movies only. $detail."
            WatchStatsContentScope.SHOWS -> "$mode for shows and episodes only. $detail."
        }

    private fun yearTitle(contentScope: WatchStatsContentScope): String =
        when (contentScope) {
            WatchStatsContentScope.ALL -> "Year insights"
            WatchStatsContentScope.MOVIES -> "Movie release years"
            WatchStatsContentScope.SHOWS -> "Show year insights"
        }

    private fun yearSubtitle(contentScope: WatchStatsContentScope): String =
        when (contentScope) {
            WatchStatsContentScope.ALL -> "Movies by release year; show years only where local metadata exists."
            WatchStatsContentScope.MOVIES -> "Movie release-year distribution from local metadata."
            WatchStatsContentScope.SHOWS -> "Episode air year when available, otherwise show first-air year from local metadata."
        }

    private fun sourceHeadline(summary: WatchStatsSummary): String {
        val labels = summary.sourceBreakdown
            .filter { it.sessionCount > 0 }
            .map { WatchStatsUiText.sourceLabel(it.source) }
            .distinct()

        if (labels.isEmpty()) return "No source yet"
        return when {
            labels.size <= 2 -> labels.joinToString(" + ")
            else -> labels.take(2).joinToString(" + ") + " + ${labels.size - 2}"
        }
    }

    private fun countLabel(
        count: Int,
        noun: String,
    ): String =
        when (count) {
            0 -> "0 ${noun}s"
            1 -> "1 $noun"
            else -> "$count ${noun}s"
        }
}
