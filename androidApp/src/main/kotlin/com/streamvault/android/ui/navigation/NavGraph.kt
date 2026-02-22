package com.streamvault.android.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import com.streamvault.android.ui.auth.LoginScreen
import com.streamvault.android.ui.catalog.CatalogScreen
import com.streamvault.android.ui.legal.LegalScreen
import com.streamvault.android.ui.detail.DetailScreen
import com.streamvault.android.ui.download.DownloadScreen
import com.streamvault.android.ui.iptv.IptvScreen
import com.streamvault.android.ui.player.PlayerScreen
import com.streamvault.android.ui.settings.SettingsScreen
import com.streamvault.android.ui.setup.SetupWizardScreen
import com.streamvault.android.ui.subscription.PaywallScreen
import com.streamvault.android.ui.tv.TvHomeScreen
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.presentation.catalog.CatalogViewModel
import com.streamvault.presentation.setup.SetupWizardViewModel
import org.koin.compose.koinInject

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Movies : Screen("movies", "Movies", Icons.Default.Movie)
    data object TvShows : Screen("tvshows", "TV Shows", Icons.Default.Tv)
    data object Iptv : Screen("iptv", "IPTV", Icons.Default.LiveTv)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavScreens = listOf(Screen.Movies, Screen.TvShows, Screen.Iptv, Screen.Settings)

@Composable
fun StreamVaultNavGraph(
    navController: NavHostController = rememberNavController(),
    isTvMode: Boolean = false,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = !isTvMode && currentRoute in bottomNavScreens.map { it.route }

    // Check if setup was completed
    val setupViewModel: SetupWizardViewModel = koinInject()
    val startDestination = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        startDestination.value = when {
            !setupViewModel.isSetupCompleted() -> "setup"
            isTvMode -> "tv_home"
            else -> Screen.Movies.route
        }
    }

    // Wait for setup check to complete
    val dest = startDestination.value ?: return

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavScreens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Movies.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = dest,
            modifier = Modifier.padding(paddingValues),
        ) {
            // Setup wizard
            composable("setup") {
                SetupWizardScreen(
                    viewModel = setupViewModel,
                    onComplete = {
                        val target = if (isTvMode) "tv_home" else Screen.Movies.route
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
                        val type = item.type.name.lowercase()
                        navController.navigate("detail/$type/${item.tmdbId}")
                    },
                )
            }
            // Movies tab
            composable(Screen.Movies.route) {
                val metadataRepo: MetadataRepository = koinInject()
                val viewModel = remember { CatalogViewModel(metadataRepo, "movie") }
                CatalogScreen(
                    viewModel = viewModel,
                    mediaType = "movie",
                    onMediaClick = { item ->
                        val type = item.type.name.lowercase()
                        navController.navigate("detail/$type/${item.tmdbId}")
                    },
                )
            }

            // TV Shows tab
            composable(Screen.TvShows.route) {
                val metadataRepo: MetadataRepository = koinInject()
                val viewModel = remember { CatalogViewModel(metadataRepo, "tv") }
                CatalogScreen(
                    viewModel = viewModel,
                    mediaType = "tv",
                    onMediaClick = { item ->
                        val type = item.type.name.lowercase()
                        navController.navigate("detail/$type/${item.tmdbId}")
                    },
                )
            }

            // IPTV tab
            composable(Screen.Iptv.route) {
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

            // Settings tab
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onDownloadsClick = { navController.navigate("downloads") },
                    onSubscriptionClick = { navController.navigate("paywall") },
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
                    onPlayClick = { url ->
                        navController.navigate(
                            "player?url=${Uri.encode(url)}" +
                                "&title=${Uri.encode("")}" +
                                "&mediaId=" +
                                "&mediaType=$detailType" +
                                "&posterUrl=${Uri.encode("")}" +
                                "&backdropUrl=${Uri.encode("")}",
                        )
                    },
                    onBack = { navController.popBackStack() },
                    onMediaClick = { item ->
                        val t = item.type.name.lowercase()
                        navController.navigate("detail/$t/${item.tmdbId}")
                    },
                )
            }

            // Player screen
            composable(
                route = "player?url={url}&title={title}&mediaId={mediaId}&mediaType={mediaType}&posterUrl={posterUrl}&backdropUrl={backdropUrl}",
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mediaId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mediaType") { type = NavType.StringType; defaultValue = "movie" },
                    navArgument("posterUrl") { type = NavType.StringType; defaultValue = "" },
                    navArgument("backdropUrl") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { backStackEntry ->
                PlayerScreen(
                    url = backStackEntry.arguments?.getString("url") ?: "",
                    title = backStackEntry.arguments?.getString("title") ?: "",
                    mediaId = backStackEntry.arguments?.getString("mediaId") ?: "",
                    mediaType = backStackEntry.arguments?.getString("mediaType") ?: "movie",
                    posterUrl = backStackEntry.arguments?.getString("posterUrl") ?: "",
                    backdropUrl = backStackEntry.arguments?.getString("backdropUrl") ?: "",
                    onBack = { navController.popBackStack() },
                )
            }

            // Downloads screen
            composable("downloads") {
                DownloadScreen(onBack = { navController.popBackStack() })
            }

            // Paywall screen
            composable("paywall") {
                PaywallScreen(onBack = { navController.popBackStack() })
            }

            // Login screen
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
    }
}
