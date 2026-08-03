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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.TV_PAGE_END_GUTTER
import com.torve.android.tv.TV_PAGE_TOP_GUTTER
import com.torve.android.tv.NotificationType
import com.torve.android.tv.TvNotificationQueue
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.components.TvRailsPresentationMode
import com.torve.android.tv.components.rememberTvFocusMemory
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.focus.TvLibraryFocusPolicy
import com.torve.android.tv.focus.TvLibraryTab
import com.torve.android.tv.toMediaItem
import com.torve.android.ui.theme.*
import com.torve.data.integrations.JellyfinBrowseItem
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.Channel
import com.torve.domain.model.DownloadMediaType
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.download.DownloadCatalogueViewModel
import com.torve.presentation.jellyfin.JellyfinBrowserViewModel
import com.torve.presentation.library.AcquisitionLifecycleItem
import com.torve.presentation.library.PermanentLibraryViewModel
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private enum class JellyfinContentType(val label: String) {
    OVERVIEW("Overview"),
    MOVIES("Movies"),
    SERIES("Series"),
}

private enum class JellyfinViewFilter(val label: String) {
    ALL("All"),
    CONTINUE("Continue"),
    RECENT("Recently added"),
    UNWATCHED("Unwatched"),
    TOP_RATED("Top rated"),
    GENRES("Genres"),
    DECADES("Decades"),
    STUDIOS("Studios"),
}

private const val TV_PREF_JELLYFIN_FILTERS_VISIBLE = "jellyfin_filters_visible"
private const val TV_PREF_JELLYFIN_POSTERS_PER_ROW = "jellyfin_posters_per_row"
private val JELLYFIN_POSTER_COLUMN_OPTIONS = listOf(5, 6, 7, 8)

private fun JellyfinContentType.availableFilters(): List<JellyfinViewFilter> = when (this) {
    JellyfinContentType.OVERVIEW -> listOf(
        JellyfinViewFilter.ALL,
        JellyfinViewFilter.CONTINUE,
        JellyfinViewFilter.RECENT,
    )
    JellyfinContentType.MOVIES,
    JellyfinContentType.SERIES,
    -> JellyfinViewFilter.entries
}

private data class JellyfinLibraryMedia(
    val source: JellyfinBrowseItem,
    val media: MediaItem,
)

private data class TvLibraryUiState(
    val loading: Boolean = true,
    val rails: List<TvContentRail> = emptyList(),
    val error: String? = null,
)

@Composable
private fun rememberLibraryEnrichedItems(
    baseItems: List<MediaItem>,
    cacheKey: String,
): List<MediaItem> {
    val ratingsEnricher: RatingsEnricher = koinInject()
    val prefsRepo: PreferencesRepository = koinInject()
    val secretStore: IntegrationSecretStore = koinInject()
    var enrichedItems by remember(cacheKey) { mutableStateOf(baseItems) }
    val signature = remember(baseItems) { libraryItemsSignature(baseItems) }

    LaunchedEffect(cacheKey, signature) {
        enrichedItems = baseItems
        if (baseItems.isEmpty()) return@LaunchedEffect

        val hydrated = withContext(Dispatchers.IO) {
            ratingsEnricher.hydrateListFromCache(baseItems)
        }
        enrichedItems = hydrated

        val apiKey = withContext(Dispatchers.IO) {
            runCatching {
                secretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            }.getOrDefault(MdbListApi.DEFAULT_API_KEY)
        }

        var current = hydrated
        for (attempt in 0 until 3) {
            val enriched = withContext(Dispatchers.IO) {
                ratingsEnricher.enrichList(current, apiKey)
            }
            if (libraryItemRatingsChanged(current, enriched)) {
                current = enriched
                enrichedItems = enriched
            }
            val remainingMs = ratingsEnricher.rateLimitRemainingMs()
            if (remainingMs <= 0L || attempt == 2) break
            delay(remainingMs + 2_000L)
        }
    }

    return enrichedItems
}

private fun libraryItemsSignature(items: List<MediaItem>): String =
    items.joinToString("|") { item ->
        "${item.type}:${item.tmdbId ?: item.id}:${item.imdbId.orEmpty()}:${item.rating ?: 0.0}"
    }

