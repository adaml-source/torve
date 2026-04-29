package com.torve.android.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.torve.android.deeplink.TorveAppLink
import com.torve.android.deeplink.TorveAppLinkTarget
import com.torve.android.premium.rememberEffectivePremiumAccessTier
import com.torve.android.sync.SyncCoordinator
import com.torve.android.sync.model.SyncInboundEvent
import com.torve.android.tv.components.TvFocusDetailsPanel
import com.torve.android.tv.components.TvHeroBackground
import com.torve.android.tv.components.TvHeroOverlay
import com.torve.android.tv.components.TvLifetimeUnlockDialog
import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.android.tv.components.TvNavRail
import com.torve.android.tv.focus.TvFocusOrigin
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.focus.TvSettingsItemIds
import com.torve.android.tv.focus.TvSettingsEntryRestoreInputs
import com.torve.android.tv.focus.rememberTvSettingsFocusStateMachine
import com.torve.android.tv.focus.resolveContentEntryRoute
import com.torve.android.tv.focus.shouldRequestSettingsEntryRestore
import com.torve.android.tv.focus.shouldSuppressRailForSettingsSubpageEntry
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
import com.torve.android.tv.screens.TvSettingsCategory
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
import com.torve.domain.repository.BackendPremiumResult
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.sync.SyncPayload
import com.torve.domain.sync.SyncRepository
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

private data class TvFocusBackStackEntry(
    val route: String,
    val origin: TvFocusOrigin? = null,
)

internal fun orderedSettingsEntryCandidates(
    settingsEntryRequester: FocusRequester?,
    settingsMainEntryFocusRequester: FocusRequester?,
    firstContentFocusRequester: FocusRequester?,
): List<FocusRequester> {
    return listOfNotNull(
        settingsEntryRequester,
        settingsMainEntryFocusRequester,
        firstContentFocusRequester,
    ).distinct()
}

