package com.torve.android.tv.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.catalog.LiveBootstrapJson
import com.torve.android.catalog.LiveBootstrapShelf
import com.torve.android.catalog.LiveBootstrapShelfEntry
import com.torve.android.catalog.liveDisplayShelfBootstrapKey
import com.torve.android.tv.TvScreenCache
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.data.auth.AuthClient
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.domain.model.epgChannelLookupKeys
import com.torve.domain.model.programmesForEpgChannel
import com.torve.domain.model.stableChannelId
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.presentation.channels.EpgState
import com.torve.presentation.channels.ChannelsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.koin.compose.koinInject

private const val SIDEBAR_WEIGHT = 0.30f
private const val PREVIEW_WEIGHT = 0.35f
private const val GRID_WEIGHT = 0.65f
private const val MAX_FORWARD_HOURS = 12
private const val PAGE_DURATION_MS = IPTV_EPG_WINDOW_HOURS * 60L * 60L * 1000L
private const val MAX_PAGE_OFFSET = MAX_FORWARD_HOURS / IPTV_EPG_WINDOW_HOURS
private const val IPTV_SCREEN_CACHE_KEY = "tv_iptv_screen_state"
private const val IPTV_STARTUP_LOG_TAG = "TvStartupRecovery"
private const val TV_STAGED_SHELF_WARMUP_DELAY_MS = 1_200L

private enum class FocusZone {
    CHANNEL_LIST,
    EPG_GRID,
}