private fun libraryItemRatingsChanged(
    before: List<MediaItem>,
    after: List<MediaItem>,
): Boolean {
    if (before.size != after.size) return true
    return before.zip(after).any { (left, right) ->
        left.id != right.id ||
            left.tmdbId != right.tmdbId ||
            left.imdbId != right.imdbId ||
            left.ratings != right.ratings
    }
}

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
    onVodItemPlay: (channel: Channel, item: MediaItem) -> Unit = { _, _ -> },
    onVodSeriesOpen: (channel: Channel, item: MediaItem) -> Unit = onVodItemPlay,
    favoriteMediaItems: List<MediaItem> = emptyList(),
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
) {
    // Defer Jellyfin ViewModel creation until the tab is actually selected
    // Verified: rememberSaveable preserves tab selection across navigation (e.g. return from Details)
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    var pendingClickedTabFocusIndex by remember { mutableStateOf<Int?>(null) }
    val availableTabs = TvLibraryFocusPolicy.visibleTabs
    val tabRequesters = remember(availableTabs.size) {
        List(availableTabs.size) { FocusRequester() }
    }
    val currentTab = TvLibraryFocusPolicy.tabAt(selectedTabIndex)

    LaunchedEffect(pendingClickedTabFocusIndex, selectedTabIndex, currentTab) {
        val targetIndex = pendingClickedTabFocusIndex ?: return@LaunchedEffect
        if (!TvLibraryFocusPolicy.shouldRestoreClickedTab(targetIndex, selectedTabIndex)) return@LaunchedEffect
        val requester = tabRequesters.getOrNull(targetIndex) ?: return@LaunchedEffect
        repeat(5) {
            delay(35)
            if (runCatching { requester.requestFocus() }.isSuccess) {
                onContentFocused(requester)
                pendingClickedTabFocusIndex = null
                return@LaunchedEffect
            }
        }
    }

    val selectedTabRequester = tabRequesters.getOrNull(selectedTabIndex) ?: headerFocusRequester
    val useHeroBackedTabRow = currentTab in setOf(
        TvLibraryTab.WATCHLIST,
        TvLibraryTab.FAVORITES,
        TvLibraryTab.REQUESTS,
    )
    val tabLabels = availableTabs.map { tab ->
        when (tab) {
            TvLibraryTab.WATCHLIST -> stringResource(R.string.tv_library_tab_watchlist)
            TvLibraryTab.FAVORITES -> stringResource(R.string.tv_iptv_favorites)
            TvLibraryTab.REQUESTS -> "Requests & downloads"
            TvLibraryTab.VOD -> stringResource(R.string.tv_library_tab_vod)
            TvLibraryTab.DOWNLOADS -> stringResource(R.string.tv_library_tab_downloads)
        }
    }

    @Composable
    fun LibraryTabsRow(modifier: Modifier = Modifier) {
        LazyRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = TV_PAGE_CONTENT_GUTTER,
                end = TV_PAGE_END_GUTTER,
                top = TV_PAGE_TOP_GUTTER,
                bottom = 8.dp,
            ),
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
                    onClick = {
                        pendingClickedTabFocusIndex = index
                        selectedTabIndex = index
                        runCatching { requester.requestFocus() }
                        onContentFocused(requester)
                    },
                )
            }
        }
    }

    @Composable
    fun LibraryTabContent() {
        when (currentTab) {
            TvLibraryTab.WATCHLIST -> WatchlistContent(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = selectedTabRequester,
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
            TvLibraryTab.FAVORITES -> FavoritesContent(
                favoriteMediaItems = favoriteMediaItems,
                railFocusRequester = railFocusRequester,
                headerFocusRequester = selectedTabRequester,
                onMediaClick = onMediaClick,
                onFirstContentRequester = {},
                onContentFocused = onContentFocused,
                onMediaFocused = onMediaFocused,
                onSeeAll = onSeeAll,
                heroOverlay = heroOverlay,
                shouldAutoFocus = false,
                contextMenuActionsForItem = contextMenuActionsForItem,
                onContextMenuAction = onContextMenuAction,
                progressResolver = progressResolver,
                registerFocusHandle = registerFocusHandle,
            )
            TvLibraryTab.REQUESTS -> RequestsContent(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = selectedTabRequester,
                onMediaClick = onMediaClick,
                onFirstContentRequester = {},
                onContentFocused = onContentFocused,
                onMediaFocused = onMediaFocused,
                heroOverlay = heroOverlay,
                registerFocusHandle = registerFocusHandle,
            )
            TvLibraryTab.DOWNLOADS -> { /* Downloads removed from TV — stream-only */ }
            TvLibraryTab.VOD -> TvVodLibraryContent(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = selectedTabRequester,
                onVodItemPlay = onVodItemPlay,
                onVodSeriesOpen = onVodSeriesOpen,
                onFirstContentRequester = {},
                onContentFocused = onContentFocused,
                onMediaFocused = onMediaFocused,
                shouldAutoFocus = false,
                registerFocusHandle = registerFocusHandle,
            )
        }
    }

    if (useHeroBackedTabRow) {
        Box(modifier = Modifier.fillMaxSize()) {
            LibraryTabContent()
            LibraryTabsRow(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .zIndex(20f),
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            LibraryTabsRow()
            LibraryTabContent()
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

    val baseItems = remember(watchlistState.items) {
        watchlistState.items.map { it.toMediaItem() }
    }
    val enrichedItems = rememberLibraryEnrichedItems(
        baseItems = baseItems,
        cacheKey = "library_watchlist",
    )

    val rails = remember(enrichedItems, watchlistMoviesLabel, watchlistShowsLabel) {
        val movieItems = enrichedItems.filter { it.type == MediaType.MOVIE }
        val showItems = enrichedItems.filter { it.type == MediaType.SERIES }
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
        shouldAutoFocus = false,
        progressResolver = progressResolver,
        contextMenuActionsForItem = contextMenuActionsForItem,
        onContextMenuAction = onContextMenuAction,
        registerFocusHandle = registerFocusHandle,
        sourceAwareRatings = true,
        presentationMode = TvRailsPresentationMode.LibraryHero,
        focusExclusive = true,
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
    onSeeAll: ((railKey: String, title: String) -> Unit)?,
    heroOverlay: (@Composable () -> Unit)?,
    shouldAutoFocus: Boolean,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)?,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)?,
    progressResolver: ((MediaItem, Float?) -> Float?)?,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)?,
) {
    val focusMemory = rememberTvFocusMemory()
    val favoriteMoviesLabel = "Favorite Movies"
    val favoriteShowsLabel = "Favorite TV Shows"
    val enrichedItems = rememberLibraryEnrichedItems(
        baseItems = favoriteMediaItems,
        cacheKey = "library_favorites",
    )
    val uiState = remember(enrichedItems, favoriteMoviesLabel, favoriteShowsLabel) {
        val movieItems = enrichedItems.filter { it.type == MediaType.MOVIE }
        val showItems = enrichedItems.filter { it.type == MediaType.SERIES }
        TvLibraryUiState(
            loading = false,
            rails = buildList {
                if (movieItems.isNotEmpty()) {
                    add(TvContentRail(key = "favorite_movies", title = favoriteMoviesLabel, items = movieItems))
                }
                if (showItems.isNotEmpty()) {
                    add(TvContentRail(key = "favorite_shows", title = favoriteShowsLabel, items = showItems))
                }
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
        emptyMessage = uiState.error ?: "No favorites yet\nMark titles as favorites to collect them here.",
        onMediaFocused = onMediaFocused,
        onSeeAll = onSeeAll,
        heroOverlay = heroOverlay,
        shouldAutoFocus = shouldAutoFocus,
        progressResolver = progressResolver,
        contextMenuActionsForItem = contextMenuActionsForItem,
        onContextMenuAction = onContextMenuAction,
        registerFocusHandle = registerFocusHandle,
        sourceAwareRatings = true,
        presentationMode = TvRailsPresentationMode.LibraryHero,
        showSeeAllCards = false,
        focusExclusive = true,
    )
}

@Composable
private fun RequestsContent(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)?,
    heroOverlay: (@Composable () -> Unit)?,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)?,
) {
    val viewModel: PermanentLibraryViewModel = koinInject()
    val state by viewModel.state.collectAsState()
    val focusMemory = rememberTvFocusMemory()

    DisposableEffect(viewModel) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    LaunchedEffect(state.newlyAvailable) {
        state.newlyAvailable.firstOrNull()?.let { item ->
            TvNotificationQueue.post(
                message = "${item.title} is now available in your library",
                type = NotificationType.SUCCESS,
            )
            viewModel.acknowledgeAvailable(item.stableId)
        }
    }

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { message ->
            TvNotificationQueue.post(
                message = message,
                type = if (message.contains("could not", ignoreCase = true)) {
                    NotificationType.ERROR
                } else {
                    NotificationType.INFO
                },
            )
            viewModel.clearActionMessage()
        }
    }

    val baseItems = remember(state.activeItems) {
        state.activeItems.map(AcquisitionLifecycleItem::toTvMediaItem)
    }
    val enrichedItems = rememberLibraryEnrichedItems(
        baseItems = baseItems,
        cacheKey = "library_acquisition_requests",
    )
    val progress = remember(state.activeItems) {
        state.activeItems.mapNotNull { item ->
            item.progressPercent?.let { item.stableId to (it / 100.0).toFloat().coerceIn(0f, 1f) }
        }.toMap()
    }
    val lifecycleById = remember(state.activeItems) {
        state.activeItems.associateBy(AcquisitionLifecycleItem::stableId)
    }
    val rails = remember(enrichedItems, progress) {
        val attention = enrichedItems.filter { it.status?.startsWith("Needs attention") == true }
        val active = enrichedItems.filterNot { it in attention }
        buildList {
            if (attention.isNotEmpty()) {
                add(TvContentRail("library_requests_attention", "Needs attention", attention))
            }
            if (active.isNotEmpty()) {
                add(
                    TvContentRail(
                        key = "library_requests_active",
                        title = "Being prepared",
                        items = active,
                        progressByMediaId = progress,
                    ),
                )
            }
        }
    }
    val emptyMessage = when {
        state.error != null -> state.error
        !state.isConfigured -> "Connect Seerr in Settings to save movies and shows permanently."
        else -> "Nothing is being prepared right now.\nUse Add to library on any movie or show."
    }.orEmpty()

    TvMediaRails(
        rails = rails,
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = { item -> if (item.tmdbId != null) onMediaClick(item) },
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        screenId = "library_requests",
        focusMemory = focusMemory,
        loading = state.isLoading,
        emptyMessage = emptyMessage,
        onMediaFocused = onMediaFocused,
        heroOverlay = heroOverlay,
        shouldAutoFocus = false,
        registerFocusHandle = registerFocusHandle,
        sourceAwareRatings = true,
        presentationMode = TvRailsPresentationMode.LibraryHero,
        showSeeAllCards = false,
        focusExclusive = true,
        contextMenuActionsForItem = { media, _ ->
            lifecycleById[media.id]?.let { lifecycle ->
                buildList {
                    if (lifecycle.canRetry) {
                        add(TvMediaContextMenuAction(id = "retry_acquisition", label = "Retry download"))
                    }
                    if (lifecycle.canCancel) {
                        add(
                            TvMediaContextMenuAction(
                                id = "cancel_acquisition",
                                label = "Cancel current download",
                                isDestructive = true,
                            ),
                        )
                    }
                    if (lifecycle.canDeleteRequest) {
                        add(
                            TvMediaContextMenuAction(
                                id = "delete_library_request",
                                label = if (lifecycle.isActive) {
                                    "Cancel library request"
                                } else {
                                    "Remove library request"
                                },
                                isDestructive = true,
                            ),
                        )
                    }
                }
            }.orEmpty()
        },
        onContextMenuAction = { media, action, _ ->
            when (action.id) {
                "retry_acquisition" -> viewModel.retryAcquisition(media.id)
                "cancel_acquisition" -> viewModel.cancelAcquisition(media.id)
                "delete_library_request" -> viewModel.deleteRequest(media.id)
            }
        },
    )
}

