package com.torve.android.tv.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.torve.android.ui.replays.webReplayRoutes
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.torve.android.tv.TvScreenCache
import com.torve.android.tv.TvNotificationQueue
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.focus.nextPlaybackDestinationRequestId
import com.torve.android.tv.focus.playbackFocusRestoreEffectId
import com.torve.android.tv.screens.TvDetailsScreen
import com.torve.android.tv.screens.TvDeviceLimitReachedScreen
import com.torve.android.tv.screens.TvBugReportScreen
import com.torve.android.tv.screens.TvHomeLayoutScreen
import com.torve.android.tv.screens.TvLivePlayerScreen
import com.torve.android.tv.screens.TvPandaSetupScreen
import com.torve.android.tv.screens.TvRatingsSettingsScreen
import com.torve.android.tv.screens.TvSeeAllScreen
import com.torve.android.tv.screens.TvPersonScreen
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
import com.torve.android.ui.community.openExtensionDetailIfNeeded
import com.torve.android.ui.community.extensionDetailSeed
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.repository.WatchProgressRepository
import com.torve.presentation.detail.DetailViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val TV_STREAM_RESOLVING_NOTIFICATION_TAG = "tv_stream_resolving"
private const val TV_RESUME_MIN_POSITION_MS = 20_000L
private const val TV_RESUME_MAX_PROGRESS = 0.85
private const val TV_PLAYBACK_RETURN_REQUEST_ID = "tv_playback_return_request_id"
private const val TV_PLAYBACK_RETURN_SEASON = "tv_playback_return_season"
private const val TV_PLAYBACK_RETURN_EPISODE = "tv_playback_return_episode"
private const val TV_PLAYBACK_RETURN_ROOT_REQUEST_ID = "tv_playback_return_root_request_id"

