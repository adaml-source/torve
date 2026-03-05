package com.streamvault.android.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streamvault.android.R
import com.streamvault.android.sync.SyncCoordinator
import com.streamvault.android.sync.model.SyncInboundEvent
import com.streamvault.android.tv.components.TvHeroBackground
import com.streamvault.android.tv.components.TvHeroOverlay
import com.streamvault.android.tv.components.TvNavRail
import com.streamvault.android.tv.nav.TvNavHost
import com.streamvault.android.tv.nav.TvRoutes
import com.streamvault.android.tv.nav.tvTopDestinations
import com.streamvault.android.tv.screens.TvHomeScreen
import com.streamvault.android.tv.screens.TvIptvScreen
import com.streamvault.android.tv.screens.TvIptvRailState
import com.streamvault.android.tv.screens.TvLibraryScreen
import com.streamvault.android.tv.screens.TvMoviesScreen
import com.streamvault.android.tv.screens.TvSearchScreen
import com.streamvault.android.tv.screens.TvSettingsScreen
import com.streamvault.android.tv.screens.TvShowsScreen
import com.streamvault.android.ui.theme.AmberSubtle
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Snow
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

/**
 * Module-level in-memory cache so screen data survives recomposition
 * and tab switching without network re-fetches.
 */
internal object TvScreenCache {
    private val data = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = data[key] as? T
    fun put(key: String, value: Any) { data[key] = value }
}

