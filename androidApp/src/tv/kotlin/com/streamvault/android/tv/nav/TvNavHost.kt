package com.streamvault.android.tv.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.streamvault.android.tv.screens.TvDetailsScreen
import com.streamvault.android.tv.screens.TvHomeScreen
import com.streamvault.android.tv.screens.TvLibraryScreen
import com.streamvault.android.tv.screens.TvMoviesScreen
import com.streamvault.android.tv.screens.TvSearchScreen
import com.streamvault.android.tv.screens.TvSettingsScreen
import com.streamvault.android.tv.screens.TvShowsScreen
import com.streamvault.android.ui.player.PlayerScreen
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType

private fun NavHostController.navigateToTvDetails(item: MediaItem, autoPlay: Boolean = false) {
    val id = item.tmdbId ?: item.id.toIntOrNull() ?: return
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    navigate(TvRoutes.details(type = type, id = id, autoPlay = autoPlay))
}

@Composable
fun TvNavHost(
    navController: NavHostController,
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    onFirstContentRequester: (String, FocusRequester) -> Unit,
    onContentFocused: (String, FocusRequester) -> Unit,
    onVoiceSearchQuery: (String) -> Unit,
    searchSeedQuery: String? = null,
) {
    NavHost(
        navController = navController,
        startDestination = TvRoutes.HOME,
    ) {
        composable(TvRoutes.HOME) {
            TvHomeScreen(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = headerFocusRequester,
                onMediaClick = { item -> navController.navigateToTvDetails(item) },
                onFirstContentRequester = { requester ->
                    onFirstContentRequester(TvRoutes.HOME, requester)
                },
                onContentFocused = { requester ->
                    onContentFocused(TvRoutes.HOME, requester)
                },
            )
        }

        composable(TvRoutes.MOVIES) {
            TvMoviesScreen(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = headerFocusRequester,
                onMediaClick = { item -> navController.navigateToTvDetails(item) },
                onFirstContentRequester = { requester ->
                    onFirstContentRequester(TvRoutes.MOVIES, requester)
                },
                onContentFocused = { requester ->
                    onContentFocused(TvRoutes.MOVIES, requester)
                },
            )
        }

        composable(TvRoutes.SHOWS) {
            TvShowsScreen(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = headerFocusRequester,
                onMediaClick = { item -> navController.navigateToTvDetails(item) },
                onFirstContentRequester = { requester ->
                    onFirstContentRequester(TvRoutes.SHOWS, requester)
                },
                onContentFocused = { requester ->
                    onContentFocused(TvRoutes.SHOWS, requester)
                },
            )
        }

        composable(TvRoutes.SEARCH) {
            TvSearchScreen(
                railFocusRequester = railFocusRequester,
                initialQuery = searchSeedQuery.orEmpty(),
                onMediaClick = { item -> navController.navigateToTvDetails(item) },
                onFirstContentRequester = { requester ->
                    onFirstContentRequester(TvRoutes.SEARCH, requester)
                },
                onContentFocused = { requester ->
                    onContentFocused(TvRoutes.SEARCH, requester)
                },
            )
        }

        composable(TvRoutes.LIBRARY) {
            TvLibraryScreen(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = headerFocusRequester,
                onMediaClick = { item -> navController.navigateToTvDetails(item) },
                onFirstContentRequester = { requester ->
                    onFirstContentRequester(TvRoutes.LIBRARY, requester)
                },
                onContentFocused = { requester ->
                    onContentFocused(TvRoutes.LIBRARY, requester)
                },
            )
        }

        composable(TvRoutes.SETTINGS) {
            TvSettingsScreen(
                railFocusRequester = railFocusRequester,
                onFirstContentRequester = { requester ->
                    onFirstContentRequester(TvRoutes.SETTINGS, requester)
                },
                onContentFocused = { requester ->
                    onContentFocused(TvRoutes.SETTINGS, requester)
                },
            )
        }

        composable(
            route = TvRoutes.DETAILS,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("id") { type = NavType.IntType },
                navArgument("autoPlay") { type = NavType.BoolType; defaultValue = false },
                navArgument("handoffPositionMs") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) { backStackEntry ->
            val detailType = backStackEntry.arguments?.getString("type") ?: "movie"
            val detailId = backStackEntry.arguments?.getInt("id") ?: 0
            val autoPlay = backStackEntry.arguments?.getBoolean("autoPlay") ?: false
            val handoffPositionMs = backStackEntry.arguments?.getLong("handoffPositionMs") ?: 0L
            TvDetailsScreen(
                type = detailType,
                id = detailId,
                autoPlay = autoPlay,
                railFocusRequester = railFocusRequester,
                onBack = { navController.popBackStack() },
                onFirstContentRequester = { requester ->
                    onFirstContentRequester("tv_details", requester)
                },
                onContentFocused = { requester ->
                    onContentFocused("tv_details", requester)
                },
                onPlayResolved = { url, fallbackUrl, mediaItem, season, episode ->
                    val mediaType = if (mediaItem.type == MediaType.SERIES) "tv" else "movie"
                    navController.navigate(
                        TvRoutes.player(
                            url = url,
                            fallbackUrl = fallbackUrl,
                            title = mediaItem.title,
                            mediaId = mediaItem.id,
                            mediaType = mediaType,
                            posterUrl = mediaItem.posterUrl.orEmpty(),
                            backdropUrl = mediaItem.backdropUrl.orEmpty(),
                            seasonNumber = season,
                            episodeNumber = episode,
                            showTmdbId = if (mediaType == "tv") mediaItem.tmdbId else null,
                            showImdbId = mediaItem.imdbId,
                            startPositionMs = handoffPositionMs.coerceAtLeast(0L),
                        ),
                    )
                },
            )
        }

        composable(
            route = TvRoutes.PLAYER,
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
                navArgument("startPositionMs") { type = NavType.LongType; defaultValue = 0L },
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
                startPositionMs = backStackEntry.arguments?.getLong("startPositionMs") ?: 0L,
                onVoiceSearchCommand = { query ->
                    val normalized = query.trim()
                    if (normalized.isNotBlank()) {
                        onVoiceSearchQuery(normalized)
                        navController.navigate(TvRoutes.SEARCH) {
                            popUpTo(TvRoutes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
