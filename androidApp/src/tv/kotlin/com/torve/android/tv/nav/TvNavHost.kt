package com.torve.android.tv.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.torve.android.tv.screens.TvDetailsScreen
import com.torve.android.tv.screens.TvDeviceLimitReachedScreen
import com.torve.android.tv.screens.TvHomeLayoutScreen
import com.torve.android.tv.screens.TvLivePlayerScreen
import com.torve.android.tv.screens.TvRatingsSettingsScreen
import com.torve.android.tv.screens.TvSeeAllScreen
import com.torve.android.tv.premium.TvEntitledFeature
import com.torve.android.ui.player.PlayerScreen
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType

private fun NavHostController.navigateToTvDetails(item: MediaItem, autoPlay: Boolean = false) {
    val id = item.tmdbId ?: item.id.toIntOrNull() ?: return
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    navigate(TvRoutes.details(type = type, id = id, autoPlay = autoPlay))
}

/**
 * NavHost that only handles sub-routes (overlay screens).
 * Top-level tab screens (Home, Movies, Shows, etc.) are rendered as
 * keep-alive composables in [com.torve.android.tv.TvRoot] and are
 * NOT part of this NavHost.
 */
@Composable
fun TvNavHost(
    navController: NavHostController,
    railFocusRequester: FocusRequester,
    onVoiceSearchQuery: (String) -> Unit,
    onSettingsClick: () -> Unit = {},
    onRequestLifetimeUnlock: (TvEntitledFeature) -> Unit = {},
    onFirstContentRequester: (FocusRequester) -> Unit = {},
    onContentFocused: (FocusRequester) -> Unit = {},
) {
    NavHost(
        navController = navController,
        startDestination = TvRoutes.SUB_NAV_START,
    ) {
        /* Empty placeholder — visible when no sub-route is active */
        composable(TvRoutes.SUB_NAV_START) { /* nothing */ }

        composable(TvRoutes.HOME_LAYOUT) {
            TvHomeLayoutScreen(
                railFocusRequester = railFocusRequester,
                onBack = { navController.popBackStack() },
                onFirstContentRequester = onFirstContentRequester,
                onContentFocused = onContentFocused,
            )
        }

        composable(TvRoutes.RATINGS_SETTINGS) {
            TvRatingsSettingsScreen(
                railFocusRequester = railFocusRequester,
                onBack = { navController.popBackStack() },
                onFirstContentRequester = onFirstContentRequester,
                onContentFocused = onContentFocused,
            )
        }

        composable(TvRoutes.DEVICE_LIMIT_REACHED) {
            TvDeviceLimitReachedScreen(
                onBack = { navController.popBackStack() },
                onActivated = { navController.popBackStack() },
            )
        }

        composable(
            route = TvRoutes.SEE_ALL,
            arguments = listOf(
                navArgument("railKey") { type = NavType.StringType },
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val railKey = backStackEntry.arguments?.getString("railKey") ?: ""
            val mediaType = backStackEntry.arguments?.getString("mediaType") ?: "movie"
            val title = backStackEntry.arguments?.getString("title") ?: ""
            TvSeeAllScreen(
                railKey = railKey,
                mediaType = mediaType,
                title = title,
                railFocusRequester = railFocusRequester,
                onMediaClick = { item -> navController.navigateToTvDetails(item) },
                onBack = { navController.popBackStack() },
                onFirstContentRequester = onFirstContentRequester,
                onContentFocused = onContentFocused,
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
                onFirstContentRequester = onFirstContentRequester,
                onContentFocused = onContentFocused,
                onMediaClick = { item -> navController.navigateToTvDetails(item) },
                onSettingsClick = {
                    navController.popBackStack()
                    onSettingsClick()
                },
                onRequestLifetimeUnlock = onRequestLifetimeUnlock,
                onCastClick = { castId, castName ->
                    navController.navigate(
                        TvRoutes.seeAll(
                            railKey = "person_credits_$castId",
                            mediaType = "movie",
                            title = castName,
                        ),
                    )
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
            route = TvRoutes.LIVE_PLAYER,
            arguments = listOf(
                navArgument("channelUrl") { type = NavType.StringType; defaultValue = "" },
                navArgument("channelName") { type = NavType.StringType; defaultValue = "" },
                navArgument("groupName") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            TvLivePlayerScreen(
                channelUrl = backStackEntry.arguments?.getString("channelUrl") ?: "",
                channelName = backStackEntry.arguments?.getString("channelName") ?: "",
                groupName = backStackEntry.arguments?.getString("groupName") ?: "",
                onBack = { navController.popBackStack() },
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
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
