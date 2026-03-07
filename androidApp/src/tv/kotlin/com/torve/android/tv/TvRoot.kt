package com.torve.android.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.torve.android.R
import com.torve.android.sync.SyncCoordinator
import com.torve.android.sync.model.SyncInboundEvent
import com.torve.android.tv.components.TvHeroBackground
import com.torve.android.tv.components.TvHeroOverlay
import com.torve.android.tv.components.TvNavRail
import com.torve.android.tv.nav.TvNavHost
import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.nav.tvTopDestinations
import com.torve.android.tv.screens.TvHomeScreen
import com.torve.android.tv.screens.TvIptvScreen
import com.torve.android.tv.screens.TvIptvRailState
import com.torve.android.tv.screens.TvLibraryScreen
import com.torve.android.tv.screens.TvMoviesScreen
import com.torve.android.tv.screens.TvSearchScreen
import com.torve.android.tv.screens.TvSettingsScreen
import com.torve.android.tv.screens.TvShowsScreen
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.sync.SyncPayload
import com.torve.domain.sync.SyncRepository
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
    val syncRepository: SyncRepository = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
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

    /* ── Notification + sync state ─────────────────────────────────────────────────────── */
    var searchSeedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var activeNotification by remember { mutableStateOf<TvNotification?>(null) }

    /* ── For focus restoration when returning from sub-routes ───────────────────────────── */
    var rootHasFocus by remember { mutableStateOf(false) }
    var focusRestoreTrigger by remember { mutableStateOf(0) }

    val heroRoutes = remember { setOf(TvRoutes.HOME, TvRoutes.MOVIES, TvRoutes.SHOWS, TvRoutes.LIBRARY) }
    val showRail = !isPlayerRoute &&
        !(selectedTopRoute == TvRoutes.IPTV && !isSubRouteActive && hideRailForIptv)
    val showHero = !isPlayerRoute && !isSubRouteActive && selectedTopRoute in heroRoutes

    /* ── Focus restore: try content first, rail is guaranteed fallback ─────────────── */
    // Use a counter (not a boolean) so the effect key doesn't change inside its body,
    // which would cancel the coroutine before delay() completes.
    LaunchedEffect(focusRestoreTrigger) {
        if (focusRestoreTrigger == 0) return@LaunchedEffect
        // Try with increasing delays — newly visited tabs may need more time to compose
        for (attempt in 0..2) {
            delay(if (attempt == 0) 150L else 200L)
            val activeRoute = if (isSubRouteActive) TvRoutes.DETAILS else selectedTopRoute
            val candidates = listOfNotNull(
                lastFocusedContentByRoute[activeRoute],
                firstContentFocusByRoute[activeRoute],
                headerPrimaryActionRequester.takeIf { activeRoute in heroRoutes },
            )
            for (candidate in candidates) {
                try { candidate.requestFocus(); return@LaunchedEffect } catch (_: Throwable) { }
            }
            // If we have no candidates at all yet, retry (tab still composing)
            if (candidates.isEmpty() && attempt < 2) continue
            break
        }
        // Rail is guaranteed fallback
        try { railFocusRequester.requestFocus() } catch (_: Throwable) { }
    }

    /* ── Focus watchdog: if focus is truly lost, put it on the rail ─────────────────── */
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            if (isPlayerRoute || rootHasFocus) continue
            // Focus is lost — wait one more check to avoid false positives
            delay(150)
            if (rootHasFocus) continue
            // Still lost — force focus to rail (always safe)
            try { railFocusRequester.requestFocus() } catch (_: Throwable) { }
        }
    }

    /* ── Sub-route transitions trigger focus restore ───────────────────────────────── */
    LaunchedEffect(isSubRouteActive) {
        if (!isSubRouteActive) {
            // Clear stale sub-route focus entries when returning to tab
            firstContentFocusByRoute.remove(TvRoutes.DETAILS)
            lastFocusedContentByRoute.remove(TvRoutes.DETAILS)
        }
        focusRestoreTrigger++
    }

    /* ── Track visited tabs ────────────────────────────────────────────────────────────── */
    LaunchedEffect(selectedTopRoute) {
        visitedTabs = visitedTabs + selectedTopRoute
        focusedMediaItem = null
        // Only restore focus to content when NOT browsing the rail.
        // Rail browsing changes tabs via onItemFocused; focus should stay on the rail item.
        if (!isRailFocused) {
            focusRestoreTrigger++
        }
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
        syncCoordinator.inboundEvents.collect { event ->
            when (event) {
                is SyncInboundEvent.SearchPush -> {
                    searchSeedQuery = event.query
                    TvNotificationQueue.post(stringResource_sync_search)
                    selectedTopRoute = TvRoutes.SEARCH
                    if (isSubRouteActive) {
                        navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                    }
                }

                is SyncInboundEvent.PlaybackIntent -> {
                    val detailId = event.contentId.toIntOrNull() ?: return@collect
                    val detailType = if (event.mediaType == "tv") "tv" else "movie"
                    TvNotificationQueue.post(stringResource_sync_playback)
                    navController.navigate(
                        TvRoutes.details(
                            type = detailType,
                            id = detailId,
                            autoPlay = true,
                            handoffPositionMs = event.positionMs.coerceAtLeast(0L),
                        ),
                    ) { launchSingleTop = true }
                }

                is SyncInboundEvent.SettingsPush -> {
                    val syncJson = Json { ignoreUnknownKeys = true }
                    val payload = try {
                        syncJson.decodeFromString<SyncPayload>(event.payloadJson)
                    } catch (e: Exception) {
                        Log.e("TvRoot", "Failed to decode settings payload", e)
                        TvNotificationQueue.post("Invalid settings data: ${e.message}", NotificationType.ERROR)
                        null
                    }
                    if (payload != null) {
                        Log.d("TvRoot", "Settings payload: ${payload.addons.size} addons, ${payload.preferences.size} prefs, ${payload.channelPlaylists.size} playlists, ${payload.channelFavorites.size} favorites, ${payload.watchProgress.size} progress, ${payload.integrationSecrets.size} secrets")
                        val result = try {
                            withContext(Dispatchers.IO) {
                                syncRepository.importSyncPayload(payload)
                            }
                        } catch (e: Exception) {
                            Log.e("TvRoot", "Failed to import settings", e)
                            TvNotificationQueue.post("Sync import failed: ${e.message}", NotificationType.ERROR)
                            null
                        }
                        if (result != null) {
                            Log.d("TvRoot", "Import result: $result")
                            // Refresh SettingsViewModel so TV UI reflects synced integrations
                            settingsViewModel.refreshSettings()
                            val parts = buildList {
                                if (result.addonsImported > 0) add("${result.addonsImported} addons")
                                if (result.preferencesImported > 0) add("${result.preferencesImported} prefs")
                                if (result.secretsImported > 0) add("${result.secretsImported} integrations")
                                if (result.playlistsImported > 0) add("${result.playlistsImported} playlists")
                                if (result.favoritesImported > 0) add("${result.favoritesImported} favorites")
                                if (result.progressImported > 0) add("${result.progressImported} progress")
                            }
                            val summary = if (parts.isNotEmpty()) parts.joinToString(", ") else "no changes"
                            TvNotificationQueue.post("Settings synced: $summary")
                        }
                    }
                }
            }
        }
    }

    // Surface sync errors as notifications
    LaunchedEffect(syncState.error) {
        syncState.error?.let { err ->
            TvNotificationQueue.post("Sync error: $err", NotificationType.ERROR)
        }
    }

    // Download completion observer via WorkManager (poll every 5s)
    val context = androidx.compose.ui.platform.LocalContext.current
    var lastCompletedCount by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        val workManager = androidx.work.WorkManager.getInstance(context)
        while (true) {
            delay(5_000)
            try {
                val infos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    workManager.getWorkInfosByTag("download_work").get()
                }
                val completed = infos.count { it.state == androidx.work.WorkInfo.State.SUCCEEDED }
                if (lastCompletedCount >= 0 && completed > lastCompletedCount) {
                    val newFinished = completed - lastCompletedCount
                    TvNotificationQueue.post("$newFinished download(s) completed", NotificationType.SUCCESS)
                }
                lastCompletedCount = completed
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    // Notification collector — show each notification for 2.4s then clear
    LaunchedEffect(Unit) {
        TvNotificationQueue.events.collectLatest { notification ->
            activeNotification = notification
            delay(2400)
            activeNotification = null
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

    // First-run hint: show a welcome notification on first launch
    LaunchedEffect(Unit) {
        val tvPrefs = context.getSharedPreferences("tv_prefs", android.content.Context.MODE_PRIVATE)
        val hasLaunched = tvPrefs.getBoolean("tv_has_launched", false)
        if (!hasLaunched) {
            tvPrefs.edit().putBoolean("tv_has_launched", true).apply()
            TvNotificationQueue.post("Welcome to Torve TV! Use the left rail to navigate.", NotificationType.INFO)
        }
    }

    /* ── Scaffold ──────────────────────────────────────────────────────────────────────── */
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { rootHasFocus = it.hasFocus },
    ) {
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
                    onMoveToContent = { focusRestoreTrigger++ },
                    onNavigate = { route ->
                        // Pop any active sub-route first
                        if (isSubRouteActive) {
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                        }
                        // Add to visitedTabs synchronously so the tab renders
                        // in the same frame as the route change (no blank frame).
                        visitedTabs = visitedTabs + route
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
                                    .zIndex(if (isActiveTab) 1f else 0f)
                                    .alpha(if (isActiveTab) 1f else 0f)
                                    // Move inactive tabs off-screen so focus search can't find them.
                                    // Content stays composed (keep-alive) but invisible + unreachable.
                                    .offset(x = if (isActiveTab) 0.dp else 10000.dp)
                                    .onPreviewKeyEvent { !isActiveTab }
                                    // Block focus from entering inactive tabs during spatial search
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
                // When no sub-route is active, block focus from entering the empty NavHost
                // so it cannot steal focus from the keep-alive tab screens.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (isSubRouteActive) 2f else -1f)
                        .alpha(if (isSubRouteActive) 1f else 0f)
                        .focusProperties {
                            if (!isSubRouteActive) {
                                enter = { FocusRequester.Cancel }
                            }
                        },
                ) {
                    TvNavHost(
                        navController = navController,
                        railFocusRequester = railFocusRequester,
                        onVoiceSearchQuery = { query ->
                            searchSeedQuery = query
                            TvNotificationQueue.post("Search: $query")
                            selectedTopRoute = TvRoutes.SEARCH
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                        },
                        onSettingsClick = {
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                            selectedTopRoute = TvRoutes.SETTINGS
                        },
                        onFirstContentRequester = { req ->
                            firstContentFocusByRoute[TvRoutes.DETAILS] = req
                        },
                        onContentFocused = { req ->
                            lastFocusedContentByRoute[TvRoutes.DETAILS] = req
                        },
                    )
                }

            }
        },
    )

    /* ── Notification overlay — above everything (hero, rail, content) ──────── */
    activeNotification?.let { notification ->
        val borderColor = when (notification.type) {
            NotificationType.SUCCESS -> Emerald
            NotificationType.ERROR -> Ruby
            NotificationType.INFO -> AmberSubtle
        }
        Box(
            modifier = Modifier
                .zIndex(100f)
                .padding(top = 18.dp, end = 20.dp)
                .align(Alignment.TopEnd)
                .background(Charcoal.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text = notification.message, color = Snow)
        }
    }
    } // end rootHasFocus Box
}

// Sync notice constants (cannot use stringResource in non-composable scope)
private const val stringResource_sync_search = "Search received from phone"
private const val stringResource_sync_playback = "Playback handoff received"
