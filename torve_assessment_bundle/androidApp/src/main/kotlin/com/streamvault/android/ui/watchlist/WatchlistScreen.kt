package com.streamvault.android.ui.watchlist

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.android.R
import com.streamvault.android.ui.components.CardSize
import com.streamvault.android.ui.components.LocalCardStyle
import com.streamvault.android.ui.components.PosterCard
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.resolveCardStyle
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.WatchHistoryEntry
import com.streamvault.domain.model.WatchProgress
import com.streamvault.domain.repository.WatchHistoryRepository
import com.streamvault.domain.repository.WatchProgressRepository
import com.streamvault.data.mdblist.RatingsEnricher
import com.streamvault.presentation.settings.SettingsViewModel
import com.streamvault.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun WatchlistScreen(
    onMediaClick: (MediaItem) -> Unit,
    onContinueWatchingClick: (WatchProgress) -> Unit = {},
    onHistoryItemClick: (WatchHistoryEntry) -> Unit = {},
    watchlistViewModel: WatchlistViewModel = koinInject(),
    watchProgressRepo: WatchProgressRepository = koinInject(),
    watchHistoryRepo: WatchHistoryRepository = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    ratingsEnricher: RatingsEnricher = koinInject(),
) {
    val watchlistState by watchlistViewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.watchlist_title),
        stringResource(R.string.watchlist_in_progress),
        stringResource(R.string.watchlist_history),
    )

    LaunchedEffect(Unit) {
        watchlistViewModel.loadWatchlist()
    }

    // Load in-progress and history data
    var inProgress by remember { mutableIntStateOf(0) }
    var inProgressItems = remember { mutableListOf<WatchProgress>() }
    var historyItems = remember { mutableListOf<WatchHistoryEntry>() }
    var progressLoaded by remember { androidx.compose.runtime.mutableStateOf(false) }
    var historyLoaded by remember { androidx.compose.runtime.mutableStateOf(false) }

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

    var enrichedWatchlist by remember { androidx.compose.runtime.mutableStateOf<List<MediaItem>>(emptyList()) }
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
            text = stringResource(R.string.watchlist_title),
            style = MaterialTheme.typography.headlineLarge,
            color = Snow,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )

        // Sub-tabs
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            tabs.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Amber.copy(alpha = 0.2f),
                        activeContentColor = Amber,
                        inactiveContainerColor = Gunmetal,
                        inactiveContentColor = StreamVault.colors.textSecondary,
                        activeBorderColor = Amber.copy(alpha = 0.4f),
                        inactiveBorderColor = Gunmetal,
                    ),
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
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
            1 -> InProgressTab(
                items = inProgressItems,
                isLoaded = progressLoaded,
                onItemClick = onContinueWatchingClick,
            )
            2 -> HistoryTab(
                items = historyItems,
                isLoaded = historyLoaded,
                onItemClick = onHistoryItemClick,
            )
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
                    color = StreamVault.colors.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.watchlist_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamVault.colors.textTertiary,
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
                    color = StreamVault.colors.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.watchlist_start_watching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamVault.colors.textTertiary,
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
                color = StreamVault.colors.textTertiary,
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
                    color = StreamVault.colors.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.watchlist_history_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamVault.colors.textTertiary,
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
                    color = StreamVault.colors.textSecondary,
                    maxLines = 1,
                )
            }
            val durationMin = (entry.durationWatchedMs / 60000).toInt()
            if (durationMin > 0) {
                Text(
                    stringResource(R.string.watchlist_watched_min, durationMin),
                    style = MaterialTheme.typography.labelSmall,
                    color = StreamVault.colors.textTertiary,
                )
            }
        }
    }
}
