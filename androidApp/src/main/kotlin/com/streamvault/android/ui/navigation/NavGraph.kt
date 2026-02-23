package com.streamvault.android.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
import com.streamvault.android.ui.home.HomeScreen
import com.streamvault.android.ui.detail.PersonScreen
import com.streamvault.android.ui.download.DownloadScreen
import com.streamvault.android.ui.iptv.IptvScreen
import com.streamvault.android.ui.legal.LegalScreen
import com.streamvault.android.ui.player.PlayerScreen
import com.streamvault.android.ui.profile.ProfileScreen
import com.streamvault.android.ui.settings.SettingsScreen
import com.streamvault.android.ui.setup.SetupWizardScreen
import com.streamvault.android.ui.subscription.PaywallScreen
import com.streamvault.android.ui.tv.TvHomeScreen
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.presentation.catalog.CatalogViewModel
import com.streamvault.presentation.setup.SetupWizardViewModel
import org.koin.compose.koinInject

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Navigation Tabs
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

data class NavTab(
    val route: String,
    val label: String,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
)

val navTabs = listOf(
    NavTab("movies", "Movies", Icons.Filled.Movie, Icons.Outlined.Movie),
    NavTab("tvshows", "TV Shows", Icons.Filled.Tv, Icons.Outlined.Tv),
    NavTab("iptv", "Live TV", Icons.Filled.LiveTv, Icons.Outlined.LiveTv),
    NavTab("calendar", "Calendar", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    NavTab("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
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

    val showBottomBar = !isTvMode && currentRoute in navTabs.map { it.route }

    val setupViewModel: SetupWizardViewModel = koinInject()
    val startDestination = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        startDestination.value = when {
            !setupViewModel.isSetupCompleted() -> "setup"
            isTvMode -> "tv_home"
            else -> "movies"
        }
    }

    val dest = startDestination.value ?: return

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content
        NavHost(
            navController = navController,
            startDestination = dest,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    bottom = if (showBottomBar) 72.dp else 0.dp,
                ),
        ) {
            // Setup wizard
            composable("setup") {
                SetupWizardScreen(
                    viewModel = setupViewModel,
                    onComplete = {
                        val target = if (isTvMode) "tv_home" else "movies"
                        navController.navigate(target) {
                            popUpTo("setup") { inclusive = true }
                        }
                    },
                )
            }

            // Android TV home
            composable("tv_home") {
                TvHomeScreen(
                    onMediaClick = { item ->
                        val type = if (item.type == MediaType.SERIES) "tv" else "movie"
                        navController.navigate("detail/$type/${item.tmdbId}")
                    },
                )
            }

            // Movies tab — Full HomeScreen with hero, recommendations, shelves
            composable("movies") {
                HomeScreen(
                    onMediaClick = { item ->
                        val type = if (item.type == MediaType.SERIES) "tv" else "movie"
                        navController.navigate("detail/$type/${item.tmdbId}")
                    },
                    onContinueWatchingClick = { progress ->
                        val type = if (progress.mediaType == MediaType.SERIES) "tv" else "movie"
                        navController.navigate("detail/$type/${progress.mediaId}")
                    },
                )
            }

            // TV Shows tab
            composable("tvshows") {
                val metadataRepo: MetadataRepository = koinInject()
                val viewModel = remember { CatalogViewModel(metadataRepo, "tv") }
                CatalogScreen(
                    viewModel = viewModel,
                    mediaType = "tv",
                    onMediaClick = { item ->
                        val type = if (item.type == MediaType.SERIES) "tv" else "movie"
                        navController.navigate("detail/$type/${item.tmdbId}")
                    },
                )
            }

            // IPTV tab
            composable("iptv") {
                IptvScreen(
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

            // Calendar tab
            composable("calendar") {
                CalendarScreen(
                    onEpisodeClick = { tmdbId ->
                        navController.navigate("detail/tv/$tmdbId")
                    },
                )
            }

            // Settings tab
            composable("settings") {
                SettingsScreen(
                    onDownloadsClick = { navController.navigate("downloads") },
                    onSubscriptionClick = { navController.navigate("paywall") },
                    onProfilesClick = { navController.navigate("profiles") },
                    onPrivacyPolicyClick = { navController.navigate("legal/privacy") },
                    onTermsClick = { navController.navigate("legal/terms") },
                    onHelpClick = { navController.navigate("legal/help") },
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
                    onPlayClick = { url, season, episode, imdbId ->
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
                                "&showImdbId=${Uri.encode(imdbId ?: "")}",
                        )
                    },
                    onBack = { navController.popBackStack() },
                    onMediaClick = { item ->
                        val t = if (item.type == MediaType.SERIES) "tv" else "movie"
                        navController.navigate("detail/$t/${item.tmdbId}")
                    },
                    onPersonClick = { personId ->
                        navController.navigate("person/$personId")
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
                    onMediaClick = { item ->
                        val t = if (item.type == MediaType.SERIES) "tv" else "movie"
                        navController.navigate("detail/$t/${item.tmdbId}")
                    },
                )
            }

            // Player screen
            composable(
                route = "player?url={url}&title={title}&mediaId={mediaId}&mediaType={mediaType}" +
                    "&posterUrl={posterUrl}&backdropUrl={backdropUrl}" +
                    "&seasonNumber={seasonNumber}&episodeNumber={episodeNumber}" +
                    "&showTmdbId={showTmdbId}&showImdbId={showImdbId}",
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
                ),
            ) { backStackEntry ->
                PlayerScreen(
                    url = backStackEntry.arguments?.getString("url") ?: "",
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

            // Downloads
            composable("downloads") {
                DownloadScreen(
                    onBack = { navController.popBackStack() },
                    onPlayOffline = { download ->
                        navController.navigate(
                            "player?url=${Uri.encode("file://${download.filePath}")}" +
                                "&title=${Uri.encode(download.title)}" +
                                "&mediaId=${download.mediaId}" +
                                "&mediaType=${download.mediaType.name.lowercase()}" +
                                "&posterUrl=${Uri.encode(download.posterUrl ?: "")}" +
                                "&backdropUrl=" +
                                "&seasonNumber=${download.seasonNumber ?: -1}" +
                                "&episodeNumber=${download.episodeNumber ?: -1}" +
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

            // Legal screens
            composable("legal/privacy") {
                LegalScreen(
                    title = "Privacy Policy",
                    assetFileName = "privacy_policy.html",
                    onBack = { navController.popBackStack() },
                )
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
                        .height(24.dp)
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
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    navTabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavBarItem(
                            tab = tab,
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo("movies") { saveState = true }
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
private fun NavBarItem(
    tab: NavTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val icon = if (selected) tab.iconSelected else tab.iconUnselected
    val color = if (selected) Amber else StreamVault.colors.textTertiary

    Column(
        modifier = Modifier
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tab.label,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        // Active indicator — small amber dot under selected tab
        if (selected) {
            Spacer(Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Amber),
            )
        }
    }
}
