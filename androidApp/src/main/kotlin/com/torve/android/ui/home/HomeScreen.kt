package com.torve.android.ui.home

import com.torve.android.ui.community.parityLabel as localizedUiText

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import com.torve.presentation.home.latestHomeResume
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import com.torve.android.ui.components.rememberMobileCurrentRatings
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import com.torve.android.ui.components.MobileGlassButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import com.torve.android.ui.components.MobileGlassIconButton as IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.torve.android.R
import com.torve.android.premium.AccessTier
import com.torve.android.premium.PremiumAccess
import com.torve.android.premium.PremiumFeature
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.components.SectionHeader
import com.torve.android.ui.components.ShimmerBox
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.LocalRatingPrefs
import com.torve.android.ui.components.MultiRatingPills
import com.torve.android.ui.image.MobileImagePrefetcher
import com.torve.android.ui.subscription.NeedsVerificationBanner
import com.torve.android.ui.subscription.NeedsVerificationToastEffect
import com.torve.domain.model.MediaRatings
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.HeroGradient
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.CustomSection
import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.MediaItem
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.MediaType
import com.torve.domain.model.allowsTmdbRatingProvider
import com.torve.domain.model.ShelfType
import com.torve.domain.model.WatchProgress
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.model.matchesSection
import com.torve.domain.model.resolveCardStyle
import com.torve.domain.model.resolvedWidthDp
import com.torve.domain.recommendation.ScoredMediaItem
import com.torve.data.auth.AuthClient
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.seeall.SeeAllViewModel
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

