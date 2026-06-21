package com.torve.android.tv.screens.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.torve.domain.stats.WatchStatsAdvancedGroup
import com.torve.domain.stats.WatchStatsRatingGroup
import com.torve.domain.stats.WatchStatsSummary

@Composable
internal fun TvWatchStatsFilterRail(
    selection: TvWatchStatsFilterSelection,
    summary: WatchStatsSummary,
    isPremium: Boolean,
    hasSimklData: Boolean,
    railFocusRequester: FocusRequester,
    mainStageRequester: FocusRequester,
    filterEntryRequester: FocusRequester,
    onSelectionChange: (TvWatchStatsFilterSelection) -> Unit,
    onUpgrade: () -> Unit,
    onFocused: (FocusRequester) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.width(220.dp),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "range_label") {
            TvStatsSectionLabel("Time range")
        }
        TvWatchStatsTimeRange.entries.forEachIndexed { index, range ->
            item(key = "range_${range.name}") {
                val requester = if (index == 0) filterEntryRequester else remember(range) { FocusRequester() }
                TvStatsChip(
                    label = range.label,
                    selected = selection.timeRange == range,
                    focusRequester = requester,
                    left = railFocusRequester,
                    right = mainStageRequester,
                    onFocused = { onFocused(requester) },
                    onClick = { onSelectionChange(selection.copy(timeRange = range)) },
                )
            }
        }

        item(key = "content_label") {
            TvStatsSectionLabel("Content")
        }
        TvWatchStatsContentFilter.entries.forEach { content ->
            item(key = "content_${content.name}") {
                val requester = remember(content) { FocusRequester() }
                TvStatsChip(
                    label = content.label,
                    selected = selection.content == content,
                    focusRequester = requester,
                    left = railFocusRequester,
                    right = mainStageRequester,
                    onFocused = { onFocused(requester) },
                    onClick = { onSelectionChange(selection.copy(content = content)) },
                )
            }
        }

        item(key = "status_label") {
            TvStatsSectionLabel("Status")
        }
        TvWatchStatsStatusFilter.entries.forEach { status ->
            item(key = "status_${status.name}") {
                val requester = remember(status) { FocusRequester() }
                TvStatsChip(
                    label = status.label,
                    selected = selection.status == status,
                    focusRequester = requester,
                    left = railFocusRequester,
                    right = mainStageRequester,
                    onFocused = { onFocused(requester) },
                    onClick = { onSelectionChange(selection.copy(status = status)) },
                )
            }
        }

        item(key = "source_label") {
            TvStatsSectionLabel("Source")
        }
        TvWatchStatsSourceFilter.entries
            .filter { it != TvWatchStatsSourceFilter.SIMKL || hasSimklData }
            .forEach { source ->
                item(key = "source_${source.name}") {
                    val requester = remember(source) { FocusRequester() }
                    TvStatsChip(
                        label = source.label,
                        selected = selection.source == source,
                        focusRequester = requester,
                        left = railFocusRequester,
                        right = mainStageRequester,
                        onFocused = { onFocused(requester) },
                        onClick = { onSelectionChange(selection.copy(source = source)) },
                    )
                }
            }

        item(key = "advanced_label") {
            TvStatsSectionLabel("Advanced")
        }
        when {
            !isPremium -> {
                item(key = "advanced_locked") {
                    val requester = remember("advanced_locked") { FocusRequester() }
                    TvStatsFocusCard(
                        title = "Advanced filters",
                        subtitle = "Unlock genre, rating, year, actor, and studio insights.",
                        meta = "Go Premium",
                        locked = true,
                        focusRequester = requester,
                        left = railFocusRequester,
                        right = mainStageRequester,
                        onFocused = { onFocused(requester) },
                        onClick = onUpgrade,
                    )
                }
            }
            !summary.advanced.availability.hasAnyMetadata -> {
                item(key = "advanced_unavailable") {
                    val requester = remember("advanced_unavailable") { FocusRequester() }
                    TvStatsFocusCard(
                        title = "Metadata unavailable",
                        subtitle = "Advanced filters appear after Torve has local genre, year, or rating data.",
                        focusRequester = requester,
                        left = railFocusRequester,
                        right = mainStageRequester,
                        onFocused = { onFocused(requester) },
                        onClick = {},
                    )
                }
            }
            else -> {
                if (selection.hasAdvancedSelection()) {
                    item(key = "advanced_clear") {
                        val requester = remember("advanced_clear") { FocusRequester() }
                        TvStatsChip(
                            label = "Clear advanced",
                            selected = false,
                            focusRequester = requester,
                            left = railFocusRequester,
                            right = mainStageRequester,
                            onFocused = { onFocused(requester) },
                            onClick = { onSelectionChange(selection.clearAdvanced()) },
                        )
                    }
                }
                summary.advanced.genreGroups.take(5).forEach { group ->
                    advancedGroupChip(
                        keyPrefix = "genre",
                        group = group,
                        selected = selection.genre == group.key,
                        railFocusRequester = railFocusRequester,
                        mainStageRequester = mainStageRequester,
                        onFocused = onFocused,
                        onClick = { onSelectionChange(selection.clearAdvanced().copy(genre = group.key)) },
                    )
                }
                summary.advanced.yearGroups.take(4).forEach { group ->
                    advancedGroupChip(
                        keyPrefix = "year",
                        group = group,
                        selected = selection.year?.toString() == group.key,
                        railFocusRequester = railFocusRequester,
                        mainStageRequester = mainStageRequester,
                        onFocused = onFocused,
                        onClick = {
                            onSelectionChange(selection.clearAdvanced().copy(year = group.key.toIntOrNull()))
                        },
                    )
                }
                summary.advanced.ratingDistribution.take(4).forEach { group ->
                    advancedRatingChip(
                        group = group,
                        selected = selection.ratingBand == group.band,
                        railFocusRequester = railFocusRequester,
                        mainStageRequester = mainStageRequester,
                        onFocused = onFocused,
                        onClick = { onSelectionChange(selection.clearAdvanced().copy(ratingBand = group.band)) },
                    )
                }
                summary.advanced.actorGroups.take(4).forEach { group ->
                    advancedGroupChip(
                        keyPrefix = "actor",
                        group = group,
                        selected = selection.actor == group.key,
                        railFocusRequester = railFocusRequester,
                        mainStageRequester = mainStageRequester,
                        onFocused = onFocused,
                        onClick = { onSelectionChange(selection.clearAdvanced().copy(actor = group.key)) },
                    )
                }
                summary.advanced.directorGroups.take(3).forEach { group ->
                    advancedGroupChip(
                        keyPrefix = "director",
                        group = group,
                        selected = selection.director == group.key,
                        railFocusRequester = railFocusRequester,
                        mainStageRequester = mainStageRequester,
                        onFocused = onFocused,
                        onClick = { onSelectionChange(selection.clearAdvanced().copy(director = group.key)) },
                    )
                }
                summary.advanced.studioGroups.take(3).forEach { group ->
                    advancedGroupChip(
                        keyPrefix = "studio",
                        group = group,
                        selected = selection.studio == group.key,
                        railFocusRequester = railFocusRequester,
                        mainStageRequester = mainStageRequester,
                        onFocused = onFocused,
                        onClick = { onSelectionChange(selection.clearAdvanced().copy(studio = group.key)) },
                    )
                }
                summary.advanced.providerGroups.take(3).forEach { group ->
                    advancedGroupChip(
                        keyPrefix = "provider",
                        group = group,
                        selected = selection.provider == group.key,
                        railFocusRequester = railFocusRequester,
                        mainStageRequester = mainStageRequester,
                        onFocused = onFocused,
                        onClick = { onSelectionChange(selection.clearAdvanced().copy(provider = group.key)) },
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.advancedGroupChip(
    keyPrefix: String,
    group: WatchStatsAdvancedGroup,
    selected: Boolean,
    railFocusRequester: FocusRequester,
    mainStageRequester: FocusRequester,
    onFocused: (FocusRequester) -> Unit,
    onClick: () -> Unit,
) {
    item(key = "advanced_${keyPrefix}_${group.key}") {
        val requester = remember(keyPrefix, group.key) { FocusRequester() }
        TvStatsChip(
            label = group.label,
            selected = selected,
            focusRequester = requester,
            left = railFocusRequester,
            right = mainStageRequester,
            onFocused = { onFocused(requester) },
            onClick = onClick,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.advancedRatingChip(
    group: WatchStatsRatingGroup,
    selected: Boolean,
    railFocusRequester: FocusRequester,
    mainStageRequester: FocusRequester,
    onFocused: (FocusRequester) -> Unit,
    onClick: () -> Unit,
) {
    item(key = "advanced_rating_${group.band.name}") {
        val requester = remember(group.band) { FocusRequester() }
        TvStatsChip(
            label = group.band.label,
            selected = selected,
            focusRequester = requester,
            left = railFocusRequester,
            right = mainStageRequester,
            onFocused = { onFocused(requester) },
            onClick = onClick,
        )
    }
}

private fun TvWatchStatsFilterSelection.hasAdvancedSelection(): Boolean =
    genre != null ||
        year != null ||
        decade != null ||
        ratingBand != null ||
        actor != null ||
        director != null ||
        studio != null ||
        network != null ||
        provider != null ||
        certification != null

private fun TvWatchStatsFilterSelection.clearAdvanced(): TvWatchStatsFilterSelection =
    copy(
        genre = null,
        year = null,
        decade = null,
        ratingBand = null,
        actor = null,
        director = null,
        studio = null,
        network = null,
        provider = null,
        certification = null,
    )
