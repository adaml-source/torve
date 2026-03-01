package com.streamvault.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streamvault.android.R
import com.streamvault.android.sync.SyncCoordinator
import com.streamvault.android.sync.model.SyncInboundEvent
import com.streamvault.android.tv.components.TvHeader
import com.streamvault.android.tv.components.TvNavRail
import com.streamvault.android.tv.nav.TvNavHost
import com.streamvault.android.tv.nav.TvRoutes
import com.streamvault.android.tv.nav.tvTopDestinations
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.repository.MetadataRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

private fun topRouteFromDestination(route: String?): String? = when {
    route == null -> TvRoutes.HOME
    route.startsWith(TvRoutes.HOME) -> TvRoutes.HOME
    route.startsWith(TvRoutes.MOVIES) -> TvRoutes.MOVIES
    route.startsWith(TvRoutes.SHOWS) -> TvRoutes.SHOWS
    route.startsWith(TvRoutes.SEARCH) -> TvRoutes.SEARCH
    route.startsWith(TvRoutes.LIBRARY) -> TvRoutes.LIBRARY
    route.startsWith(TvRoutes.SETTINGS) -> TvRoutes.SETTINGS
    else -> null
}

private fun NavHostController.navigateToTvDetails(item: MediaItem, autoPlay: Boolean = false) {
    val id = item.tmdbId ?: item.id.toIntOrNull() ?: return
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    navigate(TvRoutes.details(type = type, id = id, autoPlay = autoPlay))
}

