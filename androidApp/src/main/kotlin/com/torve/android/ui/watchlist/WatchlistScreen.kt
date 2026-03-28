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
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
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
    watchProgressRepo: WatchProgressRepository = koinInject(),
    watchHistoryRepo: WatchHistoryRepository = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    ratingsEnricher: RatingsEnricher = koinInject(),
    jellyfinBrowserViewModel: JellyfinBrowserViewModel = koinInject(),
) {
    val watchlistState by watchlistViewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val isJellyfinConnected by produceState(false) {
        value = jellyfinBrowserViewModel.isConnected()
    }

    val tabs = buildList {
        add(stringResource(R.string.watchlist_title))
        add(stringResource(R.string.download_title))
        if (isJellyfinConnected) add(stringResource(R.string.watchlist_jellyfin))
    }

    // Guard against tab index out of bounds when Jellyfin disconnects
    if (selectedTab >= tabs.size) selectedTab = 0

    LaunchedEffect(Unit) {
        watchlistViewModel.loadWatchlist()
    }

    // Load in-progress and history data
    var inProgress by remember { mutableIntStateOf(0) }
    var inProgressItems = remember { mutableListOf<WatchProgress>() }
    var historyItems = remember { mutableListOf<WatchHistoryEntry>() }
    var progressLoaded by remember { mutableStateOf(false) }
    var historyLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && !progressLoaded) {
            withContext(Dispatchers.Default) {
                watchProgressRepo.syncFromTrakt()
                val items = watchProgressRepo.getInProgress(50)
                inProgressItems.clear()
                inProgressItems.addAll(items)
                progressLoaded = true
            }
        }
        if (selectedTab == 2 && !historyLoaded) {
            withContext(Dispatchers.Default) {
                watchHistoryRepo.syncFromTrakt()
                val items = watchHistoryRepo.getRecent(100)
                historyItems.clear()
                historyItems.addAll(items)
                historyLoaded = true
            }
        }
    }

    var enrichedWatchlist by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(watchlistState.items, settingsState.mdblistApiKey) {
        val baseItems = watchlistState.items.map { wlItem ->
            MediaItem(
                id = wlItem.mediaId,
                tmdbId = wlItem.tmdbId.toInt(),
                imdbId = wlItem.imdbId,
                title = wlItem.title,
                posterUrl = wlItem.posterUrl,
                backdropUrl = wlItem.backdropUrl,
                rating = wlItem.rating,
                year = wlItem.year,
                type = wlItem.mediaType,
            )
        }
        val apiKey = settingsState.mdblistApiKey
        enrichedWatchlist = if (apiKey.isNotBlank()) {
            withContext(Dispatchers.Default) { ratingsEnricher.enrichList(baseItems, apiKey) }
        } else baseItems
    }

    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )
    CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
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
            )
            1 -> {
                LaunchedEffect(Unit) {
                    onDownloadsClick()
                    selectedTab = 0
                }
            }
            2 -> if (isJellyfinConnected) {
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
) {
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.size(40.dp))
        }
        return
    }

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.watchlist_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = Torve.colors.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.watchlist_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Torve.colors.textTertiary,
                )
            }
        }
        return
    }

    val movies = items.filter { it.type == MediaType.MOVIE }
    val shows = items.filter { it.type == MediaType.SERIES }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
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
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(movies, key = { it.id }) { wlItem ->
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
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shows, key = { it.id }) { wlItem ->
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
        // Poster
        AsyncImage(
            model = progress.posterUrl,
            contentDescription = progress.title,
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
        AsyncImage(
            model = entry.posterUrl,
            contentDescription = entry.title,
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
