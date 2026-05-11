package com.torve.desktop.ui.v2.movies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.torve.data.metadata.TmdbGenres
import com.torve.desktop.ui.components.TorveDropdownScaffold
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorveSearchField
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
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
) {
    val state by catalogViewModel.state.collectAsState()
    val colors = TorveDesktopThemeTokens.colors
    var sortMenuOpen by remember { mutableStateOf(false) }
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
        // Row 1: Search field + year filter dropdown
        // Aligned to CenterVertically so the year button's baseline
        // matches the search field's text baseline -- otherwise the
        // taller text-field box and the shorter ghost button looked
        // misaligned at the top.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorveSearchField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = ds(
                    if (mediaType == CatalogMediaType.MOVIE) "Search movies on this page"
                    else "Search shows on this page",
                ),
                modifier = Modifier.width(380.dp),
            )
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
