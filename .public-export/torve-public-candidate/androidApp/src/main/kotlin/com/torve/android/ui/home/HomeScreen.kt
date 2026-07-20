package com.torve.android.ui.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onMediaClick: (MediaItem) -> Unit,
    onContinueWatchingClick: (WatchProgress) -> Unit = {},
    onSeeAllClick: (String) -> Unit = {},
    onProviderClick: (providerId: Int, providerName: String) -> Unit = { _, _ -> },
    onPersonClick: (Int) -> Unit = {},
    accessTier: AccessTier = AccessTier.FREE,
    onLockedFeatureClick: (PremiumFeature) -> Unit = {},
    mediaType: String = "all",
    viewModel: HomeViewModel = koinInject(),
    watchlistViewModel: WatchlistViewModel = koinInject(),
    settingsViewModel: com.torve.presentation.settings.SettingsViewModel = koinInject(),
    authClient: AuthClient = koinInject(),
    subscriptionViewModel: SubscriptionViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val watchlistState by watchlistViewModel.state.collectAsState()
    val authUser by authClient.authUserFlow.collectAsState()
    val subscriptionState by subscriptionViewModel.state.collectAsState()
    val isSignedIn = authUser != null
    val sectionConfigs by viewModel.sectionConfigs.collectAsState()
    val enabledServiceIds by viewModel.enabledServiceIds.collectAsState()
    val customSections by viewModel.customSections.collectAsState()
    val homeLayoutOrder by viewModel.homeLayoutOrder.collectAsState()
    val mdblistApiKey = settingsState.mdblistApiKey
    val activeStreamingServices = remember(enabledServiceIds) {
        ALL_STREAMING_SERVICES.filter { it.tmdbProviderId in enabledServiceIds }
    }
    val providerLogos by viewModel.providerLogos.collectAsState()
    val watchlistLocked = remember(accessTier) {
        PremiumAccess.isPremiumLocked(PremiumFeature.WATCHLIST_EDIT, accessTier)
    }
    val homeListState = rememberLazyListState()

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

    LaunchedEffect(mdblistApiKey) {
        viewModel.refreshRatings(mdblistApiKey)
    }

    LaunchedEffect(authUser?.id) {
        if (authUser != null) {
            subscriptionViewModel.refreshAccess()
        }
    }

    NeedsVerificationToastEffect(
        message = subscriptionState.verificationEmailMessage,
        onConsumed = subscriptionViewModel::consumeVerificationEmailMessage,
    )

    // Reload home data when Trakt sync completes (new watchlist, history, etc.)
    val traktLastSync = settingsState.traktLastSyncTime
    LaunchedEffect(traktLastSync) {
        if (traktLastSync != null && !state.isLoading) {
            viewModel.refresh()
        }
    }

    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
        LocalRatingPrefs provides settingsState.ratingPrefs,
    ) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            state.isLoading -> {
                HomeSkeletonLoader()
            }

            state.error != null -> {
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
                            text = message,
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
                val addonShelfVisibility = state.addonShelfVisibility
                val enabledItems = remember(sectionConfigs, customSections, homeLayoutOrder, state.addonShelves, addonShelfVisibility) {
                    val orderIndex = homeLayoutOrder.withIndex().associate { it.value to it.index }
                    fun itemKey(item: HomeRenderItem): String = when (item) {
                        is BuiltInItem -> "section:${item.config.section.name}"
                        is CustomItem -> "custom:${item.section.id}"
                        is AddonShelfItem -> "addon:${item.shelf.id}"
                    }
                    buildList<HomeRenderItem> {
                        sectionConfigs.filter { it.enabled }.forEach { add(BuiltInItem(it)) }
                        customSections.filter { it.enabled }.forEach { add(CustomItem(it)) }
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
                            orderIndex[itemKey(item)] ?: (10_000 + item.order)
                        }.thenBy { it.order },
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = homeListState,
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
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
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                items(filteredContinueWatching, key = { it.mediaId }) { progress ->
                                                    ContinueWatchingCard(
                                                        progress = progress,
                                                        onClick = { onContinueWatchingClick(progress) },
                                                        ratings = state.continueWatchingRatings[progress.mediaId],
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
                                            title = config.customTitle ?: "Upcoming Schedule",
                                            action = stringResource(R.string.home_see_all),
                                            onActionClick = {
                                                SeeAllViewModel.pendingItems["upcoming_schedule"] =
                                                    (config.customTitle ?: "Upcoming Schedule") to filteredUpcomingSchedule
                                                onSeeAllClick("upcoming_schedule")
                                            },
                                        )
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
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
                                                text = "Add movies and shows to your watchlist",
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
                                                title = config.customTitle ?: "Watchlist — Movies",
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
                                                title = config.customTitle ?: "Watchlist — TV Shows",
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
                                            providerLogos = providerLogos,
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
                                                title = config.customTitle ?: "Recently Watched",
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
                                            title = config.customTitle ?: "Popular Actors",
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

                            HomeSection.BECAUSE_YOU_WATCHED -> {
                                if (state.becauseYouWatched.isNotEmpty()) {
                                    item(key = "because_you_watched") {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            LocalCardStyle provides sectionStyle,
                                        ) {
                                            Spacer(Modifier.height(8.dp))
                                            Column {
                                                state.becauseYouWatched.forEach { shelf ->
                                                    CatalogShelf(
                                                        title = shelf.title,
                                                        items = shelf.items,
                                                        shelfType = shelf.type,
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
                            // SEARCH_BAR is a floating overlay, not a LazyColumn section
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
        val showSearchBar = remember(sectionConfigs) {
            sectionConfigs.any { it.section == HomeSection.SEARCH_BAR && it.enabled }
        }
        val isSearchMode = showSearchBar && state.searchQuery.length >= 2
        if (isSearchMode) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 130.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(top = 64.dp),
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

        // ── Floating Search Bar ──
        if (showSearchBar) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Obsidian, Obsidian, Obsidian.copy(alpha = 0.9f), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    HomeSearchBar(
                        searchQuery = state.searchQuery,
                        onQueryChange = { viewModel.updateSearchQuery(it) },
                        onClearSearch = { viewModel.clearSearch() },
                    )
                }
            }
        }

    }
} // end CompositionLocalProvider
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Hero Pager — Full-bleed auto-scrolling hero
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroPager(
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    watchlistIds: Set<String> = emptySet(),
    isWatchlistLocked: Boolean = false,
    onWatchlistClick: (MediaItem) -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { items.size })

    // Auto-scroll every 6 seconds
    LaunchedEffect(pagerState) {
        while (true) {
            delay(6000)
            val next = (pagerState.currentPage + 1) % items.size
            pagerState.animateScrollToPage(next, animationSpec = tween(600))
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val item = items[page]
            HeroSlide(
                item = item,
                onClick = { onItemClick(item) },
                isInWatchlist = watchlistIds.contains(item.id),
                isWatchlistLocked = isWatchlistLocked,
                onWatchlistClick = { onWatchlistClick(item) },
            )
        }

        // Page indicators — subtle dots at bottom center
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(items.size) { index ->
                val isSelected = pagerState.currentPage == index
                val color by animateColorAsState(
                    if (isSelected) Amber else Snow.copy(alpha = 0.3f),
                    label = "dot_color",
                )
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun HeroSlide(
    item: MediaItem,
    onClick: () -> Unit,
    isInWatchlist: Boolean = false,
    isWatchlistLocked: Boolean = false,
    onWatchlistClick: () -> Unit = {},
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val ratingPrefs = LocalRatingPrefs.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((screenHeight * 0.55f).dp)
            .clickable(onClick = onClick),
    ) {
        // Backdrop image — full bleed
        // Content-policy hardening: locked items never request real artwork
        val heroImageModel = if (item.isContentPlaceholder || item.isStubDetail) null
            else item.backdropUrl ?: item.posterUrl
        AsyncImage(
            model = heroImageModel,
            contentDescription = if (item.isContentPlaceholder || item.isStubDetail) null else item.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // Gradient overlay — deep cinematic fade
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(HeroGradient)),
        )

        // Content overlay — bottom aligned
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 40.dp),
        ) {
            // Genre tags
            if (item.genres.isNotEmpty()) {
                Text(
                    text = item.genres.take(3).joinToString(" \u2022 ") { it.name },
                    style = MaterialTheme.typography.labelMedium,
                    color = Amber,
                    letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Title
            Text(
                text = item.title,
                style = MaterialTheme.typography.displayMedium,
                color = Snow,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Year + Rating
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.year?.let {
                    Text(
                        text = it.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Snow.copy(alpha = 0.7f),
                    )
                }
                if (ratingPrefs.allowsTmdbRatingProvider()) {
                    item.rating?.let { rating ->
                        if (rating > 0) {
                            Text(
                                text = "  \u2022  \u2605 ${"%.1f".format(rating)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Amber.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
            }

            // Overview snippet
            item.overview?.let { overview ->
                if (overview.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = Snow.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Action buttons
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber,
                        contentColor = Obsidian,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.common_details),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                FilledTonalButton(
                    onClick = onWatchlistClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = when {
                            isWatchlistLocked -> Amber.copy(alpha = 0.22f)
                            isInWatchlist -> Amber.copy(alpha = 0.25f)
                            else -> Snow.copy(alpha = 0.15f)
                        },
                        contentColor = when {
                            isWatchlistLocked -> Amber
                            isInWatchlist -> Amber
                            else -> Snow
                        },
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        when {
                            isWatchlistLocked -> Icons.Rounded.Lock
                            isInWatchlist -> Icons.Rounded.Bookmark
                            else -> Icons.Rounded.BookmarkBorder
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            isWatchlistLocked -> stringResource(R.string.premium_unlock_with_lifetime)
                            isInWatchlist -> stringResource(R.string.home_in_watchlist)
                            else -> stringResource(R.string.home_watchlist)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
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
) {
    val ratingPrefs = LocalRatingPrefs.current
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
            if (ratings != null && ratingPrefs.allowRatingsOnLandscapeCards) {
                MultiRatingPills(
                    ratings = ratings,
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
) {
    val width = sizeOverride?.width ?: LocalCardStyle.current.size.resolvedWidthDp().dp
    Column(modifier = modifier.width(width)) {
        PosterCard(
            item = scored.item,
            sizeOverride = sizeOverride,
            onClick = onClick,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = scored.reason,
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
    var showFallback by remember(person.profileUrl) { mutableStateOf(person.profileUrl == null) }
    Column(
        modifier = modifier
            .width(80.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!showFallback && person.profileUrl != null) {
            AsyncImage(
                model = person.profileUrl,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape),
                onError = { showFallback = true },
            )
        } else {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Gunmetal),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = person.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""),
                    style = MaterialTheme.typography.titleSmall,
                    color = Torve.colors.textTertiary,
                )
            }
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

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Search Bar
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
private fun HomeSearchBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Gunmetal)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        BasicTextField(
            value = searchQuery,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Snow),
            cursorBrush = SolidColor(Amber),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = stringResource(R.string.home_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Torve.colors.textHint,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (searchQuery.isNotEmpty()) {
            IconButton(
                onClick = onClearSearch,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(24.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = Torve.colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = Torve.colors.textTertiary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(18.dp),
            )
        }
    }
}

// Skeleton Loader — Full screen loading placeholder
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

private fun WatchProgress.toSeeAllMediaItem(): MediaItem =
    MediaItem(
        id = mediaId,
        tmdbId = mediaId.extractTmdbIdOrNull(),
        type = mediaType,
        title = if (mediaType == MediaType.SERIES) {
            showTitle?.takeIf { it.isNotBlank() } ?: title
        } else {
            title
        },
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
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
