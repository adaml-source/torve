package com.torve.desktop.ui.pages.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.torve.desktop.ui.components.TorveBackdropHero
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorveLandscapeFeatureCard
import com.torve.desktop.ui.components.TorvePosterCardLarge
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSoftContentBand
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.model.MediaItem
import com.torve.domain.model.WatchProgress
import com.torve.presentation.home.HomeUiState

data class DesktopMediaEntry(
    val item: MediaItem,
    val subtitle: String,
    val progress: Float? = null,
    val accentLabel: String? = null,
    val actionLabel: String = "Play",
)

enum class DesktopCollectionStyle {
    SHOWCASE,
    POSTER_GRID,
    ROW_LIST,
}

data class DesktopMomentumSection(
    val title: String,
    val supportingText: String,
    val entries: List<DesktopMediaEntry>,
    val style: DesktopCollectionStyle,
)

data class DesktopHomeMomentumModel(
    val hero: DesktopMediaEntry? = null,
    val heroHeading: String = "Start watching on desktop",
    val heroSupportingText: String = "Search, connect a source, and build momentum as you start using Torve Desktop.",
    val sections: List<DesktopMomentumSection> = emptyList(),
) {
    val allItems: List<MediaItem>
        get() = buildList {
            hero?.let { add(it.item) }
            sections.forEach { section -> addAll(section.entries.map { it.item }) }
        }.distinctBy { it.id }
}

data class DesktopLibraryArchive(
    val continueWatching: List<DesktopMediaEntry> = emptyList(),
    val watchlist: List<DesktopMediaEntry> = emptyList(),
    val history: List<DesktopMediaEntry> = emptyList(),
) {
    val allItems: List<MediaItem>
        get() = buildList {
            addAll(continueWatching.map { it.item })
            addAll(watchlist.map { it.item })
            addAll(history.map { it.item })
        }.distinctBy { it.id }
}

fun Modifier.desktopRailDrag(
    scrollState: ScrollState,
): Modifier = pointerInput(scrollState) {
    detectHorizontalDragGestures { change, dragAmount ->
        if (scrollState.maxValue <= 0) return@detectHorizontalDragGestures
        change.consume()
        scrollState.dispatchRawDelta(-dragAmount)
    }
}

@Composable
private fun DesktopRailControls(
    scrollState: ScrollState,
) {
    val scope = rememberCoroutineScope()
    val colors = TorveDesktopThemeTokens.colors

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            color = colors.fieldSurface.copy(alpha = 0.5f),
            shape = CircleShape,
            onClick = { scope.launch { scrollState.animateScrollTo((scrollState.value - 600).coerceAtLeast(0)) } },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ChevronLeft, "Scroll left", Modifier.size(16.dp), tint = colors.textSecondary)
            }
        }
        Surface(
            modifier = Modifier.size(28.dp),
            color = colors.fieldSurface.copy(alpha = 0.5f),
            shape = CircleShape,
            onClick = { scope.launch { scrollState.animateScrollTo((scrollState.value + 600).coerceAtMost(scrollState.maxValue)) } },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ChevronRight, "Scroll right", Modifier.size(16.dp), tint = colors.textSecondary)
            }
        }
    }
}

fun buildHomeMomentumModel(
    homeState: HomeUiState,
): DesktopHomeMomentumModel {
    val continueEntries = homeState.continueWatching
        .sortedByDescending { it.updatedAt }
        .map { it.toProgressEntry() }
    val watchlistEntries = homeState.watchlistItems.map { it.toWatchlistEntry() }
    val recentEntries = homeState.recentlyWatched.map { it.toHistoryEntry() }
    val recommendedEntries = homeState.recommendedItems.map { scored ->
        DesktopMediaEntry(
            item = scored.item,
            subtitle = scored.reason.ifBlank { buildItemSubtitle(scored.item, fallback = "Recommended for this profile.") },
            accentLabel = "Recommended",
        )
    }
    val curatedShelfEntries = homeState.becauseYouWatched.firstOrNull()?.items
        ?.map { item ->
            DesktopMediaEntry(
                item = item,
                subtitle = buildItemSubtitle(item, fallback = "Because you watched recently."),
                accentLabel = "Because You Watched",
            )
        }
        .orEmpty()
    val discoveryEntries = homeState.shelves.firstOrNull()?.items
        ?.map { item ->
            DesktopMediaEntry(
                item = item,
                subtitle = buildItemSubtitle(item, fallback = "Discovery pick from Torve."),
            )
        }
        .orEmpty()

    val hero = continueEntries.firstOrNull()
        ?: recommendedEntries.firstOrNull()
        ?: watchlistEntries.firstOrNull()
        ?: recentEntries.firstOrNull()
        ?: curatedShelfEntries.firstOrNull()
        ?: discoveryEntries.firstOrNull()

    val sections = buildList {
        if (continueEntries.size > 1) {
            add(
                DesktopMomentumSection(
                    title = "Continue Watching",
                    supportingText = "",
                    entries = continueEntries.drop(1).take(10),
                    style = DesktopCollectionStyle.SHOWCASE,
                ),
            )
        } else if (continueEntries.size == 1 && hero == null) {
            add(
                DesktopMomentumSection(
                    title = "Continue Watching",
                    supportingText = "",
                    entries = continueEntries.take(10),
                    style = DesktopCollectionStyle.SHOWCASE,
                ),
            )
        }

        if (watchlistEntries.isNotEmpty()) {
            add(
                DesktopMomentumSection(
                    title = "Watchlist Highlights",
                    supportingText = "",
                    entries = watchlistEntries.take(15),
                    style = DesktopCollectionStyle.POSTER_GRID,
                ),
            )
        }

        val curatedEntries = when {
            curatedShelfEntries.isNotEmpty() -> curatedShelfEntries
            recommendedEntries.isNotEmpty() -> recommendedEntries
            else -> discoveryEntries
        }
        if (curatedEntries.isNotEmpty()) {
            add(
                DesktopMomentumSection(
                    title = if (curatedShelfEntries.isNotEmpty()) "Because You Watched" else "Keep Momentum Going",
                    supportingText = "",
                    entries = curatedEntries.take(12),
                    style = DesktopCollectionStyle.SHOWCASE,
                ),
            )
        }

        if (recentEntries.isNotEmpty()) {
            add(
                DesktopMomentumSection(
                    title = "Recent Activity",
                    supportingText = "",
                    entries = recentEntries.take(12),
                    style = DesktopCollectionStyle.SHOWCASE,
                ),
            )
        }
    }.take(6)

    return DesktopHomeMomentumModel(
        hero = hero,
        heroHeading = if (hero?.progress != null) "Resume with confidence" else "Pick up momentum fast",
        heroSupportingText = "",
        sections = sections,
    )
}