private fun AcquisitionLifecycleItem.toTvMediaItem(): MediaItem = MediaItem(
    id = stableId,
    tmdbId = tmdbId,
    type = mediaType,
    title = title,
    year = year,
    overview = overview,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    rating = rating,
    status = statusLabel,
)

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
internal fun TvJellyfinScreen(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)?,
    heroOverlay: (@Composable () -> Unit)?,
    shouldAutoFocus: Boolean,
    onJellyfinItemPlay: (streamUrl: String, title: String) -> Unit,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)?,
) {
    val jellyfinBrowserViewModel: JellyfinBrowserViewModel = koinInject()
    val settingsRefreshNotifier: SettingsRefreshNotifier = koinInject()
    val state by jellyfinBrowserViewModel.state.collectAsState()
    val settingsRefreshEpoch by settingsRefreshNotifier.events.collectAsState(initial = 0L)
    val focusMemory = rememberTvFocusMemory()

    LaunchedEffect(Unit) {
        println("JELLYFIN_TV: JellyfinContent composed, calling loadLibrary")
        jellyfinBrowserViewModel.loadLibrary()
    }

    // Account refresh may restore a missing API key or server URL. Revalidate
    // Jellyfin without clearing posters already on screen; transient provider
    // failures must not make the library appear to have vanished.
    LaunchedEffect(settingsRefreshEpoch) {
        if (settingsRefreshEpoch > 0L) {
            jellyfinBrowserViewModel.reload(preserveContent = true)
        }
    }

    val context = LocalContext.current
    val tvPrefs = remember(context) {
        context.getSharedPreferences("tv_prefs", android.content.Context.MODE_PRIVATE)
    }
    var selectedContentType by rememberSaveable {
        mutableStateOf(JellyfinContentType.OVERVIEW)
    }
    var selectedFilter by rememberSaveable {
        mutableStateOf(JellyfinViewFilter.ALL)
    }
    var filtersVisible by rememberSaveable {
        mutableStateOf(tvPrefs.getBoolean(TV_PREF_JELLYFIN_FILTERS_VISIBLE, true))
    }
    var postersPerRow by rememberSaveable {
        mutableStateOf(
            tvPrefs.getInt(TV_PREF_JELLYFIN_POSTERS_PER_ROW, 6)
                .takeIf { it in JELLYFIN_POSTER_COLUMN_OPTIONS }
                ?: 6,
        )
    }
    val filterFocusRequester = remember { FocusRequester() }

    LaunchedEffect(selectedContentType) {
        if (selectedFilter !in selectedContentType.availableFilters()) {
            selectedFilter = JellyfinViewFilter.ALL
        }
    }

    // Keep Jellyfin's metadata beside the MediaItem so managed categories can
    // use real genres, dates, ratings and playback state.
    var jellyfinMedia by remember { mutableStateOf<List<JellyfinLibraryMedia>>(emptyList()) }
    LaunchedEffect(state.sections, state.sectionItems) {
        jellyfinMedia = state.sections
            .flatMap { section -> state.sectionItems[section.id].orEmpty() }
            .filterNot(JellyfinBrowseItem::isEpisode)
            .distinctBy(JellyfinBrowseItem::id)
            .map { jfItem ->
                val playback = jfItem.userData
                JellyfinLibraryMedia(
                    source = jfItem,
                    media = MediaItem(
                        id = "jf_${jfItem.id}",
                        type = if (jfItem.type.equals("Movie", ignoreCase = true)) {
                            MediaType.MOVIE
                        } else {
                            MediaType.SERIES
                        },
                        title = jfItem.displayTitle,
                        tmdbId = jfItem.providerIds?.tmdb?.toIntOrNull(),
                        imdbId = jfItem.providerIds?.imdb,
                        year = jfItem.productionYear,
                        overview = jfItem.overview,
                        posterUrl = jfItem.resolvedPrimaryImageTag?.let {
                            jellyfinBrowserViewModel.buildImageUrl(jfItem.id)
                        } ?: jfItem.fallbackPosterUrl,
                        backdropUrl = jfItem.backdropImageTags.firstOrNull()?.let {
                            jellyfinBrowserViewModel.buildBackdropImageUrl(jfItem.id)
                        } ?: jfItem.fallbackBackdropUrl,
                        rating = jfItem.communityRating,
                        status = when {
                            playback?.played == true -> "Watched in Jellyfin"
                            playback?.playbackPositionTicks?.let { it > 0L } == true -> "Continue watching"
                            else -> "Available in Jellyfin"
                        },
                    ),
                )
            }
    }

    val enrichedJellyfinItems = rememberLibraryEnrichedItems(
        baseItems = jellyfinMedia.map(JellyfinLibraryMedia::media),
        cacheKey = "library_jellyfin",
    )
    val enrichedById = remember(enrichedJellyfinItems) {
        enrichedJellyfinItems.associateBy(MediaItem::id)
    }
    val displayMedia = remember(jellyfinMedia, enrichedById) {
        jellyfinMedia.map { record ->
            record.copy(media = enrichedById[record.media.id] ?: record.media)
        }
    }
    val displayRails = remember(displayMedia, selectedContentType, selectedFilter) {
        buildJellyfinManagedRails(displayMedia, selectedContentType, selectedFilter)
    }

    val jellyfinScope = androidx.compose.runtime.rememberCoroutineScope()
    val heroHeight = 210.dp
    val fixedHeaderHeight = if (filtersVisible) 296.dp else 258.dp
    val railViewportTop = fixedHeaderHeight

    LaunchedEffect(filterFocusRequester) {
        onFirstContentRequester(filterFocusRequester)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = railViewportTop)
                .clipToBounds(),
        ) {
            TvMediaRails(
                rails = displayRails,
                railFocusRequester = railFocusRequester,
                headerFocusRequester = headerFocusRequester,
                heroOverlayFocusRequester = filterFocusRequester,
                onMediaClick = { item ->
                    if (item.tmdbId != null) {
                        onMediaClick(item)
                    } else {
                        val jellyfinId = item.id.removePrefix("jf_")
                        jellyfinScope.launch {
                            val url = jellyfinBrowserViewModel.buildStreamUrl(jellyfinId)
                            if (url != null) onJellyfinItemPlay(url, item.title)
                        }
                    }
                },
                onFirstContentRequester = {},
                onContentFocused = onContentFocused,
                modifier = Modifier.fillMaxSize(),
                screenId = "library_jellyfin",
                focusMemory = focusMemory,
                loading = state.isLoading,
                emptyMessage = state.error ?: stringResource(R.string.jellyfin_library_empty),
                onMediaFocused = onMediaFocused,
                onSeeAll = null,
                shouldAutoFocus = false,
                registerFocusHandle = registerFocusHandle,
                sourceAwareRatings = true,
                presentationMode = TvRailsPresentationMode.LibraryHero,
                showSeeAllCards = false,
                focusExclusive = true,
                forcePosterTitles = false,
                compactPosterTitles = false,
                compactRailTitleSpacing = true,
                posterColumns = postersPerRow,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(fixedHeaderHeight)
                .zIndex(20f)
                .clipToBounds()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.72f),
                            Graphite.copy(alpha = 0.34f),
                            Obsidian.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                    ),
                ),
        ) {
            JellyfinLibraryControlRows(
                contentType = selectedContentType,
                selectedFilter = selectedFilter,
                filtersVisible = filtersVisible,
                postersPerRow = postersPerRow,
                firstFocusRequester = filterFocusRequester,
                railFocusRequester = railFocusRequester,
                headerFocusRequester = headerFocusRequester,
                itemCount = displayMedia.size,
                onFocused = { onContentFocused(filterFocusRequester) },
                onContentTypeSelected = { contentType ->
                    selectedContentType = contentType
                    selectedFilter = JellyfinViewFilter.ALL
                },
                onFilterSelected = { selectedFilter = it },
                onFiltersVisibleChanged = { visible ->
                    filtersVisible = visible
                    tvPrefs.edit()
                        .putBoolean(TV_PREF_JELLYFIN_FILTERS_VISIBLE, visible)
                        .apply()
                },
                onPostersPerRowChanged = { count ->
                    postersPerRow = count
                    tvPrefs.edit()
                        .putInt(TV_PREF_JELLYFIN_POSTERS_PER_ROW, count)
                        .apply()
                },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .clipToBounds(),
            ) {
                heroOverlay?.invoke()
            }
        }
    }
}

