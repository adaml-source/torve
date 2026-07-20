package com.torve.android.tv.screens

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.torve.android.catalog.VodBootstrapShelf
import com.torve.android.catalog.VodBootstrapShelfEntry
import com.torve.android.tv.TV_PAGE_TOP_GUTTER
import com.torve.android.tv.TvScreenCache
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.data.auth.AuthClient
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.stableChannelId
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.domain.repository.ChannelRepository
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.VodCategoryTypeCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

private const val VOD_CACHE_KEY = "library_vod_tivimate_v6"
private const val VOD_BOOTSTRAP_CATEGORIES_PREFIX = "vod_bootstrap_categories_v1_"
private const val VOD_BOOTSTRAP_DISPLAY_PREFIX = "vod_bootstrap_display_v1_"
private const val CHANNELS_BOOTSTRAP_SELECTED_PLAYLIST_PREFIX = "channels_bootstrap_selected_playlist_"
private const val KEY_CHANNELS_SELECTED_PLAYLIST = "channels_selected_playlist"
private const val MAX_SEARCH_ITEMS = 160
private const val MAX_VOD_SHELF_ITEMS = 180

private val VodCategoryColumnWidth = 252.dp
private val VodContentGap = 26.dp
private val VodPreviewHeight = 184.dp
private val VodPosterWidth = 156.dp
private val VodPosterRowHeight = 320.dp
private val VodCategoryRowHeight = 44.dp
private val VodDisplayCacheJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

private data class TvVodLibraryUiState(
    val playlistId: String? = null,
    val loading: Boolean = true,
    val categories: List<TvVodCategory> = emptyList(),
    val error: String? = null,
)

@Serializable
private data class TvVodCategory(
    val id: String,
    val label: String,
    val rawNames: List<String>,
    val count: Long,
    val type: VodCategoryType,
    val language: String? = null,
    val pinned: Boolean = false,
    val movieCount: Long = count,
    val showCount: Long = count,
)

private enum class VodCategoryType {
    FAVORITES,
    ALL_MOVIES,
    ALL_SHOWS,
    PROVIDER,
}

private data class TvVodEntry(
    val sourceId: String,
    val sourceOrder: Int,
    val channel: Channel,
    val item: MediaItem,
    val searchTitle: String,
    val language: String?,
    val category: String,
) {
    val mediaType: MediaType get() = item.type
}

private enum class VodFocusZone { CATEGORIES, POSTERS }

private enum class VodMediaSection(val label: String, val mediaType: MediaType) {
    MOVIES("Movies", MediaType.MOVIE),
    SHOWS("Shows", MediaType.SERIES),
}

private enum class VodYearFilter(val label: String) {
    ALL("All years"),
    Y2020S("2020s"),
    Y2010S("2010s"),
    Y2000S("2000s"),
    OLDER("Older"),
}