private fun NavHostController.navigateToTvDetails(item: MediaItem, autoPlay: Boolean = false) {
    val id = item.tmdbId ?: item.id.toIntOrNull() ?: return
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    navigate(TvRoutes.details(type = type, id = id, autoPlay = autoPlay))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvRoot() {
    val navController = rememberNavController()
    val metadataRepo: MetadataRepository = koinInject()
    val watchlistViewModel: WatchlistViewModel = koinInject()
    val syncCoordinator: SyncCoordinator = koinInject()
    val syncState by syncCoordinator.state.collectAsState()

    /* ── Sub-route tracking (NavHost only handles details/player/see-all/sub-screens) ── */
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentSubRoute = navBackStackEntry?.destination?.route
    val isSubRouteActive = currentSubRoute != null && currentSubRoute != TvRoutes.SUB_NAV_START
    val isPlayerRoute = currentSubRoute?.startsWith("tv_player") == true ||
        currentSubRoute?.startsWith("tv_live_player") == true
    val hideRailForIptv by TvIptvRailState.hideRail

    /* ── Tab state ─────────────────────────────────────────────────────────────────────── */
    var selectedTopRoute by rememberSaveable { mutableStateOf(TvRoutes.HOME) }
    var visitedTabs by remember { mutableStateOf(setOf(TvRoutes.HOME)) }

    /* ── Focus state ───────────────────────────────────────────────────────────────────── */
    val railFocusRequester = remember { FocusRequester() }
    val headerPrimaryActionRequester = remember { FocusRequester() }
    val firstContentFocusByRoute = remember { mutableStateMapOf<String, FocusRequester>() }
    val lastFocusedContentByRoute = remember { mutableStateMapOf<String, FocusRequester>() }
    var isRailExpanded by rememberSaveable { mutableStateOf(false) }
    var isRailFocused by rememberSaveable { mutableStateOf(false) }
    var focusedMediaItem by remember { mutableStateOf<MediaItem?>(null) }

    /* ── Sync state ────────────────────────────────────────────────────────────────────── */
    var searchSeedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var syncNotice by remember { mutableStateOf<String?>(null) }

    /* ── For focus restoration when returning from sub-routes ───────────────────────────── */
    var wasSubRouteActive by remember { mutableStateOf(false) }

    val heroRoutes = remember { setOf(TvRoutes.HOME, TvRoutes.MOVIES, TvRoutes.SHOWS, TvRoutes.LIBRARY) }
    val showRail = !isPlayerRoute &&
        !(selectedTopRoute == TvRoutes.IPTV && !isSubRouteActive && hideRailForIptv)
    val showHero = !isPlayerRoute && !isSubRouteActive && selectedTopRoute in heroRoutes

    fun moveRailFocusToContent() {
        val route = selectedTopRoute
        val last = lastFocusedContentByRoute[route]
        val first = firstContentFocusByRoute[route]
        val focusedLast = try {
            last?.requestFocus()
            last != null
        } catch (_: IllegalStateException) {
            false
        }
        if (!focusedLast) {
            try {
                first?.requestFocus()
            } catch (_: IllegalStateException) {
                // FocusRequester not yet attached — ignore
            }
        }
    }

    /* ── Initial focus on the rail ─────────────────────────────────────────────────────── */
    LaunchedEffect(Unit) {
        // Focus nodes can attach a few frames after first composition on TV startup.
        repeat(20) {
            val focusedRail = try {
                railFocusRequester.requestFocus()
                true
            } catch (_: IllegalStateException) {
                false
            }
            val focusedContent = if (!focusedRail) {
                try {
                    firstContentFocusByRoute[selectedTopRoute]?.requestFocus()
                    firstContentFocusByRoute[selectedTopRoute] != null
                } catch (_: IllegalStateException) {
                    false
                }
            } else {
                false
            }
            if (focusedRail || focusedContent) return@LaunchedEffect
            delay(50)
        }
    }

    /* ── Focus restoration when returning from sub-routes ──────────────────────────────── */
    LaunchedEffect(isSubRouteActive) {
        if (!isSubRouteActive && wasSubRouteActive) {
            delay(100) // Let composition settle
            val last = lastFocusedContentByRoute[selectedTopRoute]
            val first = firstContentFocusByRoute[selectedTopRoute]
            val focusedLast = try {
                last?.requestFocus()
                last != null
            } catch (_: IllegalStateException) {
                false
            }
            if (!focusedLast) {
                try {
                    first?.requestFocus()
                } catch (_: IllegalStateException) { /* not yet attached */ }
            }
        }
        wasSubRouteActive = isSubRouteActive
    }

    /* ── Track visited tabs ────────────────────────────────────────────────────────────── */
    LaunchedEffect(selectedTopRoute) {
        visitedTabs = visitedTabs + selectedTopRoute
        focusedMediaItem = null
    }

    /* ── Back handler: non-HOME tab → go to HOME; on HOME → exit ───────────────────────── */
    BackHandler(enabled = selectedTopRoute != TvRoutes.HOME && !isSubRouteActive && selectedTopRoute != TvRoutes.IPTV) {
        selectedTopRoute = TvRoutes.HOME
    }

    /* ── Sync listeners ────────────────────────────────────────────────────────────────── */
    LaunchedEffect(syncState.isAuthenticated) {
        if (syncState.isAuthenticated && syncState.devices.isEmpty()) {
            syncCoordinator.refreshDevices()
        }
    }

    LaunchedEffect(Unit) {
        syncCoordinator.inboundEvents.collectLatest { event ->
            when (event) {
                is SyncInboundEvent.SearchPush -> {
                    searchSeedQuery = event.query
                    syncNotice = stringResource_sync_search
                    selectedTopRoute = TvRoutes.SEARCH
                    if (isSubRouteActive) {
                        navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                    }
                }

                is SyncInboundEvent.PlaybackIntent -> {
                    val detailId = event.contentId.toIntOrNull() ?: return@collectLatest
                    val detailType = if (event.mediaType == "tv") "tv" else "movie"
                    syncNotice = stringResource_sync_playback
                    navController.navigate(
                        TvRoutes.details(
                            type = detailType,
                            id = detailId,
                            autoPlay = true,
                            handoffPositionMs = event.positionMs.coerceAtLeast(0L),
                        ),
                    ) { launchSingleTop = true }
                }
            }
        }
    }

    LaunchedEffect(syncNotice) {
        if (syncNotice != null) {
            delay(2400)
            syncNotice = null
        }
    }

    /* ── Featured hero item ────────────────────────────────────────────────────────────── */
    val featuredCacheKey = "featured:$selectedTopRoute"
    val featuredItem by produceState<MediaItem?>(
        initialValue = TvScreenCache.get<MediaItem>(featuredCacheKey),
        selectedTopRoute,
        metadataRepo,
    ) {
        val loaded = try {
            when (selectedTopRoute) {
                TvRoutes.MOVIES -> metadataRepo.getTrending("movie").firstOrNull()
                TvRoutes.SHOWS -> metadataRepo.getTrending("tv").firstOrNull()
                TvRoutes.HOME -> metadataRepo.getPopular("movie").firstOrNull()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
        if (loaded != null) {
            TvScreenCache.put(featuredCacheKey, loaded)
        }
        value = loaded ?: TvScreenCache.get(featuredCacheKey)
    }

    val displayedFeaturedItem = focusedMediaItem?.takeIf { item ->
        !item.backdropUrl.isNullOrBlank() || !item.posterUrl.isNullOrBlank()
    } ?: featuredItem

    /* ── Section titles ────────────────────────────────────────────────────────────────── */
    val sectionTitle = when (selectedTopRoute) {
        TvRoutes.MOVIES -> stringResource(R.string.nav_movies)
        TvRoutes.SHOWS -> stringResource(R.string.nav_tv_shows)
        TvRoutes.IPTV -> stringResource(R.string.tv_nav_iptv)
        TvRoutes.SEARCH -> stringResource(R.string.tv_nav_search)
        TvRoutes.LIBRARY -> stringResource(R.string.tv_nav_library)
        TvRoutes.SETTINGS -> stringResource(R.string.tv_nav_settings)
        else -> stringResource(R.string.nav_home)
    }
    val sectionSubtitle = when (selectedTopRoute) {
        TvRoutes.MOVIES -> stringResource(R.string.tv_hero_subtitle_movies)
        TvRoutes.SHOWS -> stringResource(R.string.tv_hero_subtitle_shows)
        TvRoutes.IPTV -> stringResource(R.string.tv_hero_subtitle_iptv)
        TvRoutes.SEARCH -> stringResource(R.string.tv_hero_subtitle_search)
        TvRoutes.LIBRARY -> stringResource(R.string.tv_hero_subtitle_library)
        TvRoutes.SETTINGS -> stringResource(R.string.tv_hero_subtitle_settings)
        else -> stringResource(R.string.tv_hero_subtitle_home)
    }

    /* ── Navigation helpers ────────────────────────────────────────────────────────────── */
    val navigateToSeeAll: (String, String, String) -> Unit = remember {
        { railKey: String, title: String, mediaType: String ->
            navController.navigate(
                TvRoutes.seeAll(railKey = railKey, mediaType = mediaType, title = title),
            )
        }
    }

    /* ── Scaffold ──────────────────────────────────────────────────────────────────────── */
    TvScaffold(
        isFullscreen = isPlayerRoute || !showRail,
        leftRail = {
            if (showRail) {
                TvNavRail(
                    destinations = tvTopDestinations,
                    selectedRoute = selectedTopRoute,
                    isExpanded = isRailExpanded,
                    railFocusRequester = railFocusRequester,
                    onRailFocusChanged = { hasFocus ->
                        isRailFocused = hasFocus
                        isRailExpanded = hasFocus
                    },
                    onMoveToContent = { moveRailFocusToContent() },
                    onNavigate = { route ->
                        // Pop any active sub-route first
                        if (isSubRouteActive) {
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                        }
                        // Just update state — NO navController.navigate()
                        selectedTopRoute = route
                    },
                )
            }
        },
        background = {
            if (showHero) {
                TvHeroBackground(featuredItem = displayedFeaturedItem)
            }
        },
        content = {
            Box(modifier = Modifier.fillMaxSize()) {
                /* ── Layer 1: Keep-alive tab screens ───────────────────────── */
                val topRoutes = remember {
                    listOf(
                        TvRoutes.HOME, TvRoutes.MOVIES, TvRoutes.SHOWS,
                        TvRoutes.IPTV, TvRoutes.SEARCH, TvRoutes.LIBRARY, TvRoutes.SETTINGS,
                    )
                }

                topRoutes.forEach { route ->
                    if (route in visitedTabs) {
                        val isActiveTab = route == selectedTopRoute && !isSubRouteActive

                        key(route) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // Active tab on top so focus system evaluates it first
                                    .zIndex(if (isActiveTab) 1f else 0f)
                                    .alpha(if (isActiveTab) 1f else 0f)
                                    // Safety net: inactive keep-alive tabs must never consume d-pad actions.
                                    .onPreviewKeyEvent { !isActiveTab }
                                    .focusProperties {
                                        if (!isActiveTab) {
                                            enter = { FocusRequester.Cancel }
                                        }
                                    }
                                    .focusGroup(),
                            ) {
                                // Only the active tab gets the hero overlay (avoids shared FocusRequester conflicts)
                                val tabHeroOverlay: (@Composable () -> Unit)? =
                                    if (isActiveTab && showHero && route in heroRoutes) {
                                        {
                                            val heroItem = displayedFeaturedItem
                                            val heroInWatchlist = heroItem?.let {
                                                watchlistViewModel.isInWatchlist(it.id)
                                            } == true
                                            TvHeroOverlay(
                                                featuredItem = heroItem,
                                                sectionTitle = sectionTitle,
                                                subtitle = sectionSubtitle,
                                                primaryActionFocusRequester = headerPrimaryActionRequester,
                                                railFocusRequester = railFocusRequester,
                                                onPlayFeatured = {
                                                    heroItem?.let {
                                                        navController.navigateToTvDetails(it, autoPlay = true)
                                                    }
                                                },
                                                onOpenFeatured = {
                                                    heroItem?.let {
                                                        navController.navigateToTvDetails(it)
                                                    }
                                                },
                                                isInWatchlist = heroInWatchlist,
                                                onWatchlistToggle = {
                                                    heroItem?.let {
                                                        watchlistViewModel.toggleWatchlist(it)
                                                    }
                                                },
                                            )
                                        }
                                    } else {
                                        null
                                    }

                                when (route) {
                                    TvRoutes.HOME -> TvHomeScreen(
                                        railFocusRequester = railFocusRequester,
                                        headerFocusRequester = headerPrimaryActionRequester,
                                        onMediaClick = { item -> navController.navigateToTvDetails(item) },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.HOME] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.HOME] = it },
                                        onMediaFocused = { focusedMediaItem = it },
                                        onSeeAll = { railKey, title ->
                                            val mt = when {
                                                railKey.contains("movie") -> "movie"
                                                railKey.contains("show") || railKey.contains("tv") -> "tv"
                                                else -> "movie"
                                            }
                                            navigateToSeeAll(railKey, title, mt)
                                        },
                                        heroOverlay = tabHeroOverlay,
                                        shouldAutoFocus = false,
                                    )

                                    TvRoutes.MOVIES -> TvMoviesScreen(
                                        railFocusRequester = railFocusRequester,
                                        headerFocusRequester = headerPrimaryActionRequester,
                                        heroOverlay = tabHeroOverlay,
                                        onMediaClick = { item -> navController.navigateToTvDetails(item) },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.MOVIES] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.MOVIES] = it },
                                        onMediaFocused = { focusedMediaItem = it },
                                        onSeeAll = { railKey, title -> navigateToSeeAll(railKey, title, "movie") },
                                        shouldAutoFocus = false,
                                    )

                                    TvRoutes.SHOWS -> TvShowsScreen(
                                        railFocusRequester = railFocusRequester,
                                        headerFocusRequester = headerPrimaryActionRequester,
                                        heroOverlay = tabHeroOverlay,
                                        onMediaClick = { item -> navController.navigateToTvDetails(item) },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.SHOWS] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.SHOWS] = it },
                                        onMediaFocused = { focusedMediaItem = it },
                                        onSeeAll = { railKey, title -> navigateToSeeAll(railKey, title, "tv") },
                                        shouldAutoFocus = false,
                                    )

                                    TvRoutes.IPTV -> TvIptvScreen(
                                        railFocusRequester = railFocusRequester,
                                        onChannelPlay = { channel ->
                                            navController.navigate(
                                                TvRoutes.livePlayer(
                                                    channelUrl = channel.url,
                                                    channelName = channel.name,
                                                    groupName = channel.groupTitle.orEmpty(),
                                                ),
                                            ) {
                                                launchSingleTop = true
                                            }
                                        },
                                        onOpenEpgSettings = { selectedTopRoute = TvRoutes.SETTINGS },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.IPTV] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.IPTV] = it },
                                        shouldAutoFocus = false,
                                        isActive = isActiveTab,
                                        isRailFocused = isRailFocused,
                                        isRailExpanded = isRailExpanded,
                                        onCollapseRail = { isRailExpanded = false },
                                        onNavigateUp = { selectedTopRoute = TvRoutes.HOME },
                                    )

                                    TvRoutes.SEARCH -> TvSearchScreen(
                                        railFocusRequester = railFocusRequester,
                                        initialQuery = searchSeedQuery.orEmpty(),
                                        onMediaClick = { item -> navController.navigateToTvDetails(item) },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.SEARCH] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.SEARCH] = it },
                                        shouldAutoFocus = false,
                                    )

                                    TvRoutes.LIBRARY -> TvLibraryScreen(
                                        railFocusRequester = railFocusRequester,
                                        headerFocusRequester = headerPrimaryActionRequester,
                                        heroOverlay = tabHeroOverlay,
                                        onMediaClick = { item -> navController.navigateToTvDetails(item) },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.LIBRARY] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.LIBRARY] = it },
                                        onMediaFocused = { focusedMediaItem = it },
                                        onSeeAll = { railKey, title ->
                                            val mt = if (railKey.contains("movie")) "movie" else "tv"
                                            navigateToSeeAll(railKey, title, mt)
                                        },
                                        shouldAutoFocus = false,
                                    )

                                    TvRoutes.SETTINGS -> TvSettingsScreen(
                                        railFocusRequester = railFocusRequester,
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.SETTINGS] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.SETTINGS] = it },
                                        onNavigateToHomeLayout = { navController.navigate(TvRoutes.HOME_LAYOUT) },
                                        onNavigateToRatings = { navController.navigate(TvRoutes.RATINGS_SETTINGS) },
                                        isActive = isActiveTab,
                                    )
                                }
                            }
                        }
                    }
                }

                /* ── Layer 2: Sub-route NavHost (details, player, see-all, sub-screens) ── */
                TvNavHost(
                    navController = navController,
                    railFocusRequester = railFocusRequester,
                    onVoiceSearchQuery = { query ->
                        searchSeedQuery = query
                        syncNotice = "Search: $query"
                        selectedTopRoute = TvRoutes.SEARCH
                        navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                    },
                )

                /* ── Sync notice overlay ─────────────────────────────────────────── */
                syncNotice?.let { notice ->
                    Box(
                        modifier = Modifier
                            .padding(top = 18.dp, end = 20.dp)
                            .align(Alignment.TopEnd)
                            .background(Charcoal.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                            .border(1.dp, AmberSubtle, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(text = notice, color = Snow)
                    }
                }
            }
        },
    )
}

// Sync notice constants (cannot use stringResource in non-composable scope)
private const val stringResource_sync_search = "Search received from phone"
private const val stringResource_sync_playback = "Playback handoff received"
