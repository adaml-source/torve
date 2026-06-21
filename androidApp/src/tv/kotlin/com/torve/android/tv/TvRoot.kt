package com.torve.android.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.torve.domain.diagnostics.DiagnosticsRedactor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.torve.android.R
import com.torve.android.background.BackgroundWork
import com.torve.android.diagnostics.AndroidDiagnosticsRecorder
import com.torve.android.deeplink.TorveAppLink
import com.torve.android.deeplink.TorveAppLinkTarget
import com.torve.android.premium.rememberEffectivePremiumAccessTier
import com.torve.android.session.PostSignInRefresh
import com.torve.android.sync.SyncCoordinator
import com.torve.android.sync.model.SyncInboundEvent
import com.torve.android.tv.components.TvBrowseLayout
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
import com.torve.android.tv.screens.TvSportsScreen
import com.torve.android.tv.screens.TvLibraryScreen
import com.torve.android.tv.screens.TvMoviesScreen
import com.torve.android.tv.screens.TvManageDevicesScreen
import com.torve.android.tv.screens.TvPairedDevicesScreen
import com.torve.android.tv.screens.TvSettingsCategory
import com.torve.android.tv.screens.TvSettingsScreen
import com.torve.android.tv.screens.TvShowsScreen
import com.torve.android.tv.screens.TvSearchScreen
import com.torve.android.tv.screens.TvVodSeriesDetailsArgs
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.favoriteMediaKey
import com.torve.domain.model.toMediaItem
import com.torve.data.auth.AuthClient
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.MediaFavoritesRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.domain.repository.WatchHistoryRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.domain.sync.SyncPayload
import com.torve.domain.sync.SyncRepository
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.providerhealth.ProviderHealthRecoveryStateProvider
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.transfer.TransferImportCompletionNotifier
import com.torve.presentation.watchlist.WatchlistMutationState
import com.torve.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import androidx.compose.ui.text.font.FontWeight

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
    fun clear() { data.clear() }
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

