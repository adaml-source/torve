package com.torve.android.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.R
import com.torve.android.background.BackgroundWork
import com.torve.android.deeplink.TorveAppLink
import com.torve.android.deeplink.TorveAppLinkTarget
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.torve.android.ui.auth.LoginScreen
import com.torve.android.ui.beta.BetaProgramScreen
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
import com.torve.android.ui.profile.ProfileScreen

import com.torve.android.ui.search.SearchScreen
import com.torve.android.ui.seeall.SeeAllScreen
import com.torve.android.ui.settings.AddonCatalogScreen
import com.torve.android.ui.panda.PandaSetupScreen
import com.torve.android.ui.onboarding.VerifyEmailGateScreen
import com.torve.android.ui.settings.ManagePandaScreen
import com.torve.android.ui.settings.RegexPatternsScreen
import com.torve.android.ui.settings.SettingsScreen
import com.torve.android.ui.sync.AccountScreen
import com.torve.android.ui.sync.DevicesScreen
import com.torve.android.ui.settings.StreamGroupsScreen
import com.torve.android.ui.settings.CustomSectionEditorScreen
import com.torve.android.ui.settings.DiagnosticsScreen
import com.torve.android.ui.settings.IntegrationsScreen
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
import com.torve.android.ui.setup.SetupIntentHubScreen
import com.torve.android.ui.setup.SetupChoiceScreen
import com.torve.android.ui.setup.SetupWizardScreen
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Safe detail navigation — guards against null tmdbId
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

private fun NavHostController.navigateToDetail(item: com.torve.domain.model.MediaItem) {
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    // Addon items arrive with namespaced ids like "tmdb:1523145" or "tt0111161"
    // but leave `tmdbId`/`imdbId` unset. Parse them out of `id` so those
    // catalog shelves (Trending, Latest Releases, Popular, Language, Year,
    // etc.) are clickable.
    val tmdbId = item.tmdbId ?: item.id.extractTmdbIdOrNull()
    val imdbId = item.imdbId?.takeIf { it.isNotBlank() } ?: item.id.extractImdbIdOrNull()
    when {
        tmdbId != null -> navigate("detail/$type/$tmdbId")
        imdbId != null -> navigate("detail_imdb/$type/${Uri.encode(imdbId)}")
        else -> return
    }
}

private fun NavHostController.navigateToLifetimeUnlock(feature: PremiumFeature) {
    navigate("paywall?feature=${Uri.encode(feature.name)}")
}

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
                androidx.compose.material3.Button(onClick = onBack) {
                    Text(text = stringResource(R.string.common_back))
                }
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                if (visible) {
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, 0)
                    }
                } else {
                    // Keep the subtree alive, but remove it from layout and hit-testing.
                    layout(0, 0) {}
                }
            },
    ) {
        content()
    }
}

