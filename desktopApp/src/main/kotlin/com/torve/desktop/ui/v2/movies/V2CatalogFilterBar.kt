package com.torve.desktop.ui.v2.movies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torve.data.ai.AiProvider
import com.torve.data.metadata.TmdbGenres
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveDropdownScaffold
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSearchField
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.domain.model.MediaItem
import com.torve.presentation.catalog.CatalogViewModel
import com.torve.presentation.catalog.SortOption

enum class CatalogMediaType { MOVIE, TV }

/**
 * Filter bar above the rails/grid. Three stacked rows from top to bottom:
 *   1. Search field (filters the visible items by title)
 *   2. Watch provider chips (Netflix, Disney+, Max, ...)
 *   3. Sort dropdown + genre chips + Clear (legacy row)
 *
 * When a genre, filter, provider, or non-empty search is active the host
 * page swaps to the filtered grid; otherwise the rails layout stays.
 */
@Composable
fun V2CatalogFilterBar(
    catalogViewModel: CatalogViewModel,
    mediaType: CatalogMediaType,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    startPadding: Dp = 72.dp,
    modifier: Modifier = Modifier,
    aiProvider: AiProvider = AiProvider.CLAUDE,
    aiProviderConfigured: Boolean = false,
    aiMode: Boolean = false,
    aiQuery: String = "",
    aiLoading: Boolean = false,
    onAiModeChange: (Boolean) -> Unit = {},
    onAiQueryChange: (String) -> Unit = {},
    onRunAiSearch: () -> Unit = {},
    onOpenAiProviderSettings: (() -> Unit)? = null,
) {
    val state by catalogViewModel.state.collectAsState()
    val colors = TorveDesktopThemeTokens.colors
    var sortMenuOpen by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(true) }
    var filtersVisible by remember { mutableStateOf(true) }
    var aiNotConfiguredNotice by remember { mutableStateOf(false) }
    val currentSort = state.filter.sortBy

    val genres = remember(mediaType) {
        when (mediaType) {
            CatalogMediaType.MOVIE -> TmdbGenres.MOVIE_GENRES
            CatalogMediaType.TV -> TmdbGenres.TV_GENRES
        }
    }

    val activeCount = state.activeFilterCount +
        (if (state.selectedGenreId != null) 1 else 0) +
        (if (state.providerId != null) 1 else 0)
    val searchActive = searchQuery.isNotBlank() || aiQuery.isNotBlank()

    // Year filter choices. Each maps to (year, yearTo) range that
    // gets applied to the catalog filter. "Any" clears the range.
    val yearChoices = remember {
        listOf(
            Triple("Any year", null as Int?, null as Int?),
            Triple("2026", 2026, 2026),
            Triple("2025", 2025, 2025),
            Triple("2024", 2024, 2024),
            Triple("2020s", 2020, 2029),
            Triple("2010s", 2010, 2019),
            Triple("2000s", 2000, 2009),
            Triple("1990s", 1990, 1999),
            Triple("1980s", 1980, 1989),
            Triple("Older (pre-1980)", 1900, 1979),
        )
    }
    val currentYearLabel = remember(state.filter.year, state.filter.yearTo) {
        val y = state.filter.year
        val yt = state.filter.yearTo
        when {
            y == null && yt == null -> "Any year"
            y != null && yt != null && y == yt -> y.toString()
            y != null && yt != null -> "${y}s".takeIf { yt - y == 9 } ?: "$y–$yt"
            else -> "Custom"
        }
    }
    var yearMenuOpen by remember { mutableStateOf(false) }

    // Optional pre-cache progress banner. Shown while the background
    // worker is populating the top-1000-per-genre cache + pre-warming
    // poster images. Hidden when the pass is finished or never ran.
    val workerProgress = com.torve.desktop.globalCatalogTopWorker?.progress?.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = 24.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        workerProgress?.value?.takeIf { it.running }?.let { p ->
            val pct = if (p.totalGenres > 0) (p.processedGenres * 100 / p.totalGenres) else 0
            Text(
                text = "Pre-caching catalog: ${p.processedGenres}/${p.totalGenres} genres ($pct%) — current: ${p.currentLabel}. Genre clicks will be instant once this completes.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = colors.accent,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorveFilterChip(
                text = if (searchVisible) ds("Hide search") else ds("Show search"),
                selected = searchVisible || searchActive || aiMode,
                onClick = { searchVisible = !searchVisible },
            )
            TorveFilterChip(
                text = if (filtersVisible) ds("Hide filters") else {
                    val suffix = if (activeCount > 0) " ($activeCount)" else ""
                    ds("Show filters") + suffix
                },
                selected = filtersVisible || activeCount > 0,
                onClick = { filtersVisible = !filtersVisible },
            )
            if (searchVisible || aiMode) {
                TorveSearchField(
                    value = if (aiMode) aiQuery else searchQuery,
                    onValueChange = if (aiMode) onAiQueryChange else onSearchQueryChange,
                    placeholder = if (aiMode) {
                        ds("Ask Torve in plain English...")
                    } else {
                        ds(
                            if (mediaType == CatalogMediaType.MOVIE) "Search movies on this page"
                            else "Search shows on this page",
                        )
                    },
                    modifier = Modifier.width(420.dp),
                )
                if (aiMode) {
                    TorvePrimaryButton(
                        text = if (aiLoading) ds("Asking...") else ds("Ask"),
                        onClick = onRunAiSearch,
                        enabled = !aiLoading && aiQuery.isNotBlank(),
                    )
                }
            }
            TorveFilterChip(
                text = if (aiMode) "AI on" else "AI off",
                selected = aiMode,
                onClick = {
                    if (aiMode) {
                        onAiModeChange(false)
                        aiNotConfiguredNotice = false
                    } else if (aiProviderConfigured) {
                        searchVisible = true
                        onAiModeChange(true)
                        aiNotConfiguredNotice = false
                    } else {
                        aiNotConfiguredNotice = true
                    }
                },
            )
            TorveBadge(
                text = if (aiProviderConfigured) "${aiProvider.label} ready" else ds("AI not set up"),
                tone = if (aiProviderConfigured) TorveBadgeTone.Success else TorveBadgeTone.Warning,
            )
        }

        if (!searchVisible && !aiMode) {
            // Search is intentionally hidden; keep the rest of the bar compact.
        }

        if (aiNotConfiguredNotice && !aiProviderConfigured) {
            TorveBanner(
                title = ds("AI search needs a provider"),
                description = ds("Set up an AI provider in Settings before turning AI search on."),
                tone = TorveBannerTone.Warning,
            )
            onOpenAiProviderSettings?.let { open ->
                TorveGhostButton(
                    text = ds("Open AI provider settings"),
                    onClick = {
                        aiNotConfiguredNotice = false
                        open()
                    },
                )
            }
        }

        if (!searchVisible && !filtersVisible && !aiMode) return@Column
        if (!filtersVisible || aiMode) return@Column

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TorveGhostButton(
                    text = "${ds("Year")}: $currentYearLabel",
                    onClick = { yearMenuOpen = true },
                )
                TorveDropdownScaffold(
                    expanded = yearMenuOpen,
                    onDismissRequest = { yearMenuOpen = false },
                    items = yearChoices.map { (label, from, to) ->
                        label to {
                            yearMenuOpen = false
                            catalogViewModel.applyFilter(
                                state.filter.copy(year = from, yearTo = to),
                            )
                        }
                    },
                )
            }
        }

        // Row 2: Watch provider chips. LazyRow because the provider
        // list is wider than the screen on common laptops; LazyRow on
        // Compose Desktop maps mouse-wheel input to horizontal scroll
        // automatically, while a plain Row(horizontalScroll) requires
        // click-and-drag and most users won't realize they can scroll.
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp),
        ) {
            item {
                TorveFilterChip(
                    text = ds("All providers"),
                    selected = state.providerId == null,
                    onClick = { catalogViewModel.clearProvider() },
                )
            }
            items(DESKTOP_WATCH_PROVIDERS) { provider ->
                TorveFilterChip(
                    text = provider.label,
                    selected = state.providerId == provider.id,
                    onClick = { catalogViewModel.setProvider(provider.id) },
                )
            }
        }

        // Row 3: Sort + genre chips + clear (legacy filter row).
        // Same LazyRow rationale as Row 2 -- 19 movie genres / 16 TV
        // genres always overflow on standard 1920px screens and need
        // wheel-based horizontal scrolling.
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                Box {
                    TorveGhostButton(
                        text = "${ds("Sort")}: ${currentSort.label}",
                        onClick = { sortMenuOpen = true },
                    )
                    TorveDropdownScaffold(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                        items = SortOption.entries.map { option ->
                            option.label to {
                                sortMenuOpen = false
                                catalogViewModel.applyFilter(state.filter.copy(sortBy = option))
                            }
                        },
                    )
                }
            }

            item {
                TorveFilterChip(
                    text = ds("All"),
                    selected = state.selectedGenreId == null,
                    onClick = { catalogViewModel.selectGenre(null) },
                )
            }

            items(genres.entries.toList()) { entry ->
                TorveFilterChip(
                    text = entry.value,
                    selected = state.selectedGenreId == entry.key,
                    onClick = { catalogViewModel.selectGenre(entry.key) },
                )
            }

            if (activeCount > 0) {
                item {
                    TorveGhostButton(
                        text = ds("Clear"),
                        onClick = { catalogViewModel.clearFilters() },
                    )
                }
                item {
                    Text(
                        text = "$activeCount active",
                        modifier = Modifier.padding(horizontal = 4.dp),
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
fun V2CatalogAiResultsGrid(
    items: List<MediaItem>,
    isLoading: Boolean,
    error: String?,
    onOpenDetail: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    if (error != null) {
        TorveBanner(
            title = ds("AI search failed"),
            description = error,
            tone = TorveBannerTone.Error,
            modifier = Modifier.padding(start = 72.dp, end = 24.dp),
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(start = 72.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (isLoading) {
            item {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
        }
        if (!isLoading && items.isEmpty() && error == null) {
            item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = ds("Ask for a mood, sport, actor, title, or genre."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
            }
        }
        items(items, key = { it.id }) { item ->
            V2PosterCard(
                item.title,
                item.posterUrl,
                Modifier.width(150.dp),
                item.year?.toString(),
                item.rating?.let { r -> String.format("%.1f", r) },
                ratings = item.ratings,
                backdropUrl = item.backdropUrl,
                overview = item.overview,
            ) { onOpenDetail(item) }
        }
    }
}