private fun NavHostController.navigateToTvDetails(
    item: MediaItem,
    autoPlay: Boolean = false,
    focusEpisodes: Boolean = false,
) {
    val id = item.tmdbId ?: item.id.toIntOrNull() ?: return
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    navigate(TvRoutes.details(type = type, id = id, autoPlay = autoPlay, focusEpisodes = focusEpisodes))
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
    val backgroundWorkStatus by rememberTvBackgroundWorkStatus(context)

    // Resolve heavy singletons off the main thread so the first frame renders
    // instantly.  The background pre-warm in TorveApp usually wins the race,
    // but this guarantees the main thread never blocks on DB/VM creation.
    data class RootDeps(
        val metadataRepo: MetadataRepository,
        val watchlistViewModel: WatchlistViewModel,
        val watchProgressRepo: WatchProgressRepository,
        val watchHistoryRepo: WatchHistoryRepository,
        val syncCoordinator: SyncCoordinator,
        val syncRepository: SyncRepository,
        val settingsViewModel: SettingsViewModel,
        val subscriptionViewModel: SubscriptionViewModel,
        val subscriptionRepository: SubscriptionRepository,
        val authClient: AuthClient,
        val mediaFavoritesRepository: MediaFavoritesRepository,
        val transferRecoveryProvider: ProviderHealthRecoveryStateProvider,
        val transferImportCompletionNotifier: TransferImportCompletionNotifier,
    )

    val deps by produceState<RootDeps?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val koin = org.koin.java.KoinJavaComponent.getKoin()
            RootDeps(
                metadataRepo = koin.get(),
                watchlistViewModel = koin.get(),
                watchProgressRepo = koin.get(),
                watchHistoryRepo = koin.get(),
                syncCoordinator = koin.get(),
                syncRepository = koin.get(),
                settingsViewModel = koin.get(),
                subscriptionViewModel = koin.get(),
                subscriptionRepository = koin.get(),
                authClient = koin.get(),
                mediaFavoritesRepository = koin.get(),
                transferRecoveryProvider = koin.get(),
                transferImportCompletionNotifier = koin.get(),
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
    val watchHistoryRepo = deps!!.watchHistoryRepo
    val syncCoordinator = deps!!.syncCoordinator
    val syncRepository = deps!!.syncRepository
    val settingsViewModel = deps!!.settingsViewModel
    val subscriptionViewModel = deps!!.subscriptionViewModel
    val subscriptionRepository = deps!!.subscriptionRepository
    val authClient = deps!!.authClient
    val mediaFavoritesRepository = deps!!.mediaFavoritesRepository
    val transferRecoveryProvider = deps!!.transferRecoveryProvider
    val transferImportCompletionNotifier = deps!!.transferImportCompletionNotifier
    val tvAccountCoordinator: com.torve.presentation.session.AccountSessionCoordinator = koinInject()

    val authUser by authClient.authUserFlow.collectAsState()
    val mediaFavoritesState by mediaFavoritesRepository.state.collectAsState()
    val watchlistState by watchlistViewModel.state.collectAsState()
    val signedInUserId = authUser?.id?.takeIf { it.isNotBlank() }
    val isSignedIn = signedInUserId != null
    val syncState by syncCoordinator.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val accessTier = rememberEffectivePremiumAccessTier(
        subscriptionTier = subscriptionState.subscription?.tier,
        subscriptionIsPro = subscriptionState.isPro,
    )
    val tvPrefs = remember(context) {
        context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
    }
    val lastCredentialImportEpoch by transferImportCompletionNotifier.lastImportEpochMs.collectAsState()
    var credentialNudgeDismissed by rememberSaveable(signedInUserId) { mutableStateOf(false) }
    var showCredentialTransferNudge by rememberSaveable(signedInUserId) { mutableStateOf(false) }
    var lastTvChannelReloadAtMs by remember { mutableStateOf(0L) }

    fun reloadTvChannelShell(
        reason: String,
        clearScreenCache: Boolean = false,
        dedupe: Boolean = true,
        stagedImport: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (dedupe && now - lastTvChannelReloadAtMs < TV_CHANNEL_RELOAD_DEDUPE_MS) {
            Log.d("TvRoot", "Skipping duplicate channel shell reload reason=$reason")
            return
        }
        lastTvChannelReloadAtMs = now
        if (clearScreenCache) {
            TvScreenCache.clear()
        }
        settingsViewModel.refreshSettings()
        runCatching {
            val channelsViewModel: ChannelsViewModel = org.koin.java.KoinJavaComponent.getKoin().get()
            channelsViewModel.loadPlaylists(recoverEmptyCatalog = !stagedImport)
            channelsViewModel.loadFavorites()
        }
    }

    LaunchedEffect(signedInUserId, lastCredentialImportEpoch, credentialNudgeDismissed) {
        showCredentialTransferNudge = false
        if (signedInUserId == null || credentialNudgeDismissed) return@LaunchedEffect
        delay(1_200L)
        val shouldShow = withContext(Dispatchers.IO) {
            runCatching { transferRecoveryProvider.snapshot().shouldShowRecoveryCard }
                .getOrDefault(false)
        }
        showCredentialTransferNudge = shouldShow
    }

    LaunchedEffect(lastCredentialImportEpoch) {
        if (lastCredentialImportEpoch <= 0L) return@LaunchedEffect
        PostSignInRefresh.enqueueAfterCredentialImport(context)
        reloadTvChannelShell(
            reason = "credential_import",
            clearScreenCache = true,
            stagedImport = true,
        )
    }

    /* ── Sub-route tracking (NavHost only handles details/player/see-all/sub-screens) ── */
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentSubRoute = navBackStackEntry?.destination?.route
    val isSubRouteActive = currentSubRoute != null && currentSubRoute != TvRoutes.SUB_NAV_START
    Log.d("TvRoot", "currentSubRoute=$currentSubRoute isSubRouteActive=$isSubRouteActive")
    val isPlayerRoute = currentSubRoute?.startsWith("tv_player") == true ||
        currentSubRoute?.startsWith("tv_live_player") == true
    val hideRailForIptv by TvIptvRailState.hideRail

    LaunchedEffect(watchlistState.snackbarMessage, isSubRouteActive) {
        val message = watchlistState.snackbarMessage?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (isSubRouteActive) return@LaunchedEffect
        val notificationType = if (watchlistState.mutationState is WatchlistMutationState.Error) {
            NotificationType.ERROR
        } else {
            NotificationType.SUCCESS
        }
        TvNotificationQueue.post(message, notificationType)
        watchlistViewModel.clearSnackbar()
    }

    /* ── Tab state ─────────────────────────────────────────────────────────────────────── */
    /** The tab currently highlighted by D-pad focus on the rail. Changes on Up/Down. */
    var highlightedTopRoute by rememberSaveable { mutableStateOf(TvRoutes.HOME) }
    /** The tab whose content is currently being shown. Changes when user enters content or presses select. */
    var selectedTopRoute by rememberSaveable { mutableStateOf(TvRoutes.HOME) }
    /** The tab confirmed for content display. Updated when user navigates into content. */
    var confirmedTopRoute by rememberSaveable { mutableStateOf(TvRoutes.HOME) }

    LaunchedEffect(selectedTopRoute, currentSubRoute) {
        AndroidDiagnosticsRecorder.recordScreen(currentSubRoute ?: selectedTopRoute)
    }

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
    var contentFocusEpoch by remember { mutableIntStateOf(0) }
    var focusedContentRoute by remember { mutableStateOf<String?>(null) }
    val focusReturnStack = remember { mutableStateListOf<TvFocusBackStackEntry>() }
    var pendingFocusBackRestore by remember { mutableStateOf<TvFocusBackStackEntry?>(null) }
    val topLevelRoutes = remember { tvTopDestinations.map { it.route }.toSet() }

    // Clear stale focus requesters and account-scoped in-memory data when auth changes.
    // Home is always composed, so its requesters survive across auth transitions
    // but point to nodes that were recomposed during the state change.
    LaunchedEffect(signedInUserId, syncState.isAuthenticated, subscriptionState.isPro) {
        firstContentFocusByRoute.clear()
        lastFocusedContentByRoute.clear()
        focusHandlesByRoute.clear()
        TvScreenCache.clear()
    }

    var handledDeviceRevocationForUser by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(
        signedInUserId,
        subscriptionState.hasEntitlement,
        subscriptionState.isDeviceActivated,
        subscriptionState.deviceBlockReason,
    ) {
        val userId = signedInUserId ?: return@LaunchedEffect
        if (handledDeviceRevocationForUser == userId) return@LaunchedEffect
        if (
            subscriptionState.hasEntitlement &&
            !subscriptionState.isDeviceActivated &&
            isRemoteDeviceRevocationReason(subscriptionState.deviceBlockReason)
        ) {
            handledDeviceRevocationForUser = userId
            tvAccountCoordinator.clearLocalAccountData(reason = "device_revoked")
            authClient.logout()
            TvNotificationQueue.post(
                context.getString(R.string.tv_notification_device_removed_data_cleared),
                NotificationType.ERROR,
            )
        }
    }

    LaunchedEffect(signedInUserId) {
        if (signedInUserId == null) return@LaunchedEffect
        while (true) {
            delay(TV_DEVICE_ACCESS_MONITOR_INTERVAL_MS)
            subscriptionViewModel.loadSubscription()
        }
    }

    var isRailExpanded by rememberSaveable { mutableStateOf(false) }
    var isRailFocused by rememberSaveable { mutableStateOf(false) }
    // TV navigation is now a permanent slim rail. Keep the legacy state false
    // for callers that still accept it, but never expand the rail on focus.
    LaunchedEffect(isRailFocused) {
        isRailExpanded = false
    }
    var focusedMediaItem by remember { mutableStateOf<MediaItem?>(null) }

    fun markContentFocused(route: String, requester: FocusRequester) {
        lastFocusedContentByRoute[route] = requester
        focusedContentRoute = route
        contentFocusEpoch += 1
    }
    val mediaFavoritesPrefKey = remember(signedInUserId) {
        signedInUserId?.let { "$TV_PREF_KEY_MEDIA_FAVORITES:$it" }
    }
    val legacyFavoriteMediaKeys = remember(mediaFavoritesPrefKey) {
        mediaFavoritesPrefKey
            ?.let { tvPrefs.getStringSet(it, emptySet())?.toSet() }
            ?: emptySet()
    }
    val favoriteMediaKeys = mediaFavoritesState.favoriteKeys
    val favoriteMediaItems = remember(mediaFavoritesState.items) {
        mediaFavoritesState.items.map { it.toMediaItem() }
    }

    LaunchedEffect(signedInUserId) {
        if (signedInUserId != null) {
            mediaFavoritesRepository.refresh(force = true)
        }
    }

    LaunchedEffect(mediaFavoritesPrefKey, legacyFavoriteMediaKeys) {
        val prefKey = mediaFavoritesPrefKey ?: return@LaunchedEffect
        if (legacyFavoriteMediaKeys.isEmpty()) return@LaunchedEffect
        val items = withContext(Dispatchers.IO) {
            legacyFavoriteMediaKeys
                .filterNot { it in favoriteMediaKeys }
                .mapNotNull { it.toLegacyFavoriteMediaRefOrNull() }
                .mapNotNull { ref ->
                    runCatching {
                        metadataRepo.getDetail(
                            type = if (ref.type == MediaType.SERIES) "tv" else "movie",
                            id = ref.tmdbId,
                        )
                    }.getOrNull()
                }
        }
        items.forEach { mediaFavoritesRepository.addFavorite(it) }
        tvPrefs.edit().remove(prefKey).apply()
    }
    val progressByMediaId = remember { mutableStateMapOf<String, Float>() }

    /* ── Clearlogo cache: lazy-fetch logos for focused browse items ─────────────────── */
    val logoCache = remember { mutableStateMapOf<String, String?>() }
    val logoLoadingByKey = remember { mutableStateMapOf<String, Boolean>() }
    val heroDetailCache = remember { mutableStateMapOf<String, MediaItem?>() }
    var lastBrowseFocusKey by remember { mutableStateOf<String?>(null) }

    val onBrowseMediaFocused: (MediaItem) -> Unit = remember(logoCache, logoLoadingByKey) {
        onFocus@{ item ->
            val tmdbId = item.tmdbId
            val focusKey = tmdbId?.let { "${item.type}:$it" } ?: "${item.type}:${item.id}"
            if (focusKey == lastBrowseFocusKey) return@onFocus
            lastBrowseFocusKey = focusKey
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
                    else -> {
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
        logoCache[cacheKey]?.let { cached ->
            if (focusedMediaItem?.tmdbId == tmdbId) {
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
            val (detail, url) = withContext(Dispatchers.IO) {
                val directLogo = runCatching { metadataRepo.getLogoUrl(type, tmdbId) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                if (directLogo != null) {
                    null to directLogo
                } else {
                    val detail = runCatching { metadataRepo.getDetail(type, tmdbId) }.getOrNull()
                    detail to detail?.logoUrl?.takeIf { it.isNotBlank() }
                }
            }
            detail?.let { heroDetailCache[cacheKey] = it }
            if (url != null) {
                logoCache[cacheKey] = url
            } else {
                logoCache.remove(cacheKey)
            }
            // Only update if still the focused item
            if (focusedMediaItem?.tmdbId == tmdbId && url != null) {
                focusedMediaItem = focusedMediaItem?.copy(logoUrl = url)
            }
        } finally {
            logoLoadingByKey.remove(cacheKey)
        }
    }

    /* ── Notification + sync state ─────────────────────────────────────────────────────── */
    LaunchedEffect(signedInUserId) {
        progressByMediaId.clear()
        if (!isSignedIn) return@LaunchedEffect
        val persistedProgress = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { watchProgressRepo.getAllProgress() }.getOrDefault(emptyList())
        }
        for (entry in persistedProgress) {
            progressByMediaId[entry.mediaId] = entry.progressPercent.coerceIn(0f, 1f)
        }
        val watchedHistory = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { watchHistoryRepo.getAll() }.getOrDefault(emptyList())
        }
        for (entry in watchedHistory) {
            if (entry.seasonNumber == null && entry.episodeNumber == null) {
                progressByMediaId[entry.mediaId] = TV_CONTEXT_WATCHED_THRESHOLD
                entry.mediaId.toIntOrNull()?.let { tmdbId ->
                    progressByMediaId["tmdb:$tmdbId"] = TV_CONTEXT_WATCHED_THRESHOLD
                }
            }
        }
    }

    var searchSeedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var activeNotification by remember { mutableStateOf<TvNotification?>(null) }

    // ── Restore progress banner (non-blocking) ──
    val restoreProgress by tvAccountCoordinator.restoreProgress.collectAsState()
    LaunchedEffect(restoreProgress.message) {
        val msg = restoreProgress.message
        if (msg.isNotBlank() && restoreProgress.phase != com.torve.presentation.session.RestorePhase.IDLE) {
            TvNotificationQueue.post(msg)
        }
    }

    LaunchedEffect(restoreProgress.phase, signedInUserId) {
        if (signedInUserId == null) return@LaunchedEffect
        if (
            restoreProgress.phase == com.torve.presentation.session.RestorePhase.COMPLETED ||
            restoreProgress.phase == com.torve.presentation.session.RestorePhase.COMPLETED_WITH_ERRORS
        ) {
            syncCoordinator.refreshDevices()
            subscriptionViewModel.loadSubscription()
        }
    }

    /* ── For focus restoration when returning from sub-routes ───────────────────────────── */
    var rootHasFocus by remember { mutableStateOf(false) }
    var focusRestoreTrigger by remember { mutableStateOf(0) }
    var railInteractionEpoch by remember { mutableStateOf(0) }
    var pendingContentEntryRoute by remember { mutableStateOf<String?>(null) }
    var contentEntryRequestNonce by remember { mutableIntStateOf(0) }
    var pendingRailEntryRoute by remember { mutableStateOf<String?>(null) }
    var pendingRailEntryRequestNonce by remember { mutableIntStateOf(0) }
    var pendingSettingsSubpageEntryRoute by remember { mutableStateOf<String?>(null) }
    var pendingSettingsDestinationReturnItemId by remember { mutableStateOf<String?>(null) }
    var pendingPandaSetupReturnSettingsItemId by remember { mutableStateOf<String?>(null) }
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
    var backgroundWorkWasBlocking by remember { mutableStateOf(false) }
    var backgroundWorkWasVisible by remember { mutableStateOf(false) }
    var suppressNextSeeAllReturnFocusRestore by remember { mutableStateOf(false) }

    LaunchedEffect(backgroundWorkStatus.blockNavigation) {
        if (backgroundWorkWasBlocking && !backgroundWorkStatus.blockNavigation) {
            focusRestoreTrigger++
            reloadTvChannelShell(
                reason = "background_core_ready",
                clearScreenCache = true,
                dedupe = false,
            )
        }
        backgroundWorkWasBlocking = backgroundWorkStatus.blockNavigation
    }

    LaunchedEffect(backgroundWorkStatus.visible) {
        if (backgroundWorkWasVisible && !backgroundWorkStatus.visible) {
            runCatching { settingsViewModel.refreshSettings() }
        }
        backgroundWorkWasVisible = backgroundWorkStatus.visible
    }

    LaunchedEffect(signedInUserId) {
        progressByMediaId.clear()
        focusedMediaItem = null
        if (!isSignedIn) {
            pendingNavJob?.cancel()
            pendingNavJob = null
            pendingFocusBackRestore = null
            pendingRailEntryRoute = null
            pendingSettingsSubpageEntryRoute = null
            pendingPandaSetupReturnSettingsItemId = null
            selectedTopRoute = TvRoutes.HOME
            highlightedTopRoute = TvRoutes.HOME
            confirmedTopRoute = TvRoutes.HOME
            pendingContentEntryRoute = TvRoutes.HOME
            settingsDestination = TvSettingsDestination.MAIN
            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
        }
    }

    val contentTopRoute = selectedTopRoute
    val heroPreviewRoute = if (isRailFocused) highlightedTopRoute else selectedTopRoute
    val heroRoutes = remember { setOf(TvRoutes.HOME, TvRoutes.MOVIES, TvRoutes.SHOWS, TvRoutes.LIBRARY) }
    val showRail = !isPlayerRoute && !isSubRouteActive &&
        !(selectedTopRoute == TvRoutes.IPTV && hideRailForIptv)
    val showHero = !isPlayerRoute && !isSubRouteActive && heroPreviewRoute in heroRoutes
    val showInfoPanel = false
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
    LaunchedEffect(focusRestoreTrigger, showCredentialTransferNudge) {
        if (focusRestoreTrigger == 0) return@LaunchedEffect
        if (showCredentialTransferNudge) return@LaunchedEffect
        val restoreRailEpoch = railInteractionEpoch
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
            if (isRailFocused && railInteractionEpoch != restoreRailEpoch) {
                Log.d("TvFocusRestore", "root_focus_restore_cancelled railInteractionEpochChanged")
                return@LaunchedEffect
            }
            if (isRailFocused && pendingContentEntryRoute == null) {
                Log.d("TvFocusRestore", "root_focus_restore_cancelled railOwnsFocus=true")
                return@LaunchedEffect
            }
            // After each delay, check if focus actually landed on content.
            // Do not use "rail lost focus" as the signal: Compose can briefly
            // drop focus between the rail item and the content node, and that
            // was cancelling restore while no content requester owned focus.
            if (pendingContentEntryRoute != null && focusedContentRoute == pendingContentEntryRoute) {
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
                if (
                    activeRoute == TvRoutes.MOVIES ||
                    activeRoute == TvRoutes.SHOWS ||
                    activeRoute == TvRoutes.HOME ||
                    activeRoute == TvRoutes.SEARCH
                ) {
                    listOfNotNull(
                        lastFocusedContentByRoute[activeRoute],
                        firstContentFocusByRoute[activeRoute],
                    )
                } else {
                    listOfNotNull(
                        lastFocusedContentByRoute[activeRoute],
                        firstContentFocusByRoute[activeRoute],
                    )
                }
            }
            val candidates = settingsCandidates
            for (candidate in candidates) {
                try {
                    val beforeEpoch = contentFocusEpoch
                    candidate.requestFocus()
                    // Wait a frame, then verify a content node reported focus.
                    kotlinx.coroutines.yield()
                    withFrameNanos { }
                    if (contentFocusEpoch != beforeEpoch && focusedContentRoute == activeRoute) {
                        pendingContentEntryRoute = null
                        return@LaunchedEffect
                    }
                } catch (_: Throwable) { }
            }
            // All candidates failed or focus didn't move — retry after next delay
        }
        pendingContentEntryRoute = null
        if (isRailFocused && railInteractionEpoch != restoreRailEpoch) {
            Log.d("TvFocusRestore", "root_focus_restore_fallback_cancelled railInteractionEpochChanged")
            return@LaunchedEffect
        }
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
            if (showCredentialTransferNudge) continue
            if (isPlayerRoute || isSubRouteActive || rootHasFocus || selectedTopRoute == TvRoutes.SETTINGS) continue
            delay(500)
            if (showCredentialTransferNudge) continue
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
            if (suppressNextSeeAllReturnFocusRestore) {
                suppressNextSeeAllReturnFocusRestore = false
                pendingFocusBackRestore = null
                pendingContentEntryRoute = null
                pendingRailEntryRoute = null
                return@LaunchedEffect
            }
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
        showCredentialTransferNudge,
    ) {
        if (showCredentialTransferNudge) return@LaunchedEffect
        val shouldRequestEntryRestore = shouldRequestSettingsEntryRestore(
            TvSettingsEntryRestoreInputs(
                previousSelectedTopRoute = previousSelectedTopRoute,
                selectedTopRoute = selectedTopRoute,
                previousSettingsDestination = previousSettingsDestination,
                settingsDestination = settingsDestination,
                previousIsRailFocused = previousIsRailFocused,
                isRailFocused = isRailFocused,
                hasSettingsEntryRequester = true,
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
            pendingRailEntryRequestNonce += 1
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

    val syncSearchReceivedMessage = stringResource(R.string.tv_sync_search_received)
    val syncPlaybackReceivedMessage = stringResource(R.string.tv_sync_playback_received)

    LaunchedEffect(Unit) {
        syncCoordinator.inboundEvents.collect { event ->
            when (event) {
                is SyncInboundEvent.SearchPush -> {
                    searchSeedQuery = event.query
                    TvNotificationQueue.post(syncSearchReceivedMessage)
                    selectedTopRoute = TvRoutes.MOVIES
                    highlightedTopRoute = TvRoutes.MOVIES
                    confirmedTopRoute = TvRoutes.MOVIES
                    pendingContentEntryRoute = TvRoutes.MOVIES
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
                    TvNotificationQueue.post(syncPlaybackReceivedMessage)
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
                    runCatching {
                        withContext(Dispatchers.IO) {
                            subscriptionRepository.refreshFromBackendDetailed()
                        }
                    }.onFailure { e ->
                        Log.e("TvRoot", "Failed to refresh account state for settings sync", e)
                        TvNotificationQueue.post(
                            context.getString(R.string.tv_notification_setup_sync_refresh_failed),
                            NotificationType.INFO,
                        )
                    }
                    subscriptionViewModel.loadSubscription()
                    val syncJson = Json { ignoreUnknownKeys = true }
                    val payload = try {
                        syncJson.decodeFromString<SyncPayload>(event.payloadJson)
                    } catch (e: Exception) {
                        Log.e("TvRoot", "Failed to decode settings payload", e)
                        TvNotificationQueue.post(
                            context.getString(
                                R.string.tv_notification_invalid_settings_data,
                                e.message.orEmpty(),
                            ),
                            NotificationType.ERROR,
                        )
                        null
                    }
                    if (payload != null) {
                        Log.d("TvRoot", "Settings payload: ${payload.addons.size} addons, ${payload.preferences.size} prefs, ${payload.channelPlaylists.size} playlists, ${payload.channelFavorites.size} favorites, ${payload.watchProgress.size} progress, ${payload.integrationSecrets.size} integration rows")
                        val result = try {
                            withContext(Dispatchers.IO) {
                                syncRepository.importSyncPayload(payload)
                            }
                        } catch (e: Exception) {
                            Log.e("TvRoot", "Failed to import settings: ${e::class.simpleName} ${DiagnosticsRedactor.redact(e.message)}")
                            TvNotificationQueue.post(
                                context.getString(
                                    R.string.tv_notification_sync_import_failed,
                                    e.message.orEmpty(),
                                ),
                                NotificationType.ERROR,
                            )
                            null
                        }
                        if (result != null) {
                            Log.d("TvRoot", "Import result: $result")
                            // Reload account-scoped UI so IPTV picks up synced playlists/favorites.
                            // The helper also refreshes SettingsViewModel for synced integrations.
                            if (result.playlistsImported > 0 || result.favoritesImported > 0) {
                                if (result.playlistsImported > 0) {
                                    PostSignInRefresh.enqueueAfterCredentialImport(context)
                                }
                                reloadTvChannelShell(
                                    reason = "settings_sync_import",
                                    clearScreenCache = result.playlistsImported > 0,
                                    stagedImport = result.playlistsImported > 0,
                                )
                            }
                            val parts = buildList {
                                if (result.addonsImported > 0) {
                                    add(context.getString(R.string.tv_notification_sync_summary_addons, result.addonsImported))
                                }
                                if (result.preferencesImported > 0) {
                                    add(context.getString(R.string.tv_notification_sync_summary_preferences, result.preferencesImported))
                                }
                                if (result.secretsImported > 0) {
                                    add(context.getString(R.string.tv_notification_sync_summary_integrations, result.secretsImported))
                                }
                                if (result.playlistsImported > 0) {
                                    add(context.getString(R.string.tv_notification_sync_summary_playlists, result.playlistsImported))
                                }
                                if (result.favoritesImported > 0) {
                                    add(context.getString(R.string.tv_notification_sync_summary_favorites, result.favoritesImported))
                                }
                                if (result.progressImported > 0) {
                                    add(context.getString(R.string.tv_notification_sync_summary_progress, result.progressImported))
                                }
                            }
                            val summary = if (parts.isNotEmpty()) {
                                parts.joinToString(", ")
                            } else {
                                context.getString(R.string.tv_notification_sync_no_changes)
                            }
                            TvNotificationQueue.post(
                                context.getString(R.string.tv_notification_settings_synced, summary),
                            )
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
                TvNotificationQueue.post(
                    context.getString(R.string.tv_notification_sync_error, err),
                    NotificationType.ERROR,
                )
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
                    TvNotificationQueue.post(
                        context.getString(R.string.tv_notification_downloads_completed, newFinished),
                        NotificationType.SUCCESS,
                    )
                }
                lastCompletedCount = completed
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    // Notification collector — show each notification for 2.4s then clear
    LaunchedEffect(Unit) {
        TvNotificationQueue.events.collectLatest { notification ->
            if (notification.clear) {
                if (notification.tag == null || activeNotification?.tag == notification.tag) {
                    activeNotification = null
                }
                return@collectLatest
            }
            activeNotification = notification
            notification.durationMs?.let { durationMs ->
                delay(durationMs)
                if (activeNotification?.tag == notification.tag && activeNotification?.message == notification.message) {
                    activeNotification = null
                }
            }
        }
    }

    /* ── Featured hero item ────────────────────────────────────────────────────────────── */
    // Use highlightedTopRoute so the hero updates immediately during rail navigation,
    // not after the 250ms debounce that controls selectedTopRoute.
    val heroRoute = heroPreviewRoute
    val featuredCacheKey = "featured:$heroRoute"
    val featuredInitialValue = remember(featuredCacheKey) {
        TvScreenCache.get<MediaItem>(featuredCacheKey)
            ?: if (heroRoute == TvRoutes.HOME) TvScreenCache.get("featured:${TvRoutes.MOVIES}") else null
    }
    val featuredItems by produceState<List<MediaItem>>(
        initialValue = listOfNotNull(featuredInitialValue),
        heroRoute,
        signedInUserId,
        metadataRepo,
    ) {
        val loaded = try {
            when (heroRoute) {
                TvRoutes.MOVIES -> metadataRepo.getTrending("movie")
                    .ifEmpty { metadataRepo.discover(type = "movie", sortBy = "popularity.desc").items }
                TvRoutes.SHOWS -> metadataRepo.getTrending("tv")
                    .ifEmpty { metadataRepo.discover(type = "tv", sortBy = "popularity.desc").items }
                TvRoutes.HOME -> metadataRepo.getTrending("movie")
                    .ifEmpty { metadataRepo.getPopular("movie") }
                    .ifEmpty { metadataRepo.discover(type = "movie", sortBy = "popularity.desc").items }
                else -> emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
        val usable = loaded
            .filter { !it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank() }
            .distinctBy { "${it.type}:${it.tmdbId ?: it.id}" }
            .take(12)
        if (usable.isNotEmpty()) {
            TvScreenCache.put(featuredCacheKey, usable.first())
        }
        value = usable.ifEmpty { listOfNotNull(TvScreenCache.get(featuredCacheKey)) }
    }
    var featuredItemIndex by remember(heroRoute) { mutableIntStateOf(0) }
    LaunchedEffect(heroRoute, featuredItems, isSubRouteActive) {
        featuredItemIndex = 0
        if (featuredItems.size <= 1) return@LaunchedEffect
        while (true) {
            delay(6_500L)
            if (!isSubRouteActive) {
                featuredItemIndex = (featuredItemIndex + 1) % featuredItems.size
            }
        }
    }
    val featuredItem = featuredItems.getOrNull(
        featuredItemIndex.coerceIn(0, (featuredItems.size - 1).coerceAtLeast(0)),
    ) ?: featuredInitialValue

    fun MediaItem.matchesHeroRoute(route: String): Boolean = when (route) {
        TvRoutes.MOVIES -> type == MediaType.MOVIE
        TvRoutes.SHOWS -> type == MediaType.SERIES
        TvRoutes.HOME,
        TvRoutes.LIBRARY -> true
        else -> false
    }

    val displayedFeaturedItem = if (isRailFocused) {
        // During rail navigation prefer the per-tab hero, but do not leave the
        // backdrop blank if the root-level fetch is still resolving and the
        // active catalog page has already published its first trending item.
        featuredItem ?: focusedMediaItem?.takeIf { it.matchesHeroRoute(heroRoute) }
    } else {
        focusedMediaItem?.takeIf { item ->
            item.matchesHeroRoute(heroRoute) &&
                (!item.backdropUrl.isNullOrBlank() || !item.posterUrl.isNullOrBlank())
        } ?: featuredItem
    }

    LaunchedEffect(displayedFeaturedItem?.type, displayedFeaturedItem?.tmdbId) {
        val item = displayedFeaturedItem ?: return@LaunchedEffect
        val tmdbId = item.tmdbId ?: return@LaunchedEffect
        val cacheKey = "${item.type}:$tmdbId"
        if (heroDetailCache[cacheKey] != null) return@LaunchedEffect

        val needsDetail = item.overview.isNullOrBlank() ||
            item.logoUrl.isNullOrBlank() ||
            item.ratings == null ||
            item.runtime == null ||
            item.genres.isEmpty()
        if (!needsDetail) {
            heroDetailCache[cacheKey] = item
            return@LaunchedEffect
        }

        delay(120)
        val type = if (item.type == MediaType.MOVIE) "movie" else "tv"
        val detail = withContext(Dispatchers.IO) {
            runCatching { metadataRepo.getDetail(type, tmdbId) }.getOrNull()
        }
        heroDetailCache[cacheKey] = detail
        detail?.logoUrl?.takeIf { it.isNotBlank() }?.let { logoUrl ->
            logoCache[cacheKey] = logoUrl
            logoLoadingByKey.remove(cacheKey)
        }
    }

    /* ── Section titles ────────────────────────────────────────────────────────────────── */
    LaunchedEffect(
        displayedFeaturedItem?.type,
        displayedFeaturedItem?.tmdbId,
        displayedFeaturedItem?.logoUrl,
    ) {
        val item = displayedFeaturedItem ?: return@LaunchedEffect
        val tmdbId = item.tmdbId ?: return@LaunchedEffect
        val cacheKey = "${item.type}:$tmdbId"
        if (!item.logoUrl.isNullOrBlank()) {
            logoCache[cacheKey] = item.logoUrl
            logoLoadingByKey.remove(cacheKey)
            return@LaunchedEffect
        }
        if (logoCache[cacheKey] != null) return@LaunchedEffect

        logoLoadingByKey[cacheKey] = true
        try {
            delay(80)
            val type = if (item.type == MediaType.MOVIE) "movie" else "tv"
            val (detail, url) = withContext(Dispatchers.IO) {
                val directLogo = runCatching { metadataRepo.getLogoUrl(type, tmdbId) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                if (directLogo != null) {
                    null to directLogo
                } else {
                    val detail = runCatching { metadataRepo.getDetail(type, tmdbId) }.getOrNull()
                    detail to detail?.logoUrl?.takeIf { it.isNotBlank() }
                }
            }
            detail?.let {
                heroDetailCache[cacheKey] = it
            }
            if (url != null) {
                logoCache[cacheKey] = url
            } else {
                logoCache.remove(cacheKey)
            }
        } finally {
            logoLoadingByKey.remove(cacheKey)
        }
    }

    val displayedHeroItem = displayedFeaturedItem?.let { item ->
        val tmdbId = item.tmdbId
        val detail = tmdbId?.let { heroDetailCache["${item.type}:$it"] }
        val enriched = if (detail == null) {
            item
        } else {
            item.copy(
                imdbId = item.imdbId ?: detail.imdbId,
                year = item.year ?: detail.year,
                overview = item.overview?.takeIf { it.isNotBlank() } ?: detail.overview,
                posterUrl = item.posterUrl?.takeIf { it.isNotBlank() } ?: detail.posterUrl,
                backdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() } ?: detail.backdropUrl,
                logoUrl = item.logoUrl?.takeIf { it.isNotBlank() } ?: detail.logoUrl,
                rating = item.rating ?: detail.rating,
                voteCount = item.voteCount ?: detail.voteCount,
                runtime = item.runtime ?: detail.runtime,
                genres = if (item.genres.isNotEmpty()) item.genres else detail.genres,
                genreIds = if (item.genreIds.isNotEmpty()) item.genreIds else detail.genreIds,
                releaseDate = item.releaseDate?.takeIf { it.isNotBlank() } ?: detail.releaseDate,
                status = item.status?.takeIf { it.isNotBlank() } ?: detail.status,
                trailerKey = item.trailerKey?.takeIf { it.isNotBlank() } ?: detail.trailerKey,
                tagline = item.tagline?.takeIf { it.isNotBlank() } ?: detail.tagline,
                ratings = item.ratings ?: detail.ratings,
            )
        }
        if (!enriched.logoUrl.isNullOrBlank() || tmdbId == null) {
            enriched
        } else {
            logoCache["${item.type}:$tmdbId"]?.let { logoUrl -> enriched.copy(logoUrl = logoUrl) } ?: enriched
        }
    }

    val sectionTitle = when (contentTopRoute) {
        TvRoutes.MOVIES -> stringResource(R.string.nav_movies)
        TvRoutes.SHOWS -> stringResource(R.string.nav_tv_shows)
        TvRoutes.IPTV -> stringResource(R.string.tv_nav_iptv)
        TvRoutes.SPORTS -> stringResource(R.string.tv_nav_sports)
        TvRoutes.SEARCH -> stringResource(R.string.tv_nav_search)
        TvRoutes.LIBRARY -> stringResource(R.string.tv_nav_library)
        TvRoutes.SETTINGS -> stringResource(R.string.tv_nav_settings)
        else -> stringResource(R.string.nav_home)
    }
    val sectionSubtitle = when (contentTopRoute) {
        TvRoutes.MOVIES -> stringResource(R.string.tv_hero_subtitle_movies)
        TvRoutes.SHOWS -> stringResource(R.string.tv_hero_subtitle_shows)
        TvRoutes.IPTV -> stringResource(R.string.tv_hero_subtitle_iptv)
        TvRoutes.SPORTS -> "Newznab sport releases"
        TvRoutes.SEARCH -> stringResource(R.string.tv_hero_subtitle_search)
        TvRoutes.LIBRARY -> stringResource(R.string.tv_hero_subtitle_library)
        TvRoutes.SETTINGS -> stringResource(R.string.tv_hero_subtitle_settings)
        else -> ""
    }

    /* ── Navigation helpers ────────────────────────────────────────────────────────────── */
    val navigateToSeeAll: (String, String, String) -> Unit = remember(navController, selectedTopRoute) {
        { railKey: String, title: String, mediaType: String ->
            searchSeedQuery = null
            pushFocusReturnEntry(selectedTopRoute)
            navController.navigate(
                TvRoutes.seeAll(railKey = railKey, mediaType = mediaType, title = title),
            )
        }
    }

    fun requestContentFocusFromRail(route: String) {
        pendingContentEntryRoute = route
        contentEntryRequestNonce += 1
        val candidates = if (route == TvRoutes.SETTINGS) {
            orderedSettingsEntryCandidates(
                settingsEntryRequester = settingsEntryRequester,
                settingsMainEntryFocusRequester = settingsMainEntryFocusRequester,
                firstContentFocusRequester = firstContentFocusByRoute[TvRoutes.SETTINGS],
            )
        } else {
            if (
                route == TvRoutes.MOVIES ||
                route == TvRoutes.SHOWS ||
                route == TvRoutes.HOME ||
                route == TvRoutes.SEARCH
            ) {
                listOfNotNull(
                    lastFocusedContentByRoute[route],
                    firstContentFocusByRoute[route],
                )
            } else {
                listOfNotNull(
                    lastFocusedContentByRoute[route],
                    firstContentFocusByRoute[route],
                )
            }
        }
        rootScope.launch {
            for (candidate in candidates) {
                val beforeEpoch = contentFocusEpoch
                val movedFocus = runCatching {
                    candidate.requestFocus()
                    withFrameNanos { }
                    contentFocusEpoch != beforeEpoch && focusedContentRoute == route
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
        val candidate = railProgress
            ?: progressByMediaId[item.id]
            ?: item.tmdbId?.let { tmdbId ->
                progressByMediaId[tmdbId.toString()] ?: progressByMediaId["tmdb:$tmdbId"]
            }
        return candidate?.coerceIn(0f, 1f)
    }

    fun updateWatchedProgressHint(item: MediaItem, watched: Boolean) {
        val value = if (watched) TV_CONTEXT_WATCHED_THRESHOLD else 0f
        progressByMediaId[item.id] = value
        item.tmdbId?.let { tmdbId ->
            progressByMediaId[tmdbId.toString()] = value
            progressByMediaId["tmdb:$tmdbId"] = value
        }
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
        item.tmdbId?.let { tmdbId ->
            progressByMediaId[tmdbId.toString()] = clampedRatio
            progressByMediaId["tmdb:$tmdbId"] = clampedRatio
        }
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

    val playLabel = stringResource(R.string.common_play)
    val resumeLabelText = stringResource(R.string.tv_action_resume)
    val resumeNextEpisodeLabel = stringResource(R.string.player_next_episode)
    val startOverLabel = stringResource(R.string.tv_action_start_over)
    val goToSeasonsLabel = stringResource(R.string.tv_action_go_to_seasons)
    val addWatchlistLabel = stringResource(R.string.tv_action_add_watchlist)
    val removeWatchlistLabel = stringResource(R.string.tv_action_remove_watchlist)
    val addFavoritesLabel = stringResource(R.string.tv_action_add_favorites)
    val removeFavoritesLabel = stringResource(R.string.tv_action_remove_favorites)
    val markShowWatchedLabel = stringResource(R.string.tv_action_mark_show_watched)
    val markShowUnwatchedLabel = stringResource(R.string.tv_action_mark_show_unwatched)
    val markWatchedLabel = stringResource(R.string.tv_action_mark_watched)
    val markUnwatchedLabel = stringResource(R.string.tv_action_mark_unwatched)
    val moreLikeThisLabel = stringResource(R.string.tv_detail_more_like_this)
    val viewDetailsLabel = stringResource(R.string.tv_action_view_details)
    val trailerLabel = stringResource(R.string.tv_action_trailer)
    val chooseSourceLabel = stringResource(R.string.tv_action_choose_source)
    val unlockPremiumLabel = stringResource(TvPremiumAccess.UNLOCK_WITH_LIFETIME_LABEL_RES)

    val contextMenuActionsForItem: (MediaItem, Float?) -> List<TvMediaContextMenuAction> = { item, railProgress ->
        val progress = resolvedProgress(item, railProgress)
        val hasResume = progress != null &&
            progress > TV_CONTEXT_RESUME_THRESHOLD &&
            progress < TV_CONTEXT_WATCHED_THRESHOLD
        val isWatched = (progress ?: 0f) >= TV_CONTEXT_WATCHED_THRESHOLD
        val inWatchlist = watchlistViewModel.isInWatchlist(item.id)
        val isFavorite = item.favoriteMediaKey() in favoriteMediaKeys
        val isShow = item.type == MediaType.SERIES

        val resumeLabel = when {
            hasResume && isShow -> resumeNextEpisodeLabel
            hasResume -> resumeLabelText
            else -> startOverLabel
        }

        val watchedLabel = when {
            isShow && isWatched -> markShowUnwatchedLabel
            isShow -> markShowWatchedLabel
            isWatched -> markUnwatchedLabel
            else -> markWatchedLabel
        }

        buildList {
            add(
                TvMediaContextMenuAction(
                    id = TV_CONTEXT_ACTION_PLAY,
                    label = playLabel,
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
                add(TvMediaContextMenuAction(id = TV_CONTEXT_ACTION_GO_TO_SEASONS, label = goToSeasonsLabel))
            }
            add(
                TvMediaContextMenuAction(
                    id = TV_CONTEXT_ACTION_TOGGLE_WATCHLIST,
                    label = if (inWatchlist) removeWatchlistLabel else addWatchlistLabel,
                    isLocked = isFeatureLocked(TvEntitledFeature.WATCHLIST_EDIT),
                ),
            )
            add(
                TvMediaContextMenuAction(
                    id = TV_CONTEXT_ACTION_TOGGLE_FAVORITES,
                    label = if (isFavorite) removeFavoritesLabel else addFavoritesLabel,
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
            if (item.tmdbId != null) {
                add(
                    TvMediaContextMenuAction(
                        id = TV_CONTEXT_ACTION_MORE_LIKE_THIS,
                        label = moreLikeThisLabel,
                        isSecondary = true,
                        isLocked = isFeatureLocked(TvEntitledFeature.MORE_LIKE_THIS_PREMIUM),
                    ),
                )
            }
            add(
                TvMediaContextMenuAction(
                    id = TV_CONTEXT_ACTION_VIEW_DETAILS,
                    label = viewDetailsLabel,
                    isSecondary = true,
                ),
            )
            if (!item.trailerKey.isNullOrBlank()) {
                add(
                    TvMediaContextMenuAction(
                        id = TV_CONTEXT_ACTION_TRAILER,
                        label = trailerLabel,
                        isSecondary = true,
                    ),
                )
            }
            if (!item.imdbId.isNullOrBlank()) {
                add(
                    TvMediaContextMenuAction(
                        id = TV_CONTEXT_ACTION_CHOOSE_SOURCE,
                        label = chooseSourceLabel,
                        isSecondary = true,
                        isLocked = isFeatureLocked(TvEntitledFeature.CHOOSE_SOURCE_PREMIUM),
                    ),
                )
            }
            if (accessTier == AccessTier.FREE) {
                add(
                    TvMediaContextMenuAction(
                        id = TV_CONTEXT_ACTION_UNLOCK_LIFETIME,
                        label = unlockPremiumLabel,
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

                    TV_CONTEXT_ACTION_GO_TO_SEASONS -> {
                        pushFocusReturnEntry(selectedTopRoute)
                        navController.navigateToTvDetails(item, focusEpisodes = true)
                    }

                    TV_CONTEXT_ACTION_VIEW_DETAILS,
                    TV_CONTEXT_ACTION_CHOOSE_SOURCE -> {
                        pushFocusReturnEntry(selectedTopRoute)
                        navController.navigateToTvDetails(item)
                    }

                    TV_CONTEXT_ACTION_TOGGLE_WATCHLIST -> {
                        watchlistViewModel.toggleWatchlist(item)
                    }

                    TV_CONTEXT_ACTION_TOGGLE_FAVORITES -> {
                        val key = item.favoriteMediaKey()
                        val shouldFavorite = key !in favoriteMediaKeys
                        mediaFavoritesRepository.toggleFavorite(item)
                        TvNotificationQueue.post(
                            if (shouldFavorite) {
                                context.getString(R.string.tv_notification_favorites_added, item.title)
                            } else {
                                context.getString(R.string.tv_notification_favorites_removed, item.title)
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
                                    context.getString(R.string.tv_notification_marked_unwatched, item.title)
                                } else {
                                    context.getString(R.string.tv_notification_marked_watched, item.title)
                                },
                            )
                        }
                    }

                    TV_CONTEXT_ACTION_MORE_LIKE_THIS -> {
                        val tmdbId = item.tmdbId
                        if (tmdbId != null) {
                            val mediaType = if (item.type == MediaType.SERIES) "tv" else "movie"
                            searchSeedQuery = null
                            pushFocusReturnEntry(selectedTopRoute)
                            navController.navigate(
                                TvRoutes.seeAll(
                                    railKey = "more_like_${mediaType}_$tmdbId",
                                    mediaType = mediaType,
                                    title = "$moreLikeThisLabel: ${item.title}",
                                ),
                            )
                        }
                    }

                    TV_CONTEXT_ACTION_TRAILER -> {
                        val trailerKey = item.trailerKey
                        if (trailerKey.isNullOrBlank()) {
                            TvNotificationQueue.post(
                                context.getString(R.string.tv_notification_trailer_unavailable),
                                NotificationType.ERROR,
                            )
                        } else {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.youtube.com/watch?v=$trailerKey"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                                .onFailure {
                                    TvNotificationQueue.post(
                                        context.getString(R.string.tv_notification_trailer_unavailable),
                                        NotificationType.ERROR,
                                    )
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
            TvNotificationQueue.post(
                context.getString(R.string.tv_notification_welcome),
                NotificationType.INFO,
            )
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
        infoPanel = null,
        leftRail = {
            // Always compose the rail so railFocusRequester stays attached.
            // Screens reference it in focusProperties { left = railFocusRequester };
            // if the composable is removed, focusSearch crashes on Left press.
            TvNavRail(
                    destinations = tvTopDestinations,
                    selectedRoute = highlightedTopRoute,
                    activeRoute = selectedTopRoute,
                    isExpanded = false,
                    railFocusRequester = railFocusRequester,
                    preferredEntryRoute = pendingRailEntryRoute,
                    preferredEntryRequestNonce = pendingRailEntryRequestNonce,
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
                        if (hasFocus) {
                            focusedContentRoute = null
                            railInteractionEpoch++
                        }
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
                    onHighlight = { route ->
                        railInteractionEpoch++
                        pendingNavJob?.cancel()
                        pendingNavJob = null
                        highlightedTopRoute = route
                    },
                    onNavigate = { route ->
                        railInteractionEpoch++
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
                key(displayedHeroItem?.tmdbId, displayedHeroItem?.type) {
                    TvHeroBackground(featuredItem = displayedHeroItem)
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
                val activeTabRoute = contentTopRoute
                val tabContentVisible = !isSubRouteActive

                // The hero overlay's primary-action button is only attached to a
                // node when displayedFeaturedItem != null (TvHeroOverlay gates its
                // action row behind a non-null item). When the item is missing,
                // headerPrimaryActionRequester has no host node, so any focusProperties
                // edge that targets it (e.g. `up = …` or rail row UP redirect) will
                // throw "FocusRequester is not initialized" the moment the user
                // presses a direction key — the crash is caught and swallowed by
                // TvMainActivity, but Compose's focus state ends up stuck.
                // Pass null downstream when the requester isn't safe to target.
                val heroPrimaryActionRequester: FocusRequester? = null

                // Shared hero overlay content — only attached to the visible tab.
                val heroOverlayContent: (@Composable () -> Unit) = {
                    val heroItem = displayedHeroItem
                    val heroLogoLookupInFlight = heroItem?.tmdbId?.let { tmdbId ->
                        heroItem.logoUrl.isNullOrBlank() &&
                            logoLoadingByKey["${heroItem.type}:$tmdbId"] == true
                    } == true
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
                                    TvNotificationQueue.post(
                                        context.getString(R.string.tv_notification_trailer_unavailable),
                                        NotificationType.ERROR,
                                    )
                                } else {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.youtube.com/watch?v=$trailerKey"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    runCatching { context.startActivity(intent) }
                                        .onFailure {
                                            TvNotificationQueue.post(
                                                context.getString(R.string.tv_notification_trailer_unavailable),
                                                NotificationType.ERROR,
                                            )
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
                        logoLookupInFlight = heroLogoLookupInFlight,
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
                                    heroPrimaryActionRequester?.let { up = it }
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
                            headerFocusRequester = heroPrimaryActionRequester,
                            onMediaClick = { item ->
                                pushFocusReturnEntry(TvRoutes.HOME)
                                navController.navigateToTvDetails(item)
                            },
                            onPlayLocalFile = { item, absolutePath ->
                                // Single-OK path for downloaded items: jump
                                // straight to the player route, no detail
                                // detour. file:// URL goes through the same
                                // ExoPlayer flow as a remote URL.
                                pushFocusReturnEntry(TvRoutes.HOME)
                                val mediaType =
                                    if (item.type == MediaType.SERIES) "tv" else "movie"
                                navController.navigate(
                                    TvRoutes.player(
                                        url = "file://$absolutePath",
                                        fallbackUrl = "",
                                        title = item.title,
                                        mediaId = item.id,
                                        mediaType = mediaType,
                                        posterUrl = item.posterUrl.orEmpty(),
                                        backdropUrl = item.backdropUrl.orEmpty(),
                                        showTmdbId = if (mediaType == "tv") item.tmdbId else null,
                                        showImdbId = item.imdbId,
                                        // Prompt 24: if local playback fails (file moved /
                                        // codec mismatch), let PlayerScreen advance through
                                        // the chain instead of bouncing back to Home.
                                        autoSourceSelection = true,
                                    ),
                                )
                            },
                            onPlayLanRoute = { item, lanUrl ->
                                // Single-OK path for LAN-available items.
                                // PendingLanPlaybackHandoff was staged by
                                // TvHomeScreen — PlayerScreen consumes it
                                // before play() and attaches X-Torve-Lan-Auth.
                                pushFocusReturnEntry(TvRoutes.HOME)
                                val mediaType =
                                    if (item.type == MediaType.SERIES) "tv" else "movie"
                                navController.navigate(
                                    TvRoutes.player(
                                        url = lanUrl,
                                        fallbackUrl = "",
                                        title = item.title,
                                        mediaId = item.id,
                                        mediaType = mediaType,
                                        posterUrl = item.posterUrl.orEmpty(),
                                        backdropUrl = item.backdropUrl.orEmpty(),
                                        showTmdbId = if (mediaType == "tv") item.tmdbId else null,
                                        showImdbId = item.imdbId,
                                        // Prompt 24: if LAN playback fails (desktop hub
                                        // dropped, network blip), let PlayerScreen advance
                                        // to provider/redownload via trySwitchToStableSource
                                        // instead of dumping the user back to Home.
                                        autoSourceSelection = true,
                                    ),
                                )
                            },
                            onLiveChannelClick = { enriched ->
                                if (TvPremiumAccess.isPremiumLocked(
                                        TvEntitledFeature.STREAM_PLAYBACK,
                                        accessTier,
                                    )
                                ) {
                                    requestLifetimeUnlock(TvEntitledFeature.STREAM_PLAYBACK)
                                } else {
                                    navController.navigate(
                                        TvRoutes.livePlayer(
                                            channelUrl = enriched.channel.url,
                                            channelName = enriched.channel.name,
                                            groupName = enriched.channel.groupTitle.orEmpty(),
                                        ),
                                    ) { launchSingleTop = true }
                                }
                            },
                            onProviderBannerAction = {
                                navController.navigate(TvRoutes.SETTINGS)
                            },
                            onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.HOME] = it },
                            onContentFocused = { markContentFocused(TvRoutes.HOME, it) },
                            registerFocusHandle = { handle ->
                                if (handle == null) {
                                    focusHandlesByRoute.remove(TvRoutes.HOME)
                                } else {
                                    focusHandlesByRoute[TvRoutes.HOME] = handle
                                }
                            },
                            onMediaFocused = onBrowseMediaFocused,
                            progressResolver = ::resolvedProgress,
                            contextMenuActionsForItem = contextMenuActionsForItem,
                            onContextMenuAction = onContextMenuAction,
                            onSeeAll = { railKey, title ->
                                val mt = when {
                                    railKey.startsWith("upcoming_schedule") -> "tv"
                                    railKey.contains("movie") -> "movie"
                                    railKey.contains("show") || railKey.contains("tv") -> "tv"
                                    else -> "movie"
                                }
                                navigateToSeeAll(railKey, title, mt)
                            },
                            heroOverlay = homeHeroOverlay,
                            browseLayout = TvBrowseLayout.POSTER_ONLY,
                            shouldAutoFocus = pendingContentEntryRoute == TvRoutes.HOME,
                        )
                    }
                }

                /* ── Layer 1b: Other tabs — composed only when active ───────── */
                if (activeTabRoute != TvRoutes.HOME) {
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
                                        heroPrimaryActionRequester?.let { up = it }
                                    }
                                },
                        ) {
                            val tabHeroOverlay: (@Composable () -> Unit)? =
                                if (showHero && activeTabRoute in heroRoutes) heroOverlayContent else null

                            when (activeTabRoute) {
                                    TvRoutes.MOVIES -> TvMoviesScreen(
                                        railFocusRequester = railFocusRequester,
                                        headerFocusRequester = heroPrimaryActionRequester,
                                        heroOverlay = tabHeroOverlay,
                                        onMediaClick = { item ->
                                            pushFocusReturnEntry(TvRoutes.MOVIES)
                                            navController.navigateToTvDetails(item)
                                        },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.MOVIES] = it },
                                        onContentFocused = { markContentFocused(TvRoutes.MOVIES, it) },
                                        registerFocusHandle = { handle ->
                                            if (handle == null) {
                                                focusHandlesByRoute.remove(TvRoutes.MOVIES)
                                            } else {
                                                focusHandlesByRoute[TvRoutes.MOVIES] = handle
                                            }
                                        },
                                        onMediaFocused = onBrowseMediaFocused,
                                        onClearMediaFocus = { focusedMediaItem = null },
                                        progressResolver = ::resolvedProgress,
                                        contextMenuActionsForItem = contextMenuActionsForItem,
                                        onContextMenuAction = onContextMenuAction,
                                        onSeeAll = { railKey, title -> navigateToSeeAll(railKey, title, "movie") },
                                        initialSearchQuery = searchSeedQuery.takeIf { selectedTopRoute == TvRoutes.MOVIES },
                                        browseLayout = TvBrowseLayout.POSTER_ONLY,
                                        shouldAutoFocus = pendingContentEntryRoute == TvRoutes.MOVIES,
                                        autoFocusRequestNonce = contentEntryRequestNonce,
                                    )

                                    TvRoutes.SHOWS -> TvShowsScreen(
                                        railFocusRequester = railFocusRequester,
                                        headerFocusRequester = heroPrimaryActionRequester,
                                        heroOverlay = tabHeroOverlay,
                                        onMediaClick = { item ->
                                            pushFocusReturnEntry(TvRoutes.SHOWS)
                                            navController.navigateToTvDetails(item)
                                        },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.SHOWS] = it },
                                        onContentFocused = { markContentFocused(TvRoutes.SHOWS, it) },
                                        registerFocusHandle = { handle ->
                                            if (handle == null) {
                                                focusHandlesByRoute.remove(TvRoutes.SHOWS)
                                            } else {
                                                focusHandlesByRoute[TvRoutes.SHOWS] = handle
                                            }
                                        },
                                        onMediaFocused = onBrowseMediaFocused,
                                        onClearMediaFocus = { focusedMediaItem = null },
                                        progressResolver = ::resolvedProgress,
                                        contextMenuActionsForItem = contextMenuActionsForItem,
                                        onContextMenuAction = onContextMenuAction,
                                        onSeeAll = { railKey, title -> navigateToSeeAll(railKey, title, "tv") },
                                        initialSearchQuery = searchSeedQuery.takeIf { selectedTopRoute == TvRoutes.SHOWS },
                                        browseLayout = TvBrowseLayout.POSTER_ONLY,
                                        shouldAutoFocus = pendingContentEntryRoute == TvRoutes.SHOWS,
                                        autoFocusRequestNonce = contentEntryRequestNonce,
                                    )

                                    TvRoutes.SEARCH -> TvSearchScreen(
                                        railFocusRequester = railFocusRequester,
                                        onMediaClick = { item ->
                                            pushFocusReturnEntry(TvRoutes.SEARCH)
                                            navController.navigateToTvDetails(item)
                                        },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.SEARCH] = it },
                                        onContentFocused = { markContentFocused(TvRoutes.SEARCH, it) },
                                        initialQuery = searchSeedQuery.orEmpty(),
                                        shouldAutoFocus = pendingContentEntryRoute == TvRoutes.SEARCH,
                                    )

                                    TvRoutes.IPTV -> {
                                        val channelsViewModel: ChannelsViewModel = org.koin.compose.koinInject()
                                        TvIptvScreen(
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
                                                pendingSettingsAppLinkItemId = TvSettingsItemIds.LIBRARY_MANAGE_CHANNELS
                                                settingsDestination = TvSettingsDestination.MAIN
                                                settingsFocusStateMachine.selectedCategory = TvSettingsCategory.LIBRARY
                                                pendingNavJob?.cancel()
                                                pendingNavJob = null
                                                selectedTopRoute = TvRoutes.SETTINGS
                                                highlightedTopRoute = TvRoutes.SETTINGS
                                                confirmedTopRoute = TvRoutes.SETTINGS
                                                pendingRailEntryRoute = null
                                                pendingContentEntryRoute = TvRoutes.SETTINGS
                                                focusRestoreTrigger++
                                            },
                                            onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.IPTV] = it },
                                            onContentFocused = { markContentFocused(TvRoutes.IPTV, it) },
                                            shouldAutoFocus = pendingContentEntryRoute == TvRoutes.IPTV,
                                            isActive = true,
                                            isSubRouteActive = isSubRouteActive,
                                            isRailFocused = isRailFocused,
                                            isRailExpanded = isRailExpanded,
                                            onCollapseRail = { isRailExpanded = false },
                                            onNavigateUp = {
                                                moveFocusToRailForRoute(TvRoutes.IPTV)
                                            },
                                        )
                                    }

                                    TvRoutes.SPORTS -> TvSportsScreen(
                                        railFocusRequester = railFocusRequester,
                                        onPlayStream = { url, title, sizeBytes ->
                                            if (TvPremiumAccess.isPremiumLocked(TvEntitledFeature.STREAM_PLAYBACK, accessTier)) {
                                                requestLifetimeUnlock(TvEntitledFeature.STREAM_PLAYBACK)
                                            } else {
                                                pushFocusReturnEntry(TvRoutes.SPORTS)
                                                navController.navigate(
                                                    TvRoutes.player(
                                                        url = url,
                                                        fallbackUrl = "",
                                                        title = title,
                                                        mediaId = "sports:${title.hashCode()}",
                                                        mediaType = "sports",
                                                    ),
                                                ) { launchSingleTop = true }
                                            }
                                        },
                                        onOpenPandaSetup = {
                                            pendingPandaSetupReturnSettingsItemId = null
                                            navController.navigate(TvRoutes.PANDA_SETUP)
                                        },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.SPORTS] = it },
                                        onContentFocused = { markContentFocused(TvRoutes.SPORTS, it) },
                                    )

                                    TvRoutes.LIBRARY -> TvLibraryScreen(
                                        railFocusRequester = railFocusRequester,
                                        headerFocusRequester = heroPrimaryActionRequester,
                                        favoriteMediaItems = favoriteMediaItems,
                                        heroOverlay = tabHeroOverlay,
                                        onMediaClick = { item ->
                                            pushFocusReturnEntry(TvRoutes.LIBRARY)
                                            navController.navigateToTvDetails(item)
                                        },
                                        onFirstContentRequester = { firstContentFocusByRoute[TvRoutes.LIBRARY] = it },
                                        onContentFocused = { markContentFocused(TvRoutes.LIBRARY, it) },
                                        registerFocusHandle = { handle ->
                                            if (handle == null) {
                                                focusHandlesByRoute.remove(TvRoutes.LIBRARY)
                                            } else {
                                                focusHandlesByRoute[TvRoutes.LIBRARY] = handle
                                            }
                                        },
                                        onMediaFocused = onBrowseMediaFocused,
                                        progressResolver = ::resolvedProgress,
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
                                        onVodItemPlay = { channel, item ->
                                            if (TvPremiumAccess.isPremiumLocked(TvEntitledFeature.STREAM_PLAYBACK, accessTier)) {
                                                requestLifetimeUnlock(TvEntitledFeature.STREAM_PLAYBACK)
                                            } else {
                                                pushFocusReturnEntry(TvRoutes.LIBRARY)
                                                navController.navigate(
                                                    com.torve.android.tv.nav.TvRoutes.player(
                                                        url = channel.url,
                                                        fallbackUrl = "",
                                                        title = item.title,
                                                        mediaId = item.id,
                                                        mediaType = if (item.type == MediaType.SERIES) "tv" else "movie",
                                                        posterUrl = item.posterUrl.orEmpty(),
                                                        backdropUrl = item.backdropUrl.orEmpty(),
                                                    ),
                                                ) { launchSingleTop = true }
                                            }
                                        },
                                        onVodSeriesOpen = { channel, item ->
                                            pushFocusReturnEntry(TvRoutes.LIBRARY)
                                            val seriesId = channel.kodiProps["vod_series_id"]
                                                ?: channel.url.hashCode().toString()
                                            val cacheKey = "vod_series_${channel.playlistId}_${seriesId}_${System.currentTimeMillis()}"
                                            TvScreenCache.put(cacheKey, TvVodSeriesDetailsArgs(channel, item))
                                            navController.navigate(TvRoutes.vodSeriesDetails(cacheKey)) {
                                                launchSingleTop = true
                                            }
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
                                                onFirstContentRequester = { requester ->
                                                    firstContentFocusByRoute[TvRoutes.SETTINGS] = requester
                                                    if (pendingContentEntryRoute == TvRoutes.SETTINGS) {
                                                        focusRestoreTrigger++
                                                    }
                                                },
                                                onContentFocused = { markContentFocused(TvRoutes.SETTINGS, it) },
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
                                                    pendingPandaSetupReturnSettingsItemId = TvSettingsItemIds.ADVANCED_PANDA
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
                                                onNavigateToReportIssue = {
                                                    navController.navigate("bug_report_tv")
                                                },
                                                onNavigateToWatchStats = {
                                                    navController.navigate(TvRoutes.WATCH_STATS)
                                                },
                                                onNavigateToBetaProgram = {
                                                    navController.navigate(TvRoutes.BETA_PROGRAM)
                                                },
                                                onNavigateToPairingSignIn = {
                                                    navController.navigate("pairing_signin_tv")
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
                                                    settingsFocusStateMachine.selectedCategory = TvSettingsCategory.ACCOUNT
                                                    settingsFocusStateMachine.requestRestore(
                                                        itemId = TvSettingsItemIds.ACCOUNT_AUTH_ACCOUNT,
                                                        reason = "auth_success",
                                                    )
                                                    selectedTopRoute = TvRoutes.SETTINGS
                                                    highlightedTopRoute = TvRoutes.SETTINGS
                                                    confirmedTopRoute = TvRoutes.SETTINGS
                                                    pendingRailEntryRoute = null
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
                            pendingContentEntryRoute = null
                            pendingRailEntryRoute = null
                            navController.popBackStack()
                        },
                        onDetailsBack = {
                            pendingFocusBackRestore = popFocusReturnEntry()
                            navController.popBackStack()
                        },
                        onPairingSignInSuccess = {
                            focusReturnStack.clear()
                            pendingFocusBackRestore = null
                            pendingNavJob?.cancel()
                            pendingNavJob = null
                            suppressBackToHome = true
                            navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                            settingsDestination = TvSettingsDestination.MAIN
                            settingsFocusStateMachine.selectedCategory = TvSettingsCategory.ACCOUNT
                            settingsFocusStateMachine.requestRestore(
                                itemId = TvSettingsItemIds.ACCOUNT_AUTH_ACCOUNT,
                                reason = "phone_sign_in_success",
                            )
                            selectedTopRoute = TvRoutes.SETTINGS
                            highlightedTopRoute = TvRoutes.SETTINGS
                            confirmedTopRoute = TvRoutes.SETTINGS
                            pendingRailEntryRoute = null
                            pendingContentEntryRoute = TvRoutes.SETTINGS
                            focusRestoreTrigger++
                        },
                        onBeforeDetailsNavigateFromSeeAll = {
                            pushFocusReturnEntry(TvRoutes.SEE_ALL)
                        },
                        onVoiceSearchQuery = { query ->
                            focusReturnStack.clear()
                            pendingFocusBackRestore = null
                            searchSeedQuery = query
                            TvNotificationQueue.post(context.getString(R.string.tv_notification_search_query, query))
                            selectedTopRoute = TvRoutes.MOVIES
                            highlightedTopRoute = TvRoutes.MOVIES
                            confirmedTopRoute = TvRoutes.MOVIES
                            pendingContentEntryRoute = TvRoutes.MOVIES
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
                        onWatchStatsBack = {
                            Log.d("TvSettingsFocus", "route_return item=watch_stats reason=route_return")
                            pendingSettingsSubpageEntryRoute = null
                            settingsFocusStateMachine.selectedCategory = TvSettingsCategory.ABOUT
                            settingsFocusStateMachine.requestRestore(
                                itemId = TvSettingsItemIds.ABOUT_STATS,
                                reason = "route_return",
                            )
                            navController.popBackStack()
                        },
                        onPandaSetupBack = {
                            val returnItemId = pendingPandaSetupReturnSettingsItemId
                            if (returnItemId != null) {
                                Log.d("TvSettingsFocus", "route_return item=$returnItemId reason=panda_setup_return")
                                settingsDestination = TvSettingsDestination.MAIN
                                selectedTopRoute = TvRoutes.SETTINGS
                                highlightedTopRoute = TvRoutes.SETTINGS
                                confirmedTopRoute = TvRoutes.SETTINGS
                                pendingContentEntryRoute = TvRoutes.SETTINGS
                                settingsFocusStateMachine.requestRestore(
                                    itemId = returnItemId,
                                    reason = "panda_setup_return",
                                )
                                pendingPandaSetupReturnSettingsItemId = null
                                focusRestoreTrigger++
                            }
                            navController.popBackStack()
                        },
                        onRequestLifetimeUnlock = requestLifetimeUnlock,
                        onWatchedStatusChanged = { item, watched ->
                            updateWatchedProgressHint(item, watched)
                        },
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
        val restoreBlockerRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(30L)
            runCatching { restoreBlockerRequester.requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(200f)
                .background(com.torve.android.ui.theme.Obsidian.copy(alpha = 0.85f))
                .focusRequester(restoreBlockerRequester)
                .focusable()
                .onPreviewKeyEvent { true },
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
                    text = restoreProgress.message.ifBlank {
                        context.getString(R.string.tv_restore_account_data)
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = Snow,
                )
                if (restoreProgress.totalPlaylists > 0) {
                    Text(
                        text = context.getString(
                            R.string.tv_restore_playlists_count,
                            restoreProgress.restoredPlaylists,
                            restoreProgress.totalPlaylists,
                        ),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = com.torve.android.ui.theme.Silver,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    TvBackgroundWorkOverlay(backgroundWorkStatus)

    if (showCredentialTransferNudge) {
        TvCredentialTransferNudge(
            onGoToSettings = {
                showCredentialTransferNudge = false
                credentialNudgeDismissed = true
                pendingNavJob?.cancel()
                pendingNavJob = null
                pendingSettingsAppLinkItemId = TvSettingsItemIds.ACCOUNT_IMPORT_SETUP
                settingsDestination = TvSettingsDestination.MAIN
                settingsFocusStateMachine.selectedCategory = TvSettingsCategory.ACCOUNT
                if (isSubRouteActive) {
                    navController.popBackStack(TvRoutes.SUB_NAV_START, inclusive = false)
                }
                selectedTopRoute = TvRoutes.SETTINGS
                highlightedTopRoute = TvRoutes.SETTINGS
                confirmedTopRoute = TvRoutes.SETTINGS
                pendingContentEntryRoute = TvRoutes.SETTINGS
                pendingRailEntryRoute = null
                focusRestoreTrigger++
            },
            onDismiss = {
                showCredentialTransferNudge = false
                credentialNudgeDismissed = true
            },
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

private data class TvBackgroundWorkStatus(
    val visible: Boolean = false,
    val label: String = "",
    val blockNavigation: Boolean = false,
)

@Composable
private fun TvCredentialTransferNudge(
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val receiveRequester = remember { FocusRequester() }
    var receiveFocused by remember { mutableStateOf(false) }
    var closeFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(8) {
            withFrameNanos { }
            delay(40L)
            runCatching { receiveRequester.requestFocus() }
            if (receiveFocused) return@LaunchedEffect
        }
    }

    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 22.dp, end = 22.dp)
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Surface(
                modifier = Modifier.width(380.dp),
                color = Charcoal.copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .border(1.dp, AmberSubtle, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.recovery_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Snow,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.recovery_card_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Snow.copy(alpha = 0.82f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            modifier = Modifier
                                .onFocusChanged { closeFocused = it.isFocused },
                            onClick = onDismiss,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (closeFocused) Snow.copy(alpha = 0.14f) else Charcoal.copy(alpha = 0.2f),
                                contentColor = if (closeFocused) Snow else Snow.copy(alpha = 0.82f),
                            ),
                        ) {
                            Text(stringResource(R.string.common_close))
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            modifier = Modifier
                                .focusRequester(receiveRequester)
                                .onFocusChanged { receiveFocused = it.isFocused },
                            onClick = onGoToSettings,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (receiveFocused) Snow else AmberSubtle,
                                contentColor = if (receiveFocused) Charcoal else Snow,
                            ),
                        ) {
                            Text(stringResource(R.string.credential_transfer_nudge_go_to_settings))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberTvBackgroundWorkStatus(
    context: Context,
): androidx.compose.runtime.State<TvBackgroundWorkStatus> {
    return produceState(initialValue = TvBackgroundWorkStatus(), context) {
        val manager = androidx.work.WorkManager.getInstance(context.applicationContext)
        while (true) {
            val infos = withContext(Dispatchers.IO) {
                runCatching {
                    manager.getWorkInfosByTag(BackgroundWork.TAG_HEAVY_PRELOAD).get()
                }.getOrDefault(emptyList())
            }
            val activeInfos = infos.filter { info ->
                info.state == androidx.work.WorkInfo.State.RUNNING ||
                    info.state == androidx.work.WorkInfo.State.ENQUEUED ||
                    info.state == androidx.work.WorkInfo.State.BLOCKED
            }
            val runningInfo = activeInfos.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
                ?: activeInfos.firstOrNull()
            value = if (runningInfo == null) {
                TvBackgroundWorkStatus()
            } else {
                val isRunning = runningInfo.state == androidx.work.WorkInfo.State.RUNNING
                TvBackgroundWorkStatus(
                    visible = isRunning,
                    label = runningInfo.progress.getString(BackgroundWork.KEY_LABEL)
                        ?: "Preparing cached content",
                    blockNavigation = isRunning &&
                        runningInfo.progress.getBoolean(BackgroundWork.KEY_BLOCK_NAVIGATION, false),
                )
            }
            delay(1_000L)
        }
    }
}

@Composable
private fun TvBackgroundWorkOverlay(status: TvBackgroundWorkStatus) {
    if (!status.visible) return

    val blockerRequester = remember { FocusRequester() }
    LaunchedEffect(status.blockNavigation) {
        if (status.blockNavigation) {
            delay(30L)
            runCatching { blockerRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(250f)
            .then(
                if (status.blockNavigation) {
                    Modifier
                        .focusRequester(blockerRequester)
                        .focusable()
                        .onPreviewKeyEvent { true }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.TopEnd,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .width(280.dp)
                .padding(top = 18.dp, end = 20.dp)
                .background(Charcoal.copy(alpha = 0.90f), RoundedCornerShape(12.dp))
                .border(1.dp, AmberSubtle, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = status.label,
                color = Snow,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth(),
                color = com.torve.android.ui.theme.Amber,
                trackColor = com.torve.android.ui.theme.Steel.copy(alpha = 0.45f),
            )
        }
    }
}

private const val TV_PREF_KEY_MEDIA_FAVORITES = "tv_media_favorites"
private const val TV_CONTEXT_DEFAULT_DURATION_MS = 120L * 60L * 1000L
private const val TV_CONTEXT_RESUME_THRESHOLD = 0.03f
private const val TV_CONTEXT_WATCHED_THRESHOLD = 0.9f
private const val TV_DEVICE_ACCESS_MONITOR_INTERVAL_MS = 30_000L
private const val TV_CHANNEL_RELOAD_DEDUPE_MS = 4_000L

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

private fun isRemoteDeviceRevocationReason(reason: String?): Boolean {
    return when (reason?.trim()?.lowercase()) {
        "device_removed",
        "device_revoked",
        "device_deactivated",
        "device_inactive",
        "removed_from_active_devices",
        "active_device_removed",
        -> true
        else -> false
    }
}

private data class LegacyFavoriteMediaRef(
    val type: MediaType,
    val tmdbId: Int,
)

private fun String.toLegacyFavoriteMediaRefOrNull(): LegacyFavoriteMediaRef? {
    val typeRaw = substringBefore(":", missingDelimiterValue = "")
    val tmdbId = substringAfter(":", missingDelimiterValue = "").toIntOrNull() ?: return null
    val type = runCatching { MediaType.valueOf(typeRaw) }.getOrNull() ?: return null
    if (type != MediaType.MOVIE && type != MediaType.SERIES) return null
    return LegacyFavoriteMediaRef(type = type, tmdbId = tmdbId)
}
