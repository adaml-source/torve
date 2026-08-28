package com.torve.android.tv.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.torve.android.tv.TvScreenCache
import com.torve.android.tv.TvNotificationQueue
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.screens.TvDetailsScreen
import com.torve.android.tv.screens.TvDeviceLimitReachedScreen
import com.torve.android.tv.screens.TvBugReportScreen
import com.torve.android.tv.screens.TvHomeLayoutScreen
import com.torve.android.tv.screens.TvLivePlayerScreen
import com.torve.android.tv.screens.TvPandaSetupScreen
import com.torve.android.tv.screens.TvRatingsSettingsScreen
import com.torve.android.tv.screens.TvSeeAllScreen
import com.torve.android.tv.screens.TvVodSeriesDetailsArgs
import com.torve.android.tv.screens.TvVodSeriesDetailsScreen
import com.torve.android.tv.screens.stats.TvWatchStatsScreen
import com.torve.android.tv.premium.TvEntitledFeature
import com.torve.android.ui.player.PlayerScreen
import com.torve.android.ui.player.ActivePlaybackState
import com.torve.android.ui.player.ActivePlaybackSession
import com.torve.android.ui.navigation.PersistentPlaybackBar
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.repository.WatchProgressRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val TV_STREAM_RESOLVING_NOTIFICATION_TAG = "tv_stream_resolving"
private const val TV_RESUME_MIN_POSITION_MS = 20_000L
private const val TV_RESUME_MAX_PROGRESS = 0.85

private fun ActivePlaybackSession.tvPlayerRoute(): String = if (ActivePlaybackState.isLiveSession) {
    TvRoutes.livePlayer(
        channelUrl = url,
        channelName = title,
        groupName = ActivePlaybackState.retainedLiveGroupName,
    )
} else {
    TvRoutes.player(
        url = url,
        fallbackUrl = fallbackUrl,
        title = title,
        mediaId = mediaId,
        mediaType = mediaType,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        showTmdbId = showTmdbId,
        showImdbId = showImdbId,
        // The retained engine is already at its playback position. Supplying
        // it again would incorrectly show the resume/start-over prompt.
        startPositionMs = 0L,
        autoSourceSelection = autoSourceSelection,
    )
}

private fun NavHostController.navigateToTvDetails(item: MediaItem, autoPlay: Boolean = false) {
    val id = item.tmdbId ?: item.id.toIntOrNull() ?: item.id.extractTmdbIdOrNull() ?: return
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    navigate(TvRoutes.details(type = type, id = id, autoPlay = autoPlay))
}

private fun WatchProgress.validTvResumePositionMs(
    season: Int?,
    episode: Int?,
): Long {
    if (positionMs < TV_RESUME_MIN_POSITION_MS || durationMs <= 0L) return 0L
    if (positionMs.toDouble() / durationMs.toDouble() >= TV_RESUME_MAX_PROGRESS) return 0L
    if (mediaType == MediaType.SERIES) {
        if (season != null && seasonNumber != season) return 0L
        if (episode != null && episodeNumber != episode) return 0L
    }
    return positionMs
}

/**
 * NavHost that only handles sub-routes (overlay screens).
 * Top-level tab screens (Home, Movies, Shows, etc.) are rendered as
 * keep-alive composables in [com.torve.android.tv.TvRoot] and are
 * NOT part of this NavHost.
 */