private data class TvIptvScreenCacheState(
    val focusedChannelId: String? = null,
    val selectedChannelId: String? = null,
    val focusedZone: String = FocusZone.CHANNEL_LIST.name,
    val focusedChannelIndex: Int = 0,
    val lastGridRowIndex: Int = 0,
    val lastGridColIndex: Int = 0,
    val windowPageOffset: Int = 0,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvIptvScreen(
    railFocusRequester: FocusRequester,
    heroOverlay: (@Composable () -> Unit)? = null,
    onChannelPlay: (Channel) -> Unit,
    onOpenEpgSettings: () -> Unit = {},
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    viewModel: ChannelsViewModel = koinInject(),
    localSettingsRepo: DeviceLocalSettingsRepository = koinInject(),
    shouldAutoFocus: Boolean = true,
    isActive: Boolean = true,
    isSubRouteActive: Boolean = false,
    isRailFocused: Boolean = false,
    isRailExpanded: Boolean = false,
    onCollapseRail: () -> Unit = {},
    onNavigateUp: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val cachedScreenState = remember {
        TvScreenCache.get<TvIptvScreenCacheState>(IPTV_SCREEN_CACHE_KEY) ?: TvIptvScreenCacheState()
    }

    var focusedChannelId by rememberSaveable { mutableStateOf(cachedScreenState.focusedChannelId) }
    var selectedChannelId by rememberSaveable { mutableStateOf(cachedScreenState.selectedChannelId) }
    var focusedChannel by remember { mutableStateOf<EnrichedChannel?>(null) }
    var focusedProgramme by remember { mutableStateOf<EpgProgramme?>(null) }
    var windowPageOffset by rememberSaveable { mutableIntStateOf(cachedScreenState.windowPageOffset) }
    var focusedZone by rememberSaveable {
        mutableStateOf(
            runCatching { FocusZone.valueOf(cachedScreenState.focusedZone) }
                .getOrDefault(FocusZone.CHANNEL_LIST),
        )
    }
    var focusedChannelIndex by rememberSaveable { mutableIntStateOf(cachedScreenState.focusedChannelIndex) }
    var lastGridRowIndex by rememberSaveable { mutableIntStateOf(cachedScreenState.lastGridRowIndex) }
    var lastGridColIndex by rememberSaveable { mutableIntStateOf(cachedScreenState.lastGridColIndex) }
    var gridFocusRequestToken by rememberSaveable { mutableIntStateOf(0) }
    var lastPageChangeMs by remember { mutableLongStateOf(0L) }
    var wasActive by remember { mutableStateOf(false) }

    val sidebarFocusRequester = remember { FocusRequester() }
    val sidebarListState = rememberLazyListState()
    var iptvSearchFieldFocused by remember { mutableStateOf(false) }
    val iptvFocusManager = LocalFocusManager.current
    val iptvKeyboardController = LocalSoftwareKeyboardController.current
    val liveShelfSessionCacheKey = remember(state.selectedPlaylistId, state.xxxEnabled) {
        liveShelvesCacheKey(state.selectedPlaylistId, state.xxxEnabled)
    }
    var loadedShelvesByCategory by remember(liveShelfSessionCacheKey) {
        mutableStateOf(TvScreenCache.get<Map<String, LiveShelfLoad>>(liveShelfSessionCacheKey).orEmpty())
    }
    var pendingGridEntryName by remember { mutableStateOf<String?>(null) }
    var restoringShelfNames by remember(liveShelfSessionCacheKey) { mutableStateOf(emptySet<String>()) }
    var requestedListFocusCatalogKey by remember { mutableStateOf<String?>(null) }

    onFirstContentRequester(sidebarFocusRequester)

    LaunchedEffect(isActive, focusedZone) {
        TvIptvRailState.hideRail.value = isActive && focusedZone == FocusZone.EPG_GRID
    }
    LaunchedEffect(
        focusedChannelId,
        selectedChannelId,
        focusedZone,
        focusedChannelIndex,
        lastGridRowIndex,
        lastGridColIndex,
        windowPageOffset,
    ) {
        TvScreenCache.put(
            IPTV_SCREEN_CACHE_KEY,
            TvIptvScreenCacheState(
                focusedChannelId = focusedChannelId,
                selectedChannelId = selectedChannelId,
                focusedZone = focusedZone.name,
                focusedChannelIndex = focusedChannelIndex,
                lastGridRowIndex = lastGridRowIndex,
                lastGridColIndex = lastGridColIndex,
                windowPageOffset = windowPageOffset,
            ),
        )
    }
    DisposableEffect(isActive) {
        onDispose {
            if (isActive) {
                TvIptvRailState.hideRail.value = false
            }
        }
    }

    BackHandler(enabled = isActive) {
        when {
            iptvSearchFieldFocused -> {
                iptvKeyboardController?.hide()
                iptvFocusManager.clearFocus()
            }
            state.showFilterSheet -> viewModel.toggleFilterSheet()
            state.showCategoryManager -> viewModel.toggleCategoryManager()
            else -> {
                focusedZone = FocusZone.CHANNEL_LIST
                TvIptvRailState.hideRail.value = false
                Log.d("TvIptvFocus", "back_to_rail route_callback")
                onNavigateUp()
            }
        }
    }

    LaunchedEffect(state.playlists) {
        if (state.playlists.isNotEmpty() && state.selectedPlaylistId == null) {
            viewModel.selectPlaylist(state.playlists.first().id)
        }
    }

    if (!state.isLoading && !state.isLoadingChannels && state.playlists.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 60.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.tv_iptv_no_playlists_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.tv_iptv_no_playlists_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = Silver,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val hasRenderableCatalog = state.channels.isNotEmpty() ||
        state.categories.isNotEmpty() ||
        state.favorites.isNotEmpty() ||
        state.recentlyViewedChannels.isNotEmpty()

    if ((state.isLoading || state.isLoadingChannels) && !hasRenderableCatalog) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Amber)
        }
        return
    }

    val allChannelsLabel = stringResource(R.string.tv_iptv_all_channels)
    val favoritesLabel = stringResource(R.string.tv_iptv_favorites)
    val recentlyViewedLabel = stringResource(R.string.tv_iptv_recently_viewed)

    // TV-side IPTV search — local substring match against category +
    // channel names. Empty query falls through to the unfiltered list.
    // Kept in this screen rather than the VM because the VM's
    // searchQuery flow runs a debounced repository.searchChannels
    // call meant for the mobile global search; TV just needs a
    // visible client-side filter on the categories the user can D-pad
    // through.
    var iptvSearchQuery by rememberSaveable { mutableStateOf("") }

    val displayCategories = remember(
        state.categories,
        state.favorites,
        state.recentlyViewedChannels,
        state.selectedCountries,
        iptvSearchQuery,
    ) {
        val actualCategories = state.categories
            .filter { it.channels.isNotEmpty() || it.channelCount > 0 }
            .filterNot { it.name.startsWith("VOD:", ignoreCase = true) || it.name.equals("VOD", ignoreCase = true) }
            .sortedWith(compareBy<ChannelCategory>({ (it.countryCode ?: "ZZZ").uppercase() }, { it.name.lowercase() }))

        val filteredFavorites = state.favorites.filter {
            channelMatchesCountryFilter(it, state.selectedCountries)
        }
        val filteredRecentlyViewed = state.recentlyViewedChannels.filter {
            channelMatchesCountryFilter(it, state.selectedCountries)
        }

        buildList {
            val totalChannelCount = actualCategories.sumOf { if (it.channels.isNotEmpty()) it.channels.size else it.channelCount }
            if (totalChannelCount > 0) {
                add(
                    ChannelCategory(
                        name = allChannelsLabel,
                        channelCount = totalChannelCount,
                        channels = emptyList(), // loaded on demand when selected
                    ),
                )
            }
            if (filteredFavorites.isNotEmpty()) {
                add(
                    ChannelCategory(
                        name = favoritesLabel,
                        channelCount = filteredFavorites.size,
                        channels = filteredFavorites.map { EnrichedChannel(channel = it) },
                        countryCode = "\u2764",
                    ),
                )
            }
            if (filteredRecentlyViewed.isNotEmpty()) {
                add(
                    ChannelCategory(
                        name = recentlyViewedLabel,
                        channelCount = filteredRecentlyViewed.size,
                        channels = filteredRecentlyViewed.map { EnrichedChannel(channel = it) },
                        countryCode = "\u23F2",
                    ),
                )
            }
            addAll(actualCategories)
        }.let { built ->
            // Apply the search filter after the buildList. Match
            // category names AND any contained channel names so a
            // search like "ESPN" surfaces the category whose
            // channels match even if the category is named "Sports".
            val needle = iptvSearchQuery.trim().lowercase()
            if (needle.isBlank()) built
            else built.filter { cat ->
                cat.name.lowercase().contains(needle) ||
                    cat.channels.any { it.channel.name.lowercase().contains(needle) }
            }
        }
    }

    LaunchedEffect(hasRenderableCatalog, state.isLoading, state.isLoadingChannels, state.selectedPlaylistId) {
        if (hasRenderableCatalog) {
            android.util.Log.d(
                IPTV_STARTUP_LOG_TAG,
                "Rendered local-first IPTV catalog playlist=${state.selectedPlaylistId.orEmpty()} " +
                    "channels=${state.channels.size} categories=${displayCategories.size} " +
                    "favorites=${state.favorites.size} recents=${state.recentlyViewedChannels.size} " +
                    "loading=${state.isLoading} loadingChannels=${state.isLoadingChannels}",
            )
        }
    }

    val preloadableCategories = remember(displayCategories, allChannelsLabel) {
        displayCategories.filter { category ->
            category.name != allChannelsLabel &&
                category.channels.isEmpty() &&
                category.channelCount > 0
        }
    }
    val maxCategoryIndex = (displayCategories.lastIndex).coerceAtLeast(0)

    LaunchedEffect(displayCategories) {
        if (displayCategories.isEmpty()) {
            focusedChannelId = null
            selectedChannelId = null
            focusedChannelIndex = 0
            return@LaunchedEffect
        }
        focusedChannelIndex = focusedChannelIndex.coerceIn(0, maxCategoryIndex)
        val focusedCurrent = focusedChannelId
        val persistedGroupName = state.selectedGroup
            ?.let { persisted -> displayCategories.firstOrNull { it.name.equals(persisted, ignoreCase = true) }?.name }
        if (focusedCurrent == null || displayCategories.none { it.name == focusedCurrent }) {
            focusedChannelId = persistedGroupName ?: displayCategories[focusedChannelIndex].name
        }
        val current = selectedChannelId
        if (current == null || displayCategories.none { it.name == current }) {
            selectedChannelId = persistedGroupName ?: focusedChannelId
        }
        persistedGroupName?.let { restoredGroup ->
            val restoredIndex = displayCategories.indexOfFirst { it.name == restoredGroup }
            if (restoredIndex >= 0) {
                focusedChannelIndex = restoredIndex
                android.util.Log.d(
                    IPTV_STARTUP_LOG_TAG,
                    "Restored persisted TV group selection group=$restoredGroup index=$restoredIndex",
                )
            }
        }
    }

    val focusedGroupIndex = remember(displayCategories, focusedChannelId, focusedChannelIndex) {
        if (displayCategories.isEmpty()) {
            -1
        } else {
            displayCategories.indexOfFirst { it.name == focusedChannelId }.takeIf { it >= 0 }
                ?: focusedChannelIndex.coerceIn(0, maxCategoryIndex)
        }
    }

    val selectedGroupIndex = remember(displayCategories, selectedChannelId, focusedGroupIndex) {
        if (displayCategories.isEmpty()) {
            -1
        } else {
            displayCategories.indexOfFirst { it.name == selectedChannelId }.takeIf { it >= 0 }
                ?: focusedGroupIndex.coerceIn(0, maxCategoryIndex)
        }
    }

    val focusedCategory = displayCategories.getOrNull(focusedGroupIndex)
    val categoryRequesterKeys = remember(displayCategories) {
        displayCategories.mapIndexed { index, category -> "$index:${category.name}" }
    }
    val categoryFocusRequesters = remember(categoryRequesterKeys) {
        categoryRequesterKeys.map { FocusRequester() }
    }

    fun categoryRequesterAt(index: Int): FocusRequester {
        return categoryFocusRequesters.getOrNull(index) ?: sidebarFocusRequester
    }

    fun focusedCategoryRequester(): FocusRequester {
        val targetIndex = focusedGroupIndex.takeIf { it >= 0 }
            ?: focusedChannelIndex.coerceIn(0, maxCategoryIndex)
        return categoryRequesterAt(targetIndex)
    }

    LaunchedEffect(state.selectedPlaylistId) {
        if (!state.selectedPlaylistId.isNullOrBlank()) {
            viewModel.hydrateCachedEpgOnly()
        }
    }

    LaunchedEffect(displayCategories, focusedGroupIndex) {
        if (displayCategories.isNotEmpty()) {
            onFirstContentRequester(focusedCategoryRequester())
        } else {
            onFirstContentRequester(sidebarFocusRequester)
        }
    }

    val channelsInGroup = remember(
        focusedCategory,
        state.categories,
        loadedShelvesByCategory,
        state.selectedCountries,
    ) {
        val cat = focusedCategory ?: return@remember emptyList()
        val loadedShelf = loadedShelvesByCategory[cat.name]
        if (cat.channels.isNotEmpty()) {
            cat.channels
        } else if (cat.name == allChannelsLabel && cat.channelCount > 0) {
            val loaded = loadedShelvesByCategory.values.flatMap { it.channels }
            loaded.ifEmpty {
                state.categories
                    .filter { it.channels.isNotEmpty() }
                    .flatMap { it.channels }
            }
        } else if (loadedShelf != null) {
            loadedShelf.channels
        } else {
            emptyList()
        }
    }
    // Recompute the anchor every 30 s so the EPG timeline tracks real time.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val windowAnchorMs = remember(nowMs) { alignedHalfHour(nowMs) }
    val windowStartMs = windowAnchorMs + (windowPageOffset * PAGE_DURATION_MS)
    val windowEndMs = windowStartMs + PAGE_DURATION_MS
    val activePlaylistId = state.selectedPlaylistId.orEmpty()

    suspend fun requestListFocus(reason: String = "unspecified") {
        if (displayCategories.isEmpty()) return
        val itemIndex = (focusedGroupIndex.takeIf { it >= 0 } ?: focusedChannelIndex).coerceIn(0, maxCategoryIndex)
        val targetCategory = displayCategories.getOrNull(itemIndex)
        val targetRequester = categoryRequesterAt(itemIndex)
        // Sidebar LazyColumn has two header items before categories: "iptv_search"
        // and "list_controls". Skip both so the focused category is actually visible
        // when focus lands on it; previously `itemIndex + 1` accounted for only one
        // header, leaving the first category off-screen on initial entry.
        val lazyItemIndex = itemIndex + 2
        val visibleItemIndexes = sidebarListState.layoutInfo.visibleItemsInfo.map { it.index }
        if (lazyItemIndex !in visibleItemIndexes) {
            runCatching { sidebarListState.scrollToItem(lazyItemIndex) }
        }
        // Wait one frame for the LazyColumn to compose the scrolled-in item so its
        // FocusRequester is attached before we request focus. Without this delay
        // requestFocus silently fails when the target item hasn't been laid out yet.
        delay(48)
        val focusedSelected = runCatching { targetRequester.requestFocus() }
            .onFailure {
                Log.w("TvIptvFocus", "requestListFocus failed reason=$reason target=${targetCategory?.name}: ${it.message}")
            }
            .isSuccess
        Log.d(
            "TvIptvFocus",
            "requestListFocus reason=$reason index=$itemIndex target=${targetCategory?.name.orEmpty()} " +
                "success=$focusedSelected zone=$focusedZone rail=$isRailFocused active=$isActive",
        )
        if (!focusedSelected) {
            runCatching { sidebarFocusRequester.requestFocus() }
        }
    }

    fun isCategoryShelfReady(category: ChannelCategory): Boolean {
        return when {
            category.channels.isNotEmpty() -> true
            category.channelCount == 0 -> true
            category.name == allChannelsLabel -> category.channels.isNotEmpty() || loadedShelvesByCategory.isNotEmpty()
            else -> loadedShelvesByCategory.containsKey(category.name)
        }
    }

    fun restoreShelfIfNeeded(category: ChannelCategory, enterGridWhenReady: Boolean = false) {
        val playlistId = state.selectedPlaylistId ?: return
        if (category.name == allChannelsLabel || category.channels.isNotEmpty() || category.channelCount == 0) return
        if (loadedShelvesByCategory.containsKey(category.name)) return
        if (restoringShelfNames.contains(category.name)) {
            if (enterGridWhenReady) {
                pendingGridEntryName = category.name
            }
            return
        }

        val categoryName = category.name
        restoringShelfNames = restoringShelfNames + categoryName
        if (enterGridWhenReady) {
            pendingGridEntryName = categoryName
        }

        scope.launch {
            val userId = withContext(kotlinx.coroutines.Dispatchers.IO) {
                localSettingsRepo.getString(AuthClient.KEY_AUTH_USER_ID)
            }
            val restoredShelf = withContext(kotlinx.coroutines.Dispatchers.IO) {
                userId?.let { readLiveBootstrapShelf(localSettingsRepo, it, playlistId, categoryName) }
                    ?: run {
                        val channels = viewModel.getChannelsForCategoryDirect(playlistId, categoryName)
                        if (channels.isEmpty()) {
                            null
                        } else {
                            val programmes = viewModel.getProgrammesForChannelsDirect(playlistId, channels)
                            LiveShelfLoad(channels = channels, programmes = programmes).also { shelf ->
                                if (userId != null) {
                                    writeLiveBootstrapShelf(localSettingsRepo, userId, playlistId, categoryName, shelf)
                                }
                            }
                        }
                    }
            }?.filterAdult(allowAdult = state.xxxEnabled)

            if (restoredShelf != null) {
                loadedShelvesByCategory = loadedShelvesByCategory + (categoryName to restoredShelf)
                TvScreenCache.put(liveShelfSessionCacheKey, loadedShelvesByCategory)
            } else if (pendingGridEntryName == categoryName) {
                pendingGridEntryName = null
            }
            restoringShelfNames = restoringShelfNames - categoryName
        }
    }

    LaunchedEffect(isActive, state.selectedPlaylistId, state.xxxEnabled, displayCategories) {
        if (!isActive) return@LaunchedEffect
        delay(TV_STAGED_SHELF_WARMUP_DELAY_MS)
        val initialCategory = focusedCategory
            ?.takeIf { it.name != allChannelsLabel && it.channels.isEmpty() && it.channelCount > 0 }
            ?: preloadableCategories.firstOrNull()
        if (initialCategory != null) {
            restoreShelfIfNeeded(initialCategory)
        }
    }

    fun focusListZone() {
        focusedZone = FocusZone.CHANNEL_LIST
        onContentFocused(focusedCategoryRequester())
        scope.launch {
            delay(50)
            requestListFocus("focus_list_zone")
        }
    }

    fun focusGridZone() {
        if (displayCategories.isEmpty()) return
        val selectedIndex = focusedChannelIndex.coerceIn(0, maxCategoryIndex)
        val targetCategory = displayCategories[selectedIndex]
        focusedChannelId = targetCategory.name
        val selectionChanged = selectedChannelId != targetCategory.name
        selectedChannelId = targetCategory.name
        if (selectionChanged) {
            windowPageOffset = 0
            lastGridRowIndex = 0
            lastGridColIndex = -1
        } else if (targetCategory.channelCount > 0) {
            lastGridRowIndex = lastGridRowIndex.coerceIn(0, targetCategory.channelCount - 1)
        }
        if (targetCategory.channelCount == 0) return
        if (!isCategoryShelfReady(targetCategory)) {
            restoreShelfIfNeeded(targetCategory, enterGridWhenReady = true)
            return
        }
        focusedZone = FocusZone.EPG_GRID
        onContentFocused(focusedCategoryRequester())
        gridFocusRequestToken += 1
    }

    LaunchedEffect(pendingGridEntryName, loadedShelvesByCategory) {
        val targetName = pendingGridEntryName ?: return@LaunchedEffect
        val targetIndex = displayCategories.indexOfFirst { it.name == targetName }
        val target = displayCategories.getOrNull(targetIndex) ?: return@LaunchedEffect
        if (!isCategoryShelfReady(target)) return@LaunchedEffect
        focusedChannelIndex = targetIndex
        focusedChannelId = target.name
        selectedChannelId = target.name
        pendingGridEntryName = null
        delay(40)
        focusGridZone()
    }

    LaunchedEffect(isActive, isRailFocused, state.selectedPlaylistId, displayCategories.size, focusedZone) {
        val focusKey = "${state.selectedPlaylistId.orEmpty()}:${displayCategories.size}"
        if (
            isActive &&
            !isRailFocused &&
            displayCategories.isNotEmpty() &&
            focusedZone == FocusZone.CHANNEL_LIST &&
            requestedListFocusCatalogKey != focusKey
        ) {
            requestedListFocusCatalogKey = focusKey
            delay(90)
            requestListFocus("catalog_ready")
        }
    }

    LaunchedEffect(channelsInGroup, focusedChannelId) {
        if (channelsInGroup.isEmpty()) {
            focusedChannel = null
            focusedProgramme = null
            lastGridRowIndex = 0
            lastGridColIndex = -1
            return@LaunchedEffect
        }
        lastGridRowIndex = lastGridRowIndex.coerceIn(0, channelsInGroup.lastIndex)
        val currentFocused = focusedChannel?.channel?.url
        val stillVisible = channelsInGroup.firstOrNull { it.channel.url == currentFocused }
        val persistedSelectedChannel = state.selectedChannel
        val restoredFocused = persistedSelectedChannel?.let { persisted ->
            channelsInGroup.firstOrNull { stableChannelId(it.channel) == stableChannelId(persisted) }
        }
        val nextFocused = stillVisible ?: restoredFocused ?: channelsInGroup[lastGridRowIndex]
        restoredFocused?.let { restored ->
            lastGridRowIndex = channelsInGroup.indexOf(restored).coerceAtLeast(0)
            android.util.Log.d(
                IPTV_STARTUP_LOG_TAG,
                "Restored persisted TV channel selection channel=${restored.channel.name} group=${focusedChannelId.orEmpty()} row=$lastGridRowIndex",
            )
        }
        focusedChannel = nextFocused
        val lookupPlaylistId = nextFocused.channel.playlistId
            .takeIf { it.isNotBlank() }
            ?: activePlaylistId
        val programmes = programmesForEpgChannel(
            programmesByChannelKey = state.guideProgrammes,
            playlistId = lookupPlaylistId,
            channel = nextFocused.channel,
        ).ifEmpty {
            focusedCategory
                ?.let { loadedShelvesByCategory[it.name]?.programmes }
                ?.let { shelfProgrammes ->
                    programmesForEpgChannel(
                        programmesByChannelKey = shelfProgrammes,
                        playlistId = lookupPlaylistId,
                        channel = nextFocused.channel,
                    )
                }
                .orEmpty()
        }
        focusedProgramme = programmes.firstOrNull { it.endTime > windowStartMs && it.startTime < windowEndMs }
            ?: nextFocused.currentProgramme
    }

    LaunchedEffect(windowStartMs, windowEndMs, focusedChannel, state.guideProgrammes, loadedShelvesByCategory, focusedCategory, activePlaylistId) {
        val ch = focusedChannel ?: return@LaunchedEffect
        val lookupPlaylistId = ch.channel.playlistId
            .takeIf { it.isNotBlank() }
            ?: activePlaylistId
        val programmes = programmesForEpgChannel(
            programmesByChannelKey = state.guideProgrammes,
            playlistId = lookupPlaylistId,
            channel = ch.channel,
        ).ifEmpty {
            focusedCategory
                ?.let { loadedShelvesByCategory[it.name]?.programmes }
                ?.let { shelfProgrammes ->
                    programmesForEpgChannel(
                        programmesByChannelKey = shelfProgrammes,
                        playlistId = lookupPlaylistId,
                        channel = ch.channel,
                    )
                }
                .orEmpty()
        }
        focusedProgramme = programmes.firstOrNull { it.endTime > windowStartMs && it.startTime < windowEndMs }
            ?: ch.currentProgramme
    }

    LaunchedEffect(channelsInGroup, state.guideProgrammes, loadedShelvesByCategory, focusedCategory, activePlaylistId) {
        if (channelsInGroup.isEmpty() || state.guideProgrammes.isEmpty()) return@LaunchedEffect
        val shelfProgrammes = focusedCategory
            ?.let { loadedShelvesByCategory[it.name]?.programmes }
            .orEmpty()
        val matched = channelsInGroup.count { enriched ->
            val lookupPlaylistId = enriched.channel.playlistId
                .takeIf { it.isNotBlank() }
                ?: activePlaylistId
            programmesForEpgChannel(state.guideProgrammes, lookupPlaylistId, enriched.channel).isNotEmpty() ||
                programmesForEpgChannel(shelfProgrammes, lookupPlaylistId, enriched.channel).isNotEmpty()
        }
        Log.d(
            "ChannelsEPG",
            "visible_match category=${focusedCategory?.name.orEmpty()} channels=${channelsInGroup.size} " +
                "matched=$matched stateKeys=${state.guideProgrammes.size} shelfKeys=${shelfProgrammes.size} playlist=$activePlaylistId",
        )
    }

    LaunchedEffect(isActive) {
        if (!isActive) {
            wasActive = isActive
            return@LaunchedEffect
        }

        if (!wasActive && !isRailFocused) {
            delay(60)
            if (focusedZone == FocusZone.EPG_GRID && channelsInGroup.isNotEmpty()) {
                gridFocusRequestToken += 1
            } else if (shouldAutoFocus || focusedZone == FocusZone.CHANNEL_LIST) {
                focusedZone = FocusZone.CHANNEL_LIST
                requestListFocus("screen_active")
            }
        }
        wasActive = true
    }

    // Restore focus to the correct zone when returning from player sub-route.
    // TvRoot's generic focus restore always lands on the sidebar requester;
    // this overrides it by redirecting to the saved focusedZone.
    var wasSubRouteActive by remember { mutableStateOf(isSubRouteActive) }
    LaunchedEffect(isSubRouteActive) {
        if (wasSubRouteActive && !isSubRouteActive && isActive) {
            // Returning from player — restore focus to the correct browse zone
            delay(120) // let TvRoot's generic restore settle first, then override
            Log.d("TvIptv", "Sub-route exit: restoring focus to zone=$focusedZone")
            if (focusedZone == FocusZone.EPG_GRID && channelsInGroup.isNotEmpty()) {
                gridFocusRequestToken += 1
            } else {
                requestListFocus("sub_route_return")
            }
        }
        wasSubRouteActive = isSubRouteActive
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = sidebarListState,
                modifier = Modifier
                    .weight(SIDEBAR_WEIGHT)
                    .fillMaxHeight()
                    .background(Charcoal.copy(alpha = 0.6f))
                    .focusRequester(sidebarFocusRequester)
                    .focusProperties {
                        canFocus = focusedZone == FocusZone.CHANNEL_LIST
                        // When focus enters the sidebar from outside (nav rail / first-content
                        // routing), redirect to the focused category instead of the search
                        // field. Without this the focusGroup forwards to the first focusable
                        // child — the search input — and the user has to D-pad right twice +
                        // back to actually land on a channel category on first entry.
                        enter = { focusedCategoryRequester() }
                    }
                    .focusGroup()
                    .onFocusChanged { state ->
                        if (!isActive || focusedZone != FocusZone.CHANNEL_LIST) return@onFocusChanged
                        if (state.isFocused) {
                            scope.launch {
                                requestListFocus("sidebar_container_focused")
                            }
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (!isActive || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionRight -> {
                                if (focusedZone != FocusZone.CHANNEL_LIST) return@onPreviewKeyEvent false
                                focusGridZone()
                                true
                            }

                            Key.DirectionLeft -> {
                                runCatching { railFocusRequester.requestFocus() }
                                true
                            }

                            else -> false
                        }
                    }
                    .padding(vertical = 8.dp),
            ) {
                item(key = "iptv_search") {
                    com.torve.android.ui.components.TorveSearchField(
                        value = iptvSearchQuery,
                        onValueChange = { iptvSearchQuery = it },
                        placeholder = stringResource(R.string.tv_iptv_search_hint),
                        showFocusRing = true,
                        editOnClick = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .focusProperties { left = railFocusRequester }
                            .onFocusChanged { iptvSearchFieldFocused = it.isFocused },
                    )
                }

                item(key = "list_controls") {
                    TvIptvControlChip(
                        label = stringResource(R.string.tv_iptv_manage_channels),
                        onClick = onOpenEpgSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }

                itemsIndexed(
                    items = displayCategories,
                    key = { index, cat -> "cat_${index}_${cat.name}" },
                ) { index, category ->
                    val categoryRequester = categoryRequesterAt(index)
                    IptvCategoryItem(
                        category = category,
                        isSelected = index == selectedGroupIndex,
                        modifier = Modifier
                            .focusRequester(categoryRequester)
                            .focusProperties {
                                left = railFocusRequester
                                canFocus = focusedZone == FocusZone.CHANNEL_LIST
                            },
                        onFocused = {
                            focusedChannelIndex = index
                            focusedChannelId = category.name
                            selectedChannelId = category.name
                            if (!isCategoryShelfReady(category)) {
                                restoreShelfIfNeeded(category)
                            }
                            focusedZone = FocusZone.CHANNEL_LIST
                            onContentFocused(categoryRequester)
                            Log.d("TvIptvFocus", "category_focused index=$index name=${category.name}")
                        },
                        onClick = {
                            focusedChannelIndex = index
                            focusedChannelId = category.name
                            selectedChannelId = category.name
                            if (!isCategoryShelfReady(category)) {
                                restoreShelfIfNeeded(category, enterGridWhenReady = true)
                            } else {
                                focusGridZone()
                            }
                        },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f - SIDEBAR_WEIGHT)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvEpgPreviewPanel(
                    focusedChannel = focusedChannel,
                    focusedProgramme = focusedProgramme,
                    isActive = isActive && !isSubRouteActive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(PREVIEW_WEIGHT),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Graphite.copy(alpha = 0.45f), MaterialTheme.shapes.small)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    val noChannelProgrammeText = "No guide data"
                    Text(
                        text = focusedProgramme?.title
                            ?: if (state.epgState is EpgState.Loaded) {
                                noChannelProgrammeText
                            } else {
                                focusedChannel?.channel?.name ?: stringResource(R.string.tv_live_no_programme_data)
                            },
                        color = Snow,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(GRID_WEIGHT)
                        .focusProperties { canFocus = focusedZone == FocusZone.EPG_GRID }
                        .focusGroup()
                        .onPreviewKeyEvent { event ->
                            if (!isActive || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    if (focusedZone != FocusZone.EPG_GRID) return@onPreviewKeyEvent false
                                    // Throttle: grid already has 50ms vertical throttle;
                                    // use the same token-increment pattern (one move per event,
                                    // coalesced via gridFocusRequestToken LaunchedEffect + 60ms delay).
                                    if (lastGridColIndex > 0) {
                                        lastGridColIndex -= 1
                                        gridFocusRequestToken += 1
                                        return@onPreviewKeyEvent true
                                    }
                                    if (windowPageOffset > 0) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastPageChangeMs < 250L) return@onPreviewKeyEvent true
                                        lastPageChangeMs = now
                                        windowPageOffset -= 1
                                        lastGridColIndex = -1
                                        gridFocusRequestToken += 1
                                        return@onPreviewKeyEvent true
                                    }
                                    focusListZone()
                                    true
                                }

                                else -> false
                            }
                        },
                ) {
                    val shelfProgrammes = focusedCategory
                        ?.let { loadedShelvesByCategory[it.name]?.programmes }
                        .orEmpty()
                    val effectiveProgrammes = if (shelfProgrammes.isNotEmpty()) {
                        state.guideProgrammes + shelfProgrammes
                    } else {
                        state.guideProgrammes
                    }
                    TvEpgGrid(
                        channels = channelsInGroup,
                        guideProgrammes = effectiveProgrammes,
                        playlistId = activePlaylistId,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        canPageBackward = windowPageOffset > 0,
                        canPageForward = windowPageOffset < MAX_PAGE_OFFSET,
                        focusRowIndex = lastGridRowIndex,
                        focusColIndex = lastGridColIndex,
                        focusRequestToken = gridFocusRequestToken,
                        isFocusEnabled = focusedZone == FocusZone.EPG_GRID,
                        onChannelFocused = { channel, programme ->
                            focusedChannel = channel
                            focusedProgramme = programme ?: channel.currentProgramme
                        },
                        onGridCellFocused = { rowIndex, colIndex ->
                            lastGridRowIndex = rowIndex
                            lastGridColIndex = colIndex
                            focusedZone = FocusZone.EPG_GRID
                        },
                        onMoveVertical = { delta ->
                            val targetRow = (lastGridRowIndex + delta)
                                .coerceIn(0, (channelsInGroup.lastIndex).coerceAtLeast(0))
                            if (targetRow != lastGridRowIndex) {
                                lastGridRowIndex = targetRow
                                gridFocusRequestToken += 1
                            }
                            focusedZone = FocusZone.EPG_GRID
                        },
                        onChannelPlay = { channel ->
                            viewModel.recordChannelViewed(channel)
                            onChannelPlay(channel)
                        },
                        onTimeForward = {
                            val now = System.currentTimeMillis()
                            if (windowPageOffset < MAX_PAGE_OFFSET && now - lastPageChangeMs >= 250L) {
                                lastPageChangeMs = now
                                windowPageOffset += 1
                                lastGridColIndex = -1
                                gridFocusRequestToken += 1
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    if (channelsInGroup.isEmpty()) {
                        val categoryName = focusedCategory?.name.orEmpty()
                        val isRestoring = categoryName.isNotBlank() && restoringShelfNames.contains(categoryName)
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Graphite.copy(alpha = 0.72f), MaterialTheme.shapes.medium)
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (isRestoring) {
                                CircularProgressIndicator(color = Amber)
                            }
                            Text(
                                text = when {
                                    categoryName.isBlank() -> stringResource(R.string.tv_live_no_programme_data)
                                    isRestoring -> "Restoring cached channels for $categoryName"
                                    focusedCategory?.channelCount == 0 -> "No channels in $categoryName"
                                    else -> "$categoryName is not cached yet. Channels will appear after the background cache finishes."
                                },
                                color = Snow,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    when (val epgState = state.epgState) {
                        EpgState.Loading -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .background(Graphite.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator(color = Amber)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = stringResource(R.string.tv_iptv_loading_epg), color = Silver)
                            }
                        }

                        EpgState.NotConfigured -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 12.dp)
                                    .background(Graphite.copy(alpha = 0.7f), MaterialTheme.shapes.small)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.tv_iptv_epg_not_configured),
                                    color = Silver,
                                    textAlign = TextAlign.Center,
                                )
                                TvIptvControlChip(
                                    label = stringResource(R.string.tv_iptv_open_settings),
                                    onClick = onOpenEpgSettings,
                                )
                            }
                        }

                        is EpgState.Error -> {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 12.dp)
                                    .background(Graphite.copy(alpha = 0.75f), MaterialTheme.shapes.small)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = epgState.message,
                                    color = Silver,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                TvIptvControlChip(
                                    label = stringResource(R.string.common_retry),
                                    onClick = { viewModel.retryGuideLoad() },
                                )
                            }
                        }

                        is EpgState.Loaded -> Unit
                    }
                }
            }
        }

        heroOverlay?.invoke()
    }
}

