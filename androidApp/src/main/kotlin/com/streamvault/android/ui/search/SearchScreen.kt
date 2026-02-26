package com.streamvault.android.ui.search

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.streamvault.android.R
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.components.CardSize
import com.streamvault.android.ui.components.LocalCardStyle
import com.streamvault.android.ui.components.PosterCard
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberSubtle
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.resolveCardStyle
import com.streamvault.presentation.catalog.RuntimeFilter
import com.streamvault.presentation.catalog.SortOption
import com.streamvault.presentation.settings.SettingsViewModel
import com.streamvault.presentation.search.SearchFilter
import com.streamvault.presentation.search.SearchViewModel
import org.koin.compose.koinInject

private val genreOptions = listOf(
    null to "All Genres",
    28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
    80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
    14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
    9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi", 53 to "Thriller",
    10752 to "War", 37 to "Western",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onMediaClick: (MediaItem) -> Unit,
    viewModel: SearchViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )

    CompositionLocalProvider(LocalCardStyle provides defaultCardStyle) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
        // ── Search Input Row ──
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
                if (state.query.isNotEmpty() || state.hasActiveSearch) {
                    IconButton(
                        onClick = { viewModel.clearSearch() },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(24.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Clear search",
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
                            .size(20.dp),
                    )
                }
            }

            // Voice search
            VoiceSearchButton(
                onResult = { viewModel.updateQuery(it) },
            )

            // Filter button with active indicator
            val activeCount = state.filter.activeCount
            BadgedBox(
                badge = {
                    if (activeCount > 0) {
                        Badge(containerColor = Amber, contentColor = Obsidian) {
                            Text("$activeCount")
                        }
                    }
                },
            ) {
                IconButton(onClick = { viewModel.toggleFilterSheet() }) {
                    Icon(
                        Icons.Rounded.FilterList,
                        contentDescription = "Filters",
                        tint = if (state.filter.isActive) Amber else StreamVault.colors.textTertiary,
                    )
                }
            }
        }

        // ── Active Filter Chips ──
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
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                if (state.filter.genreIds.isNotEmpty()) {
                    val names = state.filter.genreIds.mapNotNull { id ->
                        genreOptions.find { it.first == id }?.second
                    }
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(genreIds = emptyList())) },
                        label = { Text(names.joinToString(", ").take(30)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                state.filter.minRating?.let { rating ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(minRating = null)) },
                        label = { Text("★ %.1f+".format(rating)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                state.filter.minImdbScore?.let { imdb ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(minImdbScore = null)) },
                        label = { Text("IMDb %.1f+".format(imdb)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                state.filter.minTmdbScore?.let { tmdb ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(minTmdbScore = null)) },
                        label = { Text("TMDB %.1f+".format(tmdb)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                state.filter.minTorveScore?.let { torve ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(minTorveScore = null)) },
                        label = { Text("Torve %.0f+".format(torve)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                if (state.filter.yearFrom != null || state.filter.yearTo != null) {
                    val yearLabel = when {
                        state.filter.yearFrom != null && state.filter.yearTo != null ->
                            "${state.filter.yearFrom}-${state.filter.yearTo}"
                        state.filter.yearFrom != null -> "${state.filter.yearFrom}+"
                        else -> "≤${state.filter.yearTo}"
                    }
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(yearFrom = null, yearTo = null)) },
                        label = { Text(yearLabel) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                state.filter.runtimeFilter?.let { runtime ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(runtimeFilter = null)) },
                        label = { Text(runtime.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                if (state.filter.sortBy != SortOption.POPULARITY_DESC) {
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(sortBy = SortOption.POPULARITY_DESC)) },
                        label = { Text(state.filter.sortBy.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                if (state.filter.providersAvailabilityOnly) {
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(providersAvailabilityOnly = false)) },
                        label = { Text("Provider Ready") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }
        }

        // ── Active Search Banner ──
        val displayItems = if (state.query.length >= 2 || state.hasActiveSearch) state.results else state.discoverResults
        val isLoading = state.isSearching || state.isDiscovering

        if (state.hasActiveSearch && displayItems.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Charcoal)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "\"${state.query}\"",
                    color = Snow,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${displayItems.size} results",
                    color = Silver,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { viewModel.clearSearch() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text("Clear", color = Amber, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // ── Results ──
        if (state.hasActiveSearch && (state.peopleResults.isNotEmpty() || state.userLists.isNotEmpty())) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                if (state.peopleResults.isNotEmpty()) {
                    Text(
                        text = "People: " + state.peopleResults.take(3).joinToString(", ") { it.name },
                        color = Silver,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.userLists.isNotEmpty()) {
                    Text(
                        text = "Lists: " + state.userLists.joinToString(" • "),
                        color = StreamVault.colors.textTertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && displayItems.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(40.dp),
                        color = Amber,
                        strokeWidth = 3.dp,
                    )
                }

                displayItems.isEmpty() && (state.query.length >= 2 || state.filter.isActive) && !isLoading -> {
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
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No results found",
                            style = MaterialTheme.typography.titleMedium,
                            color = StreamVault.colors.textPrimary,
                        )
                        Text(
                            text = "Try a different search term or adjust filters",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StreamVault.colors.textTertiary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
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
                                sizeOverride = CardSize.MEDIUM,
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

    // ── Filter Bottom Sheet ──
    if (state.showFilterSheet) {
        SearchFilterSheet(
            currentFilter = state.filter,
            onApply = { viewModel.applyFilter(it) },
            onDismiss = { viewModel.dismissFilterSheet() },
        )
    }
}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchFilterSheet(
    currentFilter: SearchFilter,
    onApply: (SearchFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var mediaType by remember { mutableStateOf(currentFilter.mediaType) }
    var selectedGenreIds by remember { mutableStateOf(currentFilter.genreIds) }
    var minRating by remember { mutableFloatStateOf(currentFilter.minRating ?: 0f) }
    var hasRatingFilter by remember { mutableStateOf(currentFilter.minRating != null) }
    var yearFromText by remember { mutableStateOf(currentFilter.yearFrom?.toString() ?: "") }
    var yearToText by remember { mutableStateOf(currentFilter.yearTo?.toString() ?: "") }
    var providersAvailabilityOnly by remember { mutableStateOf(currentFilter.providersAvailabilityOnly) }
    var minImdbScore by remember { mutableStateOf(currentFilter.minImdbScore) }
    var minTmdbScore by remember { mutableStateOf(currentFilter.minTmdbScore) }
    var minTorveScore by remember { mutableStateOf(currentFilter.minTorveScore) }
    var selectedRuntime by remember { mutableStateOf(currentFilter.runtimeFilter) }
    var selectedSort by remember { mutableStateOf(currentFilter.sortBy) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
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
                    "Search Filters",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = {
                    mediaType = null
                    selectedGenreIds = emptyList()
                    minRating = 0f
                    hasRatingFilter = false
                    yearFromText = ""
                    yearToText = ""
                    providersAvailabilityOnly = false
                    minImdbScore = null
                    minTmdbScore = null
                    minTorveScore = null
                    selectedRuntime = null
                    selectedSort = SortOption.POPULARITY_DESC
                }) {
                    Text(stringResource(R.string.catalog_clear_all), color = Amber)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Type ──
            Text(
                "Type",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "All", "movie" to "Movies", "tv" to "TV Shows").forEach { (type, label) ->
                    FilterChip(
                        selected = mediaType == type,
                        onClick = { mediaType = type },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
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
                    FilterChip(
                        selected = selectedSort == option,
                        onClick = { selectedSort = option },
                        label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
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

            // ── Genre (Multi-select) ──
            Text(
                "Genre${if (selectedGenreIds.isNotEmpty()) " (${selectedGenreIds.size})" else ""}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            // Skip first "All Genres" entry; multi-select toggles
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(genreOptions.drop(1)) { (id, name) ->
                    val genId = id ?: return@items
                    val selected = genId in selectedGenreIds
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedGenreIds = if (selected) {
                                selectedGenreIds - genId
                            } else {
                                selectedGenreIds + genId
                            }
                        },
                        label = { Text(name, style = MaterialTheme.typography.labelMedium) },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Minimum Rating",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = hasRatingFilter,
                    onClick = { hasRatingFilter = !hasRatingFilter },
                    label = {
                        Text(
                            if (hasRatingFilter) "%.1f+".format(minRating) else "Any",
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
            if (hasRatingFilter) {
                Slider(
                    value = minRating,
                    onValueChange = { minRating = it },
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

            // ── Year Range ──
            Text(
                "Power Filters",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = providersAvailabilityOnly,
                    onClick = { providersAvailabilityOnly = !providersAvailabilityOnly },
                    label = { Text("Provider Ready") },
                    shape = RoundedCornerShape(16.dp),
                )
                FilterChip(
                    selected = minImdbScore != null,
                    onClick = { minImdbScore = if (minImdbScore == null) 7f else null },
                    label = { Text("IMDb 7.0+") },
                    shape = RoundedCornerShape(16.dp),
                )
                FilterChip(
                    selected = minTmdbScore != null,
                    onClick = { minTmdbScore = if (minTmdbScore == null) 7f else null },
                    label = { Text("TMDB 7.0+") },
                    shape = RoundedCornerShape(16.dp),
                )
                FilterChip(
                    selected = minTorveScore != null,
                    onClick = { minTorveScore = if (minTorveScore == null) 75f else null },
                    label = { Text("Torve 75+") },
                    shape = RoundedCornerShape(16.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Release Year",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val presets = listOf(
                    "2026" to (2026 to 2026),
                    "2025" to (2025 to 2025),
                    "2020s" to (2020 to 2029),
                    "2010s" to (2010 to 2019),
                    "Classic" to (1900 to 1999),
                )
                items(presets) { (label, range) ->
                    val selected = yearFromText == range.first.toString() && yearToText == range.second.toString()
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (selected) {
                                yearFromText = ""
                                yearToText = ""
                            } else {
                                yearFromText = range.first.toString()
                                yearToText = range.second.toString()
                            }
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = yearFromText,
                        onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) yearFromText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(Amber),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            Box {
                                if (yearFromText.isEmpty()) {
                                    Text(stringResource(R.string.catalog_from), style = MaterialTheme.typography.bodyMedium, color = StreamVault.colors.textHint)
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = yearToText,
                        onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) yearToText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(Amber),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            Box {
                                if (yearToText.isEmpty()) {
                                    Text(stringResource(R.string.catalog_to), style = MaterialTheme.typography.bodyMedium, color = StreamVault.colors.textHint)
                                }
                                innerTextField()
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Runtime ──
            Text(
                "Runtime",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeFilter.entries.forEach { runtime ->
                    val selected = selectedRuntime == runtime
                    FilterChip(
                        selected = selected,
                        onClick = { selectedRuntime = if (selected) null else runtime },
                        label = { Text(runtime.label, style = MaterialTheme.typography.labelSmall) },
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

            Spacer(Modifier.height(28.dp))

            // ── Apply ──
            FilledTonalButton(
                onClick = {
                    val yearFrom = yearFromText.toIntOrNull()?.takeIf { it in 1900..2030 }
                    val yearTo = yearToText.toIntOrNull()?.takeIf { it in 1900..2030 }
                    onApply(
                        SearchFilter(
                            mediaType = mediaType,
                            genreIds = selectedGenreIds,
                            minRating = if (hasRatingFilter && minRating > 0f) minRating else null,
                            minImdbScore = minImdbScore,
                            minTmdbScore = minTmdbScore,
                            minTorveScore = minTorveScore,
                            providersAvailabilityOnly = providersAvailabilityOnly,
                            yearFrom = yearFrom,
                            yearTo = yearTo,
                            runtimeFilter = selectedRuntime,
                            sortBy = selectedSort,
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
