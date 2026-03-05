package com.streamvault.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.streamvault.android.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import com.streamvault.android.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.repository.MetadataRepository
import org.koin.compose.koinInject

@Composable
fun TvSeeAllScreen(
    railKey: String,
    mediaType: String,
    title: String,
    railFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val items = remember { mutableStateListOf<MediaItem>() }
    var currentPage by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var loading by remember { mutableStateOf(false) }
    var initialLoad by remember { mutableStateOf(true) }
    val gridState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }

    BackHandler(onBack = onBack)

    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            !loading && currentPage < totalPages && lastVisibleIndex >= totalItems - 10
        }
    }

    suspend fun loadPage(page: Int) {
        if (loading || page > totalPages) return
        loading = true
        try {
            if (railKey.startsWith("person_credits_")) {
                val personId = railKey.removePrefix("person_credits_").toIntOrNull()
                if (personId != null && page == 1) {
                    val credits = metadataRepo.getPersonCredits(personId)
                    items.addAll(credits)
                }
                totalPages = 1
                currentPage = 1
                loading = false
                initialLoad = false
                return
            }
            val result = when {
                railKey.startsWith("trending_") -> metadataRepo.getTrendingPaged(mediaType, page)
                railKey.startsWith("popular_") -> metadataRepo.getPopularPaged(mediaType, page)
                railKey.startsWith("top_rated_") -> metadataRepo.getTopRatedPaged(mediaType, page)
                railKey.startsWith("genre_") -> {
                    val genreId = railKey.substringAfterLast("_")
                    metadataRepo.discover(type = mediaType, page = page, withGenres = genreId)
                }
                railKey == "recommended" -> metadataRepo.getPopularPaged(mediaType, page)
                railKey == "continue_watching" -> {
                    loading = false
                    return
                }
                railKey.startsWith("watchlist_") -> {
                    loading = false
                    return
                }
                else -> metadataRepo.getPopularPaged(mediaType, page)
            }
            totalPages = result.totalPages
            items.addAll(result.items)
            currentPage = page
        } catch (_: Throwable) {
            // Silently handle pagination errors
        } finally {
            loading = false
            initialLoad = false
        }
    }

    LaunchedEffect(railKey, mediaType) {
        loadPage(1)
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            loadPage(currentPage + 1)
        }
    }

    // Auto-focus the first grid item once content loads so D-pad works immediately
    LaunchedEffect(items.size, initialLoad) {
        if (!initialLoad && items.isNotEmpty()) {
            try {
                firstItemFocusRequester.requestFocus()
            } catch (_: IllegalStateException) { /* not yet attached */ }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 40.dp, top = 18.dp, end = 34.dp, bottom = 16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        when {
            initialLoad && loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Amber)
                }
            }

            items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.tv_no_data),
                        style = MaterialTheme.typography.titleLarge,
                        color = Silver,
                    )
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    state = gridState,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        items,
                        key = { index, item ->
                            item.tmdbId?.let { "sa_${item.type}_$it" } ?: "${item.type}_${item.id}_$index"
                        },
                    ) { index, item ->
                        val requester = if (index == 0) firstItemFocusRequester
                            else remember(index, item.id) { FocusRequester() }
                        if (index == 0) {
                            onFirstContentRequester(requester)
                        }

                        SeeAllPosterCard(
                            item = item,
                            modifier = Modifier
                                .width(198.dp)
                                .aspectRatio(2f / 3f)
                                .focusRequester(requester)
                                .focusProperties {
                                    if (index % 5 == 0) {
                                        left = railFocusRequester
                                    }
                                },
                            onFocused = { onContentFocused(requester) },
                            onClick = { onMediaClick(item) },
                        )
                    }

                    if (loading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = Amber,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeeAllPosterCard(
    item: MediaItem,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.06f else 1f, label = "seeAllCardScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "seeAllBorder",
    )

    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp)),
    ) {
        AsyncImage(
            model = item.posterUrl ?: item.backdropUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Obsidian.copy(alpha = 0.9f),
                        ),
                    ),
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Snow,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildString {
                    item.year?.let { append(it) }
                    if (item.rating != null) {
                        if (isNotBlank()) append("  ")
                        append(String.format("%.1f", item.rating))
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Silver,
                    )
                }
            }
        }
    }
}
