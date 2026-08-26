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
import com.torve.android.tv.isTvCatalogProgress
import com.torve.android.tv.toMediaItemOrNull
import com.torve.android.ui.home.ALL_STREAMING_SERVICES
import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.CustomSection
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.PersonSummary
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
    val enabledStreamingServiceIds by homeViewModel.enabledServiceIds.collectAsState()
    val providerLogos by homeViewModel.providerLogos.collectAsState()
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
    val moreLikeThisTitle = stringResource(R.string.tv_detail_more_like_this)

    val rails = remember(
        state,
        sectionConfigs,
        customSections,
        homeLayoutOrder,
        enabledStreamingServiceIds,
        providerLogos,
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
                enabledStreamingServiceIds = enabledStreamingServiceIds,
                providerLogos = providerLogos,
            )
    }

    // Map a tap into a one-shot decision. Router lookup is suspend
    // (one DB read on the way in), then we either resolve a route and
    // launch the player directly (single-OK path) or open detail.
    // LAN token mint runs on the same coroutine — if the publisher has
    // gone away between the snapshot and the tap, we fall back to
    // opening detail rather than crashing or staring at a black screen.
    val handleMediaClick: (MediaItem) -> Unit = { item ->
        val providerId = item.id.removePrefix(TV_HOME_PROVIDER_ID_PREFIX)
            .takeIf { item.id.startsWith(TV_HOME_PROVIDER_ID_PREFIX) }
            ?.toIntOrNull()
        val personId = item.id.removePrefix(TV_HOME_PERSON_ID_PREFIX)
            .takeIf { item.id.startsWith(TV_HOME_PERSON_ID_PREFIX) }
            ?.toIntOrNull()
        if (providerId != null) {
            onSeeAll?.invoke("provider_movie_$providerId", item.title)
        } else if (personId != null) {
            onSeeAll?.invoke("person_credits_$personId", item.title)
        } else {
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
        val showConfiguredHero = sectionConfigs
            .firstOrNull { it.section == HomeSection.HERO }
            ?.enabled
            ?: HomeSection.HERO.defaultEnabled
        val showOnNow = sectionConfigs
            .firstOrNull { it.section == HomeSection.ON_NOW }
            ?.enabled
            ?: HomeSection.ON_NOW.defaultEnabled
        val showOnNowRail = showOnNow && onNow.isNotEmpty() && onLiveChannelClick != null
        if (banner == null && !showOnNowRail && (!showConfiguredHero || heroOverlay == null)) {
            null
        } else {
            @Composable {
                if (banner != null) {
                    TvProviderHealthBanner(
                        banner = banner,
                        onClick = { onProviderBannerAction?.invoke() },
                    )
                }
                if (showConfiguredHero) heroOverlay?.invoke()
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
        onMediaFocused = { item ->
            if (!item.id.startsWith(TV_HOME_PROVIDER_ID_PREFIX)) {
                onMediaFocused?.invoke(item)
            }
        },
        onRailMediaClick = { railKey, item ->
            if (railKey != TV_HOME_BECAUSE_YOU_WATCHED_SEEDS_KEY) {
                false
            } else {
                val destination = item.tvMoreLikeRailKey()
                if (destination != null && onSeeAll != null) {
                    onSeeAll(destination, "$moreLikeThisTitle: ${item.title}")
                    true
                } else {
                    false
                }
            }
        },
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

internal fun buildTvHomeRails(
    state: HomeUiState,
    sectionConfigs: List<HomeSectionConfig>,
    customSections: List<CustomSection>,
    homeLayoutOrder: List<String>,
    enabledStreamingServiceIds: Set<Int> = emptySet(),
    providerLogos: Map<Int, String> = emptyMap(),
): List<TvContentRail> {
    val orderIndex = homeLayoutOrder.withIndex().associate { it.value to it.index }
    val addonShelfVisibility = state.addonShelfVisibility

    fun itemKey(item: TvHomeRenderItem): String = when (item) {
        is BuiltInHomeItem -> "section:${item.config.section.name}"
        is CustomHomeItem -> "custom:${item.section.id}"
        is AddonShelfHomeItem -> "addon:${item.shelf.id}"
    }

    val renderItems = buildList<TvHomeRenderItem> {
        val addonSectionEnabled = sectionConfigs
            .firstOrNull { it.section == HomeSection.ADDON_SHELVES }
            ?.enabled
            ?: HomeSection.ADDON_SHELVES.defaultEnabled
        sectionConfigs.filter { it.enabled }.forEach { add(BuiltInHomeItem(it)) }
        customSections.filter { it.enabled }.forEach { add(CustomHomeItem(it)) }
        state.addonShelves.takeIf { addonSectionEnabled }.orEmpty().forEachIndexed { index, shelf ->
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
                rails += buildBuiltInRails(
                    config = item.config,
                    state = state,
                    enabledStreamingServiceIds = enabledStreamingServiceIds,
                    providerLogos = providerLogos,
                )
            }
            is CustomHomeItem -> {
                val items = state.customShelves[item.section.id].orEmpty().tvHomeCardItems()
                if (items.isNotEmpty()) {
                    rails += TvContentRail(
                        key = "custom:${item.section.id}",
                        title = item.section.title,
                        items = items,
                    )
                }
            }
            is AddonShelfHomeItem -> {
                val items = item.shelf.items.tvHomeCardItems()
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

    // Explicitly enabled Home rails remain visible whenever they have at
    // least one unique item. The generic 20-item threshold is useful for
    // discovery pagination, but made Appearance toggles appear broken.
    return rails.dedupeAcrossRails(minItemsPerRail = 1)
}

private fun buildBuiltInRails(
    config: HomeSectionConfig,
    state: HomeUiState,
    enabledStreamingServiceIds: Set<Int>,
    providerLogos: Map<Int, String>,
): List<TvContentRail> {
    val title = config.customTitle ?: config.section.defaultTitle
    return when (config.section) {
        HomeSection.SEARCH_BAR,
        HomeSection.HERO,
        HomeSection.ON_NOW,
        HomeSection.ADDON_SHELVES -> {
            emptyList()
        }

        HomeSection.STREAMING_SERVICES -> {
            val items = ALL_STREAMING_SERVICES
                .sortedBy { service ->
                    // Saved services stay at the front for quick access, but
                    // the discovery rail must not silently hide the rest of
                    // Torve's supported providers.
                    if (service.tmdbProviderId in enabledStreamingServiceIds) 0 else 1
                }
                .map { service ->
                    MediaItem(
                        id = "$TV_HOME_PROVIDER_ID_PREFIX${service.tmdbProviderId}",
                        type = MediaType.MOVIE,
                        title = service.name,
                        posterUrl = providerLogos[service.tmdbProviderId],
                        backdropUrl = providerLogos[service.tmdbProviderId],
                    )
                }
            if (items.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    TvContentRail(
                        key = "streaming_services",
                        title = title,
                        items = items,
                        cardStyle = TvCardStyle.SERVICE,
                    ),
                )
            }
        }

        HomeSection.UPCOMING_SCHEDULE -> {
            val items = state.upcomingSchedule.tvHomeCardItems()
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "upcoming_schedule", title = title, items = items))
        }

        HomeSection.ACTORS -> {
            val items = state.popularActors.map { it.toTvHomePersonItem() }
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "popular_actors", title = title, items = items))
        }

        HomeSection.DIRECTORS -> {
            val items = state.popularDirectors.map { it.toTvHomePersonItem() }
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "popular_directors", title = title, items = items))
        }

        HomeSection.CONTINUE_WATCHING -> {
            val items = state.continueWatching
                .mapNotNull { it.toMediaItemOrNull() }
                .tvHomeCardItems(limit = 20)
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
                            .filter { it.isTvCatalogProgress() && it.progressPercent > 0f }
                            .associate { it.mediaId to it.progressPercent },
                    ),
                )
            }
        }

        HomeSection.WATCHLIST -> {
            val items = state.watchlistItems.tvHomeCardItems()
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "watchlist", title = title, items = items))
        }

        HomeSection.WATCHLIST_MOVIES -> {
            val items = state.watchlistItems
                .filter { it.type == MediaType.MOVIE }
                .tvHomeCardItems()
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "watchlist_movies", title = title, items = items))
        }

        HomeSection.WATCHLIST_TV -> {
            val items = state.watchlistItems
                .filter { it.type == MediaType.SERIES }
                .tvHomeCardItems()
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
            val items = shelf?.items?.tvHomeCardItems().orEmpty()
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
            val items = state.recommendedItems.map { it.item }.tvHomeCardItems()
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "recommended", title = title, items = items))
        }

        HomeSection.RECENTLY_WATCHED -> {
            val items = state.recentlyWatched.tvHomeCardItems()
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = "recently_watched", title = title, items = items))
        }

        HomeSection.HIDDEN_GEMS -> {
            val shelf = state.hiddenGemsShelf
            val items = shelf?.items?.tvHomeCardItems().orEmpty()
            if (items.isEmpty()) emptyList()
            else listOf(TvContentRail(key = shelf?.id ?: "hidden_gems", title = title, items = items))
        }

        HomeSection.BECAUSE_YOU_WATCHED -> {
            val watchedSeeds = state.recentlyWatched
                .asSequence()
                .filter { item -> item.tmdbId?.let { it > 0 } == true }
                .distinctBy { "${it.type}:${it.tmdbId}" }
                .toList()
                .tvHomeCardItems()
            if (watchedSeeds.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    TvContentRail(
                        key = TV_HOME_BECAUSE_YOU_WATCHED_SEEDS_KEY,
                        title = title,
                        items = watchedSeeds,
                        // Each watched poster is the entry point to its own
                        // recommendations page; a rail-level See All would
                        // incorrectly show the watch-history seeds themselves.
                        showSeeAllCard = false,
                        // Recently Watched is a separate, detail-oriented rail.
                        // Preserve these intentional duplicates because this rail
                        // has a different action and destination.
                        allowCrossRailDuplicates = true,
                    ),
                )
            }
        }

        HomeSection.MDBLIST_SHELVES -> {
            state.mdbListShelves.mapNotNull { shelf ->
                shelf.items.tvHomeCardItems()
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

private fun List<MediaItem>.tvHomeCardItems(limit: Int = 24): List<MediaItem> =
    asSequence()
        .filter { it.isTvHomeDisplayable() }
        .take(limit)
        .toList()

private fun MediaItem.isTvHomeDisplayable(): Boolean =
    !isContentPlaceholder &&
        !isStubDetail &&
        title.isNotBlank()

private const val TV_HOME_PERSON_ID_PREFIX = "person:"
private const val TV_HOME_PROVIDER_ID_PREFIX = "provider:"
internal const val TV_HOME_BECAUSE_YOU_WATCHED_SEEDS_KEY = "because_you_watched_seeds"

internal fun MediaItem.tvMoreLikeRailKey(): String? {
    val id = tmdbId?.takeIf { it > 0 } ?: return null
    val mediaType = if (type == MediaType.SERIES) "tv" else "movie"
    return "more_like_${mediaType}_$id"
}

private fun PersonSummary.toTvHomePersonItem(): MediaItem = MediaItem(
    id = "$TV_HOME_PERSON_ID_PREFIX$id",
    type = MediaType.MOVIE,
    title = name,
    overview = knownForDepartment,
    posterUrl = profileUrl,
    backdropUrl = profileUrl,
)
