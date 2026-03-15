package com.torve.android.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.torve.android.R
import com.torve.android.premium.rememberEffectivePremiumAccessTier
import com.torve.android.sync.SyncCoordinator
import com.torve.android.sync.model.SyncInboundEvent
import com.torve.android.tv.components.TvFocusDetailsPanel
import com.torve.android.tv.components.TvHeroBackground
import com.torve.android.tv.components.TvHeroOverlay
import com.torve.android.tv.components.TvLifetimeUnlockDialog
import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.android.tv.components.TvNavRail
import com.torve.android.tv.nav.TvNavHost
import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.nav.tvTopDestinations
import com.torve.android.tv.premium.AccessTier
import com.torve.android.tv.premium.TvEntitledFeature
import com.torve.android.tv.premium.TvPremiumAccess
import com.torve.android.tv.screens.TvHomeScreen
import com.torve.android.tv.screens.TvIptvScreen
import com.torve.android.tv.screens.TvIptvRailState
import com.torve.android.tv.screens.TvLibraryScreen
import com.torve.android.tv.screens.TvMoviesScreen
import com.torve.android.tv.screens.TvSearchScreen
import com.torve.android.tv.screens.TvManageDevicesScreen
import com.torve.android.tv.screens.TvPairedDevicesScreen
import com.torve.android.tv.screens.TvSettingsScreen
import com.torve.android.tv.screens.TvShowsScreen
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.sync.SyncPayload
import com.torve.domain.sync.SyncRepository
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

