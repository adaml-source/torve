package com.torve.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.tv.components.TvBrowseLayout
import com.torve.android.tv.components.TvCardStyle
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.components.TvRailsPresentationMode
import com.torve.android.tv.components.TvOnNowRail
import com.torve.android.tv.components.TvProviderHealthBanner
import com.torve.android.tv.components.dedupeAcrossRails
import com.torve.android.tv.components.rememberTvFocusMemory
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.toMediaItemOrNull
import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.CustomSection
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.withEnrichedRatingsFrom
import com.torve.platform.NetworkMonitor
import com.torve.platform.NetworkType
import com.torve.presentation.home.HomeUiState
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.lanlibrary.LanLibraryConsumer
import com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.tvhome.TvHomeOutcomeUiState
import com.torve.presentation.tvhome.TvHomeOutcomeViewModel
import com.torve.presentation.tvhome.TvHomePlaybackDecision
import com.torve.presentation.tvhome.TvHomePlaybackRouter
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private sealed interface TvHomeRenderItem {
    val order: Int
}

private data class BuiltInHomeItem(
    val config: HomeSectionConfig,
) : TvHomeRenderItem {
    override val order: Int = config.order
}

private data class CustomHomeItem(
    val section: CustomSection,
) : TvHomeRenderItem {
    override val order: Int = section.order
}

private data class AddonShelfHomeItem(
    val shelf: CatalogShelf,
    override val order: Int,
) : TvHomeRenderItem

