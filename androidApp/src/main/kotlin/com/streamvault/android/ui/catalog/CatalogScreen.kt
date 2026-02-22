package com.streamvault.android.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.home.CardStyle
import com.streamvault.android.ui.home.MediaCard
import com.streamvault.domain.model.MediaItem
import com.streamvault.presentation.catalog.CatalogCategory
import com.streamvault.presentation.catalog.CatalogViewModel

private val MOVIE_GENRES = listOf(
    28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
    80 to "Crime", 99 to "Documentary", 18 to "Drama", 14 to "Fantasy",
    27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance",
    878 to "Sci-Fi", 53 to "Thriller", 10752 to "War",
)

private val TV_GENRES = listOf(
    10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy",
    80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
    10762 to "Kids", 9648 to "Mystery", 10764 to "Reality",
    10765 to "Sci-Fi & Fantasy", 53 to "Thriller", 10768 to "War & Politics",
)

/**
 * Shared catalog screen used by both Movies and TV Shows tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    mediaType: String, // "movie" or "tv"
    onMediaClick: (MediaItem) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val genres = if (mediaType == "movie") MOVIE_GENRES else TV_GENRES

    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = state.searchQuery,
                    onQueryChange = { viewModel.updateSearchQuery(it) },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    placeholder = { Text("Search ${if (mediaType == "movie") "movies" else "TV shows"}...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearSearch() }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                )
            },
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {}

        // Category chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(CatalogCategory.entries) { category ->
                FilterChip(
                    selected = state.selectedCategory == category,
                    onClick = { viewModel.selectCategory(category) },
                    label = { Text(category.label) },
                )
            }
        }

        // Genre chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = state.selectedGenreId == null,
                    onClick = { viewModel.selectGenre(null) },
                    label = { Text("All") },
                )
            }
            items(genres) { (id, name) ->
                FilterChip(
                    selected = state.selectedGenreId == id,
                    onClick = { viewModel.selectGenre(id) },
                    label = { Text(name) },
                )
            }
        }

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(48.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                state.error != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { viewModel.refresh() }) {
                            Text("Failed to load. Tap to retry.")
                        }
                    }
                }

                else -> {
                    val displayItems = if (state.searchQuery.length >= 2) {
                        state.searchResults
                    } else {
                        state.items
                    }

                    if (displayItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No results found",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 130.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(displayItems, key = { it.id }) { item ->
                                MediaCard(
                                    item = item,
                                    style = CardStyle.POSTER,
                                    onClick = { onMediaClick(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
