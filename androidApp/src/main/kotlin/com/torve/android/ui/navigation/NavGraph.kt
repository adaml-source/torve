package com.torve.android.ui.navigation

import com.torve.android.ui.community.parityLabel as localizedUiText
import com.torve.android.ui.components.mobileGlassSurface

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.runtime.key
import com.torve.domain.model.MediaItem
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.R
import com.torve.android.background.BackgroundWork
import com.torve.android.deeplink.TorveAppLink
import com.torve.android.deeplink.TorveAppLinkTarget
import com.torve.android.ui.replays.webReplayRoutes
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.torve.android.ui.auth.LoginScreen
import com.torve.android.ui.calendar.CalendarScreen
import com.torve.android.ui.catalog.CatalogScreen
import com.torve.android.ui.detail.DetailScreen
import com.torve.android.ui.discover.DiscoverScreen
import com.torve.android.ui.home.HomeScreen
import com.torve.android.ui.detail.PersonScreen
import com.torve.android.ui.download.DownloadCatalogueScreen
import com.torve.android.ui.download.DownloadScreen
import com.torve.android.ui.download.DownloadedShowDetailScreen
import com.torve.android.ui.channels.ChannelsScreen
import com.torve.android.ui.legal.HelpScreen
import com.torve.android.ui.legal.LegalScreen
import com.torve.android.ui.legal.PrivacyPolicyScreen
import com.torve.android.ui.mood.MoodMatcherScreen
import com.torve.android.ui.player.PlayerScreen
import com.torve.android.ui.player.ActivePlaybackSession
import com.torve.android.ui.player.ActivePlaybackState
import com.torve.android.ui.profile.ProfileScreen

import com.torve.android.ui.search.SearchScreen
import com.torve.android.ui.seeall.SeeAllScreen
import com.torve.android.ui.onboarding.VerifyEmailGateScreen
import com.torve.android.ui.settings.RegexPatternsScreen
import com.torve.android.ui.settings.SettingsScreen
import com.torve.android.ui.sync.AccountScreen
import com.torve.android.ui.sync.DevicesScreen
import com.torve.android.ui.settings.StreamGroupsScreen
import com.torve.android.ui.settings.CustomSectionEditorScreen
import com.torve.android.ui.settings.DiagnosticsScreen
import com.torve.android.ui.settings.IntegrationsScreen
import com.torve.android.ui.settings.AutomationAdministrationScreen
import com.torve.android.ui.settings.HomeLayoutScreen
import com.torve.android.ui.settings.MdbListSettingsScreen
import com.torve.android.ui.settings.CardStyleSettingsScreen
import com.torve.android.ui.settings.RatingSettingsScreen
import com.torve.android.ui.settings.SensitiveMaterialSettingsScreen
import com.torve.android.ui.settings.StatusRepairScreen
import com.torve.android.ui.settings.StreamingServicesSettingsScreen
import com.torve.android.ui.support.BugReportScreen
import com.torve.android.ui.transfer.providerSettingsRouteFor
import com.torve.android.ui.stats.StatsScreen
import com.torve.android.ui.watchlist.WatchlistScreen
import com.torve.android.ui.device.DeviceLimitReachedScreen
import com.torve.android.ui.device.ManageDevicesScreen
import com.torve.android.premium.PremiumAccess
import com.torve.android.premium.PremiumFeature
import com.torve.android.premium.rememberEffectivePremiumAccessTier
import com.torve.android.sync.SyncCoordinator
import com.torve.android.ui.subscription.PaywallScreen
import com.torve.android.ui.tv.TvHomeScreen
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.MediaType
import com.torve.android.ui.community.openExtensionDetailIfNeeded
import com.torve.android.ui.community.extensionDetailSeed
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.data.ai.KeywordSearchService
import com.torve.data.auth.AuthClient
import com.torve.data.contentpolicy.ContentPolicyCacheInvalidationCoordinator
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.repository.MediaFavoritesRepository
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.catalog.CatalogViewModel
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.android.ui.setup.SourceSetupScreen
import com.torve.android.ui.setup.UsenetSetupScreen
import com.torve.android.ui.setup.SourceSetupRoutes
import com.torve.presentation.setup.SetupWizardViewModel
import com.torve.presentation.search.SearchViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import com.torve.presentation.home.HomeViewModel
import org.koin.compose.koinInject
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Safe detail navigation — guards against null tmdbId
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

private fun NavHostController.navigateToDetail(item: com.torve.domain.model.MediaItem, season: Int? = null, episode: Int? = null) {
    if (com.torve.android.ui.community.CommunityNotices.handle(item)) return
    com.torve.android.ui.components.ContentLaunchArtworkStore.show(item)
    if (openExtensionDetailIfNeeded(item, season = season, episode = episode)) return
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    // Addon items arrive with namespaced ids like "tmdb:1523145" or "tt0111161"
    // but leave `tmdbId`/`imdbId` unset. Parse them out of `id` so those
    // catalog shelves (Trending, Latest Releases, Popular, Language, Year,
    // etc.) are clickable.
    val tmdbId = item.tmdbId ?: item.id.extractTmdbIdOrNull()
    val imdbId = item.imdbId?.takeIf { it.isNotBlank() } ?: item.id.extractImdbIdOrNull()
    when {
        tmdbId != null -> navigate("detail/$type/$tmdbId?season=${season ?: -1}&episode=${episode ?: -1}")
        imdbId != null -> navigate("detail_imdb/$type/${Uri.encode(imdbId)}?season=${season ?: -1}&episode=${episode ?: -1}")
        else -> return
    }
}

private fun NavHostController.navigateToLifetimeUnlock(feature: PremiumFeature) {
    navigate("paywall?feature=${Uri.encode(feature.name)}")
}

private fun ActivePlaybackSession.playerRoute(): String =
    "player?url=${Uri.encode(url)}" +
        "&title=${Uri.encode(title)}" +
        "&mediaId=${Uri.encode(mediaId)}" +
        "&mediaType=${Uri.encode(mediaType)}" +
        "&posterUrl=${Uri.encode(posterUrl)}" +
        "&backdropUrl=${Uri.encode(backdropUrl)}" +
        "&seasonNumber=${seasonNumber ?: -1}" +
        "&episodeNumber=${episodeNumber ?: -1}" +
        "&showTmdbId=${showTmdbId ?: -1}" +
        "&showImdbId=${Uri.encode(showImdbId.orEmpty())}" +
        "&fallbackUrl=${Uri.encode(fallbackUrl)}" +
        "&autoSourceSelection=$autoSourceSelection"

