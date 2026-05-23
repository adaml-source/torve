package com.torve.android.tv.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.unit.sp
import com.torve.android.R
import com.torve.android.tv.TV_PAGE_BOTTOM_GUTTER
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.catalog.LiveBootstrapJson
import com.torve.android.catalog.LiveBootstrapShelf
import com.torve.android.catalog.LiveBootstrapShelfEntry
import com.torve.android.catalog.liveDisplayShelfBootstrapKey
import com.torve.android.tv.TvScreenCache
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.data.auth.AuthClient
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.ChannelContentType
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val IPTV_CATEGORY_PANEL_WIDTH = 280.dp
private const val PREVIEW_WEIGHT = 0.26f
private const val GRID_WEIGHT = 0.74f
private const val MAX_FORWARD_HOURS = 12
private const val PAGE_DURATION_MS = IPTV_EPG_WINDOW_HOURS * 60L * 60L * 1000L
private const val MAX_PAGE_OFFSET = MAX_FORWARD_HOURS / IPTV_EPG_WINDOW_HOURS
private const val IPTV_SCREEN_CACHE_KEY = "tv_iptv_screen_state"
private const val IPTV_STARTUP_LOG_TAG = "TvStartupRecovery"
private const val TV_STAGED_SHELF_WARMUP_DELAY_MS = 1_200L
private const val IPTV_VISIBLE_EPG_CHANNEL_LIMIT = 260
private val iptvHeaderTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val iptvHeaderDateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

private enum class FocusZone {
    CHANNEL_LIST,
    EPG_GRID,
}

private enum class LeftFocusTarget {
    SEARCH,
    MANAGE,
    CATEGORY,
}