private fun androidx.lifecycle.SavedStateHandle.queuePlaybackFocusRestore(
    seasonNumber: Int?,
    episodeNumber: Int?,
) {
    val current = get<Int>(TV_PLAYBACK_RETURN_REQUEST_ID) ?: 0
    set(TV_PLAYBACK_RETURN_SEASON, seasonNumber ?: -1)
    set(TV_PLAYBACK_RETURN_EPISODE, episodeNumber ?: -1)
    set(TV_PLAYBACK_RETURN_REQUEST_ID, nextPlaybackDestinationRequestId(current))
}

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
    if (com.torve.android.ui.community.CommunityNotices.handle(item)) return
    if (openExtensionDetailIfNeeded(item, tv = true, autoPlay = autoPlay)) return
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    onAutomationConnectionsBack: () -> Unit = { navController.popBackStack() },
    onSeeAllBack: () -> Unit = { navController.popBackStack() },
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<com.torve.android.tv.components.TvMediaContextMenuAction>)? = null,
    onContextMenuAction: ((MediaItem, com.torve.android.tv.components.TvMediaContextMenuAction, Float?) -> Unit)? = null,
    onDetailsBack: () -> Unit = { navController.popBackStack() },
    onPairingSignInSuccess: () -> Unit = {},
    onBeforeDetailsNavigateFromSeeAll: () -> Unit = {},
    onRequestLifetimeUnlock: (TvEntitledFeature) -> Unit = {},
    onWatchedStatusChanged: (MediaItem, Boolean) -> Unit = { _, _ -> },
    onProfileSwitched: () -> Unit = {},
    isStreamPlaybackLocked: Boolean = false,
    personalScopeKey: String = "default",
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
    val detailViewModelStore = androidx.compose.runtime.remember(personalScopeKey) {
        TvRetainedDestinationStore<String, DetailViewModel>(
            maxEntries = 6,
            onRelease = DetailViewModel::clear,
        )
    }
    DisposableEffect(detailViewModelStore) {
        onDispose { detailViewModelStore.clear() }
    }
    val listsViewModelStore = androidx.compose.runtime.remember(personalScopeKey) {
        TvRetainedDestinationStore<String, com.torve.presentation.lists.TraktListsViewModel>(6,
            com.torve.presentation.lists.TraktListsViewModel::clear)
    }
    DisposableEffect(listsViewModelStore) { onDispose { listsViewModelStore.clear() } }
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

    // The route definitions do not change with hero artwork, ratings or focus.
    // Stable graph identity avoids rebuilding every destination on those updates;
    // delegates keep each screen's callbacks and scope current.
    val latestRailFocusRequester by rememberUpdatedState(railFocusRequester)
    val latestOnVoiceSearchQuery by rememberUpdatedState(onVoiceSearchQuery)
    val latestOnSettingsClick by rememberUpdatedState(onSettingsClick)
    val latestOnHomeLayoutBack by rememberUpdatedState(onHomeLayoutBack)
    val latestOnRatingsBack by rememberUpdatedState(onRatingsBack)
    val latestOnWatchStatsBack by rememberUpdatedState(onWatchStatsBack)
    val latestOnPandaSetupBack by rememberUpdatedState(onPandaSetupBack)
    val latestOnAutomationConnectionsBack by rememberUpdatedState(onAutomationConnectionsBack)
    val latestOnSeeAllBack by rememberUpdatedState(onSeeAllBack)
    val latestContextMenuActionsForItem by rememberUpdatedState(contextMenuActionsForItem)
    val latestOnContextMenuAction by rememberUpdatedState(onContextMenuAction)
    val latestOnDetailsBack by rememberUpdatedState(onDetailsBack)
    val latestOnPairingSignInSuccess by rememberUpdatedState(onPairingSignInSuccess)
    val latestOnBeforeDetailsNavigateFromSeeAll by rememberUpdatedState(onBeforeDetailsNavigateFromSeeAll)
    val latestOnRequestLifetimeUnlock by rememberUpdatedState(onRequestLifetimeUnlock)
    val latestOnWatchedStatusChanged by rememberUpdatedState(onWatchedStatusChanged)
    val latestOnProfileSwitched by rememberUpdatedState(onProfileSwitched)
    val latestIsStreamPlaybackLocked by rememberUpdatedState(isStreamPlaybackLocked)
    val latestPersonalScopeKey by rememberUpdatedState(personalScopeKey)
    val latestOnPlaybackFocusRestoreRequest by rememberUpdatedState(onPlaybackFocusRestoreRequest)
    val latestPlaybackFocusRestoreRequestId by rememberUpdatedState(playbackFocusRestoreRequestId)
    val latestOnFirstContentRequester by rememberUpdatedState(onFirstContentRequester)
    val latestOnContentFocused by rememberUpdatedState(onContentFocused)
    val latestOnDetailsContentFocusStateChanged by rememberUpdatedState(onDetailsContentFocusStateChanged)
    val latestRegisterSeeAllFocusHandle by rememberUpdatedState(registerSeeAllFocusHandle)
    val latestHomeLayoutEntryFocusRequester by rememberUpdatedState(homeLayoutEntryFocusRequester)
    val latestOnHomeLayoutEntryReadyChanged by rememberUpdatedState(onHomeLayoutEntryReadyChanged)
    val latestOnHomeLayoutEntryFocused by rememberUpdatedState(onHomeLayoutEntryFocused)
    val latestRatingsEntryFocusRequester by rememberUpdatedState(ratingsEntryFocusRequester)
    val latestOnRatingsEntryReadyChanged by rememberUpdatedState(onRatingsEntryReadyChanged)
    val latestOnRatingsEntryFocused by rememberUpdatedState(onRatingsEntryFocused)
    val graphBuilder: androidx.navigation.NavGraphBuilder.() -> Unit = remember(navController, personalScopeKey) {
        {
        webReplayRoutes(navController, isTv = true) { event, stream, start ->
            navController.navigate(TvRoutes.player(stream.url, "", event.title, event.id, "movie",
                posterUrl = event.artwork.orEmpty(), backdropUrl = event.artwork.orEmpty(), startPositionMs = start))
        }
        /* Empty placeholder — visible when no sub-route is active */
            composable("tv_library_collection/{kind}?folder={folder}&provider={provider}", arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("folder") { type = NavType.StringType; defaultValue = "" },
                navArgument("provider") { type = NavType.StringType; defaultValue = "REAL_DEBRID" })) { entry ->
                com.torve.android.ui.community.LibraryCollectionScreen(entry.arguments?.getString("kind").orEmpty(),
                    entry.arguments?.getString("folder").orEmpty(), entry.arguments?.getString("provider") ?: "REAL_DEBRID",
                    isTv = true, onBack = { navController.popBackStack() }, onSetup = latestOnSettingsClick,
                    onOpen = { row, provider ->
                        if (row.kind == "folder") navController.navigate("tv_library_collection/cloud?folder=${android.net.Uri.encode(row.id)}&provider=${android.net.Uri.encode(provider)}")
                        else {
                            val id = row.mediaId.removePrefix("tmdb:").toIntOrNull()
                            if (id != null) navController.navigate(TvRoutes.details(row.mediaType, id, season = row.season, episode = row.episode))
                            else navController.navigateToTvDetails(MediaItem(id = row.mediaId, type = if (row.mediaType == "tv") MediaType.SERIES else MediaType.MOVIE, title = row.title, posterUrl = row.artwork))
                        }
                    }, onPlay = { url, row ->
                        navController.navigate(TvRoutes.player(url, "", row.title, row.mediaId, row.mediaType, row.artwork.orEmpty(), row.backdrop.orEmpty()))
                    })
            }
            composable("tv_cloud_backup") {
                com.torve.android.ui.community.CloudBackupScreen(isTv = true,
                    onBack = { navController.popBackStack() }, onSignIn = { navController.navigate("pairing_signin_tv") })
            }
            composable("tv_mdblist") { com.torve.android.ui.community.MdbListManager(onBack = { navController.popBackStack() }) }
            composable("tv_appearance_refinements") {
                com.torve.android.ui.community.AppearanceControls(onBack = { navController.popBackStack() })
            }
            composable("tv_collections?movie={movie}&franchise={franchise}&name={name}", arguments = listOf(
                navArgument("movie") { type = NavType.IntType; defaultValue = 0 },
                navArgument("franchise") { type = NavType.IntType; defaultValue = 0 },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
            )) { entry ->
                com.torve.android.ui.community.CollectionsScreen(
                    movieId = entry.arguments?.getInt("movie")?.takeIf { it > 0 },
                    collectionId = entry.arguments?.getInt("franchise")?.takeIf { it > 0 },
                    collectionName = entry.arguments?.getString("name"),
                    onBack = { navController.popBackStack() }, onMediaClick = { navController.navigateToTvDetails(it) })
            }
        composable("tv_person/{personId}", arguments = listOf(navArgument("personId") { type = NavType.IntType })) { entry ->
            TvPersonScreen(entry.arguments?.getInt("personId") ?: 0,
                onBack = { navController.popBackStack() }, onMediaClick = { navController.navigateToTvDetails(it) },
                railFocusRequester = latestRailFocusRequester,
                onContentFocused = { latestOnContentFocused("tv_person", it) })
        }
        composable(TvRoutes.SUB_NAV_START) { /* nothing */ }

        composable(TvRoutes.WATCHING_HUB) {
            com.torve.android.tv.screens.TvWatchingHubScreen(
                onBack = { navController.popBackStack() },
                onEpisode = { next ->
                    if (!navController.openExtensionDetailIfNeeded(next.show, tv = true, season = next.season, episode = next.episode.episodeNumber)) {
                        val id = next.show.tmdbId ?: next.show.id.removePrefix("tmdb:").toIntOrNull()
                        if (id != null) navController.navigate(TvRoutes.details("tv", id, focusEpisodes = true, season = next.season, episode = next.episode.episodeNumber))
                        else navController.navigateToTvDetails(next.show)
                    }
                }, onContentFocused = { latestOnContentFocused(TvRoutes.WATCHING_HUB, it) })
        }
        composable(TvRoutes.FILES) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides
                androidx.compose.runtime.remember { com.torve.android.tv.screens.TvChannelCenterBringIntoViewSpec() }) {
            com.torve.android.ui.files.FilesBrowserScreen(onBack = { navController.popBackStack() }, isTv = true,
                onContentFocused = { latestOnContentFocused(TvRoutes.FILES, it) }, onPlay = { entry ->
                    navController.navigate(TvRoutes.player(url = entry.url, fallbackUrl = "", title = entry.name,
                        mediaId = entry.mediaId, mediaType = "movie", posterUrl = "", backdropUrl = ""))
                })
            }
        }
        composable(TvRoutes.PROFILES) {
            com.torve.android.tv.screens.TvProfilesScreen(onBack = { navController.popBackStack() },
                onSwitched = { navController.popBackStack(TvRoutes.SUB_NAV_START, false); latestOnProfileSwitched() },
                onContentFocused = { latestOnContentFocused(TvRoutes.PROFILES, it) })
        }
        composable(TvRoutes.CATALOG_GROUPS) {
            com.torve.android.tv.screens.TvCatalogGroupsScreen(onBack = { navController.popBackStack() },
                onOpenGroup = { group, _ -> navController.navigate(TvRoutes.catalogGroup(group.id)) },
                onContentFocused = { latestOnContentFocused(TvRoutes.CATALOG_GROUPS, it) })
        }
        composable(TvRoutes.CATALOG_GROUP, arguments = listOf(navArgument("groupId") { type = NavType.StringType })) { entry ->
            com.torve.android.tv.screens.TvCatalogGroupContentScreen(entry.arguments?.getString("groupId").orEmpty(),
                onBack = { navController.popBackStack() }, railFocusRequester = latestRailFocusRequester,
                onMedia = { navController.navigateToTvDetails(it) },
                onContentFocused = { latestOnContentFocused(TvRoutes.CATALOG_GROUP, it) })
        }
        composable(TvRoutes.TRAKT_LISTS, arguments = listOf(navArgument("type") { type = NavType.StringType; defaultValue = "" },
            navArgument("id") { type = NavType.IntType; defaultValue = 0 })) { entry ->
            val id = entry.arguments?.getInt("id") ?: 0
            val type = entry.arguments?.getString("type").orEmpty()
            val metadata: com.torve.domain.repository.MetadataRepository = koinInject()
            val listViewModel = androidx.compose.runtime.remember(entry.id, latestPersonalScopeKey) {
                listsViewModelStore.getOrPut(entry.id) {
                    org.koin.java.KoinJavaComponent.getKoin().get<com.torve.presentation.lists.TraktListsViewModel>()
                }
            }
            val titleState = androidx.compose.runtime.produceState<MediaItem?>(null, id, type) {
                if (id > 0) value = runCatching { metadata.getDetail(type, id) }.getOrElse {
                    MediaItem(id.toString(), tmdbId = id, type = if (type == "tv") MediaType.SERIES else MediaType.MOVIE, title = "This title") }
            }
            com.torve.android.tv.screens.TvTraktListsScreen(onBack = { navController.popBackStack() },
                onConnect = { navController.popBackStack(TvRoutes.SUB_NAV_START, false); latestOnSettingsClick() },
                onMedia = { navController.navigateToTvDetails(it) }, forTitle = titleState.value,
                viewModel = listViewModel, clearOnDispose = false,
                onContentFocused = { latestOnContentFocused(TvRoutes.TRAKT_LISTS, it) })
        }

        composable(TvRoutes.HOME_LAYOUT) {
            TvHomeLayoutScreen(
                railFocusRequester = latestRailFocusRequester,
                onBack = latestOnHomeLayoutBack,
                onFirstContentRequester = { latestOnFirstContentRequester(TvRoutes.HOME_LAYOUT, it) },
                onContentFocused = { latestOnContentFocused(TvRoutes.HOME_LAYOUT, it) },
                entryFocusRequester = latestHomeLayoutEntryFocusRequester,
                onEntryFocusReadyChanged = latestOnHomeLayoutEntryReadyChanged,
                onEntryFocusFocused = latestOnHomeLayoutEntryFocused,
            )
        }

        composable(TvRoutes.RATINGS_SETTINGS) {
            TvRatingsSettingsScreen(
                railFocusRequester = latestRailFocusRequester,
                onBack = latestOnRatingsBack,
                onFirstContentRequester = { latestOnFirstContentRequester(TvRoutes.RATINGS_SETTINGS, it) },
                onContentFocused = { latestOnContentFocused(TvRoutes.RATINGS_SETTINGS, it) },
                entryFocusRequester = latestRatingsEntryFocusRequester,
                onEntryFocusReadyChanged = latestOnRatingsEntryReadyChanged,
                onEntryFocusFocused = latestOnRatingsEntryFocused,
            )
        }

        composable(TvRoutes.WATCH_STATS) {
            val entryFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
            TvWatchStatsScreen(
                railFocusRequester = latestRailFocusRequester,
                onBack = latestOnWatchStatsBack,
                onOpenDetails = { session ->
                    val id = session.tmdbId
                        ?: session.mediaId.extractTmdbIdOrNull()
                        ?: session.mediaId.toIntOrNull()
                    if (id != null) {
                        val type = if (session.mediaType == MediaType.SERIES) "tv" else "movie"
                        navController.navigate(TvRoutes.details(type = type, id = id))
                    }
                },
                onRequestLifetimeUnlock = latestOnRequestLifetimeUnlock,
                onFirstContentRequester = { latestOnFirstContentRequester(TvRoutes.WATCH_STATS, it) },
                onContentFocused = { latestOnContentFocused(TvRoutes.WATCH_STATS, it) },
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
            com.torve.android.ui.community.ExtensionsScreen(onBack = latestOnPandaSetupBack, isTv = true)
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

        composable(
            "${TvRoutes.AUTOMATION_CONNECTIONS}?attention={attention}",
            arguments = listOf(navArgument("attention") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            val initialFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
            val attentionTarget = entry.arguments?.getString("attention")?.takeIf { it.isNotBlank() }
            val healthCoordinator: com.torve.presentation.providerhealth.ProviderHealthCoordinator = koinInject()
            val healthEntries = healthCoordinator.entries.collectAsState().value
            val attentionEntry = com.torve.android.tv.screens.providerAttentionEntries(healthEntries).firstOrNull {
                it.providerKey == if (attentionTarget == "seerr") "seerr:request_manager" else "automation:admin"
            }
            com.torve.android.ui.settings.AutomationControlMode(tvEnabled = true) {
                com.torve.android.ui.settings.AutomationConnectionsScreen(
                    onBack = latestOnAutomationConnectionsBack,
                    initialFocusRequester = initialFocusRequester,
                    attentionTarget = attentionTarget,
                    attentionMessage = attentionEntry?.message,
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
                    latestOnPairingSignInSuccess()
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
            val personId = railKey.takeIf { it.startsWith("person_credits_") }
                ?.removePrefix("person_credits_")?.toIntOrNull()
            if (personId != null) TvPersonScreen(personId, latestOnSeeAllBack,
                onMediaClick = { latestOnBeforeDetailsNavigateFromSeeAll(); navController.navigateToTvDetails(it) },
                railFocusRequester = latestRailFocusRequester, initialName = title,
                onFirstContentRequester = { latestOnFirstContentRequester(TvRoutes.SEE_ALL, it) },
                onContentFocused = { latestOnContentFocused(TvRoutes.SEE_ALL, it) })
            else TvSeeAllScreen(
                railKey = railKey,
                mediaType = mediaType,
                title = title,
                railFocusRequester = latestRailFocusRequester,
                onMediaClick = { item ->
                    latestOnBeforeDetailsNavigateFromSeeAll()
                    navController.navigateToTvDetails(item)
                },
                onBack = latestOnSeeAllBack,
                contextMenuActionsForItem = latestContextMenuActionsForItem,
                onContextMenuAction = latestOnContextMenuAction,
                onFirstContentRequester = { latestOnFirstContentRequester(TvRoutes.SEE_ALL, it) },
                onContentFocused = { latestOnContentFocused(TvRoutes.SEE_ALL, it) },
                onSeeAll = { childRailKey, childTitle, childMediaType ->
                    navController.navigate(
                        TvRoutes.seeAll(
                            railKey = childRailKey,
                            mediaType = childMediaType,
                            title = childTitle,
                        ),
                    )
                },
                registerFocusHandle = latestRegisterSeeAllFocusHandle,
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
                    railFocusRequester = latestRailFocusRequester,
                    onBack = { navController.popBackStack() },
                    onEpisodePlay = { episode, episodeItem ->
                        if (latestIsStreamPlaybackLocked) {
                            latestOnRequestLifetimeUnlock(TvEntitledFeature.STREAM_PLAYBACK)
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
                    onFirstContentRequester = { latestOnFirstContentRequester(TvRoutes.VOD_SERIES_DETAILS, it) },
                    onContentFocused = { latestOnContentFocused(TvRoutes.VOD_SERIES_DETAILS, it) },
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
                navArgument("season") { type = NavType.IntType; defaultValue = -1 },
                navArgument("episode") { type = NavType.IntType; defaultValue = -1 },
                navArgument("extension") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            val detailType = backStackEntry.arguments?.getString("type") ?: "movie"
            val detailId = backStackEntry.arguments?.getInt("id") ?: 0
            val autoPlay = backStackEntry.arguments?.getBoolean("autoPlay") ?: false
            val handoffPositionMs = backStackEntry.arguments?.getLong("handoffPositionMs") ?: 0L
            val focusEpisodes = backStackEntry.arguments?.getBoolean("focusEpisodes") ?: false
            val initialSeason = backStackEntry.arguments?.getInt("season")?.takeIf { it >= 0 }
            val initialEpisode = backStackEntry.arguments?.getInt("episode")?.takeIf { it > 0 }
            val destinationPlaybackRestoreRequestId = backStackEntry.savedStateHandle
                .getStateFlow(TV_PLAYBACK_RETURN_REQUEST_ID, 0)
                .collectAsState()
                .value
            val destinationPlaybackRestoreSeason = backStackEntry.savedStateHandle
                .getStateFlow(TV_PLAYBACK_RETURN_SEASON, -1)
                .collectAsState()
                .value
                .takeIf { it > 0 }
                ?: initialSeason
            val destinationPlaybackRestoreEpisode = backStackEntry.savedStateHandle
                .getStateFlow(TV_PLAYBACK_RETURN_EPISODE, -1)
                .collectAsState()
                .value
                .takeIf { it > 0 }
                ?: initialEpisode
            // Root clears its pending request when a detail control gains focus.
            // Keep that signal on this destination so acknowledgement cannot
            // change the effect id and cancel its final focus confirmation.
            val rootPlaybackRestoreRequestId = latestPlaybackFocusRestoreRequestId.takeIf { it > 0 }
                ?: backStackEntry.savedStateHandle.get<Int>(TV_PLAYBACK_RETURN_ROOT_REQUEST_ID)
                ?: 0
            SideEffect {
                if (latestPlaybackFocusRestoreRequestId > 0) {
                    backStackEntry.savedStateHandle[TV_PLAYBACK_RETURN_ROOT_REQUEST_ID] =
                        latestPlaybackFocusRestoreRequestId
                }
            }
            val detailViewModel = androidx.compose.runtime.remember(backStackEntry.id, latestPersonalScopeKey) {
                detailViewModelStore.getOrPut(backStackEntry.id) {
                    org.koin.java.KoinJavaComponent.getKoin().get<DetailViewModel>()
                }
            }
            val playbackRestoreEffectId = playbackFocusRestoreEffectId(
                rootRequestId = rootPlaybackRestoreRequestId,
                destinationRequestId = destinationPlaybackRestoreRequestId,
            )
            val isInitialEpisodeSelection = playbackRestoreEffectId == 0L &&
                initialSeason != null && initialEpisode != null
            TvDetailsScreen(
                type = detailType,
                id = detailId,
                extensionSeedJson = navController.extensionDetailSeed(backStackEntry),
                autoPlay = autoPlay,
                focusEpisodes = focusEpisodes,
                railFocusRequester = latestRailFocusRequester,
                onBack = latestOnDetailsBack,
                onFirstContentRequester = { latestOnFirstContentRequester(TvRoutes.DETAILS, it) },
                onContentFocused = { latestOnContentFocused(TvRoutes.DETAILS, it) },
                onContentFocusStateChanged = latestOnDetailsContentFocusStateChanged,
                // Entry must not share epoch 1 with the first return from playback.
                playbackFocusRestoreRequestId = if (isInitialEpisodeSelection) Long.MAX_VALUE else playbackRestoreEffectId,
                playbackFocusRestoreSeason = destinationPlaybackRestoreSeason,
                playbackFocusRestoreEpisode = destinationPlaybackRestoreEpisode,
                preserveEntryPlaybackTarget = isInitialEpisodeSelection,
                onMediaClick = { item -> navController.navigateToTvDetails(item) },
                onSettingsClick = {
                    // Focus state cleanup handled in TvRoot via isSubRouteActive LaunchedEffect
                    navController.popBackStack()
                    latestOnSettingsClick()
                },
                onLibraryAutomationClick = {
                    navController.navigate(TvRoutes.AUTOMATION_CONNECTIONS)
                },
                onCollectionsClick = { item -> navController.navigate("tv_collections?movie=${item.tmdbId ?: 0}&franchise=${item.collectionId ?: 0}&name=${android.net.Uri.encode(item.collectionName.orEmpty())}") },
                onTraktListsClick = { item -> item.tmdbId?.let { navController.navigate(TvRoutes.traktLists(
                    if (item.type == MediaType.SERIES) "tv" else "movie", it)) } },
                onRequestLifetimeUnlock = latestOnRequestLifetimeUnlock,
                onWatchedStatusChanged = latestOnWatchedStatusChanged,
                detailViewModel = detailViewModel,
                onCastClick = { castId, _ -> navController.navigate("tv_person/$castId") },
                onPlayResolved = { url, fallbackUrl, mediaItem, season, episode, autoSourceSelection, episodeName ->
                    com.torve.android.ui.player.PlaybackMetadataStore.stage(org.koin.java.KoinJavaComponent.getKoin().get<com.torve.data.auth.UserIdProvider>().currentDataUserId(), detailViewModel.state.value)
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
            if (latestIsStreamPlaybackLocked) {
                LaunchedEffect(Unit) {
                    latestOnRequestLifetimeUnlock(TvEntitledFeature.STREAM_PLAYBACK)
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
                        latestOnPlaybackFocusRestoreRequest(returnRoute)
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
            if (latestIsStreamPlaybackLocked) {
                LaunchedEffect(Unit) {
                    latestOnRequestLifetimeUnlock(TvEntitledFeature.STREAM_PLAYBACK)
                    // Focus state cleanup handled in TvRoot via isSubRouteActive LaunchedEffect
                    navController.popBackStack()
                }
            } else {
                PlayerScreen(
                    url = backStackEntry.arguments?.getString("url") ?: "",
                    fallbackUrl = backStackEntry.arguments?.getString("fallbackUrl") ?: "",
                    autoSourceSelection = backStackEntry.arguments?.getBoolean("autoSourceSelection") ?: false,
                    title = com.torve.android.tv.components.tvSettingsLabel(backStackEntry.arguments?.getString("title") ?: ""),
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
                            latestOnVoiceSearchQuery(normalized)
                        }
                    },
                    onBack = {
                        val returnEntry = navController.previousBackStackEntry
                        val returnRoute = returnEntry?.destination?.route
                        // Exo playback deliberately hands focus to the retained
                        // playback card. MPV/non-retained exits have no such
                        // owner, so restore the originating details control.
                        if (ActivePlaybackState.session == null) {
                            returnEntry?.savedStateHandle?.queuePlaybackFocusRestore(
                                seasonNumber = backStackEntry.arguments?.getInt("seasonNumber")
                                    ?.takeIf { it > 0 },
                                episodeNumber = backStackEntry.arguments?.getInt("episodeNumber")
                                    ?.takeIf { it > 0 },
                            )
                        }
                        navController.popBackStack()
                        if (ActivePlaybackState.session == null) {
                            latestOnPlaybackFocusRestoreRequest(returnRoute)
                        }
                    },
                    onStop = {
                        val returnEntry = navController.previousBackStackEntry
                        val returnRoute = returnEntry?.destination?.route
                        returnEntry?.savedStateHandle?.queuePlaybackFocusRestore(
                            seasonNumber = backStackEntry.arguments?.getInt("seasonNumber")
                                ?.takeIf { it > 0 },
                            episodeNumber = backStackEntry.arguments?.getInt("episodeNumber")
                                ?.takeIf { it > 0 },
                        )
                        navController.popBackStack()
                        latestOnPlaybackFocusRestoreRequest(returnRoute)
                    },
                )
            }
        }
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
            builder = graphBuilder,
        )

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
                val playbackBarEntryFocusRequestId = androidx.compose.runtime.remember(
                    currentBackStackEntry?.id, activePlaybackSession.url,
                ) { ActivePlaybackState.focusPlaybackBarRequestId }
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
                    // Details restores the source picker after playback. Keep
                    // the playback bar reachable through an explicit Menu request.
                    requestInitialFocus = currentBackStackEntry?.destination?.route != TvRoutes.DETAILS ||
                        ActivePlaybackState.focusPlaybackBarRequestId > playbackBarEntryFocusRequestId,
                    focusRequestId = ActivePlaybackState.focusPlaybackBarRequestId,
                    onNavigateAway = { onPlaybackFocusRestoreRequest(currentRoute) },
                )
            }
        }
    }
}