@Composable
internal fun TvHomeScreen(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)? = null,
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    heroOverlay: (@Composable () -> Unit)? = null,
    shouldAutoFocus: Boolean = true,
    browseLayout: TvBrowseLayout = TvBrowseLayout.INFO_PANEL,
    progressResolver: ((MediaItem, Float?) -> Float?)? = null,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)? = null,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)? = null,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
    /**
     * Direct-to-player launch for AutoplayLocal decisions. Caller
     * navigates to the player route with the given absolute file path
     * as a `file://` URL. When null, autoplay-eligible tiles fall
     * through to [onMediaClick] (loses the single-OK property).
     */
    onPlayLocalFile: ((MediaItem, absolutePath: String) -> Unit)? = null,
    /**
     * Direct-to-player launch for AutoplayLan decisions. Caller
     * navigates to the player route with the LAN URL — headers are
     * attached automatically via PendingLanPlaybackHandoff.
     */
    onPlayLanRoute: ((MediaItem, lanUrl: String) -> Unit)? = null,
    /**
     * Live-channel tile click. Receives an [EnrichedChannel] from the
     * outcome state's onNow bucket; caller navigates to the live
     * player route. Hidden when null.
     */
    onLiveChannelClick: ((EnrichedChannel) -> Unit)? = null,
    /** Provider banner action. When null, the banner stays informational. */
    onProviderBannerAction: (() -> Unit)? = null,
) {
    val homeViewModel: HomeViewModel = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val outcomeViewModel: TvHomeOutcomeViewModel = koinInject()
    val playbackRouter: TvHomePlaybackRouter = koinInject()
    val networkMonitor: NetworkMonitor = koinInject()
    val lanLibraryConsumer: LanLibraryConsumer = koinInject()

    val state by homeViewModel.state.collectAsState()
    val sectionConfigs by homeViewModel.sectionConfigs.collectAsState()
    val customSections by homeViewModel.customSections.collectAsState()
    val homeLayoutOrder by homeViewModel.homeLayoutOrder.collectAsState()
    val outcomeState by outcomeViewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val onNowFocusRequester = remember { FocusRequester() }

    val focusMemory = rememberTvFocusMemory()
    val emptyMessage = state.error ?: stringResource(R.string.tv_no_data)
    val onNowTitle = stringResource(R.string.tv_home_on_now)
    val availableNowTitle = stringResource(R.string.tv_home_available_now)
    val downloadsOnDesktopTitle = stringResource(R.string.tv_home_downloads_on_desktop)
    val recentlyAddedTitle = stringResource(R.string.tv_home_recently_added_sources)

    val rails = remember(
        state,
        sectionConfigs,
        customSections,
        homeLayoutOrder,
        outcomeState,
        state.continueWatchingRatings,
        availableNowTitle,
        downloadsOnDesktopTitle,
        recentlyAddedTitle,
    ) {
        buildOutcomeRails(
            outcome = outcomeState,
            availableNowTitle = availableNowTitle,
            downloadsOnDesktopTitle = downloadsOnDesktopTitle,
            recentlyAddedTitle = recentlyAddedTitle,
            ratingsByKey = state.continueWatchingRatings,
        ) +
            buildTvHomeRails(
                state = state,
                sectionConfigs = sectionConfigs,
                customSections = customSections,
                homeLayoutOrder = homeLayoutOrder,
            )
    }

    // Map a tap into a one-shot decision. Router lookup is suspend
    // (one DB read on the way in), then we either resolve a route and
    // launch the player directly (single-OK path) or open detail.
    // LAN token mint runs on the same coroutine — if the publisher has
    // gone away between the snapshot and the tap, we fall back to
    // opening detail rather than crashing or staring at a black screen.
    val handleMediaClick: (MediaItem) -> Unit = { item ->
        coroutineScope.launch {
            val networkMode = networkMonitor.currentNetworkType().toLanlibraryMode()
            val decision = playbackRouter.resolve(
                item = item,
                availability = outcomeState.availabilityByTmdbId,
                lanTitlesLowercase = outcomeState.lanTitlesLowercase,
                networkMode = networkMode,
                wifiOnlyForLan = settingsState.lanPlaybackWifiOnly,
            )
            when (decision) {
                is TvHomePlaybackDecision.AutoplayLocal -> {
                    val launch = onPlayLocalFile
                    if (launch != null) launch(item, decision.absolutePath)
                    else onMediaClick(item)
                }
                is TvHomePlaybackDecision.AutoplayLan -> {
                    val launch = onPlayLanRoute
                    val route = runCatching {
                        lanLibraryConsumer.findLanRoute(
                            title = decision.title,
                            seasonNumber = decision.seasonNumber,
                            episodeNumber = decision.episodeNumber,
                        )
                    }.getOrNull()
                    if (launch != null && route != null) {
                        // Stage headers BEFORE navigating so the player
                        // attaches `X-Torve-Lan-Auth` on the same frame
                        // it calls play().
                        PendingLanPlaybackHandoff.stage(route)
                        launch(item, route.url)
                    } else {
                        onMediaClick(item)
                    }
                }
                TvHomePlaybackDecision.OpenDetail -> onMediaClick(item)
            }
        }
    }

    val composedHeroOverlay: (@Composable () -> Unit)? = remember(
        outcomeState.providerBanner,
        outcomeState.onNow,
        sectionConfigs,
        heroOverlay,
        onLiveChannelClick,
        onNowTitle,
        onNowFocusRequester,
        railFocusRequester,
        onContentFocused,
    ) {
        val banner = outcomeState.providerBanner
        val onNow = outcomeState.onNow
        val showOnNow = sectionConfigs
            .firstOrNull { it.section == HomeSection.ON_NOW }
            ?.enabled
            ?: HomeSection.ON_NOW.defaultEnabled
        val showOnNowRail = showOnNow && onNow.isNotEmpty() && onLiveChannelClick != null
        if (banner == null && !showOnNowRail && heroOverlay == null) {
            null
        } else {
            @Composable {
                if (banner != null) {
                    TvProviderHealthBanner(
                        banner = banner,
                        onClick = { onProviderBannerAction?.invoke() },
                    )
                }
                heroOverlay?.invoke()
                if (showOnNowRail) {
                    TvOnNowRail(
                        title = onNowTitle,
                        channels = onNow,
                        onChannelClick = { ch -> onLiveChannelClick?.invoke(ch) },
                        firstTileFocusRequester = onNowFocusRequester,
                        railFocusRequester = railFocusRequester,
                        onContentFocused = onContentFocused,
                    )
                }
            }
        }
    }

    TvMediaRails(
        rails = rails,
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = handleMediaClick,
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        screenId = "home",
        loading = state.isLoading,
        emptyMessage = emptyMessage,
        focusMemory = focusMemory,
        onMediaFocused = onMediaFocused,
        onSeeAll = onSeeAll,
        heroOverlay = composedHeroOverlay,
        presentationMode = TvRailsPresentationMode.CatalogHero,
        focusExclusive = true,
        heroOverlayFocusRequester = onNowFocusRequester.takeIf {
            outcomeState.onNow.isNotEmpty() &&
                onLiveChannelClick != null &&
                (
                    sectionConfigs.firstOrNull { config -> config.section == HomeSection.ON_NOW }?.enabled
                        ?: HomeSection.ON_NOW.defaultEnabled
                    )
        },
        shouldAutoFocus = shouldAutoFocus,
        browseLayout = browseLayout,
        progressResolver = progressResolver,
        contextMenuActionsForItem = contextMenuActionsForItem,
        onContextMenuAction = onContextMenuAction,
        registerFocusHandle = registerFocusHandle,
    )
}