private data class TvIptvScreenCacheState(
    val focusedChannelId: String? = null,
    val selectedChannelId: String? = null,
    val focusedZone: String = FocusZone.CHANNEL_LIST.name,
    val leftFocusTarget: String = LeftFocusTarget.SEARCH.name,
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
    var lastLeftFocusTarget by rememberSaveable {
        mutableStateOf(
            runCatching { LeftFocusTarget.valueOf(cachedScreenState.leftFocusTarget) }
                .getOrDefault(LeftFocusTarget.SEARCH),
        )
    }
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
    val searchFocusRequester = remember { FocusRequester() }
    val manageFocusRequester = remember { FocusRequester() }
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
    var visibleDirectProgrammes by remember(liveShelfSessionCacheKey) {
        mutableStateOf<Map<String, List<EpgProgramme>>>(emptyMap())
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
        lastLeftFocusTarget,
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
                leftFocusTarget = lastLeftFocusTarget.name,
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
            focusedZone == FocusZone.EPG_GRID -> {
                focusedZone = FocusZone.CHANNEL_LIST
                lastLeftFocusTarget = LeftFocusTarget.CATEGORY
                TvIptvRailState.hideRail.value = false
            }
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

    fun leftEntryRequester(): FocusRequester = when (lastLeftFocusTarget) {
        LeftFocusTarget.SEARCH -> searchFocusRequester
        LeftFocusTarget.MANAGE -> manageFocusRequester
        LeftFocusTarget.CATEGORY -> focusedCategoryRequester()
    }

    LaunchedEffect(state.selectedPlaylistId) {
        if (!state.selectedPlaylistId.isNullOrBlank()) {
            viewModel.hydrateCachedEpgOnly()
        }
    }

    LaunchedEffect(displayCategories, focusedGroupIndex, lastLeftFocusTarget) {
        if (displayCategories.isNotEmpty()) {
            onFirstContentRequester(leftEntryRequester())
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
        }.filter { enriched -> isPlayableIptvChannel(enriched.channel) }
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

    LaunchedEffect(activePlaylistId, channelsInGroup, state.epgState) {
        visibleDirectProgrammes = emptyMap()
        if (activePlaylistId.isBlank() || channelsInGroup.isEmpty()) return@LaunchedEffect
        if (state.epgState is EpgState.NotConfigured) return@LaunchedEffect
        val queryChannels = channelsInGroup.take(IPTV_VISIBLE_EPG_CHANNEL_LIMIT)
        val programmes = runCatching {
            viewModel.getProgrammesForChannelsDirect(
                playlistId = activePlaylistId,
                channels = queryChannels,
            )
        }.getOrDefault(emptyMap())
        visibleDirectProgrammes = programmes
        Log.i(
            "ChannelsEPG",
            "visible_direct_load category=${focusedCategory?.name.orEmpty()} channels=${channelsInGroup.size} " +
                "queried=${queryChannels.size} keys=${programmes.size} state=${state.epgState::class.simpleName}",
        )
    }

    suspend fun requestListFocus(reason: String = "unspecified") {
        if (displayCategories.isEmpty()) return
        val itemIndex = (focusedGroupIndex.takeIf { it >= 0 } ?: focusedChannelIndex).coerceIn(0, maxCategoryIndex)
        val targetCategory = displayCategories.getOrNull(itemIndex)
        val targetRequester = categoryRequesterAt(itemIndex)
        val lazyItemIndex = itemIndex
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

    suspend fun requestLeftPanelFocus(reason: String = "unspecified") {
        when (lastLeftFocusTarget) {
            LeftFocusTarget.SEARCH -> {
                delay(32)
                runCatching { searchFocusRequester.requestFocus() }
                    .onFailure { Log.w("TvIptvFocus", "requestSearchFocus failed reason=$reason: ${it.message}") }
            }
            LeftFocusTarget.MANAGE -> {
                delay(32)
                runCatching { manageFocusRequester.requestFocus() }
                    .onFailure { Log.w("TvIptvFocus", "requestManageFocus failed reason=$reason: ${it.message}") }
            }
            LeftFocusTarget.CATEGORY -> requestListFocus(reason)
        }
    }

    LaunchedEffect(focusedZone, lastLeftFocusTarget) {
        if (focusedZone == FocusZone.CHANNEL_LIST && lastLeftFocusTarget == LeftFocusTarget.CATEGORY) {
            requestListFocus("group_mode_visible")
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
        lastLeftFocusTarget = LeftFocusTarget.CATEGORY
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
            requestLeftPanelFocus("catalog_ready")
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
        val shelfProgrammes = focusedCategory
            ?.let { loadedShelvesByCategory[it.name]?.programmes }
            .orEmpty()
        val effectiveProgrammes = mergeEpgProgrammeMaps(
            primary = mergeEpgProgrammeMaps(
                primary = state.guideProgrammes,
                secondary = shelfProgrammes,
            ),
            secondary = visibleDirectProgrammes,
        )
        val programmes = programmesForEpgChannel(
            programmesByChannelKey = effectiveProgrammes,
            playlistId = lookupPlaylistId,
            channel = nextFocused.channel,
        )
        focusedProgramme = programmes.firstOrNull { it.endTime > windowStartMs && it.startTime < windowEndMs }
            ?: nextFocused.currentProgramme
    }

    LaunchedEffect(windowStartMs, windowEndMs, focusedChannel, state.guideProgrammes, visibleDirectProgrammes, loadedShelvesByCategory, focusedCategory, activePlaylistId) {
        val ch = focusedChannel ?: return@LaunchedEffect
        val lookupPlaylistId = ch.channel.playlistId
            .takeIf { it.isNotBlank() }
            ?: activePlaylistId
        val shelfProgrammes = focusedCategory
            ?.let { loadedShelvesByCategory[it.name]?.programmes }
            .orEmpty()
        val effectiveProgrammes = mergeEpgProgrammeMaps(
            primary = mergeEpgProgrammeMaps(
                primary = state.guideProgrammes,
                secondary = shelfProgrammes,
            ),
            secondary = visibleDirectProgrammes,
        )
        val programmes = programmesForEpgChannel(
            programmesByChannelKey = effectiveProgrammes,
            playlistId = lookupPlaylistId,
            channel = ch.channel,
        )
        focusedProgramme = programmes.firstOrNull { it.endTime > windowStartMs && it.startTime < windowEndMs }
            ?: ch.currentProgramme
    }

    LaunchedEffect(channelsInGroup, state.guideProgrammes, visibleDirectProgrammes, loadedShelvesByCategory, focusedCategory, activePlaylistId) {
        if (channelsInGroup.isEmpty()) return@LaunchedEffect
        val shelfProgrammes = focusedCategory
            ?.let { loadedShelvesByCategory[it.name]?.programmes }
            .orEmpty()
        val effectiveProgrammes = mergeEpgProgrammeMaps(
            primary = mergeEpgProgrammeMaps(
                primary = state.guideProgrammes,
                secondary = shelfProgrammes,
            ),
            secondary = visibleDirectProgrammes,
        )
        val matched = channelsInGroup.count { enriched ->
            val lookupPlaylistId = enriched.channel.playlistId
                .takeIf { it.isNotBlank() }
                ?: activePlaylistId
            programmesForEpgChannel(effectiveProgrammes, lookupPlaylistId, enriched.channel).isNotEmpty()
        }
        if (com.torve.android.BuildConfig.DEBUG) {
            val sample = channelsInGroup.take(5).joinToString(separator = ";") { enriched ->
                val lookupPlaylistId = enriched.channel.playlistId
                    .takeIf { it.isNotBlank() }
                    ?: activePlaylistId
                val programmeCount = programmesForEpgChannel(
                    programmesByChannelKey = effectiveProgrammes,
                    playlistId = lookupPlaylistId,
                    channel = enriched.channel,
                ).size
                val lookupCount = epgChannelLookupKeys(
                    playlistId = lookupPlaylistId,
                    channel = enriched.channel,
                ).size
                "id=${stableChannelId(enriched.channel).take(12)} keys=$lookupCount programmes=$programmeCount"
            }
            Log.d(
                "ChannelsEPG",
                    "visible_match category=${focusedCategory?.name.orEmpty()} channels=${channelsInGroup.size} " +
                    "matched=$matched stateKeys=${state.guideProgrammes.size} shelfKeys=${shelfProgrammes.size} " +
                    "directKeys=${visibleDirectProgrammes.size} mergedKeys=${effectiveProgrammes.size} playlist=$activePlaylistId " +
                    "window=${Date(windowStartMs)}..${Date(windowEndMs)} tz=${TimeZone.getDefault().id} sample=[$sample]",
            )
        }
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
                requestLeftPanelFocus("screen_active")
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
                requestLeftPanelFocus("sub_route_return")
            }
        }
        wasSubRouteActive = isSubRouteActive
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1D2448).copy(alpha = 0.42f),
                        Obsidian.copy(alpha = 0.98f),
                    ),
                    center = androidx.compose.ui.geometry.Offset(1480f, 80f),
                    radius = 1500f,
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = TV_PAGE_CONTENT_GUTTER, end = 24.dp, top = 26.dp, bottom = TV_PAGE_BOTTOM_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val isGuideMode = focusedZone == FocusZone.EPG_GRID
            if (!isGuideMode) {
            Column(
                modifier = Modifier
                    .width(IPTV_CATEGORY_PANEL_WIDTH)
                    .fillMaxHeight()
                    .padding(top = 48.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Charcoal.copy(alpha = 0.76f),
                                Obsidian.copy(alpha = 0.92f),
                            ),
                        ),
                    )
                    .border(1.dp, Steel.copy(alpha = 0.26f), RoundedCornerShape(26.dp))
                    .focusRequester(sidebarFocusRequester)
                    .focusProperties {
                        canFocus = focusedZone == FocusZone.CHANNEL_LIST
                        // When focus enters the sidebar from outside (nav rail / first-content
                        // routing), redirect to the focused category instead of the search
                        // field. Without this the focusGroup forwards to the first focusable
                        // child — the search input — and the user has to D-pad right twice +
                        // back to actually land on a channel category on first entry.
                        enter = { leftEntryRequester() }
                    }
                    .focusGroup()
                    .onFocusChanged { state ->
                        if (!isActive || focusedZone != FocusZone.CHANNEL_LIST) return@onFocusChanged
                        if (state.isFocused) {
                            scope.launch {
                                requestLeftPanelFocus("sidebar_container_focused")
                            }
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (!isActive || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionRight -> {
                                if (focusedZone != FocusZone.CHANNEL_LIST) return@onPreviewKeyEvent false
                                if (lastLeftFocusTarget == LeftFocusTarget.CATEGORY) {
                                    focusGridZone()
                                }
                                true
                            }

                            Key.DirectionLeft -> {
                                runCatching { railFocusRequester.requestFocus() }
                                true
                            }

                            else -> false
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "CATEGORIES",
                    color = Silver.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                com.torve.android.ui.components.TorveSearchField(
                    value = iptvSearchQuery,
                    onValueChange = { iptvSearchQuery = it },
                    placeholder = stringResource(R.string.tv_iptv_search_hint),
                    showFocusRing = true,
                    editOnClick = true,
                    onMoveDownFromEdit = {
                        lastLeftFocusTarget = LeftFocusTarget.MANAGE
                        runCatching { manageFocusRequester.requestFocus() }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .focusRequester(searchFocusRequester)
                        .focusProperties {
                            left = railFocusRequester
                            up = searchFocusRequester
                            down = manageFocusRequester
                            right = searchFocusRequester
                            canFocus = focusedZone == FocusZone.CHANNEL_LIST
                        }
                        .onFocusChanged {
                            iptvSearchFieldFocused = it.hasFocus
                            if (it.hasFocus) {
                                lastLeftFocusTarget = LeftFocusTarget.SEARCH
                                onContentFocused(searchFocusRequester)
                            }
                        },
                )
                Spacer(Modifier.height(8.dp))
                IptvPanelActionRow(
                    icon = Icons.Filled.Settings,
                    label = stringResource(R.string.tv_iptv_manage_channels),
                    onClick = {
                        lastLeftFocusTarget = LeftFocusTarget.MANAGE
                        onOpenEpgSettings()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .focusRequester(manageFocusRequester)
                        .focusProperties {
                            left = railFocusRequester
                            up = searchFocusRequester
                            down = categoryRequesterAt(0)
                            right = manageFocusRequester
                            canFocus = focusedZone == FocusZone.CHANNEL_LIST
                        },
                    onFocused = {
                        lastLeftFocusTarget = LeftFocusTarget.MANAGE
                        onContentFocused(manageFocusRequester)
                    },
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    state = sidebarListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    itemsIndexed(
                        items = displayCategories,
                        key = { index, cat -> "cat_${index}_${cat.name}" },
                    ) { index, category ->
                        val categoryRequester = categoryRequesterAt(index)
                        IptvCategoryItem(
                            category = category,
                            isSelected = index == selectedGroupIndex,
                            guideOwnsFocus = focusedZone == FocusZone.EPG_GRID,
                            modifier = Modifier
                                .focusRequester(categoryRequester)
                                .focusProperties {
                                    left = railFocusRequester
                                    up = if (index > 0) categoryRequesterAt(index - 1) else manageFocusRequester
                                    down = if (index < displayCategories.lastIndex) {
                                        categoryRequesterAt(index + 1)
                                    } else {
                                        categoryRequester
                                    }
                                    canFocus = focusedZone == FocusZone.CHANNEL_LIST
                                },
                            onFocused = {
                                lastLeftFocusTarget = LeftFocusTarget.CATEGORY
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
                                lastLeftFocusTarget = LeftFocusTarget.CATEGORY
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
            }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(30.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvIptvHeader(
                    channelCount = channelsInGroup.size,
                    groupName = focusedCategory?.name,
                    showGroupName = isGuideMode,
                )

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
                    val effectiveProgrammes = remember(state.guideProgrammes, shelfProgrammes, visibleDirectProgrammes) {
                        mergeEpgProgrammeMaps(
                            primary = mergeEpgProgrammeMaps(
                                primary = state.guideProgrammes,
                                secondary = shelfProgrammes,
                            ),
                            secondary = visibleDirectProgrammes,
                        )
                    }
                    TvEpgGrid(
                        channels = channelsInGroup,
                        guideProgrammes = effectiveProgrammes,
                        isGuideLoading = state.epgState is EpgState.Loading,
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
                        channelColumnWidth = if (isGuideMode) 310.dp else 178.dp,
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

private fun mergeEpgProgrammeMaps(
    primary: Map<String, List<EpgProgramme>>,
    secondary: Map<String, List<EpgProgramme>>,
): Map<String, List<EpgProgramme>> {
    if (primary.isEmpty()) return secondary
    if (secondary.isEmpty()) return primary

    val merged = linkedMapOf<String, List<EpgProgramme>>()
    (primary.keys + secondary.keys).forEach { key ->
        val primaryProgrammes = primary[key].orEmpty()
        val secondaryProgrammes = secondary[key].orEmpty()
        val programmes = when {
            primaryProgrammes.isEmpty() -> secondaryProgrammes
            secondaryProgrammes.isEmpty() -> primaryProgrammes
            else -> (primaryProgrammes + secondaryProgrammes)
                .distinctBy { programme ->
                    "${programme.channelId}|${programme.startTime}|${programme.endTime}|${programme.title}"
                }
                .sortedWith(compareBy<EpgProgramme> { it.startTime }.thenBy { it.endTime })
        }
        if (programmes.isNotEmpty()) {
            merged[key] = programmes
        }
    }
    return merged
}

@Composable
private fun IptvPanelActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Row(
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
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Amber else Steel.copy(alpha = 0.14f),
                shape = RoundedCornerShape(16.dp),
            )
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) Amber.copy(alpha = 0.17f) else Graphite.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (focused) Amber else Silver,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = label,
            color = if (focused) Snow else Silver,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvIptvHeader(
    channelCount: Int,
    groupName: String? = null,
    showGroupName: Boolean = false,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Charcoal.copy(alpha = 0.34f))
                .border(1.dp, Steel.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = if (showGroupName && !groupName.isNullOrBlank()) {
                    "$groupName · $channelCount channels"
                } else {
                    "$channelCount channels"
                },
                color = Silver,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Charcoal.copy(alpha = 0.34f))
                .border(1.dp, Steel.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = iptvHeaderDateFormat.format(Date(nowMs)),
                color = Silver,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = iptvHeaderTimeFormat.format(Date(nowMs)),
                color = Amber,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
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

private fun isPlayableIptvChannel(channel: Channel): Boolean {
    val url = channel.url.trim()
    if (url.isBlank()) return false
    if (url == "#" || url.equals("about:blank", ignoreCase = true)) return false
    if (channel.contentType == ChannelContentType.VOD_MOVIE || channel.contentType == ChannelContentType.VOD_SERIES) {
        return false
    }
    return !isDecorativeChannelName(channel.name)
}

private fun isDecorativeChannelName(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isBlank()) return true
    if (trimmed.all { it == '#' || it == '-' || it == '_' || it == '*' || it == '=' || it.isWhitespace() }) {
        return true
    }
    val hashCount = trimmed.count { it == '#' }
    if (hashCount >= 6 && trimmed.startsWith("#") && trimmed.endsWith("#")) return true
    val stripped = trimmed.trim('#', '-', '_', '*', '=', ' ')
    return stripped.isBlank()
}