@Composable
fun TvRoot() {
    val navController = rememberNavController()
    val metadataRepo: MetadataRepository = koinInject()
    val syncCoordinator: SyncCoordinator = koinInject()
    val syncState by syncCoordinator.state.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentTopRoute = topRouteFromDestination(currentRoute)

    var selectedTopRoute by rememberSaveable { mutableStateOf(TvRoutes.HOME) }
    if (currentTopRoute != null) {
        selectedTopRoute = currentTopRoute
    }

    val isPlayerRoute = currentRoute?.startsWith("tv_player") == true
    val showRail = !isPlayerRoute
    val showHeader = !isPlayerRoute && currentTopRoute != null

    val railFocusRequester = remember { FocusRequester() }
    val headerPrimaryActionRequester = remember { FocusRequester() }
    val firstContentFocusByRoute = remember { mutableStateMapOf<String, FocusRequester>() }
    val lastFocusedContentByRoute = remember { mutableStateMapOf<String, FocusRequester>() }
    var searchSeedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var syncNotice by remember { mutableStateOf<String?>(null) }

    var isRailExpanded by rememberSaveable { mutableStateOf(false) }
    var previousRoute by remember { mutableStateOf<String?>(null) }

    fun moveRailFocusToContent() {
        val route = selectedTopRoute
        val target = lastFocusedContentByRoute[route] ?: firstContentFocusByRoute[route]
        target?.requestFocus()
    }

    LaunchedEffect(currentRoute, currentTopRoute) {
        val cameFromDetails = previousRoute?.startsWith("tv_details") == true
        val cameFromPlayer = previousRoute?.startsWith("tv_player") == true
        if ((cameFromDetails || cameFromPlayer) && currentTopRoute != null) {
            val targetRoute = currentTopRoute
            val target = lastFocusedContentByRoute[targetRoute] ?: firstContentFocusByRoute[targetRoute]
            target?.requestFocus()
        }
        previousRoute = currentRoute
    }

    LaunchedEffect(syncState.isAuthenticated) {
        if (syncState.isAuthenticated && syncState.devices.isEmpty()) {
            syncCoordinator.refreshDevices()
        }
    }

    LaunchedEffect(Unit) {
        syncCoordinator.inboundEvents.collectLatest { event ->
            when (event) {
                is SyncInboundEvent.SearchPush -> {
                    searchSeedQuery = event.query
                    syncNotice = "Search received from phone"
                    navController.navigate(TvRoutes.SEARCH) {
                        popUpTo(TvRoutes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                is SyncInboundEvent.PlaybackIntent -> {
                    val detailId = event.contentId.toIntOrNull() ?: return@collectLatest
                    val detailType = if (event.mediaType == "tv") "tv" else "movie"
                    syncNotice = "Playback handoff received"
                    navController.navigate(
                        TvRoutes.details(
                            type = detailType,
                            id = detailId,
                            autoPlay = true,
                            handoffPositionMs = event.positionMs.coerceAtLeast(0L),
                        ),
                    ) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    LaunchedEffect(syncNotice) {
        if (syncNotice != null) {
            delay(2400)
            syncNotice = null
        }
    }

    val featuredItem by produceState<MediaItem?>(initialValue = null, selectedTopRoute, metadataRepo) {
        value = try {
            when (selectedTopRoute) {
                TvRoutes.MOVIES -> metadataRepo.getTrending("movie").firstOrNull()
                TvRoutes.SHOWS -> metadataRepo.getTrending("tv").firstOrNull()
                TvRoutes.HOME -> metadataRepo.getPopular("movie").firstOrNull()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    val sectionTitle = when (selectedTopRoute) {
        TvRoutes.MOVIES -> stringResource(R.string.nav_movies)
        TvRoutes.SHOWS -> stringResource(R.string.nav_tv_shows)
        TvRoutes.SEARCH -> stringResource(R.string.tv_nav_search)
        TvRoutes.LIBRARY -> stringResource(R.string.tv_nav_library)
        TvRoutes.SETTINGS -> stringResource(R.string.tv_nav_settings)
        else -> stringResource(R.string.nav_home)
    }
    val sectionSubtitle = when (selectedTopRoute) {
        TvRoutes.MOVIES -> stringResource(R.string.tv_hero_subtitle_movies)
        TvRoutes.SHOWS -> stringResource(R.string.tv_hero_subtitle_shows)
        TvRoutes.SEARCH -> stringResource(R.string.tv_hero_subtitle_search)
        TvRoutes.LIBRARY -> stringResource(R.string.tv_hero_subtitle_library)
        TvRoutes.SETTINGS -> stringResource(R.string.tv_hero_subtitle_settings)
        else -> stringResource(R.string.tv_hero_subtitle_home)
    }

    TvScaffold(
        leftRail = {
            if (showRail) {
                TvNavRail(
                    destinations = tvTopDestinations,
                    selectedRoute = selectedTopRoute,
                    isExpanded = isRailExpanded,
                    railFocusRequester = railFocusRequester,
                    onRailFocusChanged = { hasFocus -> isRailExpanded = hasFocus },
                    onMoveToContent = { moveRailFocusToContent() },
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(TvRoutes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
        header = {
            if (showHeader) {
                TvHeader(
                    sectionTitle = sectionTitle,
                    subtitle = sectionSubtitle,
                    featuredItem = featuredItem,
                    primaryActionFocusRequester = headerPrimaryActionRequester,
                    railFocusRequester = railFocusRequester,
                    onPlayFeatured = {
                        featuredItem?.let { navController.navigateToTvDetails(it, autoPlay = true) }
                    },
                    onOpenFeatured = {
                        featuredItem?.let { navController.navigateToTvDetails(it) }
                    },
                )
            } else {
                Spacer(modifier = Modifier.height(0.dp))
            }
        },
        content = {
            Box(modifier = Modifier.fillMaxWidth()) {
                TvNavHost(
                    navController = navController,
                    railFocusRequester = railFocusRequester,
                    headerFocusRequester = headerPrimaryActionRequester,
                    onFirstContentRequester = { route, requester ->
                        firstContentFocusByRoute[route] = requester
                    },
                    onContentFocused = { route, requester ->
                        lastFocusedContentByRoute[route] = requester
                    },
                    onVoiceSearchQuery = { query ->
                        searchSeedQuery = query
                        syncNotice = "Search: $query"
                    },
                    searchSeedQuery = searchSeedQuery,
                )
                syncNotice?.let { notice ->
                    Box(
                        modifier = Modifier
                            .padding(top = 18.dp, end = 20.dp)
                            .align(androidx.compose.ui.Alignment.TopEnd)
                            .background(Color(0xCC18243A), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0x66C9A062), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(text = notice, color = Color.White)
                    }
                }
            }
        },
    )
}