private fun NetworkType.toLanlibraryMode(): NetworkMode = when (this) {
    NetworkType.WIFI -> NetworkMode.WIFI
    NetworkType.CELLULAR -> NetworkMode.CELLULAR
    NetworkType.ETHERNET -> NetworkMode.ETHERNET
    NetworkType.UNKNOWN, NetworkType.NONE -> NetworkMode.UNKNOWN
}

/**
 * Outcome rails are surfaced FIRST so the user lands on something they
 * can play immediately. Empty buckets are skipped, so the rest of the
 * (TMDB-shaped) rails fall into place when nothing's playable.
 */
private fun buildOutcomeRails(
    outcome: TvHomeOutcomeUiState,
    availableNowTitle: String,
    downloadsOnDesktopTitle: String,
    recentlyAddedTitle: String,
    ratingsByKey: Map<String, MediaRatings>,
): List<TvContentRail> {
    val out = mutableListOf<TvContentRail>()
    if (outcome.availableNow.isNotEmpty()) {
        out += TvContentRail(
            key = "outcome:available_now",
            title = availableNowTitle,
            items = outcome.availableNow.withEnrichedRatingsFrom(ratingsByKey),
        )
    }
    if (outcome.downloadsOnDesktop.isNotEmpty()) {
        out += TvContentRail(
            key = "outcome:downloads_on_desktop",
            title = downloadsOnDesktopTitle,
            items = outcome.downloadsOnDesktop.withEnrichedRatingsFrom(ratingsByKey),
        )
    }
    if (outcome.recentlyAdded.isNotEmpty()) {
        out += TvContentRail(
            key = "outcome:recently_added",
            title = recentlyAddedTitle,
            items = outcome.recentlyAdded.withEnrichedRatingsFrom(ratingsByKey),
        )
    }
    return out
}

private fun buildTvHomeRails(
    state: HomeUiState,
    sectionConfigs: List<HomeSectionConfig>,
    customSections: List<CustomSection>,
    homeLayoutOrder: List<String>,
): List<TvContentRail> {
    val orderIndex = homeLayoutOrder.withIndex().associate { it.value to it.index }
    val addonShelfVisibility = state.addonShelfVisibility

    fun itemKey(item: TvHomeRenderItem): String = when (item) {
        is BuiltInHomeItem -> "section:${item.config.section.name}"
        is CustomHomeItem -> "custom:${item.section.id}"
        is AddonShelfHomeItem -> "addon:${item.shelf.id}"
    }

    val renderItems = buildList<TvHomeRenderItem> {
        sectionConfigs.filter { it.enabled }.forEach { add(BuiltInHomeItem(it)) }
        customSections.filter { it.enabled }.forEach { add(CustomHomeItem(it)) }
        state.addonShelves.forEachIndexed { index, shelf ->
            if (addonShelfVisibility[shelf.id] != false) {
                add(
                    AddonShelfHomeItem(
                        shelf = shelf,
                        order = orderIndex["addon:${shelf.id}"] ?: (10_000 + 100 + index),
                    ),
                )
            }
        }
    }.sortedWith(
        compareBy<TvHomeRenderItem> { item ->
            orderIndex[itemKey(item)] ?: (10_000 + item.order)
        }.thenBy { it.order },
    )

    val rails = mutableListOf<TvContentRail>()
    renderItems.forEach { item ->
        when (item) {
            is BuiltInHomeItem -> {
                rails += buildBuiltInRails(item.config, state)
            }
            is CustomHomeItem -> {
                val items = state.customShelves[item.section.id].orEmpty().take(24)
                if (items.isNotEmpty()) {
                    rails += TvContentRail(
                        key = "custom:${item.section.id}",
                        title = item.section.title,
                        items = items,
                    )
                }
            }
            is AddonShelfHomeItem -> {
                val items = item.shelf.items.take(24)
                if (items.isNotEmpty()) {
                    rails += TvContentRail(
                        key = "addon:${item.shelf.id}",
                        title = item.shelf.title,
                        items = items,
                    )
                }
            }
        }
    }

    return rails.dedupeAcrossRails()
}