@Composable
internal fun TvNavHost(
    navController: NavHostController,
    railFocusRequester: FocusRequester,
    onVoiceSearchQuery: (String) -> Unit,
    onSettingsClick: () -> Unit = {},
    // Focus state cleanup handled in TvRoot via isSubRouteActive LaunchedEffect
    onHomeLayoutBack: () -> Unit = { navController.popBackStack() },
    // Focus state cleanup handled in TvRoot via isSubRouteActive LaunchedEffect
    onRatingsBack: () -> Unit = { navController.popBackStack() },
    onWatchStatsBack: () -> Unit = { navController.popBackStack() },
    onPandaSetupBack: () -> Unit = { navController.popBackStack() },
    onSeeAllBack: () -> Unit = { navController.popBackStack() },
    onDetailsBack: () -> Unit = { navController.popBackStack() },
    onPairingSignInSuccess: () -> Unit = {},
    onBeforeDetailsNavigateFromSeeAll: () -> Unit = {},
    onRequestLifetimeUnlock: (TvEntitledFeature) -> Unit = {},
    onWatchedStatusChanged: (MediaItem, Boolean) -> Unit = { _, _ -> },
    isStreamPlaybackLocked: Boolean = false,
    onPlaybackFocusRestoreRequest: (returnDestinationRoute: String?) -> Unit = {},
    playbackFocusRestoreRequestId: Int = 0,
    onFirstContentRequester: (route: String, FocusRequester) -> Unit = { _, _ -> },
    onContentFocused: (route: String, FocusRequester) -> Unit = { _, _ -> },
    onDetailsContentFocusStateChanged: (Boolean) -> Unit = {},
    registerSeeAllFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
    homeLayoutEntryFocusRequester: FocusRequester,
    onHomeLayoutEntryReadyChanged: (Boolean) -> Unit = {},
    onHomeLayoutEntryFocused: () -> Unit = {},
    ratingsEntryFocusRequester: FocusRequester,
    onRatingsEntryReadyChanged: (Boolean) -> Unit = {},
    onRatingsEntryFocused: () -> Unit = {},
) {
    val watchProgressRepo: WatchProgressRepository = koinInject()
    val navScope = rememberCoroutineScope()
    val currentBackStackEntry = navController.currentBackStackEntryAsState().value
    val currentRoute = currentBackStackEntry?.destination?.route
    val returnToPlayerRequestId = ActivePlaybackState.returnToPlayerRequestId

    SideEffect {
        ActivePlaybackState.isFullScreenPlayerVisible =
            currentRoute == TvRoutes.PLAYER || currentRoute == TvRoutes.LIVE_PLAYER
    }
    DisposableEffect(Unit) {
        onDispose { ActivePlaybackState.isFullScreenPlayerVisible = false }
    }
    LaunchedEffect(returnToPlayerRequestId) {
        val activeSession = ActivePlaybackState.session ?: return@LaunchedEffect
        if (currentRoute != TvRoutes.PLAYER && currentRoute != TvRoutes.LIVE_PLAYER && returnToPlayerRequestId > 0L) {
            navController.navigate(activeSession.tvPlayerRoute()) { launchSingleTop = true }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = TvRoutes.SUB_NAV_START,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
        /* Empty placeholder — visible when no sub-route is active */
        composable(TvRoutes.SUB_NAV_START) { /* nothing */ }

        composable(TvRoutes.HOME_LAYOUT) {
            TvHomeLayoutScreen(
                railFocusRequester = railFocusRequester,
                onBack = onHomeLayoutBack,
                onFirstContentRequester = { onFirstContentRequester(TvRoutes.HOME_LAYOUT, it) },
                onContentFocused = { onContentFocused(TvRoutes.HOME_LAYOUT, it) },
                entryFocusRequester = homeLayoutEntryFocusRequester,
                onEntryFocusReadyChanged = onHomeLayoutEntryReadyChanged,
                onEntryFocusFocused = onHomeLayoutEntryFocused,
            )
        }

        composable(TvRoutes.RATINGS_SETTINGS) {
            TvRatingsSettingsScreen(
                railFocusRequester = railFocusRequester,
                onBack = onRatingsBack,
                onFirstContentRequester = { onFirstContentRequester(TvRoutes.RATINGS_SETTINGS, it) },
                onContentFocused = { onContentFocused(TvRoutes.RATINGS_SETTINGS, it) },
                entryFocusRequester = ratingsEntryFocusRequester,
                onEntryFocusReadyChanged = onRatingsEntryReadyChanged,
                onEntryFocusFocused = onRatingsEntryFocused,
            )
        }

        composable(TvRoutes.WATCH_STATS) {
            val entryFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
            TvWatchStatsScreen(
                railFocusRequester = railFocusRequester,
                onBack = onWatchStatsBack,
                onOpenDetails = { session ->
                    val id = session.tmdbId
                        ?: session.mediaId.extractTmdbIdOrNull()
                        ?: session.mediaId.toIntOrNull()
                    if (id != null) {
                        val type = if (session.mediaType == MediaType.SERIES) "tv" else "movie"
                        navController.navigate(TvRoutes.details(type = type, id = id))
                    }
                },
                onRequestLifetimeUnlock = onRequestLifetimeUnlock,
                onFirstContentRequester = { onFirstContentRequester(TvRoutes.WATCH_STATS, it) },
                onContentFocused = { onContentFocused(TvRoutes.WATCH_STATS, it) },
                entryFocusRequester = entryFocusRequester,
            )
        }

        composable(TvRoutes.DEVICE_LIMIT_REACHED) {
            TvDeviceLimitReachedScreen(
                // Focus state cleanup handled in TvRoot via isSubRouteActive LaunchedEffect
                onBack = { navController.popBackStack() },
                // Focus state cleanup handled in TvRoot via isSubRouteActive LaunchedEffect
                onActivated = { navController.popBackStack() },
            )
        }

        composable(TvRoutes.PANDA_SETUP) {
            TvPandaSetupScreen(
                onBack = onPandaSetupBack,
                onComplete = onPandaSetupBack,
            )
        }

        composable(TvRoutes.AUTOMATION_ADMIN) {
            val initialFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
            com.torve.android.ui.settings.AutomationControlMode(tvEnabled = true) {
                com.torve.android.ui.settings.AutomationAdministrationScreen(
                    onBack = { navController.popBackStack() },
                    onManageConnections = { navController.navigate(TvRoutes.AUTOMATION_CONNECTIONS) },
                    initialFocusRequester = initialFocusRequester,
                )
            }
        }

        composable(TvRoutes.AUTOMATION_CONNECTIONS) {
            val initialFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
            com.torve.android.ui.settings.AutomationControlMode(tvEnabled = true) {
                com.torve.android.ui.settings.AutomationConnectionsScreen(
                    onBack = { navController.popBackStack() },
                    initialFocusRequester = initialFocusRequester,
                )
            }
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

        composable("transfer_receive_tv") {
            val receiverVm: com.torve.presentation.transfer.SecretsTransferReceiverViewModel =
                org.koin.compose.koinInject()
            com.torve.android.ui.transfer.SecretsTransferReceiveScreen(
                viewModel = receiverVm,
                onBack = { navController.popBackStack() },
                largeQr = true,
            )
        }

        composable("transfer_diagnostics_tv") {
            com.torve.android.ui.transfer.TransferDiagnosticsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable("bug_report_tv") {
            TvBugReportScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable("pairing_signin_tv") {
            com.torve.android.tv.screens.TvPairingSignInScreen(
                onBack = { navController.popBackStack() },
                onSignedIn = {
                    // Pop the sign-in flow; the rest of the TV nav will
                    // pick up the signed-in user via authUserFlow.
                    navController.popBackStack()
                    onPairingSignInSuccess()
                },
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
                onMediaClick = { item ->
                    onBeforeDetailsNavigateFromSeeAll()
                    navController.navigateToTvDetails(item)
                },
                onBack = onSeeAllBack,
                onFirstContentRequester = { onFirstContentRequester(TvRoutes.SEE_ALL, it) },
                onContentFocused = { onContentFocused(TvRoutes.SEE_ALL, it) },
                onSeeAll = { childRailKey, childTitle, childMediaType ->
                    navController.navigate(
                        TvRoutes.seeAll(
                            railKey = childRailKey,
                            mediaType = childMediaType,
                            title = childTitle,
                        ),
                    )
                },
                registerFocusHandle = registerSeeAllFocusHandle,
            )
        }

        composable(
            route = TvRoutes.VOD_SERIES_DETAILS,
            arguments = listOf(
                navArgument("cacheKey") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val cacheKey = backStackEntry.arguments?.getString("cacheKey").orEmpty()
            val args = TvScreenCache.get<TvVodSeriesDetailsArgs>(cacheKey)
            if (args == null) {
                LaunchedEffect(cacheKey) {
                    navController.popBackStack()
                }
            } else {
                TvVodSeriesDetailsScreen(
                    args = args,
                    railFocusRequester = railFocusRequester,
                    onBack = { navController.popBackStack() },
                    onEpisodePlay = { episode, episodeItem ->
                        if (isStreamPlaybackLocked) {
                            onRequestLifetimeUnlock(TvEntitledFeature.STREAM_PLAYBACK)
                        } else {
                            navScope.launch {
                                val season = episode.kodiProps["vod_season_number"]?.toIntOrNull()
                                val episodeNumber = episode.kodiProps["vod_episode_number"]?.toIntOrNull()
                                val progressMediaId = args.item.tmdbId?.takeIf { it > 0 }?.toString()
                                    ?: episodeItem.id
                                val savedMs = runCatching {
                                    watchProgressRepo.getProgress(progressMediaId)
                                        ?.validTvResumePositionMs(season, episodeNumber)
                                        ?: 0L
                                }.getOrDefault(0L)
                                val episodeName = episode.tvgName?.takeIf { it.isNotBlank() }
                                    ?: episode.name
                                navController.navigate(
                                    TvRoutes.player(
                                        url = episode.url,
                                        fallbackUrl = "",
                                        title = args.item.title,
                                        mediaId = progressMediaId,
                                        mediaType = "tv",
                                        posterUrl = episodeItem.posterUrl.orEmpty(),
                                        backdropUrl = episodeItem.backdropUrl.orEmpty(),
                                        seasonNumber = season,
                                        episodeNumber = episodeNumber,
                                        episodeName = episodeName,
                                        showTmdbId = args.item.tmdbId,
                                        showImdbId = args.item.imdbId,
                                        startPositionMs = savedMs,
                                    ),
                                ) { launchSingleTop = true }
                            }
                        }
                    },
                    onFirstContentRequester = { onFirstContentRequester(TvRoutes.VOD_SERIES_DETAILS, it) },
                    onContentFocused = { onContentFocused(TvRoutes.VOD_SERIES_DETAILS, it) },
                )
            }
        }

        composable(
            route = TvRoutes.DETAILS,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("id") { type = NavType.IntType },
                navArgument("autoPlay") { type = NavType.BoolType; defaultValue = false },
                navArgument("handoffPositionMs") { type = NavType.LongType; defaultValue = 0L },
                navArgument("focusEpisodes") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { backStackEntry ->
            val detailType = backStackEntry.arguments?.getString("type") ?: "movie"
            val detailId = backStackEntry.arguments?.getInt("id") ?: 0
            val autoPlay = backStackEntry.arguments?.getBoolean("autoPlay") ?: false
            val handoffPositionMs = backStackEntry.arguments?.getLong("handoffPositionMs") ?: 0L
            val focusEpisodes = backStackEntry.arguments?.getBoolean("focusEpisodes") ?: false
            TvDetailsScreen(
                type = detailType,
                id = detailId,
                autoPlay = autoPlay,
                focusEpisodes = focusEpisodes,
                railFocusRequester = railFocusRequester,
                onBack = onDetailsBack,
                onFirstContentRequester = { onFirstContentRequester(TvRoutes.DETAILS, it) },
                onContentFocused = { onContentFocused(TvRoutes.DETAILS, it) },
                onContentFocusStateChanged = onDetailsContentFocusStateChanged,
                playbackFocusRestoreRequestId = playbackFocusRestoreRequestId,
                onMediaClick = { item -> navController.navigateToTvDetails(item) },
                onSettingsClick = {
                    // Focus state cleanup handled in TvRoot via isSubRouteActive LaunchedEffect
                    navController.popBackStack()
                    onSettingsClick()
                },
                onLibraryAutomationClick = {
                    navController.navigate(TvRoutes.AUTOMATION_CONNECTIONS)
                },
                onRequestLifetimeUnlock = onRequestLifetimeUnlock,
                onWatchedStatusChanged = onWatchedStatusChanged,
                onCastClick = { castId, castName ->
                    navController.navigate(
                        TvRoutes.seeAll(
                            railKey = "person_credits_$castId",
                            mediaType = "movie",
                            title = castName,
                        ),
                    )
                },
                onPlayResolved = { url, fallbackUrl, mediaItem, season, episode, autoSourceSelection, episodeName ->
                    val mediaType = if (mediaItem.type == MediaType.SERIES) "tv" else "movie"
                    navScope.launch {
                        val savedMs = if (handoffPositionMs > 0L) {
                            handoffPositionMs
                        } else {
                            runCatching {
                                watchProgressRepo.getProgress(mediaItem.id)
                                    ?.validTvResumePositionMs(season, episode)
                                    ?: 0L
                            }.getOrDefault(0L)
                        }
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
                                episodeName = episodeName,
                                showTmdbId = if (mediaType == "tv") mediaItem.tmdbId else null,
                                showImdbId = mediaItem.imdbId,
                                startPositionMs = savedMs,
                                autoSourceSelection = autoSourceSelection,
                            ),
                        )
                    }
                },
            )
        }

        composable(
            route = TvRoutes.LIVE_PLAYER,
            arguments = listOf(
                navArgument("channelUrl") { type = NavType.StringType; defaultValue = "" },
                navArgument("channelName") { type = NavType.StringType; defaultValue = "" },
                navArgument("groupName") { type = NavType.StringType; defaultValue = "" },
                navArgument("replayUrl") { type = NavType.StringType; defaultValue = "" },
                navArgument("replayStartMs") { type = NavType.LongType; defaultValue = -1L },
                navArgument("replayEndMs") { type = NavType.LongType; defaultValue = -1L },
                navArgument("replayTitle") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            LaunchedEffect(backStackEntry.id) {
                TvNotificationQueue.clear(TV_STREAM_RESOLVING_NOTIFICATION_TAG)
            }
            if (isStreamPlaybackLocked) {
                LaunchedEffect(Unit) {
                    onRequestLifetimeUnlock(TvEntitledFeature.STREAM_PLAYBACK)
                    // Focus state cleanup handled in TvRoot via isSubRouteActive LaunchedEffect
                    navController.popBackStack()
                }
            } else {
                TvLivePlayerScreen(
                    channelUrl = backStackEntry.arguments?.getString("channelUrl") ?: "",
                    channelName = backStackEntry.arguments?.getString("channelName") ?: "",
                    groupName = backStackEntry.arguments?.getString("groupName") ?: "",
                    initialReplayUrl = backStackEntry.arguments?.getString("replayUrl").orEmpty(),
                    initialReplayStartMs = backStackEntry.arguments?.getLong("replayStartMs") ?: -1L,
                    initialReplayEndMs = backStackEntry.arguments?.getLong("replayEndMs") ?: -1L,
                    initialReplayTitle = backStackEntry.arguments?.getString("replayTitle").orEmpty(),
                    onBack = {
                        val returnRoute = navController.previousBackStackEntry?.destination?.route
                        navController.popBackStack()
                        // Live playback has no persistent playback card to own
                        // focus after exit. Restore the originating IPTV surface
                        // explicitly as well as through the route transition.
                        onPlaybackFocusRestoreRequest(returnRoute)
                    },
                )
            }
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
                navArgument("autoSourceSelection") { type = NavType.BoolType; defaultValue = false },
                navArgument("episodeName") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            if (isStreamPlaybackLocked) {
                LaunchedEffect(Unit) {
                    onRequestLifetimeUnlock(TvEntitledFeature.STREAM_PLAYBACK)
                    // Focus state cleanup handled in TvRoot via isSubRouteActive LaunchedEffect
                    navController.popBackStack()
                }
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
                    episodeName = backStackEntry.arguments?.getString("episodeName").orEmpty(),
                    showTmdbId = backStackEntry.arguments?.getInt("showTmdbId")?.takeIf { it > 0 },
                    showImdbId = backStackEntry.arguments?.getString("showImdbId")?.takeIf { it.isNotBlank() },
                    startPositionMs = backStackEntry.arguments?.getLong("startPositionMs") ?: 0L,
                    onVoiceSearchCommand = { query ->
                        val normalized = query.trim()
                        if (normalized.isNotBlank()) {
                            onVoiceSearchQuery(normalized)
                        }
                    },
                    onBack = {
                        val returnRoute = navController.previousBackStackEntry?.destination?.route
                        navController.popBackStack()
                        // Exo playback deliberately hands focus to the retained
                        // playback card. MPV/non-retained exits have no such
                        // owner, so restore the originating details control.
                        if (ActivePlaybackState.session == null) {
                            onPlaybackFocusRestoreRequest(returnRoute)
                        }
                    },
                    onStop = {
                        val returnRoute = navController.previousBackStackEntry?.destination?.route
                        navController.popBackStack()
                        onPlaybackFocusRestoreRequest(returnRoute)
                    },
                )
            }
        }
        }

        val activePlaybackSession = ActivePlaybackState.session
        if (
            currentBackStackEntry?.destination?.route != TvRoutes.PLAYER &&
            currentBackStackEntry?.destination?.route != TvRoutes.LIVE_PLAYER &&
            activePlaybackSession != null
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
            ) {
                PersistentPlaybackBar(
                    session = activePlaybackSession,
                    isTv = true,
                    onResume = {
                        navController.navigate(activePlaybackSession.tvPlayerRoute()) {
                            launchSingleTop = true
                        }
                    },
                    onTogglePlayback = ActivePlaybackState::togglePlayback,
                    onStop = {
                        ActivePlaybackState.stopAndClear()
                        onPlaybackFocusRestoreRequest(currentRoute)
                    },
                    requestInitialFocus = true,
                    focusRequestId = ActivePlaybackState.focusPlaybackBarRequestId,
                    onNavigateAway = { onPlaybackFocusRestoreRequest(currentRoute) },
                )
            }
        }
    }
}
