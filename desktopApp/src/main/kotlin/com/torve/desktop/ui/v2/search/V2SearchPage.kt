package com.torve.desktop.ui.v2.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveSearchField
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2Atmosphere
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.domain.model.MediaItem
import com.torve.presentation.search.SearchUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun V2SearchPage(
    searchState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onApplyFilter: (String?) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    onOpenAiSearch: (() -> Unit)? = null,
) {
    val colors = TorveDesktopThemeTokens.colors

    Box(Modifier.fillMaxSize()) {
        V2Atmosphere(searchState.results.firstOrNull()?.backdropUrl, Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Search field + AI launch button
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TorveSearchField(
                    value = searchState.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(0.6f),
                    placeholder = "Search movies, shows, people...",
                )
                if (onOpenAiSearch != null) {
                    com.torve.desktop.ui.components.TorveSecondaryButton(
                        text = "Ask AI",
                        onClick = onOpenAiSearch,
                    )
                }
            }

            // Filter chips
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveFilterChip(
                    text = "All",
                    selected = searchState.filter.mediaType == null,
                    onClick = { onApplyFilter(null) },
                )
                TorveFilterChip(
                    text = "Movies",
                    selected = searchState.filter.mediaType == "movie",
                    onClick = { onApplyFilter("movie") },
                )
                TorveFilterChip(
                    text = "TV Shows",
                    selected = searchState.filter.mediaType == "tv",
                    onClick = { onApplyFilter("tv") },
                )
            }

            // Loading state
            if (searchState.isSearching) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }

            // Error state
            searchState.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                )
            }

            // Results grid
            val results = searchState.results
            if (results.isNotEmpty()) {
                Text(
                    text = "${results.size} results",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    results.forEach { item ->
                        V2PosterCard(
                            title = item.title,
                            imageUrl = item.posterUrl,
                            modifier = Modifier.width(155.dp),
                            year = item.year?.toString(),
                            rating = item.rating?.let { String.format("%.1f", it) },
                            backdropUrl = item.backdropUrl,
                            overview = item.overview,
                            onClick = { onOpenDetail(item) },
                        )
                    }
                }
            } else if (!searchState.isSearching && searchState.query.isNotBlank()) {
                Spacer(Modifier.height(32.dp))
                com.torve.desktop.ui.components.TorvePlaceholderState(
                    title = "No results",
                    description = "Nothing matched \"${searchState.query}\". Try a different spelling, or browse Discover below.",
                    emoji = "🔎",
                )
            }

            // Discover results (when no active search)
            if (searchState.discoverResults.isNotEmpty() && searchState.query.isBlank()) {
                Text(
                    text = "Discover",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    searchState.discoverResults.forEach { item ->
                        V2PosterCard(
                            title = item.title,
                            imageUrl = item.posterUrl,
                            modifier = Modifier.width(155.dp),
                            year = item.year?.toString(),
                            rating = item.rating?.let { String.format("%.1f", it) },
                            backdropUrl = item.backdropUrl,
                            overview = item.overview,
                            onClick = { onOpenDetail(item) },
                        )
                    }
                }
            }
        }
    }
}
