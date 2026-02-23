package com.streamvault.android.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.components.CardSize
import com.streamvault.android.ui.components.PosterCard
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.MediaItem
import com.streamvault.presentation.search.SearchFilter
import com.streamvault.presentation.search.SearchViewModel
import org.koin.compose.koinInject

private val genreOptions = listOf(
    null to "All Genres",
    28 to "Action",
    12 to "Adventure",
    16 to "Animation",
    35 to "Comedy",
    80 to "Crime",
    99 to "Documentary",
    18 to "Drama",
    10751 to "Family",
    14 to "Fantasy",
    36 to "History",
    27 to "Horror",
    10402 to "Music",
    9648 to "Mystery",
    10749 to "Romance",
    878 to "Sci-Fi",
    53 to "Thriller",
    10752 to "War",
    37 to "Western",
)

@Composable
fun SearchScreen(
    onMediaClick: (MediaItem) -> Unit,
    viewModel: SearchViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Search input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Gunmetal)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                BasicTextField(
                    value = state.query,
                    onValueChange = { viewModel.updateQuery(it) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Snow),
                    cursorBrush = SolidColor(Amber),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Box {
                            if (state.query.isEmpty()) {
                                Text(
                                    text = "Search movies & TV shows...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = StreamVault.colors.textHint,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (state.query.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.clearSearch() },
                        modifier = Modifier.align(Alignment.CenterEnd).size(24.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Clear",
                            tint = StreamVault.colors.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = StreamVault.colors.textTertiary,
                        modifier = Modifier.align(Alignment.CenterEnd).size(20.dp),
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Filter button
            IconButton(onClick = { viewModel.toggleFilterSheet() }) {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = "Filters",
                    tint = if (state.filter.isActive) Amber else StreamVault.colors.textTertiary,
                )
            }
        }

        // Active filter chips
        if (state.filter.isActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.filter.mediaType?.let { type ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(mediaType = null)) },
                        label = { Text(if (type == "movie") "Movies" else "TV Shows") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber.copy(alpha = 0.2f),
                        ),
                    )
                }
                state.filter.genreId?.let { id ->
                    val name = genreOptions.find { it.first == id }?.second ?: "Genre"
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(genreId = null)) },
                        label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber.copy(alpha = 0.2f),
                        ),
                    )
                }
                state.filter.minRating?.let { rating ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(minRating = null)) },
                        label = { Text("${rating}+") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber.copy(alpha = 0.2f),
                        ),
                    )
                }
                state.filter.year?.let { year ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(year = null)) },
                        label = { Text("$year") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber.copy(alpha = 0.2f),
                        ),
                    )
                }
                TextButton(onClick = { viewModel.clearFilters() }) {
                    Text("Clear all", color = Amber)
                }
            }
        }

        // Results
        val displayItems = if (state.query.length >= 2) state.results else state.discoverResults
        val isLoading = state.isSearching || state.isDiscovering

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(40.dp),
                        color = Amber,
                        strokeWidth = 3.dp,
                    )
                }

                displayItems.isEmpty() && (state.query.length >= 2 || state.filter.isActive) && !isLoading -> {
                    Text(
                        text = "No results found",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                        color = StreamVault.colors.textTertiary,
                    )
                }

                displayItems.isNotEmpty() -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 130.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(displayItems, key = { it.id }) { item ->
                            PosterCard(
                                item = item,
                                size = CardSize.MEDIUM,
                                onClick = { onMediaClick(item) },
                            )
                        }
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = StreamVault.colors.textHint,
                        )
                        Text(
                            text = "Search for movies and TV shows",
                            style = MaterialTheme.typography.bodyLarge,
                            color = StreamVault.colors.textTertiary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    // Filter dialog
    if (state.showFilterSheet) {
        SearchFilterDialog(
            currentFilter = state.filter,
            onApply = { viewModel.applyFilter(it) },
            onDismiss = { viewModel.dismissFilterSheet() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchFilterDialog(
    currentFilter: SearchFilter,
    onApply: (SearchFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var mediaType by remember { mutableStateOf(currentFilter.mediaType) }
    var genreId by remember { mutableStateOf(currentFilter.genreId) }
    var minRating by remember { mutableFloatStateOf(currentFilter.minRating ?: 0f) }
    var hasRatingFilter by remember { mutableStateOf(currentFilter.minRating != null) }
    var year by remember { mutableStateOf(currentFilter.year?.toString() ?: "") }
    var genreExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search Filters") },
        text = {
            Column {
                // Media type
                Text("Type", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mediaType == null,
                        onClick = { mediaType = null },
                        label = { Text("All") },
                    )
                    FilterChip(
                        selected = mediaType == "movie",
                        onClick = { mediaType = "movie" },
                        label = { Text("Movies") },
                    )
                    FilterChip(
                        selected = mediaType == "tv",
                        onClick = { mediaType = "tv" },
                        label = { Text("TV Shows") },
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Genre
                ExposedDropdownMenuBox(
                    expanded = genreExpanded,
                    onExpandedChange = { genreExpanded = it },
                ) {
                    OutlinedTextField(
                        value = genreOptions.find { it.first == genreId }?.second ?: "All Genres",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Genre") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(genreExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = genreExpanded,
                        onDismissRequest = { genreExpanded = false },
                    ) {
                        genreOptions.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    genreId = id
                                    genreExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Min rating
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = hasRatingFilter,
                        onClick = { hasRatingFilter = !hasRatingFilter },
                        label = { Text("Min Rating") },
                    )
                    if (hasRatingFilter) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${"%.1f".format(minRating)}+",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Amber,
                        )
                    }
                }
                if (hasRatingFilter) {
                    Slider(
                        value = minRating,
                        onValueChange = { minRating = it },
                        valueRange = 0f..9f,
                        steps = 17,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Year
                OutlinedTextField(
                    value = year,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) year = it },
                    label = { Text("Year") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply(
                    SearchFilter(
                        mediaType = mediaType,
                        genreId = genreId,
                        minRating = if (hasRatingFilter && minRating > 0f) minRating else null,
                        year = year.toIntOrNull()?.takeIf { it in 1900..2030 },
                    ),
                )
            }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