private fun buildJellyfinManagedRails(
    records: List<JellyfinLibraryMedia>,
    contentType: JellyfinContentType,
    filter: JellyfinViewFilter,
): List<TvContentRail> {
    val scoped = when (contentType) {
        JellyfinContentType.MOVIES -> records.filter { it.media.type == MediaType.MOVIE }
        JellyfinContentType.SERIES -> records.filter { it.media.type == MediaType.SERIES }
        JellyfinContentType.OVERVIEW -> records
    }
    val alphabetical = scoped.sortedBy { it.media.title.lowercase() }
    val recent = scoped.sortedWith(
        compareByDescending<JellyfinLibraryMedia> { it.source.dateCreated.orEmpty() }
            .thenBy { it.media.title.lowercase() },
    )
    val topRated = scoped
        .filter { (it.media.rating ?: 0.0) > 0.0 }
        .sortedByDescending { it.media.rating }
    val continueWatching = scoped
        .filter {
            val userData = it.source.userData
            userData != null && userData.playbackPositionTicks > 0L && !userData.played
        }
        .sortedByDescending { it.source.userData?.playedPercentage ?: 0.0 }
    val unwatched = scoped
        .filter { it.source.userData?.played != true }
        .sortedWith(
            compareByDescending<JellyfinLibraryMedia> { it.source.dateCreated.orEmpty() }
                .thenBy { it.media.title.lowercase() },
        )

    fun rail(key: String, title: String, source: List<JellyfinLibraryMedia>): TvContentRail? {
        val items = source.distinctBy { it.media.id }.map(JellyfinLibraryMedia::media)
        return items.takeIf { it.isNotEmpty() }?.let {
            TvContentRail(key = key, title = title, items = it)
        }
    }

    return buildList {
        when (filter) {
            JellyfinViewFilter.ALL -> when (contentType) {
                JellyfinContentType.OVERVIEW -> {
                    rail("jf_continue_all", "Continue watching", continueWatching.take(30))?.let(::add)
                    rail(
                        "jf_recent_movies",
                        "Recently added movies",
                        recent.filter { it.media.type == MediaType.MOVIE }.take(30),
                    )?.let(::add)
                    rail(
                        "jf_recent_series",
                        "Recently added series",
                        recent.filter { it.media.type == MediaType.SERIES }.take(30),
                    )?.let(::add)
                }
                JellyfinContentType.MOVIES,
                JellyfinContentType.SERIES,
                -> {
                    val suffix = if (contentType == JellyfinContentType.MOVIES) "movies" else "series"
                    val typeLabel = if (contentType == JellyfinContentType.MOVIES) "movies" else "series"
                    rail("jf_continue_" + suffix, "Continue watching", continueWatching.take(30))?.let(::add)
                    rail("jf_recent_" + suffix, "Recently added " + typeLabel, recent.take(30))?.let(::add)
                    rail("jf_top_" + suffix, "Top rated " + typeLabel, topRated.take(30))?.let(::add)
                    rail("jf_unwatched_" + suffix, "Unwatched " + typeLabel, unwatched)?.let(::add)
                    rail("jf_all_" + suffix, "All " + typeLabel + " A–Z", alphabetical)?.let(::add)
                }
            }
            JellyfinViewFilter.CONTINUE ->
                rail("jf_continue_filtered", "Continue watching", continueWatching)?.let(::add)
            JellyfinViewFilter.RECENT -> {
                val title = when (contentType) {
                    JellyfinContentType.OVERVIEW -> "Recently added"
                    JellyfinContentType.MOVIES -> "Recently added movies"
                    JellyfinContentType.SERIES -> "Recently added series"
                }
                rail("jf_recent_filtered", title, recent)?.let(::add)
            }
            JellyfinViewFilter.UNWATCHED ->
                rail("jf_unwatched_filtered", "Unwatched", unwatched)?.let(::add)
            JellyfinViewFilter.TOP_RATED -> {
                if (contentType == JellyfinContentType.OVERVIEW) {
                    rail(
                        "jf_top_movies",
                        "Top rated movies",
                        topRated.filter { it.media.type == MediaType.MOVIE },
                    )?.let(::add)
                    rail(
                        "jf_top_series",
                        "Top rated series",
                        topRated.filter { it.media.type == MediaType.SERIES },
                    )?.let(::add)
                } else {
                    val title = if (contentType == JellyfinContentType.MOVIES) {
                        "Top rated movies"
                    } else {
                        "Top rated series"
                    }
                    rail("jf_top_filtered", title, topRated)?.let(::add)
                }
            }
            JellyfinViewFilter.GENRES -> {
                scoped
                    .flatMap { record ->
                        record.source.genres
                            .filter(String::isNotBlank)
                            .distinct()
                            .map { it to record }
                    }
                    .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                    .entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, List<JellyfinLibraryMedia>>> { it.value.size }
                            .thenBy { it.key },
                    )
                    .take(18)
                    .forEach { (genre, genreItems) ->
                        val key = genre.lowercase().replace(' ', '_')
                        rail(
                            "jf_genre_" + key,
                            genre,
                            genreItems.sortedBy { it.media.title.lowercase() },
                        )?.let(::add)
                    }
            }
            JellyfinViewFilter.DECADES -> {
                scoped
                    .filter { it.media.year != null }
                    .groupBy { (it.media.year!! / 10) * 10 }
                    .toSortedMap(compareByDescending { it })
                    .forEach { (decade, decadeItems) ->
                        rail(
                            key = "jf_decade_$decade",
                            title = "${decade}s",
                            source = decadeItems.sortedByDescending { it.media.year },
                        )?.let(::add)
                    }
            }
            JellyfinViewFilter.STUDIOS -> {
                scoped
                    .flatMap { record ->
                        record.source.studios
                            .map { it.name }
                            .filter(String::isNotBlank)
                            .distinct()
                            .map { it to record }
                    }
                    .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                    .entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, List<JellyfinLibraryMedia>>> { it.value.size }
                            .thenBy { it.key },
                    )
                    .take(18)
                    .forEach { (studio, studioItems) ->
                        val key = studio.lowercase().replace(' ', '_')
                        rail(
                            "jf_studio_" + key,
                            studio,
                            studioItems.sortedBy { it.media.title.lowercase() },
                        )?.let(::add)
                    }
            }
        }

    }
}

