package com.streamvault.android.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.components.CardSize
import com.streamvault.android.ui.components.PosterCard
import com.streamvault.android.ui.components.ShimmerPosterCard
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberSubtle
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.MediaItem
import com.streamvault.presentation.catalog.CatalogCategory
import com.streamvault.presentation.catalog.CatalogFilter
import com.streamvault.presentation.catalog.CatalogViewModel
import com.streamvault.presentation.catalog.SortOption
import kotlinx.coroutines.flow.distinctUntilChanged

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

@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    mediaType: String,
    onMediaClick: (MediaItem) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val genres = if (mediaType == "movie") MOVIE_GENRES else TV_GENRES
    val gridState = rememberLazyGridState()

    // Infinite scroll: trigger loadMore when near the bottom
    LaunchedEffect(gridState) {
        snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to totalItems
        }
            .distinctUntilChanged()
            .collect { (lastVisible, totalItems) ->
                if (totalItems > 0 && lastVisible >= totalItems - 6) {
                    viewModel.loadMore()
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // ── Search Bar + Filter Button ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Gunmetal)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = Snow,
                    ),
                    cursorBrush = SolidColor(Amber),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Box {
                            if (state.searchQuery.isEmpty()) {
                                Text(
                                    text = "Search ${if (mediaType == "movie") "movies" else "TV shows"}...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StreamVault.colors.textHint,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.clearSearch() },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(24.dp),
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
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(18.dp),
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Filter button with badge
            IconButton(onClick = { viewModel.toggleFilterSheet() }) {
                val count = state.activeFilterCount
                if (count > 0) {
                    BadgedBox(badge = {
                        Badge(containerColor = Amber) {
                            Text(count.toString(), color = MaterialTheme.colorScheme.background)
                        }
                    }) {
                        Icon(
                            Icons.Rounded.FilterList,
                            contentDescription = "Filters",
                            tint = Amber,
                        )
                    }
                } else {
                    Icon(
                        Icons.Rounded.FilterList,
                        contentDescription = "Filters",
                        tint = StreamVault.colors.textSecondary,
                    )
                }
            }
        }

        // ── Category Chips ──
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(CatalogCategory.entries) { category ->
                val selected = state.selectedCategory == category
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectCategory(category) },
                    label = {
                        Text(
                            category.label,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Amber,
                        selectedLabelColor = MaterialTheme.colorScheme.background,
                        containerColor = Gunmetal,
                        labelColor = StreamVault.colors.textSecondary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = MaterialTheme.colorScheme.background,
                        selectedBorderColor = Amber,
                        enabled = true,
                        selected = selected,
                    ),
                    shape = RoundedCornerShape(20.dp),
                )
            }
        }

        // ── Genre Chips ──
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                val selected = state.selectedGenreId == null
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectGenre(null) },
                    label = {
                        Text("All", style = MaterialTheme.typography.labelMedium)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AmberSubtle,
                        selectedLabelColor = Amber,
                        containerColor = MaterialTheme.colorScheme.background,
                        labelColor = StreamVault.colors.textTertiary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = StreamVault.colors.border,
                        selectedBorderColor = Amber.copy(alpha = 0.3f),
                        enabled = true,
                        selected = selected,
                    ),
                    shape = RoundedCornerShape(16.dp),
                )
            }
            items(genres) { (id, name) ->
                val selected = state.selectedGenreId == id
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectGenre(id) },
                    label = {
                        Text(name, style = MaterialTheme.typography.labelMedium)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AmberSubtle,
                        selectedLabelColor = Amber,
                        containerColor = MaterialTheme.colorScheme.background,
                        labelColor = StreamVault.colors.textTertiary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = StreamVault.colors.border,
                        selectedBorderColor = Amber.copy(alpha = 0.3f),
                        enabled = true,
                        selected = selected,
                    ),
                    shape = RoundedCornerShape(16.dp),
                )
            }
        }

        // ── Content Grid ──
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 130.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(12) { ShimmerPosterCard() }
                    }
                }

                state.error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Failed to load",
                            style = MaterialTheme.typography.titleMedium,
                            color = StreamVault.colors.textSecondary,
                        )
                        FilledTonalButton(
                            onClick = { viewModel.refresh() },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Retry")
                        }
                    }
                }

                else -> {
                    val isSearchMode = state.searchQuery.length >= 2
                    val displayItems = if (isSearchMode) state.searchResults else state.items
                    val isLoadingMore = if (isSearchMode) state.isSearchingMore else state.isLoadingMore

                    if (displayItems.isEmpty() && !state.isSearching) {
                        Text(
                            text = "No results found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = StreamVault.colors.textTertiary,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 130.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(
                                displayItems.size,
                                key = { index -> "${displayItems[index].id}_$index" },
                            ) { index ->
                                val item = displayItems[index]
                                PosterCard(
                                    item = item,
                                    size = CardSize.MEDIUM,
                                    onClick = { onMediaClick(item) },
                                )
                            }
                            // Loading indicator at bottom
                            if (isLoadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            color = Amber,
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Filter Bottom Sheet ──
    if (state.showFilterSheet) {
        FilterBottomSheet(
            currentFilter = state.filter,
            onApply = { viewModel.applyFilter(it) },
            onClear = { viewModel.clearFilters() },
            onDismiss = { viewModel.dismissFilterSheet() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBottomSheet(
    currentFilter: CatalogFilter,
    onApply: (CatalogFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedSort by remember { mutableStateOf(currentFilter.sortBy) }
    var ratingValue by remember { mutableFloatStateOf(currentFilter.minRating ?: 0f) }
    var ratingEnabled by remember { mutableStateOf(currentFilter.minRating != null) }
    var yearText by remember { mutableStateOf(currentFilter.year?.toString() ?: "") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onClear) {
                    Text("Clear All", color = Amber)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Sort By ──
            Text(
                "Sort By",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SortOption.entries) { option ->
                    val selected = selectedSort == option
                    FilterChip(
                        selected = selected,
                        onClick = { selectedSort = option },
                        label = {
                            Text(option.label, style = MaterialTheme.typography.labelMedium)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber,
                            selectedLabelColor = MaterialTheme.colorScheme.background,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Minimum Rating ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Minimum Rating",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = ratingEnabled,
                    onClick = { ratingEnabled = !ratingEnabled },
                    label = {
                        Text(
                            if (ratingEnabled) "%.1f+".format(ratingValue) else "Any",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AmberSubtle,
                        selectedLabelColor = Amber,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    shape = RoundedCornerShape(16.dp),
                )
            }
            if (ratingEnabled) {
                Slider(
                    value = ratingValue,
                    onValueChange = { ratingValue = it },
                    valueRange = 0f..9f,
                    steps = 17,
                    colors = SliderDefaults.colors(
                        thumbColor = Amber,
                        activeTrackColor = Amber,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("0", style = MaterialTheme.typography.labelSmall, color = StreamVault.colors.textTertiary)
                    Text("9+", style = MaterialTheme.typography.labelSmall, color = StreamVault.colors.textTertiary)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Year ──
            Text(
                "Release Year",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Quick year chips
                val currentYear = 2026
                listOf(currentYear, currentYear - 1, currentYear - 2, currentYear - 5, currentYear - 10).forEach { y ->
                    val label = if (y == currentYear) "2026" else y.toString()
                    val selected = yearText == y.toString()
                    FilterChip(
                        selected = selected,
                        onClick = {
                            yearText = if (selected) "" else y.toString()
                        },
                        label = {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = yearText,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) yearText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(Amber),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Box {
                            if (yearText.isEmpty()) {
                                Text(
                                    "Enter year (e.g. 2024)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StreamVault.colors.textHint,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Apply Button ──
            FilledTonalButton(
                onClick = {
                    val year = yearText.toIntOrNull()?.takeIf { it in 1900..2030 }
                    onApply(
                        CatalogFilter(
                            sortBy = selectedSort,
                            minRating = if (ratingEnabled && ratingValue > 0f) ratingValue else null,
                            year = year,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "Apply Filters",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