@Composable
private fun ResolveExternalDetailRoute(
    type: String,
    imdbId: String,
    metadataRepo: MetadataRepository,
    onResolved: (String, Int) -> Unit,
    onBack: () -> Unit,
) {
    var resolutionError by remember(imdbId, type) { mutableStateOf<String?>(null) }

    LaunchedEffect(imdbId, type) {
        val resolved = runCatching {
            metadataRepo.findByImdbId(imdbId, preferredType = type)
        }.getOrNull()
        val resolvedTmdbId = resolved?.tmdbId
        if (resolvedTmdbId != null) {
            val resolvedType = if (resolved.type == MediaType.SERIES) "tv" else "movie"
            onResolved(resolvedType, resolvedTmdbId)
        } else {
            resolutionError = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            if (resolutionError == null) {
                androidx.compose.material3.CircularProgressIndicator(color = Amber)
                Text(
                    text = stringResource(R.string.detail_resolving),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            } else {
                Text(
                    text = resolutionError ?: stringResource(R.string.detail_open_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                com.torve.android.ui.components.BackButton(onClick = onBack)
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Navigation Tabs
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

data class NavTab(
    val route: String,
    val labelResId: Int,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
)

private data class BackgroundRefreshStatus(
    val label: String,
    val progress: Float,
)

@Composable
private fun PersistentMobileTabPane(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    val savedState = rememberSaveableStateHolder()
    if (visible) savedState.SaveableStateProvider("content") {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

private val navTabDefs = listOf(
    NavTab("home", R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    NavTab("live_tv", R.string.nav_channels, Icons.Filled.LiveTv, Icons.Outlined.LiveTv),
    NavTab("search", R.string.nav_search, Icons.Filled.Search, Icons.Outlined.Search),
    NavTab("calendar_tab", R.string.nav_calendar, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    NavTab("watchlist_tab", R.string.nav_library, Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
    NavTab("profile_tab", R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

private const val MOBILE_ROOT_ROUTE = "mobile_root"
private const val MOBILE_HOME_ROUTE = "home"
private const val VERIFY_EMAIL_ROUTE = "verify_email"
private const val SETUP_CHOICE_ROUTE = "setup_choice"
private const val SETUP_ROUTE = "setup"
private const val TV_HOME_ROUTE = "tv_home"
private const val DEVICE_ACCESS_MONITOR_INTERVAL_MS = 30_000L

private fun mobileOnboardingCompleteKey(userId: String): String =
    "mobile_onboarding_complete_$userId"

private fun mobileOnboardingRequiredKey(userId: String): String =
    "mobile_onboarding_required_$userId"

private fun String?.requiresDeviceManagement(): Boolean =
    this in setOf(
        "device_cap_reached",
        "activation_slot_exhausted",
        "no_activation_slots",
        "swap_limit_reached",
    )

private fun String?.isRemoteDeviceRevocationReason(): Boolean =
    when (this?.trim()?.lowercase()) {
        "device_removed",
        "device_revoked",
        "device_deactivated",
        "device_inactive",
        "removed_from_active_devices",
        "active_device_removed",
        -> true
        else -> false
    }

private fun String?.isAuthOrOnboardingRoute(): Boolean =
    this in setOf(
        "login",
        VERIFY_EMAIL_ROUTE,
        SETUP_CHOICE_ROUTE,
        SETUP_ROUTE,
        "setup_guided",
    )

private val tvNavTabDefs = listOf(
    NavTab(TV_HOME_ROUTE, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    NavTab("movies", R.string.nav_movies, Icons.Filled.Movie, Icons.Outlined.Movie),
    NavTab("tv_shows", R.string.nav_tv_shows, Icons.Filled.Tv, Icons.Outlined.Tv),
    NavTab("live_tv", R.string.nav_channels, Icons.Filled.LiveTv, Icons.Outlined.LiveTv),
    NavTab("watchlist_tab", R.string.nav_library, Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
    NavTab("profile_tab", R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Main Nav Graph
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun TorveNavGraph(
    externalNavController: NavHostController = rememberNavController(),
    isTvMode: Boolean = false,
    appLink: TorveAppLink? = null,
    onAppLinkConsumed: () -> Unit = {},
    resetMobileHomeScrollOnLaunch: Boolean = false,
    episodeAlert: com.torve.android.notification.EpisodeAlertTarget? = null,
    onEpisodeAlertConsumed: () -> Unit = {},
    storeUpdates: com.torve.android.update.StoreUpdateController? = null,
) {
    val mobileSettingsViewModel: com.torve.presentation.settings.SettingsViewModel = koinInject()
    val mobileSettings by mobileSettingsViewModel.state.collectAsState()
    val navigationOwner = mobileSettings.personalDataUserId
    var mobileSelectedTab by rememberSaveable(navigationOwner) { mutableStateOf(MOBILE_HOME_ROUTE) }
    // Each destination owns its complete stack, including detail routes and saved UI state.
    val phoneControllers: Map<String, NavHostController> = if (isTvMode) emptyMap() else
        listOf(MOBILE_HOME_ROUTE, "movies", "tv_shows", "live_tv", "search", "calendar_tab", "watchlist_tab", "profile_tab")
            .associateWith { tab -> key(navigationOwner, tab) { rememberNavController() } }
    val navController = if (isTvMode) externalNavController else phoneControllers.getValue(mobileSelectedTab)
    val tabState = key(navigationOwner) { androidx.compose.runtime.saveable.rememberSaveableStateHolder() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var pendingMobileHomeScrollReset by remember(resetMobileHomeScrollOnLaunch) {
        mutableStateOf(resetMobileHomeScrollOnLaunch)
    }
    LaunchedEffect(resetMobileHomeScrollOnLaunch, isTvMode) {
        if (resetMobileHomeScrollOnLaunch && !isTvMode) {
            mobileSelectedTab = MOBILE_HOME_ROUTE
        }
    }

    val persistentNavigationRoutes = setOf(MOBILE_ROOT_ROUTE, MOBILE_HOME_ROUTE,
        "movies", "tv_shows", "search", "detail", "detail_imdb", "person", "settings",
        "collections", "your_shows", "library_tools", "files", "watch_stats", "watching_hub",
        "home_layout", "custom_sections", "custom_section_editor", "card_style_settings",
        "rating_settings", "appearance_refinements", "streaming_services", "stream_groups",
        "addon_catalog", "manage_panda", "panda_setup", "regex_patterns", "mdblist", "integrations",
        "diagnostics", "downloads", "download_show", "download_catalog", "backup", "trakt_lists",
        "connection_center", "catalog_groups", "catalog_group", "library_collection", "jellyfin_library", "seeall", "provider", "genre", "profiles", "calendar", "sports")
    val showBottomBar = !isTvMode && currentRoute?.substringBefore('/')?.substringBefore('?') in persistentNavigationRoutes
    val showTvNavRail = isTvMode && currentRoute in tvNavTabDefs.map { it.route }
    androidx.activity.compose.BackHandler(enabled = !isTvMode && currentRoute == MOBILE_ROOT_ROUTE && mobileSelectedTab != MOBILE_HOME_ROUTE) {
        mobileSelectedTab = MOBILE_HOME_ROUTE
    }


    val setupViewModel: SetupWizardViewModel = koinInject()
    val setupCoordinator: com.torve.presentation.setup.SetupWizardCoordinator = koinInject()
    val setupSummary by setupCoordinator.summary.collectAsState()
    val authClient: AuthClient = koinInject()
    val authUser by authClient.authUserFlow.collectAsState()
    val navScope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val watchlistViewModel: WatchlistViewModel = koinInject()
    val mediaFavoritesRepository: MediaFavoritesRepository = koinInject()
    val homeViewModel: HomeViewModel = koinInject()
    val mobileLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val mobileLifecycleState by mobileLifecycle.lifecycle.currentStateFlow.collectAsState()
    val requestMonitor: com.torve.presentation.library.LibraryRequestMonitor = koinInject()
    val requestSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
    fun openContinueWatching(progress: com.torve.domain.model.WatchProgress) {
        if (progress.mediaId.startsWith("jf_")) {
            navScope.launch {
                try {
                    val jellyfin: com.torve.data.integrations.JellyfinLibraryOverlayService = org.koin.mp.KoinPlatform.getKoin().get()
                    val nativeId = progress.mediaId.removePrefix("jf_")
                    val playableId = if (progress.mediaType == MediaType.SERIES) {
                        val episode = jellyfin.getSeriesEpisodes(nativeId).firstOrNull {
                            it.parentIndexNumber == progress.seasonNumber && it.indexNumber == progress.episodeNumber
                        }
                        if (episode == null) { requestSnackbar.showSnackbar("This library episode is unavailable"); return@launch }
                        episode.id
                    } else nativeId
                    val route = jellyfin.buildAuthenticatedStream(playableId)
                    if (route == null) { requestSnackbar.showSnackbar("Jellyfin is disabled or disconnected"); return@launch }
                    com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff.stage(route)
                    navController.navigate(
                        "player?url=${Uri.encode(route.url)}&title=${Uri.encode(progress.title)}" +
                            "&mediaId=${Uri.encode(progress.mediaId)}&mediaType=${if (progress.mediaType == MediaType.SERIES) "series" else "movie"}" +
                            "&posterUrl=${Uri.encode(progress.posterUrl.orEmpty())}&backdropUrl=${Uri.encode(progress.backdropUrl.orEmpty())}" +
                            "&seasonNumber=${progress.seasonNumber ?: -1}&episodeNumber=${progress.episodeNumber ?: -1}" +
                            "&startPositionMs=${progress.positionMs}")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { requestSnackbar.showSnackbar("Unable to open library copy") }
            }
        } else {
            val type = if (progress.mediaType == MediaType.SERIES) "tv" else "movie"
            val id = progress.mediaId.extractTmdbIdOrNull()
            if (id == null) {
                if (progress.mediaId.matches(Regex("tt[0-9]+"))) navController.navigate("detail_imdb/$type/${Uri.encode(progress.mediaId)}?season=${progress.seasonNumber ?: -1}&episode=${progress.episodeNumber ?: -1}")
                return
            }
            val episode = if (type == "tv" && progress.seasonNumber != null && progress.episodeNumber != null)
                "?season=${progress.seasonNumber}&episode=${progress.episodeNumber}" else ""
            navController.navigate("detail/$type/$id$episode")
        }
    }

    LaunchedEffect(authUser?.id, mobileSettings.personalDataUserId, mobileLifecycleState,
        mobileSettings.optionalAppFeaturesLoaded, mobileSettings.optionalAppFeatures.arrStack, isTvMode) {
        if (isTvMode || authUser == null || !mobileSettings.optionalAppFeaturesLoaded ||
            !mobileSettings.optionalAppFeatures.arrStack ||
            !mobileLifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@LaunchedEffect
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            for (notice in requestMonitor.poll()) {
                requestSnackbar.showSnackbar(notice.message, duration = androidx.compose.material3.SnackbarDuration.Long)
                requestMonitor.acknowledge(notice.key)
            }
            delay(30_000L)
        }
    }
    LaunchedEffect(mobileSettings.personalDataUserId, mobileSettings.optionalAppFeaturesLoaded, mobileSettings.optionalAppFeatures.channels) {
        if (!mobileSettings.optionalAppFeaturesLoaded) return@LaunchedEffect
        val channels: com.torve.presentation.channels.ChannelsViewModel = org.koin.mp.KoinPlatform.getKoin().get()
        if (mobileSettings.optionalAppFeatures.channels) {
            com.torve.android.epg.EpgWarmupWorker.schedule(appContext)
        } else {
            com.torve.android.epg.EpgWarmupWorker.cancel(appContext)
            channels.stopBackgroundUpdates()
            // The Channels tab remains reachable for setup even before it is enabled.
        }
    }
    val searchViewModel: SearchViewModel = koinInject()
    val subscriptionViewModel: SubscriptionViewModel = koinInject()
    val syncCoordinator: SyncCoordinator = koinInject()
    val accountSessionCoordinator: com.torve.presentation.session.AccountSessionCoordinator = koinInject()
    val watchlistState by watchlistViewModel.state.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val syncState by syncCoordinator.state.collectAsState()
    var backgroundRefreshStatus by remember { mutableStateOf<BackgroundRefreshStatus?>(null) }
    var didInitialWatchlistSync by remember { mutableStateOf(false) }
    var mobileOnboardingComplete by rememberSaveable { mutableStateOf(false) }
    var mobileOnboardingRequired by rememberSaveable { mutableStateOf(false) }
    val dest = if (isTvMode) TV_HOME_ROUTE else MOBILE_ROOT_ROUTE
    val accessTier = rememberEffectivePremiumAccessTier(
        subscriptionTier = subscriptionState.subscription?.tier,
        subscriptionIsPro = subscriptionState.isPro,
    )
    val isLocked: (PremiumFeature) -> Boolean = remember(accessTier) {
        { feature -> PremiumAccess.isPremiumLocked(feature, accessTier) }
    }
    LaunchedEffect(appContext) {
        val workManager = WorkManager.getInstance(appContext)
        while (true) {
            val active = runCatching {
                withContext(Dispatchers.IO) {
                    workManager.getWorkInfosByTag(BackgroundWork.TAG_HEAVY_PRELOAD)
                        .get()
                        .filter {
                            it.state == WorkInfo.State.ENQUEUED ||
                                it.state == WorkInfo.State.RUNNING ||
                                it.state == WorkInfo.State.BLOCKED
                        }
                        .maxByOrNull {
                            it.progress.getFloat(BackgroundWork.KEY_PROGRESS, -1f)
                        }
                }
            }.getOrNull()
            backgroundRefreshStatus = active?.let { workInfo ->
                BackgroundRefreshStatus(
                    label = workInfo.progress.getString(BackgroundWork.KEY_LABEL)
                        ?: "Refreshing in background",
                    progress = workInfo.progress
                        .getFloat(BackgroundWork.KEY_PROGRESS, 0f)
                        .coerceIn(0f, 1f),
                )
            }
            delay(750L)
        }
    }
    val requestLifetimeUnlock: (PremiumFeature) -> Unit = remember(
        navController,
        subscriptionState.hasEntitlement,
        subscriptionState.isDeviceActivated,
        subscriptionState.deviceBlockReason,
        subscriptionState.deviceCapReached,
    ) {
        { feature ->
            val state = subscriptionState
            when {
                // Fresh-install race: the very first /me/access-state call
                // beat device registration to the backend, so we cached
                // device_not_registered for an account that has normal
                // access. Refreshing now will pick up the post-registration
                // state and the technical block will lift on the next tap.
                // Routing to the compatibility info screen here is misleading.
                state.hasEntitlement && state.deviceBlockReason == "device_not_registered" -> {
                    subscriptionViewModel.refreshAccess()
                }
                // User has normal access but their technical device limit is
                // full; managing devices is the actual path forward.
                state.hasEntitlement && state.deviceCapReached -> {
                    navController.navigate("device_limit_reached")
                }
                // User has normal access but this device isn't activated for
                // any other reason (e.g. activation_slot_exhausted); send
                // them to the device manager.
                state.hasEntitlement &&
                    !state.isDeviceActivated &&
                    state.deviceBlockReason.requiresDeviceManagement() -> {
                    navController.navigate("manage_devices")
                }
                state.hasEntitlement && !state.isDeviceActivated -> {
                    subscriptionViewModel.refreshAccess()
                }
                // Legacy fallback: show the free-software compatibility surface.
                else -> {
                    navController.navigateToLifetimeUnlock(feature)
                }
            }
        }
    }
    val canAccessManageDevicesSurface = subscriptionState.isLoggedIn

    LaunchedEffect(syncState.blockedFeature, currentRoute) {
        val blockedFeature = syncState.blockedFeature ?: return@LaunchedEffect
        if (currentRoute.isAuthOrOnboardingRoute()) {
            syncCoordinator.clearBlockedFeature()
            return@LaunchedEffect
        }
        if (currentRoute?.startsWith("paywall") != true) {
            requestLifetimeUnlock(blockedFeature)
        }
        syncCoordinator.clearBlockedFeature()
    }

    var handledDeviceRevocationForUser by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(
        authUser?.id,
        subscriptionState.hasEntitlement,
        subscriptionState.isDeviceActivated,
        subscriptionState.deviceBlockReason,
    ) {
        val userId = authUser?.id?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (handledDeviceRevocationForUser == userId) return@LaunchedEffect
        if (
            subscriptionState.hasEntitlement &&
            !subscriptionState.isDeviceActivated &&
            subscriptionState.deviceBlockReason.isRemoteDeviceRevocationReason()
        ) {
            handledDeviceRevocationForUser = userId
            accountSessionCoordinator.clearLocalAccountData(reason = "device_revoked")
            authClient.logout()
            navController.navigate(MOBILE_ROOT_ROUTE) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(authUser?.id) {
        if (authUser?.id.isNullOrBlank()) return@LaunchedEffect
        while (true) {
            delay(DEVICE_ACCESS_MONITOR_INTERVAL_MS)
            subscriptionViewModel.loadSubscription()
        }
    }

    val metadataRepo: MetadataRepository = koinInject()
    val keywordSearchService: KeywordSearchService = koinInject()
    val prefsRepo: com.torve.domain.repository.PreferencesRepository = koinInject()
    val watchProgressRepo: com.torve.domain.repository.WatchProgressRepository = koinInject()
    val ratingsEnricher: RatingsEnricher = koinInject()
    val integrationSecretStore: IntegrationSecretStore = koinInject()
    val contentPolicyRepository: ContentPolicyRepository = koinInject()
    val contentPolicyFilter: ContentPolicyFilter = koinInject()
    val invalidationCoordinator: ContentPolicyCacheInvalidationCoordinator = koinInject()

    fun markMobileOnboardingComplete() {
        val userId = authUser?.id ?: return
        mobileOnboardingComplete = true
        mobileOnboardingRequired = false
        navScope.launch {
            prefsRepo.setString(mobileOnboardingCompleteKey(userId), "true")
            prefsRepo.remove(mobileOnboardingRequiredKey(userId))
        }
    }

    fun markMobileOnboardingRequired() {
        val userId = authUser?.id ?: return
        mobileOnboardingRequired = true
        navScope.launch {
            prefsRepo.setString(mobileOnboardingRequiredKey(userId), "true")
        }
    }

    LaunchedEffect(authUser?.id) {
        val userId = authUser?.id
        setupCoordinator.load()
        if (userId == null) {
            mobileOnboardingComplete = false
            mobileOnboardingRequired = false
        } else {
            mobileOnboardingComplete = prefsRepo.getString(mobileOnboardingCompleteKey(userId)) == "true"
            mobileOnboardingRequired = prefsRepo.getString(mobileOnboardingRequiredKey(userId)) == "true"
        }
    }

    // A successful register emits AuthEvent.Registered exactly once;
    // mark onboarding required so the post-verify routing lands the
    // user on the setup choice instead of straight on Home. Plain
    // login doesn't emit this event — a returning configured user
    // should never see the setup flow as a side effect of signing in.
    LaunchedEffect(Unit) {
        authClient.authEvents.collect { event ->
            if (event is com.torve.data.auth.AuthEvent.Registered) {
                mobileOnboardingRequired = true
                navScope.launch {
                    prefsRepo.setString(mobileOnboardingRequiredKey(event.userId), "true")
                }
            }
        }
    }

    LaunchedEffect(
        authUser?.id,
        authUser?.isVerified,
        mobileOnboardingComplete,
        mobileOnboardingRequired,
        setupSummary.canStartWatching,
        subscriptionState.hasEntitlement,
        subscriptionState.isDeviceActivated,
        subscriptionState.deviceBlockReason,
        subscriptionState.deviceCapReached,
        currentRoute,
        isTvMode,
    ) {
        if (isTvMode) return@LaunchedEffect
        val user = authUser ?: return@LaunchedEffect

        // Delegate the destination decision to the pure router so
        // production matches the tested rules in
        // shared/.../MobilePostAuthRouter. Anything trust-leak related
        // (forced-setup-route on returning users, accidental device
        // management on stale device_not_registered) regresses against
        // MobilePostAuthRouterTest before the user ever sees it.
        val destination = com.torve.presentation.session.MobilePostAuthRouter.decide(
            com.torve.presentation.session.PostAuthInputs(
                isSignedIn = true,
                isEmailVerified = user.isVerified,
                mobileOnboardingRequired = mobileOnboardingRequired,
                mobileOnboardingComplete = mobileOnboardingComplete,
                canStartWatching = setupSummary.canStartWatching,
                hasEntitlement = subscriptionState.hasEntitlement,
                isDeviceActivated = subscriptionState.isDeviceActivated,
                deviceBlockReason = subscriptionState.deviceBlockReason,
                deviceCapReached = subscriptionState.deviceCapReached,
                currentRoute = currentRoute,
            ),
        )

        when (destination) {
            com.torve.presentation.session.PostAuthDestination.STAY -> Unit
            com.torve.presentation.session.PostAuthDestination.VERIFY_EMAIL -> {
                if (currentRoute != VERIFY_EMAIL_ROUTE) {
                    navController.navigate(VERIFY_EMAIL_ROUTE) {
                        launchSingleTop = true
                    }
                }
            }
            com.torve.presentation.session.PostAuthDestination.SETUP_CHOICE -> {
                if (currentRoute != SETUP_CHOICE_ROUTE) {
                    navController.navigate(SETUP_CHOICE_ROUTE) {
                        if (currentRoute == VERIFY_EMAIL_ROUTE) {
                            popUpTo(VERIFY_EMAIL_ROUTE) { inclusive = true }
                        }
                        launchSingleTop = true
                    }
                }
            }
            com.torve.presentation.session.PostAuthDestination.HOME -> {
                if (currentRoute == VERIFY_EMAIL_ROUTE) {
                    navController.navigate(MOBILE_ROOT_ROUTE) {
                        popUpTo(VERIFY_EMAIL_ROUTE) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            com.torve.presentation.session.PostAuthDestination.DEVICE_LIMIT_REACHED -> {
                if (currentRoute != "device_limit_reached") {
                    navController.navigate("device_limit_reached") { launchSingleTop = true }
                }
            }
            com.torve.presentation.session.PostAuthDestination.MANAGE_DEVICES -> {
                if (currentRoute != "manage_devices") {
                    navController.navigate("manage_devices") { launchSingleTop = true }
                }
            }
        }

        // Once setup is actually complete, retire the onboarding-required
        // flag so future cold starts land the user on Home.
        if (mobileOnboardingRequired && setupSummary.canStartWatching) {
            markMobileOnboardingComplete()
        }
    }
    val movieCatalogViewModel = remember(
        metadataRepo,
        watchProgressRepo,
        keywordSearchService,
        prefsRepo,
        ratingsEnricher,
        integrationSecretStore,
        contentPolicyRepository,
        contentPolicyFilter,
        invalidationCoordinator,
    ) {
        CatalogViewModel(
            metadataRepo,
            "movie",
            watchProgressRepo = watchProgressRepo,
            keywordSearchService = keywordSearchService,
            prefsRepo = prefsRepo,
            ratingsEnricher = ratingsEnricher,
            integrationSecretStore = integrationSecretStore,
            contentPolicyRepository = contentPolicyRepository,
            contentPolicyFilter = contentPolicyFilter,
            invalidationCoordinator = invalidationCoordinator,
        )
    }
    val tvCatalogViewModel = remember(
        metadataRepo,
        watchProgressRepo,
        keywordSearchService,
        prefsRepo,
        ratingsEnricher,
        integrationSecretStore,
        contentPolicyRepository,
        contentPolicyFilter,
        invalidationCoordinator,
    ) {
        CatalogViewModel(
            metadataRepo,
            "tv",
            watchProgressRepo = watchProgressRepo,
            keywordSearchService = keywordSearchService,
            prefsRepo = prefsRepo,
            ratingsEnricher = ratingsEnricher,
            integrationSecretStore = integrationSecretStore,
            contentPolicyRepository = contentPolicyRepository,
            contentPolicyFilter = contentPolicyFilter,
            invalidationCoordinator = invalidationCoordinator,
        )
    }

    LaunchedEffect(Unit) {
        watchlistViewModel.loadWatchlist()
        mediaFavoritesRepository.refresh(force = true)
    }

    LaunchedEffect(authUser?.id) {
        if (!authUser?.id.isNullOrBlank()) {
            mediaFavoritesRepository.refresh(force = true)
        }
    }

    LaunchedEffect(watchlistState.isLoading, watchlistState.items.size) {
        if (!watchlistState.isLoading && !didInitialWatchlistSync) {
            didInitialWatchlistSync = true
            homeViewModel.refresh()
        }
    }

    LaunchedEffect(appLink, isTvMode) {
        val link = appLink ?: return@LaunchedEffect
        if (isTvMode) {
            onAppLinkConsumed()
            return@LaunchedEffect
        }
        when (link.target) {
            TorveAppLinkTarget.DETAIL -> {
                val ids = org.koin.java.KoinJavaComponent.getKoin().get<com.torve.data.auth.UserIdProvider>()
                val target = com.torve.android.launcher.readPlayNextTarget(link.uri.toString(), ids.currentDataUserId())
                if (target != null) navController.navigate("detail/${target.type}/${target.id}" +
                    if (target.type == "tv") "?season=${target.season}&episode=${target.episode}" else "") { launchSingleTop = true }
            }
            TorveAppLinkTarget.HOME -> {
                mobileSelectedTab = MOBILE_HOME_ROUTE
                navController.navigate(MOBILE_ROOT_ROUTE) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }

            TorveAppLinkTarget.HELP -> {
                navController.navigate("legal/help") {
                    launchSingleTop = true
                }
            }

            TorveAppLinkTarget.SETUP -> {
                navController.navigate(SETUP_ROUTE) {
                    launchSingleTop = true
                }
            }

            TorveAppLinkTarget.ACCOUNT -> {
                navController.navigate("sync_account") {
                    launchSingleTop = true
                }
            }
        }
        onAppLinkConsumed()
    }

    LaunchedEffect(episodeAlert, authUser?.id) {
        val target = episodeAlert ?: return@LaunchedEffect
        if (authUser == null || isTvMode) return@LaunchedEffect
        val ids: com.torve.data.auth.UserIdProvider = org.koin.java.KoinJavaComponent.getKoin().get()
        if (ids.currentDataUserIdOrNull() == target.person) {
            navController.navigate("detail/tv/${target.showId}?season=${target.season}&episode=${target.episode}") { launchSingleTop = true }
        } else {
            // Opening an alert never bypasses another profile's PIN or age policy.
            navController.navigate("profiles") { launchSingleTop = true }
        }
        onEpisodeAlertConsumed()
    }

    // Content padding: the nav bar Row applies navigationBarsPadding() internally,
    // so its total rendered height = row content + system nav inset.
    // Content must clear: 56dp (row) + nav inset. The 12dp scrim overlaps content intentionally.
    val navBarInsetBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottomPadding = if (showBottomBar) 84.dp + navBarInsetBottom else navBarInsetBottom

    Box(modifier = Modifier.fillMaxSize()) {
        com.torve.android.ui.community.CatalogueNoticeHost { navController.navigate("addon_catalog") }
        // ── Restore progress ──
        val restoreProgress by accountSessionCoordinator.restoreProgress.collectAsState()

        // Non-blocking sync indicator during early bootstrap phase
        val accountSessionState by accountSessionCoordinator.state.collectAsState()
        if (accountSessionState.isBootstrapping) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .zIndex(100f)
                    .statusBarsPadding()
                    .background(com.torve.android.ui.theme.Charcoal),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = com.torve.android.ui.theme.Amber,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = localizedUiText("Syncing account…"),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = com.torve.android.ui.theme.Snow,
                    )
                }
            }
        }

        // Blocking overlay during heavy import (playlist channel loading)
        if (restoreProgress.isImporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(200f)
                    .background(com.torve.android.ui.theme.Obsidian.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = com.torve.android.ui.theme.Amber,
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = localizedUiText(restoreProgress.message.ifBlank { "Restoring your account data…" }),
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = com.torve.android.ui.theme.Snow,
                    )
                    if (restoreProgress.totalPlaylists > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = localizedUiText("${restoreProgress.restoredPlaylists}/${restoreProgress.totalPlaylists} playlists"),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = com.torve.android.ui.theme.Silver,
                        )
                    }
                }
            }
        }

        // Non-blocking banner for completion status
        if (!restoreProgress.isImporting && restoreProgress.phase != com.torve.presentation.session.RestorePhase.IDLE &&
            restoreProgress.message.isNotBlank()
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(10f)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = com.torve.android.ui.theme.Gunmetal,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = localizedUiText(restoreProgress.message),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = com.torve.android.ui.theme.Snow,
                        )
                        restoreProgress.issues.forEach { issue ->
                            Text(
                                text = "• $issue",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = com.torve.android.ui.theme.Silver,
                            )
                        }
                        if (restoreProgress.phase == com.torve.presentation.session.RestorePhase.COMPLETED_WITH_ERRORS) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.TextButton(
                                    onClick = accountSessionCoordinator::dismissRestoreProgress,
                                ) {
                                    Text(localizedUiText("Dismiss"))
                                }
                                androidx.compose.material3.TextButton(
                                    onClick = accountSessionCoordinator::retryAccountRestore,
                                ) {
                                    Text(localizedUiText("Retry restore"))
                                }
                            }
                        }
                    }
                }
                LaunchedEffect(restoreProgress.phase) {
                    if (restoreProgress.phase == com.torve.presentation.session.RestorePhase.COMPLETED) {
                        kotlinx.coroutines.delay(3000)
                        accountSessionCoordinator.dismissRestoreProgress()
                    }
                }
            }
        }

        backgroundRefreshStatus?.takeIf {
            !restoreProgress.isImporting && !accountSessionState.isBootstrapping
        }?.let { status ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(80f)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = com.torve.android.ui.theme.Gunmetal,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = localizedUiText(status.label),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = com.torve.android.ui.theme.Snow,
                        )
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = com.torve.android.ui.theme.Amber,
                            trackColor = com.torve.android.ui.theme.Snow.copy(alpha = 0.14f),
                        )
                    }
                }
            }
        }

        // Save destination-local scroll, filters and forms along with each tab stack.
        key(navigationOwner, if (isTvMode) "tv" else mobileSelectedTab) {
        tabState.SaveableStateProvider(if (isTvMode) "tv" else mobileSelectedTab) {
        NavHost(
            navController = navController,
            startDestination = dest,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (showTvNavRail) 138.dp else 0.dp,
                    bottom = contentBottomPadding,
                ),
        ) {
            // Old links remain valid, but every entry renders the current source directory.
            SourceSetupRoutes.directoryAliases.forEach { route ->
                composable(route) {
                    fun closeSources() {
                        markMobileOnboardingComplete()
                        if (!navController.popBackStack()) {
                            navController.navigate(if (isTvMode) TV_HOME_ROUTE else MOBILE_ROOT_ROUTE) {
                                popUpTo(route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    SourceSetupScreen(onBack = ::closeSources,
                        termsAccepted = setupViewModel.state.collectAsState().value.termsAccepted,
                        onAcceptTerms = { setupViewModel.setTermsAccepted(true) }, onOpen = { action ->
                        val target = SourceSetupRoutes.destination(action)
                        navController.navigate(target) { launchSingleTop = true }
                    })
                }
            }
            composable("usenet_setup") {
                val setupState by setupViewModel.state.collectAsState()
                if (setupState.termsAccepted) {
                    UsenetSetupScreen(onBack = { navController.popBackStack() })
                } else {
                    SourceSetupScreen(onBack = { navController.popBackStack() },
                        termsAccepted = setupState.termsAccepted,
                        onAcceptTerms = { setupViewModel.setTermsAccepted(true) },
                        onOpen = { action ->
                            navController.navigate(SourceSetupRoutes.destination(action)) { launchSingleTop = true }
                        })
                }
            }

            // Android TV home
            composable("tv_home") {
                TvHomeScreen(
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                )
            }

            composable(MOBILE_ROOT_ROUTE) {
                // Root tabs share one safe top inset. Child screens consume it instead
                // of reserving a second status-bar-sized gap below the tab controls.
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    com.torve.android.ui.community.PhoneHomeSelector(mobileSelectedTab,
                        onSelect = { mobileSelectedTab = it })
                    Box(Modifier.weight(1f)) {
                    PersistentMobileTabPane(visible = mobileSelectedTab == MOBILE_HOME_ROUTE) {
                        HomeScreen(
                            onWebReplaysClick = { navController.navigate("web_replays") },
                            onWebReplaySettingsClick = { navController.navigate("web_replay_settings") },
                            onWebReplayClick = { navController.navigate("web_replay/${Uri.encode(it)}") },
                            showHeader = false,
                            onYourShowsClick = { navController.navigate("your_shows") },
                            onEpisodeClick = { episode -> episode.show.tmdbId?.let { id ->
                                navController.navigate("detail/tv/$id?season=${episode.season}&episode=${episode.episode.episodeNumber}")
                            } },
                            onMediaClick = { item -> navController.navigateToDetail(item) },
                            onContinueWatchingClick = ::openContinueWatching,
                            onSeeAllClick = { sectionId ->
                                navController.navigate("seeall/${Uri.encode(sectionId)}")
                            },
                            onProviderClick = { providerId, providerName ->
                                navController.navigate("provider/$providerId/${Uri.encode(providerName)}")
                            },
                            onPersonClick = { personId ->
                                navController.navigate("person/$personId")
                            },
                            onChannelsClick = { navController.navigate("channels") },
                            onSettingsClick = { navController.navigate("settings") },
                            onExtensionsClick = { navController.navigate("addon_catalog") },
                            onProfilesClick = { navController.navigate("profiles") },
                            onRepairClick = { navController.navigate("status_repair") },
                            isActive = mobileSelectedTab == MOBILE_HOME_ROUTE,
                            accessTier = accessTier,
                            onLockedFeatureClick = requestLifetimeUnlock,
                            resetScrollToTop = pendingMobileHomeScrollReset,
                            onScrollReset = { pendingMobileHomeScrollReset = false },
                        )
                    }
                    PersistentMobileTabPane(visible = mobileSelectedTab == "movies") {
                        com.torve.android.ui.community.PhoneDiscoveryScreen(
                            viewModel = movieCatalogViewModel,
                            mediaType = "movie",
                            isActive = mobileSelectedTab == "movies",
                            onMediaClick = { item -> navController.navigateToDetail(item) },
                        )
                    }
                    PersistentMobileTabPane(visible = mobileSelectedTab == "tv_shows") {
                        com.torve.android.ui.community.PhoneDiscoveryScreen(
                            viewModel = tvCatalogViewModel,
                            mediaType = "tv",
                            isActive = mobileSelectedTab == "tv_shows",
                            onMediaClick = { item -> navController.navigateToDetail(item) },
                        )
                    }
                    PersistentMobileTabPane(visible = mobileSelectedTab == "calendar_tab") {
                        CalendarScreen(onEpisodeClick = { navController.navigate("detail/tv/$it") },
                            onCalendarEpisode = { episode -> episode.showTmdbId?.let { id ->
                                navController.navigate("detail/tv/$id?season=${episode.season}&episode=${episode.episode}")
                            } })
                    }
                    PersistentMobileTabPane(visible = mobileSelectedTab == "search") {
                        SearchScreen(
                            viewModel = searchViewModel,
                            isActive = mobileSelectedTab == "search",
                            onMediaClick = { item -> navController.navigateToDetail(item) },
                            onPersonClick = { personId -> navController.navigate("person/$personId") },
                        )
                    }
                    PersistentMobileTabPane(visible = mobileSelectedTab == "live_tv") {
                        ChannelsScreen(
                            onChannelPlay = { channel ->
                                if (isLocked(PremiumFeature.STREAM_PLAYBACK)) {
                                    requestLifetimeUnlock(PremiumFeature.STREAM_PLAYBACK)
                                } else {
                                    navController.navigate(
                                        "player?url=${Uri.encode(channel.url)}" +
                                            "&title=${Uri.encode(channel.name)}" +
                                            "&mediaId=" +
                                            "&mediaType=live" +
                                            "&posterUrl=${Uri.encode(channel.tvgLogo ?: "")}" +
                                            "&backdropUrl=",
                                    )
                                }
                            },
                        )
                    }
                    PersistentMobileTabPane(visible = mobileSelectedTab == "watchlist_tab") {
                        if (isLocked(PremiumFeature.WATCHLIST_EDIT)) {
                            PaywallScreen(
                                onBack = { navController.popBackStack() },
                                onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                                onLogin = { navController.navigate("login") },
                                lockedFeature = PremiumFeature.WATCHLIST_EDIT,
                            )
                        } else {
                            WatchlistScreen(
                                onJellyfinSetup = { navController.navigate("integrations?service=jellyfin") },
                                onLibraryCollection = { navController.navigate("library_collection/$it") },
                                onOpenEpisode = { item, season, episode -> navController.navigateToDetail(item, season, episode) },
                                onEpisode = { value ->
                                    val id = value.show.tmdbId ?: value.show.id.extractTmdbIdOrNull()
                                    if (id != null) navController.navigate("detail/tv/$id?season=${value.season}&episode=${value.episode.episodeNumber}")
                                    else navController.navigateToDetail(value.show)
                                },
                                downloadsContent = { downloadBack ->
                                    DownloadCatalogueScreen(onBack = downloadBack,
                                        onPlayOffline = { item ->
                                            navController.navigate("player?url=${Uri.encode("file://${item.filePath}")}&title=${Uri.encode(item.title)}&mediaId=${Uri.encode(item.mediaId)}&mediaType=${if (item.type == com.torve.domain.model.DownloadMediaType.MOVIE) "movie" else "series"}&posterUrl=${Uri.encode(item.posterUrl.orEmpty())}&backdropUrl=&seasonNumber=${item.seasonNumber ?: -1}&episodeNumber=${item.episodeNumber ?: -1}")
                                        }, onShowDetail = { navController.navigate("download_show/${Uri.encode(it)}") })
                                },
                                onToolsClick = { navController.navigate("library_tools") },
                                onCollectionsClick = { navController.navigate("collections") },
                                onYourShowsClick = { navController.navigate("your_shows") },
                                onFilesClick = { navController.navigate("files") },
                                onMediaClick = { item -> navController.navigateToDetail(item) },
                                onContinueWatchingClick = ::openContinueWatching,
                                onHistoryItemClick = { entry ->
                                    val id = entry.mediaId.extractTmdbIdOrNull() ?: return@WatchlistScreen
                                    val type = if (entry.mediaType.lowercase() in setOf("series", "tv", "show")) "tv" else "movie"
                                    val episode = if (type == "tv" && entry.seasonNumber != null && entry.episodeNumber != null)
                                        "?season=${entry.seasonNumber}&episode=${entry.episodeNumber}" else ""
                                    navController.navigate("detail/$type/$id$episode")
                                },
                                onDownloadsClick = {
                                    if (isLocked(PremiumFeature.DOWNLOADS)) {
                                        requestLifetimeUnlock(PremiumFeature.DOWNLOADS)
                                    } else {
                                        navController.navigate("downloads")
                                    }
                                },
                                onJellyfinItemPlay = { route, item, season, episode ->
                                    com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff.stage(route)
                                    navController.navigate(
                                        "player?url=${Uri.encode(route.url)}" +
                                            "&title=${Uri.encode(item.title)}" +
                                            "&mediaId=${Uri.encode(item.id)}" +
                                            "&mediaType=${if (item.type == MediaType.SERIES) "series" else "movie"}" +
                                            "&posterUrl=${Uri.encode(item.posterUrl ?: "")}" +
                                            "&backdropUrl=${Uri.encode(item.backdropUrl ?: "")}" +
                                            "&seasonNumber=${season ?: -1}&episodeNumber=${episode ?: -1}" +
                                            "&showTmdbId=${item.tmdbId ?: -1}" +
                                            "&showImdbId=${Uri.encode(item.imdbId ?: "")}",
                                    )
                                },
                            )
                        }
                    }
                    PersistentMobileTabPane(visible = mobileSelectedTab == "profile_tab") {
                        SettingsScreen(
                            storeUpdates = storeUpdates,
                            onConnectionCenterClick = { navController.navigate("status_repair") },
                            onCloudBackupClick = { navController.navigate("cloud_backup") },
                            accessTier = accessTier,
                            onLockedFeatureClick = requestLifetimeUnlock,
                            onDownloadsClick = {
                                if (isLocked(PremiumFeature.DOWNLOADS)) {
                                    requestLifetimeUnlock(PremiumFeature.DOWNLOADS)
                                } else {
                                    navController.navigate("downloads")
                                }
                            },
                            onSubscriptionClick = { navController.navigate("paywall") },
                            onProfilesClick = { navController.navigate("profiles") },
                            onCalendarClick = { navController.navigate("calendar") },
                            onAccountClick = { navController.navigate("sync_account") },
                            onDevicesClick = { navController.navigate("sync_devices") },
                            onSignInTvClick = { navController.navigate("sign_in_tv") },
                            onSetupPandaClick = { navController.navigate("usenet_setup") },
                            onManageDevicesClick = { navController.navigate("manage_devices") },
                            onLoginClick = { navController.navigate("login") },
                            onPrivacyPolicyClick = { navController.navigate("legal/privacy") },
                            onTermsClick = { navController.navigate("legal/terms") },
                            onHelpClick = { navController.navigate("legal/help") },
                            onReportIssueClick = { navController.navigate("report_issue") },
                            onStreamingServicesClick = {
                                if (isLocked(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)) {
                                    requestLifetimeUnlock(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)
                                } else {
                                    navController.navigate("streaming_services_settings")
                                }
                            },
                            onWebReplaySettingsClick = { navController.navigate("web_replay_settings") },
                            onAddonCatalogClick = {
                                if (isLocked(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT)) {
                                    requestLifetimeUnlock(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT)
                                } else {
                                    navController.navigate("addon_catalog")
                                }
                            },
                            onRegexPatternsClick = {
                                if (isLocked(PremiumFeature.ADVANCED_CONNECTION_CONFIGURATION)) {
                                    requestLifetimeUnlock(PremiumFeature.ADVANCED_CONNECTION_CONFIGURATION)
                                } else {
                                    navController.navigate("regex_patterns")
                                }
                            },
                            onStreamGroupsClick = {
                                if (isLocked(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)) {
                                    requestLifetimeUnlock(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)
                                } else {
                                    navController.navigate("stream_groups")
                                }
                            },
                            onHomeLayoutClick = {
                                if (isLocked(PremiumFeature.SYNC_CUSTOM_LAYOUTS)) {
                                    requestLifetimeUnlock(PremiumFeature.SYNC_CUSTOM_LAYOUTS)
                                } else {
                                    navController.navigate("home_layout")
                                }
                            },
                            onMdbListClick = {
                                if (isLocked(PremiumFeature.MDBLIST_SETUP)) {
                                    requestLifetimeUnlock(PremiumFeature.MDBLIST_SETUP)
                                } else {
                                    navController.navigate("mdblist_settings")
                                }
                            },
                            onSensitiveMaterialClick = { navController.navigate("sensitive_material_settings") },
                            onRatingSettingsClick = { navController.navigate("rating_settings") },
                            onCardStyleClick = { navController.navigate("card_style_settings") },
                            onAppearanceClick = { navController.navigate("appearance_refinements") },
                            onCollectionsClick = { navController.navigate("collections") },
                            onCustomSectionsClick = { navController.navigate("custom_sections") },
                            onIntegrationServiceClick = { service -> navController.navigate("integrations?service=${Uri.encode(service)}") },
                            onIntegrationsClick = {
                                if (isLocked(PremiumFeature.TRAKT_CONNECT)) {
                                    requestLifetimeUnlock(PremiumFeature.TRAKT_CONNECT)
                                } else {
                                    navController.navigate("integrations")
                                }
                            },
                            onDiagnosticsClick = {
                                if (isLocked(PremiumFeature.DIAGNOSTICS)) {
                                    requestLifetimeUnlock(PremiumFeature.DIAGNOSTICS)
                                } else {
                                    navController.navigate("diagnostics")
                                }
                            },
                            onSendCredentialsClick = { navController.navigate("transfer_send") },
                            onReceiveCredentialsClick = { navController.navigate("transfer_receive") },
                            onTransferDiagnosticsClick = { navController.navigate("transfer_diagnostics") },
                            onStartSetupClick = { navController.navigate("source_setup") },
                            onStatusRepairClick = { navController.navigate("status_repair") },
                        )
                    }
                }
            }

                }
            composable(MOBILE_HOME_ROUTE) {
                LaunchedEffect(Unit) {
                    mobileSelectedTab = MOBILE_HOME_ROUTE
                    navController.navigate(MOBILE_ROOT_ROUTE) {
                        popUpTo(MOBILE_HOME_ROUTE) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            composable("movies") {
                LaunchedEffect(Unit) {
                    mobileSelectedTab = "movies"
                    navController.navigate(MOBILE_ROOT_ROUTE) {
                        popUpTo("movies") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            composable("tv_shows") {
                LaunchedEffect(Unit) {
                    mobileSelectedTab = "tv_shows"
                    navController.navigate(MOBILE_ROOT_ROUTE) {
                        popUpTo("tv_shows") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            composable("live_tv") {
                LaunchedEffect(Unit) {
                    mobileSelectedTab = "live_tv"
                    navController.navigate(MOBILE_ROOT_ROUTE) {
                        popUpTo("live_tv") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            // Channels (also accessible via non-tab navigation)
            composable("channels") {
                if (!mobileSettings.optionalAppFeaturesLoaded || !mobileSettings.optionalAppFeatures.channels) {
                    LaunchedEffect(mobileSettings.optionalAppFeaturesLoaded) {
                        if (mobileSettings.optionalAppFeaturesLoaded) navController.popBackStack()
                    }
                } else ChannelsScreen(
                    onChannelPlay = { channel ->
                        if (isLocked(PremiumFeature.STREAM_PLAYBACK)) {
                            requestLifetimeUnlock(PremiumFeature.STREAM_PLAYBACK)
                        } else {
                            navController.navigate(
                                "player?url=${Uri.encode(channel.url)}" +
                                    "&title=${Uri.encode(channel.name)}" +
                                    "&mediaId=" +
                                    "&mediaType=live" +
                                    "&posterUrl=${Uri.encode(channel.tvgLogo ?: "")}" +
                                    "&backdropUrl=",
                            )
                        }
                    },
                )
            }

            // Discover tab — Genre browsing hub
            composable("discover") {
                DiscoverScreen(
                    onGenreClick = { genreId, genreName, mediaType ->
                        navController.navigate("catalog/$mediaType/$genreId/${Uri.encode(genreName)}")
                    },
                    onMoodClick = { navController.navigate("mood") },
                )
            }

            // Search tab — Global search
            composable("search") {
                LaunchedEffect(Unit) {
                    mobileSelectedTab = "search"
                    navController.navigate(MOBILE_ROOT_ROUTE) {
                        popUpTo("search") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            // Catalog screen — Genre-filtered browsing (opened from Discover)
            composable(
                route = "catalog/{mediaType}/{genreId}/{genreName}",
                arguments = listOf(
                    navArgument("mediaType") { type = NavType.StringType },
                    navArgument("genreId") { type = NavType.IntType },
                    navArgument("genreName") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val mediaType = backStackEntry.arguments?.getString("mediaType") ?: "movie"
                val genreId = backStackEntry.arguments?.getInt("genreId") ?: 0
                val genreName = backStackEntry.arguments?.getString("genreName") ?: ""
                val catalogViewModel = remember {
                    CatalogViewModel(
                        metadataRepo, mediaType,
                        keywordSearchService = keywordSearchService,
                        prefsRepo = prefsRepo,
                        ratingsEnricher = ratingsEnricher,
                        integrationSecretStore = integrationSecretStore,
                    ).also {
                        if (genreId > 0) it.selectGenre(genreId)
                    }
                }
                CatalogScreen(
                    viewModel = catalogViewModel,
                    mediaType = mediaType,
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                    onBack = { navController.popBackStack() },
                    title = genreName.ifBlank { null },
                )
            }

            // Provider catalog — filtered by streaming service (TMDB watch provider)
            composable(
                route = "provider/{providerId}/{providerName}",
                arguments = listOf(
                    navArgument("providerId") { type = NavType.IntType },
                    navArgument("providerName") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val providerId = backStackEntry.arguments?.getInt("providerId") ?: 0
                val providerName = backStackEntry.arguments?.getString("providerName") ?: ""
                var selectedMediaType by remember { mutableStateOf("movie") }
                val catalogViewModel = remember(selectedMediaType) {
                    CatalogViewModel(
                        metadataRepo, selectedMediaType,
                        keywordSearchService = keywordSearchService,
                        prefsRepo = prefsRepo,
                        ratingsEnricher = ratingsEnricher,
                        integrationSecretStore = integrationSecretStore,
                        initialProviderId = providerId,
                        initialProviderName = providerName,
                    )
                }
                CatalogScreen(
                    viewModel = catalogViewModel,
                    mediaType = selectedMediaType,
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                    onBack = { navController.popBackStack() },
                    title = providerName,
                    onMediaTypeChange = { selectedMediaType = it },
                )
            }

            // Calendar shares its source and episode destinations with the root Calendar tab.
            composable("calendar") {
                CalendarScreen(onEpisodeClick = { navController.navigate("detail/tv/$it") },
                    onCalendarEpisode = { episode -> episode.showTmdbId?.let { id ->
                        navController.navigate("detail/tv/$id?season=${episode.season}&episode=${episode.episode}")
                    } },
                    onBack = { navController.popBackStack() },
                )
            }

            // Mood Matcher — "What should I watch?"
            composable("mood") {
                MoodMatcherScreen(
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                    onBack = { navController.popBackStack() },
                )
            }

            // Stats — Watch activity stats
            composable("stats") {
                StatsScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            composable("watchlist_tab") {
                LaunchedEffect(Unit) {
                    mobileSelectedTab = "watchlist_tab"
                    navController.navigate(MOBILE_ROOT_ROUTE) {
                        popUpTo("watchlist_tab") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            composable("profile_tab") {
                LaunchedEffect(Unit) {
                    mobileSelectedTab = "profile_tab"
                    navController.navigate(MOBILE_ROOT_ROUTE) {
                        popUpTo("profile_tab") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            composable("more") {
                LaunchedEffect(Unit) {
                    mobileSelectedTab = "profile_tab"
                    navController.navigate(MOBILE_ROOT_ROUTE) {
                        popUpTo("more") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            // Settings (accessible from Profile, not in bottom nav)
            composable("cloud_backup") {
                com.torve.android.ui.community.CloudBackupScreen(
                    onBack = { navController.popBackStack() }, onSignIn = { navController.navigate("login") })
            }
            composable("settings?group={group}", arguments = listOf(navArgument("group") { type = NavType.StringType; defaultValue = "" })) { entry ->
                SettingsScreen(initialGroup = entry.arguments?.getString("group")?.takeIf { it.isNotBlank() },
                    storeUpdates = storeUpdates,
                    onConnectionCenterClick = { navController.navigate("status_repair") },
                            onCloudBackupClick = { navController.navigate("cloud_backup") },
                    accessTier = accessTier,
                    onLockedFeatureClick = requestLifetimeUnlock,
                    onDownloadsClick = {
                        if (isLocked(PremiumFeature.DOWNLOADS)) {
                            requestLifetimeUnlock(PremiumFeature.DOWNLOADS)
                        } else {
                            navController.navigate("downloads")
                        }
                    },
                    onSubscriptionClick = { navController.navigate("paywall") },
                    onProfilesClick = { navController.navigate("profiles") },
                    onCalendarClick = { navController.navigate("calendar") },
                    onAccountClick = { navController.navigate("sync_account") },
                    onDevicesClick = { navController.navigate("sync_devices") },
                    onSignInTvClick = { navController.navigate("sign_in_tv") },
                    onSetupPandaClick = { navController.navigate("usenet_setup") },
                    onManageDevicesClick = { navController.navigate("manage_devices") },
                    onLoginClick = { navController.navigate("login") },
                    onPrivacyPolicyClick = { navController.navigate("legal/privacy") },
                    onTermsClick = { navController.navigate("legal/terms") },
                    onHelpClick = { navController.navigate("legal/help") },
                    onReportIssueClick = { navController.navigate("report_issue") },
                    onStreamingServicesClick = {
                        if (isLocked(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)) {
                            requestLifetimeUnlock(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)
                        } else {
                            navController.navigate("streaming_services_settings")
                        }
                    },
                    onWebReplaySettingsClick = { navController.navigate("web_replay_settings") },
                            onAddonCatalogClick = {
                        if (isLocked(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT)) {
                            requestLifetimeUnlock(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT)
                        } else {
                            navController.navigate("addon_catalog")
                        }
                    },
                    onRegexPatternsClick = {
                        if (isLocked(PremiumFeature.ADVANCED_CONNECTION_CONFIGURATION)) {
                            requestLifetimeUnlock(PremiumFeature.ADVANCED_CONNECTION_CONFIGURATION)
                        } else {
                            navController.navigate("regex_patterns")
                        }
                    },
                    onStreamGroupsClick = {
                        if (isLocked(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)) {
                            requestLifetimeUnlock(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)
                        } else {
                            navController.navigate("stream_groups")
                        }
                    },
                    onHomeLayoutClick = {
                        if (isLocked(PremiumFeature.SYNC_CUSTOM_LAYOUTS)) {
                            requestLifetimeUnlock(PremiumFeature.SYNC_CUSTOM_LAYOUTS)
                        } else {
                            navController.navigate("home_layout")
                        }
                    },
                    onMdbListClick = {
                        if (isLocked(PremiumFeature.MDBLIST_SETUP)) {
                            requestLifetimeUnlock(PremiumFeature.MDBLIST_SETUP)
                        } else {
                            navController.navigate("mdblist_settings")
                        }
                    },
                    onSensitiveMaterialClick = { navController.navigate("sensitive_material_settings") },
                    onRatingSettingsClick = { navController.navigate("rating_settings") },
                    onCardStyleClick = { navController.navigate("card_style_settings") },
                    onAppearanceClick = { navController.navigate("appearance_refinements") },
                    onCollectionsClick = { navController.navigate("collections") },
                    onCustomSectionsClick = { navController.navigate("custom_sections") },

                    onIntegrationServiceClick = { service -> navController.navigate("integrations?service=${Uri.encode(service)}") },
                            onIntegrationsClick = {
                        if (isLocked(PremiumFeature.TRAKT_CONNECT)) {
                            requestLifetimeUnlock(PremiumFeature.TRAKT_CONNECT)
                        } else {
                            navController.navigate("integrations")
                        }
                    },
                    onDiagnosticsClick = {
                        if (isLocked(PremiumFeature.DIAGNOSTICS)) {
                            requestLifetimeUnlock(PremiumFeature.DIAGNOSTICS)
                        } else {
                            navController.navigate("diagnostics")
                        }
                    },
                    onSendCredentialsClick = { navController.navigate("transfer_send") },
                    onReceiveCredentialsClick = { navController.navigate("transfer_receive") },
                    onTransferDiagnosticsClick = { navController.navigate("transfer_diagnostics") },
                    onStartSetupClick = { navController.navigate("source_setup") },
                    onStatusRepairClick = { navController.navigate("status_repair") },
                )
            }

            // Detail screen
            composable(
                route = "detail_imdb/{type}/{imdbId}?season={season}&episode={episode}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("imdbId") { type = NavType.StringType },
                    navArgument("season") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("episode") { type = NavType.IntType; defaultValue = -1 },
                ),
            ) { backStackEntry ->
                val detailType = backStackEntry.arguments?.getString("type") ?: "movie"
                val imdbId = backStackEntry.arguments?.getString("imdbId").orEmpty()
                ResolveExternalDetailRoute(
                    type = detailType,
                    imdbId = imdbId,
                    metadataRepo = metadataRepo,
                    onResolved = { resolvedType, resolvedId ->
                        navController.navigate("detail/$resolvedType/$resolvedId?season=${backStackEntry.arguments?.getInt("season") ?: -1}&episode=${backStackEntry.arguments?.getInt("episode") ?: -1}") {
                            popUpTo(backStackEntry.destination.route ?: "detail_imdb/{type}/{imdbId}") {
                                inclusive = true
                            }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = "detail/{type}/{id}?season={season}&episode={episode}&extension={extension}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("id") { type = NavType.IntType },
                    navArgument("season") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("episode") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("extension") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { backStackEntry ->
                val detailType = backStackEntry.arguments?.getString("type") ?: "movie"
                val detailId = backStackEntry.arguments?.getInt("id") ?: 0
                val detailSession = rememberMobileDetailSession(backStackEntry,
                    mobileSettings.personalDataUserId ?: com.torve.data.auth.UserIdProvider.SIGNED_OUT_USER_ID)
                DetailScreen(
                    onDevicesClick = { navController.navigate("sync_devices") },
                    onCollectionsClick = { item -> navController.navigate("collections?movie=${item.tmdbId ?: 0}&franchise=${item.collectionId ?: 0}&name=${Uri.encode(item.collectionName.orEmpty())}") },
                    viewModel = detailSession,
                    type = detailType,
                    id = detailId,
                    extensionSeedJson = navController.extensionDetailSeed(backStackEntry),
                    initialSeason = backStackEntry.arguments?.getInt("season")?.takeIf { it > 0 },
                    initialEpisode = backStackEntry.arguments?.getInt("episode")?.takeIf { it > 0 },
                    onOpenTraktLists = { navController.navigate("trakt_lists?type=$detailType&titleId=$detailId") },
                    accessTier = accessTier,
                    onLockedFeatureClick = requestLifetimeUnlock,
                    onPlayClick = { url, fallbackUrl, season, episode, imdbId, autoSourceSelection ->
                        val artwork = detailSession.state.value.mediaItem
                        navController.navigate(
                            "player?url=${Uri.encode(url)}" +
                                "&title=${Uri.encode(artwork?.title.orEmpty())}" +
                                "&mediaId=${Uri.encode(artwork?.id ?: detailId.toString())}" +
                                "&mediaType=$detailType" +
                                "&posterUrl=${Uri.encode(artwork?.posterUrl.orEmpty())}" +
                                "&backdropUrl=${Uri.encode(artwork?.backdropUrl.orEmpty())}" +
                                "&seasonNumber=${season ?: -1}" +
                                "&episodeNumber=${episode ?: -1}" +
                                "&showTmdbId=${if (detailType == "tv") artwork?.tmdbId ?: -1 else -1}" +
                                "&showImdbId=${Uri.encode(imdbId ?: "")}" +
                                "&fallbackUrl=${Uri.encode(fallbackUrl)}" +
                                "&autoSourceSelection=$autoSourceSelection",
                        )
                    },
                    onBack = { navController.popBackStack() },
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                    onPersonClick = { personId ->
                        navController.navigate("person/$personId")
                    },
                    onSettingsClick = {
                        if (isLocked(PremiumFeature.CLOUD_PROVIDER_SETUP)) {
                            requestLifetimeUnlock(PremiumFeature.CLOUD_PROVIDER_SETUP)
                        } else {
                            navController.navigate("settings")
                        }
                    },
                    onOpenAddonCatalog = {
                        if (isLocked(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT)) {
                            requestLifetimeUnlock(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT)
                        } else {
                            navController.navigate("addon_catalog")
                        }
                    },
                )
            }

            composable("custom_sections") {
                val homeModel: com.torve.presentation.home.HomeViewModel = koinInject()
                val sections by homeModel.customSections.collectAsState()
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
                    com.torve.android.ui.community.CommunityAction(localizedUiText("Back"), { navController.popBackStack() })
                    Text(localizedUiText("Custom sections"), style = MaterialTheme.typography.headlineSmall)
                    com.torve.android.ui.community.CommunityAction(localizedUiText("Add section"), { navController.navigate("custom_section_editor") })
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(sections.size) { index ->
                            val section = sections[index]
                            com.torve.android.ui.community.CommunityAction(section.title, { navController.navigate("custom_section_editor?sectionId=${section.id}") })
                        }
                    }
                }
            }
            composable("appearance_refinements") {
                com.torve.android.ui.community.AppearanceControls(onBack = { navController.popBackStack() })
            }
            composable("collections?movie={movie}&franchise={franchise}&name={name}", arguments = listOf(
                navArgument("movie") { type = NavType.IntType; defaultValue = 0 },
                navArgument("franchise") { type = NavType.IntType; defaultValue = 0 },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
            )) { entry ->
                com.torve.android.ui.community.CollectionsScreen(
                    movieId = entry.arguments?.getInt("movie")?.takeIf { it > 0 },
                    collectionId = entry.arguments?.getInt("franchise")?.takeIf { it > 0 },
                    collectionName = entry.arguments?.getString("name"),
                    onBack = { navController.popBackStack() }, onMediaClick = { navController.navigateToDetail(it) })
            }

            // Person screen
            composable(
                route = "person/{personId}",
                arguments = listOf(
                    navArgument("personId") { type = NavType.IntType },
                ),
            ) { backStackEntry ->
                val personId = backStackEntry.arguments?.getInt("personId") ?: 0
                PersonScreen(
                    personId = personId,
                    onBack = { navController.popBackStack() },
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                )
            }

            composable("files") {
                com.torve.android.ui.files.FilesBrowserScreen(onBack = { navController.popBackStack() }, onPlay = { entry ->
                    navController.navigate("player?url=${Uri.encode(entry.url)}&title=${Uri.encode(entry.name)}&mediaId=${Uri.encode(entry.mediaId)}&mediaType=movie")
                })
            }

            webReplayRoutes(navController, isTv = false) { event, stream, start ->
                navController.navigate("player?url=${Uri.encode(stream.url)}&title=${Uri.encode(event.title)}&mediaId=${Uri.encode(event.id)}&mediaType=movie&posterUrl=${Uri.encode(event.artwork.orEmpty())}&startPositionMs=$start")
            }

            // Player screen
            composable(
                route = "player?url={url}&title={title}&mediaId={mediaId}&mediaType={mediaType}" +
                    "&posterUrl={posterUrl}&backdropUrl={backdropUrl}" +
                    "&seasonNumber={seasonNumber}&episodeNumber={episodeNumber}" +
                    "&showTmdbId={showTmdbId}&showImdbId={showImdbId}" +
                    "&fallbackUrl={fallbackUrl}&autoSourceSelection={autoSourceSelection}&startPositionMs={startPositionMs}",
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mediaId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mediaType") { type = NavType.StringType; defaultValue = "movie" },
                    navArgument("posterUrl") { type = NavType.StringType; defaultValue = "" },
                    navArgument("backdropUrl") { type = NavType.StringType; defaultValue = "" },
                    navArgument("seasonNumber") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("episodeNumber") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("showTmdbId") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("showImdbId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("fallbackUrl") { type = NavType.StringType; defaultValue = "" },
                    navArgument("autoSourceSelection") { type = NavType.BoolType; defaultValue = false },
                    navArgument("startPositionMs") { type = NavType.LongType; defaultValue = 0L },
                ),
                // Player startup is already CPU-, codec-, and allocation-heavy on
                // older Fire TV hardware. Avoid composing the details screen and
                // the full player simultaneously during Navigation's default
                // AnimatedContent transition; that overlap can starve input long
                // enough for Fire OS to raise an ANR.
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) { backStackEntry ->
                if (isLocked(PremiumFeature.STREAM_PLAYBACK)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.STREAM_PLAYBACK,
                    )
                } else {
                    PlayerScreen(
                        startPositionMs = backStackEntry.arguments?.getLong("startPositionMs") ?: 0L,
                        url = backStackEntry.arguments?.getString("url") ?: "",
                        fallbackUrl = backStackEntry.arguments?.getString("fallbackUrl") ?: "",
                        autoSourceSelection = backStackEntry.arguments?.getBoolean("autoSourceSelection") ?: false,
                        title = localizedUiText(backStackEntry.arguments?.getString("title") ?: ""),
                        mediaId = backStackEntry.arguments?.getString("mediaId") ?: "",
                        mediaType = backStackEntry.arguments?.getString("mediaType") ?: "movie",
                        posterUrl = backStackEntry.arguments?.getString("posterUrl") ?: "",
                        backdropUrl = backStackEntry.arguments?.getString("backdropUrl") ?: "",
                        seasonNumber = backStackEntry.arguments?.getInt("seasonNumber")?.takeIf { it > 0 },
                        episodeNumber = backStackEntry.arguments?.getInt("episodeNumber")?.takeIf { it > 0 },
                        showTmdbId = backStackEntry.arguments?.getInt("showTmdbId")?.takeIf { it > 0 },
                        showImdbId = backStackEntry.arguments?.getString("showImdbId")?.takeIf { it.isNotBlank() },
                        onVoiceSearchCommand = { query ->
                            val normalized = query.trim()
                            if (normalized.isNotBlank()) {
                                searchViewModel.updateQuery(normalized)
                                navController.navigate("search") {
                                    launchSingleTop = true
                                }
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // Downloads — Catalogue
            composable("downloads") {
                if (isLocked(PremiumFeature.DOWNLOADS)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.DOWNLOADS,
                    )
                } else {
                    DownloadCatalogueScreen(
                        onBack = { navController.popBackStack() },
                        onPlayOffline = { item ->
                            navController.navigate(
                                "player?url=${Uri.encode("file://${item.filePath}")}" +
                                    "&title=${Uri.encode(item.title)}" +
                                    "&mediaId=${item.mediaId}" +
                                    "&mediaType=${if (item.type == com.torve.domain.model.DownloadMediaType.MOVIE) "movie" else "series"}" +
                                    "&posterUrl=${Uri.encode(item.posterUrl ?: "")}" +
                                    "&backdropUrl=" +
                                    "&seasonNumber=${item.seasonNumber ?: -1}" +
                                    "&episodeNumber=${item.episodeNumber ?: -1}" +
                                    "&showTmdbId=-1" +
                                    "&showImdbId=",
                            )
                        },
                        onShowDetail = { mediaId ->
                            navController.navigate("download_show/$mediaId")
                        },
                    )
                }
            }

            // Downloaded Show Detail
            composable(
                route = "download_show/{mediaId}",
                arguments = listOf(androidx.navigation.navArgument("mediaId") { type = androidx.navigation.NavType.StringType }),
            ) { backStackEntry ->
                val mediaId = backStackEntry.arguments?.getString("mediaId") ?: return@composable
                if (isLocked(PremiumFeature.DOWNLOADS)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.DOWNLOADS,
                    )
                } else {
                    DownloadedShowDetailScreen(
                        mediaId = mediaId,
                        onBack = { navController.popBackStack() },
                        onPlayEpisode = { item ->
                            navController.navigate(
                                "player?url=${Uri.encode("file://${item.filePath}")}" +
                                    "&title=${Uri.encode(item.title)}" +
                                    "&mediaId=${item.mediaId}" +
                                    "&mediaType=series" +
                                    "&posterUrl=${Uri.encode(item.posterUrl ?: "")}" +
                                    "&backdropUrl=" +
                                    "&seasonNumber=${item.seasonNumber ?: -1}" +
                                    "&episodeNumber=${item.episodeNumber ?: -1}" +
                                    "&showTmdbId=-1" +
                                    "&showImdbId=",
                            )
                        },
                    )
                }
            }

            // Profiles
            composable("profiles") {
                com.torve.android.ui.product.MobileProfilesScreen(onBack = { navController.popBackStack() })
            }

            composable("library_collection/{kind}?folder={folder}&provider={provider}", arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("folder") { type = NavType.StringType; defaultValue = "" },
                navArgument("provider") { type = NavType.StringType; defaultValue = "REAL_DEBRID" })) { entry ->
                com.torve.android.ui.community.LibraryCollectionScreen(entry.arguments?.getString("kind").orEmpty(),
                    entry.arguments?.getString("folder").orEmpty(), entry.arguments?.getString("provider") ?: "REAL_DEBRID",
                    onBack = { navController.popBackStack() }, onSetup = { navController.navigate("settings?group=connections") },
                    onOpen = { row, provider ->
                        if (row.kind == "folder") navController.navigate("library_collection/cloud?folder=${Uri.encode(row.id)}&provider=${Uri.encode(provider)}")
                        else {
                            val id = row.mediaId.extractTmdbIdOrNull()
                            if (id != null) navController.navigate("detail/${row.mediaType}/$id" + if (row.season > 0 && row.episode > 0) "?season=${row.season}&episode=${row.episode}" else "")
                            else navController.navigateToDetail(MediaItem(id = row.mediaId, type = if (row.mediaType == "tv") MediaType.SERIES else MediaType.MOVIE, title = row.title, posterUrl = row.artwork))
                        }
                    }, onPlay = { url, row ->
                        navController.navigate("player?url=${Uri.encode(url)}&title=${Uri.encode(row.title)}&mediaId=${Uri.encode(row.mediaId)}&mediaType=${row.mediaType}&posterUrl=${Uri.encode(row.artwork.orEmpty())}&backdropUrl=${Uri.encode(row.backdrop.orEmpty())}")
                    })
            }
            composable("jellyfin_library") {
                com.torve.android.ui.watchlist.PhoneJellyfinLibraryScreen(onBack = { navController.popBackStack() },
                    onSetup = { navController.navigate("integrations?service=jellyfin") }, onMediaClick = { navController.navigateToDetail(it) },
                    onPlay = { route, item, season, episode ->
                        com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff.stage(route)
                        navController.navigate("player?url=${Uri.encode(route.url)}&title=${Uri.encode(item.title)}&mediaId=${Uri.encode(item.id)}&mediaType=${if (item.type == MediaType.SERIES) "series" else "movie"}&posterUrl=${Uri.encode(item.posterUrl.orEmpty())}&backdropUrl=${Uri.encode(item.backdropUrl.orEmpty())}&seasonNumber=${season ?: -1}&episodeNumber=${episode ?: -1}&showTmdbId=${item.tmdbId ?: -1}&showImdbId=${Uri.encode(item.imdbId.orEmpty())}")
                    })
            }

            composable("library_tools") {
                com.torve.android.ui.product.MobileLibraryToolsScreen(
                    onBack = { navController.popBackStack() }, onOpen = { navController.navigate(it) })
            }
            composable("your_shows?calendar={calendar}", arguments = listOf(navArgument("calendar") { type = NavType.BoolType; defaultValue = false })) { entry ->
                com.torve.android.ui.product.MobileWatchingHubScreen(
                    onBack = { navController.popBackStack() }, initialCalendar = entry.arguments?.getBoolean("calendar") == true,
                    onEpisode = { episode ->
                        val tmdb = episode.show.tmdbId ?: episode.show.id.extractTmdbIdOrNull()
                        if (tmdb != null) navController.navigate("detail/tv/$tmdb?season=${episode.season}&episode=${episode.episode.episodeNumber}")
                        else navController.navigateToDetail(episode.show)
                    })
            }
            composable("catalog_groups") {
                com.torve.android.ui.product.MobileCatalogGroupsScreen(
                    onBack = { navController.popBackStack() }, onOpen = { navController.navigate("catalog_groups/${Uri.encode(it)}") })
            }
            composable("catalog_groups/{groupId}", arguments = listOf(navArgument("groupId") { type = NavType.StringType })) { entry ->
                com.torve.android.ui.product.MobileCatalogGroupContentScreen(entry.arguments?.getString("groupId").orEmpty(),
                    onBack = { navController.popBackStack() }, onMedia = { navController.navigateToDetail(it) })
            }
            composable("trakt_lists?type={type}&titleId={titleId}", arguments = listOf(
                navArgument("type") { type = NavType.StringType; defaultValue = "movie" },
                navArgument("titleId") { type = NavType.IntType; defaultValue = -1 })) { entry ->
                com.torve.android.ui.product.MobileTraktListsScreen(onBack = { navController.popBackStack() },
                    onMedia = { navController.navigateToDetail(it) }, onConnect = { navController.navigate("connection_center") },
                    titleType = entry.arguments?.getString("type") ?: "movie", titleId = entry.arguments?.getInt("titleId") ?: -1)
            }

            composable("sync_account") {
                AccountScreen(
                    onOpenDevices = { navController.navigate("sync_devices") },
                    onManageDevices = { navController.navigate("manage_devices") },
                    onBack = { navController.popBackStack() },
                )
            }

            // Pairings — accessible to all authenticated users (individual
            // pair-creation actions may still check premium at runtime).
            composable("sync_devices") {
                DevicesScreen(
                    onOpenRemote = { navController.navigate("companion_remote") },
                    onBack = { navController.popBackStack() },
                    onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                )
            }

            composable("sign_in_tv") {
                com.torve.android.ui.sync.SignInTvScreen(
                    onBack = { navController.popBackStack() },
                    onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                )
            }

            // Paywall
            composable(
                route = "paywall?feature={feature}",
                arguments = listOf(
                    navArgument("feature") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val featureName = backStackEntry.arguments?.getString("feature")
                val lockedFeature = featureName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { PremiumFeature.valueOf(it) }.getOrNull() }
                PaywallScreen(
                    onBack = { navController.popBackStack() },
                    onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                    onLogin = { navController.navigate("login") },
                    onManageDevices = { navController.navigate("manage_devices") },
                    lockedFeature = lockedFeature,
                )
            }

            // Device Governance — accessible to all authenticated users
            composable("manage_devices") {
                ManageDevicesScreen(onBack = { navController.popBackStack() })
            }
            composable("device_limit_reached") {
                DeviceLimitReachedScreen(
                    onBack = { navController.popBackStack() },
                    onActivated = {
                        // After successful device activation, go straight to the main app
                        // (not back to login). The user is already authenticated.
                        subscriptionViewModel.loadSubscription()
                        homeViewModel.refresh()
                        navController.navigate(MOBILE_ROOT_ROUTE) {
                            popUpTo(MOBILE_ROOT_ROUTE) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(VERIFY_EMAIL_ROUTE) {
                VerifyEmailGateScreen(
                    authClient = authClient,
                    onVerified = {
                        val target = if (mobileOnboardingRequired && !mobileOnboardingComplete) {
                            SETUP_CHOICE_ROUTE
                        } else {
                            MOBILE_ROOT_ROUTE
                        }
                        navController.navigate(target) {
                            popUpTo(VERIFY_EMAIL_ROUTE) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onSignedOut = {
                        navController.navigate(MOBILE_ROOT_ROUTE) {
                            popUpTo(VERIFY_EMAIL_ROUTE) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            // Login
            composable("login") {
                LoginScreen(
                    onLoginSuccess = { isNewRegistration ->
                        subscriptionViewModel.loadSubscription()
                        val user = authClient.authUserFlow.value
                        navScope.launch {
                            if (isNewRegistration && user?.id != null) {
                                prefsRepo.setString(mobileOnboardingRequiredKey(user.id), "true")
                                mobileOnboardingRequired = true
                            }
                            val isComplete = user?.id?.let {
                                prefsRepo.getString(mobileOnboardingCompleteKey(it)) == "true"
                            } ?: false
                            val target = when {
                                user?.isVerified == false -> VERIFY_EMAIL_ROUTE
                                isNewRegistration && !isComplete -> SETUP_CHOICE_ROUTE
                                else -> MOBILE_ROOT_ROUTE
                            }
                            navController.navigate(target) {
                                popUpTo("login") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                    onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                    onSkip = { navController.popBackStack() },
                )
            }

            // See All screen — paginated grid for any section
            composable(
                route = "seeall/{sectionId}",
                arguments = listOf(navArgument("sectionId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val sectionId = Uri.decode(backStackEntry.arguments?.getString("sectionId") ?: return@composable)
                if (sectionId.startsWith("shelf:")) com.torve.android.ui.community.ExtensionBrowserScreen(
                    initialShelfId = sectionId.removePrefix("shelf:"), onBack = { navController.popBackStack() },
                    onMedia = { navController.navigateToDetail(it) }, onManage = { navController.navigate("addon_catalog") })
                else SeeAllScreen(
                    sectionId = sectionId,
                    onBack = { navController.popBackStack() },
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                )
            }

            // Streaming Services Settings
            composable("streaming_services_settings") {
                if (isLocked(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.CUSTOM_SOURCE_MANAGEMENT,
                    )
                } else {
                    StreamingServicesSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // Addon Catalog
            composable("addon_catalog") {
                if (isLocked(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT,
                    )
                } else {
                    com.torve.android.ui.community.ExtensionsScreen(onBack = { navController.popBackStack() })
                }
            }

            // Regex Patterns
            composable("regex_patterns") {
                if (isLocked(PremiumFeature.ADVANCED_CONNECTION_CONFIGURATION)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.ADVANCED_CONNECTION_CONFIGURATION,
                    )
                } else {
                    RegexPatternsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // Stream Groups
            composable("stream_groups") {
                if (isLocked(PremiumFeature.CUSTOM_SOURCE_MANAGEMENT)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.CUSTOM_SOURCE_MANAGEMENT,
                    )
                } else {
                    StreamGroupsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // MDBList Settings
            composable("mdblist_settings") {
                if (isLocked(PremiumFeature.MDBLIST_SETUP)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.MDBLIST_SETUP,
                    )
                } else {
                    MdbListSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable("sensitive_material_settings") {
                SensitiveMaterialSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            // Status & Repair detail screen
            composable("status_repair") {
                StatusRepairScreen(
                    onBack = { navController.popBackStack() },
                    onNavigate = { route -> navController.navigate(route) },
                    onDiagnose = { navController.navigate("diagnostics") },
                )
            }

            // Rating Settings
            composable("rating_settings") {
                RatingSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            // Card Style Settings
            composable("card_style_settings") {
                CardStyleSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            // Home Layout
            composable("home_layout") {
                if (isLocked(PremiumFeature.SYNC_CUSTOM_LAYOUTS)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.SYNC_CUSTOM_LAYOUTS,
                    )
                } else {
                    HomeLayoutScreen(
                        onBack = { navController.popBackStack() },
                        onAddCustomSection = { navController.navigate("custom_section_editor") },
                        onEditCustomSection = { sectionId ->
                            navController.navigate("custom_section_editor?sectionId=$sectionId")
                        },
                    )
                }
            }

            // Integrations
            composable("integrations?service={service}", arguments = listOf(navArgument("service") { type = NavType.StringType; defaultValue = "" })) { entry ->
                if (isLocked(PremiumFeature.TRAKT_CONNECT)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.TRAKT_CONNECT,
                    )
                } else {
                    IntegrationsScreen(
                        initialService = entry.arguments?.getString("service").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onAutomationAdminClick = { navController.navigate("automation_admin") },
                    )
                }
            }

            composable("companion_remote") {
                com.torve.android.ui.sync.CompanionRemoteScreen(onBack = { navController.popBackStack() }, onDevices = { navController.navigate("sync_devices") })
            }

            composable("connection_center") {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                    navController.navigate("settings?group=connections")
                }
            }

            composable("automation_admin") {
                AutomationAdministrationScreen(
                    onBack = { navController.popBackStack() },
                    onManageConnections = {
                        navController.popBackStack("integrations?service={service}", inclusive = false)
                    },
                )
            }

            composable("diagnostics") {
                if (isLocked(PremiumFeature.DIAGNOSTICS)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.DIAGNOSTICS,
                    )
                } else {
                    DiagnosticsScreen(onBack = { navController.popBackStack() })
                }
            }

            composable("report_issue") {
                BugReportScreen(onBack = { navController.popBackStack() })
            }

            // Custom Section Editor
            composable(
                route = "custom_section_editor?sectionId={sectionId}",
                arguments = listOf(
                    navArgument("sectionId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val sectionId = backStackEntry.arguments?.getString("sectionId")
                if (isLocked(PremiumFeature.SYNC_CUSTOM_LAYOUTS)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.SYNC_CUSTOM_LAYOUTS,
                    )
                } else {
                    CustomSectionEditorScreen(
                        sectionId = sectionId,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // Legal screens — point to web with language; fall back to bundled assets.
            composable("legal/privacy") {
                PrivacyPolicyScreen(onBack = { navController.popBackStack() })
            }
            composable("legal/terms") {
                LegalScreen(
                    title = stringResource(R.string.settings_terms),
                    assetFileName = "terms.html",
                    remoteUrl = "https://torve.app/terms.html",
                    onBack = { navController.popBackStack() },
                )
            }
            composable("legal/help") {
                HelpScreen(onBack = { navController.popBackStack() })
            }
            composable("transfer_send") {
                val senderVm: com.torve.presentation.transfer.SecretsTransferSenderViewModel =
                    org.koin.compose.koinInject()
                com.torve.android.ui.transfer.SecretsTransferSendScreen(
                    viewModel = senderVm,
                    onBack = { navController.popBackStack() },
                    preferPaste = false,
                )
            }
            composable("transfer_receive") {
                val receiverVm: com.torve.presentation.transfer.SecretsTransferReceiverViewModel =
                    org.koin.compose.koinInject()
                com.torve.android.ui.transfer.SecretsTransferReceiveScreen(
                    viewModel = receiverVm,
                    onBack = { navController.popBackStack() },
                    largeQr = false,
                )
            }
            composable("transfer_diagnostics") {
                com.torve.android.ui.transfer.TransferDiagnosticsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable("transfer_send_tv") {
                val senderVm: com.torve.presentation.transfer.SecretsTransferSenderViewModel =
                    org.koin.compose.koinInject()
                com.torve.android.ui.transfer.SecretsTransferSendScreen(
                    viewModel = senderVm,
                    onBack = { navController.popBackStack() },
                    preferPaste = true,
                )
            }
        }

        }
        }

        // ── Custom Bottom Navigation Bar ──
        androidx.compose.material3.SnackbarHost(requestSnackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (showBottomBar) 92.dp + navBarInsetBottom else 16.dp).zIndex(90f))
        // Floating above content with a subtle top gradient scrim.
        // Not using stock NavigationBar — custom design for the cinematic feel.
        val activePlaybackSession = ActivePlaybackState.session
        AnimatedVisibility(
            visible = currentRoute?.startsWith("player") != true && activePlaybackSession != null,
            modifier = Modifier
                .align(if (isTvMode) Alignment.BottomEnd else Alignment.BottomCenter)
                .padding(
                    start = if (isTvMode) 144.dp else 8.dp,
                    end = if (isTvMode) 24.dp else 8.dp,
                    bottom = if (showBottomBar) 92.dp + navBarInsetBottom else 18.dp,
                )
                .zIndex(4f),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            activePlaybackSession?.let { session ->
                PersistentPlaybackBar(
                    session = session,
                    isTv = isTvMode,
                    onResume = {
                        navController.navigate(session.playerRoute()) { launchSingleTop = true }
                    },
                    onTogglePlayback = ActivePlaybackState::togglePlayback,
                    onStop = ActivePlaybackState::stopAndClear,
                )
            }
        }

        AnimatedVisibility(
            visible = showTvNavRail,
            modifier = Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            TvNavRail(
                currentRoute = currentRoute,
                onTabClick = { route ->
                    navController.navigate(route) {
                        popUpTo(TV_HOME_ROUTE) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = showBottomBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .mobileGlassSurface(RoundedCornerShape(34.dp))
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    navTabDefs.forEach { tab ->
                        val selected = mobileSelectedTab == tab.route || (tab.route == MOBILE_HOME_ROUTE && mobileSelectedTab in setOf("movies", "tv_shows"))
                        NavBarItem(
                            modifier = Modifier.weight(1f),
                            tab = tab,
                            selected = selected,
                            onClick = {
                                if (mobileSelectedTab == tab.route) {
                                    navController.popBackStack(MOBILE_ROOT_ROUTE, inclusive = false)
                                } else mobileSelectedTab = tab.route
                            },
                        )
                    }
                }
            }
        }
        if (!isTvMode && storeUpdates != null && currentRoute?.startsWith("player") != true &&
            activePlaybackSession == null && !ActivePlaybackState.isInPipMode) {
            com.torve.android.update.StoreUpdatePrompt(storeUpdates,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = if (showBottomBar) 84.dp else 16.dp).zIndex(20f))
        }
    }
}

@Composable
internal fun PersistentPlaybackBar(
    session: ActivePlaybackSession,
    isTv: Boolean,
    onResume: () -> Unit,
    onTogglePlayback: () -> Unit,
    onStop: () -> Unit,
    requestInitialFocus: Boolean = false,
    focusRequestId: Long = 0L,
    onNavigateAway: () -> Unit = {},
) {
    if (isTv) {
        TvPersistentPlaybackBar(
            session = session,
            onResume = onResume,
            onTogglePlayback = onTogglePlayback,
            onStop = onStop,
            requestInitialFocus = requestInitialFocus,
            focusRequestId = focusRequestId,
            onNavigateAway = onNavigateAway,
        )
        return
    }

    var focused by remember { mutableStateOf(false) }
    val resumeFocusRequester = remember { FocusRequester() }
    val progress = if (session.durationMs > 0L) {
        (session.positionMs.toFloat() / session.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val widthModifier = if (isTv) Modifier.width(440.dp) else Modifier.fillMaxWidth()

    LaunchedEffect(requestInitialFocus, session.url) {
        if (!isTv || !requestInitialFocus) return@LaunchedEffect
        repeat(5) { attempt ->
            withFrameNanos { }
            if (attempt > 0) delay(120L)
            runCatching { resumeFocusRequester.requestFocus() }
            // The destination screen also restores its previous focus after
            // player disposal. Keep claiming the new playback card through
            // that brief restoration window before considering focus stable.
            if (attempt >= 2 && focused) return@LaunchedEffect
        }
    }

    Column(
        modifier = widthModifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xF2181C25))
            .then(if (focused) Modifier.background(Amber.copy(alpha = 0.16f)) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(resumeFocusRequester)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .clickable(onClick = onResume)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Amber else Color.White.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = localizedUiText(session.title.ifBlank { "Now playing" }),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = if (isTv) stringResource(
                        if (session.isPlaying) R.string.player_background_playing else R.string.player_background_paused,
                    ) else stringResource(if (session.isPlaying) R.string.tv_iptv_now_playing else R.string.download_paused),
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                if (isTv) {
                    Text(
                        text = stringResource(R.string.player_background_hold_back_hint),
                        color = Amber,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            Icon(
                imageVector = if (session.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = localizedUiText(if (session.isPlaying) "Pause" else "Play"),
                tint = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onTogglePlayback)
                    .padding(8.dp)
                    .size(22.dp),
            )
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = localizedUiText("Stop playback"),
                tint = Color.White.copy(alpha = 0.82f),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onStop)
                    .padding(8.dp)
                    .size(20.dp),
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Amber,
            trackColor = Color.White.copy(alpha = 0.12f),
        )
    }
}

@Composable
private fun TvPersistentPlaybackBar(
    session: ActivePlaybackSession,
    onResume: () -> Unit,
    onTogglePlayback: () -> Unit,
    onStop: () -> Unit,
    requestInitialFocus: Boolean,
    focusRequestId: Long,
    onNavigateAway: () -> Unit,
) {
    val returnFocusRequester = remember { FocusRequester() }
    val toggleFocusRequester = remember { FocusRequester() }
    val stopFocusRequester = remember { FocusRequester() }
    var focusedAction by remember { mutableStateOf<String?>(null) }
    val progress = if (session.durationMs > 0L) {
        (session.positionMs.toFloat() / session.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    LaunchedEffect(requestInitialFocus, session.url, focusRequestId) {
        if (!requestInitialFocus) return@LaunchedEffect
        repeat(5) { attempt ->
            withFrameNanos { }
            if (attempt > 0) delay(120L)
            runCatching { returnFocusRequester.requestFocus() }
            // Stop claiming focus as soon as the playback card owns it. The
            // old minimum-three-attempt loop could pull focus back after the
            // user had already pressed Up to return to the content surface.
            withFrameNanos { }
            if (focusedAction == "return") return@LaunchedEffect
        }
    }

    Column(
        modifier = Modifier
            .width(480.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                    onNavigateAway()
                    true
                } else {
                    false
                }
            }
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xF2181C25))
            .border(
                width = if (focusedAction != null) 2.dp else 1.dp,
                color = if (focusedAction != null) Amber else Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(14.dp),
            ),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        ) {
            Text(
                text = localizedUiText(session.title.ifBlank { "Now playing" }),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Text(
                text = stringResource(
                    if (session.isPlaying) R.string.player_background_playing
                    else R.string.player_background_paused,
                ),
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.player_background_hold_back_hint),
                color = Amber,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TvPlaybackBarButton(
                label = stringResource(R.string.player_background_return),
                icon = Icons.Filled.PlayArrow,
                focused = focusedAction == "return",
                onFocused = { focusedAction = if (it) "return" else focusedAction.takeUnless { value -> value == "return" } },
                onClick = onResume,
                modifier = Modifier
                    .weight(1.2f)
                    .focusRequester(returnFocusRequester)
                    .focusProperties { right = toggleFocusRequester },
            )
            TvPlaybackBarButton(
                label = stringResource(
                    if (session.isPlaying) R.string.common_pause else R.string.common_play,
                ),
                icon = if (session.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                focused = focusedAction == "toggle",
                onFocused = { focusedAction = if (it) "toggle" else focusedAction.takeUnless { value -> value == "toggle" } },
                onClick = onTogglePlayback,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(toggleFocusRequester)
                    .focusProperties {
                        left = returnFocusRequester
                        right = stopFocusRequester
                    },
            )
            TvPlaybackBarButton(
                label = stringResource(R.string.player_background_stop),
                icon = Icons.Filled.Close,
                focused = focusedAction == "stop",
                onFocused = { focusedAction = if (it) "stop" else focusedAction.takeUnless { value -> value == "stop" } },
                onClick = onStop,
                modifier = Modifier
                    .weight(0.9f)
                    .focusRequester(stopFocusRequester)
                    .focusProperties { left = toggleFocusRequester },
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Amber,
            trackColor = Color.White.copy(alpha = 0.12f),
        )
    }
}

@Composable
private fun TvPlaybackBarButton(
    label: String,
    icon: ImageVector,
    focused: Boolean,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.onFocusChanged { onFocused(it.isFocused) },
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = if (focused) Amber else Color.White.copy(alpha = 0.10f),
            contentColor = if (focused) Color.Black else Color.White,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 10.dp,
            vertical = 8.dp,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = localizedUiText(label), maxLines = 1)
    }
}

@Composable
private fun TvNavRail(
    currentRoute: String?,
    onTabClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(128.dp)
            .background(Obsidian.copy(alpha = 0.96f))
            .padding(horizontal = 10.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = localizedUiText("Torve"),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )

        tvNavTabDefs.forEach { tab ->
            TvNavRailItem(
                tab = tab,
                selected = currentRoute == tab.route,
                onClick = { onTabClick(tab.route) },
            )
        }
    }
}

@Composable
private fun TvNavRailItem(
    tab: NavTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val icon = if (selected) tab.iconSelected else tab.iconUnselected
    val label = stringResource(tab.labelResId)
    val textColor = when {
        isFocused -> Color.White
        selected -> Amber
        else -> Torve.colors.textTertiary
    }
    val backgroundColor = when {
        isFocused -> Amber.copy(alpha = 0.30f)
        selected -> Amber.copy(alpha = 0.16f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            )
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = textColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = localizedUiText(label),
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
        )
    }
}

@Composable
private fun NavBarItem(tab: NavTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    com.torve.android.ui.components.MobileBottomDestination(
        label = stringResource(com.torve.android.ui.components.mobileBottomLabelRes(tab.labelResId)), accessibilityLabel = stringResource(tab.labelResId), icon = if (selected) tab.iconSelected else tab.iconUnselected,
        selected = selected, onClick = onClick, modifier = modifier,
        showLabel = androidx.compose.ui.platform.LocalConfiguration.current.fontScale < 1.5f)
}
