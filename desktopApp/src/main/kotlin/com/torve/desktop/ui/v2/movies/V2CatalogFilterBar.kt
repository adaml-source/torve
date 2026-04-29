package com.torve.desktop.ui.v2.movies

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torve.data.metadata.TmdbGenres
import com.torve.desktop.ui.components.TorveDropdownScaffold
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.presentation.catalog.CatalogViewModel
import com.torve.presentation.catalog.SortOption

enum class CatalogMediaType { MOVIE, TV }

/**
 * Filter bar above the rails/grid. When any filter or genre is active, the
 * host page swaps to a grid driven by `state.items`; otherwise it stays on
 * the curated-rails layout.
 */
@Composable
fun V2CatalogFilterBar(
    catalogViewModel: CatalogViewModel,
    mediaType: CatalogMediaType,
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

    val activeCount = state.activeFilterCount + (if (state.selectedGenreId != null) 1 else 0)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = 24.dp, top = 4.dp, bottom = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                        catalogViewModel.loadCatalog()
                    }
                },
            )
        }

        TorveFilterChip(
            text = ds("All"),
            selected = state.selectedGenreId == null,
            onClick = {
                catalogViewModel.selectGenre(null)
                catalogViewModel.loadCatalog()
            },
        )

        genres.entries.forEach { (id, label) ->
            TorveFilterChip(
                text = label,
                selected = state.selectedGenreId == id,
                onClick = {
                    catalogViewModel.selectGenre(id)
                    catalogViewModel.loadCatalog()
                },
            )
        }

        if (activeCount > 0) {
            TorveGhostButton(
                text = ds("Clear"),
                onClick = {
                    catalogViewModel.clearFilters()
                    catalogViewModel.loadCatalog()
                },
            )
            Text(
                text = "$activeCount active",
                modifier = Modifier.padding(horizontal = 4.dp),
                color = colors.textSecondary,
            )
        }
    }
}
