package com.torve.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.torve.android.tv.components.rememberTvProviderNoticeVisible
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject

private data class TvHomeWatchingState(
    val personalDataUserId: String? = null,
    val upNext: List<com.torve.presentation.watching.WatchingEpisode> = emptyList(),
    val hideSpoilers: Boolean = false,
)

private data class TvHomeRailsInputs(
    val state: HomeUiState,
    val sectionConfigs: List<HomeSectionConfig>,
    val customSections: List<CustomSection>,
    val homeLayoutOrder: List<String>,
    val enabledStreamingServiceIds: Set<Int>,
    val providerLogos: Map<Int, String>,
    val outcomeState: TvHomeOutcomeUiState,
    val groupDefinitions: List<com.torve.domain.model.CatalogGroup>,
    val watchingState: TvHomeWatchingState,
    val tmdbCatalogueEnabled: Boolean,
    val showRemainingEpisodes: Boolean,
    val availableNowTitle: String,
    val downloadsOnDesktopTitle: String,
    val recentlyAddedTitle: String,
)

private data class TvHomeRailsSnapshot(
    val state: HomeUiState? = null,
    val rails: List<TvContentRail> = emptyList(),
)

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
    isActive: Boolean = true,
    onEpisodeClick: ((com.torve.presentation.watching.WatchingEpisode) -> Unit)? = null,
    onWebReplayClick: (String) -> Unit = {},
    onWebReplaySettingsClick: () -> Unit = {},
    onWebReplaysClick: () -> Unit = {},
) {
    val replayCatalogue: com.torve.data.replays.WebReplayCatalogue = koinInject()
    val replayState by replayCatalogue.state.collectAsState()
    val homeViewModel: HomeViewModel = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val services by androidx.compose.runtime.produceState<TvHomeServices?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            loadTvHomeServices()
        }
    }
    val emptyGroups = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.torve.domain.model.CatalogGroup>()) }
    val emptyWatching = remember { kotlinx.coroutines.flow.MutableStateFlow(com.torve.presentation.watching.WatchingHubState()) }
    val emptyOutcomes = remember { kotlinx.coroutines.flow.MutableStateFlow(TvHomeOutcomeUiState()) }
    val groupDefinitions by (services?.catalogGroups?.groups ?: emptyGroups).collectAsState()
    val watchingFlow = services?.watching?.state ?: emptyWatching
    val homeWatchingFlow = remember(watchingFlow) {
        watchingFlow.map { TvHomeWatchingState(it.personalDataUserId, it.upNext, it.hideSpoilers) }
            .distinctUntilChanged()
    }
    val rawWatchingState by homeWatchingFlow.collectAsState(TvHomeWatchingState())
    val ids: com.torve.data.auth.UserIdProvider = koinInject()
    val watchingState = rawWatchingState.takeIf { it.personalDataUserId == ids.currentDataUserId() }
        ?: TvHomeWatchingState()

    val state by homeViewModel.state.collectAsState()
    val sectionConfigs by homeViewModel.sectionConfigs.collectAsState()
    val customSections by homeViewModel.customSections.collectAsState()
    val homeLayoutOrder by homeViewModel.homeLayoutOrder.collectAsState()
    val enabledStreamingServiceIds by homeViewModel.enabledServiceIds.collectAsState()
    val providerLogos by homeViewModel.providerLogos.collectAsState()
    val outcomeState by (services?.outcomeViewModel?.state ?: emptyOutcomes).collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val onNowFocusRequester = remember { FocusRequester() }

    val focusMemory = rememberTvFocusMemory()
    val emptyMessage = state.error ?: stringResource(R.string.tv_no_data)
    val onNowTitle = stringResource(R.string.tv_home_on_now)
    val availableNowTitle = stringResource(R.string.tv_home_available_now)
    val downloadsOnDesktopTitle = stringResource(R.string.tv_home_downloads_on_desktop)
    val recentlyAddedTitle = stringResource(R.string.tv_home_recently_added_sources)

    // Building/deduplicating a large installed catalogue is independent of UI
    // rendering. Keep the last rails visible while a new snapshot is prepared.
    val railInputs = TvHomeRailsInputs(
        state,
        sectionConfigs,
        customSections,
        homeLayoutOrder,
        enabledStreamingServiceIds,
        providerLogos,
        outcomeState,
        groupDefinitions,
        watchingState,
        settingsState.tmdbCatalogueEnabled,
        settingsState.showRemainingEpisodes,
        availableNowTitle,
        downloadsOnDesktopTitle,
        recentlyAddedTitle,
    )
    val railSnapshot by androidx.compose.runtime.produceState(TvHomeRailsSnapshot(), railInputs) {
        val preparedRails = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // Read one immutable input snapshot, never Compose state from the worker.
            with(railInputs) {
                val outcomeRails = buildOutcomeRails(
                    outcome = outcomeState,
                    availableNowTitle = availableNowTitle,
                    downloadsOnDesktopTitle = downloadsOnDesktopTitle,
                    recentlyAddedTitle = recentlyAddedTitle,
                    ratingsByKey = state.continueWatchingRatings,
                )
                val configuredRails = buildTvHomeRails(
                    state = state,
                    sectionConfigs = sectionConfigs.filter { tmdbCatalogueEnabled || it.section !in com.torve.android.ui.community.tmdbHomeSections },
                    customSections = customSections.takeIf { tmdbCatalogueEnabled }.orEmpty(),
                    homeLayoutOrder = homeLayoutOrder,
                    enabledStreamingServiceIds = enabledStreamingServiceIds,
                    providerLogos = providerLogos,
                    catalogGroups = groupDefinitions,
                    upNext = watchingState.upNext,
                    hideSpoilers = watchingState.hideSpoilers,
                    showRemainingEpisodes = showRemainingEpisodes,
                )
                mergeTvHomePlaybackRails(configuredRails, outcomeRails)
            }
        }
        value = TvHomeRailsSnapshot(railInputs.state, preparedRails)
        if (com.torve.android.BuildConfig.STARTUP_METRICS) {
            com.torve.android.debug.StartupMetrics.record("STARTUP home_rails_ready rows=${preparedRails.size} cards=${preparedRails.sumOf { it.items.size }}")
        }
    }
    val rails = railSnapshot.rails
    val startupSnapshotReady = railSnapshot.state === state && !state.isLoading && sectionConfigs.isNotEmpty()
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.torve.android.TorveApp

    val replayRails = if (replayState.enabled && replayState.owner == ids.currentDataUserId() && replayState.events.isNotEmpty()) listOf(
        TvContentRail("web_replays", "Sports Replays", replayState.events.take(20).map { it.mediaItem() },
            cardStyle = TvCardStyle.BACKDROP)) else emptyList()

    LaunchedEffect(startupSnapshotReady, rails.isEmpty(), replayRails.isEmpty(), isActive) {
        // A resolved empty/setup page is also usable. It has no card-layout callback.
        if (isActive && startupSnapshotReady && rails.isEmpty() && replayRails.isEmpty()) {
            androidx.compose.runtime.withFrameNanos { }
            application?.onTvUiReady()
        }
    }

    if (!replayState.enabled && !settingsState.tmdbCatalogueEnabled && rails.isEmpty() && !state.isLoading &&
        sectionConfigs.isNotEmpty() && state.addonShelves.isEmpty() &&
        state.continueWatching.isEmpty() && state.watchlistItems.isEmpty()) {
        val entry = remember { FocusRequester() }
        LaunchedEffect(entry) { onFirstContentRequester(entry) }
        com.torve.android.ui.community.ExtensionCatalogueHome("all", onMediaClick, isTv = true,
            entryFocus = entry, railFocus = railFocusRequester, requestEntryFocus = shouldAutoFocus, onEntryFocused = onContentFocused,
            onFirstContentRequester = onFirstContentRequester)
        return
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
        if (item.id == "web-replay-settings") {
            onWebReplaySettingsClick()
        } else if (item.id.startsWith("web-replay:")) {
            onWebReplayClick(item.id)
        } else if (providerId != null) {
            onSeeAll?.invoke("provider_movie_$providerId", item.title)
        } else if (personId != null) {
            onSeeAll?.invoke("person_credits_$personId", item.title)
        } else if (services == null) {
            onMediaClick(item)
        } else {
            val readyServices = services!!
            coroutineScope.launch {
                val networkMode = readyServices.networkMonitor.currentNetworkType().toLanlibraryMode()
                val decision = readyServices.playbackRouter.resolve(
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
                            readyServices.lanLibraryConsumer.findLanRoute(
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

    val noticeVisible = rememberTvProviderNoticeVisible(outcomeState.providerBanner != null, isActive)
    val visibleProviderBanner = outcomeState.providerBanner.takeIf { noticeVisible }
    val composedHeroOverlay: (@Composable () -> Unit)? = remember(
        visibleProviderBanner,
        outcomeState.onNow,
        settingsState.optionalAppFeatures.channels,
        sectionConfigs,
        heroOverlay,
        onLiveChannelClick,
        onNowTitle,
        onNowFocusRequester,
        railFocusRequester,
        onContentFocused,
    ) {
        val banner = visibleProviderBanner
        val onNow = outcomeState.onNow
        val showConfiguredHero = sectionConfigs
            .firstOrNull { it.section == HomeSection.HERO }
            ?.enabled
            ?: HomeSection.HERO.defaultEnabled
        val showOnNow = sectionConfigs
            .firstOrNull { it.section == HomeSection.ON_NOW }
            ?.enabled
            ?: HomeSection.ON_NOW.defaultEnabled
        val showOnNowRail = settingsState.optionalAppFeatures.channels && showOnNow && onNow.isNotEmpty() && onLiveChannelClick != null
        if (banner == null && !showOnNowRail && (!showConfiguredHero || heroOverlay == null)) {
            null
        } else {
            @Composable {
                if (banner != null) {
                    TvProviderHealthBanner(banner = banner)
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
        rails = replayRails + rails,
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
            if (railKey == "up_next" && onEpisodeClick != null) {
                watchingState.upNext.firstOrNull { it.show.id == item.id }?.let(onEpisodeClick)
                true
            } else {
            val watchedSourceRail = state.becauseYouWatched.firstOrNull { shelf ->
                shelf.id == railKey && shelf.items.any { it.hasSameTvRecommendationIdentity(item) }
            }
            if (watchedSourceRail == null) {
                false
            } else {
                val destination = item.tvMoreLikeRailKey()
                if (destination != null) {
                    onSeeAll?.invoke(
                        destination,
                        "$TV_HOME_BECAUSE_YOU_WATCHED_COLLECTION_PREFIX ${item.title}",
                    )
                }
                // Never fall through to the normal detail-page click path for
                // posters in these watched-source rails.
                true
            }
            }
        },
        onSeeAll = { key, title -> if (key == "web_replays") onWebReplaysClick() else onSeeAll?.invoke(key, title) },
        heroOverlay = composedHeroOverlay,
        presentationMode = TvRailsPresentationMode.CatalogHero,
        focusExclusive = true,
        heroOverlayFocusRequester = onNowFocusRequester.takeIf {
            settingsState.optionalAppFeatures.channels && outcomeState.onNow.isNotEmpty() &&
                onLiveChannelClick != null &&
                (
                    sectionConfigs.firstOrNull { config -> config.section == HomeSection.ON_NOW }?.enabled
                        ?: HomeSection.ON_NOW.defaultEnabled
                    )
        },
        shouldAutoFocus = shouldAutoFocus,
        initialFocusFollowsTop = isActive,
        onContentLaidOut = { if (isActive && startupSnapshotReady) application?.onTvUiReady() },
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
    catalogGroups: List<com.torve.domain.model.CatalogGroup> = emptyList(),
    upNext: List<com.torve.presentation.watching.WatchingEpisode> = emptyList(),
    hideSpoilers: Boolean = false,
    showRemainingEpisodes: Boolean = true,
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
    val sources = com.torve.presentation.home.catalogGroupSources(state, customSections)
    val groupRails = catalogGroups.filter { it.pinned }.mapNotNull { group ->
        val items = com.torve.domain.model.mergeCatalogGroup(group, sources)
        if (items.isNotEmpty()) TvContentRail("group:${group.id}", group.name, items,
            cardStyle = if (group.presentation == com.torve.domain.model.CatalogGroupPresentation.LANDSCAPE) TvCardStyle.BACKDROP else TvCardStyle.POSTER,
            showSeeAllCard = false, allowCrossRailDuplicates = true) else null
    }
    renderItems.forEach { item ->
        when (item) {
            is BuiltInHomeItem -> {
                if (item.config.section == HomeSection.UP_NEXT) {
                    if (upNext.isNotEmpty()) rails += TvContentRail("up_next", "Up Next", upNext.map { next ->
                        next.show.copy(title = "S${next.season} E${next.episode.episodeNumber} · ${next.show.title}",
                            overview = "${if (hideSpoilers && !next.watched) "Episode ${next.episode.episodeNumber}" else next.episode.name} · ${next.remainingAired} aired episodes left. ${next.show.overview.orEmpty()}")
                    }, cardStyle = TvCardStyle.BACKDROP, allowCrossRailDuplicates = true,
                        remainingEpisodesByMediaId = if (showRemainingEpisodes) upNext.filter { it.remainingAired > 0 }
                            .associate { it.show.id to it.remainingAired } else emptyMap())
                } else {
                rails += buildBuiltInRails(
                    config = item.config,
                    state = state,
                    enabledStreamingServiceIds = enabledStreamingServiceIds,
                    providerLogos = providerLogos,
                )
                }
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
    val groupPosition = rails.indexOfLast { it.key.startsWith("continue_watching") || it.key == "up_next" }
    rails.addAll((groupPosition + 1).coerceAtLeast(0), groupRails)
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
        HomeSection.UP_NEXT,
        HomeSection.ON_NOW,
        HomeSection.ADDON_SHELVES,
        HomeSection.BECAUSE_YOU_WATCHED -> {
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
            else listOf(TvContentRail(key = "upcoming_schedule", title = title, items = items, allowCrossRailDuplicates = true))
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

        HomeSection.BECAUSE_YOU_WATCHED_MOVIES,
        HomeSection.BECAUSE_YOU_WATCHED_TV -> {
            val shelf = state.becauseYouWatched.firstOrNull { it.id == config.section.shelfId }
            val watchedSources = shelf?.items?.tvHomeCardItems().orEmpty()
            if (shelf == null || watchedSources.isEmpty()) emptyList()
            else listOf(
                TvContentRail(
                    key = shelf.id,
                    title = config.customTitle ?: shelf.title,
                    items = watchedSources,
                    showSeeAllCard = false,
                    // Watched posters intentionally also appear in Recently
                    // Watched, but every poster here opens recommendations.
                    allowCrossRailDuplicates = true,
                ),
            )
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
internal const val TV_HOME_BECAUSE_YOU_WATCHED_COLLECTION_PREFIX = "Because You Watched"

internal fun MediaItem.tvMoreLikeRailKey(): String? {
    val id = tmdbId?.takeIf { it > 0 } ?: return null
    val mediaType = if (type == MediaType.SERIES) "tv" else "movie"
    return "more_like_${mediaType}_$id"
}

private fun MediaItem.hasSameTvRecommendationIdentity(other: MediaItem): Boolean {
    val canonicalTmdbId = tmdbId?.takeIf { it > 0 }
    return if (canonicalTmdbId != null && other.tmdbId != null) {
        canonicalTmdbId == other.tmdbId && type == other.type
    } else {
        id == other.id && type == other.type
    }
}

private fun PersonSummary.toTvHomePersonItem(): MediaItem = MediaItem(
    id = "$TV_HOME_PERSON_ID_PREFIX$id",
    type = MediaType.MOVIE,
    title = name,
    overview = knownForDepartment,
    posterUrl = profileUrl,
    backdropUrl = profileUrl,
)