internal data class LiveShelfLoad(
    val channels: List<EnrichedChannel>,
    val programmes: Map<String, List<EpgProgramme>>,
)

/** Cache key used by both [TvIptvScreen] and the in-player overlay to share loaded shelves. */
internal fun liveShelvesCacheKey(playlistId: String?, xxxEnabled: Boolean): String =
    "tv_iptv_live_shelves:${playlistId.orEmpty()}:$xxxEnabled"

internal suspend fun readLiveBootstrapShelf(
    localSettingsRepo: DeviceLocalSettingsRepository,
    userId: String,
    playlistId: String,
    categoryName: String,
): LiveShelfLoad? {
    val cached = localSettingsRepo.getString(liveDisplayShelfBootstrapKey(userId, playlistId, categoryName))
        ?: return null
    return runCatching {
        LiveBootstrapJson.decodeFromString<LiveBootstrapShelf>(cached).toLiveShelfLoad(playlistId)
    }.getOrNull()
}

private suspend fun writeLiveBootstrapShelf(
    localSettingsRepo: DeviceLocalSettingsRepository,
    userId: String,
    playlistId: String,
    categoryName: String,
    shelf: LiveShelfLoad,
) {
    val payload = LiveBootstrapShelf(
        entries = shelf.channels.map { enriched ->
            LiveBootstrapShelfEntry(
                channel = enriched.channel,
                currentProgramme = enriched.currentProgramme,
                nextProgramme = enriched.nextProgramme,
                programmes = programmesForEpgChannel(
                    programmesByChannelKey = shelf.programmes,
                    playlistId = playlistId,
                    channel = enriched.channel,
                ),
            )
        },
    )
    localSettingsRepo.setString(
        liveDisplayShelfBootstrapKey(userId, playlistId, categoryName),
        LiveBootstrapJson.encodeToString(payload),
    )
}

