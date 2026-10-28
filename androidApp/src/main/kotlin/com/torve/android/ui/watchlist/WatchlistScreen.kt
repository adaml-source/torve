package com.torve.android.ui.watchlist

import com.torve.android.ui.community.parityLabel as localizedUiText

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.torve.android.ui.community.adaptiveColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import androidx.compose.foundation.layout.heightIn
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.LocalRatingPrefs
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Ruby
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
import com.torve.presentation.library.AcquisitionLifecycleItem
import com.torve.presentation.library.AcquisitionStage
import com.torve.presentation.library.PermanentLibraryViewModel
import com.torve.presentation.settings.SettingsViewModel
import com.torve.domain.lanlibrary.PlaybackRoute
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
    onJellyfinItemPlay: (route: PlaybackRoute.JellyfinStream, item: MediaItem, season: Int?, episode: Int?) -> Unit = { _, _, _, _ -> },
    onLibraryCollection: (String) -> Unit = {},
    onEpisode: (com.torve.presentation.watching.WatchingEpisode) -> Unit = {},
    onOpenEpisode: (MediaItem, Int?, Int?) -> Unit = { item, _, _ -> onMediaClick(item) },
    downloadsContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    onToolsClick: () -> Unit = {},
    onCollectionsClick: () -> Unit = onToolsClick,
    onYourShowsClick: () -> Unit = {},
    onFilesClick: () -> Unit = {},
    onJellyfinSetup: () -> Unit = onToolsClick,
    watchlistViewModel: WatchlistViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    ratingsEnricher: RatingsEnricher = koinInject(),
    jellyfinBrowserViewModel: JellyfinBrowserViewModel = koinInject(),
    permanentLibraryViewModel: PermanentLibraryViewModel = koinInject(),
    mediaFavoritesRepository: MediaFavoritesRepository = koinInject(),
) {
    val watchlistState by watchlistViewModel.state.collectAsState()
    val favoritesState by mediaFavoritesRepository.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var primarySection by rememberSaveable { mutableIntStateOf(0) }
    var watchlistSection by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val progressRepository: com.torve.domain.repository.WatchProgressRepository = koinInject()
    val progress by produceState<List<WatchProgress>>(emptyList(), settingsState.personalDataUserId, watchlistSection) {
        value = progressRepository.getInProgress(100)
    }
    var loadedLibraryOwner by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(settingsState.personalDataUserId) {
        val owner = settingsState.personalDataUserId ?: return@LaunchedEffect
        if (loadedLibraryOwner != owner) {
            loadedLibraryOwner = owner
            watchlistViewModel.loadWatchlist()
            mediaFavoritesRepository.refresh(force = true)
        }
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
        enrichedWatchlist = baseItems
        // Always enrich — the enricher falls back to OMDB and Trakt tiers when
        // no MDBList key is configured, so IMDB ratings (needed by the "Highest
        // IMDB Rating" sort) still populate without a paid key.
        val apiKey = settingsState.mdblistApiKey
        enrichedWatchlist = withContext(Dispatchers.Default) {
            ratingsEnricher.enrichList(baseItems, apiKey)
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
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                listOf("Watchlist" to Icons.Rounded.BookmarkBorder, "Favorites" to Icons.Rounded.FavoriteBorder,
                    "Jellyfin" to Icons.Rounded.Storage, "Downloads" to Icons.Rounded.Download).forEachIndexed { index, (label, icon) ->
                    item(label) {
                        com.torve.android.ui.components.MobileLibraryDestination(label, icon, primarySection == index,
                            { primarySection = index }, Modifier.testTag("library_destination_$index"))
                    }
                }
            }
            Box {
                androidx.compose.material3.IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = localizedUiText("Library menu")) }
                androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    listOf("Viewing history" to "history", "Requests" to "requests", "Cloud files" to "cloud").forEach { (title, kind) ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(title) }, onClick = { menu = false; onLibraryCollection(kind) })
                    }
                    androidx.compose.material3.DropdownMenuItem(text = { Text(localizedUiText("Saved collections")) }, onClick = { menu = false; onCollectionsClick() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text(localizedUiText("Library tools")) }, onClick = { menu = false; onToolsClick() })
                }
            }
        }
        when (primarySection) {
            // Use the same owner-scoped, policy-filtered browser as the
            // existing Favorites destination, including its metadata hydration.
            1 -> com.torve.android.ui.community.LibraryCollectionScreen("favorites", onBack = { primarySection = 0 },
                onOpen = { row, _ -> onOpenEpisode(MediaItem(id = row.mediaId, type = if (row.mediaType == "tv") MediaType.SERIES else MediaType.MOVIE,
                    title = row.title, posterUrl = row.artwork), row.season.takeIf { it > 0 }, row.episode.takeIf { it > 0 }) },
                onPlay = { _, _ -> }, onSetup = onToolsClick)
            2 -> {
                val connected by produceState<Boolean?>(null, settingsState.personalDataUserId, settingsState.jellyfinConnected) { value = jellyfinBrowserViewModel.isConnected() }
                when (connected) {
                    null -> CircularProgressIndicator(Modifier.padding(20.dp))
                    true -> JellyfinTab(jellyfinBrowserViewModel, settingsState.personalDataUserId.orEmpty(), onJellyfinItemPlay, onMediaClick)
                    false -> Column(Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.jellyfin_not_connected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onJellyfinSetup) { Text(localizedUiText("Open connection settings")) }
                    }
                }
            }
            3 -> if (downloadsContent != null) downloadsContent { primarySection = 0 } else com.torve.android.ui.download.DownloadScreen(onBack = { primarySection = 0 })
            else -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Watchlist", "In Progress", "History").forEachIndexed { index, label ->
                        androidx.compose.material3.FilterChip(watchlistSection == index, { watchlistSection = index }, label = { Text(localizedUiText(label)) })
                    }
                }
                when (watchlistSection) {
                    1 -> InProgressTab(progress, true, onContinueWatchingClick)
                    2 -> com.torve.android.ui.community.LibraryCollectionScreen("history", onBack = { watchlistSection = 0 },
                        onOpen = { row, _ -> onOpenEpisode(MediaItem(id = row.mediaId, type = if (row.mediaType == "tv") MediaType.SERIES else MediaType.MOVIE, title = row.title, posterUrl = row.artwork), row.season.takeIf { it > 0 }, row.episode.takeIf { it > 0 }) },
                        onPlay = { _, _ -> }, onSetup = onToolsClick)
                    else -> WatchlistTab(enrichedWatchlist, watchlistState.isLoading, onMediaClick,
                        stringResource(R.string.watchlist_empty), stringResource(R.string.watchlist_empty_desc))
                }
            }
        }
    }
    }
}

