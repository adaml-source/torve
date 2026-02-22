package com.streamvault.android.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamvault.android.ui.detail.DetailScreen
import com.streamvault.android.ui.home.HomeScreen
import com.streamvault.android.ui.player.PlayerScreen
import com.streamvault.android.ui.search.SearchScreen
import com.streamvault.android.ui.settings.SettingsScreen

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object Search : Screen("search", "Search", Icons.Default.Search)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavScreens = listOf(Screen.Home, Screen.Search, Screen.Settings)

@Composable
fun StreamVaultNavGraph(navController: NavHostController = rememberNavController()) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomNavScreens.map { it.route }

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
                                    popUpTo(Screen.Home.route) { saveState = true }
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
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(paddingValues),
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onMediaClick = { item ->
                        val type = item.type.name.lowercase()
                        navController.navigate("detail/$type/${item.tmdbId}")
                    },
                    onContinueWatchingClick = { progress ->
                        val type = progress.mediaType.name.lowercase()
                        navController.navigate("detail/$type/${progress.mediaId}")
                    },
                )
            }

            composable(Screen.Search.route) {
                SearchScreen(onMediaClick = { item ->
                    val type = item.type.name.lowercase()
                    navController.navigate("detail/$type/${item.tmdbId}")
                })
            }

            composable(Screen.Settings.route) {
                SettingsScreen()
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
        }
    }
}