private fun LiveBootstrapShelf.toLiveShelfLoad(playlistId: String): LiveShelfLoad {
    val channels = entries.map { entry ->
        EnrichedChannel(
            channel = entry.channel,
            currentProgramme = entry.currentProgramme,
            nextProgramme = entry.nextProgramme,
        )
    }
    val programmes = entries.mapNotNull { entry ->
        val key = canonicalEpgChannelKey(playlistId = playlistId, channel = entry.channel)
            ?: return@mapNotNull null
        key to entry.programmes
    }.toMap()
    return LiveShelfLoad(channels = channels, programmes = programmes)
}

internal fun LiveShelfLoad.filterAdult(allowAdult: Boolean): LiveShelfLoad {
    if (allowAdult) return this
    val adultKeywords = setOf("xxx", "adult", "18+", "porn", "erotic")
    val filteredChannels = channels.filter { enriched ->
        val group = enriched.channel.groupTitle.orEmpty().lowercase()
        val name = enriched.channel.name.lowercase()
        adultKeywords.none { keyword -> group.contains(keyword) || name.contains(keyword) }
    }
    if (filteredChannels.size == channels.size) return this
    val visibleKeys = filteredChannels.flatMap { enriched ->
        epgChannelLookupKeys(
            playlistId = enriched.channel.playlistId,
            channel = enriched.channel,
        )
    }.toSet()
    return LiveShelfLoad(
        channels = filteredChannels,
        programmes = programmes.filterKeys(visibleKeys::contains),
    )
}
