package com.torve.android.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.components.ShimmerBox
import com.torve.android.ui.components.ShimmerPosterCard
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.MediaItem
import com.torve.domain.model.resolveCardStyle
import com.torve.presentation.catalog.CatalogCategory
import com.torve.presentation.catalog.CatalogFilter
import com.torve.presentation.catalog.CatalogViewModel
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject
import com.torve.presentation.catalog.RuntimeFilter
import com.torve.presentation.catalog.SortOption
import kotlinx.coroutines.delay
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

// Floating search overlay heights — keep in sync with the overlay Column below.
private val SEARCH_BAR_BLOCK_HEIGHT = 72.dp  // search field + vertical padding
private val BACK_TITLE_ROW_HEIGHT = 44.dp    // optional back/title row

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    mediaType: String,
    onMediaClick: (MediaItem) -> Unit,
    onBack: (() -> Unit)? = null,
    title: String? = null,
    onMediaTypeChange: ((String) -> Unit)? = null,
    settingsViewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val genres = if (mediaType == "movie") MOVIE_GENRES else TV_GENRES
    val gridState = rememberLazyGridState()
    val mdblistApiKey = settingsState.mdblistApiKey

    if (onBack != null) {
        BackHandler(onBack = onBack)
    }

    // Scroll to 0 only when mediaType changes within the same back-stack entry
    // (e.g. provider route movie ↔ tv switch). Do NOT scroll on recomposition
    // from details → back — rememberLazyGridState (saveable) restores position.
    var lastResetMediaType by rememberSaveable { mutableStateOf(mediaType) }
    LaunchedEffect(mediaType) {
        if (mediaType != lastResetMediaType) {
            lastResetMediaType = mediaType
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(mdblistApiKey) {
        if (mdblistApiKey.isNotBlank()) viewModel.refresh()
    }

    // Infinite scroll: trigger loadMore when near the bottom
    // Key includes viewModel so it restarts when VM changes (provider Movies→TV switch)
    LaunchedEffect(gridState, viewModel) {
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

    // Only switch to search results when we actually HAVE results or a completed search.
    // This prevents the grid from blanking during the debounce window (typing "h" → "he"
    // would immediately show empty searchResults before the 300ms debounce fires).
    val isSearchMode = state.hasActiveSearch || state.isAiSearching ||
        (state.isSearching && state.searchResults.isNotEmpty())
    val displayItems = if (isSearchMode) state.searchResults else state.items
    val isLoadingMore = if (isSearchMode) state.isSearchingMore else state.isLoadingMore

    // Track scroll direction for floating search bar visibility
    val previousScrollOffset = remember { mutableIntStateOf(0) }
    val previousFirstVisibleItem = remember { mutableIntStateOf(0) }
    val isSearchBarVisible = remember { derivedStateOf {
        val firstVisible = gridState.firstVisibleItemIndex
        val offset = gridState.firstVisibleItemScrollOffset
        val isScrollingDown = firstVisible > previousFirstVisibleItem.intValue ||
            (firstVisible == previousFirstVisibleItem.intValue && offset > previousScrollOffset.intValue + 10)
        previousFirstVisibleItem.intValue = firstVisible
        previousScrollOffset.intValue = offset
        // Show search bar when at top or scrolling up, hide when scrolling down
        !isScrollingDown || firstVisible <= 1
    } }

    // Top padding so grid content starts below the floating search overlay.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val showBackTitleRow = onBack != null || title != null
    val gridTopPadding = statusBarTop + SEARCH_BAR_BLOCK_HEIGHT +
        if (showBackTitleRow) BACK_TITLE_ROW_HEIGHT else 0.dp

    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )
    CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading && displayItems.isEmpty() -> {
                CatalogSkeletonLoader()
            }

            state.error != null && displayItems.isEmpty() -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.catalog_failed_to_load),
                        style = MaterialTheme.typography.titleMedium,
                        color = Torve.colors.textSecondary,
                    )
                    FilledTonalButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }

            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    contentPadding = PaddingValues(
                        top = gridTopPadding,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {

                    // ── Featured Hero Pager ──
                    if (!isSearchMode && displayItems.size >= 3) {
                        item(
                            key = "hero",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            CatalogHeroPager(
                                items = displayItems.take(5),
                                onItemClick = onMediaClick,
                            )
                        }
                    }

                    // ── Category Chips ──
                    item(
                        key = "categories",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(CatalogCategory.entries.filter { it != CatalogCategory.IN_PROGRESS }) { category ->
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
                                        labelColor = Torve.colors.textSecondary,
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
                    }

                    // ── Genre Chips ──
                    item(
                        key = "genres",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            item {
                                val selected = state.selectedGenreId == null
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.selectGenre(null) },
                                    label = {
                                        Text(stringResource(R.string.catalog_all), style = MaterialTheme.typography.labelMedium)
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AmberSubtle,
                                        selectedLabelColor = Amber,
                                        containerColor = MaterialTheme.colorScheme.background,
                                        labelColor = Torve.colors.textTertiary,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        borderColor = Torve.colors.border,
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
                                        labelColor = Torve.colors.textTertiary,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        borderColor = Torve.colors.border,
                                        selectedBorderColor = Amber.copy(alpha = 0.3f),
                                        enabled = true,
                                        selected = selected,
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                )
                            }
                        }
                    }

                    // ── Section Label ──
                    if (displayItems.isNotEmpty()) {
                        item(
                            key = "section_label",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            Text(
                                text = if (isSearchMode) {
                                    state.aiSearchLabel ?: stringResource(R.string.catalog_search_results)
                                } else {
                                    state.selectedCategory.label
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                color = Torve.colors.textPrimary,
                            )
                        }
                    }

                    // ── Poster Grid ──
                    items(
                        displayItems.size,
                        key = { index -> "${displayItems[index].type}_${displayItems[index].id}_$index" },
                    ) { index ->
                        val item = displayItems[index]
                        PosterCard(
                            item = item,
                            sizeOverride = CardSize.MEDIUM,
                            onClick = { onMediaClick(item) },
                        )
                    }

                    // ── Loading More ──
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

                    // ── Empty State ──
                    if (displayItems.isEmpty() && !state.isSearching && !state.isLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = Torve.colors.textHint,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.catalog_no_results),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Torve.colors.textTertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Floating Search Bar ──
        AnimatedVisibility(
            visible = isSearchBarVisible.value,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Obsidian, Obsidian, Obsidian.copy(alpha = 0.9f), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding(),
            ) {
                // Back + Title row when navigated from provider/genre
                if (onBack != null || title != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onBack != null) {
                            com.torve.android.ui.components.BackButton(onClick = onBack)
                            Spacer(Modifier.width(12.dp))
                        }
                        if (title != null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = Snow,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (onMediaTypeChange != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val isMovie = mediaType == "movie"
                                FilterChip(
                                    selected = isMovie,
                                    onClick = { onMediaTypeChange("movie") },
                                    label = { Text("Movies", style = MaterialTheme.typography.labelMedium) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Amber,
                                        selectedLabelColor = Obsidian,
                                        containerColor = Gunmetal,
                                        labelColor = Silver,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        borderColor = Color.Transparent,
                                        selectedBorderColor = Amber,
                                        enabled = true,
                                        selected = isMovie,
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                )
                                FilterChip(
                                    selected = !isMovie,
                                    onClick = { onMediaTypeChange("tv") },
                                    label = { Text("TV", style = MaterialTheme.typography.labelMedium) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Amber,
                                        selectedLabelColor = Obsidian,
                                        containerColor = Gunmetal,
                                        labelColor = Silver,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        borderColor = Color.Transparent,
                                        selectedBorderColor = Amber,
                                        enabled = true,
                                        selected = !isMovie,
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    CatalogSearchRow(
                        searchQuery = state.searchQuery,
                        activeFilterCount = state.activeFilterCount,
                        mediaType = mediaType,
                        onQueryChange = { viewModel.updateSearchQuery(it) },
                        onClearSearch = { viewModel.clearSearch() },
                        onFilterClick = { viewModel.toggleFilterSheet() },
                        isAiSearching = state.isAiSearching,
                        onAiSearchClick = { viewModel.searchWithAi(settingsState.aiProvider, settingsState.activeAiApiKey) },
                        hasActiveSearch = state.hasActiveSearch,
                    )
                    // AI search hint / error
                    state.aiSearchError?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = Amber.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
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

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Hero Pager — Compact auto-scrolling featured banner
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatalogHeroPager(
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })

    // Auto-scroll every 5 seconds
    LaunchedEffect(pagerState) {
        while (true) {
            delay(5000)
            val next = (pagerState.currentPage + 1) % items.size
            pagerState.animateScrollToPage(next, animationSpec = tween(600))
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val item = items[page]
            CatalogHeroSlide(item = item, onClick = { onItemClick(item) })
        }

        // Page indicators
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(items.size) { index ->
                val isSelected = pagerState.currentPage == index
                val color by animateColorAsState(
                    if (isSelected) Amber else Snow.copy(alpha = 0.3f),
                    label = "dot",
                )
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun CatalogHeroSlide(
    item: MediaItem,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        // Backdrop image
        AsyncImage(
            model = item.backdropUrl ?: item.posterUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // Cinematic gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Obsidian.copy(alpha = 0.3f),
                            Obsidian.copy(alpha = 0.7f),
                            Obsidian.copy(alpha = 0.95f),
                        ),
                    ),
                ),
        )

        // Content overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            // Genre tags
            if (item.genres.isNotEmpty()) {
                Text(
                    text = item.genres.take(3).joinToString(" • ") { it.name },
                    style = MaterialTheme.typography.labelMedium,
                    color = Amber,
                )
                Spacer(Modifier.height(4.dp))
            }

            // Title
            Text(
                text = item.title,
                style = MaterialTheme.typography.displaySmall,
                color = Snow,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Year + Rating
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.year?.let {
                    Text(
                        text = it.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Snow.copy(alpha = 0.7f),
                    )
                }
                item.rating?.let { rating ->
                    if (rating > 0) {
                        Text(
                            text = "  •  ★ ${"%.1f".format(rating)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Amber.copy(alpha = 0.9f),
                        )
                    }
                }
            }

            // Overview snippet
            item.overview?.let { overview ->
                if (overview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = Snow.copy(alpha = 0.5f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Search Row
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun CatalogSearchRow(
    searchQuery: String,
    activeFilterCount: Int,
    mediaType: String,
    onQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onFilterClick: () -> Unit,
    isAiSearching: Boolean = false,
    onAiSearchClick: () -> Unit = {},
    hasActiveSearch: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                value = searchQuery,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Snow),
                cursorBrush = SolidColor(Amber),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(if (mediaType == "movie") R.string.catalog_search_movies else R.string.catalog_search_tv),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Torve.colors.textHint,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (searchQuery.isNotEmpty() || hasActiveSearch) {
                IconButton(
                    onClick = onClearSearch,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(24.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = Torve.colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Torve.colors.textTertiary,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(18.dp),
                )
            }
        }

        // AI sparkle button — visible when query has text
        if (searchQuery.length >= 2 || hasActiveSearch) {
            IconButton(
                onClick = onAiSearchClick,
                enabled = !isAiSearching,
            ) {
                if (isAiSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Amber,
                    )
                } else {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = "AI Search",
                        tint = Amber,
                    )
                }
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }

        // Filter button with badge
        IconButton(onClick = onFilterClick) {
            val count = activeFilterCount
            if (count > 0) {
                BadgedBox(badge = {
                    Badge(containerColor = Amber) {
                        Text(count.toString(), color = MaterialTheme.colorScheme.background)
                    }
                }) {
                    Icon(
                        Icons.Rounded.FilterList,
                        contentDescription = stringResource(R.string.catalog_filters),
                        tint = Amber,
                    )
                }
            } else {
                Icon(
                    Icons.Rounded.FilterList,
                    contentDescription = stringResource(R.string.catalog_filters),
                    tint = Torve.colors.textSecondary,
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Skeleton Loader — Cinematic shimmer placeholders
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun CatalogSkeletonLoader() {
    Column(modifier = Modifier.fillMaxSize()) {
        // Hero shimmer
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(300.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(12.dp))

        // Search bar shimmer
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(12.dp))

        // Chips shimmer
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(4) {
                ShimmerBox(
                    modifier = Modifier
                        .width(80.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // Grid shimmer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(3) {
                ShimmerPosterCard(modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(3) {
                ShimmerPosterCard(modifier = Modifier.weight(1f))
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Filter Bottom Sheet
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
    var yearFromText by remember { mutableStateOf(currentFilter.year?.toString() ?: "") }
    var yearToText by remember { mutableStateOf(currentFilter.yearTo?.toString() ?: "") }
    var selectedRuntime by remember { mutableStateOf(currentFilter.runtimeFilter) }

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
                    stringResource(R.string.catalog_filters),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.catalog_clear_all), color = Amber)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Sort By ──
            Text(
                stringResource(R.string.catalog_sort_by),
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
                    stringResource(R.string.catalog_min_rating),
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
                            if (ratingEnabled) "%.1f+".format(ratingValue) else stringResource(R.string.catalog_any),
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
                    Text("0", style = MaterialTheme.typography.labelSmall, color = Torve.colors.textTertiary)
                    Text("9+", style = MaterialTheme.typography.labelSmall, color = Torve.colors.textTertiary)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Year Range ──
            Text(
                stringResource(R.string.catalog_release_year),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            // Quick preset chips
            val classicLabel = stringResource(R.string.catalog_classic)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val currentYear = 2026
                val presets = listOf(
                    "2026" to (currentYear to currentYear),
                    "2025" to (currentYear - 1 to currentYear - 1),
                    "2020s" to (2020 to 2029),
                    "2010s" to (2010 to 2019),
                    "2000s" to (2000 to 2009),
                    classicLabel to (1900 to 1999),
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
            // From / To fields
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
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(Amber),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            Box {
                                if (yearFromText.isEmpty()) {
                                    Text(stringResource(R.string.catalog_from), style = MaterialTheme.typography.bodyMedium, color = Torve.colors.textHint)
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
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(Amber),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            Box {
                                if (yearToText.isEmpty()) {
                                    Text(stringResource(R.string.catalog_to), style = MaterialTheme.typography.bodyMedium, color = Torve.colors.textHint)
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
                stringResource(R.string.catalog_runtime),
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
                        label = {
                            Text(runtime.label, style = MaterialTheme.typography.labelSmall)
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

            Spacer(Modifier.height(28.dp))

            // ── Apply Button ──
            FilledTonalButton(
                onClick = {
                    val yearFrom = yearFromText.toIntOrNull()?.takeIf { it in 1900..2030 }
                    val yearTo = yearToText.toIntOrNull()?.takeIf { it in 1900..2030 }
                    onApply(
                        CatalogFilter(
                            sortBy = selectedSort,
                            minRating = if (ratingEnabled && ratingValue > 0f) ratingValue else null,
                            year = yearFrom,
                            yearTo = yearTo,
                            runtimeFilter = selectedRuntime,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    stringResource(R.string.catalog_apply_filters),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
