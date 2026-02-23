package com.streamvault.android.ui.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.streamvault.android.R
import coil3.compose.AsyncImage
import com.streamvault.android.ui.components.CardSize
import com.streamvault.android.ui.components.PosterCard
import com.streamvault.android.ui.components.SectionHeader
import com.streamvault.android.ui.components.ShimmerBox
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.HeroGradient
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.ShelfType
import com.streamvault.domain.model.WatchProgress
import com.streamvault.domain.recommendation.ScoredMediaItem
import com.streamvault.presentation.home.HomeViewModel
import com.streamvault.presentation.watchlist.WatchlistViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onMediaClick: (MediaItem) -> Unit,
    onContinueWatchingClick: (WatchProgress) -> Unit = {},
    mediaType: String = "all",
    viewModel: HomeViewModel = koinInject(),
    watchlistViewModel: WatchlistViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val watchlistState by watchlistViewModel.state.collectAsState()

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

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                // Shimmer loading skeleton
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
                        color = StreamVault.colors.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StreamVault.colors.textTertiary,
                    )
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { viewModel.refresh() }) {
                        Text(stringResource(R.string.home_try_again))
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // No top padding — hero bleeds to top edge under status bar
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    // ── Hero Banner (Full-Bleed Pager) ──
                    // Takes up ~60% of screen height. Auto-pages every 6 seconds.
                    // Shows top 5 trending items with backdrop, gradient overlay,
                    // title, tagline, and Play/Watchlist buttons.
                    item(key = "hero") {
                        val heroItems = filteredShelves
                            .firstOrNull()?.items?.take(5) ?: emptyList()

                        if (heroItems.isNotEmpty()) {
                            HeroPager(
                                items = heroItems,
                                onItemClick = onMediaClick,
                                watchlistIds = watchlistState.watchlistIds,
                                onWatchlistClick = { watchlistViewModel.toggleWatchlist(it) },
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

                    // ── Continue Watching ──
                    if (filteredContinueWatching.isNotEmpty()) {
                        item(key = "continue_watching") {
                            SectionHeader(title = stringResource(R.string.home_continue_watching))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(filteredContinueWatching, key = { it.mediaId }) { progress ->
                                    ContinueWatchingCard(
                                        progress = progress,
                                        onClick = { onContinueWatchingClick(progress) },
                                    )
                                }
                            }
                        }
                    } else if (mediaType == "all" && !state.isLoading) {
                        item(key = "continue_watching_empty") {
                            SectionHeader(title = stringResource(R.string.home_continue_watching))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Charcoal)
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.home_continue_watching_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StreamVault.colors.textTertiary,
                                )
                            }
                        }
                    }

                    // ── Recommended For You ──
                    if (filteredRecommendations.isNotEmpty()) {
                        item(key = "recommended") {
                            Spacer(Modifier.height(8.dp))
                            SectionHeader(title = stringResource(R.string.home_recommended))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(
                                    filteredRecommendations,
                                    key = { it.item.id },
                                ) { scored ->
                                    RecommendationCard(
                                        scored = scored,
                                        onClick = { onMediaClick(scored.item) },
                                    )
                                }
                            }
                        }
                    }

                    // ── Your Watchlist ──
                    state.watchlistShelf?.let { watchlist ->
                        if (watchlist.items.isNotEmpty()) {
                            item(key = "your_watchlist") {
                                Spacer(Modifier.height(8.dp))
                                CatalogShelf(
                                    title = watchlist.title,
                                    items = watchlist.items,
                                    shelfType = watchlist.type,
                                    onItemClick = onMediaClick,
                                    onSeeAll = {},
                                )
                            }
                        }
                    }

                    // ── Catalog Shelves ──
                    items(filteredShelves, key = { it.id }) { shelf ->
                        Spacer(Modifier.height(8.dp))
                        CatalogShelf(
                            title = shelf.title,
                            items = shelf.items,
                            shelfType = shelf.type,
                            onItemClick = onMediaClick,
                            onSeeAll = {},
                        )
                    }

                    // ── Because You Watched ──
                    state.becauseYouWatched.forEach { shelf ->
                        item(key = shelf.id) {
                            Spacer(Modifier.height(8.dp))
                            CatalogShelf(
                                title = shelf.title,
                                items = shelf.items,
                                shelfType = shelf.type,
                                onItemClick = onMediaClick,
                                onSeeAll = {},
                            )
                        }
                    }

                    // ── Hidden Gems ──
                    state.hiddenGemsShelf?.let { gems ->
                        if (gems.items.isNotEmpty()) {
                            item(key = "hidden_gems") {
                                Spacer(Modifier.height(8.dp))
                                CatalogShelf(
                                    title = gems.title,
                                    items = gems.items,
                                    shelfType = gems.type,
                                    onItemClick = onMediaClick,
                                    onSeeAll = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
    onWatchlistClick: () -> Unit = {},
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((screenHeight * 0.55f).dp)
            .clickable(onClick = onClick),
    ) {
        // Backdrop image — full bleed
        AsyncImage(
            model = item.backdropUrl ?: item.posterUrl,
            contentDescription = item.title,
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
                    text = item.genres.take(3).joinToString(" • ") { it.name },
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
                item.rating?.let { rating ->
                    if (rating > 0) {
                        Text(
                            text = "  •  ★ ${"%.1f".format(rating)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Amber.copy(alpha = 0.9f),
                        )
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
                        containerColor = if (isInWatchlist) Amber.copy(alpha = 0.25f) else Snow.copy(alpha = 0.15f),
                        contentColor = if (isInWatchlist) Amber else Snow,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        if (isInWatchlist) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isInWatchlist) stringResource(R.string.home_in_watchlist) else stringResource(R.string.home_watchlist),
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
) {
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
            AsyncImage(
                model = progress.backdropUrl ?: progress.posterUrl,
                contentDescription = progress.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

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
            color = StreamVault.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (progress.showTitle != null) {
            Text(
                text = "S${progress.seasonNumber}E${progress.episodeNumber}",
                style = MaterialTheme.typography.bodySmall,
                color = StreamVault.colors.textTertiary,
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
    modifier: Modifier = Modifier,
) {
    val cardSize = when (shelfType) {
        ShelfType.LANDSCAPE, ShelfType.WIDE -> CardSize.LANDSCAPE
        else -> CardSize.MEDIUM
    }

    Column(modifier = modifier) {
        SectionHeader(
            title = title,
            action = if (onSeeAll != null) stringResource(R.string.home_see_all) else null,
            onActionClick = onSeeAll,
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                PosterCard(
                    item = item,
                    size = cardSize,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Recommendation Card — Poster with "Because you like…" label
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun RecommendationCard(
    scored: ScoredMediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.width(120.dp)) {
        PosterCard(
            item = scored.item,
            size = CardSize.MEDIUM,
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
// Skeleton Loader — Full screen loading placeholder
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
                    com.streamvault.android.ui.components.ShimmerPosterCard()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