@Composable
fun PhoneJellyfinLibraryScreen(onBack: () -> Unit, onSetup: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onPlay: (PlaybackRoute.JellyfinStream, MediaItem, Int?, Int?) -> Unit,
    viewModel: JellyfinBrowserViewModel = koinInject(), settings: SettingsViewModel = koinInject()) {
    val state by settings.state.collectAsState()
    val connected by produceState<Boolean?>(null, state.personalDataUserId, state.jellyfinConnected) { value = viewModel.isConnected() }
    com.torve.android.ui.product.ProductPage(localizedUiText("Jellyfin"), onBack = onBack) {
        when (connected) {
            null -> CircularProgressIndicator(Modifier.padding(20.dp))
            true -> JellyfinTab(viewModel, state.personalDataUserId.orEmpty(), onPlay, onMediaClick)
            false -> Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.jellyfin_not_connected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onSetup) { Text(localizedUiText("Open connection settings")) }
            }
        }
    }
}

@Composable
private fun RequestsTab(
    viewModel: PermanentLibraryViewModel,
    onMediaClick: (MediaItem) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    DisposableEffect(viewModel, lifecycleState) {
        if (lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) viewModel.startPolling()
        else viewModel.stopPolling()
        onDispose { viewModel.stopPolling() }
    }

    LaunchedEffect(state.newlyAvailable) {
        state.newlyAvailable.firstOrNull()?.let { item ->
            snackbarHostState.showSnackbar("${item.title} is now available in your library")
            viewModel.acknowledgeAvailable(item.stableId)
        }
    }

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearActionMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading && state.items.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Amber) }

            state.activeItems.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = localizedUiText(if (state.isConfigured) {
                        "Nothing is being prepared. Use Add to library on any movie or show."
                    } else {
                        "Connect Seerr in Settings to save movies and shows permanently."
                    }),
                    color = Torve.colors.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.activeItems, key = AcquisitionLifecycleItem::stableId) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(enabled = item.tmdbId != null) {
                            item.tmdbId?.let {
                                onMediaClick(
                                    MediaItem(
                                        id = item.stableId,
                                        tmdbId = it,
                                        type = item.mediaType,
                                        title = item.title,
                                        year = item.year,
                                        overview = item.overview,
                                        posterUrl = item.posterUrl,
                                        backdropUrl = item.backdropUrl,
                                        rating = item.rating,
                                        status = item.statusLabel,
                                    ),
                                )
                            }
                        }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .width(72.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.background),
                        contentScale = ContentScale.Crop,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        item.year?.let { Text(it.toString(), color = Torve.colors.textTertiary) }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.statusLabel,
                            color = if (item.stage == AcquisitionStage.NEEDS_ATTENTION) Ruby else Amber,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        item.progressPercent?.let { value ->
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { (value / 100.0).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = Amber,
                                trackColor = Obsidian,
                            )
                        }
                        item.timeLeft?.takeIf { it.isNotBlank() }?.let {
                            Text(localizedUiText("$it remaining"), color = Torve.colors.textTertiary, style = MaterialTheme.typography.labelSmall)
                        }
                        if (item.canRetry || item.canCancel) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                if (item.canRetry) {
                                    TextButton(
                                        enabled = state.actionInProgressStableId == null,
                                        onClick = { viewModel.retryAcquisition(item.stableId) },
                                    ) {
                                        Text(localizedUiText("Retry"), color = Amber)
                                    }
                                }
                                if (item.canCancel) {
                                    TextButton(
                                        enabled = state.actionInProgressStableId == null,
                                        onClick = { viewModel.cancelAcquisition(item.stableId) },
                                    ) {
                                        Text(localizedUiText("Cancel download"), color = Ruby)
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
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
    var sortMode by rememberSaveable { mutableStateOf(com.torve.presentation.seeall.SeeAllSortMode.IMDB_DESC) }
    var yearFrom by rememberSaveable { mutableStateOf<Int?>(null) }
    var yearTo by rememberSaveable { mutableStateOf<Int?>(null) }
    var genreIds by rememberSaveable { mutableStateOf<Set<Int>>(emptySet()) }

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

    val density = com.torve.android.ui.community.rememberPageGrid("watchlist")
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(sortMode, yearFrom, yearTo, genreIds, query) { gridState.scrollToItem(0) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(localizedUiText("Search your watchlist")) }, modifier = Modifier.weight(1f))
            com.torve.android.ui.community.PageGridMenu(density)
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(density.adaptiveColumns()), state = gridState,
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
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
                compact = true,
            )

            }
            val groups = listOf("Movies" to movies, "TV Shows" to shows)
            groups.forEach { (title, source) ->
                val entries = source.filter { query.isBlank() || it.title.contains(query.trim(), true) }
                if (entries.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Text(title, style = MaterialTheme.typography.titleMedium) }
                    items(entries.size, key = { "${entries[it].type}:${entries[it].id}" }) { index ->
                        PosterCard(entries[index], { onMediaClick(entries[index]) }, fillCell = true)
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
            .background(MaterialTheme.colorScheme.surface)
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
                color = MaterialTheme.colorScheme.onSurface,
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
                localizedUiText("${remaining}min remaining"),
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
            .background(MaterialTheme.colorScheme.surface)
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
                color = MaterialTheme.colorScheme.onSurface,
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

private enum class JellyfinMobileScope(val label: String) {
    ALL("Overview"),
    MOVIES("Movies"),
    SERIES("Series"),
    RECENT("Recent"),
    TOP_RATED("Top rated"),
    DECADES("Decades"),
}

private fun buildMobileJellyfinRows(
    allItems: List<JellyfinBrowseItem>,
    scope: JellyfinMobileScope,
): List<Pair<String, List<JellyfinBrowseItem>>> {
    val scoped = when (scope) {
        JellyfinMobileScope.MOVIES -> allItems.filter { it.type.equals("Movie", true) }
        JellyfinMobileScope.SERIES -> allItems.filter { it.type.equals("Series", true) }
        else -> allItems
    }
    val alphabetical = scoped.sortedBy { it.name.lowercase() }
    val recent = scoped.sortedByDescending { it.dateCreated.orEmpty() }
    val topRated = scoped.filter { (it.communityRating ?: 0.0) > 0.0 }
        .sortedByDescending { it.communityRating }

    return buildList {
        when (scope) {
            JellyfinMobileScope.ALL -> {
                scoped.filter {
                    val user = it.userData
                    user != null && user.playbackPositionTicks > 0L && !user.played
                }.takeIf { it.isNotEmpty() }?.let { add("Continue watching" to it) }
                add("Recently added" to recent.take(30))
                alphabetical.filter { it.type.equals("Movie", true) }
                    .takeIf { it.isNotEmpty() }?.let { add("Movies" to it) }
                alphabetical.filter { it.type.equals("Series", true) }
                    .takeIf { it.isNotEmpty() }?.let { add("Series" to it) }
            }
            JellyfinMobileScope.MOVIES -> {
                add("Recently added movies" to recent.take(30))
                add("All movies A–Z" to alphabetical)
            }
            JellyfinMobileScope.SERIES -> {
                add("Recently added series" to recent.take(30))
                add("All series A–Z" to alphabetical)
            }
            JellyfinMobileScope.RECENT -> add("Recently added" to recent)
            JellyfinMobileScope.TOP_RATED -> add("Top rated" to topRated)
            JellyfinMobileScope.DECADES -> scoped
                .filter { it.productionYear != null }
                .groupBy { (it.productionYear!! / 10) * 10 }
                .toSortedMap(compareByDescending { it })
                .forEach { (decade, items) -> add("${decade}s" to items) }
        }

        if (scope == JellyfinMobileScope.ALL ||
            scope == JellyfinMobileScope.MOVIES ||
            scope == JellyfinMobileScope.SERIES
        ) {
            scoped.flatMap { item -> item.genres.distinct().map { it to item } }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .entries
                .sortedByDescending { it.value.size }
                .take(8)
                .forEach { add(it.key to it.value.distinctBy(JellyfinBrowseItem::id)) }
        }
    }.filter { it.second.isNotEmpty() }
}

@Composable
private fun JellyfinTab(
    viewModel: JellyfinBrowserViewModel,
    ownerId: String,
    onItemPlay: (route: PlaybackRoute.JellyfinStream, item: MediaItem, season: Int?, episode: Int?) -> Unit,
    onMediaClick: (MediaItem) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var selectedScope by rememberSaveable { mutableStateOf(JellyfinMobileScope.ALL) }

    LaunchedEffect(ownerId) { viewModel.loadLibraryForOwner(ownerId) }

    if (state.isLoading && state.sections.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.size(40.dp))
        }
        return
    }

    state.error?.let { error ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(localizedUiText(error), color = Torve.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
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

    val allItems = remember(state.sections, state.sectionItems) {
        state.sections
            .flatMap { state.sectionItems[it.id].orEmpty() }
            .filterNot(JellyfinBrowseItem::isEpisode)
            .distinctBy(JellyfinBrowseItem::id)
    }
    val managedRows = remember(allItems, selectedScope) {
        buildMobileJellyfinRows(allItems, selectedScope)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "jellyfin_filters") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(JellyfinMobileScope.entries, key = { it.name }) { scope ->
                    val selected = selectedScope == scope
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) Amber.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .clickable { selectedScope = scope }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text(
                            text = localizedUiText(scope.label),
                            color = if (selected) Amber else Torve.colors.textSecondary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }

        for ((index, row) in managedRows.withIndex()) {
            val (title, items) = row
            item(key = "header_${index}_$title") {
                Text(
                    "$title (${items.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Amber,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item(key = "row_${index}_$title") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.id }) { jfItem ->
                        JellyfinItemCard(
                            item = jfItem,
                            viewModel = viewModel,
                            onPlay = onItemPlay,
                            onMediaClick = onMediaClick,
                        )
                    }
                }
            }
            item(key = "spacer_${index}_$title") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun JellyfinItemCard(
    item: JellyfinBrowseItem,
    viewModel: JellyfinBrowserViewModel,
    onPlay: (route: PlaybackRoute.JellyfinStream, item: MediaItem, season: Int?, episode: Int?) -> Unit,
    onMediaClick: (MediaItem) -> Unit,
) {
    val imageUrl by produceState<String?>(null, item.id) {
        value = item.fallbackPosterUrl ?: viewModel.buildImageUrl(
            if (item.isEpisode) item.seriesId ?: item.id else item.id,
        )
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var episodes by remember(item.id) { mutableStateOf<List<JellyfinBrowseItem>?>(null) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    fun playLibraryItem(selected: JellyfinBrowseItem) {
        scope.launch {
            try {
                val route = viewModel.buildAuthenticatedStream(selected.id) ?: error("Library connection unavailable")
                onPlay(route, MediaItem(
                    id = item.providerIds?.tmdb?.toIntOrNull()?.takeIf { it > 0 }?.toString()
                        ?: "jf_${if (selected.isEpisode) item.id else selected.id}", title = item.displayTitle,
                    tmdbId = item.providerIds?.tmdb?.toIntOrNull(), imdbId = item.providerIds?.imdb,
                    type = if (selected.isEpisode) MediaType.SERIES else MediaType.MOVIE,
                    posterUrl = imageUrl, backdropUrl = item.fallbackBackdropUrl ?: viewModel.buildBackdropImageUrl(item.id),
                ), selected.parentIndexNumber, selected.indexNumber)
                episodes = null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Unable to open library copy" }
        }
    }
    episodes?.let { available ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { episodes = null },
            title = { Text(item.displayTitle) },
            text = { androidx.compose.foundation.lazy.LazyColumn {
                items(available) { episode ->
                    TextButton(onClick = { playLibraryItem(episode) }) {
                        Text(episode.displaySubtitle ?: episode.name)
                    }
                }
            } }, confirmButton = { TextButton(onClick = { episodes = null }) { Text(localizedUiText("Close")) } })
    }
    error?.let { message ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { error = null },
            text = { Text(localizedUiText(message)) }, confirmButton = { TextButton(onClick = { error = null }) { Text(localizedUiText("Close")) } })
    }

    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable {
                scope.launch {
                    if (item.type.equals("Series", ignoreCase = true)) {
                        try {
                            val detail = viewModel.mediaDetails(item)
                            if (detail != null) onMediaClick(detail)
                            else {
                                val found = viewModel.seriesEpisodes(item.id)
                                if (found.isEmpty()) error = "No playable episodes in this library copy"
                                else episodes = found
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Unable to open library copy" }
                    } else playLibraryItem(item)
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
                .background(MaterialTheme.colorScheme.surface),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.displayTitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.displaySubtitle?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
                    text = localizedUiText("Tap the download icon on an episode to save it for offline access."),
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
                    .background(MaterialTheme.colorScheme.surface)
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
                        color = MaterialTheme.colorScheme.onSurface,
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
                        text = localizedUiText(statusText),
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