@Composable
internal fun TvVodLibraryContent(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    onVodItemPlay: (Channel, MediaItem) -> Unit,
    onVodSeriesOpen: (Channel, MediaItem) -> Unit = onVodItemPlay,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)?,
    shouldAutoFocus: Boolean,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)?,
) {
    val channelRepo: ChannelRepository = koinInject()
    val prefsRepo: PreferencesRepository = koinInject()
    val localSettingsRepo: DeviceLocalSettingsRepository = koinInject()
    val firstCategoryRequester = remember { FocusRequester() }
    val selectedCategoryRequester = remember { FocusRequester() }
    val firstPosterRequester = remember { FocusRequester() }
    val searchRequester = remember { FocusRequester() }
    val firstYearFilterRequester = remember { FocusRequester() }
    val firstSectionRequester = remember { FocusRequester() }
    val categoryListState = rememberLazyListState()
    val posterListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var uiState by remember {
        mutableStateOf(TvScreenCache.get<TvVodLibraryUiState>(VOD_CACHE_KEY) ?: TvVodLibraryUiState())
    }
    var selectedMediaSection by remember { mutableStateOf(VodMediaSection.MOVIES) }
    var searchQuery by remember { mutableStateOf("") }
    var yearFilter by remember { mutableStateOf(VodYearFilter.ALL) }
    var focusedCategoryIndex by remember { mutableIntStateOf(0) }
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var focusZone by remember { mutableStateOf(VodFocusZone.CATEGORIES) }
    var loadedEntriesByCategoryId by remember(uiState.playlistId) {
        mutableStateOf<Map<String, List<TvVodEntry>>>(emptyMap())
    }
    var enrichedVodSourceIds by remember(uiState.playlistId) { mutableStateOf<Set<String>>(emptySet()) }
    var searchEntries by remember(uiState.playlistId) { mutableStateOf<List<TvVodEntry>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var favoriteChannelIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loadingCategoryKeys by remember(uiState.playlistId) { mutableStateOf<Set<String>>(emptySet()) }
    var focusedEntry by remember { mutableStateOf<TvVodEntry?>(null) }
    var lastFocusedSourceByCategoryId by remember(uiState.playlistId) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var pendingCategoryFocusRestoreId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.playlistId) {
        favoriteChannelIds = withContext(Dispatchers.IO) {
            channelRepo.getFavorites()
                .filter(::isVodChannel)
                .map(::stableChannelId)
                .toSet()
        }
    }

    LaunchedEffect(Unit) {
        val bootstrapUserId = withContext(Dispatchers.IO) { currentBootstrapUserId(localSettingsRepo) }
        val playlistId = withContext(Dispatchers.IO) {
            resolveSelectedVodPlaylistId(channelRepo, prefsRepo, localSettingsRepo, bootstrapUserId)
        }
        if (playlistId != null && bootstrapUserId != null) {
            withContext(Dispatchers.IO) {
                localSettingsRepo.setString(channelsBootstrapSelectedPlaylistKey(bootstrapUserId), playlistId)
            }
        }
        val cached = TvScreenCache.get<TvVodLibraryUiState>(VOD_CACHE_KEY)
        if (cached != null && cached.playlistId == playlistId && cached.categories.isNotEmpty()) {
            uiState = cached.copy(loading = false)
            return@LaunchedEffect
        }
        val bootstrappedCategories = if (playlistId != null && bootstrapUserId != null) {
            withContext(Dispatchers.IO) { readVodCategoryBootstrap(localSettingsRepo, bootstrapUserId, playlistId) }
        } else {
            emptyList()
        }
        val categories = if (playlistId != null) {
            bootstrappedCategories.ifEmpty {
                withContext(Dispatchers.IO) {
                    val databaseCategories = buildTvVodCategories(channelRepo.getVodCategoryTypeCounts(playlistId))
                    if (bootstrapUserId != null && databaseCategories.isNotEmpty()) {
                        writeVodCategoryBootstrap(localSettingsRepo, bootstrapUserId, playlistId, databaseCategories)
                    }
                    databaseCategories
                }
            }
        } else {
            emptyList()
        }
        if (playlistId != null && categories.isNotEmpty()) {
            val bootstrappedState = TvVodLibraryUiState(
                playlistId = playlistId,
                loading = false,
                categories = categories,
                error = null,
            )
            uiState = bootstrappedState
            TvScreenCache.put(VOD_CACHE_KEY, bootstrappedState)
            return@LaunchedEffect
        }
        uiState = if (playlistId == null) {
            TvVodLibraryUiState(loading = false, error = "No IPTV playlist with VOD found")
        } else {
            TvVodLibraryUiState(
                playlistId = playlistId,
                loading = false,
                error = "No locally cached VOD items found yet.",
            )
        }
    }

    val displayedCategories = remember(uiState.categories, selectedMediaSection) {
        filterVodCategoriesForSection(uiState.categories, selectedMediaSection)
    }

    LaunchedEffect(displayedCategories, selectedCategoryIndex, focusedCategoryIndex) {
        if (displayedCategories.isEmpty()) return@LaunchedEffect
        selectedCategoryIndex = selectedCategoryIndex.coerceIn(0, displayedCategories.lastIndex)
        focusedCategoryIndex = focusedCategoryIndex.coerceIn(0, displayedCategories.lastIndex)
    }

    val selectedCategory = displayedCategories.getOrNull(selectedCategoryIndex)
    val selectedCategoryCacheKey = selectedCategory?.cacheKey(selectedMediaSection)
    val trimmedSearchQuery = searchQuery.trim()
    val isSearchActive = trimmedSearchQuery.length >= 2
    val selectedEntries = selectedCategoryCacheKey?.let { loadedEntriesByCategoryId[it] }.orEmpty()
    val activeEntries = if (isSearchActive) searchEntries else selectedEntries
    val visibleEntries = remember(activeEntries, yearFilter) { activeEntries.filterByYear(yearFilter).dedupeForDisplay() }
    val visibleEntryIds = remember(visibleEntries) { visibleEntries.map { it.sourceId } }
    val activeShelfKey = if (isSearchActive) {
        "search:${selectedMediaSection.name}:${trimmedSearchQuery.lowercase()}:${yearFilter.name}"
    } else {
        selectedCategoryCacheKey
    }
    val activeShelfLabel = if (isSearchActive) {
        "Search: $trimmedSearchQuery"
    } else {
        selectedCategory?.label.orEmpty()
    }
    val isSelectedCategoryLoading = if (isSearchActive) searchLoading else selectedCategoryCacheKey in loadingCategoryKeys

    suspend fun loadVodCategoryIntoCache(
        playlistId: String,
        category: TvVodCategory,
        mediaSection: VodMediaSection,
    ) {
        val cacheKey = category.cacheKey(mediaSection)
        if (loadedEntriesByCategoryId.containsKey(cacheKey) || cacheKey in loadingCategoryKeys) return
        loadingCategoryKeys = loadingCategoryKeys + cacheKey
        try {
            val displayEntries = withContext(Dispatchers.IO) {
                val bootstrapped = readVodBootstrapShelf(localSettingsRepo, playlistId, cacheKey)
                if (bootstrapped.isNullOrEmpty()) {
                    loadVodShelfFromDatabase(channelRepo, playlistId, category, mediaSection)
                } else {
                    bootstrapped
                }
            }
            enrichedVodSourceIds = enrichedVodSourceIds + displayEntries.map { it.sourceId }
            if (!loadedEntriesByCategoryId.containsKey(cacheKey)) {
                loadedEntriesByCategoryId = loadedEntriesByCategoryId + (cacheKey to displayEntries)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Throwable) {
            loadedEntriesByCategoryId = loadedEntriesByCategoryId + (cacheKey to emptyList())
        } finally {
            loadingCategoryKeys = loadingCategoryKeys - cacheKey
        }
    }

    fun enterPostersIfReady() {
        if (visibleEntries.isNotEmpty()) {
            focusZone = VodFocusZone.POSTERS
            val targetSourceId = activeShelfKey
                ?.let { lastFocusedSourceByCategoryId[it] }
                ?: visibleEntries.firstOrNull()?.sourceId
            val targetIndex = targetSourceId
                ?.let { sourceId -> visibleEntries.indexOfFirst { it.sourceId == sourceId } }
                ?.takeIf { it >= 0 }
            coroutineScope.launch {
                targetIndex?.let { index -> posterListState.scrollToItem(index) }
                runCatching { firstPosterRequester.requestFocus() }
            }
        }
    }

    BackHandler(enabled = focusZone == VodFocusZone.POSTERS) {
        pendingCategoryFocusRestoreId = selectedCategory?.id
        focusZone = VodFocusZone.CATEGORIES
    }

    LaunchedEffect(selectedCategory?.id, selectedMediaSection, uiState.playlistId) {
        val playlistId = uiState.playlistId ?: return@LaunchedEffect
        val category = selectedCategory ?: return@LaunchedEffect
        loadVodCategoryIntoCache(playlistId, category, selectedMediaSection)
        val nextCategories = listOfNotNull(
            displayedCategories.getOrNull(selectedCategoryIndex + 1),
            displayedCategories.getOrNull(selectedCategoryIndex - 1),
        )
        nextCategories.forEach { neighbor ->
            loadVodCategoryIntoCache(playlistId, neighbor, selectedMediaSection)
        }
    }

    LaunchedEffect(trimmedSearchQuery, selectedMediaSection, uiState.playlistId) {
        val playlistId = uiState.playlistId ?: return@LaunchedEffect
        if (trimmedSearchQuery.length < 2) {
            searchEntries = emptyList()
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        try {
            val entries = withContext(Dispatchers.IO) {
                searchVodEntries(
                    channelRepo = channelRepo,
                    playlistId = playlistId,
                    query = trimmedSearchQuery,
                    mediaSection = selectedMediaSection,
                )
            }
            searchEntries = entries
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Throwable) {
            searchEntries = emptyList()
        } finally {
            searchLoading = false
        }
    }

    LaunchedEffect(visibleEntryIds, activeShelfKey) {
        val categoryId = activeShelfKey
        val rememberedSourceId = categoryId?.let { lastFocusedSourceByCategoryId[it] }
        val nextFocused = rememberedSourceId
            ?.let { sourceId -> visibleEntries.firstOrNull { it.sourceId == sourceId } }
            ?: focusedEntry
            ?.sourceId
            ?.let { sourceId -> visibleEntries.firstOrNull { it.sourceId == sourceId } }
            ?: visibleEntries.firstOrNull()
        if (nextFocused != null) {
            focusedEntry = nextFocused
            onMediaFocused?.invoke(nextFocused.item)
            val index = visibleEntries.indexOfFirst { it.sourceId == nextFocused.sourceId }
            if (index >= 0) runCatching { posterListState.scrollToItem(index) }
        }
    }

    LaunchedEffect(shouldAutoFocus, uiState.categories) {
        if (!shouldAutoFocus || displayedCategories.isEmpty() || focusZone != VodFocusZone.CATEGORIES) return@LaunchedEffect
        onFirstContentRequester(firstCategoryRequester)
        kotlinx.coroutines.delay(50)
        runCatching { firstCategoryRequester.requestFocus() }
    }

    LaunchedEffect(focusZone, pendingCategoryFocusRestoreId, displayedCategories) {
        if (focusZone != VodFocusZone.CATEGORIES) return@LaunchedEffect
        val restoreId = pendingCategoryFocusRestoreId ?: return@LaunchedEffect
        if (displayedCategories.isEmpty()) return@LaunchedEffect
        val restoreIndex = displayedCategories.indexOfFirst { it.id == restoreId }
            .takeIf { it >= 0 }
            ?: selectedCategoryIndex.coerceIn(0, displayedCategories.lastIndex)
        if (restoreIndex != selectedCategoryIndex && restoreIndex in displayedCategories.indices) {
            selectedCategoryIndex = restoreIndex
        }
        kotlinx.coroutines.delay(50)
        val requester = if (restoreIndex == 0) firstCategoryRequester else selectedCategoryRequester
        runCatching { categoryListState.scrollToItem(restoreIndex) }
        runCatching { requester.requestFocus() }
        pendingCategoryFocusRestoreId = null
    }

    registerFocusHandle?.invoke(null)

    when {
        uiState.loading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(firstCategoryRequester)
                    .focusProperties { left = railFocusRequester },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Amber)
            }
        }

        uiState.categories.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(firstCategoryRequester)
                    .focusProperties { left = railFocusRequester },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = uiState.error ?: "No VOD items found",
                    style = MaterialTheme.typography.titleLarge,
                    color = Silver,
                )
            }
        }

        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Obsidian.copy(alpha = 0.78f),
                                Obsidian.copy(alpha = 0.68f),
                                Obsidian.copy(alpha = 0.52f),
                            ),
                        ),
                    )
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Obsidian.copy(alpha = 0.28f),
                                Obsidian.copy(alpha = 0.56f),
                                Obsidian.copy(alpha = 0.78f),
                            ),
                        ),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 32.dp, end = 28.dp, top = TV_PAGE_TOP_GUTTER, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (focusZone == VodFocusZone.CATEGORIES) VodContentGap else 0.dp),
                ) {
                    if (focusZone == VodFocusZone.CATEGORIES) {
                        Column(
                            modifier = Modifier
                                .width(VodCategoryColumnWidth)
                                .fillMaxHeight(),
                        ) {
                            TvVodSearchAndFilters(
                                query = searchQuery,
                                onQueryChange = {
                                    searchQuery = it
                                    if (focusZone != VodFocusZone.CATEGORIES) focusZone = VodFocusZone.CATEGORIES
                                },
                                yearFilter = yearFilter,
                                onYearFilterSelected = { filter -> yearFilter = filter },
                                searchRequester = searchRequester,
                                firstYearFilterRequester = firstYearFilterRequester,
                                sectionRequester = firstSectionRequester,
                                headerFocusRequester = headerFocusRequester,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(Modifier.height(10.dp))

                            TvVodSectionToggle(
                                selected = selectedMediaSection,
                                firstRequester = firstSectionRequester,
                                yearFilterRequester = firstYearFilterRequester,
                                onSelected = { section ->
                                    if (selectedMediaSection != section) {
                                        selectedMediaSection = section
                                        selectedCategoryIndex = 0
                                        focusedEntry = null
                                        pendingCategoryFocusRestoreId = null
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(Modifier.height(10.dp))

                            TvVodCategoryList(
                                categories = displayedCategories,
                                selectedIndex = selectedCategoryIndex,
                                listState = categoryListState,
                                firstRequester = firstCategoryRequester,
                                selectedRequester = selectedCategoryRequester,
                                railFocusRequester = railFocusRequester,
                                posterRequester = firstPosterRequester,
                                onFirstContentRequester = onFirstContentRequester,
                                onContentFocused = onContentFocused,
                                onSelect = { index ->
                                    focusedCategoryIndex = index
                                    if (selectedCategoryIndex != index) {
                                        selectedCategoryIndex = index
                                    }
                                },
                                onEnterPosters = { index ->
                                    selectedCategoryIndex = index
                                    enterPostersIfReady()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        TvVodPreviewPanel(
                            entry = focusedEntry,
                            categoryLabel = activeShelfLabel,
                            loading = isSelectedCategoryLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(VodPreviewHeight),
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (focusZone == VodFocusZone.POSTERS) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(20.dp)
                                        .background(Amber, RoundedCornerShape(2.dp)),
                                )
                            }
                            Text(
                                text = activeShelfLabel,
                                color = Snow,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        TvVodPosterStrip(
                            entries = visibleEntries,
                            listState = posterListState,
                            firstRequester = firstPosterRequester,
                            categoryRequester = firstCategoryRequester,
                            loading = isSelectedCategoryLoading,
                            focusZone = focusZone,
                            focusedSourceId = focusedEntry?.sourceId,
                            favoriteChannelIds = favoriteChannelIds,
                            onContentFocused = onContentFocused,
                            onFocused = { entry ->
                                focusedEntry = entry
                                activeShelfKey?.let { categoryId ->
                                    lastFocusedSourceByCategoryId =
                                        lastFocusedSourceByCategoryId + (categoryId to entry.sourceId)
                                }
                                onMediaFocused?.invoke(entry.item)
                            },
                            onFavoriteToggle = { entry ->
                                coroutineScope.launch {
                                    val channelId = stableChannelId(entry.channel)
                                    if (channelId in favoriteChannelIds) {
                                        withContext(Dispatchers.IO) { channelRepo.removeFavorite(channelId) }
                                        favoriteChannelIds = favoriteChannelIds - channelId
                                        uiState = uiState.withFavoriteDelta(-1).also { TvScreenCache.put(VOD_CACHE_KEY, it) }
                                        if (selectedCategory?.type == VodCategoryType.FAVORITES && activeShelfKey != null) {
                                            loadedEntriesByCategoryId = loadedEntriesByCategoryId + (
                                                activeShelfKey to visibleEntries.filterNot { it.sourceId == entry.sourceId }
                                                )
                                        }
                                    } else {
                                        withContext(Dispatchers.IO) { channelRepo.addFavorite(entry.channel) }
                                        favoriteChannelIds = favoriteChannelIds + channelId
                                        uiState = uiState.withFavoriteDelta(1).also { TvScreenCache.put(VOD_CACHE_KEY, it) }
                                        if (selectedCategory?.type == VodCategoryType.FAVORITES && activeShelfKey != null) {
                                            loadedEntriesByCategoryId = loadedEntriesByCategoryId + (activeShelfKey to (visibleEntries + entry))
                                        }
                                    }
                                }
                            },
                            onClick = { entry ->
                                if (entry.channel.contentType == ChannelContentType.VOD_SERIES) {
                                    onVodSeriesOpen(entry.channel, entry.item)
                                } else {
                                    onVodItemPlay(entry.channel, entry.item)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvVodSeriesEpisodePickerOverlay(
    entry: TvVodEntry,
    episodes: List<Channel>,
    loading: Boolean,
    error: String?,
    onClose: () -> Unit,
    onEpisodeSelected: (Channel) -> Unit,
) {
    val seasons = remember(episodes) {
        episodes
            .mapNotNull { it.kodiProps["vod_season_number"]?.toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .sorted()
            .ifEmpty { listOf(1) }
    }
    var selectedSeason by remember(entry.sourceId, seasons) { mutableIntStateOf(seasons.firstOrNull() ?: 1) }
    val visibleEpisodes = remember(episodes, selectedSeason) {
        val seasonEpisodes = episodes.filter {
            (it.kodiProps["vod_season_number"]?.toIntOrNull() ?: 1) == selectedSeason
        }
        if (seasonEpisodes.isNotEmpty()) seasonEpisodes else episodes
    }
    val firstEpisodeRequester = remember(selectedSeason, visibleEpisodes.size) { FocusRequester() }

    LaunchedEffect(visibleEpisodes, loading) {
        if (!loading && visibleEpisodes.isNotEmpty()) {
            kotlinx.coroutines.delay(120)
            runCatching { firstEpisodeRequester.requestFocus() }
        }
    }

    BackHandler(enabled = true) { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Obsidian.copy(alpha = 0.94f))
            .padding(horizontal = 64.dp, vertical = 44.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = entry.item.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Snow,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Choose an episode",
                        style = MaterialTheme.typography.titleMedium,
                        color = Silver,
                    )
                }
                TvVodEpisodeActionChip(
                    text = "Close",
                    onClick = onClose,
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(end = 16.dp),
            ) {
                itemsIndexed(seasons, key = { _, season -> "vod_season_$season" }) { _, season ->
                    TvVodEpisodeActionChip(
                        text = "Season $season",
                        selected = season == selectedSeason,
                        onClick = { selectedSeason = season },
                    )
                }
            }

            when {
                loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Amber)
                    }
                }

                error != null -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.titleLarge,
                        color = Silver,
                    )
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 48.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(
                            visibleEpisodes,
                            key = { index, episode ->
                                episode.kodiProps["vod_episode_id"] ?: "${episode.url}_$index"
                            },
                        ) { index, episode ->
                            TvVodEpisodeRow(
                                episode = episode,
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstEpisodeRequester)
                                } else {
                                    Modifier
                                },
                                onClick = { onEpisodeSelected(episode) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvVodEpisodeActionChip(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            selected -> Amber
            else -> Steel.copy(alpha = 0.28f)
        },
        label = "vodEpisodeActionBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            focused -> Graphite
            selected -> Amber.copy(alpha = 0.16f)
            else -> Charcoal.copy(alpha = 0.64f)
        },
        label = "vodEpisodeActionBg",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.hasFocus }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused || selected) Snow else Silver,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TvVodEpisodeRow(
    episode: Channel,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val episodeNumber = episode.kodiProps["vod_episode_number"]?.toIntOrNull()
    val title = episode.tvgName?.takeIf { it.isNotBlank() } ?: episode.name
    val plot = episode.kodiProps["vod_episode_plot"]
    val rating = episode.kodiProps["vod_episode_rating"]?.toDoubleOrNull()
    val durationMinutes = episode.duration.takeIf { it > 0 }?.let { it / 60 }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Graphite else Charcoal.copy(alpha = 0.72f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) AmberLight else Steel.copy(alpha = 0.22f),
                shape = RoundedCornerShape(14.dp),
            )
            .onFocusChanged { focused = it.hasFocus }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(Obsidian),
        ) {
            AsyncImage(
                model = episode.tvgLogo,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            episodeNumber?.let { number ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Obsidian.copy(alpha = 0.78f))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "E$number",
                        color = Snow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Snow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val metadata = buildList {
                durationMinutes?.takeIf { it > 0 }?.let { add("${it}m") }
                rating?.takeIf { it > 0.0 }?.let { add("Rating ${"%.1f".format(it)}") }
            }.joinToString(" • ")
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = AmberLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            plot?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvVodSearchAndFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    yearFilter: VodYearFilter,
    onYearFilterSelected: (VodYearFilter) -> Unit,
    searchRequester: FocusRequester,
    firstYearFilterRequester: FocusRequester,
    sectionRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val context = LocalContext.current
        val view = LocalView.current
        var focused by remember { mutableStateOf(false) }
        var editMode by remember { mutableStateOf(false) }
        val borderColor by animateColorAsState(
            targetValue = when {
                editMode -> AmberLight
                focused -> Amber.copy(alpha = 0.95f)
                else -> Silver.copy(alpha = 0.18f)
            },
            label = "vodSearchBorder",
        )
        fun showKeyboard() {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
        fun hideKeyboard() {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }

        LaunchedEffect(editMode) {
            if (editMode) {
                kotlinx.coroutines.delay(50)
                showKeyboard()
            }
        }

        BasicTextField(
            value = query,
            onValueChange = { value ->
                if (editMode) onQueryChange(value)
            },
            readOnly = !editMode,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Snow, fontSize = 14.sp),
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .focusRequester(searchRequester)
                .focusProperties {
                    headerFocusRequester?.let { up = it }
                    down = firstYearFilterRequester
                }
                .clip(RoundedCornerShape(10.dp))
                .background(Charcoal.copy(alpha = if (focused) 0.42f else 0.24f))
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused && editMode) {
                        editMode = false
                        hideKeyboard()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            if (editMode) {
                                editMode = false
                                hideKeyboard()
                            }
                            headerFocusRequester?.let { requester ->
                                runCatching { requester.requestFocus() }
                                true
                            } ?: false
                        }

                        Key.DirectionDown -> {
                            if (editMode) {
                                editMode = false
                                hideKeyboard()
                            }
                            runCatching { firstYearFilterRequester.requestFocus() }
                            true
                        }

                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (!editMode) {
                                editMode = true
                                true
                            } else {
                                false
                            }
                        }

                        Key.Back -> {
                            if (editMode) {
                                editMode = false
                                hideKeyboard()
                                true
                            } else {
                                false
                            }
                        }

                        else -> false
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { editMode = true },
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isBlank()) {
                        Text(
                            text = if (editMode) "Type to search" else "Search VOD",
                            color = Silver.copy(alpha = 0.58f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VodYearFilter.entries.forEachIndexed { index, filter ->
                TvVodFilterChip(
                    label = filter.label,
                    selected = yearFilter == filter,
                    onClick = { onYearFilterSelected(filter) },
                    modifier = Modifier
                        .weight(1f)
                        .then(if (index == 0) Modifier.focusRequester(firstYearFilterRequester) else Modifier)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> {
                                    runCatching { searchRequester.requestFocus() }
                                    true
                                }
                                Key.DirectionDown -> {
                                    runCatching { sectionRequester.requestFocus() }
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun TvVodFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        targetValue = when {
            focused -> Graphite.copy(alpha = 0.48f)
            selected -> Amber.copy(alpha = 0.14f)
            else -> Charcoal.copy(alpha = 0.16f)
        },
        label = "vodFilterBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused || selected) Amber.copy(alpha = if (focused) 0.9f else 0.45f) else Color.Transparent,
        label = "vodFilterBorder",
    )
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused || selected) Snow else Silver,
            fontSize = 10.sp,
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp),
        )
    }
}

@Composable
private fun TvVodSectionToggle(
    selected: VodMediaSection,
    firstRequester: FocusRequester,
    yearFilterRequester: FocusRequester,
    onSelected: (VodMediaSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VodMediaSection.entries.forEachIndexed { index, section ->
            var focused by remember(section) { mutableStateOf(false) }
            val selectedOrFocused = selected == section || focused
            val bgColor by animateColorAsState(
                targetValue = when {
                    focused -> Graphite.copy(alpha = 0.50f)
                    selected == section -> Amber.copy(alpha = 0.14f)
                    else -> Charcoal.copy(alpha = 0.20f)
                },
                label = "vodSectionBg",
            )
            val borderColor by animateColorAsState(
                targetValue = if (selectedOrFocused) Amber.copy(alpha = if (focused) 0.95f else 0.55f) else Color.Transparent,
                label = "vodSectionBorder",
            )
            val interactionSource = remember(section) { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .then(if (index == 0) Modifier.focusRequester(firstRequester) else Modifier)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            runCatching { yearFilterRequester.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onSelected(section) },
                    )
                    .focusable(interactionSource = interactionSource),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = section.label,
                    color = if (selectedOrFocused) Snow else Silver,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selectedOrFocused) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvVodCategoryList(
    categories: List<TvVodCategory>,
    selectedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    firstRequester: FocusRequester,
    selectedRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    posterRequester: FocusRequester,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onSelect: (Int) -> Unit,
    onEnterPosters: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
            val requester = when {
                index == 0 -> firstRequester
                index == selectedIndex -> selectedRequester
                else -> remember(category.id) { FocusRequester() }
            }
            if (index == 0) onFirstContentRequester(requester)
            TvVodCategoryRow(
                category = category,
                selected = index == selectedIndex,
                modifier = Modifier
                    .focusRequester(requester)
                    .focusProperties {
                        left = railFocusRequester
                        right = posterRequester
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                            onEnterPosters(index)
                            true
                        } else {
                            false
                        }
                    },
                onFocused = {
                    onSelect(index)
                    onContentFocused(requester)
                },
                onClick = {
                    onEnterPosters(index)
                },
            )
        }
    }
}

@Composable
private fun TvVodCategoryRow(
    category: TvVodCategory,
    selected: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        targetValue = when {
            focused -> Graphite.copy(alpha = 0.58f)
            selected -> Charcoal.copy(alpha = 0.46f)
            else -> Charcoal.copy(alpha = 0.08f)
        },
        label = "vodCategoryBg",
    )
    val accentColor by animateColorAsState(
        targetValue = if (focused || selected) Amber else Color.Transparent,
        label = "vodCategoryAccent",
    )
    val textColor = when {
        focused -> AmberLight
        selected -> Snow
        else -> Snow.copy(alpha = 0.78f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(VodCategoryRowHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = 0.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accentColor),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = category.label,
            color = textColor,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (category.count > 0) {
            Text(
                text = category.count.toString(),
                color = Silver.copy(alpha = if (focused || selected) 0.78f else 0.56f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun TvVodPreviewPanel(
    entry: TvVodEntry?,
    categoryLabel: String,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val item = entry?.item
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Charcoal.copy(alpha = 0.34f)),
    ) {
        item?.backdropUrl?.takeIf { it.isNotBlank() }?.let { backdrop ->
            AsyncImage(
                model = backdrop,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Obsidian.copy(alpha = 0.96f),
                                Obsidian.copy(alpha = 0.84f),
                                Obsidian.copy(alpha = 0.70f),
                            ),
                        ),
                    )
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Obsidian.copy(alpha = 0.28f),
                                Obsidian.copy(alpha = 0.72f),
                            ),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item == null) {
                Text(
                    text = if (loading) "Loading..." else categoryLabel,
                    color = Silver,
                    style = MaterialTheme.typography.titleLarge,
                )
                return@Column
            }

            VodHeroTitle(item = item)
            VodMetadataLine(item = item)
            if (item.logoUrl.isNullOrBlank()) {
                VodPeopleLine(item = item)
            }
            VodRatingLine(item = item)
            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    color = Snow.copy(alpha = 0.82f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.overview.isNullOrBlank()) {
                Text(
                    text = entry.category,
                    color = Silver,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VodHeroTitle(item: MediaItem) {
    val logoUrl = item.logoUrl?.takeIf { it.isNotBlank() }
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    if (logoUrl != null && !logoFailed) {
        AsyncImage(
            model = logoUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { logoFailed = true },
            modifier = Modifier
                .width(360.dp)
                .height(52.dp),
        )
    } else {
        Text(
            text = item.title,
            color = Snow,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VodMetadataLine(item: MediaItem) {
    val runtime = item.runtime
    val parts = buildList {
        item.year?.let { add(it.toString()) }
        runtime?.takeIf { it > 0 }?.let { add("${it / 60}h ${it % 60}m") }
        if (runtime == null || runtime <= 0) {
            item.seasons.takeIf { it.isNotEmpty() }?.let { seasons ->
                add("${seasons.size} season${if (seasons.size == 1) "" else "s"}")
            }
        }
        item.genres.firstOrNull()?.name?.let { add(it) }
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString("  •  "),
        color = Silver,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun VodPeopleLine(item: MediaItem) {
    val parts = buildList {
        item.cast.takeIf { it.isNotEmpty() }?.let { cast ->
            add("Cast: ${cast.take(4).joinToString(", ") { it.name }}")
        }
        item.director?.takeIf { it.isNotBlank() }?.let { add("Director: $it") }
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString("  /  "),
        color = Silver.copy(alpha = 0.86f),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun VodRatingLine(item: MediaItem) {
    val rating = item.bestVodRating()
    if (rating == null || rating <= 0f) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(Amber.copy(alpha = 0.9f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "%.1f".format(rating),
                color = Obsidian,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TvVodPosterStrip(
    entries: List<TvVodEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    firstRequester: FocusRequester,
    categoryRequester: FocusRequester,
    loading: Boolean,
    focusZone: VodFocusZone,
    focusedSourceId: String?,
    favoriteChannelIds: Set<String>,
    onContentFocused: (FocusRequester) -> Unit,
    onFocused: (TvVodEntry) -> Unit,
    onFavoriteToggle: (TvVodEntry) -> Unit,
    onClick: (TvVodEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        loading && entries.isEmpty() -> {
            Box(
                modifier = modifier.height(VodPosterRowHeight),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Amber)
            }
        }

        entries.isEmpty() -> {
            Box(
                modifier = modifier.height(VodPosterRowHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No items in this category",
                    color = Silver,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        else -> {
            val focusedIndex = focusedSourceId
                ?.let { sourceId -> entries.indexOfFirst { it.sourceId == sourceId } }
                ?.takeIf { it >= 0 }
                ?: 0
            LazyRow(
                state = listState,
                modifier = modifier
                    .height(VodPosterRowHeight)
                    .onPreviewKeyEvent { event ->
                        if (focusZone != VodFocusZone.POSTERS || event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when (event.key) {
                            Key.DirectionUp, Key.DirectionDown -> true
                            Key.DirectionLeft -> focusedIndex <= 0
                            Key.DirectionRight -> focusedIndex >= entries.lastIndex
                            Key.Menu -> {
                                entries.getOrNull(focusedIndex)?.let(onFavoriteToggle)
                                true
                            }
                            else -> false
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(start = 14.dp, top = 20.dp, end = 22.dp, bottom = 26.dp),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.sourceId }) { index, entry ->
                    val isFocusRestoreTarget = entry.sourceId == focusedSourceId || (focusedSourceId == null && index == 0)
                    val requester = if (isFocusRestoreTarget) firstRequester else remember(entry.sourceId) { FocusRequester() }
                    TvVodPosterCard(
                        entry = entry,
                        favorite = stableChannelId(entry.channel) in favoriteChannelIds,
                        modifier = Modifier
                            .width(VodPosterWidth)
                            .aspectRatio(2f / 3f)
                            .focusRequester(requester)
                            .focusProperties {
                                if (focusZone == VodFocusZone.CATEGORIES && index == 0) left = categoryRequester
                            }
                            .onPreviewKeyEvent { event ->
                                if (
                                    focusZone == VodFocusZone.CATEGORIES &&
                                    event.type == KeyEventType.KeyDown &&
                                    event.key == Key.DirectionLeft &&
                                    index == 0
                                ) {
                                    runCatching { categoryRequester.requestFocus() }
                                    true
                                } else {
                                    false
                                }
                            },
                        onFocused = {
                            onContentFocused(requester)
                            onFocused(entry)
                        },
                        onClick = { onClick(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvVodPosterCard(
    entry: TvVodEntry,
    favorite: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.08f else 1f, label = "vodPosterScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "vodPosterBorder",
    )
    val posterDim by animateColorAsState(
        targetValue = if (focused) Color.Transparent else Obsidian.copy(alpha = 0.18f),
        label = "vodPosterDim",
    )

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .border(if (focused) 2.dp else 0.dp, borderColor, RoundedCornerShape(12.dp))
            .background(Charcoal)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        val item = entry.item
        val poster = item.posterUrl ?: item.backdropUrl
        if (poster.isNullOrBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = item.title,
                    color = Snow,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
        } else {
            AsyncImage(
                model = poster,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (posterDim != Color.Transparent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(posterDim),
            )
        }
        item.bestVodRating()?.takeIf { it > 0f }?.let { rating ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Amber.copy(alpha = 0.9f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "%.1f".format(rating),
                    color = Obsidian,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (favorite) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Obsidian.copy(alpha = 0.78f))
                    .border(1.dp, Amber.copy(alpha = 0.7f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "Fav",
                    color = AmberLight,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private suspend fun resolveSelectedVodPlaylistId(
    channelRepo: ChannelRepository,
    prefsRepo: PreferencesRepository,
    localSettingsRepo: DeviceLocalSettingsRepository,
    bootstrapUserId: String?,
): String? {
    val playlists = channelRepo.getPlaylists()
    val savedId = prefsRepo.getString(KEY_CHANNELS_SELECTED_PLAYLIST)
        ?: bootstrapUserId?.let { userId ->
            localSettingsRepo.getString(channelsBootstrapSelectedPlaylistKey(userId))
        }
    return savedId?.takeIf { id -> playlists.any { it.id == id } }
        ?: playlists.firstOrNull()?.id
}

private suspend fun currentBootstrapUserId(
    localSettingsRepo: DeviceLocalSettingsRepository,
): String? {
    return localSettingsRepo
        .getString(AuthClient.KEY_AUTH_USER_ID)
        ?.takeIf { it.isNotBlank() }
}

private fun channelsBootstrapSelectedPlaylistKey(userId: String): String {
    return "$CHANNELS_BOOTSTRAP_SELECTED_PLAYLIST_PREFIX$userId"
}

private fun vodCategoryBootstrapKey(userId: String, playlistId: String): String {
    return "$VOD_BOOTSTRAP_CATEGORIES_PREFIX${userId.hashCode()}_${playlistId.hashCode()}"
}

private fun vodDisplayShelfBootstrapKey(userId: String, playlistId: String, shelfKey: String): String {
    return "$VOD_BOOTSTRAP_DISPLAY_PREFIX${userId.hashCode()}_${playlistId.hashCode()}_${shelfKey.hashCode()}"
}

private suspend fun readVodCategoryBootstrap(
    localSettingsRepo: DeviceLocalSettingsRepository,
    userId: String,
    playlistId: String,
): List<TvVodCategory> {
    val raw = localSettingsRepo.getString(vodCategoryBootstrapKey(userId, playlistId)) ?: return emptyList()
    return runCatching {
        VodDisplayCacheJson.decodeFromString<List<TvVodCategory>>(raw)
    }.getOrDefault(emptyList())
}

private suspend fun writeVodCategoryBootstrap(
    localSettingsRepo: DeviceLocalSettingsRepository,
    userId: String,
    playlistId: String,
    categories: List<TvVodCategory>,
) {
    if (categories.isEmpty()) return
    runCatching {
        localSettingsRepo.setString(
            vodCategoryBootstrapKey(userId, playlistId),
            VodDisplayCacheJson.encodeToString(categories),
        )
    }
}

private fun TvVodCategory.cacheKey(section: VodMediaSection): String = "${section.name}:$id"

private data class TvVodCounts(
    val rawNames: Set<String> = emptySet(),
    val movieCount: Long = 0,
    val showCount: Long = 0,
)

private fun buildTvVodCategories(typeCounts: List<VodCategoryTypeCount>): List<TvVodCategory> {
    val grouped = linkedMapOf<String, TvVodCounts>()
    typeCounts.forEach { row ->
        val rawName = row.groupTitle.ifBlank { "VOD" }
        val label = cleanVodCategory(rawName)
        val existing = grouped[label] ?: TvVodCounts()
        grouped[label] = when (row.contentType) {
            ChannelContentType.VOD_MOVIE -> existing.copy(
                rawNames = existing.rawNames + rawName,
                movieCount = existing.movieCount + row.count,
            )
            ChannelContentType.VOD_SERIES -> existing.copy(
                rawNames = existing.rawNames + rawName,
                showCount = existing.showCount + row.count,
            )
            else -> existing
        }
    }
    val movieTotal = grouped.values.sumOf { it.movieCount }
    val showTotal = grouped.values.sumOf { it.showCount }
    return buildList {
        add(
            TvVodCategory(
                id = "favorites",
                label = "Favorites",
                rawNames = emptyList(),
                count = 0,
                type = VodCategoryType.FAVORITES,
                pinned = true,
                movieCount = 0,
                showCount = 0,
            ),
        )
        if (movieTotal > 0) {
            add(
                TvVodCategory(
                    id = "all_movies",
                    label = "All movies",
                    rawNames = grouped.values.flatMap { it.rawNames },
                    count = movieTotal,
                    type = VodCategoryType.ALL_MOVIES,
                    pinned = true,
                    movieCount = movieTotal,
                    showCount = 0,
                ),
            )
        }
        if (showTotal > 0) {
            add(
                TvVodCategory(
                    id = "all_shows",
                    label = "All shows",
                    rawNames = grouped.values.flatMap { it.rawNames },
                    count = showTotal,
                    type = VodCategoryType.ALL_SHOWS,
                    pinned = true,
                    movieCount = 0,
                    showCount = showTotal,
                ),
            )
        }
        addAll(
            grouped.map { (label, counts) ->
                TvVodCategory(
                    id = "category:${label.hashCode()}",
                    label = label,
                    rawNames = counts.rawNames.toList(),
                    count = counts.movieCount + counts.showCount,
                    type = VodCategoryType.PROVIDER,
                    movieCount = counts.movieCount,
                    showCount = counts.showCount,
                )
            }.filter { it.count > 0 }
                .sortedBy { it.label.lowercase() },
        )
    }
}

private suspend fun loadVodShelfFromDatabase(
    channelRepo: ChannelRepository,
    playlistId: String,
    category: TvVodCategory,
    section: VodMediaSection,
): List<TvVodEntry> {
    val type = section.channelContentType
    val channels = when (category.type) {
        VodCategoryType.FAVORITES -> channelRepo.getFavorites()
            .asSequence()
            .filter { it.playlistId == playlistId && it.contentType == type }
            .take(MAX_VOD_SHELF_ITEMS)
            .toList()
        VodCategoryType.ALL_MOVIES,
        VodCategoryType.ALL_SHOWS -> channelRepo.getChannelsForContentType(
            playlistId = playlistId,
            type = type,
            limit = MAX_VOD_SHELF_ITEMS,
        )
        VodCategoryType.PROVIDER -> {
            val loaded = mutableListOf<Channel>()
            for (rawName in category.rawNames) {
                val remaining = MAX_VOD_SHELF_ITEMS - loaded.size
                if (remaining <= 0) break
                loaded += channelRepo.getChannelsForCategoryContentType(
                    playlistId = playlistId,
                    categoryName = rawName,
                    type = type,
                    limit = remaining,
                )
            }
            loaded
        }
    }
    return channels
        .distinctBy { (it.kodiProps["vod_stream_id"] ?: it.kodiProps["vod_series_id"] ?: it.url) }
        .mapIndexed { index, channel -> channel.toVodEntry(index) }
}

private val VodMediaSection.channelContentType: ChannelContentType
    get() = when (this) {
        VodMediaSection.MOVIES -> ChannelContentType.VOD_MOVIE
        VodMediaSection.SHOWS -> ChannelContentType.VOD_SERIES
    }

private suspend fun readVodBootstrapShelf(
    localSettingsRepo: DeviceLocalSettingsRepository,
    playlistId: String,
    shelfKey: String,
): List<TvVodEntry>? {
    val userId = currentBootstrapUserId(localSettingsRepo) ?: return null
    val raw = localSettingsRepo.getString(vodDisplayShelfBootstrapKey(userId, playlistId, shelfKey))
        ?: return null
    return runCatching {
        VodDisplayCacheJson.decodeFromString<VodBootstrapShelf>(raw)
            .entries
            .map { it.toTvVodEntry() }
    }.getOrNull()
}

private fun VodBootstrapShelfEntry.toTvVodEntry(): TvVodEntry {
    return TvVodEntry(
        sourceId = sourceId,
        sourceOrder = sourceOrder,
        channel = channel,
        item = item,
        searchTitle = searchTitle,
        language = language,
        category = category,
    )
}

private fun TvVodLibraryUiState.withFavoriteDelta(delta: Long): TvVodLibraryUiState {
    if (delta == 0L) return this
    return copy(
        categories = categories.map { category ->
            if (category.type == VodCategoryType.FAVORITES) {
                category.copy(count = (category.count + delta).coerceAtLeast(0))
            } else {
                category
            }
        },
    )
}

private fun filterVodCategoriesForSection(
    categories: List<TvVodCategory>,
    section: VodMediaSection,
): List<TvVodCategory> {
    val allCategoryType = when (section) {
        VodMediaSection.MOVIES -> VodCategoryType.ALL_MOVIES
        VodMediaSection.SHOWS -> VodCategoryType.ALL_SHOWS
    }
    return categories.mapNotNull { category ->
        when (category.type) {
            VodCategoryType.FAVORITES -> {
                val count = category.countForSection(section)
                category.copy(count = count).takeIf { count > 0 }
            }
            VodCategoryType.ALL_MOVIES,
            VodCategoryType.ALL_SHOWS -> category.takeIf { category.type == allCategoryType }
            VodCategoryType.PROVIDER -> {
                val count = category.countForSection(section)
                category.copy(count = count).takeIf { count > 0 }
            }
        }
    }.ifEmpty {
        categories.filter { it.type == allCategoryType }
    }
}

private fun TvVodCategory.countForSection(section: VodMediaSection): Long {
    return when (section) {
        VodMediaSection.MOVIES -> movieCount
        VodMediaSection.SHOWS -> showCount
    }
}

private suspend fun searchVodEntries(
    channelRepo: ChannelRepository,
    playlistId: String,
    query: String,
    mediaSection: VodMediaSection,
): List<TvVodEntry> {
    return channelRepo.searchChannels(query)
        .asSequence()
        .filter { it.playlistId == playlistId }
        .filter(::isVodChannel)
        .filter { inferVodMediaType(it) == mediaSection.mediaType }
        .take(MAX_SEARCH_ITEMS)
        .distinctBy { it.url }
        .mapIndexed { index, channel -> channel.toVodEntry(index) }
        .toList()
}

private fun Channel.toVodEntry(index: Int): TvVodEntry {
    val mediaType = inferVodMediaType(this)
    val rawTitle = tvgName ?: name
    val parsed = parseVodTitle(rawTitle, mediaType)
    val sourceId = (kodiProps["vod_stream_id"] ?: kodiProps["vod_series_id"])
        ?.let { "vod_source:$playlistId:${mediaType.name}:$it" }
        ?: "vod_source:${url.hashCode()}"
    val xtreamRating = kodiProps["vod_rating"]?.toDoubleOrNull()?.takeIf { it > 0 }
    return TvVodEntry(
        sourceId = sourceId,
        sourceOrder = index,
        channel = this,
        item = MediaItem(
            id = vodDisplayId(mediaType, parsed.searchTitle, parsed.year, sourceId),
            type = mediaType,
            title = parsed.displayTitle,
            year = parsed.year,
            posterUrl = tvgLogo,
            rating = xtreamRating,
        ),
        searchTitle = parsed.searchTitle,
        language = inferVodLanguage(this, rawTitle),
        category = cleanVodCategory(groupTitle),
    )
}

private fun List<TvVodEntry>.filterByYear(filter: VodYearFilter): List<TvVodEntry> {
    if (filter == VodYearFilter.ALL) return this
    return filter { entry ->
        val year = entry.item.year ?: parseVodTitle(entry.channel.tvgName ?: entry.channel.name, entry.mediaType).year
        when (filter) {
            VodYearFilter.ALL -> true
            VodYearFilter.Y2020S -> year != null && year in 2020..2029
            VodYearFilter.Y2010S -> year != null && year in 2010..2019
            VodYearFilter.Y2000S -> year != null && year in 2000..2009
            VodYearFilter.OLDER -> year != null && year < 2000
        }
    }
}

private data class ParsedVodTitle(
    val displayTitle: String,
    val searchTitle: String,
    val year: Int?,
)

private fun vodDisplayId(
    mediaType: MediaType,
    title: String,
    year: Int?,
    fallbackSourceId: String,
): String {
    val normalizedTitle = normalizeVodTitle(title)
    if (normalizedTitle.isBlank()) return fallbackSourceId
    return buildString {
        append("vod:")
        append(mediaType.name.lowercase())
        append(":")
        append(normalizedTitle)
        year?.let {
            append(":")
            append(it)
        }
    }
}

private fun List<TvVodEntry>.dedupeForDisplay(): List<TvVodEntry> {
    return groupBy { entry ->
        entry.item.tmdbId?.let { "${entry.mediaType}:tmdb:$it" } ?: entry.item.id
    }.values.map { variants ->
        variants.maxWith(
            compareBy<TvVodEntry> { if (it.item.tmdbId != null) 1 else 0 }
                .thenBy { if (it.item.ratings.withFallbackTmdbScore(it.item.rating) != null) 1 else 0 }
                .thenBy { if (!it.item.posterUrl.isNullOrBlank()) 1 else 0 }
                .thenBy { qualityScore(it.channel.name) }
                .thenByDescending { -it.sourceOrder },
        )
    }.sortedWith(compareBy<TvVodEntry>({ it.mediaType.name }, { it.item.title.lowercase() }))
}

private fun qualityScore(name: String): Int {
    val lower = name.lowercase()
    return when {
        "2160" in lower || "4k" in lower || "uhd" in lower -> 4
        "1080" in lower || "fhd" in lower -> 3
        "720" in lower || "hd" in lower -> 2
        else -> 1
    }
}

private fun parseVodTitle(raw: String, mediaType: MediaType): ParsedVodTitle {
    val withoutExtension = raw.substringBeforeLast('.', raw)
    val year = Regex("""(?:^|[\s._\-\[(])((?:19|20)\d{2})(?:[\s._\-\])]|\z)""")
        .find(withoutExtension)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    var cleaned = withoutExtension
        .replace(Regex("""\[[^\]]*]"""), " ")
        .replace(Regex("""\([^)]*(?:480p|720p|1080p|2160p|4k|hdr|x264|x265|hevc|web[- ]?dl|bluray|brrip)[^)]*\)""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""(?:^|[\s._-])(?:480p|720p|1080p|2160p|4k|uhd|hdr|hdr10|dv|x264|x265|hevc|web[- ]?dl|bluray|brrip|webrip|hdtv)(?=$|[\s._-])""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""(?:^|[\s._-])(?:multi|dual|en|eng|english|de|ger|german|deutsch|es|spa|spanish|fr|fre|french|it|ita|italian|tr|tur|turkish|proper|repack|extended|unrated|remux)(?=$|[\s._-])""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\b(?:19|20)\d{2}\b"""), " ")
        .replace('_', ' ')
        .replace('.', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '|', ':')

    if (mediaType == MediaType.SERIES) {
        cleaned = cleaned
            .replace(Regex("""\bS\d{1,2}E\d{1,3}\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b\d{1,2}x\d{1,3}\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', ':')
    }

    val fallback = raw.trim().ifBlank { "VOD" }
    val displayTitle = cleaned.ifBlank { fallback }
    return ParsedVodTitle(
        displayTitle = displayTitle,
        searchTitle = displayTitle,
        year = year,
    )
}

private fun isVodChannel(channel: Channel): Boolean {
    return channel.contentType == ChannelContentType.VOD_MOVIE ||
        channel.contentType == ChannelContentType.VOD_SERIES ||
        channel.groupTitle.orEmpty().startsWith("VOD:", ignoreCase = true) ||
        isVodUrl(channel.url)
}

private fun inferVodMediaType(channel: Channel): MediaType {
    val lowerUrl = channel.url.substringBefore('?').lowercase()
    val lowerGroup = channel.groupTitle.orEmpty().lowercase()
    val lowerName = channel.name.lowercase()
    return when {
        channel.contentType == ChannelContentType.VOD_SERIES -> MediaType.SERIES
        lowerUrl.contains("/series/") -> MediaType.SERIES
        lowerGroup.contains("series") ||
            lowerGroup.contains("serien") ||
            lowerGroup.contains("serie") ||
            lowerGroup.contains("show") ||
            lowerGroup.contains("tv show") ||
            lowerGroup.contains("staffel") ||
            lowerGroup.contains("season") ||
            lowerGroup.contains("episod") ||
            Regex("""\bs\d{1,2}\b""").containsMatchIn(lowerGroup) -> MediaType.SERIES
        Regex("""\bS\d{1,2}E\d{1,3}\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerName) -> MediaType.SERIES
        Regex("""\b\d{1,2}x\d{1,3}\b""").containsMatchIn(lowerName) -> MediaType.SERIES
        channel.contentType == ChannelContentType.VOD_MOVIE -> MediaType.MOVIE
        else -> MediaType.MOVIE
    }
}

private fun isVodUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.contains("/movie/") || path.contains("/series/") ||
        path.contains("/vod/") ||
        path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".avi") ||
        path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".wmv") ||
        path.endsWith(".webm")
}

private fun cleanVodCategory(groupTitle: String?): String {
    return groupTitle
        ?.removePrefix("VOD:")
        ?.removePrefix("vod:")
        ?.trim()
        ?.ifBlank { null }
        ?: "VOD"
}

private fun inferVodLanguage(channel: Channel, rawTitle: String): String? {
    channel.tvgLanguage
        ?.split(',', ';', '/', '|')
        ?.firstNotNullOfOrNull(::normalizeLanguageToken)
        ?.let { return it }
    channel.tvgCountry
        ?.split(',', ';', '/', '|')
        ?.firstNotNullOfOrNull(::normalizeLanguageToken)
        ?.let { return it }
    return inferLanguageFromText("${channel.groupTitle.orEmpty()} $rawTitle")
}

private fun inferLanguageFromText(value: String): String? {
    val candidates = value.split(' ', '-', '_', '|', ':', '/', '[', ']', '(', ')', '.', '+')
    return candidates.firstNotNullOfOrNull(::normalizeLanguageToken)
}

private fun normalizeLanguageToken(raw: String): String? {
    val token = raw.trim().trim('.', ',', ';').lowercase()
    return when (token) {
        "multi", "multisub", "subs", "multilang", "multi-language", "dual", "dual-audio" -> "Multi"
        "en", "eng", "english", "us", "usa", "uk", "gb" -> "English"
        "de", "deu", "ger", "german", "deutsch" -> "German"
        "es", "spa", "spanish", "espanol", "mx" -> "Spanish"
        "fr", "fre", "fra", "french", "francais" -> "French"
        "it", "ita", "italian" -> "Italian"
        "tr", "tur", "turkish" -> "Turkish"
        "pt", "por", "portuguese", "br" -> "Portuguese"
        "nl", "dut", "nld", "dutch" -> "Dutch"
        "ar", "ara", "arabic" -> "Arabic"
        "hi", "hin", "hindi", "in" -> "Hindi"
        "ja", "jp", "jpn", "japanese" -> "Japanese"
        "ko", "kor", "korean" -> "Korean"
        "zh", "chi", "zho", "chinese", "cn" -> "Chinese"
        "pl", "pol", "polish" -> "Polish"
        "sv", "swe", "swedish" -> "Swedish"
        "no", "nor", "norwegian" -> "Norwegian"
        "da", "dan", "danish" -> "Danish"
        "fi", "fin", "finnish" -> "Finnish"
        "ru", "rus", "russian" -> "Russian"
        "ro", "rum", "ron", "romanian" -> "Romanian"
        "el", "gre", "ell", "greek" -> "Greek"
        else -> null
    }
}

private fun MediaItem.bestVodRating(): Float? {
    return rating?.toFloat()
        ?: ratings?.imdbScore
        ?: ratings?.tmdbScore
        ?: ratings?.letterboxdScore?.times(2f)
        ?: ratings?.traktScore?.div(10f)
        ?: ratings?.mdblistScore?.div(10f)
}

private fun normalizeVodTitle(value: String): String {
    return value.lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "")
        .trim()
}
