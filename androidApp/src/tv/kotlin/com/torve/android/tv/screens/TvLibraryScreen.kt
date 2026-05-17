package com.torve.android.tv.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.tv.TvScreenCache
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.components.rememberTvFocusMemory
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.toMediaItem
import com.torve.android.ui.theme.*
import com.torve.domain.model.Channel
import com.torve.domain.model.DownloadMediaType
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.WatchlistRepository
import com.torve.presentation.download.DownloadCatalogueViewModel
import com.torve.presentation.jellyfin.JellyfinBrowserViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private data class TvLibraryUiState(
    val loading: Boolean = true,
    val rails: List<TvContentRail> = emptyList(),
    val error: String? = null,
)

private enum class LibraryTab { WATCHLIST, FAVORITES, VOD, DOWNLOADS, JELLYFIN }

@Composable
internal fun TvLibraryScreen(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)? = null,
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    heroOverlay: (@Composable () -> Unit)? = null,
    shouldAutoFocus: Boolean = true,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)? = null,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)? = null,
    progressResolver: ((MediaItem, Float?) -> Float?)? = null,
    onJellyfinItemPlay: (streamUrl: String, title: String) -> Unit = { _, _ -> },
    onVodItemPlay: (channel: Channel, item: MediaItem) -> Unit = { _, _ -> },
    onVodSeriesOpen: (channel: Channel, item: MediaItem) -> Unit = onVodItemPlay,
    favoriteMediaItems: List<MediaItem> = emptyList(),
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
) {
    // Defer Jellyfin ViewModel creation until the tab is actually selected
    // Verified: rememberSaveable preserves tab selection across navigation (e.g. return from Details)
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    val availableTabs = listOf(
        LibraryTab.WATCHLIST,
        LibraryTab.FAVORITES,
        LibraryTab.VOD,
        LibraryTab.JELLYFIN,
    )
    val tabRequesters = remember(availableTabs.size) {
        List(availableTabs.size) { FocusRequester() }
    }
    val currentTab = availableTabs.getOrElse(selectedTabIndex) { LibraryTab.WATCHLIST }

    LaunchedEffect(shouldAutoFocus) {
        if (!shouldAutoFocus) return@LaunchedEffect
        delay(50)
        runCatching { tabRequesters.firstOrNull()?.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab row
        val tabLabels = availableTabs.map { tab ->
            when (tab) {
                LibraryTab.WATCHLIST -> stringResource(R.string.tv_library_tab_watchlist)
                LibraryTab.FAVORITES -> stringResource(R.string.tv_iptv_favorites)
                LibraryTab.VOD -> stringResource(R.string.tv_library_tab_vod)
                LibraryTab.DOWNLOADS -> stringResource(R.string.tv_library_tab_downloads)
                LibraryTab.JELLYFIN -> stringResource(R.string.watchlist_jellyfin)
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        ) {
            itemsIndexed(
                items = tabLabels,
                key = { index, _ -> "tab_$index" },
            ) { index, label ->
                val requester = tabRequesters[index]
                if (index == 0) {
                    onFirstContentRequester(requester)
                }
                TvLibraryTabChip(
                    label = label,
                    isSelected = index == selectedTabIndex,
                    modifier = Modifier
                        .focusRequester(requester)
                        .then(
                            // railFocusRequester is always attached (left nav rail in TvRoot), safe to reference
                        if (index == 0) Modifier.focusProperties { left = railFocusRequester }
                            else Modifier
                        ),
                    onFocused = { onContentFocused(requester) },
                    onClick = { selectedTabIndex = index },
                )
            }
        }

        // Content
        when (currentTab) {
            LibraryTab.WATCHLIST -> WatchlistContent(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = headerFocusRequester,
                onMediaClick = onMediaClick,
                onFirstContentRequester = {},
                onContentFocused = onContentFocused,
                onMediaFocused = onMediaFocused,
                onSeeAll = onSeeAll,
                heroOverlay = heroOverlay,
                shouldAutoFocus = shouldAutoFocus,
                contextMenuActionsForItem = contextMenuActionsForItem,
                onContextMenuAction = onContextMenuAction,
                progressResolver = progressResolver,
                registerFocusHandle = registerFocusHandle,
            )
            LibraryTab.FAVORITES -> FavoritesContent(
                favoriteMediaItems = favoriteMediaItems,
                railFocusRequester = railFocusRequester,
                headerFocusRequester = tabRequesters.getOrNull(selectedTabIndex) ?: headerFocusRequester,
                onMediaClick = onMediaClick,
                onFirstContentRequester = {},
                onContentFocused = onContentFocused,
                onMediaFocused = onMediaFocused,
                shouldAutoFocus = shouldAutoFocus,
                contextMenuActionsForItem = contextMenuActionsForItem,
                onContextMenuAction = onContextMenuAction,
                progressResolver = progressResolver,
            )
            LibraryTab.DOWNLOADS -> { /* Downloads removed from TV — stream-only */ }
            LibraryTab.VOD -> TvVodLibraryContent(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = tabRequesters.getOrNull(selectedTabIndex) ?: headerFocusRequester,
                onVodItemPlay = onVodItemPlay,
                onVodSeriesOpen = onVodSeriesOpen,
                onFirstContentRequester = {},
                onContentFocused = onContentFocused,
                onMediaFocused = onMediaFocused,
                shouldAutoFocus = shouldAutoFocus,
                registerFocusHandle = registerFocusHandle,
            )
            LibraryTab.JELLYFIN -> JellyfinContent(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = headerFocusRequester,
                onFirstContentRequester = {},
                onContentFocused = onContentFocused,
                onMediaFocused = onMediaFocused,
                shouldAutoFocus = shouldAutoFocus,
                onJellyfinItemPlay = onJellyfinItemPlay,
                registerFocusHandle = registerFocusHandle,
            )
        }
    }
}

@Composable
private fun WatchlistContent(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)?,
    onSeeAll: ((railKey: String, title: String) -> Unit)?,
    heroOverlay: (@Composable () -> Unit)?,
    shouldAutoFocus: Boolean,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)?,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)?,
    progressResolver: ((MediaItem, Float?) -> Float?)?,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)?,
) {
    val watchlistViewModel: WatchlistViewModel = koinInject()
    val watchlistState by watchlistViewModel.state.collectAsState()
    val focusMemory = rememberTvFocusMemory()

    val watchlistMoviesLabel = stringResource(R.string.tv_section_watchlist_movies)
    val watchlistShowsLabel = stringResource(R.string.tv_section_watchlist_shows)

    LaunchedEffect(Unit) {
        watchlistViewModel.loadWatchlist()
    }

    val rails = remember(watchlistState.items, watchlistMoviesLabel, watchlistShowsLabel) {
        val movieItems = watchlistState.items.filter { it.mediaType == MediaType.MOVIE }.map { it.toMediaItem() }
        val showItems = watchlistState.items.filter { it.mediaType == MediaType.SERIES }.map { it.toMediaItem() }
        buildList {
            if (movieItems.isNotEmpty()) {
                add(
                    TvContentRail(
                        key = "watchlist_movies",
                        title = watchlistMoviesLabel,
                        items = movieItems,
                    ),
                )
            }
            if (showItems.isNotEmpty()) {
                add(
                    TvContentRail(
                        key = "watchlist_shows",
                        title = watchlistShowsLabel,
                        items = showItems,
                    ),
                )
            }
        }
    }

    val emptyMessage = watchlistState.error ?: stringResource(R.string.tv_library_empty)
    TvMediaRails(
        rails = rails,
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = onMediaClick,
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        screenId = "library_watchlist",
        focusMemory = focusMemory,
        loading = watchlistState.isLoading,
        emptyMessage = emptyMessage,
        onMediaFocused = onMediaFocused,
        onSeeAll = onSeeAll,
        heroOverlay = heroOverlay,
        shouldAutoFocus = shouldAutoFocus,
        progressResolver = progressResolver,
        contextMenuActionsForItem = contextMenuActionsForItem,
        onContextMenuAction = onContextMenuAction,
        registerFocusHandle = registerFocusHandle,
    )
}