private sealed interface HomeRenderItem { val order: Int }
private data class BuiltInItem(val config: HomeSectionConfig) : HomeRenderItem { override val order = config.order }
private data class CustomItem(val section: CustomSection) : HomeRenderItem { override val order = section.order }
private data class AddonShelfItem(val shelf: CatalogShelf, override val order: Int) : HomeRenderItem
private data class GroupItem(val group: com.torve.domain.model.CatalogGroup, override val order: Int) : HomeRenderItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onMediaClick: (MediaItem) -> Unit,
    onWebReplaysClick: () -> Unit = {},
    onWebReplaySettingsClick: () -> Unit = {},
    onWebReplayClick: (String) -> Unit = {},
    onContinueWatchingClick: (WatchProgress) -> Unit = {},
    onSeeAllClick: (String) -> Unit = {},
    onProviderClick: (providerId: Int, providerName: String) -> Unit = { _, _ -> },
    onPersonClick: (Int) -> Unit = {},
    onChannelsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onExtensionsClick: () -> Unit = onSettingsClick,
    onProfilesClick: () -> Unit = {},
    onRepairClick: () -> Unit = onSettingsClick,
    onYourShowsClick: () -> Unit = {},
    onEpisodeClick: (com.torve.presentation.watching.WatchingEpisode) -> Unit = {},
    accessTier: AccessTier = AccessTier.FREE,
    onLockedFeatureClick: (PremiumFeature) -> Unit = {},
    mediaType: String = "all",
    showHeader: Boolean = true,
    isActive: Boolean = true,
    resetScrollToTop: Boolean = false,
    onScrollReset: () -> Unit = {},
    viewModel: HomeViewModel = koinInject(),
    watchlistViewModel: WatchlistViewModel = koinInject(),
    settingsViewModel: com.torve.presentation.settings.SettingsViewModel = koinInject(),
    authClient: AuthClient = koinInject(),
    subscriptionViewModel: SubscriptionViewModel = koinInject(),
    catalogGroups: com.torve.data.catalog.CatalogGroupRepository = koinInject(),
    profiles: com.torve.presentation.profile.ProfileViewModel = koinInject(),
    providerHealth: com.torve.presentation.providerhealth.ProviderHealthCoordinator = koinInject(),
    watching: com.torve.presentation.watching.WatchingHubViewModel = koinInject(),
) {
    val replayCatalogue: com.torve.data.replays.WebReplayCatalogue = koinInject()
    val replayState by replayCatalogue.state.collectAsState()
    val state by viewModel.state.collectAsState()
    val rawWatchingState by watching.state.collectAsState()
    val ids: com.torve.data.auth.UserIdProvider = koinInject()
    val watchingState = rawWatchingState.takeIf { it.personalDataUserId == ids.currentDataUserId() }
        ?: com.torve.presentation.watching.WatchingHubState()
    val settingsState by settingsViewModel.state.collectAsState()
    var recommendationFeedback by remember(settingsState.personalDataUserId) { mutableStateOf<MediaItem?>(null) }
    recommendationFeedback?.let { item ->
        RecommendationFeedbackSheet(item, { recommendationFeedback = null }, {
            viewModel.dismissRecommendation(item); recommendationFeedback = null
        }, { viewModel.resetRecommendationFeedback(); recommendationFeedback = null })
    }
    val watchlistState by watchlistViewModel.state.collectAsState()
    val authUser by authClient.authUserFlow.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val profileState by profiles.state.collectAsState()
    val providerEntries by providerHealth.entries.collectAsState()
    val needsAttention = providerEntries.any { it.status == com.torve.domain.providerhealth.ProviderHealthStatus.RED ||
        it.status == com.torve.domain.providerhealth.ProviderHealthStatus.YELLOW }
    LaunchedEffect(settingsState.personalDataUserId) { profiles.loadProfiles() }
    val isSignedIn = authUser != null
    val sectionConfigs by viewModel.sectionConfigs.collectAsState()
    val enabledServiceIds by viewModel.enabledServiceIds.collectAsState()
    val customSections by viewModel.customSections.collectAsState()
    val homeLayoutOrder by viewModel.homeLayoutOrder.collectAsState()
    val addonShelfVisibility by viewModel.addonShelfVisibility.collectAsState()
    val returningViewerHome by viewModel.returningViewerHome.collectAsState()
    val showPhoneResumeBar by viewModel.showPhoneResumeBar.collectAsState()
    val groupDefinitions by catalogGroups.groups.collectAsState()
    val groupSources = com.torve.presentation.home.catalogGroupSources(state, customSections)
    LaunchedEffect(settingsState.personalDataUserId, isActive) { if (isActive) catalogGroups.refresh() }
    val mdblistApiKey = settingsState.mdblistApiKey
    val activeStreamingServices = remember(enabledServiceIds) {
        ALL_STREAMING_SERVICES.filter { it.tmdbProviderId in enabledServiceIds }
    }
    val providerLogos by viewModel.providerLogos.collectAsState()
    val watchlistLocked = remember(accessTier) {
        PremiumAccess.isPremiumLocked(PremiumFeature.WATCHLIST_EDIT, accessTier)
    }
    val homeListState = rememberLazyListState()
    val context = LocalContext.current
    // A saved LazyColumn anchor follows the old first row when the editor moves it.
    // Return to the beginning only after an actual reorder, not on ordinary tab/detail returns.
    val layoutPositionKey = homeLayoutOrder.joinToString("\n")
    var lastAppliedLayoutOrder by rememberSaveable { mutableStateOf(layoutPositionKey) }
    LaunchedEffect(layoutPositionKey) {
        if (layoutPositionKey != lastAppliedLayoutOrder) {
            lastAppliedLayoutOrder = layoutPositionKey
            homeListState.scrollToItem(0)
        }
    }

    // Snapshot content can arrive before the persisted section layout. Waiting
    // for both avoids LazyColumn key anchoring keeping a later shelf at the top
    // when Hero and earlier sections are inserted during startup.
    LaunchedEffect(
        resetScrollToTop,
        state.isLoading,
        sectionConfigs,
        homeLayoutOrder,
    ) {
        if (
            resetScrollToTop &&
            !state.isLoading &&
            sectionConfigs.isNotEmpty() &&
            homeLayoutOrder.isNotEmpty()
        ) {
            homeListState.scrollToItem(0)
            onScrollReset()
        }
    }

    // Filter by media type for TV Shows / Movies tab reuse
    val filteredShelves = remember(state.shelves, mediaType) {
        if (mediaType == "all") state.shelves
        else state.shelves.map { shelf ->
            shelf.copy(items = shelf.items.filter { item ->
                when (mediaType) {
                    "tv" -> item.type == MediaType.SERIES
                    "movie" -> item.type == MediaType.MOVIE
                    else -> true
                }
            })
        }.filter { it.items.isNotEmpty() }
    }
    LaunchedEffect(filteredShelves, state.popularActors, state.popularDirectors, isActive) {
        if (isActive) {
            MobileImagePrefetcher.prefetchHome(
                context = context,
                shelves = filteredShelves,
                actors = state.popularActors,
                directors = state.popularDirectors,
            )
        }
    }
    val filteredContinueWatching = remember(state.continueWatching, mediaType) {
        if (mediaType == "all") state.continueWatching
        else state.continueWatching.filter { progress ->
            when (mediaType) {
                "tv" -> progress.mediaType == MediaType.SERIES
                "movie" -> progress.mediaType == MediaType.MOVIE
                else -> true
            }
        }
    }
    val filteredUpcomingSchedule = remember(state.upcomingSchedule, mediaType) {
        when (mediaType) {
            "all", "tv" -> state.upcomingSchedule
            else -> emptyList()
        }
    }
    val filteredWatchlist = remember(state.watchlistItems, mediaType) {
        if (mediaType == "all") state.watchlistItems
        else state.watchlistItems.filter { item ->
            when (mediaType) {
                "tv" -> item.type == MediaType.SERIES
                "movie" -> item.type == MediaType.MOVIE
                else -> true
            }
        }
    }
    val watchlistMovies = remember(state.watchlistItems) {
        state.watchlistItems.filter { it.type == MediaType.MOVIE }
    }
    val watchlistTv = remember(state.watchlistItems) {
        state.watchlistItems.filter { it.type == MediaType.SERIES }
    }
    val filteredRecommendations = remember(state.recommendedItems, mediaType) {
        if (mediaType == "all") state.recommendedItems
        else state.recommendedItems.filter { scored ->
            when (mediaType) {
                "tv" -> scored.item.type == MediaType.SERIES
                "movie" -> scored.item.type == MediaType.MOVIE
                else -> true
            }
        }
    }

    // Tabs leave composition while hidden. Returning must not restart rating requests.
    val ratingsRefreshKey = remember(mdblistApiKey, settingsState.personalDataUserId) {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("${settingsState.personalDataUserId}:$mdblistApiKey".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    var lastRatingsRefreshKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(ratingsRefreshKey) {
        if (lastRatingsRefreshKey != ratingsRefreshKey) {
            lastRatingsRefreshKey = ratingsRefreshKey
            viewModel.refreshRatings(mdblistApiKey)
        }
    }

    var lastAccessRefreshUser by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(authUser?.id) {
        if (authUser?.id != lastAccessRefreshUser) {
            lastAccessRefreshUser = authUser?.id
            if (authUser != null) subscriptionViewModel.refreshAccess()
        }
    }

    NeedsVerificationToastEffect(
        message = subscriptionState.verificationEmailMessage,
        onConsumed = subscriptionViewModel::consumeVerificationEmailMessage,
    )

    // Reload home data when Trakt sync completes (new watchlist, history, etc.)
    val traktLastSync = settingsState.traktLastSyncTime
    var lastHandledTraktSync by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(traktLastSync, settingsState.personalDataUserId) {
        val syncKey = "${settingsState.personalDataUserId}:$traktLastSync"
        if (lastHandledTraktSync != syncKey) {
            lastHandledTraktSync = syncKey
            if (traktLastSync != null && !state.isLoading) viewModel.refresh()
        }
    }

    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )
    val showSearchBar = remember(sectionConfigs) {
        showHeader && sectionConfigs.any { it.section == HomeSection.SEARCH_BAR && it.enabled }
    }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    fun closeHomeSearch() {
        viewModel.clearSearch()
        searchExpanded = false
        focusManager.clearFocus()
    }
    BackHandler(enabled = searchExpanded || state.searchQuery.isNotEmpty()) { closeHomeSearch() }
    val quickResume = if (isSignedIn && sectionConfigs.any { it.section == HomeSection.CONTINUE_WATCHING && it.enabled }) {
        latestHomeResume(filteredContinueWatching)
    } else null
    val quickNext = if (quickResume == null && mediaType != "movie" && sectionConfigs.any { it.section == HomeSection.UP_NEXT && it.enabled }) {
        watchingState.upNext.firstOrNull()
    } else null
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
        LocalRatingPrefs provides settingsState.ratingPrefs,
    ) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (showHeader) MobileHomeQuickAccessHeader(onChannelsClick.takeIf { settingsState.optionalAppFeaturesLoaded && settingsState.optionalAppFeatures.channels }, onSettingsClick,
            profileState.activeProfile?.name ?: "Default", onProfilesClick, needsAttention,
            onSearchClick = if (showSearchBar) ({ searchExpanded = !searchExpanded; if (!searchExpanded) closeHomeSearch() }) else null)
        if (showSearchBar && (searchExpanded || state.searchQuery.isNotEmpty())) {
            Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                HomeSearchBar(searchQuery = state.searchQuery, onQueryChange = viewModel::updateSearchQuery,
                    onClearSearch = viewModel::clearSearch, onClose = ::closeHomeSearch)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
        when {
            state.isLoading && !replayState.enabled -> {
                HomeSkeletonLoader()
            }

            state.error != null && !replayState.enabled -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.home_something_went_wrong),
                        style = MaterialTheme.typography.titleLarge,
                        color = Torve.colors.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    state.error?.let { message ->
                        Text(
                            text = localizedUiText(message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Torve.colors.textTertiary,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    FilledTonalButton(onClick = { viewModel.refresh() }) {
                        Text(stringResource(R.string.home_try_again))
                    }
                }
            }

            else -> {
                // Render sections (built-in + custom) in user-defined order
                val enabledItems = remember(sectionConfigs, customSections, homeLayoutOrder, state.addonShelves, addonShelfVisibility, groupDefinitions, settingsState.tmdbCatalogueEnabled) {
                    val orderIndex = homeLayoutOrder.withIndex().associate { it.value to it.index }
                    fun itemKey(item: HomeRenderItem): String = when (item) {
                        is BuiltInItem -> "section:${item.config.section.name}"
                        is CustomItem -> "custom:${item.section.id}"
                        is AddonShelfItem -> "addon:${item.shelf.id}"
                        is GroupItem -> "group:${item.group.id}"
                    }
                    buildList<HomeRenderItem> {
                        sectionConfigs.filter { it.enabled && (settingsState.tmdbCatalogueEnabled || it.section !in com.torve.android.ui.community.tmdbHomeSections) }.forEach { add(BuiltInItem(it)) }
                        customSections.filter { it.enabled && settingsState.tmdbCatalogueEnabled }.forEach { add(CustomItem(it)) }
                        groupDefinitions.filter { it.pinned }.forEachIndexed { index, group -> add(GroupItem(group, index)) }
                        // Add individual addon shelves that are visible
                        state.addonShelves.forEachIndexed { idx, shelf ->
                            val visible = addonShelfVisibility[shelf.id] ?: true
                            if (visible) {
                                val addonOrder = orderIndex["addon:${shelf.id}"] ?: (10_000 + 100 + idx)
                                add(AddonShelfItem(shelf, addonOrder))
                            }
                        }
                    }.sortedWith(
                        compareBy<HomeRenderItem> { item ->
                            if (item is GroupItem) (orderIndex["section:CONTINUE_WATCHING"] ?: orderIndex["section:HERO"] ?: 0).toDouble() + .5 + item.order * .001
                            else (orderIndex[itemKey(item)] ?: (10_000 + item.order)).toDouble()
                        }.thenBy { it.order },
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = homeListState,
                    verticalArrangement = Arrangement.spacedBy((settingsState.railSpacingDp ?: 0).dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (mediaType == "all" && replayState.enabled) item(key = "web_replays") {
                        com.torve.android.ui.replays.WebReplayRail(onWebReplayClick, onWebReplaysClick, onWebReplaySettingsClick)
                    }
                    if (!settingsState.tmdbCatalogueEnabled && !replayState.enabled) item(key = "extensions_setup") {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text(localizedUiText("Your catalogues"), style = MaterialTheme.typography.titleLarge)
                            Text(localizedUiText("Add an extension or choose the optional TMDB catalogue."), color = Torve.colors.textSecondary)
                            TextButton(onClick = onExtensionsClick) { Text(localizedUiText("Manage extensions")) }
                        }
                    }
                    if (showPhoneResumeBar && (quickResume != null || quickNext != null)) {
                        item(key = "playback_shortcut") {
                            MobilePlaybackShortcut(quickResume, quickNext,
                                onResume = onContinueWatchingClick, onEpisode = onEpisodeClick,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    if (subscriptionState.needsVerification) {
                        item(key = "needs_verification") {
                            NeedsVerificationBanner(
                                isSending = subscriptionState.isSendingVerificationEmail,
                                onSendVerificationEmail = subscriptionViewModel::sendVerificationEmail,
                                onRefreshAccess = subscriptionViewModel::refreshAccess,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                    enabledItems.forEach { item ->
                        when (item) {
                            is BuiltInItem -> {
                                val config = item.config
                                val sectionStyle = resolveCardStyle(
                                    presets = settingsState.cardStylePresets,
                                    presetId = config.presetId,
                                    globalDefaultPresetId = settingsState.globalDefaultPresetId,
                                )
                                when (config.section) {
                            HomeSection.HERO -> {
                                item(key = "hero") {
                                    val heroItems = filteredShelves
                                        .firstOrNull()?.items?.take(5) ?: emptyList()
                                    if (heroItems.isNotEmpty()) {
                                        HeroPager(
                                            items = heroItems,
                                            onItemClick = onMediaClick,
                                            watchlistIds = watchlistState.watchlistIds,
                                            isWatchlistLocked = watchlistLocked,
                                            onWatchlistClick = { media ->
                                                if (watchlistLocked) {
                                                    onLockedFeatureClick(PremiumFeature.WATCHLIST_EDIT)
                                                } else {
                                                    watchlistViewModel.toggleWatchlist(media)
                                                }
                                            },
                                        )
                                    } else {
                                        state.heroItem?.let { hero ->
                                            SingleHeroBanner(
                                                item = hero,
                                                onClick = { onMediaClick(hero) },
                                            )
                                        }
                                    }
                                }
                            }

                            HomeSection.CONTINUE_WATCHING -> if (isSignedIn) {
                                if (filteredContinueWatching.isNotEmpty()) {
                                    item(key = "continue_watching") {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            LocalCardStyle provides sectionStyle,
                                        ) {
                                            val title = config.customTitle ?: stringResource(R.string.home_continue_watching)
                                            SectionHeader(
                                                title = title,
                                                action = stringResource(R.string.home_see_all),
                                                onActionClick = {
                                                    if (mediaType == "all") {
                                                        onSeeAllClick("continue_watching")
                                                    } else {
                                                        val key = "continue_watching_$mediaType"
                                                        SeeAllViewModel.pendingItems[key] =
                                                            title to filteredContinueWatching.map { it.toSeeAllMediaItem() }
                                                        onSeeAllClick("shelf:$key")
                                                    }
                                                },
                                            )
                                            val watchingRowState = rememberLazyListState()
                                            val refreshIndex by remember {
                                                androidx.compose.runtime.derivedStateOf {
                                                    if (watchingRowState.isScrollInProgress || homeListState.isScrollInProgress ||
                                                        homeListState.layoutInfo.visibleItemsInfo.none { it.key == "continue_watching" }) -1
                                                    else watchingRowState.firstVisibleItemIndex
                                                }
                                            }
                                            LazyRow(
                                                state = watchingRowState,
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                itemsIndexed(filteredContinueWatching, key = { _, it -> it.mediaId }) { index, progress ->
                                                    ContinueWatchingCard(
                                                        progress = progress,
                                                        onClick = { onContinueWatchingClick(progress) },
                                                        ratings = state.continueWatchingRatings[progress.mediaId],
                                                        refreshRating = isActive && index == refreshIndex,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else if (mediaType == "all" && !state.isLoading) {
                                    item(key = "continue_watching_empty") {
                                        SectionHeader(
                                            title = config.customTitle ?: stringResource(R.string.home_continue_watching),
                                        )
                                        EmptySectionHint(
                                            text = stringResource(R.string.home_continue_watching_empty),
                                            icon = Icons.Rounded.BookmarkBorder,
                                        )
                                    }
                                }
                            }

                            HomeSection.UPCOMING_SCHEDULE -> if (isSignedIn && filteredUpcomingSchedule.isNotEmpty()) {
                                item(key = "upcoming_schedule") {
                                    androidx.compose.runtime.CompositionLocalProvider(
                                        LocalCardStyle provides sectionStyle,
                                    ) {
                                        SectionHeader(
                                            title = localizedUiText(config.customTitle ?: "Upcoming Schedule"),
                                            action = stringResource(R.string.home_see_all),
                                            onActionClick = {
                                                SeeAllViewModel.pendingItems["upcoming_schedule"] =
                                                    (config.customTitle ?: "Upcoming Schedule") to filteredUpcomingSchedule
                                                onSeeAllClick("upcoming_schedule")
                                            },
                                        )
                                        LazyRow(
                                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            items(
                                                filteredUpcomingSchedule.size,
                                                key = { index -> "upcoming_${filteredUpcomingSchedule[index].id}_$index" },
                                            ) { index ->
                                                PosterCard(
                                                    item = filteredUpcomingSchedule[index],
                                                    onClick = { onMediaClick(filteredUpcomingSchedule[index]) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            HomeSection.WATCHLIST -> if (isSignedIn) {
                                item(key = "watchlist") {
                                    androidx.compose.runtime.CompositionLocalProvider(
                                        LocalCardStyle provides sectionStyle,
                                    ) {
                                        val title = config.customTitle ?: "My Watchlist"
                                        SectionHeader(
                                            title = title,
                                            action = stringResource(R.string.home_see_all),
                                            onActionClick = {
                                                if (mediaType == "all") {
                                                    onSeeAllClick("watchlist")
                                                } else {
                                                    val key = "watchlist_$mediaType"
                                                    SeeAllViewModel.pendingItems[key] = title to filteredWatchlist
                                                    onSeeAllClick("shelf:$key")
                                                }
                                            },
                                        )
                                        if (filteredWatchlist.isNotEmpty()) {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                items(
                                                    filteredWatchlist.size,
                                                    key = { index -> "wl_${filteredWatchlist[index].id}_$index" },
                                                ) { index ->
                                                    PosterCard(
                                                        item = filteredWatchlist[index],
                                                        onClick = { onMediaClick(filteredWatchlist[index]) },
                                                    )
                                                }
                                            }
                                        } else {
                                            EmptySectionHint(
                                                text = localizedUiText("Add movies and shows to your watchlist"),
                                                icon = Icons.Rounded.BookmarkBorder,
                                            )
                                        }
                                    }
                                }
                            }

                            HomeSection.WATCHLIST_MOVIES -> if (isSignedIn) {
                                if (watchlistMovies.isNotEmpty()) {
                                    item(key = "watchlist_movies") {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            LocalCardStyle provides sectionStyle,
                                        ) {
                                            SectionHeader(
                                                title = localizedUiText(config.customTitle ?: "Watchlist — Movies"),
                                                action = stringResource(R.string.home_see_all),
                                                onActionClick = {
                                                    val key = "watchlist_movies"
                                                    SeeAllViewModel.pendingItems[key] =
                                                        (config.customTitle ?: "Watchlist - Movies") to watchlistMovies
                                                    onSeeAllClick("shelf:$key")
                                                },
                                            )
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                items(
                                                    watchlistMovies.size,
                                                    key = { index -> "wlm_${watchlistMovies[index].id}_$index" },
                                                ) { index ->
                                                    PosterCard(
                                                        item = watchlistMovies[index],
                                                        onClick = { onMediaClick(watchlistMovies[index]) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            HomeSection.WATCHLIST_TV -> if (isSignedIn) {
                                if (watchlistTv.isNotEmpty()) {
                                    item(key = "watchlist_tv") {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            LocalCardStyle provides sectionStyle,
                                        ) {
                                            SectionHeader(
                                                title = localizedUiText(config.customTitle ?: "Watchlist — TV Shows"),
                                                action = stringResource(R.string.home_see_all),
                                                onActionClick = {
                                                    val key = "watchlist_tv"
                                                    SeeAllViewModel.pendingItems[key] =
                                                        (config.customTitle ?: "Watchlist - TV Shows") to watchlistTv
                                                    onSeeAllClick("shelf:$key")
                                                },
                                            )
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                items(
                                                    watchlistTv.size,
                                                    key = { index -> "wlt_${watchlistTv[index].id}_$index" },
                                                ) { index ->
                                                    PosterCard(
                                                        item = watchlistTv[index],
                                                        onClick = { onMediaClick(watchlistTv[index]) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            HomeSection.RECOMMENDED -> {
                                if (filteredRecommendations.isNotEmpty()) {
                                    item(key = "recommended") {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            LocalCardStyle provides sectionStyle,
                                        ) {
                                            Spacer(Modifier.height(8.dp))
                                            SectionHeader(
                                                title = config.customTitle ?: stringResource(R.string.home_recommended),
                                                action = stringResource(R.string.home_see_all),
                                                onActionClick = {
                                                    val title = config.customTitle ?: "Recommended"
                                                    val key = "recommended_$mediaType"
                                                    SeeAllViewModel.pendingItems[key] =
                                                        title to filteredRecommendations.map { it.item }
                                                    onSeeAllClick("shelf:$key")
                                                },
                                            )
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                items(
                                                    filteredRecommendations.size,
                                                    key = { index -> "rec_${filteredRecommendations[index].item.id}_$index" },
                                                ) { index ->
                                                    RecommendationCard(
                                                        scored = filteredRecommendations[index],
                                                        onClick = { onMediaClick(filteredRecommendations[index].item) },
                                                        onFeedback = { recommendationFeedback = filteredRecommendations[index].item },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // All catalog shelf sections
                            HomeSection.TRENDING_MOVIES,
                            HomeSection.TRENDING_TV,
                            HomeSection.POPULAR_MOVIES,
                            HomeSection.NOW_PLAYING,
                            HomeSection.NEW_RELEASES,
                            HomeSection.TOP_RATED -> {
                                val shelf = filteredShelves.find { it.matchesSection(config.section) }
                                if (shelf != null && shelf.items.isNotEmpty()) {
                                    item(key = config.section.name) {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            LocalCardStyle provides sectionStyle,
                                        ) {
                                            Spacer(Modifier.height(8.dp))
                                            CatalogShelf(
                                                title = config.customTitle ?: shelf.title,
                                                items = shelf.items,
                                                shelfType = shelf.type,
                                                onItemClick = onMediaClick,
                                                onSeeAll = { onSeeAllClick(config.section.name) },
                                            )
                                        }
                                    }
                                }
                            }

                            HomeSection.STREAMING_SERVICES -> {
                                if (activeStreamingServices.isNotEmpty()) {
                                    item(key = "streaming_services") {
                                        StreamingServicesRow(
                                            services = activeStreamingServices,
                                            onProviderClick = onProviderClick,
                                        )
                                    }
                                }
                            }

                            HomeSection.RECENTLY_WATCHED -> {
                                if (state.recentlyWatched.isNotEmpty()) {
                                    item(key = "recently_watched") {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            LocalCardStyle provides sectionStyle,
                                        ) {
                                            SectionHeader(
                                                title = localizedUiText(config.customTitle ?: "Recently Watched"),
                                                action = stringResource(R.string.home_see_all),
                                                onActionClick = { onSeeAllClick("recently_watched") },
                                            )
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                items(
                                                    state.recentlyWatched.size,
                                                    key = { index -> "rw_${state.recentlyWatched[index].id}_$index" },
                                                ) { index ->
                                                    PosterCard(
                                                        item = state.recentlyWatched[index],
                                                        onClick = { onMediaClick(state.recentlyWatched[index]) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            HomeSection.ACTORS -> {
                                if (state.popularActors.isNotEmpty()) {
                                    item(key = "actors") {
                                        SectionHeader(
                                            title = localizedUiText(config.customTitle ?: "Popular Actors"),
                                        )
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        ) {
                                            items(state.popularActors, key = { it.id }) { person ->
                                                PersonAvatarCard(
                                                    person = person,
                                                    onClick = { onPersonClick(person.id) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            HomeSection.DIRECTORS -> {
                                // Directors section removed by request.
                            }

                            HomeSection.HIDDEN_GEMS -> {
                                state.hiddenGemsShelf?.let { gems ->
                                    if (gems.items.isNotEmpty()) {
                                        item(key = "hidden_gems") {
                                            androidx.compose.runtime.CompositionLocalProvider(
                                                LocalCardStyle provides sectionStyle,
                                            ) {
                                                Spacer(Modifier.height(8.dp))
                                                CatalogShelf(
                                                    title = config.customTitle ?: gems.title,
                                                    items = gems.items,
                                                    shelfType = gems.type,
                                                    onItemClick = onMediaClick,
                                                    onSeeAll = {
                                                        SeeAllViewModel.pendingItems["hidden_gems"] = (config.customTitle ?: gems.title) to gems.items
                                                        onSeeAllClick("shelf:hidden_gems")
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            HomeSection.ADDON_SHELVES -> {
                                // Individual addon shelves are now rendered via AddonShelfItem
                                // in the layout order. This section is kept for backwards
                                // compatibility but doesn't render anything.
                            }

                            HomeSection.BECAUSE_YOU_WATCHED -> Unit

                            HomeSection.BECAUSE_YOU_WATCHED_MOVIES,
                            HomeSection.BECAUSE_YOU_WATCHED_TV -> {
                                val shelf = state.becauseYouWatched
                                    .firstOrNull { it.id == config.section.shelfId }
                                    ?.takeIf {
                                        mediaType == "all" ||
                                            (mediaType == "movie" && config.section == HomeSection.BECAUSE_YOU_WATCHED_MOVIES) ||
                                            (mediaType == "tv" && config.section == HomeSection.BECAUSE_YOU_WATCHED_TV)
                                    }
                                if (shelf != null && shelf.items.isNotEmpty()) {
                                    item(key = shelf.id) {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            LocalCardStyle provides sectionStyle,
                                        ) {
                                            Spacer(Modifier.height(8.dp))
                                            CatalogShelf(
                                                title = config.customTitle ?: shelf.title,
                                                items = shelf.items,
                                                shelfType = shelf.type,
                                                onItemClick = { item ->
                                                    item.tmdbId?.let { tmdbId ->
                                                        val type = if (item.type == MediaType.SERIES) "tv" else "movie"
                                                        val destination = "more_like_${type}_$tmdbId"
                                                        SeeAllViewModel.pendingItems[destination] =
                                                            "Because You Watched ${item.title}" to emptyList()
                                                        onSeeAllClick(destination)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            HomeSection.MDBLIST_SHELVES -> {
                                if (state.mdbListShelves.isNotEmpty()) {
                                    item(key = "mdblist_shelves") {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            LocalCardStyle provides sectionStyle,
                                        ) {
                                            Spacer(Modifier.height(8.dp))
                                            Column {
                                                state.mdbListShelves.forEach { shelf ->
                                                    CatalogShelf(
                                                        title = shelf.title,
                                                        items = shelf.items,
                                                        onItemClick = onMediaClick,
                                                        onSeeAll = {
                                                            SeeAllViewModel.pendingItems[shelf.id] = shelf.title to shelf.items
                                                            onSeeAllClick("shelf:${shelf.id}")
                                                        },
                                                    )
                                                    Spacer(Modifier.height(8.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            HomeSection.UP_NEXT -> if (mediaType != "movie" && watchingState.upNext.isNotEmpty()) {
                                item(key = "up_next") {
                                    com.torve.android.ui.product.MobileUpNextShelf(watchingState, onEpisodeClick, onYourShowsClick,
                                        returningViewer = returningViewerHome)
                                }
                            }
                            // SEARCH_BAR is rendered above the scrolling content.
                            HomeSection.SEARCH_BAR,
                            HomeSection.ON_NOW -> {}
                        }
                    }
                    is CustomItem -> {
                                val section = item.section
                                val items = state.customShelves[section.id]
                                if (!items.isNullOrEmpty()) {
                                    val customStyle = resolveCardStyle(
                                        presets = settingsState.cardStylePresets,
                                        presetId = null,
                                        globalDefaultPresetId = settingsState.globalDefaultPresetId,
                                    )
                                    item(key = "custom_${section.id}") {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            LocalCardStyle provides customStyle,
                                        ) {
                                            Spacer(Modifier.height(8.dp))
                                            CatalogShelf(
                                                title = section.title,
                                                items = items,
                                                onItemClick = onMediaClick,
                                                onSeeAll = { onSeeAllClick("custom:${section.id}") },
                                            )
                                        }
                                    }
                                }
                            }
                            is GroupItem -> {
                                val titles = com.torve.domain.model.mergeCatalogGroup(item.group, groupSources).filter { media ->
                                    mediaType == "all" || mediaType == "movie" && media.type == MediaType.MOVIE || mediaType == "tv" && media.type == MediaType.SERIES
                                }
                                if (titles.isNotEmpty()) item(key = "group_${item.group.id}") {
                                    CatalogShelf(title = item.group.name, items = titles,
                                        sizeOverride = if (item.group.presentation == com.torve.domain.model.CatalogGroupPresentation.LANDSCAPE) CardSize.LANDSCAPE else CardSize.MEDIUM,
                                        onItemClick = onMediaClick, onSeeAll = {
                                            val key = "group:${item.group.id}:$mediaType"
                                            SeeAllViewModel.pendingItems[key] = item.group.name to titles
                                            onSeeAllClick("shelf:$key")
                                        })
                                }
                            }
                            is AddonShelfItem -> {
                                val shelf = item.shelf
                                if (shelf.items.isNotEmpty()) {
                                    val addonStyle = resolveCardStyle(
                                        presets = settingsState.cardStylePresets,
                                        presetId = null,
                                        globalDefaultPresetId = settingsState.globalDefaultPresetId,
                                    )
                                    item(key = "addon_${shelf.id}") {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            LocalCardStyle provides addonStyle,
                                        ) {
                                            Spacer(Modifier.height(8.dp))
                                            CatalogShelf(
                                                title = shelf.title,
                                                items = shelf.items,
                                                onItemClick = onMediaClick,
                                                onSeeAll = {
                                                    SeeAllViewModel.pendingItems[shelf.id] = shelf.title to shelf.items
                                                    onSeeAllClick("shelf:${shelf.id}")
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // (Hidden Gems is now part of the section config loop above)
                }
            }
        }

        // ── Search ──
        val isSearchMode = showSearchBar && state.searchQuery.length >= 2
        if (isSearchMode) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 130.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Show existing results while loading new ones to avoid flicker
                items(
                    state.searchResults.size,
                    key = { index -> "${state.searchResults[index].id}_$index" },
                ) { index ->
                    val item = state.searchResults[index]
                    PosterCard(
                        item = item,
                        sizeOverride = CardSize.MEDIUM,
                        fillCell = true,
                        onClick = { onMediaClick(item) },
                    )
                }
                if (state.isSearching) {
                    item(
                        key = "loading",
                        span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Amber, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (state.searchResults.isEmpty()) {
                    item(
                        key = "empty",
                        span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.search_no_results),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Torve.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }

        }
    }
} // end CompositionLocalProvider
}

@Composable
private fun MobileHomeQuickAccessHeader(
    onChannelsClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    profileName: String,
    onProfilesClick: () -> Unit,
    needsAttention: Boolean,
    onSearchClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = "Switch household profile", onClick = onProfilesClick)) {
            Text(
                text = localizedUiText("Torve"),
                style = MaterialTheme.typography.headlineSmall,
                color = Torve.colors.textPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = profileName,
                style = MaterialTheme.typography.bodySmall,
                color = Torve.colors.textTertiary,
            )
        }
        onSearchClick?.let { openSearch ->
            IconButton(onClick = openSearch, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Search, stringResource(R.string.nav_search), tint = Torve.colors.textSecondary)
            }
        }
        onChannelsClick?.let { openChannels ->
        IconButton(onClick = openChannels) {
            Icon(
                imageVector = Icons.Rounded.LiveTv,
                contentDescription = stringResource(R.string.nav_channels),
                tint = Amber,
            )
        }
        }
        IconButton(onClick = onSettingsClick) {
          Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = localizedUiText(if (needsAttention) "Settings · providers need attention" else stringResource(R.string.nav_settings)),
                tint = Torve.colors.textSecondary,
            )
            if (needsAttention) Box(Modifier.align(Alignment.TopEnd).size(8.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape))
          }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Hero Pager — Full-bleed artwork with deliberate swipe navigation
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroPager(items: List<MediaItem>, onItemClick: (MediaItem) -> Unit,
    watchlistIds: Set<String> = emptySet(), isWatchlistLocked: Boolean = false,
    onWatchlistClick: (MediaItem) -> Unit = {}) {
    val pager = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(pager, Modifier.fillMaxWidth(), key = { "${items[it].type}:${items[it].id}" }) { page ->
            HeroSlide(items[page], { onItemClick(items[page]) },
                refreshRating = page == pager.settledPage && !pager.isScrollInProgress)
        }
        Row(Modifier.align(Alignment.CenterHorizontally).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, item ->
                Box(Modifier.size(40.dp, 44.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) {
                    scope.launch { pager.animateScrollToPage(index) }
                }.semantics { contentDescription = "Featured title ${index + 1} of ${items.size}: ${item.title}"; selected = pager.currentPage == index }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (pager.currentPage == index) 20.dp else 6.dp, 6.dp)
                        .clip(CircleShape).background(if (pager.currentPage == index) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)))
                }
            }
        }
    }
}

@Composable
private fun HeroSlide(item: MediaItem, onClick: () -> Unit,
    isInWatchlist: Boolean = false, isWatchlistLocked: Boolean = false, onWatchlistClick: () -> Unit = {},
    refreshRating: Boolean = true) {
    val fontScale = LocalConfiguration.current.fontScale.coerceAtLeast(1f)
    val ratings = LocalRatingPrefs.current
    val currentRatings = rememberMobileCurrentRatings(item, refreshRating)
    val metadata: com.torve.domain.repository.MetadataRepository = koinInject()
    var logo by remember(item.type, item.id, item.logoUrl) { mutableStateOf(item.logoUrl) }
    LaunchedEffect(item.type, item.id, item.logoUrl) {
        if (logo != null || item.isContentPlaceholder || item.isStubDetail) return@LaunchedEffect
        val id = item.tmdbId ?: item.id.extractTmdbIdOrNull() ?: return@LaunchedEffect
        try { logo = metadata.getLogoUrl(if (item.type == MediaType.SERIES) "tv" else "movie", id) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height((240 * fontScale).dp)
        .clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        AsyncImage(if (item.isContentPlaceholder || item.isStubDetail) null else item.backdropUrl ?: item.posterUrl,
            item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.torve.android.ui.product.ArtworkTitle(item, logoUrl = logo, onDarkArtwork = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item.year?.let { Text(it.toString(), color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall) }
                val scores = currentRatings ?: item.rating?.let { MediaRatings(tmdbScore = it.toFloat()) }
                if (scores != null) MultiRatingPills(scores, prefs = ratings)
            }
        }
    }
}

// Fallback single hero (if not enough items for pager)
@Composable
private fun SingleHeroBanner(
    item: MediaItem,
    onClick: () -> Unit,
) {
    HeroSlide(item = item, onClick = onClick)
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Continue Watching Card — Landscape thumbnail with progress
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun ContinueWatchingCard(
    progress: WatchProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ratings: MediaRatings? = null,
    refreshRating: Boolean = false,
) {
    val ratingPrefs = LocalRatingPrefs.current
    val currentRatings = rememberMobileCurrentRatings(progress.toSeeAllMediaItem().copy(ratings = ratings), refreshRating)
    Column(
        modifier = modifier
            .width(220.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(124.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            // Content-policy hardening: placeholder items get no real image
            val cwImageModel = if (progress.isContentPlaceholder) null
                else progress.backdropUrl ?: progress.posterUrl
            AsyncImage(
                model = cwImageModel,
                contentDescription = if (progress.isContentPlaceholder) null else progress.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            // Rating pills at top-right
            if (currentRatings != null && ratingPrefs.allowRatingsOnLandscapeCards) {
                MultiRatingPills(
                    ratings = currentRatings,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }

            // Subtle gradient at bottom for progress bar visibility
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Obsidian.copy(alpha = 0.8f)),
                        ),
                    ),
            )

            // Progress bar — amber, thin, at absolute bottom
            LinearProgressIndicator(
                progress = { progress.progressPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter),
                color = Amber,
                trackColor = Snow.copy(alpha = 0.15f),
            )

            // Play icon center
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Obsidian.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.common_play),
                    tint = Snow,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = progress.title,
            style = MaterialTheme.typography.titleSmall,
            color = Torve.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (progress.showTitle != null) {
            Text(
                text = "S${progress.seasonNumber}E${progress.episodeNumber}",
                style = MaterialTheme.typography.bodySmall,
                color = Torve.colors.textTertiary,
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Catalog Shelf — Horizontal scrolling content row
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun CatalogShelf(
    title: String,
    items: List<MediaItem>,
    shelfType: ShelfType = ShelfType.POSTER,
    onItemClick: (MediaItem) -> Unit,
    onSeeAll: (() -> Unit)? = null,
    cardStyleOverride: com.torve.domain.model.CardStyle? = null,
    sizeOverride: CardSize? = null,
    modifier: Modifier = Modifier,
) {
    val baseStyle = cardStyleOverride ?: LocalCardStyle.current
    val shelfListState = rememberLazyListState()
    val shelfSizeOverride = if (cardStyleOverride == null) {
        when (shelfType) {
            ShelfType.LANDSCAPE, ShelfType.WIDE -> CardSize.LANDSCAPE
            else -> null
        }
    } else null

    androidx.compose.runtime.CompositionLocalProvider(LocalCardStyle provides baseStyle) {
        Column(modifier = modifier) {
            SectionHeader(
                title = title,
                action = if (onSeeAll != null) stringResource(R.string.home_see_all) else null,
                onActionClick = onSeeAll,
            )

            LazyRow(
                state = shelfListState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items.size,
                    key = { index -> "${items[index].id}_$index" },
                ) { index ->
                    PosterCard(
                        item = items[index],
                        sizeOverride = sizeOverride ?: shelfSizeOverride,
                        onClick = { onItemClick(items[index]) },
                    )
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Recommendation Card — Poster with "Because you like..." label
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun RecommendationCard(
    scored: ScoredMediaItem,
    onClick: () -> Unit,
    sizeOverride: CardSize? = null,
    modifier: Modifier = Modifier,
    onFeedback: (() -> Unit)? = null,
) {
    val width = sizeOverride?.width ?: LocalCardStyle.current.size.resolvedWidthDp().dp
    Column(modifier = modifier.width(width)) {
        Box {
        PosterCard(
            item = scored.item,
            sizeOverride = sizeOverride,
            onClick = onClick,
        )
        if (onFeedback != null) IconButton(onClick = onFeedback, modifier = Modifier.align(Alignment.TopEnd)
            .size(48.dp).clip(CircleShape).background(Charcoal.copy(alpha = .75f))) {
            Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.gap_recommendation_options), tint = Snow)
        }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = localizedRecommendationReason(scored.reason),
            style = MaterialTheme.typography.labelSmall,
            color = Amber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Person Avatar Card — Circular avatar with name
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun PersonAvatarCard(
    person: PersonSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profileUrl = person.profileUrl?.trim()?.takeIf {
        it.startsWith("https://") || it.startsWith("http://")
    }
    Column(
        modifier = modifier
            .width(80.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (profileUrl != null) {
            SubcomposeAsyncImage(
                model = profileUrl,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape),
                loading = { ShimmerBox(modifier = Modifier.fillMaxSize()) },
                error = { PersonAvatarFallback(person.name, Modifier.fillMaxSize()) },
            )
        } else {
            PersonAvatarFallback(person.name, Modifier.size(72.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = person.name,
            style = MaterialTheme.typography.labelSmall,
            color = Snow,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun PersonAvatarFallback(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Gunmetal),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""),
            style = MaterialTheme.typography.titleSmall,
            color = Torve.colors.textTertiary,
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Search Bar
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
internal fun HomeSearchBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onClose: () -> Unit,
) {
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Gunmetal)
            .heightIn(min = 52.dp).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = searchQuery, onValueChange = onQueryChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Snow),
            cursorBrush = SolidColor(Amber),
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
            decorationBox = { innerTextField ->
                Box {
                    if (searchQuery.isEmpty()) Text(stringResource(R.string.home_search_hint),
                        style = MaterialTheme.typography.bodyMedium, color = Torve.colors.textHint)
                    innerTextField()
                }
            },
        )
        IconButton(onClick = if (searchQuery.isNotEmpty()) onClearSearch else onClose,
            modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(
                if (searchQuery.isNotEmpty()) R.string.home_clear_search else R.string.common_close),
                tint = Torve.colors.textSecondary)
        }
    }
}

private fun WatchProgress.toSeeAllMediaItem(): MediaItem =
    MediaItem(
        id = mediaId,
        tmdbId = mediaId.extractTmdbIdOrNull(),
        type = mediaType,
        title = if (mediaType == MediaType.SERIES) showTitle?.takeIf { it.isNotBlank() } ?: title else title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        isContentPlaceholder = isContentPlaceholder,
    )

@Composable
private fun HomeSkeletonLoader() {
    Column(modifier = Modifier.fillMaxSize()) {
        // Hero shimmer
        val screenHeight = LocalConfiguration.current.screenHeightDp
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height((screenHeight * 0.55f).dp),
        )
        Spacer(Modifier.height(20.dp))

        // Shelf shimmer
        repeat(3) {
            ShimmerBox(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .width(120.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(5) {
                    com.torve.android.ui.components.ShimmerPosterCard()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
