package com.torve.desktop.ui.v2.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.download.DesktopDownloadManagerState
import com.torve.desktop.download.DesktopLocalMediaGroup
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorveListRow
import com.torve.desktop.ui.components.TorvePlaceholderState
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.components.V2Shelf
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import com.torve.desktop.ui.v2.seeall.SeeAllRequest
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.ChannelPlaylist
import com.torve.domain.model.DownloadGroup
import com.torve.domain.model.DownloadGroupType
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.PlaylistType
import com.torve.domain.repository.ChannelRepository
import com.torve.presentation.download.DownloadCatalogueUiState
import com.torve.presentation.download.DownloadUiState
import com.torve.presentation.home.HomeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

private enum class LibraryViewTab(
    val label: String,
) {
    OVERVIEW("Overview"),
    FAVORITES("Favorites"),
    VOD("IPTV VOD"),
    STORED("Stored Media"),
    LOCAL("Local Files"),
}

private enum class LibraryVodKind(
    val label: String,
) {
    MOVIES("Movies"),
    SHOWS("Shows"),
}

@Composable
fun V2LibraryPage(
    homeState: HomeUiState,
    downloadState: DownloadUiState,
    downloadCatalogueState: DownloadCatalogueUiState,
    desktopDownloadState: DesktopDownloadManagerState,
    favoriteItems: List<MediaItem> = emptyList(),
    scrollState: ScrollState,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    onDeleteDownloadGroup: (DownloadGroup) -> Unit,
    onDeleteDownloadSeason: (String, Int) -> Unit,
    onDeleteDownloadEpisode: (String) -> Unit,
    onRefreshStoredMedia: () -> Unit,
    onSeeAll: (SeeAllRequest) -> Unit = {},
    onPlayFile: (filePath: String, title: String, posterUrl: String?) -> Unit = { _, _, _ -> },
    localLibrary: com.torve.desktop.library.LocalLibraryRepository? = null,
    channelRepository: ChannelRepository? = null,
    onPlayVodChannel: (Channel) -> Unit = {},
) {
    val colors = TorveDesktopThemeTokens.colors
    var selectedTab by remember { mutableStateOf(LibraryViewTab.OVERVIEW) }
    val heroBackdropUrl = remember(homeState) {
        homeState.continueWatching.firstOrNull()?.backdropUrl
            ?: homeState.watchlistItems.firstOrNull()?.backdropUrl
            ?: favoriteItems.firstOrNull()?.backdropUrl
            ?: homeState.recentlyWatched.firstOrNull()?.backdropUrl
    }
    val heroBackdrop = rememberCachedBitmap(heroBackdropUrl)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vpH = maxHeight

        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(vpH * 0.6f).background(colors.shellBackground)) {
                heroBackdrop?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Box(
                Modifier.fillMaxWidth().height(vpH * 0.6f).background(
                    Brush.verticalGradient(
                        0.0f to colors.shellBackground.copy(alpha = 0.3f),
                        0.4f to colors.shellBackground.copy(alpha = 0.65f),
                        1.0f to colors.shellBackground,
                    ),
                ),
            )

            Column(
                Modifier.fillMaxSize().verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(132.dp)) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            ds("Library"),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LibraryViewTab.entries.forEach { tab ->
                                TorveFilterChip(
                                    text = ds(tab.label),
                                    selected = tab == selectedTab,
                                    onClick = { selectedTab = tab },
                                )
                            }
                        }
                    }
                }

                when (selectedTab) {
                    LibraryViewTab.OVERVIEW -> LibraryOverview(
                        homeState = homeState,
                        favoriteItems = favoriteItems,
                        onOpenDetail = onOpenDetail,
                        onSeeAll = onSeeAll,
                    )
                    LibraryViewTab.FAVORITES -> LibraryFavoritesView(
                        favoriteItems = favoriteItems,
                        onOpenDetail = onOpenDetail,
                    )
                    LibraryViewTab.VOD -> DesktopVodLibraryView(
                        channelRepository = channelRepository,
                        onPlayVodChannel = onPlayVodChannel,
                    )
                    LibraryViewTab.STORED -> StoredMediaView(
                        downloadState = downloadState,
                        downloadCatalogueState = downloadCatalogueState,
                        desktopDownloadState = desktopDownloadState,
                        onDeleteDownloadGroup = onDeleteDownloadGroup,
                        onDeleteDownloadSeason = onDeleteDownloadSeason,
                        onDeleteDownloadEpisode = onDeleteDownloadEpisode,
                        onRefreshStoredMedia = onRefreshStoredMedia,
                        onPlayFile = onPlayFile,
                    )
                    LibraryViewTab.LOCAL -> LocalLibraryView(
                        repository = localLibrary,
                        onPlayFile = onPlayFile,
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun LibraryOverview(
    homeState: HomeUiState,
    favoriteItems: List<MediaItem>,
    onOpenDetail: (MediaItem) -> Unit,
    onSeeAll: (SeeAllRequest) -> Unit,
) {
    if (favoriteItems.isNotEmpty()) {
        V2Shelf(
            "Favorites",
            modifier = Modifier.padding(start = 72.dp),
        ) {
            favoriteItems.take(20).forEach { item ->
                V2PosterCard(
                    title = item.title,
                    imageUrl = item.posterUrl,
                    modifier = Modifier.width(160.dp),
                    year = item.year?.toString(),
                    rating = item.rating?.let { String.format("%.1f", it) },
                    backdropUrl = item.backdropUrl,
                    overview = item.overview,
                    onClick = { onOpenDetail(item) },
                )
            }
        }
    }

    if (homeState.continueWatching.isNotEmpty()) {
        V2Shelf(
            "Continue Watching",
            modifier = Modifier.padding(start = 72.dp),
            onSeeAll = { onSeeAll(SeeAllRequest("continue_watching", "Continue Watching")) },
        ) {
            homeState.continueWatching.take(15).forEach { wp ->
                V2PosterCard(
                    title = wp.showTitle ?: wp.title,
                    imageUrl = wp.posterUrl,
                    modifier = Modifier.width(160.dp),
                    progress = wp.progressPercent.takeIf { it > 0f },
                    onClick = {
                        onOpenDetail(
                            MediaItem(
                                id = wp.mediaId,
                                tmdbId = wp.mediaId.toIntOrNull(),
                                type = wp.mediaType,
                                title = wp.showTitle ?: wp.title,
                                posterUrl = wp.posterUrl,
                                backdropUrl = wp.backdropUrl,
                            ),
                        )
                    },
                )
            }
        }
    }

    if (homeState.watchlistItems.isNotEmpty()) {
        V2Shelf(
            "Watchlist",
            modifier = Modifier.padding(start = 72.dp),
            onSeeAll = { onSeeAll(SeeAllRequest("watchlist", "Watchlist")) },
        ) {
            homeState.watchlistItems.take(20).forEach { item ->
                V2PosterCard(
                    title = item.title,
                    imageUrl = item.posterUrl,
                    modifier = Modifier.width(160.dp),
                    year = item.year?.toString(),
                    rating = item.rating?.let { String.format("%.1f", it) },
                    backdropUrl = item.backdropUrl,
                    overview = item.overview,
                    onClick = { onOpenDetail(item) },
                )
            }
        }
    }

    if (homeState.recentlyWatched.isNotEmpty()) {
        V2Shelf(
            "History",
            modifier = Modifier.padding(start = 72.dp),
            onSeeAll = { onSeeAll(SeeAllRequest("recently_watched", "Recently Watched")) },
        ) {
            homeState.recentlyWatched.take(15).forEach { item ->
                V2PosterCard(
                    title = item.title,
                    imageUrl = item.posterUrl,
                    modifier = Modifier.width(160.dp),
                    year = item.year?.toString(),
                    rating = item.rating?.let { String.format("%.1f", it) },
                    backdropUrl = item.backdropUrl,
                    overview = item.overview,
                    onClick = { onOpenDetail(item) },
                )
            }
        }
    }

    if (homeState.continueWatching.isEmpty() &&
        homeState.watchlistItems.isEmpty() &&
        favoriteItems.isEmpty() &&
        homeState.recentlyWatched.isEmpty() &&
        !homeState.isLoading
    ) {
        Text(
            text = ds("Your library is empty. Start watching to build your collection."),
            modifier = Modifier.padding(start = 72.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = TorveDesktopThemeTokens.colors.textSecondary,
        )
    }
}

@Composable
private fun LibraryFavoritesView(
    favoriteItems: List<MediaItem>,
    onOpenDetail: (MediaItem) -> Unit,
) {
    val movies = remember(favoriteItems) { favoriteItems.filter { it.type == MediaType.MOVIE } }
    val shows = remember(favoriteItems) { favoriteItems.filter { it.type == MediaType.SERIES } }

    Column(
        modifier = Modifier.padding(start = 72.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (favoriteItems.isEmpty()) {
            TorvePlaceholderState(
                title = ds("No favorites yet"),
                description = ds("Movies and shows you favorite on any device appear here."),
                emoji = "FAV",
            )
            return@Column
        }

        if (movies.isNotEmpty()) {
            V2Shelf(ds("Movies")) {
                movies.forEach { item ->
                    V2PosterCard(
                        title = item.title,
                        imageUrl = item.posterUrl,
                        modifier = Modifier.width(160.dp),
                        year = item.year?.toString(),
                        rating = item.rating?.let { String.format("%.1f", it) },
                        backdropUrl = item.backdropUrl,
                        overview = item.overview,
                        onClick = { onOpenDetail(item) },
                    )
                }
            }
        }

        if (shows.isNotEmpty()) {
            V2Shelf(ds("TV Shows")) {
                shows.forEach { item ->
                    V2PosterCard(
                        title = item.title,
                        imageUrl = item.posterUrl,
                        modifier = Modifier.width(160.dp),
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

@Composable
private fun DesktopVodLibraryView(
    channelRepository: ChannelRepository?,
    onPlayVodChannel: (Channel) -> Unit,
) {
    var playlists by remember { mutableStateOf<List<ChannelPlaylist>>(emptyList()) }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var vodKind by remember { mutableStateOf(LibraryVodKind.MOVIES) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var movies by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var shows by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedSeries by remember { mutableStateOf<Channel?>(null) }
    var seriesEpisodes by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = TorveDesktopThemeTokens.colors

    LaunchedEffect(channelRepository) {
        if (channelRepository == null) return@LaunchedEffect
        isLoading = true
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) {
                channelRepository.getPlaylists().filter { it.type == PlaylistType.XTREAM }
            }
        }.onSuccess { loaded ->
            playlists = loaded
            selectedPlaylistId = selectedPlaylistId?.takeIf { id -> loaded.any { it.id == id } }
                ?: loaded.firstOrNull()?.id
        }.onFailure { error ->
            loadError = error.message ?: "Could not load IPTV playlists."
        }
        isLoading = false
    }

    LaunchedEffect(channelRepository, selectedPlaylistId) {
        val repo = channelRepository ?: return@LaunchedEffect
        val playlistId = selectedPlaylistId ?: return@LaunchedEffect
        isLoading = true
        loadError = null
        selectedSeries = null
        seriesEpisodes = emptyList()
        runCatching {
            withContext(Dispatchers.IO) {
                val movieChannels = repo.getChannelsByContentType(playlistId, ChannelContentType.VOD_MOVIE)
                    .map { it.channel }
                val seriesChannels = repo.getChannelsByContentType(playlistId, ChannelContentType.VOD_SERIES)
                    .map { it.channel }
                movieChannels to seriesChannels
            }
        }.onSuccess { (movieChannels, seriesChannels) ->
            movies = movieChannels
            shows = seriesChannels
        }.onFailure { error ->
            movies = emptyList()
            shows = emptyList()
            loadError = error.message ?: "Could not load IPTV VOD."
        }
        isLoading = false
    }

    val activeItems = if (vodKind == LibraryVodKind.MOVIES) movies else shows
    val categories = remember(activeItems) {
        listOf("All") + activeItems
            .map { it.vodCategoryLabel() }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }
    LaunchedEffect(vodKind, categories) {
        if (selectedCategory !in categories) {
            selectedCategory = "All"
        }
        selectedSeries = null
        seriesEpisodes = emptyList()
    }

    val filteredItems = remember(activeItems, selectedCategory, searchQuery) {
        val query = searchQuery.trim()
        activeItems
            .asSequence()
            .filter { selectedCategory == "All" || it.vodCategoryLabel() == selectedCategory }
            .filter { channel ->
                query.isBlank() ||
                    channel.name.contains(query, ignoreCase = true) ||
                    channel.tvgName.orEmpty().contains(query, ignoreCase = true) ||
                    channel.groupTitle.orEmpty().contains(query, ignoreCase = true) ||
                    channel.kodiProps["vod_genre"].orEmpty().contains(query, ignoreCase = true)
            }
            .toList()
    }
    fun openSeriesEpisodes(channel: Channel) {
        val repo = channelRepository ?: return
        selectedSeries = channel
        seriesEpisodes = emptyList()
        isLoadingEpisodes = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repo.getVodSeriesEpisodes(channel) }
            }.onSuccess { episodes ->
                seriesEpisodes = episodes
            }.onFailure {
                seriesEpisodes = emptyList()
            }
            isLoadingEpisodes = false
        }
    }

    Column(
        modifier = Modifier.padding(start = 72.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TorveSectionCard(
            title = ds("IPTV VOD"),
            supportingText = ds("Browse cached Xtream movies and series from your IPTV playlists."),
        ) {
            if (channelRepository == null) {
                TorvePlaceholderState(
                    title = ds("IPTV is not available"),
                    description = ds("The desktop channel repository is not connected."),
                    emoji = "TV",
                )
                return@TorveSectionCard
            }

            if (playlists.isEmpty() && !isLoading) {
                TorvePlaceholderState(
                    title = ds("No Xtream playlist found"),
                    description = ds("Add an Xtream provider in Settings to browse VOD here."),
                    emoji = "TV",
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 20.dp),
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        TorveFilterChip(
                            text = playlist.name,
                            selected = playlist.id == selectedPlaylistId,
                            onClick = { selectedPlaylistId = playlist.id },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryVodKind.entries.forEach { kind ->
                        val kindLabel = ds(kind.label)
                        val itemCount = if (kind == LibraryVodKind.MOVIES) movies.size else shows.size
                        TorveFilterChip(
                            text = "$kindLabel ($itemCount)",
                            selected = vodKind == kind,
                            onClick = { vodKind = kind },
                        )
                    }
                }
                TorveTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = if (vodKind == LibraryVodKind.MOVIES) ds("Search movies") else ds("Search shows"),
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = ds("Title, category, genre"),
                )
                if (categories.size > 1) {
                    DesktopVodCategoryFilterRow(
                        categories = categories,
                        selectedCategory = selectedCategory,
                        onSelectCategory = { selectedCategory = it },
                    )
                }

                loadError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }
                if (isLoading) {
                    Text(
                        text = ds("Loading cached IPTV VOD..."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        selectedSeries?.let { series ->
            TorveSectionCard(
                title = series.name,
                supportingText = series.kodiProps["vod_plot"].orEmpty().ifBlank {
                    ds("Choose an episode to play.")
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorveBadge(text = ds("Series"), tone = TorveBadgeTone.Accent)
                    series.vodYear()?.let { TorveBadge(text = it.toString(), tone = TorveBadgeTone.Neutral) }
                    series.vodRatingText()?.let { TorveBadge(text = it, tone = TorveBadgeTone.Warning) }
                    TorveGhostButton(text = ds("Close episodes"), onClick = {
                        selectedSeries = null
                        seriesEpisodes = emptyList()
                    })
                }
                if (isLoadingEpisodes) {
                    Text(ds("Loading episodes..."), color = colors.textSecondary)
                } else if (seriesEpisodes.isEmpty()) {
                    TorvePlaceholderState(
                        title = ds("No episodes found"),
                        description = ds("This provider did not return playable episodes for the selected series."),
                        emoji = "TV",
                    )
                } else {
                    seriesEpisodes
                        .groupBy { it.kodiProps["vod_season_number"]?.toIntOrNull() ?: 1 }
                        .toSortedMap()
                        .forEach { (season, episodes) ->
                            Text(
                                text = "${ds("Season")} $season",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            episodes.forEach { episode ->
                                TorveListRow(
                                    title = episode.tvgName ?: episode.name,
                                    subtitle = episode.kodiProps["vod_episode_plot"].orEmpty()
                                        .ifBlank { episode.url },
                                    onClick = { onPlayVodChannel(episode) },
                                )
                            }
                        }
                }
            }
        }

        if (!isLoading && activeItems.isEmpty() && playlists.isNotEmpty()) {
            TorvePlaceholderState(
                title = if (vodKind == LibraryVodKind.MOVIES) {
                    ds("No movies in the cached VOD catalog")
                } else {
                    ds("No shows in the cached VOD catalog")
                },
                description = if (vodKind == LibraryVodKind.MOVIES) {
                    ds("Refresh the Xtream playlist if this provider should include movies.")
                } else {
                    ds("Refresh the Xtream playlist if this provider should include shows.")
                },
                emoji = "TV",
            )
        } else if (!isLoading && filteredItems.isEmpty() && activeItems.isNotEmpty()) {
            TorvePlaceholderState(
                title = ds("No VOD results"),
                description = ds("Try another category or search term."),
                emoji = "Search",
            )
        } else {
            DesktopVodShelf(
                title = when {
                    searchQuery.isNotBlank() -> ds("Search results")
                    selectedCategory == "All" -> if (vodKind == LibraryVodKind.MOVIES) {
                        ds("All Movies")
                    } else {
                        ds("All Shows")
                    }
                    else -> selectedCategory
                },
                channels = filteredItems,
                vodKind = vodKind,
                onMovieClick = onPlayVodChannel,
                onSeriesClick = { channel -> openSeriesEpisodes(channel) },
            )

            if (searchQuery.isBlank() && selectedCategory == "All") {
                categories.drop(1).take(6).forEach { category ->
                    val categoryItems = activeItems.filter { it.vodCategoryLabel() == category }
                    if (categoryItems.isNotEmpty()) {
                        DesktopVodShelf(
                            title = category,
                            channels = categoryItems,
                            vodKind = vodKind,
                            onMovieClick = onPlayVodChannel,
                            onSeriesClick = { channel -> openSeriesEpisodes(channel) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopVodCategoryFilterRow(
    categories: List<String>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .desktopVodHorizontalDrag(scrollState)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            categories.forEach { category ->
                TorveFilterChip(
                    text = category,
                    selected = category == selectedCategory,
                    onClick = { onSelectCategory(category) },
                )
            }
            Spacer(Modifier.width(20.dp))
        }
        if (scrollState.maxValue > 0) {
            HorizontalScrollbar(
                modifier = Modifier.fillMaxWidth().height(8.dp),
                adapter = rememberScrollbarAdapter(scrollState),
            )
        }
    }
}

private fun Modifier.desktopVodHorizontalDrag(scrollState: ScrollState): Modifier = pointerInput(scrollState) {
    detectHorizontalDragGestures { _, dragAmount ->
        if (scrollState.maxValue <= 0) return@detectHorizontalDragGestures
        scrollState.dispatchRawDelta(-dragAmount)
    }
}

@Composable
private fun DesktopVodShelf(
    title: String,
    channels: List<Channel>,
    vodKind: LibraryVodKind,
    onMovieClick: (Channel) -> Unit,
    onSeriesClick: (Channel) -> Unit,
) {
    if (channels.isEmpty()) return
    val colors = TorveDesktopThemeTokens.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "$title (${channels.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 4.dp, end = 24.dp),
        ) {
            items(channels, key = { it.vodStableKey() }) { channel ->
                V2PosterCard(
                    title = channel.name,
                    imageUrl = channel.tvgLogo,
                    modifier = Modifier.width(150.dp),
                    year = channel.vodYear()?.toString(),
                    rating = channel.vodRatingText(),
                    ratings = channel.vodRatingValue()?.let { MediaRatings(tmdbScore = it.toFloat()) },
                    backdropUrl = channel.kodiProps["vod_backdrop"],
                    overview = channel.kodiProps["vod_plot"],
                    onClick = {
                        if (vodKind == LibraryVodKind.SHOWS || channel.contentType == ChannelContentType.VOD_SERIES) {
                            onSeriesClick(channel)
                        } else {
                            onMovieClick(channel)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StoredMediaView(
    downloadState: DownloadUiState,
    downloadCatalogueState: DownloadCatalogueUiState,
    desktopDownloadState: DesktopDownloadManagerState,
    onDeleteDownloadGroup: (DownloadGroup) -> Unit,
    onDeleteDownloadSeason: (String, Int) -> Unit,
    onDeleteDownloadEpisode: (String) -> Unit,
    onRefreshStoredMedia: () -> Unit,
    onPlayFile: (filePath: String, title: String, posterUrl: String?) -> Unit,
) {
    val groups = remember(downloadCatalogueState.catalogue.sections) {
        downloadCatalogueState.catalogue.sections.flatMap { it.items }
    }

    Column(
        modifier = Modifier.padding(start = 72.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TorveSectionCard(
            title = ds("Stored Media"),
            supportingText = ds("Torve downloads stay manageable here, and extra folders are shown with their source path."),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveBadge(
                    text = "${groups.size} Torve group(s)",
                    tone = TorveBadgeTone.Accent,
                )
                TorveBadge(
                    text = "${desktopDownloadState.scannedGroups.size} local group(s)",
                    tone = TorveBadgeTone.Neutral,
                )
                TorveGhostButton(
                    text = ds("Refresh"),
                    onClick = onRefreshStoredMedia,
                )
            }
            desktopDownloadState.lastEvent?.takeIf { it.isNotBlank() }?.let { event ->
                Text(
                    text = event,
                    style = MaterialTheme.typography.bodySmall,
                    color = TorveDesktopThemeTokens.colors.textSecondary,
                )
            }
        }

        if (downloadState.activeDownloads.isNotEmpty() || desktopDownloadState.isProcessing) {
            TorveSectionCard(
                title = ds("Active Downloads"),
                supportingText = ds("Downloads for shows are processed sequentially, one file at a time."),
            ) {
                if (desktopDownloadState.activeDownloadTitle != null) {
                    TorveListRow(
                        title = desktopDownloadState.activeDownloadTitle,
                        subtitle = "${(desktopDownloadState.activeProgress * 100f).toInt()}% complete",
                        trailing = {
                            TorveBadge(
                                text = ds("Running"),
                                tone = TorveBadgeTone.Accent,
                            )
                        },
                    )
                }
                downloadState.activeDownloads.forEach { download ->
                    TorveListRow(
                        title = download.title,
                        subtitle = buildString {
                            append(download.status.name.lowercase().replaceFirstChar(Char::uppercase))
                            download.fileSizeBytes?.takeIf { it > 0 }?.let {
                                append(" - ")
                                append(formatBytes(it))
                            }
                        },
                    )
                }
            }
        }

        TorveSectionCard(
            title = ds("Torve Downloads"),
            supportingText = ds("Only Torve-managed files can be deleted from here."),
        ) {
            if (groups.isEmpty()) {
                TorvePlaceholderState(
                    title = ds("No Torve downloads yet"),
                    description = ds("Download a movie from the source picker or queue episodes from a show detail page."),
                    emoji = "📥",
                )
            } else {
                groups.forEach { group ->
                    TorveListRow(
                        title = group.title,
                        subtitle = buildString {
                            append(if (group.type == DownloadGroupType.MOVIE) "Movie" else "TV Show")
                            append(" - ")
                            append(formatBytes(group.totalSizeBytes))
                            append(" - ")
                            append(group.itemCount)
                            append(if (group.itemCount == 1) " file" else " files")
                        },
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TorveBadge(
                                    text = ds("Downloaded by Torve"),
                                    tone = TorveBadgeTone.Success,
                                )
                                TorveGhostButton(
                                    text = ds("Delete"),
                                    onClick = { onDeleteDownloadGroup(group) },
                                )
                            }
                        },
                    )
                    if (group.type == DownloadGroupType.SHOW) {
                        group.seasons.orEmpty().forEach { season ->
                            TorveListRow(
                                title = "Season ${season.seasonNumber}",
                                subtitle = "${season.episodes.size} episode(s) - ${formatBytes(season.totalSizeBytes)}",
                                trailing = {
                                    TorveGhostButton(
                                        text = ds("Delete Season"),
                                        onClick = { onDeleteDownloadSeason(group.mediaId, season.seasonNumber) },
                                    )
                                },
                            )
                            season.episodes.forEach { episode ->
                                val episodePath = episode.filePath
                                TorveListRow(
                                    title = "S${(episode.seasonNumber ?: 0).toString().padStart(2, '0')}E${(episode.episodeNumber ?: 0).toString().padStart(2, '0')}",
                                    subtitle = buildString {
                                        append(episodePath ?: "Torve download")
                                        if (episode.fileSizeBytes > 0) {
                                            append(" - ")
                                            append(formatBytes(episode.fileSizeBytes))
                                        }
                                    },
                                    onClick = episodePath?.takeIf { it.isNotBlank() }?.let { p ->
                                        { onPlayFile(p, "${group.title} S${season.seasonNumber}E${episode.episodeNumber ?: 0}", group.posterUrl) }
                                    },
                                    trailing = {
                                        TorveGhostButton(
                                            text = ds("Delete Episode"),
                                            onClick = { onDeleteDownloadEpisode(episode.id) },
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        group.movie?.let { movie ->
                            val moviePath = movie.filePath
                            TorveListRow(
                                title = ds("Play"),
                                subtitle = buildString {
                                    append(moviePath ?: "Torve download")
                                    if (movie.fileSizeBytes > 0) {
                                        append(" - ")
                                        append(formatBytes(movie.fileSizeBytes))
                                    }
                                },
                                onClick = moviePath?.takeIf { it.isNotBlank() }?.let { p ->
                                    { onPlayFile(p, group.title, group.posterUrl) }
                                },
                            )
                        }
                    }
                }
            }
        }

        TorveSectionCard(
            title = ds("Extra Local Folders"),
            supportingText = ds("These folders are scanned read-only. Source path stays visible so Jellyfin-linked folders are distinguishable from Torve downloads."),
        ) {
            if (desktopDownloadState.scannedGroups.isEmpty()) {
                TorvePlaceholderState(
                    title = ds("No extra folders scanned"),
                    description = ds("Add scan folders in Settings to surface existing local media here."),
                    emoji = "📁",
                )
            } else {
                desktopDownloadState.scannedGroups.forEach { group ->
                    LocalMediaGroupCard(group = group, onPlayFile = onPlayFile)
                }
            }
        }
    }
}

@Composable
private fun LocalMediaGroupCard(
    group: DesktopLocalMediaGroup,
    onPlayFile: (String, String, String?) -> Unit,
) {
    TorveListRow(
        title = group.title,
        subtitle = "${group.entries.size} item(s) - ${formatBytes(group.totalSizeBytes)} - ${group.sourcePath}",
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveBadge(
                    text = if (group.isShow) "TV Group" else "Movie Group",
                    tone = TorveBadgeTone.Neutral,
                )
                TorveBadge(
                    text = group.sourceLabel,
                    tone = TorveBadgeTone.Warning,
                )
            }
        },
    )
    group.entries.forEach { entry ->
        TorveListRow(
            title = buildString {
                if (entry.seasonNumber != null && entry.episodeNumber != null) {
                    append("S${entry.seasonNumber.toString().padStart(2, '0')}E${entry.episodeNumber.toString().padStart(2, '0')} ")
                }
                append(entry.name)
            },
            subtitle = "${entry.path} - ${formatBytes(entry.sizeBytes)}",
            onClick = { onPlayFile(entry.path, entry.name, null) },
        )
    }
}

private fun Channel.vodCategoryLabel(): String {
    return groupTitle
        ?.removePrefix("VOD:")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "Uncategorized"
}

private fun Channel.vodStableKey(): String {
    val providerId = kodiProps["vod_stream_id"]
        ?: kodiProps["vod_series_id"]
        ?: kodiProps["vod_episode_id"]
        ?: url
    return listOf(playlistId, contentType.name, providerId, url, name).joinToString("|")
}

private fun Channel.vodRatingValue(): Double? {
    val rating = kodiProps["vod_rating"]?.toDoubleOrNull()?.takeIf { it > 0.0 }
    if (rating != null) return rating
    return kodiProps["vod_rating_5based"]
        ?.toDoubleOrNull()
        ?.takeIf { it > 0.0 }
        ?.times(2.0)
}

private fun Channel.vodRatingText(): String? {
    val rating = vodRatingValue() ?: return null
    return DecimalFormat("0.#").format(rating)
}

private fun Channel.vodYear(): Int? {
    val candidates = listOf(
        kodiProps["vod_release_date"],
        kodiProps["vod_last_modified"],
        name,
    )
    return candidates
        .asSequence()
        .filterNotNull()
        .mapNotNull { YEAR_PATTERN.find(it)?.value?.toIntOrNull() }
        .firstOrNull { it in 1900..2100 }
}

private val YEAR_PATTERN = Regex("""\b(19|20)\d{2}\b""")

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return "${DecimalFormat("0.#").format(value)} ${units[unitIndex]}"
}