@Composable
private fun JellyfinLibraryControlRows(
    contentType: JellyfinContentType,
    selectedFilter: JellyfinViewFilter,
    filtersVisible: Boolean,
    postersPerRow: Int,
    firstFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    itemCount: Int,
    onFocused: () -> Unit,
    onContentTypeSelected: (JellyfinContentType) -> Unit,
    onFilterSelected: (JellyfinViewFilter) -> Unit,
    onFiltersVisibleChanged: (Boolean) -> Unit,
    onPostersPerRowChanged: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(
                start = TV_PAGE_CONTENT_GUTTER,
                end = TV_PAGE_END_GUTTER,
                top = 12.dp,
                bottom = 4.dp,
            ),
        ) {
            itemsIndexed(
                items = JellyfinContentType.entries,
                key = { _, type -> "content_" + type.name },
            ) { index, type ->
                JellyfinCompactPill(
                    label = type.label,
                    selected = contentType == type,
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                        .focusProperties {
                            if (index == 0) left = railFocusRequester
                            headerFocusRequester?.let { up = it }
                        },
                    onFocused = onFocused,
                    onClick = { onContentTypeSelected(type) },
                )
            }
            item(key = "filters_toggle") {
                JellyfinCompactPill(
                    label = if (filtersVisible) "Hide filters" else "Show filters",
                    selected = filtersVisible,
                    modifier = Modifier.focusProperties {
                        headerFocusRequester?.let { up = it }
                    },
                    onFocused = onFocused,
                    onClick = { onFiltersVisibleChanged(!filtersVisible) },
                )
            }
            item(key = "poster_density_label") {
                Text(
                    text = "Posters",
                    color = Silver,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 5.dp, end = 1.dp),
                )
            }
            itemsIndexed(
                items = JELLYFIN_POSTER_COLUMN_OPTIONS,
                key = { _, count -> "posters_" + count },
            ) { _, count ->
                JellyfinCompactPill(
                    label = count.toString(),
                    selected = postersPerRow == count,
                    modifier = Modifier.focusProperties {
                        headerFocusRequester?.let { up = it }
                    },
                    onFocused = onFocused,
                    onClick = { onPostersPerRowChanged(count) },
                )
            }
            item(key = "jellyfin_count") {
                Text(
                    text = itemCount.toString() + " titles",
                    color = Silver,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }

        if (filtersVisible) {
            val availableFilters = contentType.availableFilters()
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(
                    start = TV_PAGE_CONTENT_GUTTER,
                    end = TV_PAGE_END_GUTTER,
                    top = 4.dp,
                    bottom = 8.dp,
                ),
            ) {
                itemsIndexed(
                    items = availableFilters,
                    key = { _, filter -> "filter_" + filter.name },
                ) { index, filter ->
                    JellyfinCompactPill(
                        label = filter.label,
                        selected = selectedFilter == filter,
                        modifier = Modifier.focusProperties {
                            if (index == 0) left = railFocusRequester
                        },
                        onFocused = onFocused,
                        onClick = { onFilterSelected(filter) },
                    )
                }
            }
        }
    }
}

@Composable
private fun JellyfinCompactPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    // The label may change in-place (for example Hide filters -> Show
    // filters). Keep the focus node and interaction source stable so the
    // remote remains on the same control after confirming it.
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val active = selected || focused
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    focused -> Graphite.copy(alpha = 0.55f)
                    selected -> Amber.copy(alpha = 0.14f)
                    else -> Charcoal.copy(alpha = 0.20f)
                },
            )
            .border(
                width = 1.dp,
                color = when {
                    focused -> Amber.copy(alpha = 0.98f)
                    selected -> Amber.copy(alpha = 0.48f)
                    else -> Color.Transparent
                },
                shape = shape,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) Snow else Silver,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
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