private fun buildBuiltInRails(
    config: HomeSectionConfig,
    state: HomeUiState,
): List<TvContentRail> {
    val title = config.customTitle ?: config.section.defaultTitle
    return when (config.section) {
        HomeSection.SEARCH_BAR,
        HomeSection.HERO,
        HomeSection.ON_NOW,
        HomeSection.STREAMING_SERVICES,
        HomeSection.ACTORS,
        HomeSection.DIRECTORS,
        HomeSection.UPCOMING_SCHEDULE,
        HomeSection.ADDON_SHELVES -> {
            emptyList()
        }

        HomeSection.CONTINUE_WATCHING -> {
            val items = state.continueWatching
                .mapNotNull { it.toMediaItemOrNull() }
                .filter { it.tmdbId != null }
                .take(20)
                .withEnrichedRatingsFrom(state.continueWatchingRatings)
            if (items.isEmpty()) emptyList()
            else {
                listOf(
                    TvContentRail(
                        key = "continue_watching",
                        title = title,
                        items = items,
                        cardStyle = TvCardStyle.BACKDROP,
                        progressByMediaId = state.continueWatching
                            .filter { it.progressPercent > 0f }
                            .associate { it.mediaId to it.progressPercent },
                    ),
                )
            }
        }

        HomeSection.WATCHLIST -> {
            val items = state.watchlistItems.take(24)
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "watchlist", title = title, items = items))
        }

        HomeSection.WATCHLIST_MOVIES -> {
            val items = state.watchlistItems
                .filter { it.type == MediaType.MOVIE }
                .take(24)
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "watchlist_movies", title = title, items = items))
        }

        HomeSection.WATCHLIST_TV -> {
            val items = state.watchlistItems
                .filter { it.type == MediaType.SERIES }
                .take(24)
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "watchlist_tv", title = title, items = items))
        }

        HomeSection.TRENDING_MOVIES,
        HomeSection.TRENDING_TV,
        HomeSection.POPULAR_MOVIES,
        HomeSection.NOW_PLAYING,
        HomeSection.NEW_RELEASES,
        HomeSection.TOP_RATED -> {
            val shelf = state.shelves.firstOrNull { it.id == config.section.shelfId }
            val items = shelf?.items?.take(24).orEmpty()
            if (items.isEmpty()) emptyList()
            else {
                listOf(
                    TvContentRail(
                        key = config.section.shelfId ?: config.section.name.lowercase(),
                        title = title,
                        items = items,
                    ),
                )
            }
        }

        HomeSection.RECOMMENDED -> {
            val items = state.recommendedItems.map { it.item }.take(24)
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "recommended", title = title, items = items))
        }

        HomeSection.RECENTLY_WATCHED -> {
            val items = state.recentlyWatched.take(24)
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "recently_watched", title = title, items = items))
        }

        HomeSection.HIDDEN_GEMS -> {
            val shelf = state.hiddenGemsShelf
            val items = shelf?.items?.take(24).orEmpty()
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = shelf?.id ?: "hidden_gems", title = title, items = items))
        }

        HomeSection.BECAUSE_YOU_WATCHED -> {
            state.becauseYouWatched.mapNotNull { shelf ->
                shelf.items.take(24)
                    .takeIf { it.isNotEmpty() }
                    ?.let { items ->
                        TvContentRail(
                            key = shelf.id,
                            title = shelf.title,
                            items = items,
                        )
                    }
            }
        }

        HomeSection.MDBLIST_SHELVES -> {
            state.mdbListShelves.mapNotNull { shelf ->
                shelf.items.take(24)
                    .takeIf { it.isNotEmpty() }
                    ?.let { items ->
                        TvContentRail(
                            key = "mdblist:${shelf.id}",
                            title = shelf.title,
                            items = items,
                        )
                    }
            }
        }
    }
}