@Composable
private fun FavoritesContent(
    favoriteMediaItems: List<MediaItem>,
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)?,
    shouldAutoFocus: Boolean,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)?,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)?,
    progressResolver: ((MediaItem, Float?) -> Float?)?,
) {
    val focusMemory = rememberTvFocusMemory()
    val favoritesLabel = stringResource(R.string.tv_iptv_favorites)
    val uiState = remember(favoriteMediaItems, favoritesLabel) {
        TvLibraryUiState(
            loading = false,
            rails = if (favoriteMediaItems.isNotEmpty()) {
                listOf(TvContentRail(key = "media_favorites", title = favoritesLabel, items = favoriteMediaItems))
            } else {
                emptyList()
            },
        )
    }

    TvMediaRails(
        rails = uiState.rails,
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = onMediaClick,
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        screenId = "library_favorites",
        focusMemory = focusMemory,
        loading = uiState.loading,
        emptyMessage = uiState.error ?: "No favorites yet",
        onMediaFocused = onMediaFocused,
        onSeeAll = null,
        shouldAutoFocus = shouldAutoFocus,
        progressResolver = progressResolver,
        contextMenuActionsForItem = contextMenuActionsForItem,
        onContextMenuAction = onContextMenuAction,
    )
}

@Composable
private fun DownloadsContent(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)?,
    onSeeAll: ((railKey: String, title: String) -> Unit)?,
    shouldAutoFocus: Boolean,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)?,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)?,
) {
    val downloadCatalogueViewModel: DownloadCatalogueViewModel = koinInject()
    val catalogueState by downloadCatalogueViewModel.state.collectAsState()
    val focusMemory = rememberTvFocusMemory()

    // loadCatalogue() is already called in the ViewModel's init block;
    // calling it again here would reset isLoading and briefly clear the rails,
    // which can cause focus loss and crashes during navigation.

    val downloadingLabel = stringResource(R.string.tv_library_downloading)
    val downloadedMoviesLabel = stringResource(R.string.tv_library_downloaded_movies)
    val downloadedShowsLabel = stringResource(R.string.tv_library_downloaded_shows)

    val rails = remember(catalogueState.allDownloadedItems, catalogueState.activeDownloads) {
        val items = catalogueState.allDownloadedItems
        val movieItems = items.filter { it.type == DownloadMediaType.MOVIE }.map { dl ->
            MediaItem(
                id = dl.mediaId,
                type = MediaType.MOVIE,
                title = dl.title,
                posterUrl = dl.posterUrl,
            )
        }
        val showItems = items.filter { it.type == DownloadMediaType.EPISODE }.map { dl ->
            MediaItem(
                id = dl.mediaId,
                type = MediaType.SERIES,
                title = dl.title,
                posterUrl = dl.posterUrl,
            )
        }
        val activeItems = catalogueState.activeDownloads
            .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
            .map { dl ->
                val pct = if ((dl.fileSizeBytes ?: 0) > 0) (dl.downloadedBytes * 100 / dl.fileSizeBytes!!) else 0
                MediaItem(
                    id = dl.mediaId,
                    type = if (dl.mediaType == MediaType.SERIES) MediaType.SERIES else MediaType.MOVIE,
                    title = "${dl.title} (${pct}%)",
                    posterUrl = dl.posterUrl,
                )
            }
        buildList {
            if (activeItems.isNotEmpty()) {
                add(TvContentRail(key = "dl_active", title = downloadingLabel, items = activeItems))
            }
            if (movieItems.isNotEmpty()) {
                add(TvContentRail(key = "dl_movies", title = downloadedMoviesLabel, items = movieItems.distinctBy { it.id }))
            }
            if (showItems.isNotEmpty()) {
                add(TvContentRail(key = "dl_shows", title = downloadedShowsLabel, items = showItems.distinctBy { it.id }))
            }
        }
    }

    val emptyMessage = stringResource(R.string.tv_library_no_downloads)
    TvMediaRails(
        rails = rails,
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = onMediaClick,
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        screenId = "library_downloads",
        focusMemory = focusMemory,
        loading = catalogueState.isLoading,
        emptyMessage = emptyMessage,
        onMediaFocused = onMediaFocused,
        onSeeAll = null, // No See All for downloads — items are local, not from metadata API
        shouldAutoFocus = shouldAutoFocus,
        contextMenuActionsForItem = contextMenuActionsForItem,
        onContextMenuAction = onContextMenuAction,
    )
}

