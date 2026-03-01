package com.streamvault.android.ui.navigation

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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.android.R
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamvault.android.ui.auth.LoginScreen
import com.streamvault.android.ui.calendar.CalendarScreen
import com.streamvault.android.ui.catalog.CatalogScreen
import com.streamvault.android.ui.detail.DetailScreen
import com.streamvault.android.ui.discover.DiscoverScreen
import com.streamvault.android.ui.home.HomeScreen
import com.streamvault.android.ui.detail.PersonScreen
import com.streamvault.android.ui.download.DownloadCatalogueScreen
import com.streamvault.android.ui.download.DownloadScreen
import com.streamvault.android.ui.download.DownloadedShowDetailScreen
import com.streamvault.android.ui.channels.ChannelsScreen
import com.streamvault.android.ui.legal.LegalScreen
import com.streamvault.android.ui.legal.PrivacyPolicyScreen
import com.streamvault.android.ui.mood.MoodMatcherScreen
import com.streamvault.android.ui.player.PlayerScreen
import com.streamvault.android.ui.profile.ProfileScreen

import com.streamvault.android.ui.search.SearchScreen
import com.streamvault.android.ui.seeall.SeeAllScreen
import com.streamvault.android.ui.settings.AddonCatalogScreen
import com.streamvault.android.ui.settings.RegexPatternsScreen
import com.streamvault.android.ui.settings.SettingsScreen
import com.streamvault.android.ui.sync.AccountScreen
import com.streamvault.android.ui.sync.DevicesScreen
import com.streamvault.android.ui.settings.StreamGroupsScreen
import com.streamvault.android.ui.settings.CustomSectionEditorScreen
import com.streamvault.android.ui.settings.DiagnosticsScreen
import com.streamvault.android.ui.settings.IntegrationsScreen
import com.streamvault.android.ui.settings.HomeLayoutScreen
import com.streamvault.android.ui.settings.MdbListSettingsScreen
import com.streamvault.android.ui.settings.CardStyleSettingsScreen
import com.streamvault.android.ui.settings.RatingSettingsScreen
import com.streamvault.android.ui.settings.StreamingServicesSettingsScreen
import com.streamvault.android.ui.stats.StatsScreen
import com.streamvault.android.ui.watchlist.WatchlistScreen
import com.streamvault.android.ui.setup.SetupWizardScreen
import com.streamvault.android.ui.subscription.PaywallScreen
import com.streamvault.android.ui.tv.TvHomeScreen
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.MediaType
import com.streamvault.data.ai.KeywordSearchService
import com.streamvault.data.mdblist.RatingsEnricher
import com.streamvault.domain.integrations.IntegrationSecretStore
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.presentation.catalog.CatalogViewModel
import com.streamvault.presentation.setup.SetupWizardViewModel
import com.streamvault.presentation.watchlist.WatchlistViewModel
import com.streamvault.presentation.home.HomeViewModel
import org.koin.compose.koinInject

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Safe detail navigation — guards against null tmdbId
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

