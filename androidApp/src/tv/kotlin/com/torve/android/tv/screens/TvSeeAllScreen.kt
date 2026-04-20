package com.torve.android.tv.screens

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
import androidx.compose.foundation.layout.Row
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
import com.torve.android.R
import androidx.compose.runtime.DisposableEffect
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
import com.torve.android.tv.toMediaItemOrNull
import com.torve.android.ui.theme.*
import com.torve.domain.integrations.LibraryOverlayService
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.tv.components.TvFocusDetailsPanel
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaItem
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.torve.domain.model.MediaType
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.WatchProgressRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.torve.android.tv.focus.TvFocusTargetId
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.focus.rememberRegisteredTvFocusRequester
import com.torve.android.tv.focus.rememberTvModalFocusRestoreController
import org.koin.compose.koinInject

@Composable
internal fun TvSeeAllScreen(
    railKey: String,
    mediaType: String,
    title: String,
    railFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val watchProgressRepo: WatchProgressRepository = koinInject()
    val libraryOverlayService: LibraryOverlayService = koinInject()
    val ratingsEnricher: RatingsEnricher = koinInject()
    val prefsRepo: PreferencesRepository = koinInject()
    val secretStore: IntegrationSecretStore = koinInject()
    val items = remember { mutableStateListOf<MediaItem>() }
    var currentPage by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var loading by remember { mutableStateOf(false) }
    var initialLoad by remember { mutableStateOf(true) }
    val gridState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val focusRestoreController = rememberTvModalFocusRestoreController(key = "see_all_${railKey}_$mediaType")
    val sortOptions = remember(railKey) { sortOptionsForRail(railKey) }
    var selectedSortKey by remember(railKey) { mutableStateOf(sortOptions.firstOrNull()?.key ?: TvSeeAllSortKey.DEFAULT) }
    val sortRequesters = remember(sortOptions) { List(sortOptions.size) { FocusRequester() } }
    val selectedSortIndex = sortOptions.indexOfFirst { it.key == selectedSortKey }.coerceAtLeast(0)
    val loadedItems = items.toList()
    val displayedItems = remember(loadedItems, selectedSortKey) {
        sortSeeAllItems(
            items = loadedItems,
            sortKey = selectedSortKey,
        )
    }
    var initialFocusHandled by remember(railKey, mediaType) { mutableStateOf(false) }
    var focusedMediaItem by remember { mutableStateOf<MediaItem?>(null) }
    var lastFocusedIndex by remember { mutableIntStateOf(-1) }
    val focusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val screenId = remember(railKey, mediaType) { "see_all:$railKey:$mediaType" }

    DisposableEffect(registerFocusHandle, focusRestoreController, screenId) {
        registerFocusHandle?.invoke(
            TvScreenFocusHandle(
                captureFocusedOrigin = {
                    focusRestoreController.captureFocusedOrigin(
                        screenId = screenId,
                    )
                },
                requestRestore = { origin, reason ->
                    focusRestoreController.requestRestore(origin = origin, reason = reason)
                },
            ),
        )
        onDispose {
            registerFocusHandle?.invoke(null)
        }
    }

    BackHandler(onBack = {
        lastFocusedIndex = -1  // Clear so we don't restore when exiting See All itself
        onBack()
    })

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
            if (railKey.startsWith("continue_watching")) {
                val targetType = when (railKey) {
                    "continue_watching_movie" -> MediaType.MOVIE
                    "continue_watching_tv" -> MediaType.SERIES
                    else -> null
                }
                val overlayItems = try {
                    libraryOverlayService.getContinueWatching(200)
                } catch (_: Throwable) {
                    emptyList()
                }
                val mergedProgress = (watchProgressRepo.getInProgress(200) + overlayItems)
                    .groupBy { "${it.mediaType.name}:${it.mediaId}" }
                    .mapNotNull { (_, entries) -> entries.maxByOrNull { it.updatedAt } }
                    .sortedByDescending { it.updatedAt }
                val baseItems = mergedProgress
                    .asSequence()
                    .filter { targetType == null || it.mediaType == targetType }
                    .mapNotNull { it.toMediaItemOrNull() }
                    .filter { it.tmdbId != null }
                    .distinctBy { it.seeAllStableKey() }
                    .toList()
                val mergedItems = coroutineScope {
                    baseItems.map { item ->
                        async {
                            val tmdbId = item.tmdbId ?: return@async item
                            val detailType = if (item.type == MediaType.SERIES) "tv" else "movie"
                            val detail = runCatching { metadataRepo.getDetail(detailType, tmdbId) }.getOrNull()
                            detail
                                ?.copy(
                                    id = item.id,
                                    posterUrl = item.posterUrl ?: detail.posterUrl,
                                    backdropUrl = item.backdropUrl ?: detail.backdropUrl,
                                )
                                ?: item
                        }
                    }.map { it.await() }
                }
                items.clear()
                items.addAll(mergedItems)
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
                railKey.startsWith("watchlist_") -> {
                    loading = false
                    return
                }
                else -> metadataRepo.getPopularPaged(mediaType, page)
            }
            totalPages = result.totalPages
            val existingKeys = items.mapTo(mutableSetOf()) { it.seeAllStableKey() }
            items.addAll(result.items.filter { existingKeys.add(it.seeAllStableKey()) })
            currentPage = page
        } catch (_: Throwable) {
            // Silently handle pagination errors
        } finally {
            loading = false
            initialLoad = false
        }
    }

    LaunchedEffect(railKey, mediaType) {
        items.clear()
        currentPage = 1
        totalPages = Int.MAX_VALUE
        loading = false
        initialLoad = true
        initialFocusHandled = false
        loadPage(1)
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            loadPage(currentPage + 1)
        }
    }

    // Background ratings enrichment — populates SQLite cache + updates items in place.
    var enrichedPages by remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(items.size) {
        if (items.isEmpty()) return@LaunchedEffect
        val page = currentPage
        if (page in enrichedPages) return@LaunchedEffect
        enrichedPages = enrichedPages + page
        launch(Dispatchers.IO) {
            val apiKey = runCatching {
                secretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            }.getOrDefault(MdbListApi.DEFAULT_API_KEY)
            val enriched = ratingsEnricher.enrichList(items.toList(), apiKey)
            withContext(Dispatchers.Main) {
                enriched.forEachIndexed { index, enrichedItem ->
                    if (index < items.size && items[index].tmdbId == enrichedItem.tmdbId) {
                        items[index] = enrichedItem
                    }
                }
            }
        }
    }

    LaunchedEffect(sortOptions, selectedSortIndex) {
        if (sortOptions.isNotEmpty()) {
            onFirstContentRequester(sortRequesters[selectedSortIndex])
        }
    }

    LaunchedEffect(selectedSortKey) {
        if (gridState.firstVisibleItemIndex != 0) {
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(focusRestoreController.pendingRestore?.restoreToken) {
        focusRestoreController.restorePendingFocus(
            screenId = screenId,
        )
    }

    // Restore focus to last focused item when returning from Details sub-route.
    // Uses Lifecycle ON_RESUME to detect when this composable's NavBackStackEntry
    // becomes the active destination again after Details is popped.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && lastFocusedIndex >= 0) {
                val requester = focusRequesters[lastFocusedIndex]
                if (requester != null) {
                    try { requester.requestFocus() } catch (_: Throwable) { }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-focus once content loads so D-pad works immediately.
    LaunchedEffect(initialLoad, displayedItems.size, sortOptions.size, selectedSortIndex) {
        if (!initialLoad && !initialFocusHandled && (sortOptions.isNotEmpty() || displayedItems.isNotEmpty())) {
            try {
                if (sortOptions.isNotEmpty()) {
                    sortRequesters[selectedSortIndex].requestFocus()
                } else {
                    firstItemFocusRequester.requestFocus()
                }
                initialFocusHandled = true
            } catch (_: IllegalStateException) { /* not yet attached */ }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Info panel — left side
        TvFocusDetailsPanel(
            focusedItem = focusedMediaItem,
            modifier = Modifier.width(340.dp),
        )

    Column(
        modifier = Modifier
            .weight(1f)
            .padding(start = 16.dp, top = 32.dp, end = 34.dp, bottom = 16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        if (sortOptions.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                sortOptions.forEachIndexed { index, option ->
                    val sortTarget = remember(screenId, index, option.key) {
                        TvFocusTargetId(
                            screenId = screenId,
                            rowKey = "sort_bar",
                            itemKey = option.key.name,
                            rowIndex = 0,
                            itemIndex = index,
                            targetType = "sort",
                        )
                    }
                    val sortRequester = rememberRegisteredTvFocusRequester(
                        controller = focusRestoreController,
                        target = sortTarget,
                        externalRequester = sortRequesters[index],
                    )
                    TvSeeAllSortButton(
                        label = option.label,
                        selected = option.key == selectedSortKey,
                        modifier = Modifier
                            .focusRequester(sortRequester)
                            .focusProperties {
                                if (index == 0) {
                                    left = railFocusRequester
                                } else {
                                    left = sortRequesters[index - 1]
                                }
                                if (index < sortOptions.lastIndex) {
                                    right = sortRequesters[index + 1]
                                }
                                if (displayedItems.isNotEmpty()) {
                                    down = firstItemFocusRequester
                                }
                            },
                        onFocused = {
                            focusRestoreController.markFocused(sortTarget)
                            onContentFocused(sortRequester)
                        },
                        onClick = { selectedSortKey = option.key },
                    )
                }
            }
        }

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
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        displayedItems,
                        key = { _, item -> "sa_${item.seeAllStableKey()}" },
                    ) { index, item ->
                        val baseRequester = focusRequesters.getOrPut(index) {
                            if (index == 0) firstItemFocusRequester else FocusRequester()
                        }
                        val target = remember(screenId, index, item.id, item.tmdbId) {
                            TvFocusTargetId(
                                screenId = screenId,
                                rowKey = "grid",
                                itemKey = item.seeAllStableKey(),
                                rowIndex = index / 4,
                                itemIndex = index,
                                targetType = "card",
                            )
                        }
                        val requester = rememberRegisteredTvFocusRequester(
                            controller = focusRestoreController,
                            target = target,
                            externalRequester = baseRequester,
                        )
                        if (index == 0 && sortOptions.isEmpty()) {
                            onFirstContentRequester(requester)
                        }

                        SeeAllPosterCard(
                            item = item,
                            modifier = Modifier
                                .width(170.dp)
                                .aspectRatio(2f / 3f)
                                .focusRequester(requester)
                                .focusProperties {
                                    if (index % 4 == 0) {
                                        left = railFocusRequester
                                    }
                                    if (index < 4 && sortOptions.isNotEmpty()) {
                                        up = sortRequesters[selectedSortIndex]
                                    }
                                },
                            onFocused = {
                                focusRestoreController.markFocused(target)
                                onContentFocused(requester)
                                focusedMediaItem = item
                                lastFocusedIndex = index
                            },
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
    } // Row

}

private enum class TvSeeAllSortKey {
    DEFAULT,
    RATING_DESC,
    TITLE_ASC,
    TITLE_DESC,
    NEWEST_RELEASE,
    OLDEST_RELEASE,
}

private data class TvSeeAllSortOption(
    val key: TvSeeAllSortKey,
    val label: String,
)

private fun sortOptionsForRail(railKey: String): List<TvSeeAllSortOption> {
    val firstLabel = if (railKey.startsWith("continue_watching")) "Recent Viewed" else "Default"
    return listOf(
        TvSeeAllSortOption(TvSeeAllSortKey.DEFAULT, firstLabel),
        TvSeeAllSortOption(TvSeeAllSortKey.RATING_DESC, "IMDb Rating"),
        TvSeeAllSortOption(TvSeeAllSortKey.TITLE_ASC, "A-Z"),
        TvSeeAllSortOption(TvSeeAllSortKey.TITLE_DESC, "Z-A"),
        TvSeeAllSortOption(TvSeeAllSortKey.NEWEST_RELEASE, "Newest Release"),
        TvSeeAllSortOption(TvSeeAllSortKey.OLDEST_RELEASE, "Oldest Release"),
    )
}

private fun sortSeeAllItems(
    items: List<MediaItem>,
    sortKey: TvSeeAllSortKey,
): List<MediaItem> {
    fun MediaItem.latestKnownReleaseDate(): String =
        seasons.mapNotNull { it.airDate }.maxOrNull()
            ?: releaseDate
            ?: year?.toString()
            ?: ""
    fun MediaItem.normalizedTitle(): String = title.trim().lowercase()

    return when (sortKey) {
        TvSeeAllSortKey.DEFAULT -> items
        TvSeeAllSortKey.RATING_DESC -> items.sortedWith(
            compareByDescending<MediaItem> {
                it.ratings?.imdbScore ?: it.rating ?: 0.0
            }.thenBy { it.normalizedTitle() },
        )
        TvSeeAllSortKey.TITLE_ASC -> items.sortedWith(compareBy<MediaItem> { it.normalizedTitle() }.thenBy { it.id })
        TvSeeAllSortKey.TITLE_DESC -> items.sortedWith(compareByDescending<MediaItem> { it.normalizedTitle() }.thenBy { it.id })
        TvSeeAllSortKey.NEWEST_RELEASE -> items.sortedWith(
            compareByDescending<MediaItem> { it.latestKnownReleaseDate() }.thenBy { it.normalizedTitle() },
        )
        TvSeeAllSortKey.OLDEST_RELEASE -> items.sortedWith(
            compareBy<MediaItem> { it.latestKnownReleaseDate() }.thenBy { it.normalizedTitle() },
        )
    }
}

private fun MediaItem.seeAllStableKey(): String = "${type.name}:${tmdbId ?: id}"

@Composable
private fun TvSeeAllSortButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected -> Amber
            focused -> Charcoal.copy(alpha = 0.95f)
            else -> Charcoal.copy(alpha = 0.75f)
        },
        label = "tvSeeAllSortBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) Obsidian else Snow,
        label = "tvSeeAllSortText",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "tvSeeAllSortBorder",
    )

    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .border(2.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            fontWeight = FontWeight.Medium,
        )
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
    }
}
