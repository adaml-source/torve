package com.torve.android.ui.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.LocalRatingPrefs
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.data.integrations.JellyfinBrowseItem
import com.torve.domain.model.resolveCardStyle
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.toMediaItem
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.MediaFavoritesRepository
import com.torve.data.mdblist.RatingsEnricher
import com.torve.presentation.jellyfin.JellyfinBrowserViewModel
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun WatchlistScreen(
    onMediaClick: (MediaItem) -> Unit,
    onContinueWatchingClick: (WatchProgress) -> Unit = {},
    onHistoryItemClick: (WatchHistoryEntry) -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onJellyfinItemPlay: (streamUrl: String, title: String) -> Unit = { _, _ -> },
    watchlistViewModel: WatchlistViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    ratingsEnricher: RatingsEnricher = koinInject(),
    jellyfinBrowserViewModel: JellyfinBrowserViewModel = koinInject(),
    mediaFavoritesRepository: MediaFavoritesRepository = koinInject(),
) {
    val watchlistState by watchlistViewModel.state.collectAsState()
    val favoritesState by mediaFavoritesRepository.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val isJellyfinConnected by produceState(false) {
        value = jellyfinBrowserViewModel.isConnected()
    }

    val tabs = buildList {
        add(stringResource(R.string.watchlist_title))
        add(stringResource(R.string.channels_favorites))
        add(stringResource(R.string.download_title))
        if (isJellyfinConnected) add(stringResource(R.string.watchlist_jellyfin))
    }

    // Guard against tab index out of bounds when Jellyfin disconnects
    if (selectedTab >= tabs.size) selectedTab = 0

    LaunchedEffect(Unit) {
        watchlistViewModel.loadWatchlist()
        mediaFavoritesRepository.refresh(force = true)
    }

    var enrichedWatchlist by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(watchlistState.items, settingsState.mdblistApiKey) {
        val baseItems = watchlistState.items.map { wlItem ->
            MediaItem(
                id = wlItem.mediaId,
                tmdbId = wlItem.tmdbId,
                imdbId = wlItem.imdbId,
                title = wlItem.title,
                posterUrl = wlItem.posterUrl,
                backdropUrl = wlItem.backdropUrl,
                rating = wlItem.rating,
                year = wlItem.year,
                type = wlItem.mediaType,
            )
        }
        // Always enrich — the enricher falls back to OMDB and Trakt tiers when
        // no MDBList key is configured, so IMDB ratings (needed by the "Highest
        // IMDB Rating" sort) still populate without a paid key.
        val apiKey = settingsState.mdblistApiKey
        enrichedWatchlist = withContext(Dispatchers.Default) {
            ratingsEnricher.enrichList(baseItems, apiKey)
        }
    }
    var enrichedFavorites by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(favoritesState.items, settingsState.mdblistApiKey) {
        val baseItems = favoritesState.items.map { it.toMediaItem() }
        enrichedFavorites = withContext(Dispatchers.Default) {
            ratingsEnricher.enrichList(baseItems, settingsState.mdblistApiKey)
        }
    }

    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )
    CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
        LocalRatingPrefs provides settingsState.ratingPrefs,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding(),
    ) {
        // Header
        Text(
            text = stringResource(R.string.nav_library),
            style = MaterialTheme.typography.headlineLarge,
            color = Snow,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )

        // Sub-tabs — horizontally scrollable chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tabs.size) { index ->
                val selected = selectedTab == index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Amber.copy(alpha = 0.2f) else Gunmetal)
                        .clickable { selectedTab = index }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = tabs[index],
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) Amber else Torve.colors.textSecondary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (selectedTab) {
            0 -> WatchlistTab(
                items = enrichedWatchlist,
                isLoading = watchlistState.isLoading,
                onMediaClick = onMediaClick,
                emptyTitle = stringResource(R.string.watchlist_empty),
                emptyDescription = stringResource(R.string.watchlist_empty_desc),
            )
            1 -> WatchlistTab(
                items = enrichedFavorites,
                isLoading = favoritesState.isLoading,
                onMediaClick = onMediaClick,
                emptyTitle = "No favorites yet",
                emptyDescription = "Movies and shows you favorite appear here.",
            )
            2 -> {
                LaunchedEffect(Unit) {
                    onDownloadsClick()
                    selectedTab = 0
                }
            }
            3 -> if (isJellyfinConnected) {
                JellyfinTab(
                    viewModel = jellyfinBrowserViewModel,
                    onItemPlay = onJellyfinItemPlay,
                )
            }
        }
    }
    }
}