private fun NavHostController.navigateToTvDetails(item: MediaItem, autoPlay: Boolean = false) {
    val id = item.tmdbId ?: item.id.toIntOrNull() ?: return
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    navigate(TvRoutes.details(type = type, id = id, autoPlay = autoPlay))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvRoot(
    appLink: TorveAppLink? = null,
    onAppLinkConsumed: () -> Unit = {},
) {
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
        val subscriptionViewModel: SubscriptionViewModel,
        val subscriptionRepository: SubscriptionRepository,
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
                subscriptionViewModel = koin.get(),
                subscriptionRepository = koin.get(),
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
    val subscriptionViewModel = deps!!.subscriptionViewModel
    val subscriptionRepository = deps!!.subscriptionRepository

    val syncState by syncCoordinator.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val accessTier = rememberEffectivePremiumAccessTier(
        subscriptionTier = subscriptionState.subscription?.tier,
        subscriptionIsPro = subscriptionState.isPro,
    )
    val tvPrefs = remember(context) {
        context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
    }

    // Eager ChannelsViewModel creation: triggers cache-first init immediately
    // on app start so cached categories are ready before the user navigates to IPTV.
    val channelsViewModel: com.torve.presentation.channels.ChannelsViewModel = org.koin.compose.koinInject()

    /* ── Sub-route tracking (NavHost only handles details/player/see-all/sub-screens) ── */
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentSubRoute = navBackStackEntry?.destination?.route
    val isSubRouteActive = currentSubRoute != null && currentSubRoute != TvRoutes.SUB_NAV_START
    Log.d("TvRoot", "currentSubRoute=$currentSubRoute isSubRouteActive=$isSubRouteActive")
    val isPlayerRoute = currentSubRoute?.startsWith("tv_player") == true ||
        currentSubRoute?.startsWith("tv_live_player") == true
    val hideRailForIptv by TvIptvRailState.hideRail

    /* ── Tab state ─────────────────────────────────────────────────────────────────────── */
    /** The tab currently highlighted by D-pad focus on the rail. Changes on Up/Down. */
    var highlightedTopRoute by rememberSaveable { mutableStateOf(TvRoutes.HOME) }
    /** The tab whose content is currently being shown. Changes when user enters content or presses select. */
    var selectedTopRoute by rememberSaveable { mutableStateOf(TvRoutes.HOME) }
    /** The tab confirmed for content display. Updated when user navigates into content. */
    var confirmedTopRoute by rememberSaveable { mutableStateOf(TvRoutes.HOME) }
    // Cache LazyListState per route so scroll position survives tab switches.
    // TODO: pass per-route scroll states to individual screen composables (TvHomeScreen, etc.)
    //       once their signatures accept an external LazyListState parameter.
    val scrollStatesByRoute = remember { mutableMapOf<String, LazyListState>() }
    // Debounced rail navigation: focus changes only update highlightedTopRoute immediately.
    // The actual selectedTopRoute commit is delayed so rapid D-pad traversal doesn't trigger
    // expensive full-tab recompositions on every focus move.
    val navDebounceScope = rememberCoroutineScope()
    var pendingNavJob by remember { mutableStateOf<Job?>(null) }
    var visitedTabs by remember { mutableStateOf(setOf(TvRoutes.HOME)) }
    var settingsDestination by remember { mutableStateOf(TvSettingsDestination.MAIN) }
    var openSettingsToChannels by remember { mutableStateOf(false) }
    var openSettingsToSubscription by remember { mutableStateOf(false) }
    var pendingSettingsAppLinkItemId by remember { mutableStateOf<String?>(null) }
    var pendingUnlockFeature by remember { mutableStateOf<TvEntitledFeature?>(null) }
    var suppressBackToHome by remember { mutableStateOf(false) }
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
    val settingsMainEntryFocusRequester = remember { FocusRequester() }
    val settingsHomeLayoutCardRequester = remember { FocusRequester() }
    val settingsRatingsCardRequester = remember { FocusRequester() }
    val settingsPairedDevicesCardRequester = remember { FocusRequester() }
    val settingsActivatedDevicesCardRequester = remember { FocusRequester() }
    val homeLayoutEntryFocusRequester = remember { FocusRequester() }
    val ratingsEntryFocusRequester = remember { FocusRequester() }
    val pairedDevicesFocusRequester = remember { FocusRequester() }
    val activatedDevicesFocusRequester = remember { FocusRequester() }
    val settingsFocusStateMachine = rememberTvSettingsFocusStateMachine(key = "root_settings_focus_state")
    val firstContentFocusByRoute = remember { mutableStateMapOf<String, FocusRequester>() }
    val lastFocusedContentByRoute = remember { mutableStateMapOf<String, FocusRequester>() }
    val focusHandlesByRoute = remember { mutableStateMapOf<String, TvScreenFocusHandle>() }
    val focusReturnStack = remember { mutableStateListOf<TvFocusBackStackEntry>() }
    var pendingFocusBackRestore by remember { mutableStateOf<TvFocusBackStackEntry?>(null) }
    val topLevelRoutes = remember { tvTopDestinations.map { it.route }.toSet() }

    // Clear stale focus requesters when auth state changes (login/logout/sync).
    // Home is always composed, so its requesters survive across auth transitions
    // but point to nodes that were recomposed during the state change.
    LaunchedEffect(syncState.isAuthenticated, subscriptionState.isPro) {
        firstContentFocusByRoute.clear()
        lastFocusedContentByRoute.clear()
    }

    var isRailExpanded by rememberSaveable { mutableStateOf(false) }
    var isRailFocused by rememberSaveable { mutableStateOf(false) }
    // Debounce rail expansion: only expand after sustained focus (prevents flash on transient focus).
    LaunchedEffect(isRailFocused) {
        if (isRailFocused) {
            delay(100)
            isRailExpanded = true
        }
    }
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

    // ── Restore progress banner (non-blocking) ──
    val tvAccountCoordinator: com.torve.presentation.session.AccountSessionCoordinator = koinInject()
    val restoreProgress by tvAccountCoordinator.restoreProgress.collectAsState()
    LaunchedEffect(restoreProgress.message) {
        val msg = restoreProgress.message
        if (msg.isNotBlank() && restoreProgress.phase != com.torve.presentation.session.RestorePhase.IDLE) {
            TvNotificationQueue.post(msg)
        }
    }

    /* ── For focus restoration when returning from sub-routes ───────────────────────────── */
    var rootHasFocus by remember { mutableStateOf(false) }
    var focusRestoreTrigger by remember { mutableStateOf(0) }
    var pendingContentEntryRoute by remember { mutableStateOf<String?>(null) }
    var pendingRailEntryRoute by remember { mutableStateOf<String?>(null) }
    var pendingSettingsSubpageEntryRoute by remember { mutableStateOf<String?>(null) }
    var pendingSettingsDestinationReturnItemId by remember { mutableStateOf<String?>(null) }
    var homeLayoutEntryReady by remember { mutableStateOf(false) }
    var ratingsEntryReady by remember { mutableStateOf(false) }
    var focusedSettingsSubpageEntryRoute by remember { mutableStateOf<String?>(null) }
    val settingsEntryRequester = when (settingsDestination) {
        TvSettingsDestination.MAIN -> settingsFocusStateMachine.entryRequesterForCurrentState() ?: settingsMainEntryFocusRequester
        TvSettingsDestination.PAIRED_DEVICES -> pairedDevicesFocusRequester
        TvSettingsDestination.ACTIVATED_DEVICES -> activatedDevicesFocusRequester
    }
    val pendingSettingsModalRestoreToken = settingsFocusStateMachine.pendingRestore?.restoreToken
    var previousSelectedTopRoute by remember { mutableStateOf(selectedTopRoute) }
    var previousSettingsDestination by remember { mutableStateOf(settingsDestination) }
    var previousIsRailFocused by remember { mutableStateOf(isRailFocused) }

    val heroRoutes = remember { setOf(TvRoutes.HOME, TvRoutes.MOVIES, TvRoutes.SHOWS, TvRoutes.LIBRARY) }
    val infoPanelRoutes = remember { setOf(TvRoutes.HOME, TvRoutes.MOVIES, TvRoutes.SHOWS) }
    val showRail = !isPlayerRoute && !isSubRouteActive &&
        !(selectedTopRoute == TvRoutes.IPTV && hideRailForIptv)
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
    val suppressRailForSettingsSubpageEntry = shouldSuppressRailForSettingsSubpageEntry(
        pendingRoute = pendingSettingsSubpageEntryRoute,
        currentSubRoute = currentSubRoute,
    )

    LaunchedEffect(
        pendingSettingsSubpageEntryRoute,
        currentSubRoute,
        homeLayoutEntryReady,
        ratingsEntryReady,
        focusedSettingsSubpageEntryRoute,
    ) {
        val pendingRoute = pendingSettingsSubpageEntryRoute ?: return@LaunchedEffect
        if (currentSubRoute != pendingRoute) return@LaunchedEffect
        val (requester, isReady) = when (pendingRoute) {
            TvRoutes.HOME_LAYOUT -> homeLayoutEntryFocusRequester to homeLayoutEntryReady
            TvRoutes.RATINGS_SETTINGS -> ratingsEntryFocusRequester to ratingsEntryReady
            else -> return@LaunchedEffect
        }
        if (!isReady) return@LaunchedEffect

        repeat(15) {
            withFrameNanos { }
            delay(50)
            if (currentSubRoute != pendingRoute) return@LaunchedEffect
            runCatching { requester.requestFocus() }
            withFrameNanos { }
            if (focusedSettingsSubpageEntryRoute == pendingRoute) {
                pendingSettingsSubpageEntryRoute = null
                pendingContentEntryRoute = null
                return@LaunchedEffect
            }
        }

        Log.w("TvSettingsFocus", "subpage_entry_focus_failed route=$pendingRoute")
    }

    LaunchedEffect(selectedTopRoute, currentSubRoute, pendingSettingsSubpageEntryRoute) {
        val pendingRoute = pendingSettingsSubpageEntryRoute ?: return@LaunchedEffect
        val subRouteChangedAway = currentSubRoute != null &&
            currentSubRoute != TvRoutes.SUB_NAV_START &&
            currentSubRoute != pendingRoute
        if (selectedTopRoute != TvRoutes.SETTINGS || subRouteChangedAway) {
            pendingSettingsSubpageEntryRoute = null
        }
    }

    /* ── Focus restore: try content first, rail is guaranteed fallback ─────────────── */
    // Use a counter (not a boolean) so the effect key doesn't change inside its body,
    // which would cancel the coroutine before delay() completes.
    LaunchedEffect(focusRestoreTrigger) {
        if (focusRestoreTrigger == 0) return@LaunchedEffect
        if (pendingFocusBackRestore != null) {
            Log.d("TvFocusRestore", "root_focus_restore_suppressed pendingBackRestore=${pendingFocusBackRestore?.route}")
            return@LaunchedEffect
        }
        if (pendingSettingsSubpageEntryRoute != null) {
            Log.d("TvSettingsFocus", "root_focus_restore_suppressed pendingSubpageRoute=$pendingSettingsSubpageEntryRoute")
            return@LaunchedEffect
        }
        if (isRailFocused && pendingContentEntryRoute == null) {
            Log.d("TvSettingsFocus", "root_focus_restore_suppressed railOwnsFocus=true")
            return@LaunchedEffect
        }
        if (selectedTopRoute == TvRoutes.SETTINGS && pendingSettingsModalRestoreToken != null) {
            Log.d("TvSettingsFocus", "root_focus_restore_suppressed pendingToken=$pendingSettingsModalRestoreToken")
            return@LaunchedEffect
        }
        val isSettingsSubmenu = selectedTopRoute == TvRoutes.SETTINGS &&
            settingsDestination != TvSettingsDestination.MAIN
        // Multiple attempts with progressive delays — Settings composable takes
        // many seconds to JIT-compile on Fire TV Stick, so focus targets may not be
        // ready for a while after tab switch.
        val delays = if (isSettingsSubmenu) {
            listOf(40L, 90L, 160L, 260L, 500L)
        } else if (selectedTopRoute == TvRoutes.SETTINGS) {
            listOf(100L, 300L, 600L, 1000L, 2000L, 3000L)
        } else {
            // Content tabs need longer retries when returning from sub-routes
            // (See All, Details) because tab composables re-attach focus after
            // the sub-route overlay is removed.
            listOf(60L, 150L, 400L, 800L, 1500L)
        }
        for ((attempt, waitMs) in delays.withIndex()) {
            delay(waitMs)
            // After each delay, check if focus already landed on content (e.g.
            // the user pressed right again, or a previous attempt took effect).
            if (!isRailFocused && pendingContentEntryRoute != null) {
                pendingContentEntryRoute = null
                return@LaunchedEffect
            }
            val activeRoute = resolveContentEntryRoute(
                pendingContentEntryRoute = pendingContentEntryRoute,
                currentSubRoute = currentSubRoute,
                isSubRouteActive = isSubRouteActive,
                isRailFocused = isRailFocused,
                confirmedTopRoute = confirmedTopRoute,
                selectedTopRoute = selectedTopRoute,
            )
            val settingsCandidates = if (activeRoute == TvRoutes.SETTINGS) {
                orderedSettingsEntryCandidates(
                    settingsEntryRequester = settingsEntryRequester,
                    settingsMainEntryFocusRequester = settingsMainEntryFocusRequester,
                    firstContentFocusRequester = firstContentFocusByRoute[TvRoutes.SETTINGS],
                )
            } else {
                listOfNotNull(
                    lastFocusedContentByRoute[activeRoute],
                    firstContentFocusByRoute[activeRoute],
                )
            }
            val candidates = settingsCandidates + listOfNotNull(
                headerPrimaryActionRequester.takeIf { activeRoute in heroRoutes },
            )
            for (candidate in candidates) {
                try {
                    candidate.requestFocus()
                    // Wait a frame, then verify focus actually left the rail
                    kotlinx.coroutines.yield()
                    if (!isRailFocused) {
                        pendingContentEntryRoute = null
                        return@LaunchedEffect
                    }
                } catch (_: Throwable) { }
            }
            // All candidates failed or focus didn't move — retry after next delay
        }
        pendingContentEntryRoute = null
        // Last resort: put focus back on the rail so the user isn't stuck
        pendingRailEntryRoute = selectedTopRoute
        try { railFocusRequester.requestFocus() } catch (_: Throwable) { }
    }

    /* ── Focus watchdog: if focus is truly lost, put it on the rail ─────────────────── */
    // Reduced polling frequency from 250+150ms to 1500+500ms to lower CPU overhead.
    // Focus is typically restored by the focusRestoreTrigger LaunchedEffect above;
    // this watchdog is only for edge cases where focus escapes entirely.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            if (isPlayerRoute || isSubRouteActive || rootHasFocus || selectedTopRoute == TvRoutes.SETTINGS) continue
            delay(500)
            if (isSubRouteActive || rootHasFocus || selectedTopRoute == TvRoutes.SETTINGS) continue
            Log.d("TvFocusWatchdog", "Focus lost — restoring to rail")
            com.torve.android.debug.AnrDebugLogger.log("FOCUS_WATCHDOG fired — restoring to rail")
            pendingRailEntryRoute = selectedTopRoute
            try { railFocusRequester.requestFocus() } catch (_: Throwable) { }
        }
    }

    /* ── Sub-route transitions trigger focus restore ───────────────────────────────── */
    LaunchedEffect(isSubRouteActive) {
        if (isSubRouteActive) {
            // Entering a sub-route (e.g. Details) — cancel any pending debounced rail
            // navigation to prevent selectedTopRoute from switching while in the sub-route.
            pendingNavJob?.cancel()
            pendingNavJob = null
        }
        if (!isSubRouteActive) {
            // Returning from a sub-route (See All, Details, etc.) — restore focus to
            // the content page, not the rail. Clear stale focus requesters that were
            // registered while the tab had canFocus=false during the sub-route overlay.
            firstContentFocusByRoute.remove(TvRoutes.DETAILS)
            lastFocusedContentByRoute.remove(TvRoutes.DETAILS)
            // Also clear the active tab's stale requesters so fresh ones are used
            lastFocusedContentByRoute.remove(selectedTopRoute)
            pendingContentEntryRoute = pendingFocusBackRestore?.route ?: selectedTopRoute
        }
        focusRestoreTrigger++
    }

    /* ── Track visited tabs ────────────────────────────────────────────────────────────── */
    LaunchedEffect(selectedTopRoute) {
        visitedTabs = visitedTabs + selectedTopRoute
        // Clear stale Settings focus entries when leaving the Settings tab.
        // Only one tab is composed at a time — stored requesters from a disposed
        // tab can crash focusSearch if referenced during spatial navigation.
        if (selectedTopRoute != TvRoutes.SETTINGS) {
            firstContentFocusByRoute.remove(TvRoutes.SETTINGS)
            lastFocusedContentByRoute.remove(TvRoutes.SETTINGS)
        }
        // Only clear hero state when entering a tab via content, not during rail browsing.
        // This prevents the hero from blanking on every debounced tab switch.
        if (!isRailFocused) {
            focusedMediaItem = null
            highlightedTopRoute = selectedTopRoute
            confirmedTopRoute = selectedTopRoute
            focusRestoreTrigger++
        }
    }

    LaunchedEffect(
        selectedTopRoute,
        settingsDestination,
        pendingSettingsAppLinkItemId,
        settingsFocusStateMachine.currentRegistrationVersion,
    ) {
        val itemId = pendingSettingsAppLinkItemId ?: return@LaunchedEffect
        if (selectedTopRoute != TvRoutes.SETTINGS || settingsDestination != TvSettingsDestination.MAIN) {
            return@LaunchedEffect
        }
        if (!settingsFocusStateMachine.isItemRegistered(itemId)) return@LaunchedEffect
        settingsFocusStateMachine.requestRestore(
            itemId = itemId,
            reason = "app_link",
        )
        pendingContentEntryRoute = TvRoutes.SETTINGS
        focusRestoreTrigger++
        pendingSettingsAppLinkItemId = null
    }

    LaunchedEffect(appLink) {
        val link = appLink ?: return@LaunchedEffect
        focusReturnStack.clear()
        pendingFocusBackRestore = null
        pendingRailEntryRoute = null
        pendingNavJob?.cancel()
        pendingNavJob = null
        pendingSettingsDestinationReturnItemId = null
        pendingSettingsSubpageEntryRoute = null
        focusedSettingsSubpageEntryRoute = null

        when (link.target) {
            TorveAppLinkTarget.HOME -> {
                pendingSettingsAppLinkItemId = null
                if (isSubRouteActive) {
                    navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                }
                selectedTopRoute = TvRoutes.HOME
                highlightedTopRoute = TvRoutes.HOME
                confirmedTopRoute = TvRoutes.HOME
                pendingContentEntryRoute = TvRoutes.HOME
                focusRestoreTrigger++
            }

            TorveAppLinkTarget.ACCOUNT -> {
                pendingSettingsAppLinkItemId = null
                settingsDestination = TvSettingsDestination.MAIN
                settingsFocusStateMachine.selectedCategory = TvSettingsCategory.ACCOUNT
                if (isSubRouteActive) {
                    navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                }
                selectedTopRoute = TvRoutes.SETTINGS
                highlightedTopRoute = TvRoutes.SETTINGS
                confirmedTopRoute = TvRoutes.SETTINGS
                pendingContentEntryRoute = TvRoutes.SETTINGS
                focusRestoreTrigger++
            }

            TorveAppLinkTarget.SETUP -> {
                pendingSettingsAppLinkItemId = TvSettingsItemIds.CONNECTIONS_PAIRING
                settingsDestination = TvSettingsDestination.MAIN
                settingsFocusStateMachine.selectedCategory = TvSettingsCategory.CONNECTIONS
                if (isSubRouteActive) {
                    navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                }
                selectedTopRoute = TvRoutes.SETTINGS
                highlightedTopRoute = TvRoutes.SETTINGS
                confirmedTopRoute = TvRoutes.SETTINGS
                pendingContentEntryRoute = TvRoutes.SETTINGS
                focusRestoreTrigger++
            }

            TorveAppLinkTarget.HELP -> {
                pendingSettingsAppLinkItemId = TvSettingsItemIds.ABOUT_SUPPORT
                settingsDestination = TvSettingsDestination.MAIN
                settingsFocusStateMachine.selectedCategory = TvSettingsCategory.ABOUT
                if (isSubRouteActive) {
                    navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                }
                selectedTopRoute = TvRoutes.SETTINGS
                highlightedTopRoute = TvRoutes.SETTINGS
                confirmedTopRoute = TvRoutes.SETTINGS
                pendingContentEntryRoute = TvRoutes.SETTINGS
                focusRestoreTrigger++
            }
        }

        onAppLinkConsumed()
    }

    LaunchedEffect(
        selectedTopRoute,
        settingsDestination,
        isRailFocused,
    ) {
        val shouldRequestEntryRestore = shouldRequestSettingsEntryRestore(
            TvSettingsEntryRestoreInputs(
                previousSelectedTopRoute = previousSelectedTopRoute,
                selectedTopRoute = selectedTopRoute,
                previousSettingsDestination = previousSettingsDestination,
                settingsDestination = settingsDestination,
                previousIsRailFocused = previousIsRailFocused,
                isRailFocused = isRailFocused,
                hasSettingsEntryRequester = settingsEntryRequester != null,
                hasExplicitReturnFocus = false,
                hasPendingExactRestore = pendingSettingsModalRestoreToken != null,
            ),
        )
        previousSelectedTopRoute = selectedTopRoute
        previousSettingsDestination = settingsDestination
        previousIsRailFocused = isRailFocused
        if (selectedTopRoute != TvRoutes.SETTINGS) return@LaunchedEffect
        if (pendingSettingsModalRestoreToken != null) {
            Log.d("TvSettingsFocus", "root_settings_entry_restore_suppressed pendingToken=$pendingSettingsModalRestoreToken")
            return@LaunchedEffect
        }
        if (!shouldRequestEntryRestore) {
            Log.d("TvSettingsFocus", "root_settings_entry_restore_skipped route=$selectedTopRoute dest=$settingsDestination railFocused=$isRailFocused")
            return@LaunchedEffect
        }
        pendingContentEntryRoute = TvRoutes.SETTINGS
        focusRestoreTrigger++
    }

    val moveFocusToRailForRoute: (String) -> Unit = remember(railFocusRequester) {
        { route ->
            pendingNavJob?.cancel()
            pendingNavJob = null
            selectedTopRoute = route
            highlightedTopRoute = route
            confirmedTopRoute = route
            pendingContentEntryRoute = null
            pendingRailEntryRoute = route
            runCatching { railFocusRequester.requestFocus() }
        }
    }

    fun pushFocusReturnEntry(route: String) {
        if (route.isBlank() || route == TvRoutes.SUB_NAV_START) return
        val origin = focusHandlesByRoute[route]?.captureFocusedOrigin()
        val entry = TvFocusBackStackEntry(
            route = route,
            origin = origin,
        )
        focusReturnStack.add(entry)
        Log.d(
            "TvFocusRestore",
            "push route=${entry.route} hasOrigin=${entry.origin != null} size=${focusReturnStack.size}",
        )
    }

    fun popFocusReturnEntry(): TvFocusBackStackEntry {
        val entry = if (focusReturnStack.isNotEmpty()) {
            focusReturnStack.removeAt(focusReturnStack.lastIndex)
        } else {
            TvFocusBackStackEntry(route = selectedTopRoute)
        }
        Log.d(
            "TvFocusRestore",
            "pop route=${entry.route} hasOrigin=${entry.origin != null} size=${focusReturnStack.size}",
        )
        return entry
    }

    LaunchedEffect(
        pendingFocusBackRestore?.route,
        pendingFocusBackRestore?.origin?.restoreToken,
        currentSubRoute,
        isSubRouteActive,
        selectedTopRoute,
        pendingSettingsSubpageEntryRoute,
        settingsDestination,
    ) {
        val entry = pendingFocusBackRestore ?: return@LaunchedEffect
        val route = entry.route
        val routeReady = when {
            route == TvRoutes.SEE_ALL -> currentSubRoute?.startsWith("tv_see_all/") == true
            route == TvRoutes.DETAILS -> currentSubRoute?.startsWith("tv_details/") == true
            route in topLevelRoutes -> !isSubRouteActive && selectedTopRoute == route
            else -> currentSubRoute == route
        }
        if (!routeReady) return@LaunchedEffect
        if (entry.origin != null && focusHandlesByRoute[route] == null) return@LaunchedEffect
        if (route == TvRoutes.SETTINGS && pendingSettingsSubpageEntryRoute != null) return@LaunchedEffect

        pendingContentEntryRoute = route
        if (entry.origin != null) {
            focusHandlesByRoute[route]?.requestRestore(entry.origin, "back_stack")
        }
        pendingFocusBackRestore = null
        if (route in topLevelRoutes) {
            focusRestoreTrigger++
        }
    }

    /* ── Back handler: non-HOME tab → go to HOME; on HOME → exit ───────────────────────── */
    BackHandler(
        enabled = pendingUnlockFeature == null &&
            !suppressBackToHome &&
            selectedTopRoute != TvRoutes.HOME &&
            !isSubRouteActive &&
            selectedTopRoute != TvRoutes.IPTV &&
            selectedTopRoute != TvRoutes.SETTINGS &&
            !isRailFocused,
    ) {
        Log.w("TvNavDebug", "BACK HANDLER: selectedTopRoute $selectedTopRoute → HOME (isSubRouteActive=$isSubRouteActive)")
        moveFocusToRailForRoute(selectedTopRoute)
    }

    /* ── Sync listeners ────────────────────────────────────────────────────────────────── */
    BackHandler(
        enabled = pendingUnlockFeature == null &&
            !suppressBackToHome &&
            selectedTopRoute != TvRoutes.HOME &&
            !isSubRouteActive &&
            selectedTopRoute != TvRoutes.IPTV &&
            selectedTopRoute != TvRoutes.SETTINGS &&
            isRailFocused,
    ) {
        Log.w("TvNavDebug", "BACK HANDLER: selectedTopRoute $selectedTopRoute â†’ HOME (isSubRouteActive=$isSubRouteActive)")
        selectedTopRoute = TvRoutes.HOME
        highlightedTopRoute = TvRoutes.HOME
        confirmedTopRoute = TvRoutes.HOME
        pendingContentEntryRoute = null
    }

    LaunchedEffect(syncState.isAuthenticated) {
        if (
            syncState.isAuthenticated &&
            syncState.devices.isEmpty() &&
            !TvPremiumAccess.isPremiumLocked(TvEntitledFeature.DEVICE_LINKING, accessTier)
        ) {
            syncCoordinator.refreshDevices()
        }
    }

    LaunchedEffect(suppressBackToHome) {
        if (!suppressBackToHome) return@LaunchedEffect
        delay(700)
        suppressBackToHome = false
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
                    val premiumResult = try {
                        withContext(Dispatchers.IO) {
                            subscriptionRepository.refreshFromBackendDetailed()
                        }
                    } catch (e: Exception) {
                        Log.e("TvRoot", "Failed to verify premium access for settings sync", e)
                        TvNotificationQueue.post(
                            "Setup sync could not verify Premium on this TV.",
                            NotificationType.ERROR,
                        )
                        null
                    }
                    subscriptionViewModel.loadSubscription()
                    when (premiumResult) {
                        BackendPremiumResult.Active -> Unit
                        is BackendPremiumResult.DeviceBlocked -> {
                            TvNotificationQueue.post(
                                "Setup sync is blocked until this TV has an active Premium device slot.",
                                NotificationType.ERROR,
                            )
                            return@collect
                        }
                        BackendPremiumResult.NoEntitlement -> {
                            TvNotificationQueue.post(
                                "Premium is required before this TV can receive setup sync.",
                                NotificationType.ERROR,
                            )
                            return@collect
                        }
                        is BackendPremiumResult.Offline -> {
                            TvNotificationQueue.post(
                                "Connect to Torve to verify Premium before receiving setup sync.",
                                NotificationType.ERROR,
                            )
                            return@collect
                        }
                        null -> return@collect
                    }
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
                                val channelsViewModel = runCatching {
                                    org.koin.java.KoinJavaComponent.getKoin().get<ChannelsViewModel>()
                                }.getOrNull()
                                channelsViewModel?.loadPlaylists()
                                channelsViewModel?.loadFavorites()
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

    // Surface sync errors as notifications — skip pairing transport issues
    // which are non-blocking and only affect pairing features, not settings sync.
    LaunchedEffect(syncState.error) {
        syncState.error?.let { err ->
            val isPairingTransportNoise = err.contains("pairing", ignoreCase = true)
                || err.contains("LAN", ignoreCase = true)
                || err.contains("transport", ignoreCase = true)
            if (!isPairingTransportNoise) {
                TvNotificationQueue.post("Sync error: $err", NotificationType.ERROR)
            }
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
    // Use highlightedTopRoute so the hero updates immediately during rail navigation,
    // not after the 250ms debounce that controls selectedTopRoute.
    val heroRoute = if (isRailFocused) highlightedTopRoute else selectedTopRoute
    val featuredCacheKey = "featured:$heroRoute"
    val featuredItem by produceState<MediaItem?>(
        initialValue = TvScreenCache.get<MediaItem>(featuredCacheKey),
        heroRoute,
        metadataRepo,
    ) {
        val loaded = try {
            when (heroRoute) {
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

    val displayedFeaturedItem = if (isRailFocused) {
        // On the rail, show the per-tab featured item (responds to highlightedTopRoute)
        featuredItem
    } else {
        focusedMediaItem?.takeIf { item ->
            !item.backdropUrl.isNullOrBlank() || !item.posterUrl.isNullOrBlank()
        } ?: featuredItem
    }

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
            pushFocusReturnEntry(selectedTopRoute)
            navController.navigate(
                TvRoutes.seeAll(railKey = railKey, mediaType = mediaType, title = title),
            )
        }
    }

    fun requestContentFocusFromRail(route: String) {
        val candidates = if (route == TvRoutes.SETTINGS) {
            orderedSettingsEntryCandidates(
                settingsEntryRequester = settingsEntryRequester,
                settingsMainEntryFocusRequester = settingsMainEntryFocusRequester,
                firstContentFocusRequester = firstContentFocusByRoute[TvRoutes.SETTINGS],
            )
        } else {
            listOfNotNull(
                lastFocusedContentByRoute[route],
                firstContentFocusByRoute[route],
            )
        }
        rootScope.launch {
            for (candidate in candidates) {
                val movedFocus = runCatching {
                    candidate.requestFocus()
                    withFrameNanos { }
                    !isRailFocused
                }.getOrDefault(false)
                if (movedFocus) {
                    pendingContentEntryRoute = null
                    return@launch
                }
            }
            focusRestoreTrigger++
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
                    TV_CONTEXT_ACTION_PLAY -> {
                        pushFocusReturnEntry(selectedTopRoute)
                        navController.navigateToTvDetails(item, autoPlay = true)
                    }

                    TV_CONTEXT_ACTION_RESUME_OR_START_OVER -> {
                        if (hasResume) {
                            pushFocusReturnEntry(selectedTopRoute)
                            navController.navigateToTvDetails(item, autoPlay = true)
                        } else {
                            rootScope.launch {
                                persistProgressRatio(item, 0f)
                                pushFocusReturnEntry(selectedTopRoute)
                                navController.navigateToTvDetails(item, autoPlay = true)
                            }
                        }
                    }

                    TV_CONTEXT_ACTION_GO_TO_SEASONS,
                    TV_CONTEXT_ACTION_VIEW_DETAILS,
                    TV_CONTEXT_ACTION_CHOOSE_SOURCE
                    -> {
                        pushFocusReturnEntry(selectedTopRoute)
                        navController.navigateToTvDetails(item)
                    }

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
            // Always compose the rail so railFocusRequester stays attached.
            // Screens reference it in focusProperties { left = railFocusRequester };
            // if the composable is removed, focusSearch crashes on Left press.
            TvNavRail(
                    destinations = tvTopDestinations,
                    selectedRoute = highlightedTopRoute,
                    activeRoute = selectedTopRoute,
                    isExpanded = if (showRail) isRailExpanded else false,
                    railFocusRequester = railFocusRequester,
                    preferredEntryRoute = pendingRailEntryRoute,
                    modifier = Modifier
                        .alpha(if (showRail) 1f else 0f)
                        .focusProperties {
                            if (!showRail || suppressRailForSettingsSubpageEntry || isSubRouteActive) {
                                canFocus = false
                                enter = { FocusRequester.Cancel }
                            }
                        },
                    navigateOnFocus = true,
                    onRailFocusChanged = { hasFocus ->
                        isRailFocused = hasFocus
                        // Only collapse immediately; expansion is debounced below
                        // to prevent the rail from flashing open during transient focus.
                        if (!hasFocus) {
                            isRailExpanded = false
                            pendingNavJob?.cancel()
                            pendingNavJob = null
                        }
                        highlightedTopRoute = pendingRailEntryRoute ?: selectedTopRoute
                    },
                    onPreferredEntryRouteConsumed = {
                        pendingRailEntryRoute = null
                    },
                    onMoveToContent = { route ->
                        Log.w("TvNavDebug", "MOVE_TO_CONTENT: selectedTopRoute $selectedTopRoute → $route")
                        pendingNavJob?.cancel()
                        pendingNavJob = null
                        pendingRailEntryRoute = null
                        confirmedTopRoute = route
                        highlightedTopRoute = route
                        pendingContentEntryRoute = route
                        if (route == TvRoutes.SETTINGS && selectedTopRoute != TvRoutes.SETTINGS) {
                            settingsDestination = TvSettingsDestination.MAIN
                            pendingSettingsDestinationReturnItemId = null
                        }
                        if (selectedTopRoute != route) {
                            selectedTopRoute = route
                            visitedTabs = visitedTabs + route
                        }
                        // Only use per-route focus targets here. Do NOT use
                        // headerPrimaryActionRequester — it's shared and may still
                        // be attached to the *previous* tab's hero overlay (recomposition
                        // hasn't happened yet). The focusRestoreTrigger LaunchedEffect
                        // handles the delayed fallback after recomposition.
                        requestContentFocusFromRail(route)
                    },
                    onConfirm = { route ->
                        Log.w("TvNavDebug", "CONFIRM: selectedTopRoute $selectedTopRoute → $route")
                        pendingNavJob?.cancel()
                        pendingNavJob = null
                        pendingRailEntryRoute = null
                        confirmedTopRoute = route
                        highlightedTopRoute = route
                        if (isSubRouteActive) {
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                        }
                        visitedTabs = visitedTabs + route
                        if (route == TvRoutes.SETTINGS && selectedTopRoute != TvRoutes.SETTINGS) {
                            settingsDestination = TvSettingsDestination.MAIN
                            pendingSettingsDestinationReturnItemId = null
                        }
                        selectedTopRoute = route
                        // Move focus into the content area — same as onMoveToContent.
                        // Previously Enter/Center only selected the tab but left focus
                        // on the rail, so the user couldn't enter content pages.
                        pendingContentEntryRoute = route
                        requestContentFocusFromRail(route)
                    },
                    onNavigate = { route ->
                        pendingContentEntryRoute = null
                        pendingRailEntryRoute = null
                        pendingNavJob?.cancel()
                        pendingNavJob = null
                        // Update highlightedTopRoute immediately so the hero background
                        // responds to rail navigation without waiting for the debounce.
                        highlightedTopRoute = route
                        if (route == selectedTopRoute) return@TvNavRail
                        if (isSubRouteActive) return@TvNavRail
                        pendingNavJob = navDebounceScope.launch {
                            delay(80)
                            visitedTabs = visitedTabs + route
                            if (route == TvRoutes.SETTINGS && selectedTopRoute != TvRoutes.SETTINGS) {
                                settingsDestination = TvSettingsDestination.MAIN
                            }
                            selectedTopRoute = route
                        }
                    },
                )
        },
        background = {
            if (showHero) {
                key(displayedFeaturedItem?.tmdbId, displayedFeaturedItem?.type) {
                    TvHeroBackground(featuredItem = displayedFeaturedItem)
                }
            }
        },
        content = {
            Box(modifier = Modifier.fillMaxSize()) {
                /* ── Layer 1: Keep-alive tab screens ───────────────────────── */
                // Home is always composed (hidden when inactive) for instant return.
                // Other tabs are composed only when active — their saveable state
                // (scroll positions, etc.) is preserved by SaveableStateHolder but
                // their composition trees are disposed. This keeps max 2 tabs
                // composed (Home + current) — well below the 7-tab ANR threshold.
                val stateHolder = rememberSaveableStateHolder()
                // Keep tab content composed even when a sub-route (Details, See All) is active,
                // but hide it visually. This prevents focus from falling to the rail during
                // content-to-details transitions (which caused the nav rail to flash).
                val activeTabRoute = selectedTopRoute
                val tabContentVisible = !isSubRouteActive

                // Shared hero overlay content — only attached to the visible tab.
                val heroOverlayContent: (@Composable () -> Unit) = {
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
                        onDetailsFeatured = {
                            heroItem?.let {
                                navController.navigateToTvDetails(it)
                            }
                        },
                        onTrailerFeatured = {
                            heroItem?.let { item ->
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
                        },
                        isInWatchlist = heroInWatchlist,
                        onWatchlistToggle = {
                            heroItem?.let {
                                watchlistViewModel.toggleWatchlist(it)
                            }
                        },
                    )
                }

                /* ── Layer 1a: Home — always composed, hidden when inactive ── */
                val isHomeVisible = activeTabRoute == TvRoutes.HOME && tabContentVisible
                stateHolder.SaveableStateProvider(TvRoutes.HOME) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (isHomeVisible) 1f else 0f)
                            .graphicsLayer { alpha = if (isHomeVisible) 1f else 0f }
                            // Move off-screen when hidden to prevent spatial focus
                            // from finding Home content before recomposition updates
                            // the focusProperties canFocus flag.
                            .offset(x = if (isHomeVisible) 0.dp else 10000.dp)
                            .focusGroup()
                            .focusProperties {
                                if (!isHomeVisible) {
                                    canFocus = false
                                    enter = { FocusRequester.Cancel }
                                } else if (showHero && TvRoutes.HOME in heroRoutes) {
                                    up = headerPrimaryActionRequester
                                }
                            }
                            .then(
                                if (!isHomeVisible) Modifier.clearAndSetSemantics {} else Modifier
                            ),
                    ) {
                        val homeHeroOverlay: (@Composable () -> Unit)? =
                            if (isHomeVisible && showHero && TvRoutes.HOME in heroRoutes) heroOverlayContent else null

                        TvHomeScreen(
                            railFocusRequester = railFocusRequester,
                            headerFocusRequester = headerPrimaryActionRequester,
                            onMediaClick = { item ->
                                pushFocusReturnEntry(TvRoutes.HOME)
                                navController.navigateToTvDetails(item)
                            },
                            onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.HOME] = it },
                            onContentFocused = { lastFocusedContentByRoute[TvRoutes.HOME] = it },
                            registerFocusHandle = { handle ->
                                if (handle == null) {
                                    focusHandlesByRoute.remove(TvRoutes.HOME)
                                } else {
                                    focusHandlesByRoute[TvRoutes.HOME] = handle
                                }
                            },
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
                            heroOverlay = homeHeroOverlay,
                            shouldAutoFocus = pendingContentEntryRoute == TvRoutes.HOME,
                        )
                    }
                }

                /* ── Layer 1b: Other tabs — composed only when active ───────── */
                if (activeTabRoute != null && activeTabRoute != TvRoutes.HOME) {
                    stateHolder.SaveableStateProvider(activeTabRoute) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(1f)
                                .graphicsLayer { alpha = if (tabContentVisible) 1f else 0f }
                                .focusGroup()
                                .focusProperties {
                                    if (!tabContentVisible) {
                                        canFocus = false
                                        enter = { FocusRequester.Cancel }
                                    }
                                    if (showHero && activeTabRoute in heroRoutes) {
                                        up = headerPrimaryActionRequester
                                    }
                                },
                        ) {
                            val tabHeroOverlay: (@Composable () -> Unit)? =
                                if (showHero && activeTabRoute in heroRoutes) heroOverlayContent else null

                            when (activeTabRoute) {
                                    TvRoutes.MOVIES -> TvMoviesScreen(
                                        railFocusRequester = railFocusRequester,
                                        headerFocusRequester = headerPrimaryActionRequester,
                                        heroOverlay = tabHeroOverlay,
                                        onMediaClick = { item ->
                                            pushFocusReturnEntry(TvRoutes.MOVIES)
                                            navController.navigateToTvDetails(item)
                                        },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.MOVIES] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.MOVIES] = it },
                                        registerFocusHandle = { handle ->
                                            if (handle == null) {
                                                focusHandlesByRoute.remove(TvRoutes.MOVIES)
                                            } else {
                                                focusHandlesByRoute[TvRoutes.MOVIES] = handle
                                            }
                                        },
                                        onMediaFocused = onBrowseMediaFocused,
                                        contextMenuActionsForItem = contextMenuActionsForItem,
                                        onContextMenuAction = onContextMenuAction,
                                        onSeeAll = { railKey, title -> navigateToSeeAll(railKey, title, "movie") },
                                        shouldAutoFocus = pendingContentEntryRoute == TvRoutes.MOVIES,
                                    )

                                    TvRoutes.SHOWS -> TvShowsScreen(
                                        railFocusRequester = railFocusRequester,
                                        headerFocusRequester = headerPrimaryActionRequester,
                                        heroOverlay = tabHeroOverlay,
                                        onMediaClick = { item ->
                                            pushFocusReturnEntry(TvRoutes.SHOWS)
                                            navController.navigateToTvDetails(item)
                                        },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.SHOWS] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.SHOWS] = it },
                                        registerFocusHandle = { handle ->
                                            if (handle == null) {
                                                focusHandlesByRoute.remove(TvRoutes.SHOWS)
                                            } else {
                                                focusHandlesByRoute[TvRoutes.SHOWS] = handle
                                            }
                                        },
                                        onMediaFocused = onBrowseMediaFocused,
                                        contextMenuActionsForItem = contextMenuActionsForItem,
                                        onContextMenuAction = onContextMenuAction,
                                        onSeeAll = { railKey, title -> navigateToSeeAll(railKey, title, "tv") },
                                        shouldAutoFocus = pendingContentEntryRoute == TvRoutes.SHOWS,
                                    )

                                    TvRoutes.IPTV -> TvIptvScreen(
                                        viewModel = channelsViewModel,
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
                                            pendingContentEntryRoute = TvRoutes.SETTINGS
                                            focusRestoreTrigger++
                                        },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.IPTV] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.IPTV] = it },
                                        shouldAutoFocus = pendingContentEntryRoute == TvRoutes.IPTV,
                                        isActive = true,
                                        isSubRouteActive = isSubRouteActive,
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
                                        onMediaClick = { item ->
                                            pushFocusReturnEntry(TvRoutes.SEARCH)
                                            navController.navigateToTvDetails(item)
                                        },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.SEARCH] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.SEARCH] = it },
                                        shouldAutoFocus = pendingContentEntryRoute == TvRoutes.SEARCH,
                                    )

                                    TvRoutes.LIBRARY -> TvLibraryScreen(
                                        railFocusRequester = railFocusRequester,
                                        headerFocusRequester = headerPrimaryActionRequester,
                                        heroOverlay = tabHeroOverlay,
                                        onMediaClick = { item ->
                                            pushFocusReturnEntry(TvRoutes.LIBRARY)
                                            navController.navigateToTvDetails(item)
                                        },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.LIBRARY] = it },
                                        onContentFocused = { lastFocusedContentByRoute[TvRoutes.LIBRARY] = it },
                                        registerFocusHandle = { handle ->
                                            if (handle == null) {
                                                focusHandlesByRoute.remove(TvRoutes.LIBRARY)
                                            } else {
                                                focusHandlesByRoute[TvRoutes.LIBRARY] = handle
                                            }
                                        },
                                        onMediaFocused = onBrowseMediaFocused,
                                        contextMenuActionsForItem = contextMenuActionsForItem,
                                        onContextMenuAction = onContextMenuAction,
                                        onSeeAll = { railKey, title ->
                                            val mt = if (railKey.contains("movie")) "movie" else "tv"
                                            navigateToSeeAll(railKey, title, mt)
                                        },
                                        shouldAutoFocus = pendingContentEntryRoute == TvRoutes.LIBRARY,
                                        onJellyfinItemPlay = { streamUrl, title ->
                                            navController.navigate(
                                                com.torve.android.tv.nav.TvRoutes.player(
                                                    url = streamUrl,
                                                    fallbackUrl = "",
                                                    title = title,
                                                    mediaId = "",
                                                    mediaType = "movie",
                                                ),
                                            )
                                        },
                                    )

                                    TvRoutes.SETTINGS -> when (settingsDestination) {
                                        TvSettingsDestination.PAIRED_DEVICES -> {
                                            TvPairedDevicesScreen(
                                                onBack = {
                                                    val returnItemId = pendingSettingsDestinationReturnItemId
                                                        ?: TvSettingsItemIds.ACCOUNT_PAIRED_DEVICES
                                                    Log.d("TvSettingsFocus", "route_return item=$returnItemId reason=route_return")
                                                    settingsFocusStateMachine.requestRestore(
                                                        itemId = returnItemId,
                                                        reason = "route_return",
                                                    )
                                                    settingsDestination = TvSettingsDestination.MAIN
                                                    pendingSettingsDestinationReturnItemId = null
                                                    pendingContentEntryRoute = null
                                                },
                                                backButtonRequester = pairedDevicesFocusRequester,
                                                onFirstContentRequester = {},
                                                onContentFocused = {},
                                            )
                                        }
                                        TvSettingsDestination.ACTIVATED_DEVICES -> {
                                            TvManageDevicesScreen(
                                                onBack = {
                                                    val returnItemId = pendingSettingsDestinationReturnItemId
                                                        ?: TvSettingsItemIds.ACCOUNT_ACTIVATED_DEVICES
                                                    Log.d("TvSettingsFocus", "route_return item=$returnItemId reason=route_return")
                                                    settingsFocusStateMachine.requestRestore(
                                                        itemId = returnItemId,
                                                        reason = "route_return",
                                                    )
                                                    settingsDestination = TvSettingsDestination.MAIN
                                                    pendingSettingsDestinationReturnItemId = null
                                                    pendingContentEntryRoute = null
                                                },
                                                backButtonRequester = activatedDevicesFocusRequester,
                                                onFirstContentRequester = {},
                                                onContentFocused = {},
                                            )
                                        }
                                        TvSettingsDestination.MAIN -> {
                                            TvSettingsScreen(
                                                railFocusRequester = railFocusRequester,
                                                onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.SETTINGS] = it },
                                                onContentFocused = { lastFocusedContentByRoute[TvRoutes.SETTINGS] = it },
                                                onMoveFocusToRail = {
                                                    suppressBackToHome = true
                                                    selectedTopRoute = TvRoutes.SETTINGS
                                                    highlightedTopRoute = TvRoutes.SETTINGS
                                                    confirmedTopRoute = TvRoutes.SETTINGS
                                                    pendingContentEntryRoute = null
                                                    pendingRailEntryRoute = TvRoutes.SETTINGS
                                                },
                                                mainEntryFocusRequester = settingsMainEntryFocusRequester,
                                                homeLayoutFocusRequester = settingsHomeLayoutCardRequester,
                                                ratingsFocusRequester = settingsRatingsCardRequester,
                                                onNavigateToHomeLayout = {
                                                    pendingSettingsSubpageEntryRoute = TvRoutes.HOME_LAYOUT
                                                    pendingContentEntryRoute = TvRoutes.HOME_LAYOUT
                                                    focusedSettingsSubpageEntryRoute = null
                                                    navController.navigate(TvRoutes.HOME_LAYOUT)
                                                },
                                                onNavigateToRatings = {
                                                    pendingSettingsSubpageEntryRoute = TvRoutes.RATINGS_SETTINGS
                                                    pendingContentEntryRoute = TvRoutes.RATINGS_SETTINGS
                                                    focusedSettingsSubpageEntryRoute = null
                                                    navController.navigate(TvRoutes.RATINGS_SETTINGS)
                                                },
                                                onNavigateToPandaSetup = {
                                                    navController.navigate(TvRoutes.PANDA_SETUP)
                                                },
                                                onNavigateToSendCredentials = {
                                                    navController.navigate("transfer_send_tv")
                                                },
                                                onNavigateToReceiveCredentials = {
                                                    navController.navigate("transfer_receive_tv")
                                                },
                                                onNavigateToTransferDiagnostics = {
                                                    navController.navigate("transfer_diagnostics_tv")
                                                },
                                                onNavigateToPairedDevices = { itemId ->
                                                    pendingSettingsDestinationReturnItemId = itemId
                                                    settingsDestination = TvSettingsDestination.PAIRED_DEVICES
                                                    pendingContentEntryRoute = TvRoutes.SETTINGS
                                                    focusRestoreTrigger++
                                                },
                                                onNavigateToActivatedDevices = { itemId ->
                                                    pendingSettingsDestinationReturnItemId = itemId
                                                    settingsDestination = TvSettingsDestination.ACTIVATED_DEVICES
                                                    pendingContentEntryRoute = TvRoutes.SETTINGS
                                                    focusRestoreTrigger++
                                                },
                                                onAuthSuccess = {
                                                    pendingNavJob?.cancel()
                                                    pendingNavJob = null
                                                    suppressBackToHome = true
                                                    settingsDestination = TvSettingsDestination.MAIN
                                                    selectedTopRoute = TvRoutes.SETTINGS
                                                    highlightedTopRoute = TvRoutes.SETTINGS
                                                    confirmedTopRoute = TvRoutes.SETTINGS
                                                    pendingRailEntryRoute = TvRoutes.SETTINGS
                                                    pendingContentEntryRoute = TvRoutes.SETTINGS
                                                    focusRestoreTrigger++
                                                },
                                                onRequestLifetimeUnlock = requestLifetimeUnlock,
                                                openToChannelsTab = openSettingsToChannels,
                                                onChannelsTabConsumed = { openSettingsToChannels = false },
                                                openToSubscriptionSection = openSettingsToSubscription,
                                                onSubscriptionSectionConsumed = { openSettingsToSubscription = false },
                                                pairedDevicesFocusRequester = settingsPairedDevicesCardRequester,
                                                activatedDevicesFocusRequester = settingsActivatedDevicesCardRequester,
                                                settingsFocusController = settingsFocusStateMachine,
                                                isActive = true,
                                                isRailFocused = isRailFocused,
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
                        onSeeAllBack = {
                            pendingFocusBackRestore = popFocusReturnEntry()
                            navController.popBackStack()
                        },
                        onDetailsBack = {
                            pendingFocusBackRestore = popFocusReturnEntry()
                            navController.popBackStack()
                        },
                        onBeforeDetailsNavigateFromSeeAll = {
                            pushFocusReturnEntry(TvRoutes.SEE_ALL)
                        },
                        onVoiceSearchQuery = { query ->
                            focusReturnStack.clear()
                            pendingFocusBackRestore = null
                            searchSeedQuery = query
                            TvNotificationQueue.post("Search: $query")
                            selectedTopRoute = TvRoutes.SEARCH
                            highlightedTopRoute = TvRoutes.SEARCH
                            confirmedTopRoute = TvRoutes.SEARCH
                            pendingContentEntryRoute = null
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                        },
                        onSettingsClick = {
                            focusReturnStack.clear()
                            pendingFocusBackRestore = null
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                            selectedTopRoute = TvRoutes.SETTINGS
                            highlightedTopRoute = TvRoutes.SETTINGS
                            confirmedTopRoute = TvRoutes.SETTINGS
                            pendingContentEntryRoute = null
                        },
                        onHomeLayoutBack = {
                            Log.d("TvSettingsFocus", "route_return item=home_layout reason=route_return")
                            pendingSettingsSubpageEntryRoute = null
                            settingsFocusStateMachine.requestRestore(
                                itemId = TvSettingsItemIds.APPEARANCE_HOME_LAYOUT,
                                reason = "route_return",
                            )
                            navController.popBackStack()
                        },
                        onRatingsBack = {
                            Log.d("TvSettingsFocus", "route_return item=ratings reason=route_return")
                            pendingSettingsSubpageEntryRoute = null
                            settingsFocusStateMachine.requestRestore(
                                itemId = TvSettingsItemIds.APPEARANCE_RATINGS,
                                reason = "route_return",
                            )
                            navController.popBackStack()
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
                        registerSeeAllFocusHandle = { handle ->
                            if (handle == null) {
                                focusHandlesByRoute.remove(TvRoutes.SEE_ALL)
                            } else {
                                focusHandlesByRoute[TvRoutes.SEE_ALL] = handle
                            }
                        },
                        homeLayoutEntryFocusRequester = homeLayoutEntryFocusRequester,
                        onHomeLayoutEntryReadyChanged = { isReady ->
                            homeLayoutEntryReady = isReady
                            if (isReady) {
                                firstContentFocusByRoute[TvRoutes.HOME_LAYOUT] = homeLayoutEntryFocusRequester
                            }
                        },
                        onHomeLayoutEntryFocused = {
                            focusedSettingsSubpageEntryRoute = TvRoutes.HOME_LAYOUT
                            lastFocusedContentByRoute[TvRoutes.HOME_LAYOUT] = homeLayoutEntryFocusRequester
                        },
                        ratingsEntryFocusRequester = ratingsEntryFocusRequester,
                        onRatingsEntryReadyChanged = { isReady ->
                            ratingsEntryReady = isReady
                            if (isReady) {
                                firstContentFocusByRoute[TvRoutes.RATINGS_SETTINGS] = ratingsEntryFocusRequester
                            }
                        },
                        onRatingsEntryFocused = {
                            focusedSettingsSubpageEntryRoute = TvRoutes.RATINGS_SETTINGS
                            lastFocusedContentByRoute[TvRoutes.RATINGS_SETTINGS] = ratingsEntryFocusRequester
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
                openSettingsToSubscription = true
                settingsDestination = TvSettingsDestination.MAIN
                settingsFocusStateMachine.selectedCategory = TvSettingsCategory.ACCOUNT
                settingsFocusStateMachine.requestRestore(
                    itemId = TvSettingsItemIds.ACCOUNT_SUBSCRIPTION_MONTHLY,
                    reason = "premium_unlock_redirect",
                )
                if (isSubRouteActive) {
                    navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                }
                selectedTopRoute = TvRoutes.SETTINGS
                highlightedTopRoute = TvRoutes.SETTINGS
                confirmedTopRoute = TvRoutes.SETTINGS
                pendingContentEntryRoute = TvRoutes.SETTINGS
                focusRestoreTrigger++
            },
            onDismiss = { pendingUnlockFeature = null },
        )
    }

    // ── Import overlay: blocks navigation during heavy playlist restore ──
    if (restoreProgress.isImporting) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(200f)
                .background(com.torve.android.ui.theme.Obsidian.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp).fillMaxWidth(0.06f),
                    color = com.torve.android.ui.theme.Amber,
                    strokeWidth = 3.dp,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 16.dp))
                Text(
                    text = restoreProgress.message.ifBlank { "Restoring your account data…" },
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = Snow,
                )
                if (restoreProgress.totalPlaylists > 0) {
                    Text(
                        text = "${restoreProgress.restoredPlaylists}/${restoreProgress.totalPlaylists} playlists",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = com.torve.android.ui.theme.Silver,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
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