@Composable
private fun JellyfinContent(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)?,
    shouldAutoFocus: Boolean,
    onJellyfinItemPlay: (streamUrl: String, title: String) -> Unit,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)?,
) {
    val jellyfinBrowserViewModel: JellyfinBrowserViewModel = koinInject()
    val state by jellyfinBrowserViewModel.state.collectAsState()
    val focusMemory = rememberTvFocusMemory()

    LaunchedEffect(Unit) {
        println("JELLYFIN_TV: JellyfinContent composed, calling loadLibrary")
        jellyfinBrowserViewModel.loadLibrary()
    }

    // Build rails with image URLs (needs suspend for buildImageUrl)
    var jellyfinRails by remember { mutableStateOf<List<TvContentRail>>(emptyList()) }
    LaunchedEffect(state.sections, state.sectionItems) {
        jellyfinRails = state.sections.mapNotNull { section ->
            val items = state.sectionItems[section.id] ?: return@mapNotNull null
            if (items.isEmpty()) return@mapNotNull null
            TvContentRail(
                key = "jf_${section.id}",
                title = section.name,
                items = items.map { jfItem ->
                    MediaItem(
                        id = "jf_${jfItem.id}",
                        type = if (jfItem.type == "Series") MediaType.SERIES else MediaType.MOVIE,
                        title = jfItem.name,
                        year = jfItem.productionYear,
                        posterUrl = jellyfinBrowserViewModel.buildImageUrl(jfItem.id),
                    )
                },
            )
        }
    }

    val jellyfinScope = androidx.compose.runtime.rememberCoroutineScope()
    TvMediaRails(
        rails = jellyfinRails,
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = { item ->
            val jellyfinId = item.id.removePrefix("jf_")
            jellyfinScope.launch {
                val url = jellyfinBrowserViewModel.buildStreamUrl(jellyfinId)
                if (url != null) onJellyfinItemPlay(url, item.title)
            }
        },
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        screenId = "library_jellyfin",
        focusMemory = focusMemory,
        loading = state.isLoading,
        emptyMessage = stringResource(R.string.jellyfin_library_empty),
        onMediaFocused = onMediaFocused,
        onSeeAll = null,
        shouldAutoFocus = shouldAutoFocus,
        registerFocusHandle = registerFocusHandle,
    )
}

@Composable
private fun TvLibraryTabChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.035f else 1f, label = "tabScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            isSelected -> Amber.copy(alpha = 0.48f)
            else -> Color.Transparent
        },
        label = "tabBorder",
    )
    val bgColor = when {
        focused -> Graphite.copy(alpha = 0.44f)
        isSelected -> Amber.copy(alpha = 0.11f)
        else -> Charcoal.copy(alpha = 0.24f)
    }

    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .focusable()
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected || focused) Snow else Silver,
            fontWeight = if (isSelected || focused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