private val navTabDefs = listOf(
    NavTab("home", R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    NavTab("movies", R.string.nav_movies, Icons.Filled.Movie, Icons.Outlined.Movie),
    NavTab("tv_shows", R.string.nav_tv_shows, Icons.Filled.Tv, Icons.Outlined.Tv),
    NavTab("live_tv", R.string.nav_channels, Icons.Filled.LiveTv, Icons.Outlined.LiveTv),
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
    navController: NavHostController = rememberNavController(),
    isTvMode: Boolean = false,
    appLink: TorveAppLink? = null,
    onAppLinkConsumed: () -> Unit = {},
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var mobileSelectedTab by rememberSaveable { mutableStateOf(MOBILE_HOME_ROUTE) }

    val showBottomBar = !isTvMode && currentRoute == MOBILE_ROOT_ROUTE
    val showTvNavRail = isTvMode && currentRoute in tvNavTabDefs.map { it.route }

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

    // Content padding: the nav bar Row applies navigationBarsPadding() internally,
    // so its total rendered height = row content + system nav inset.
    // Content must clear: 56dp (row) + nav inset. The 12dp scrim overlaps content intentionally.
    val navBarInsetBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottomPadding = if (showBottomBar) 56.dp + navBarInsetBottom else navBarInsetBottom

    Box(modifier = Modifier.fillMaxSize()) {
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
                        text = "Syncing account…",
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
                        text = restoreProgress.message.ifBlank { "Restoring your account data…" },
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = com.torve.android.ui.theme.Snow,
                    )
                    if (restoreProgress.totalPlaylists > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${restoreProgress.restoredPlaylists}/${restoreProgress.totalPlaylists} playlists",
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = restoreProgress.message,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = com.torve.android.ui.theme.Snow,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                LaunchedEffect(restoreProgress.phase) {
                    if (restoreProgress.phase != com.torve.presentation.session.RestorePhase.RUNNING) {
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
                            text = status.label,
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

        // Main content
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
            composable(SETUP_CHOICE_ROUTE) {
                SetupChoiceScreen(
                    onGuidedSetup = {
                        navController.navigate(SETUP_ROUTE) {
                            launchSingleTop = true
                        }
                    },
                    onManualSetup = {
                        markMobileOnboardingComplete()
                        navController.navigate("settings") {
                            popUpTo(SETUP_CHOICE_ROUTE) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onExit = {
                        markMobileOnboardingComplete()
                        val target = if (isTvMode) TV_HOME_ROUTE else MOBILE_ROOT_ROUTE
                        navController.navigate(target) {
                            popUpTo(SETUP_CHOICE_ROUTE) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            // Setup hub — credential-first picker. Each card "Set up"
            // routes into the relevant detail surface (legacy wizard step
            // for Debrid/IPTV, settings for Plex/Jellyfin, Panda setup
            // for Usenet) so we don't duplicate per-feature credential
            // forms. The legacy linear wizard is reachable via "Use
            // guided wizard instead".
            composable(SETUP_ROUTE) {
                // Force the disclaimer/terms before any path that
                // ultimately exits to the app or jumps deep into the
                // wizard. SetupWizardViewModel.jumpToStep already gates
                // wizard targets, but the hub also has "exit to app"
                // shortcuts ("Continue", "Explore without sources",
                // window-X) that must not bypass terms either. Wrap each
                // exit so it routes through the wizard at TERMS first.
                fun runWithTermsGate(intendedExit: () -> Unit) {
                    if (setupViewModel.needsTermsAcceptance()) {
                        // Land on TERMS; honoring the intended exit
                        // after acceptance is handled by the wizard's
                        // own "Next" → DONE → onComplete callback below.
                        setupViewModel.jumpToStep(com.torve.presentation.setup.SetupStep.TERMS)
                        navController.navigate("setup_guided")
                    } else {
                        intendedExit()
                    }
                }
                val exitToHome: () -> Unit = {
                    markMobileOnboardingComplete()
                    val target = if (isTvMode) TV_HOME_ROUTE else MOBILE_ROOT_ROUTE
                    navController.navigate(target) {
                        popUpTo(SETUP_CHOICE_ROUTE) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                SetupIntentHubScreen(
                    coordinator = setupCoordinator,
                    onOpenDebridSetup = {
                        setupViewModel.jumpToStep(com.torve.presentation.setup.SetupStep.DEBRID)
                        navController.navigate("setup_guided")
                    },
                    onOpenIptvSetup = {
                        setupViewModel.jumpToStep(com.torve.presentation.setup.SetupStep.CHANNELS)
                        navController.navigate("setup_guided")
                    },
                    onOpenPlexJellyfinSetup = {
                        runWithTermsGate {
                            setupCoordinator.beginIntent(com.torve.presentation.setup.SetupIntent.PLEX_JELLYFIN)
                            navController.navigate("settings")
                        }
                    },
                    onOpenUsenetSetup = {
                        runWithTermsGate {
                            setupCoordinator.beginIntent(com.torve.presentation.setup.SetupIntent.USENET)
                            navController.navigate("panda_setup")
                        }
                    },
                    onUseGuidedWizard = { navController.navigate("setup_guided") },
                    onContinueToApp = { runWithTermsGate(exitToHome) },
                    onExit = { runWithTermsGate(exitToHome) },
                    onSkipToApp = { runWithTermsGate(exitToHome) },
                )
            }

            // Legacy linear setup wizard. Still reachable from the hub
            // for users who want a guided walkthrough. Onboarding-flag
            // persistence (`setup_completed`) lives here.
            composable("setup_guided") {
                SetupWizardScreen(
                    viewModel = setupViewModel,
                    onComplete = {
                        markMobileOnboardingComplete()
                        val target = if (isTvMode) TV_HOME_ROUTE else MOBILE_ROOT_ROUTE
                        navController.navigate(target) {
                            popUpTo(SETUP_CHOICE_ROUTE) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onExit = {
                        markMobileOnboardingComplete()
                        val target = if (isTvMode) TV_HOME_ROUTE else MOBILE_ROOT_ROUTE
                        navController.navigate(target) {
                            popUpTo(SETUP_CHOICE_ROUTE) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onPandaSetupClick = { navController.navigate("panda_setup") },
                )
            }

            // Android TV home
            composable("tv_home") {
                TvHomeScreen(
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                )
            }

            composable(MOBILE_ROOT_ROUTE) {
                Box(modifier = Modifier.fillMaxSize()) {
                    PersistentMobileTabPane(visible = mobileSelectedTab == MOBILE_HOME_ROUTE) {
                        HomeScreen(
                            onMediaClick = { item -> navController.navigateToDetail(item) },
                            onContinueWatchingClick = { progress ->
                                val id = progress.mediaId.extractTmdbIdOrNull() ?: return@HomeScreen
                                val type = if (progress.mediaType == MediaType.SERIES) "tv" else "movie"
                                navController.navigate("detail/$type/$id")
                            },
                            onSeeAllClick = { sectionId ->
                                navController.navigate("seeall/${Uri.encode(sectionId)}")
                            },
                            onProviderClick = { providerId, providerName ->
                                navController.navigate("provider/$providerId/${Uri.encode(providerName)}")
                            },
                            onPersonClick = { personId ->
                                navController.navigate("person/$personId")
                            },
                            accessTier = accessTier,
                            onLockedFeatureClick = requestLifetimeUnlock,
                        )
                    }
                    PersistentMobileTabPane(visible = mobileSelectedTab == "movies") {
                        CatalogScreen(
                            viewModel = movieCatalogViewModel,
                            mediaType = "movie",
                            onMediaClick = { item -> navController.navigateToDetail(item) },
                        )
                    }
                    PersistentMobileTabPane(visible = mobileSelectedTab == "tv_shows") {
                        CatalogScreen(
                            viewModel = tvCatalogViewModel,
                            mediaType = "tv",
                            onMediaClick = { item -> navController.navigateToDetail(item) },
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
                                onMediaClick = { item -> navController.navigateToDetail(item) },
                                onContinueWatchingClick = { progress ->
                                    val id = progress.mediaId.extractTmdbIdOrNull() ?: return@WatchlistScreen
                                    val type = if (progress.mediaType == MediaType.SERIES) "tv" else "movie"
                                    navController.navigate("detail/$type/$id")
                                },
                                onHistoryItemClick = { entry ->
                                    val id = entry.mediaId.extractTmdbIdOrNull() ?: return@WatchlistScreen
                                    navController.navigate("detail/${entry.mediaType}/$id")
                                },
                                onDownloadsClick = {
                                    if (isLocked(PremiumFeature.DOWNLOADS)) {
                                        requestLifetimeUnlock(PremiumFeature.DOWNLOADS)
                                    } else {
                                        navController.navigate("downloads")
                                    }
                                },
                                onJellyfinItemPlay = { streamUrl, title ->
                                    navController.navigate(
                                        "player?url=${Uri.encode(streamUrl)}" +
                                            "&title=${Uri.encode(title)}" +
                                            "&mediaId=" +
                                            "&mediaType=movie" +
                                            "&posterUrl=" +
                                            "&backdropUrl=",
                                    )
                                },
                            )
                        }
                    }
                    PersistentMobileTabPane(visible = mobileSelectedTab == "profile_tab") {
                        SettingsScreen(
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
                            onSetupPandaClick = { navController.navigate("panda_setup") },
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
                            onStartSetupClick = { navController.navigate(SETUP_CHOICE_ROUTE) },
                            onStatusRepairClick = { navController.navigate("status_repair") },
                            onBetaProgramClick = { navController.navigate("beta_program") },
                        )
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
                SearchScreen(
                    viewModel = searchViewModel,
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                )
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

            // Calendar (accessible via navigation, no longer in bottom nav)
            composable("calendar") {
                CalendarScreen(
                    onEpisodeClick = { tmdbId ->
                        navController.navigate("detail/tv/$tmdbId")
                    },
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

            // Settings (accessible from Profile, not in bottom nav)
            composable("settings") {
                SettingsScreen(
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
                    onSetupPandaClick = { navController.navigate("panda_setup") },
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
                    onStartSetupClick = { navController.navigate(SETUP_CHOICE_ROUTE) },
                    onStatusRepairClick = { navController.navigate("status_repair") },
                    onBetaProgramClick = { navController.navigate("beta_program") },
                )
            }

            composable("beta_program") {
                BetaProgramScreen(
                    onBack = { navController.popBackStack() },
                    onSignIn = { navController.navigate("login") },
                )
            }

            // Detail screen
            composable(
                route = "detail_imdb/{type}/{imdbId}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("imdbId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val detailType = backStackEntry.arguments?.getString("type") ?: "movie"
                val imdbId = backStackEntry.arguments?.getString("imdbId").orEmpty()
                ResolveExternalDetailRoute(
                    type = detailType,
                    imdbId = imdbId,
                    metadataRepo = metadataRepo,
                    onResolved = { resolvedType, resolvedId ->
                        navController.navigate("detail/$resolvedType/$resolvedId") {
                            popUpTo(backStackEntry.destination.route ?: "detail_imdb/{type}/{imdbId}") {
                                inclusive = true
                            }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = "detail/{type}/{id}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("id") { type = NavType.IntType },
                ),
            ) { backStackEntry ->
                val detailType = backStackEntry.arguments?.getString("type") ?: "movie"
                val detailId = backStackEntry.arguments?.getInt("id") ?: 0
                DetailScreen(
                    type = detailType,
                    id = detailId,
                    accessTier = accessTier,
                    onLockedFeatureClick = requestLifetimeUnlock,
                    onPlayClick = { url, fallbackUrl, season, episode, imdbId, autoSourceSelection ->
                        navController.navigate(
                            "player?url=${Uri.encode(url)}" +
                                "&title=${Uri.encode("")}" +
                                "&mediaId=$detailId" +
                                "&mediaType=$detailType" +
                                "&posterUrl=${Uri.encode("")}" +
                                "&backdropUrl=${Uri.encode("")}" +
                                "&seasonNumber=${season ?: -1}" +
                                "&episodeNumber=${episode ?: -1}" +
                                "&showTmdbId=${if (detailType == "tv") detailId else -1}" +
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

            // Player screen
            composable(
                route = "player?url={url}&title={title}&mediaId={mediaId}&mediaType={mediaType}" +
                    "&posterUrl={posterUrl}&backdropUrl={backdropUrl}" +
                    "&seasonNumber={seasonNumber}&episodeNumber={episodeNumber}" +
                    "&showTmdbId={showTmdbId}&showImdbId={showImdbId}" +
                    "&fallbackUrl={fallbackUrl}&autoSourceSelection={autoSourceSelection}",
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
                ),
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
                        url = backStackEntry.arguments?.getString("url") ?: "",
                        fallbackUrl = backStackEntry.arguments?.getString("fallbackUrl") ?: "",
                        autoSourceSelection = backStackEntry.arguments?.getBoolean("autoSourceSelection") ?: false,
                        title = backStackEntry.arguments?.getString("title") ?: "",
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
                ProfileScreen(onBack = { navController.popBackStack() })
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
                SeeAllScreen(
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
                    AddonCatalogScreen(
                        onBack = { navController.popBackStack() },
                        onManagePandaClick = { navController.navigate("manage_panda") },
                    )
                }
            }

            // Manage Panda
            composable("manage_panda") {
                if (isLocked(PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT,
                    )
                } else {
                    ManagePandaScreen(
                        onBack = { navController.popBackStack() },
                        onSetupClick = { navController.navigate("panda_setup") },
                    )
                }
            }

            // Panda Native Setup
            composable("panda_setup") {
                PandaSetupScreen(
                    onBack = { navController.popBackStack() },
                    onComplete = { navController.popBackStack() },
                )
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
            composable("integrations") {
                if (isLocked(PremiumFeature.TRAKT_CONNECT)) {
                    PaywallScreen(
                        onBack = { navController.popBackStack() },
                        onDeviceLimitReached = { navController.navigate("device_limit_reached") },
                        onLogin = { navController.navigate("login") },
                        lockedFeature = PremiumFeature.TRAKT_CONNECT,
                    )
                } else {
                    IntegrationsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
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

        // ── Custom Bottom Navigation Bar ──
        // Floating above content with a subtle top gradient scrim.
        // Not using stock NavigationBar — custom design for the cinematic feel.
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
            Column {
                // Top gradient scrim — fades content into nav bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Obsidian),
                            ),
                        ),
                )

                // Nav bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Obsidian)
                        .navigationBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    navTabDefs.forEach { tab ->
                        val selected = mobileSelectedTab == tab.route
                        NavBarItem(
                            tab = tab,
                            selected = selected,
                            onClick = {
                                mobileSelectedTab = tab.route
                                if (currentRoute != MOBILE_ROOT_ROUTE) {
                                    navController.navigate(MOBILE_ROOT_ROUTE) {
                                        launchSingleTop = true
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
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
            text = "Torve",
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
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
        )
    }
}

@Composable
private fun NavBarItem(
    tab: NavTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val icon = if (selected) tab.iconSelected else tab.iconUnselected
    val color = if (selected) Amber else Torve.colors.textTertiary
    val label = stringResource(tab.labelResId)

    Column(
        modifier = Modifier
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            )
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontSize = 10.sp,
        )
    }
}