private fun NavHostController.navigateToDetail(item: com.streamvault.domain.model.MediaItem) {
    val id = item.tmdbId ?: item.id.toIntOrNull() ?: return
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    navigate("detail/$type/$id")
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

private val navTabDefs = listOf(
    NavTab("home", R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    NavTab("movies", R.string.nav_movies, Icons.Filled.Movie, Icons.Outlined.Movie),
    NavTab("tv_shows", R.string.nav_tv_shows, Icons.Filled.Tv, Icons.Outlined.Tv),
    NavTab("live_tv", R.string.nav_channels, Icons.Filled.LiveTv, Icons.Outlined.LiveTv),
    NavTab("watchlist_tab", R.string.nav_watchlist, Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
    NavTab("profile_tab", R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

private const val MOBILE_HOME_ROUTE = "home"
private const val TV_HOME_ROUTE = "tv_home"

private val tvNavTabDefs = listOf(
    NavTab(TV_HOME_ROUTE, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    NavTab("movies", R.string.nav_movies, Icons.Filled.Movie, Icons.Outlined.Movie),
    NavTab("tv_shows", R.string.nav_tv_shows, Icons.Filled.Tv, Icons.Outlined.Tv),
    NavTab("live_tv", R.string.nav_channels, Icons.Filled.LiveTv, Icons.Outlined.LiveTv),
    NavTab("watchlist_tab", R.string.nav_watchlist, Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
    NavTab("profile_tab", R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Main Nav Graph
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun StreamVaultNavGraph(
    navController: NavHostController = rememberNavController(),
    isTvMode: Boolean = false,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = !isTvMode && currentRoute in navTabDefs.map { it.route }
    val showTvNavRail = isTvMode && currentRoute in tvNavTabDefs.map { it.route }

    val setupViewModel: SetupWizardViewModel = koinInject()
    val watchlistViewModel: WatchlistViewModel = koinInject()
    val homeViewModel: HomeViewModel = koinInject()
    val watchlistState by watchlistViewModel.state.collectAsState()
    var didInitialWatchlistSync by remember { mutableStateOf(false) }
    val dest = if (isTvMode) TV_HOME_ROUTE else MOBILE_HOME_ROUTE

    // Hoist CatalogViewModels to NavGraph level so they survive detail navigation
    val metadataRepo: MetadataRepository = koinInject()
    val keywordSearchService: KeywordSearchService = koinInject()
    val prefsRepo: com.streamvault.domain.repository.PreferencesRepository = koinInject()
    val ratingsEnricher: RatingsEnricher = koinInject()
    val integrationSecretStore: IntegrationSecretStore = koinInject()
    val moviesCatalogViewModel = remember {
        CatalogViewModel(
            metadataRepo,
            "movie",
            keywordSearchService = keywordSearchService,
            prefsRepo = prefsRepo,
            ratingsEnricher = ratingsEnricher,
            integrationSecretStore = integrationSecretStore,
        )
    }
    val tvCatalogViewModel = remember {
        CatalogViewModel(
            metadataRepo,
            "tv",
            keywordSearchService = keywordSearchService,
            prefsRepo = prefsRepo,
            ratingsEnricher = ratingsEnricher,
            integrationSecretStore = integrationSecretStore,
        )
    }

    LaunchedEffect(Unit) {
        watchlistViewModel.loadWatchlist()
    }

    LaunchedEffect(watchlistState.isLoading, watchlistState.items.size) {
        if (!watchlistState.isLoading && !didInitialWatchlistSync) {
            didInitialWatchlistSync = true
            homeViewModel.refresh()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content
        NavHost(
            navController = navController,
            startDestination = dest,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (showTvNavRail) 138.dp else 0.dp,
                    bottom = if (showBottomBar) 56.dp else 0.dp,
                ),
        ) {
            // Setup wizard
            composable("setup") {
                SetupWizardScreen(
                    viewModel = setupViewModel,
                    onComplete = {
                        val target = if (isTvMode) TV_HOME_ROUTE else MOBILE_HOME_ROUTE
                        navController.navigate(target) {
                            popUpTo("setup") { inclusive = true }
                        }
                    },
                )
            }

            // Android TV home
            composable("tv_home") {
                TvHomeScreen(
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                )
            }

            // Home tab — Unified HomeScreen with all content (movies + TV)
            composable("home") {
                HomeScreen(
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                    onContinueWatchingClick = { progress ->
                        val id = progress.mediaId.toIntOrNull() ?: return@HomeScreen
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
                )
            }

            // Movies tab — CatalogScreen for movies
            composable("movies") {
                CatalogScreen(
                    viewModel = moviesCatalogViewModel,
                    mediaType = "movie",
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                )
            }

            // TV Shows tab — CatalogScreen for TV
            composable("tv_shows") {
                CatalogScreen(
                    viewModel = tvCatalogViewModel,
                    mediaType = "tv",
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                )
            }

            // Live TV tab
            composable("live_tv") {
                ChannelsScreen(
                    onChannelPlay = { channel ->
                        navController.navigate(
                            "player?url=${Uri.encode(channel.url)}" +
                                "&title=${Uri.encode(channel.name)}" +
                                "&mediaId=" +
                                "&mediaType=live" +
                                "&posterUrl=${Uri.encode(channel.tvgLogo ?: "")}" +
                                "&backdropUrl=",
                        )
                    },
                )
            }

            // Channels (also accessible via non-tab navigation)
            composable("channels") {
                ChannelsScreen(
                    onChannelPlay = { channel ->
                        navController.navigate(
                            "player?url=${Uri.encode(channel.url)}" +
                                "&title=${Uri.encode(channel.name)}" +
                                "&mediaId=" +
                                "&mediaType=live" +
                                "&posterUrl=${Uri.encode(channel.tvgLogo ?: "")}" +
                                "&backdropUrl=",
                        )
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

            // Watchlist tab — 3 sub-tabs: Watchlist, In Progress, History
            composable("watchlist_tab") {
                WatchlistScreen(
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                    onContinueWatchingClick = { progress ->
                        val id = progress.mediaId.toIntOrNull() ?: return@WatchlistScreen
                        val type = if (progress.mediaType == MediaType.SERIES) "tv" else "movie"
                        navController.navigate("detail/$type/$id")
                    },
                    onHistoryItemClick = { entry ->
                        val id = entry.mediaId.toIntOrNull() ?: return@WatchlistScreen
                        navController.navigate("detail/${entry.mediaType}/$id")
                    },
                )
            }

            // Profile tab — Settings screen with all navigation callbacks
            composable("profile_tab") {
                SettingsScreen(
                    onDownloadsClick = { navController.navigate("downloads") },
                    onSubscriptionClick = { navController.navigate("paywall") },
                    onProfilesClick = { navController.navigate("profiles") },
                    onCalendarClick = { navController.navigate("calendar") },
                    onAccountClick = { navController.navigate("sync_account") },
                    onDevicesClick = { navController.navigate("sync_devices") },
                    onPrivacyPolicyClick = { navController.navigate("legal/privacy") },
                    onTermsClick = { navController.navigate("legal/terms") },
                    onHelpClick = { navController.navigate("legal/help") },
                    onStreamingServicesClick = { navController.navigate("streaming_services_settings") },
                    onAddonCatalogClick = { navController.navigate("addon_catalog") },
                    onRegexPatternsClick = { navController.navigate("regex_patterns") },
                    onStreamGroupsClick = { navController.navigate("stream_groups") },
                    onHomeLayoutClick = { navController.navigate("home_layout") },
                    onMdbListClick = { navController.navigate("mdblist_settings") },
                    onRatingSettingsClick = { navController.navigate("rating_settings") },
                    onCardStyleClick = { navController.navigate("card_style_settings") },
                    onIntegrationsClick = { navController.navigate("integrations") },
                    onDiagnosticsClick = { navController.navigate("diagnostics") },
                )
            }

            // Settings (accessible from Profile, not in bottom nav)
            composable("settings") {
                SettingsScreen(
                    onDownloadsClick = { navController.navigate("downloads") },
                    onSubscriptionClick = { navController.navigate("paywall") },
                    onProfilesClick = { navController.navigate("profiles") },
                    onCalendarClick = { navController.navigate("calendar") },
                    onAccountClick = { navController.navigate("sync_account") },
                    onDevicesClick = { navController.navigate("sync_devices") },
                    onPrivacyPolicyClick = { navController.navigate("legal/privacy") },
                    onTermsClick = { navController.navigate("legal/terms") },
                    onHelpClick = { navController.navigate("legal/help") },
                    onStreamingServicesClick = { navController.navigate("streaming_services_settings") },
                    onAddonCatalogClick = { navController.navigate("addon_catalog") },
                    onRegexPatternsClick = { navController.navigate("regex_patterns") },
                    onStreamGroupsClick = { navController.navigate("stream_groups") },
                    onHomeLayoutClick = { navController.navigate("home_layout") },
                    onMdbListClick = { navController.navigate("mdblist_settings") },
                    onRatingSettingsClick = { navController.navigate("rating_settings") },
                    onCardStyleClick = { navController.navigate("card_style_settings") },
                    onIntegrationsClick = { navController.navigate("integrations") },
                    onDiagnosticsClick = { navController.navigate("diagnostics") },
                )
            }

            // Detail screen
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
                    onPlayClick = { url, fallbackUrl, season, episode, imdbId ->
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
                                "&fallbackUrl=${Uri.encode(fallbackUrl)}",
                        )
                    },
                    onBack = { navController.popBackStack() },
                    onMediaClick = { item -> navController.navigateToDetail(item) },
                    onPersonClick = { personId ->
                        navController.navigate("person/$personId")
                    },
                    onSettingsClick = {
                        navController.navigate("settings")
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
                    "&fallbackUrl={fallbackUrl}",
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
                ),
            ) { backStackEntry ->
                PlayerScreen(
                    url = backStackEntry.arguments?.getString("url") ?: "",
                    fallbackUrl = backStackEntry.arguments?.getString("fallbackUrl") ?: "",
                    title = backStackEntry.arguments?.getString("title") ?: "",
                    mediaId = backStackEntry.arguments?.getString("mediaId") ?: "",
                    mediaType = backStackEntry.arguments?.getString("mediaType") ?: "movie",
                    posterUrl = backStackEntry.arguments?.getString("posterUrl") ?: "",
                    backdropUrl = backStackEntry.arguments?.getString("backdropUrl") ?: "",
                    seasonNumber = backStackEntry.arguments?.getInt("seasonNumber")?.takeIf { it > 0 },
                    episodeNumber = backStackEntry.arguments?.getInt("episodeNumber")?.takeIf { it > 0 },
                    showTmdbId = backStackEntry.arguments?.getInt("showTmdbId")?.takeIf { it > 0 },
                    showImdbId = backStackEntry.arguments?.getString("showImdbId")?.takeIf { it.isNotBlank() },
                    onBack = { navController.popBackStack() },
                )
            }

            // Downloads — Catalogue
            composable("downloads") {
                DownloadCatalogueScreen(
                    onBack = { navController.popBackStack() },
                    onPlayOffline = { item ->
                        navController.navigate(
                            "player?url=${Uri.encode("file://${item.filePath}")}" +
                                "&title=${Uri.encode(item.title)}" +
                                "&mediaId=${item.mediaId}" +
                                "&mediaType=${if (item.type == com.streamvault.domain.model.DownloadMediaType.MOVIE) "movie" else "series"}" +
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

            // Downloaded Show Detail
            composable(
                route = "download_show/{mediaId}",
                arguments = listOf(androidx.navigation.navArgument("mediaId") { type = androidx.navigation.NavType.StringType }),
            ) { backStackEntry ->
                val mediaId = backStackEntry.arguments?.getString("mediaId") ?: return@composable
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

            // Profiles
            composable("profiles") {
                ProfileScreen(onBack = { navController.popBackStack() })
            }

            composable("sync_account") {
                AccountScreen(
                    onOpenDevices = { navController.navigate("sync_devices") },
                    onBack = { navController.popBackStack() },
                )
            }

            composable("sync_devices") {
                DevicesScreen(onBack = { navController.popBackStack() })
            }

            // Paywall
            composable("paywall") {
                PaywallScreen(onBack = { navController.popBackStack() })
            }

            // Login
            composable("login") {
                LoginScreen(
                    onLoginSuccess = { navController.popBackStack() },
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
                StreamingServicesSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            // Addon Catalog
            composable("addon_catalog") {
                AddonCatalogScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            // Regex Patterns
            composable("regex_patterns") {
                RegexPatternsScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            // Stream Groups
            composable("stream_groups") {
                StreamGroupsScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            // MDBList Settings
            composable("mdblist_settings") {
                MdbListSettingsScreen(
                    onBack = { navController.popBackStack() },
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
                HomeLayoutScreen(
                    onBack = { navController.popBackStack() },
                    onAddCustomSection = { navController.navigate("custom_section_editor") },
                    onEditCustomSection = { sectionId ->
                        navController.navigate("custom_section_editor?sectionId=$sectionId")
                    },
                )
            }

            // Integrations
            composable("integrations") {
                IntegrationsScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            composable("diagnostics") {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
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
                CustomSectionEditorScreen(
                    sectionId = sectionId,
                    onBack = { navController.popBackStack() },
                )
            }

            // Legal screens
            composable("legal/privacy") {
                PrivacyPolicyScreen(onBack = { navController.popBackStack() })
            }
            composable("legal/terms") {
                LegalScreen(
                    title = "Terms & Conditions",
                    assetFileName = "terms.html",
                    onBack = { navController.popBackStack() },
                )
            }
            composable("legal/help") {
                LegalScreen(
                    title = "Help & Documentation",
                    assetFileName = "help.html",
                    onBack = { navController.popBackStack() },
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
                        val selected = currentRoute == tab.route
                        NavBarItem(
                            tab = tab,
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
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
        else -> StreamVault.colors.textTertiary
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
    val color = if (selected) Amber else StreamVault.colors.textTertiary
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