@Composable
private fun WatchlistTab(
    items: List<MediaItem>,
    isLoading: Boolean,
    onMediaClick: (MediaItem) -> Unit,
    emptyTitle: String,
    emptyDescription: String,
) {
    var sortMode by remember { mutableStateOf(com.torve.presentation.seeall.SeeAllSortMode.DEFAULT) }
    var yearFrom by remember { mutableStateOf<Int?>(null) }
    var yearTo by remember { mutableStateOf<Int?>(null) }
    var genreIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.size(40.dp))
        }
        return
    }

    if (items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    emptyTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Torve.colors.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    emptyDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Torve.colors.textTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        return
    }

    val displayed = com.torve.presentation.seeall.applySortAndFilter(
        items = items,
        sortMode = sortMode,
        yearFrom = yearFrom,
        yearTo = yearTo,
        genreIds = genreIds,
    )
    val movies = displayed.filter { it.type == MediaType.MOVIE }
    val shows = displayed.filter { it.type == MediaType.SERIES }

    val sortOptions = remember {
        listOf(
            com.torve.android.ui.components.SortOption(com.torve.presentation.seeall.SeeAllSortMode.DEFAULT.name, "Recently watched"),
            com.torve.android.ui.components.SortOption(com.torve.presentation.seeall.SeeAllSortMode.A_Z.name, "A → Z"),
            com.torve.android.ui.components.SortOption(com.torve.presentation.seeall.SeeAllSortMode.Z_A.name, "Z → A"),
            com.torve.android.ui.components.SortOption(com.torve.presentation.seeall.SeeAllSortMode.IMDB_DESC.name, "Highest IMDB Rating"),
            com.torve.android.ui.components.SortOption(com.torve.presentation.seeall.SeeAllSortMode.TMDB_DESC.name, "Highest TMDB Rating"),
            com.torve.android.ui.components.SortOption(com.torve.presentation.seeall.SeeAllSortMode.YEAR_DESC.name, "Newest"),
            com.torve.android.ui.components.SortOption(com.torve.presentation.seeall.SeeAllSortMode.YEAR_ASC.name, "Oldest"),
        )
    }
    val availableGenres = items.flatMap { it.genres.map { g -> g.id to g.name } }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .map { it.key }
    val years = items.mapNotNull { it.year }.filter { it in 1900..2100 }
    val yearRange = if (years.isEmpty()) null else years.min()..years.max()

    // After any sort / filter change, snap both horizontal rows back to the
    // first card so the user sees the newly-sorted top item — the LazyRow
    // preserves its scroll offset by default, which meant the top-rated pick
    // ended up off-screen after picking "Highest IMDB Rating".
    val moviesRowState = rememberLazyListState()
    val showsRowState = rememberLazyListState()
    LaunchedEffect(sortMode, yearFrom, yearTo, genreIds) {
        moviesRowState.scrollToItem(0)
        showsRowState.scrollToItem(0)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            com.torve.android.ui.components.MediaSortFilterBar(
                currentSort = sortOptions.first { it.key == sortMode.name },
                availableSorts = sortOptions,
                onSortSelected = { opt -> sortMode = com.torve.presentation.seeall.SeeAllSortMode.valueOf(opt.key) },
                availableGenres = availableGenres,
                selectedGenreIds = genreIds,
                onGenreToggled = { id ->
                    genreIds = if (id in genreIds) genreIds - id else genreIds + id
                },
                availableYearRange = yearRange,
                selectedYearFrom = yearFrom,
                selectedYearTo = yearTo,
                onYearRangeChanged = { from, to -> yearFrom = from; yearTo = to },
                onClearFilters = { yearFrom = null; yearTo = null; genreIds = emptySet() },
            )
        }
        if (movies.isNotEmpty()) {
            item {
                Text(
                    "${stringResource(R.string.watchlist_movies)} (${movies.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Amber,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item {
                LazyRow(
                    state = moviesRowState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(movies, key = { "${it.type}:${it.id}" }) { wlItem ->
                        PosterCard(
                            item = wlItem,
                            onClick = { onMediaClick(wlItem) },
                            sizeOverride = CardSize.MEDIUM,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        if (shows.isNotEmpty()) {
            item {
                Text(
                    "${stringResource(R.string.watchlist_tv_shows)} (${shows.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Amber,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item {
                LazyRow(
                    state = showsRowState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shows, key = { "${it.type}:${it.id}" }) { wlItem ->
                        PosterCard(
                            item = wlItem,
                            onClick = { onMediaClick(wlItem) },
                            sizeOverride = CardSize.MEDIUM,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InProgressTab(
    items: List<WatchProgress>,
    isLoaded: Boolean,
    onItemClick: (WatchProgress) -> Unit,
) {
    if (!isLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.size(40.dp))
        }
        return
    }

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.watchlist_nothing_in_progress),
                    style = MaterialTheme.typography.titleMedium,
                    color = Torve.colors.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.watchlist_start_watching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Torve.colors.textTertiary,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.mediaId }) { progress ->
            ContinueWatchingCard(
                progress = progress,
                onClick = { onItemClick(progress) },
            )
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    progress: WatchProgress,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Gunmetal)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Poster — content-policy: no real artwork for placeholder items
        AsyncImage(
            model = if (progress.isContentPlaceholder) null else progress.posterUrl,
            contentDescription = if (progress.isContentPlaceholder) null else progress.title,
            modifier = Modifier
                .width(70.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = progress.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Snow,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress.seasonNumber != null && progress.episodeNumber != null) {
                Text(
                    stringResource(R.string.episode_format, progress.seasonNumber!!, progress.episodeNumber!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
            }
            Spacer(Modifier.height(8.dp))
            // Progress bar
            val progressPercent = if (progress.durationMs > 0) {
                (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = { progressPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Amber,
                trackColor = Obsidian,
            )
            Spacer(Modifier.height(4.dp))
            val remaining = ((progress.durationMs - progress.positionMs) / 60000).toInt()
            Text(
                "${remaining}min remaining",
                style = MaterialTheme.typography.labelSmall,
                color = Torve.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun HistoryTab(
    items: List<WatchHistoryEntry>,
    isLoaded: Boolean,
    onItemClick: (WatchHistoryEntry) -> Unit,
) {
    if (!isLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.size(40.dp))
        }
        return
    }

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.watchlist_no_history),
                    style = MaterialTheme.typography.titleMedium,
                    color = Torve.colors.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.watchlist_history_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Torve.colors.textTertiary,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { entry ->
            HistoryEntryCard(
                entry = entry,
                onClick = { onItemClick(entry) },
            )
        }
    }
}

@Composable
private fun HistoryEntryCard(
    entry: WatchHistoryEntry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Gunmetal)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Content-policy: no real artwork for placeholder history entries
        AsyncImage(
            model = if (entry.isContentPlaceholder) null else entry.posterUrl,
            contentDescription = if (entry.isContentPlaceholder) null else entry.title,
            modifier = Modifier
                .width(50.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Snow,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.seasonNumber != null && entry.episodeNumber != null) {
                Text(
                    "${entry.showTitle ?: ""} ${stringResource(R.string.episode_format, entry.seasonNumber!!, entry.episodeNumber!!)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
                    maxLines = 1,
                )
            }
            val durationMin = (entry.durationWatchedMs / 60000).toInt()
            if (durationMin > 0) {
                Text(
                    stringResource(R.string.watchlist_watched_min, durationMin),
                    style = MaterialTheme.typography.labelSmall,
                    color = Torve.colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun JellyfinTab(
    viewModel: JellyfinBrowserViewModel,
    onItemPlay: (streamUrl: String, title: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadLibrary() }

    if (state.isLoading && state.sections.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.size(40.dp))
        }
        return
    }

    state.error?.let { error ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error, color = Torve.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    if (state.sections.isEmpty() && !state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.jellyfin_library_empty),
                color = Torve.colors.textSecondary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        for (section in state.sections) {
            val items = state.sectionItems[section.id] ?: continue
            if (items.isEmpty()) continue

            item(key = "header_${section.id}") {
                Text(
                    "${section.name} (${items.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Amber,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item(key = "row_${section.id}") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.id }) { jfItem ->
                        JellyfinItemCard(
                            item = jfItem,
                            viewModel = viewModel,
                            onPlay = onItemPlay,
                        )
                    }
                }
            }
            item(key = "spacer_${section.id}") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun JellyfinItemCard(
    item: JellyfinBrowseItem,
    viewModel: JellyfinBrowserViewModel,
    onPlay: (streamUrl: String, title: String) -> Unit,
) {
    val imageUrl by produceState<String?>(null, item.id) {
        value = viewModel.buildImageUrl(item.id)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable {
                scope.launch {
                    val url = viewModel.buildStreamUrl(item.id)
                    if (url != null) onPlay(url, item.name)
                }
            },
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = item.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(Gunmetal),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            color = Snow,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.productionYear?.let { year ->
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = Torve.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun DownloadsTab(
    downloads: List<com.torve.domain.model.Download>,
    isLoading: Boolean,
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.size(28.dp))
        }
        return
    }

    if (downloads.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.download_no_downloads),
                    style = MaterialTheme.typography.titleMedium,
                    color = Torve.colors.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Tap the download icon on an episode to save it for offline access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textTertiary,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(downloads, key = { it.id }) { dl ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Gunmetal)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!dl.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = dl.posterUrl,
                        contentDescription = dl.title,
                        modifier = Modifier
                            .width(48.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dl.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Snow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val statusText = when (dl.status) {
                        com.torve.domain.model.DownloadStatus.PENDING -> "Pending"
                        com.torve.domain.model.DownloadStatus.DOWNLOADING -> {
                            val pct = if ((dl.fileSizeBytes ?: 0) > 0) {
                                ((dl.downloadedBytes ?: 0) * 100 / dl.fileSizeBytes!!).toInt()
                            } else 0
                            "Downloading · $pct%"
                        }
                        com.torve.domain.model.DownloadStatus.COMPLETED -> "Ready offline"
                        com.torve.domain.model.DownloadStatus.FAILED -> "Failed"
                        com.torve.domain.model.DownloadStatus.PAUSED -> "Paused"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (dl.status) {
                            com.torve.domain.model.DownloadStatus.COMPLETED -> Amber
                            com.torve.domain.model.DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> Torve.colors.textSecondary
                        },
                    )
                    if (dl.status == com.torve.domain.model.DownloadStatus.DOWNLOADING) {
                        val pct = if ((dl.fileSizeBytes ?: 0) > 0) {
                            (dl.downloadedBytes ?: 0).toFloat() / dl.fileSizeBytes!!.toFloat()
                        } else 0f
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { pct },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Amber,
                            trackColor = Obsidian,
                        )
                    }
                }
            }
        }
    }
}