fun buildLibraryArchive(
    homeState: HomeUiState,
): DesktopLibraryArchive {
    return DesktopLibraryArchive(
        continueWatching = homeState.continueWatching
            .sortedByDescending { it.updatedAt }
            .map { it.toProgressEntry() },
        watchlist = homeState.watchlistItems.map { it.toWatchlistEntry() },
        history = homeState.recentlyWatched.map { it.toHistoryEntry() },
    )
}

@Composable
fun TorveSummaryCard(
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    TorveSoftContentBand(
        title = title,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.filter { it.isNotBlank() }.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
fun DesktopMomentumHeroCard(
    hero: DesktopMediaEntry,
    onSelect: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onChooseSource: (MediaItem) -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
) {
    TorveBackdropHero(
        title = hero.item.title,
        subtitle = hero.item.overview?.take(200) ?: "",
        seed = hero.item.backdropUrl ?: hero.item.posterUrl ?: hero.item.id,
        titleArtUrl = hero.item.logoUrl,
        accentLabel = hero.accentLabel,
        metadata = momentumMetadata(hero),
        progress = hero.progress,
        actions = {
            TorvePrimaryButton(
                text = hero.actionLabel,
                onClick = {
                    onSelect(hero.item)
                    onPlay(hero.item)
                },
            )
            TorveGhostButton(
                text = "Details",
                onClick = {
                    onSelect(hero.item)
                    onOpenDetails(hero.item)
                },
            )
        },
    )
}

@Composable
fun DesktopMomentumSectionCard(
    section: DesktopMomentumSection,
    selectedItemId: String?,
    onSelectItem: (MediaItem) -> Unit,
    onPlayItem: (MediaItem) -> Unit,
    onChooseSource: (MediaItem) -> Unit,
    onOpenDetailsItem: ((MediaItem) -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    val colors = TorveDesktopThemeTokens.colors

    Column(
        modifier = Modifier.padding(start = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            DesktopRailControls(scrollState = scrollState)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .desktopRailDrag(scrollState)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            section.entries.forEach { entry ->
                when (section.style) {
                    DesktopCollectionStyle.SHOWCASE -> TorveLandscapeFeatureCard(
                        title = entry.item.title,
                        subtitle = "",
                        modifier = Modifier.width(320.dp),
                        seed = entry.item.backdropUrl ?: entry.item.posterUrl ?: entry.item.id,
                        progress = entry.progress,
                        selected = selectedItemId == entry.item.id,
                        onSelect = {
                            onSelectItem(entry.item)
                            onOpenDetailsItem?.invoke(entry.item)
                        },
                        onPrimaryAction = {
                            onSelectItem(entry.item)
                            onPlayItem(entry.item)
                        },
                    )

                    DesktopCollectionStyle.POSTER_GRID,
                    DesktopCollectionStyle.ROW_LIST,
                    -> TorvePosterCardLarge(
                        title = entry.item.title,
                        subtitle = "",
                        modifier = Modifier.width(170.dp),
                        seed = entry.item.posterUrl ?: entry.item.backdropUrl ?: entry.item.id,
                        metadata = momentumMetadata(entry),
                        progress = entry.progress,
                        selected = selectedItemId == entry.item.id,
                        onSelect = {
                            onSelectItem(entry.item)
                            onOpenDetailsItem?.invoke(entry.item)
                        },
                        onPrimaryAction = {
                            onSelectItem(entry.item)
                            onPlayItem(entry.item)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun DesktopArchiveSectionCard(
    title: String,
    supportingText: String,
    entries: List<DesktopMediaEntry>,
    style: DesktopCollectionStyle,
    selectedItemId: String?,
    emptyTitle: String,
    emptyDescription: String,
    onSelectItem: (MediaItem) -> Unit,
    onPlayItem: (MediaItem) -> Unit,
    onChooseSource: (MediaItem) -> Unit,
    onOpenDetailsItem: ((MediaItem) -> Unit)? = null,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.padding(start = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TorveDesktopThemeTokens.colors.textPrimary,
            )
            DesktopRailControls(scrollState = scrollState)
        }
        supportingText.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TorveDesktopThemeTokens.colors.textSecondary,
            )
        }
        if (entries.isEmpty()) {
            Text(
                text = emptyDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = TorveDesktopThemeTokens.colors.textSecondary,
            )
            return@Column
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .desktopRailDrag(scrollState)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        )
        {
            entries.forEach { entry ->
                when (style) {
                    DesktopCollectionStyle.SHOWCASE -> TorveLandscapeFeatureCard(
                        title = entry.item.title,
                        subtitle = "",
                        modifier = Modifier.width(300.dp),
                        seed = entry.item.backdropUrl ?: entry.item.posterUrl ?: entry.item.id,
                        progress = entry.progress,
                        selected = selectedItemId == entry.item.id,
                        onSelect = {
                            onSelectItem(entry.item)
                            onOpenDetailsItem?.invoke(entry.item)
                        },
                        onPrimaryAction = {
                            onSelectItem(entry.item)
                            onPlayItem(entry.item)
                        },
                    )

                    DesktopCollectionStyle.POSTER_GRID,
                    DesktopCollectionStyle.ROW_LIST,
                    -> TorvePosterCardLarge(
                        title = entry.item.title,
                        subtitle = "",
                        modifier = Modifier.width(170.dp),
                        seed = entry.item.posterUrl ?: entry.item.backdropUrl ?: entry.item.id,
                        metadata = momentumMetadata(entry),
                        progress = entry.progress,
                        selected = selectedItemId == entry.item.id,
                        onSelect = {
                            onSelectItem(entry.item)
                            onOpenDetailsItem?.invoke(entry.item)
                        },
                        onPrimaryAction = {
                            onSelectItem(entry.item)
                            onPlayItem(entry.item)
                        },
                    )
                }
            }
        }
    }
}


fun momentumMetadata(
    entry: DesktopMediaEntry,
): List<String> {
    val item = entry.item
    return buildList {
        add(item.type.name.lowercase())
        item.year?.let { add(it.toString()) }
        item.runtime?.let { add("${it}m") }
        item.rating?.let { add(String.format("%.1f", it)) }
    }
}

private fun WatchProgress.toProgressEntry(): DesktopMediaEntry {
    val item = toLibraryMediaItem()
    val episodeLabel = if (seasonNumber != null && episodeNumber != null) {
        "S${seasonNumber}E${episodeNumber}"
    } else {
        null
    }
    return DesktopMediaEntry(
        item = item,
        subtitle = buildString {
            append(showTitle ?: title)
            episodeLabel?.let {
                append(" | ")
                append(it)
            }
            if (durationMs > 0) {
                append(" | ")
                append("${(progressPercent * 100).toInt()}% complete")
            }
        },
        progress = progressPercent.takeIf { it > 0f },
        accentLabel = "Resume",
        actionLabel = "Resume",
    )
}

private fun MediaItem.toWatchlistEntry(): DesktopMediaEntry {
    return DesktopMediaEntry(
        item = this,
        subtitle = buildItemSubtitle(this, fallback = "Saved in your watchlist."),
        accentLabel = "Watchlist",
        actionLabel = "Play",
    )
}

private fun MediaItem.toHistoryEntry(): DesktopMediaEntry {
    return DesktopMediaEntry(
        item = this,
        subtitle = buildItemSubtitle(this, fallback = "From your recent viewing history."),
        accentLabel = "History",
        actionLabel = "Play",
    )
}

private fun buildItemSubtitle(
    item: MediaItem,
    fallback: String,
): String {
    return buildString {
        append(item.type.name.lowercase())
        item.year?.let {
            append(" | ")
            append(it)
        }
        item.rating?.let {
            append(" | ")
            append(String.format("%.1f", it))
        }
        item.overview?.takeIf { it.isNotBlank() }?.let {
            append(" | ")
            append(it.take(72))
        } ?: append(" | $fallback")
    }
}

private fun WatchProgress.toLibraryMediaItem(): MediaItem {
    val seasonEpisode = if (seasonNumber != null && episodeNumber != null) {
        "S${seasonNumber}E${episodeNumber}"
    } else {
        null
    }
    val progressText = if (durationMs > 0) {
        "${(progressPercent * 100).toInt()}% complete"
    } else {
        "In progress"
    }
    return MediaItem(
        id = mediaId,
        tmdbId = mediaId.toIntOrNull(),
        type = mediaType,
        title = showTitle ?: title,
        overview = listOfNotNull(seasonEpisode, progressText).joinToString(" | ").ifBlank { null },
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
    )
}