/** Sub-destinations within the Settings tab — single source of truth. */
internal enum class TvSettingsDestination {
    MAIN,
    PAIRED_DEVICES,
    ACTIVATED_DEVICES,
}

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
    com.torve.android.debug.AnrDebugLogger.log("STARTUP TvRoot composable entered")
    val navController = rememberNavController()
    val context = LocalContext.current
    val rootScope = rememberCoroutineScope()

    // Resolve heavy singletons off the main thread so the first frame renders
    // instantly.  The background pre-warm in TorveApp usually wins the race,
    // but this guarantees the main thread never blocks on DB/VM creation.
    data class RootDeps(
        val metadataRepo: MetadataRepository,
        val watchlistViewModel: WatchlistViewModel,
        val watchProgressRepo: WatchProgressRepository,
        val syncCoordinator: SyncCoordinator,
        val syncRepository: SyncRepository,
        val settingsViewModel: SettingsViewModel,
        val channelsViewModel: ChannelsViewModel,
        val subscriptionViewModel: SubscriptionViewModel,
    )

    val deps by produceState<RootDeps?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val koin = org.koin.java.KoinJavaComponent.getKoin()
            RootDeps(
                metadataRepo = koin.get(),
                watchlistViewModel = koin.get(),
                watchProgressRepo = koin.get(),
                syncCoordinator = koin.get(),
                syncRepository = koin.get(),
                settingsViewModel = koin.get(),
                channelsViewModel = koin.get(),
                subscriptionViewModel = koin.get(),
            )
        }
    }

    // Show a lightweight shell while singletons initialize on the background thread.
    if (deps == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(com.torve.android.ui.theme.Obsidian),
        )
        return
    }

    val metadataRepo = deps!!.metadataRepo
    val watchlistViewModel = deps!!.watchlistViewModel
    val watchProgressRepo = deps!!.watchProgressRepo
    val syncCoordinator = deps!!.syncCoordinator
    val syncRepository = deps!!.syncRepository
    val settingsViewModel = deps!!.settingsViewModel
    val channelsViewModel = deps!!.channelsViewModel
    val subscriptionViewModel = deps!!.subscriptionViewModel

    val syncState by syncCoordinator.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val accessTier = rememberEffectivePremiumAccessTier(subscriptionState.isPro)
    val tvPrefs = remember(context) {
        context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
    }

    /* ── Sub-route tracking (NavHost only handles details/player/see-all/sub-screens) ── */
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentSubRoute = navBackStackEntry?.destination?.route
    val isSubRouteActive = currentSubRoute != null && currentSubRoute != TvRoutes.SUB_NAV_START
    Log.d("TvRoot", "currentSubRoute=$currentSubRoute isSubRouteActive=$isSubRouteActive")
    val isPlayerRoute = currentSubRoute?.startsWith("tv_player") == true ||
        currentSubRoute?.startsWith("tv_live_player") == true
    val hideRailForIptv by TvIptvRailState.hideRail

    /* ── Tab state ─────────────────────────────────────────────────────────────────────── */
    // Top-level destination state:
    // - highlightedTopRoute: current rail hover/focus target
    // - selectedTopRoute: previewed content shown in main pane
    // - confirmedTopRoute: route explicitly confirmed by Center/Enter or Right
    var highlightedTopRoute by rememberSaveable { mutableStateOf(TvRoutes.HOME) }
    var selectedTopRoute by rememberSaveable { mutableStateOf(TvRoutes.HOME) }
    var confirmedTopRoute by rememberSaveable { mutableStateOf(TvRoutes.HOME) }
    var visitedTabs by remember { mutableStateOf(setOf(TvRoutes.HOME)) }
    var settingsDestination by remember { mutableStateOf(TvSettingsDestination.MAIN) }
    var openSettingsToChannels by remember { mutableStateOf(false) }
    var pendingUnlockFeature by remember { mutableStateOf<TvEntitledFeature?>(null) }
    val requestLifetimeUnlock: (TvEntitledFeature) -> Unit = remember {
        { feature -> pendingUnlockFeature = feature }
    }

    /* ── Focus state ───────────────────────────────────────────────────────────────────── */
    LaunchedEffect(syncState.blockedFeature) {
        val blockedFeature = syncState.blockedFeature ?: return@LaunchedEffect
        requestLifetimeUnlock(blockedFeature)
        syncCoordinator.clearBlockedFeature()
    }

    val railFocusRequester = remember { FocusRequester() }
    val headerPrimaryActionRequester = remember { FocusRequester() }
    val firstContentFocusByRoute = remember { mutableStateMapOf<String, FocusRequester>() }
    val lastFocusedContentByRoute = remember { mutableStateMapOf<String, FocusRequester>() }
    var isRailExpanded by rememberSaveable { mutableStateOf(false) }
    var isRailFocused by rememberSaveable { mutableStateOf(false) }
    var focusedMediaItem by remember { mutableStateOf<MediaItem?>(null) }
    var favoriteMediaKeys by remember {
        mutableStateOf(tvPrefs.getStringSet(TV_PREF_KEY_MEDIA_FAVORITES, emptySet())?.toSet() ?: emptySet())
    }
    val progressByMediaId = remember { mutableStateMapOf<String, Float>() }

    /* ── Clearlogo cache: lazy-fetch logos for focused browse items ─────────────────── */
    val logoCache = remember { mutableStateMapOf<String, String?>() }
    val logoLoadingByKey = remember { mutableStateMapOf<String, Boolean>() }

    val onBrowseMediaFocused: (MediaItem) -> Unit = remember(logoCache, logoLoadingByKey) {
        { item ->
            val tmdbId = item.tmdbId
            if (tmdbId == null) {
                focusedMediaItem = item
            } else {
                val cacheKey = "${item.type}:$tmdbId"
                when {
                    !item.logoUrl.isNullOrBlank() -> {
                        focusedMediaItem = item
                        logoCache[cacheKey] = item.logoUrl
                        logoLoadingByKey.remove(cacheKey)
                    }
                    logoCache[cacheKey] != null -> {
                        focusedMediaItem = item.copy(logoUrl = logoCache[cacheKey])
                        logoLoadingByKey.remove(cacheKey)
                    }
                    cacheKey in logoCache -> {
                        // Known-missing logo; avoid repeated loading state churn.
                        focusedMediaItem = item
                        logoLoadingByKey.remove(cacheKey)
                    }
                    cacheKey !in logoCache -> {
                        focusedMediaItem = item
                        // Mark immediately so the panel can avoid text-first flash.
                        logoLoadingByKey[cacheKey] = true
                    }
                }
            }
        }
    }

    LaunchedEffect(focusedMediaItem?.let { "${it.type}:${it.tmdbId}" }) {
        val item = focusedMediaItem ?: return@LaunchedEffect
        val tmdbId = item.tmdbId ?: return@LaunchedEffect
        // Already has a logo from the detail endpoint
        if (!item.logoUrl.isNullOrBlank()) {
            logoCache["${item.type}:$tmdbId"] = item.logoUrl
            return@LaunchedEffect
        }
        val cacheKey = "${item.type}:$tmdbId"
        // Already resolved (could be null = no logo available)
        if (cacheKey in logoCache) {
            val cached = logoCache[cacheKey]
            if (cached != null && focusedMediaItem?.tmdbId == tmdbId) {
                focusedMediaItem = item.copy(logoUrl = cached)
            }
            logoLoadingByKey.remove(cacheKey)
            return@LaunchedEffect
        }
        logoLoadingByKey[cacheKey] = true
        try {
            // Short debounce: keeps network usage controlled without delaying UI as much.
            delay(80)
            // Check if focus already moved on
            if (focusedMediaItem?.tmdbId != tmdbId) return@LaunchedEffect
            val type = if (item.type == MediaType.MOVIE) "movie" else "tv"
            val url = withContext(Dispatchers.IO) { metadataRepo.getLogoUrl(type, tmdbId) }
            logoCache[cacheKey] = url
            // Only update if still the focused item
            if (focusedMediaItem?.tmdbId == tmdbId && url != null) {
                focusedMediaItem = focusedMediaItem?.copy(logoUrl = url)
            }
        } finally {
            logoLoadingByKey.remove(cacheKey)
        }
    }

    /* ── Notification + sync state ─────────────────────────────────────────────────────── */
    LaunchedEffect(Unit) {
        val persistedProgress = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { watchProgressRepo.getAllProgress() }.getOrDefault(emptyList())
        }
        for (entry in persistedProgress) {
            progressByMediaId[entry.mediaId] = entry.progressPercent.coerceIn(0f, 1f)
        }
    }

    var searchSeedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var activeNotification by remember { mutableStateOf<TvNotification?>(null) }

    /* ── For focus restoration when returning from sub-routes ───────────────────────────── */
    var rootHasFocus by remember { mutableStateOf(false) }
    var focusRestoreTrigger by remember { mutableStateOf(0) }
    var pendingContentEntryRoute by remember { mutableStateOf<String?>(null) }

    val heroRoutes = remember { setOf(TvRoutes.HOME, TvRoutes.MOVIES, TvRoutes.SHOWS, TvRoutes.LIBRARY) }
    val infoPanelRoutes = remember { setOf(TvRoutes.HOME, TvRoutes.MOVIES, TvRoutes.SHOWS) }
    val showRail = !isPlayerRoute &&
        !(selectedTopRoute == TvRoutes.IPTV && !isSubRouteActive && hideRailForIptv)
    val showHero = !isPlayerRoute && !isSubRouteActive && selectedTopRoute in heroRoutes
    val showInfoPanel = !isPlayerRoute && !isSubRouteActive && !isRailFocused &&
        selectedTopRoute in infoPanelRoutes && focusedMediaItem != null
    val focusedLogoKey = focusedMediaItem?.tmdbId?.let { tmdbId ->
        focusedMediaItem?.let { item -> "${item.type}:$tmdbId" }
    }
    val focusedLogoLookupInFlight = focusedLogoKey?.let { key ->
        logoLoadingByKey[key] == true
    } ?: false
    val focusedLogoLookupResolved = focusedLogoKey?.let { key ->
        logoCache.containsKey(key)
    } ?: true

    /* ── Focus restore: try content first, rail is guaranteed fallback ─────────────── */
    // Use a counter (not a boolean) so the effect key doesn't change inside its body,
    // which would cancel the coroutine before delay() completes.
    LaunchedEffect(focusRestoreTrigger) {
        if (focusRestoreTrigger == 0) return@LaunchedEffect
        // Two attempts with shorter delays — reduces focus search churn on Fire TV.
        for (attempt in 0..1) {
            delay(if (attempt == 0) 60L else 150L)
            val activeRoute = pendingContentEntryRoute
                ?: if (isSubRouteActive) {
                    TvRoutes.DETAILS
                } else if (!isRailFocused) {
                    confirmedTopRoute
                } else {
                    selectedTopRoute
                }
            val candidates = listOfNotNull(
                lastFocusedContentByRoute[activeRoute],
                firstContentFocusByRoute[activeRoute],
                headerPrimaryActionRequester.takeIf { activeRoute in heroRoutes },
            )
            for (candidate in candidates) {
                try {
                    candidate.requestFocus()
                    pendingContentEntryRoute = null
                    return@LaunchedEffect
                } catch (_: Throwable) { }
            }
            if (candidates.isEmpty() && attempt < 1) continue
            break
        }
        pendingContentEntryRoute = null
        try { railFocusRequester.requestFocus() } catch (_: Throwable) { }
    }

    /* ── Focus watchdog: if focus is truly lost, put it on the rail ─────────────────── */
    // Reduced polling frequency from 250+150ms to 1500+500ms to lower CPU overhead.
    // Focus is typically restored by the focusRestoreTrigger LaunchedEffect above;
    // this watchdog is only for edge cases where focus escapes entirely.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            if (isPlayerRoute || isSubRouteActive || rootHasFocus) continue
            delay(500)
            if (isSubRouteActive || rootHasFocus) continue
            Log.d("TvRoot", "Focus watchdog: focus lost, restoring to rail")
            com.torve.android.debug.AnrDebugLogger.log("FOCUS_WATCHDOG fired — restoring to rail")
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
        if (!isRailFocused) {
            highlightedTopRoute = selectedTopRoute
            confirmedTopRoute = selectedTopRoute
        }
        // Only restore focus to content when NOT browsing the rail.
        // While rail has focus, selection changes should not force content focus.
        if (!isRailFocused) {
            focusRestoreTrigger++
        }
    }

    /* ── Back handler: non-HOME tab → go to HOME; on HOME → exit ───────────────────────── */
    BackHandler(
        enabled = pendingUnlockFeature == null &&
            selectedTopRoute != TvRoutes.HOME &&
            !isSubRouteActive &&
            selectedTopRoute != TvRoutes.IPTV,
    ) {
        selectedTopRoute = TvRoutes.HOME
        highlightedTopRoute = TvRoutes.HOME
        confirmedTopRoute = TvRoutes.HOME
        pendingContentEntryRoute = null
    }

    /* ── Sync listeners ────────────────────────────────────────────────────────────────── */
    LaunchedEffect(syncState.isAuthenticated) {
        if (
            syncState.isAuthenticated &&
            syncState.devices.isEmpty() &&
            !TvPremiumAccess.isPremiumLocked(TvEntitledFeature.DEVICE_LINKING, accessTier)
        ) {
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
                    highlightedTopRoute = TvRoutes.SEARCH
                    confirmedTopRoute = TvRoutes.SEARCH
                    pendingContentEntryRoute = null
                    if (isSubRouteActive) {
                        navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                    }
                }

                is SyncInboundEvent.PlaybackIntent -> {
                    val detailId = event.contentId.toIntOrNull() ?: return@collect
                    val detailType = if (event.mediaType == "tv") "tv" else "movie"
                    if (TvPremiumAccess.isPremiumLocked(TvEntitledFeature.STREAM_PLAYBACK, accessTier)) {
                        requestLifetimeUnlock(TvEntitledFeature.STREAM_PLAYBACK)
                        return@collect
                    }
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
                            // Reload channel data so IPTV screen picks up synced playlists/favorites
                            if (result.playlistsImported > 0 || result.favoritesImported > 0) {
                                channelsViewModel.loadPlaylists()
                                channelsViewModel.loadFavorites()
                            }
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

    fun resolvedProgress(item: MediaItem, railProgress: Float?): Float? {
        val candidate = railProgress ?: progressByMediaId[item.id]
        return candidate?.coerceIn(0f, 1f)
    }

    suspend fun persistProgressRatio(item: MediaItem, ratio: Float) {
        val clampedRatio = ratio.coerceIn(0f, 1f)
        val existing = runCatching { watchProgressRepo.getProgress(item.id) }.getOrNull()
        val durationMs = existing?.durationMs?.takeIf { it > 0 } ?: TV_CONTEXT_DEFAULT_DURATION_MS
        val positionMs = (durationMs * clampedRatio).toLong()
        watchProgressRepo.saveProgress(
            WatchProgress(
                mediaId = item.id,
                mediaType = item.type,
                title = item.title,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                positionMs = positionMs,
                durationMs = durationMs,
                seasonNumber = existing?.seasonNumber,
                episodeNumber = existing?.episodeNumber,
                showTitle = existing?.showTitle ?: if (item.type == MediaType.SERIES) item.title else null,
            ),
        )
        progressByMediaId[item.id] = clampedRatio
    }

    val isFeatureLocked: (TvEntitledFeature) -> Boolean = { feature ->
        TvPremiumAccess.isPremiumLocked(feature, accessTier)
    }
    val featureForContextAction: (String) -> TvEntitledFeature? = { actionId ->
        when (actionId) {
            TV_CONTEXT_ACTION_PLAY,
            TV_CONTEXT_ACTION_RESUME_OR_START_OVER -> TvEntitledFeature.STREAM_PLAYBACK
            TV_CONTEXT_ACTION_TOGGLE_WATCHLIST -> TvEntitledFeature.WATCHLIST_EDIT
            TV_CONTEXT_ACTION_TOGGLE_FAVORITES -> TvEntitledFeature.FAVORITES_EDIT
            TV_CONTEXT_ACTION_TOGGLE_WATCHED -> TvEntitledFeature.WATCHED_STATUS_EDIT
            TV_CONTEXT_ACTION_MORE_LIKE_THIS -> TvEntitledFeature.MORE_LIKE_THIS_PREMIUM
            TV_CONTEXT_ACTION_CHOOSE_SOURCE -> TvEntitledFeature.CHOOSE_SOURCE_PREMIUM
            else -> null
        }
    }

    val contextMenuActionsForItem: (MediaItem, Float?) -> List<TvMediaContextMenuAction> = { item, railProgress ->
        val progress = resolvedProgress(item, railProgress)
        val hasResume = progress != null &&
            progress > TV_CONTEXT_RESUME_THRESHOLD &&
            progress < TV_CONTEXT_WATCHED_THRESHOLD
        val isWatched = (progress ?: 0f) >= TV_CONTEXT_WATCHED_THRESHOLD
        val inWatchlist = watchlistViewModel.isInWatchlist(item.id)
        val isFavorite = item.contextMenuFavoriteKey() in favoriteMediaKeys
        val isShow = item.type == MediaType.SERIES

        val resumeLabel = when {
            hasResume && isShow -> "Resume Next Episode"
            hasResume -> "Resume"
            else -> "Start Over"
        }

        val watchedLabel = when {
            isShow && isWatched -> "Mark Show as Unwatched"
            isShow -> "Mark Show as Watched"
            isWatched -> "Mark as Unwatched"
            else -> "Mark as Watched"
        }

        buildList {
            add(
                TvMediaContextMenuAction(
                    id = TV_CONTEXT_ACTION_PLAY,
                    label = "Play",
                    isLocked = isFeatureLocked(TvEntitledFeature.STREAM_PLAYBACK),
                ),
            )
            add(
                TvMediaContextMenuAction(
                    id = TV_CONTEXT_ACTION_RESUME_OR_START_OVER,
                    label = resumeLabel,
                    isLocked = isFeatureLocked(TvEntitledFeature.STREAM_PLAYBACK),
                ),
            )
            if (isShow) {
                add(TvMediaContextMenuAction(id = TV_CONTEXT_ACTION_GO_TO_SEASONS, label = "Go to Seasons"))
            }
            add(
                TvMediaContextMenuAction(
                    id = TV_CONTEXT_ACTION_TOGGLE_WATCHLIST,
                    label = if (inWatchlist) "Remove from Watchlist" else "Add to Watchlist",
                    isLocked = isFeatureLocked(TvEntitledFeature.WATCHLIST_EDIT),
                ),
            )
            add(
                TvMediaContextMenuAction(
                    id = TV_CONTEXT_ACTION_TOGGLE_FAVORITES,
                    label = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                    isLocked = isFeatureLocked(TvEntitledFeature.FAVORITES_EDIT),
                ),
            )
            add(
                TvMediaContextMenuAction(
                    id = TV_CONTEXT_ACTION_TOGGLE_WATCHED,
                    label = watchedLabel,
                    isLocked = isFeatureLocked(TvEntitledFeature.WATCHED_STATUS_EDIT),
                ),
            )
            add(
                TvMediaContextMenuAction(
                    id = TV_CONTEXT_ACTION_MORE_LIKE_THIS,
                    label = "More Like This",
                    isSecondary = true,
                    isLocked = isFeatureLocked(TvEntitledFeature.MORE_LIKE_THIS_PREMIUM),
                ),
            )
            add(
                TvMediaContextMenuAction(
                    id = TV_CONTEXT_ACTION_VIEW_DETAILS,
                    label = "View Details",
                    isSecondary = true,
                ),
            )
            if (!item.trailerKey.isNullOrBlank()) {
                add(
                    TvMediaContextMenuAction(
                        id = TV_CONTEXT_ACTION_TRAILER,
                        label = "Trailer",
                        isSecondary = true,
                    ),
                )
            }
            if (!item.imdbId.isNullOrBlank()) {
                add(
                    TvMediaContextMenuAction(
                        id = TV_CONTEXT_ACTION_CHOOSE_SOURCE,
                        label = "Choose Source",
                        isSecondary = true,
                        isLocked = isFeatureLocked(TvEntitledFeature.CHOOSE_SOURCE_PREMIUM),
                    ),
                )
            }
            if (accessTier == AccessTier.FREE) {
                add(
                    TvMediaContextMenuAction(
                        id = TV_CONTEXT_ACTION_UNLOCK_LIFETIME,
                        label = TvPremiumAccess.UNLOCK_WITH_LIFETIME_LABEL,
                        isSecondary = true,
                    ),
                )
            }
        }
    }

    val onContextMenuAction: (MediaItem, TvMediaContextMenuAction, Float?) -> Unit =
        { item, action, railProgress ->
            if (action.id == TV_CONTEXT_ACTION_UNLOCK_LIFETIME) {
                requestLifetimeUnlock(TvEntitledFeature.VIEW_PURCHASE_AND_UNLOCK)
            } else if (action.isLocked) {
                val lockedFeature = featureForContextAction(action.id)
                    ?: TvEntitledFeature.VIEW_PURCHASE_AND_UNLOCK
                requestLifetimeUnlock(lockedFeature)
            } else {
                val progress = resolvedProgress(item, railProgress)
                val hasResume = progress != null &&
                    progress > TV_CONTEXT_RESUME_THRESHOLD &&
                    progress < TV_CONTEXT_WATCHED_THRESHOLD
                val isWatched = (progress ?: 0f) >= TV_CONTEXT_WATCHED_THRESHOLD
                when (action.id) {
                    TV_CONTEXT_ACTION_PLAY -> navController.navigateToTvDetails(item, autoPlay = true)

                    TV_CONTEXT_ACTION_RESUME_OR_START_OVER -> {
                        if (hasResume) {
                            navController.navigateToTvDetails(item, autoPlay = true)
                        } else {
                            rootScope.launch {
                                persistProgressRatio(item, 0f)
                                navController.navigateToTvDetails(item, autoPlay = true)
                            }
                        }
                    }

                    TV_CONTEXT_ACTION_GO_TO_SEASONS,
                    TV_CONTEXT_ACTION_VIEW_DETAILS,
                    TV_CONTEXT_ACTION_CHOOSE_SOURCE
                    -> navController.navigateToTvDetails(item)

                    TV_CONTEXT_ACTION_TOGGLE_WATCHLIST -> {
                        val currentlyInWatchlist = watchlistViewModel.isInWatchlist(item.id)
                        watchlistViewModel.toggleWatchlist(item)
                        TvNotificationQueue.post(
                            if (currentlyInWatchlist) {
                                "${item.title} removed from Watchlist"
                            } else {
                                "${item.title} added to Watchlist"
                            },
                        )
                    }

                    TV_CONTEXT_ACTION_TOGGLE_FAVORITES -> {
                        val key = item.contextMenuFavoriteKey()
                        val shouldFavorite = key !in favoriteMediaKeys
                        val updatedKeys = if (shouldFavorite) favoriteMediaKeys + key else favoriteMediaKeys - key
                        favoriteMediaKeys = updatedKeys
                        tvPrefs.edit().putStringSet(TV_PREF_KEY_MEDIA_FAVORITES, updatedKeys).apply()
                        TvNotificationQueue.post(
                            if (shouldFavorite) {
                                "${item.title} added to Favorites"
                            } else {
                                "${item.title} removed from Favorites"
                            },
                        )
                    }

                    TV_CONTEXT_ACTION_TOGGLE_WATCHED -> {
                        rootScope.launch {
                            if (isWatched) {
                                persistProgressRatio(item, 0f)
                            } else {
                                persistProgressRatio(item, TV_CONTEXT_WATCHED_THRESHOLD)
                            }
                            TvNotificationQueue.post(
                                if (isWatched) {
                                    "${item.title} marked as unwatched"
                                } else {
                                    "${item.title} marked as watched"
                                },
                            )
                        }
                    }

                    TV_CONTEXT_ACTION_MORE_LIKE_THIS -> {
                        searchSeedQuery = item.title
                        if (isSubRouteActive) {
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                        }
                        selectedTopRoute = TvRoutes.SEARCH
                        highlightedTopRoute = TvRoutes.SEARCH
                        confirmedTopRoute = TvRoutes.SEARCH
                        pendingContentEntryRoute = null
                    }

                    TV_CONTEXT_ACTION_TRAILER -> {
                        val trailerKey = item.trailerKey
                        if (trailerKey.isNullOrBlank()) {
                            TvNotificationQueue.post("Trailer unavailable", NotificationType.ERROR)
                        } else {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.youtube.com/watch?v=$trailerKey"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                                .onFailure {
                                    TvNotificationQueue.post("Trailer unavailable", NotificationType.ERROR)
                                }
                        }
                    }
                }
            }
        }

    // First-run hint: show a welcome notification on first launch
    LaunchedEffect(Unit) {
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
        showInfoPanel = showInfoPanel,
        infoPanel = {
            TvFocusDetailsPanel(
                focusedItem = focusedMediaItem,
                logoLookupInFlight = focusedLogoLookupInFlight,
                logoLookupResolved = focusedLogoLookupResolved,
            )
        },
        leftRail = {
            if (showRail) {
                TvNavRail(
                    destinations = tvTopDestinations,
                    selectedRoute = highlightedTopRoute,
                    isExpanded = isRailExpanded,
                    railFocusRequester = railFocusRequester,
                    navigateOnFocus = true,
                    onRailFocusChanged = { hasFocus ->
                        isRailFocused = hasFocus
                        isRailExpanded = hasFocus
                        if (hasFocus) {
                            highlightedTopRoute = selectedTopRoute
                        }
                    },
                    onMoveToContent = { route ->
                        confirmedTopRoute = route
                        highlightedTopRoute = route
                        pendingContentEntryRoute = route
                        if (selectedTopRoute != route) {
                            selectedTopRoute = route
                            visitedTabs = visitedTabs + route
                        }
                        // Only use per-route focus targets here. Do NOT use
                        // headerPrimaryActionRequester — it's shared and may still
                        // be attached to the *previous* tab's hero overlay (recomposition
                        // hasn't happened yet). The focusRestoreTrigger LaunchedEffect
                        // handles the delayed fallback after recomposition.
                        val candidates = listOfNotNull(
                            lastFocusedContentByRoute[route],
                            firstContentFocusByRoute[route],
                        )
                        val focusedNow = candidates.any { candidate ->
                            runCatching {
                                candidate.requestFocus()
                                true
                            }.getOrDefault(false)
                        }
                        if (!focusedNow) {
                            focusRestoreTrigger++
                        } else {
                            pendingContentEntryRoute = null
                        }
                    },
                    onConfirm = { route ->
                        confirmedTopRoute = route
                        highlightedTopRoute = route
                        if (isSubRouteActive) {
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                        }
                        visitedTabs = visitedTabs + route
                        if (route == TvRoutes.SETTINGS) {
                            settingsDestination = TvSettingsDestination.MAIN
                        }
                        selectedTopRoute = route
                        // Move focus into the content area — same as onMoveToContent.
                        // Previously Enter/Center only selected the tab but left focus
                        // on the rail, so the user couldn't enter content pages.
                        pendingContentEntryRoute = route
                        val candidates = listOfNotNull(
                            lastFocusedContentByRoute[route],
                            firstContentFocusByRoute[route],
                        )
                        val focusedNow = candidates.any { candidate ->
                            runCatching {
                                candidate.requestFocus()
                                true
                            }.getOrDefault(false)
                        }
                        if (!focusedNow) {
                            focusRestoreTrigger++
                        } else {
                            pendingContentEntryRoute = null
                        }
                    },
                    onNavigate = { route ->
                        highlightedTopRoute = route
                        pendingContentEntryRoute = null
                        // Pop any active sub-route first
                        if (isSubRouteActive && route != selectedTopRoute) {
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                        }
                        // Add to visitedTabs synchronously so the tab renders
                        // in the same frame as the route change (no blank frame).
                        visitedTabs = visitedTabs + route
                        // Reset settings sub-destination so selecting Settings
                        // from the rail always shows the main settings screen.
                        if (route == TvRoutes.SETTINGS) {
                            settingsDestination = TvSettingsDestination.MAIN
                        }
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
                // SaveableStateHolder: Only the ACTIVE tab is composed at any given time.
                // Inactive tabs' saveable state (scroll positions, text input, etc.) is
                // preserved in memory but their composition trees are disposed — eliminating
                // the recomposition amplification where all 7 tabs recompose on every VM
                // state emission. This is the single largest ANR reduction.
                val stateHolder = rememberSaveableStateHolder()
                val activeTabRoute = if (!isSubRouteActive) selectedTopRoute else null

                activeTabRoute?.let { route ->
                    stateHolder.SaveableStateProvider(route) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .focusGroup(),
                        ) {
                            val isActiveTab = true
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
                                        onMediaFocused = onBrowseMediaFocused,
                                        contextMenuActionsForItem = contextMenuActionsForItem,
                                        onContextMenuAction = onContextMenuAction,
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
                                        onMediaFocused = onBrowseMediaFocused,
                                        contextMenuActionsForItem = contextMenuActionsForItem,
                                        onContextMenuAction = onContextMenuAction,
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
                                        onMediaFocused = onBrowseMediaFocused,
                                        contextMenuActionsForItem = contextMenuActionsForItem,
                                        onContextMenuAction = onContextMenuAction,
                                        onSeeAll = { railKey, title -> navigateToSeeAll(railKey, title, "tv") },
                                        shouldAutoFocus = false,
                                    )

                                    TvRoutes.IPTV -> TvIptvScreen(
                                        railFocusRequester = railFocusRequester,
                                        onChannelPlay = { channel ->
                                            if (TvPremiumAccess.isPremiumLocked(TvEntitledFeature.STREAM_PLAYBACK, accessTier)) {
                                                requestLifetimeUnlock(TvEntitledFeature.STREAM_PLAYBACK)
                                            } else {
                                                navController.navigate(
                                                    TvRoutes.livePlayer(
                                                        channelUrl = channel.url,
                                                        channelName = channel.name,
                                                        groupName = channel.groupTitle.orEmpty(),
                                                    ),
                                                ) {
                                                    launchSingleTop = true
                                                }
                                            }
                                        },
                                        onOpenEpgSettings = {
                                            openSettingsToChannels = true
                                            selectedTopRoute = TvRoutes.SETTINGS
                                            highlightedTopRoute = TvRoutes.SETTINGS
                                            confirmedTopRoute = TvRoutes.SETTINGS
                                            pendingContentEntryRoute = null
                                        },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.IPTV] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.IPTV] = it },
                                        shouldAutoFocus = false,
                                        isActive = isActiveTab,
                                        isRailFocused = isRailFocused,
                                        isRailExpanded = isRailExpanded,
                                        onCollapseRail = { isRailExpanded = false },
                                        onNavigateUp = {
                                            selectedTopRoute = TvRoutes.HOME
                                            highlightedTopRoute = TvRoutes.HOME
                                            confirmedTopRoute = TvRoutes.HOME
                                            pendingContentEntryRoute = null
                                        },
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
                                        onMediaFocused = onBrowseMediaFocused,
                                        contextMenuActionsForItem = contextMenuActionsForItem,
                                        onContextMenuAction = onContextMenuAction,
                                        onSeeAll = { railKey, title ->
                                            val mt = if (railKey.contains("movie")) "movie" else "tv"
                                            navigateToSeeAll(railKey, title, mt)
                                        },
                                        shouldAutoFocus = false,
                                    )

                                    TvRoutes.SETTINGS -> when (settingsDestination) {
                                        TvSettingsDestination.PAIRED_DEVICES -> {
                                            TvPairedDevicesScreen(
                                                onBack = { settingsDestination = TvSettingsDestination.MAIN },
                                            )
                                        }
                                        TvSettingsDestination.ACTIVATED_DEVICES -> {
                                            TvManageDevicesScreen(
                                                onBack = { settingsDestination = TvSettingsDestination.MAIN },
                                            )
                                        }
                                        TvSettingsDestination.MAIN -> {
                                            TvSettingsScreen(
                                                railFocusRequester = railFocusRequester,
                                                onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.SETTINGS] = it },
                                                onContentFocused = { lastFocusedContentByRoute[TvRoutes.SETTINGS] = it },
                                                onNavigateToHomeLayout = { navController.navigate(TvRoutes.HOME_LAYOUT) },
                                                onNavigateToRatings = { navController.navigate(TvRoutes.RATINGS_SETTINGS) },
                                                onNavigateToPairedDevices = {
                                                    settingsDestination = TvSettingsDestination.PAIRED_DEVICES
                                                },
                                                onNavigateToActivatedDevices = {
                                                    settingsDestination = TvSettingsDestination.ACTIVATED_DEVICES
                                                },
                                                onRequestLifetimeUnlock = requestLifetimeUnlock,
                                                openToChannelsTab = openSettingsToChannels,
                                                onChannelsTabConsumed = { openSettingsToChannels = false },
                                                isActive = isActiveTab,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                // Mark route as visited for state preservation
                LaunchedEffect(selectedTopRoute) {
                    visitedTabs = visitedTabs + selectedTopRoute
                }

                /* ── Layer 2: Sub-route NavHost (details, player, see-all, sub-screens) ── */
                // When no sub-route is active, block focus from entering the empty NavHost
                // so it cannot steal focus from the keep-alive tab screens.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (isSubRouteActive) 2f else -1f)
                        .alpha(if (isSubRouteActive) 1f else 0f)
                        .offset(x = if (isSubRouteActive) 0.dp else 10000.dp)
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
                            highlightedTopRoute = TvRoutes.SEARCH
                            confirmedTopRoute = TvRoutes.SEARCH
                            pendingContentEntryRoute = null
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                        },
                        onSettingsClick = {
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                            selectedTopRoute = TvRoutes.SETTINGS
                            highlightedTopRoute = TvRoutes.SETTINGS
                            confirmedTopRoute = TvRoutes.SETTINGS
                            pendingContentEntryRoute = null
                        },
                        onRequestLifetimeUnlock = requestLifetimeUnlock,
                        isStreamPlaybackLocked = TvPremiumAccess.isPremiumLocked(
                            TvEntitledFeature.STREAM_PLAYBACK,
                            accessTier,
                        ),
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
    pendingUnlockFeature?.let { feature ->
        TvLifetimeUnlockDialog(
            feature = feature,
            onUnlock = {
                pendingUnlockFeature = null
                tvPrefs.edit().putBoolean(PREF_KEY_OPEN_SUBSCRIPTION_ONCE, true).apply()
                settingsDestination = TvSettingsDestination.MAIN
                if (isSubRouteActive) {
                    navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                }
                selectedTopRoute = TvRoutes.SETTINGS
                highlightedTopRoute = TvRoutes.SETTINGS
                confirmedTopRoute = TvRoutes.SETTINGS
                pendingContentEntryRoute = null
            },
            onDismiss = { pendingUnlockFeature = null },
        )
    }

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

private const val TV_PREF_KEY_MEDIA_FAVORITES = "tv_media_favorites"
private const val PREF_KEY_OPEN_SUBSCRIPTION_ONCE = "tv_settings_open_subscription_once"
private const val TV_CONTEXT_DEFAULT_DURATION_MS = 120L * 60L * 1000L
private const val TV_CONTEXT_RESUME_THRESHOLD = 0.03f
private const val TV_CONTEXT_WATCHED_THRESHOLD = 0.9f

private const val TV_CONTEXT_ACTION_PLAY = "play"
private const val TV_CONTEXT_ACTION_RESUME_OR_START_OVER = "resume_or_start_over"
private const val TV_CONTEXT_ACTION_TOGGLE_WATCHLIST = "toggle_watchlist"
private const val TV_CONTEXT_ACTION_TOGGLE_FAVORITES = "toggle_favorites"
private const val TV_CONTEXT_ACTION_TOGGLE_WATCHED = "toggle_watched"
private const val TV_CONTEXT_ACTION_MORE_LIKE_THIS = "more_like_this"
private const val TV_CONTEXT_ACTION_VIEW_DETAILS = "view_details"
private const val TV_CONTEXT_ACTION_TRAILER = "trailer"
private const val TV_CONTEXT_ACTION_CHOOSE_SOURCE = "choose_source"
private const val TV_CONTEXT_ACTION_GO_TO_SEASONS = "go_to_seasons"
private const val TV_CONTEXT_ACTION_UNLOCK_LIFETIME = "unlock_lifetime"

private fun MediaItem.contextMenuFavoriteKey(): String {
    val stableId = tmdbId?.toString() ?: id
    return "${type.name}:$stableId"
}
