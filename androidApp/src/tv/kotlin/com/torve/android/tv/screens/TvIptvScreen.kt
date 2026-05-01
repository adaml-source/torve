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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.torve.android.tv.TvScreenCache
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.domain.model.stableChannelId
import com.torve.presentation.channels.EpgState
import com.torve.presentation.channels.ChannelsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val SIDEBAR_WEIGHT = 0.30f
private const val PREVIEW_WEIGHT = 0.35f
private const val GRID_WEIGHT = 0.65f
private const val MAX_FORWARD_HOURS = 12
private const val PAGE_DURATION_MS = IPTV_EPG_WINDOW_HOURS * 60L * 60L * 1000L
private const val MAX_PAGE_OFFSET = MAX_FORWARD_HOURS / IPTV_EPG_WINDOW_HOURS
private const val IPTV_SCREEN_CACHE_KEY = "tv_iptv_screen_state"
private const val IPTV_STARTUP_LOG_TAG = "TvStartupRecovery"

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

@Composable
fun TvIptvScreen(
    railFocusRequester: FocusRequester,
    heroOverlay: (@Composable () -> Unit)? = null,
    onChannelPlay: (Channel) -> Unit,
    onOpenEpgSettings: () -> Unit = {},
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    viewModel: ChannelsViewModel = koinInject(),
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
    val focusedCategoryFocusRequester = remember { FocusRequester() }
    val sidebarListState = rememberLazyListState()

    onFirstContentRequester(focusedCategoryFocusRequester)

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
            state.showFilterSheet -> viewModel.toggleFilterSheet()
            state.showCategoryManager -> viewModel.toggleCategoryManager()
            else -> onNavigateUp()
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

    // On-demand channel + EPG loading: when cached categories have empty channels,
    // load channels and their EPG programmes from DB for the focused category.
    var onDemandCategoryName by remember { mutableStateOf<String?>(null) }
    var onDemandChannels by remember { mutableStateOf<List<EnrichedChannel>>(emptyList()) }
    var onDemandProgrammes by remember { mutableStateOf<Map<String, List<EpgProgramme>>>(emptyMap()) }

    LaunchedEffect(focusedCategory?.name, state.selectedPlaylistId) {
        val cat = focusedCategory ?: return@LaunchedEffect
        if (cat.channels.isEmpty() && cat.channelCount > 0 && cat.name != allChannelsLabel) {
            val playlistId = state.selectedPlaylistId ?: return@LaunchedEffect
            Log.d("TvIptv", "On-demand load: category=${cat.name} count=${cat.channelCount}")
            val (loaded, programmes) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val channels = viewModel.getChannelsForCategoryDirect(playlistId, cat.name)
                // Load EPG programmes for these channels from local DB
                val progs = viewModel.getProgrammesForChannelsDirect(playlistId, channels)
                channels to progs
            }
            onDemandCategoryName = cat.name
            onDemandChannels = loaded
            onDemandProgrammes = programmes
            Log.d("TvIptv", "On-demand loaded: ${loaded.size} channels, ${programmes.size} EPG keys for ${cat.name}")
        }
    }

    val channelsInGroup = remember(
        focusedCategory,
        state.categories,
        onDemandCategoryName,
        onDemandChannels,
        state.selectedCountries,
    ) {
        val cat = focusedCategory ?: return@remember emptyList()
        if (cat.channels.isNotEmpty()) {
            cat.channels
        } else if (cat.name == allChannelsLabel && cat.channelCount > 0) {
            state.categories
                .filter { it.channels.isNotEmpty() }
                .flatMap { it.channels }
        } else if (onDemandCategoryName == cat.name && onDemandChannels.isNotEmpty()) {
            onDemandChannels
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

    suspend fun requestListFocus() {
        if (displayCategories.isEmpty()) return
        val itemIndex = focusedChannelIndex.coerceIn(0, maxCategoryIndex)
        runCatching { sidebarListState.scrollToItem(itemIndex + 1) }
        val focusedSelected = runCatching { focusedCategoryFocusRequester.requestFocus() }.isSuccess
        if (!focusedSelected) {
            runCatching { sidebarFocusRequester.requestFocus() }
        }
    }

    fun focusListZone() {
        focusedZone = FocusZone.CHANNEL_LIST
        onContentFocused(focusedCategoryFocusRequester)
        scope.launch {
            delay(50)
            requestListFocus()
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
        focusedZone = FocusZone.EPG_GRID
        onContentFocused(focusedCategoryFocusRequester)
        gridFocusRequestToken += 1
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
        val key = canonicalEpgChannelKey(
            playlistId = nextFocused.channel.playlistId,
            channel = nextFocused.channel,
        )
        val programmes = if (key.isNullOrBlank()) {
            emptyList()
        } else {
            state.guideProgrammes[key].orEmpty()
        }
        focusedProgramme = programmes.firstOrNull { it.endTime > windowStartMs && it.startTime < windowEndMs }
            ?: nextFocused.currentProgramme
    }

    LaunchedEffect(windowStartMs, windowEndMs, focusedChannel, state.guideProgrammes) {
        val ch = focusedChannel ?: return@LaunchedEffect
        val key = canonicalEpgChannelKey(
            playlistId = ch.channel.playlistId,
            channel = ch.channel,
        )
        val programmes = if (key.isNullOrBlank()) {
            emptyList()
        } else {
            state.guideProgrammes[key].orEmpty()
        }
        focusedProgramme = programmes.firstOrNull { it.endTime > windowStartMs && it.startTime < windowEndMs }
            ?: ch.currentProgramme
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
                requestListFocus()
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
                requestListFocus()
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
                    .focusProperties { canFocus = focusedZone == FocusZone.CHANNEL_LIST }
                    .focusGroup()
                    .onFocusChanged { state ->
                        if (!isActive || focusedZone != FocusZone.CHANNEL_LIST) return@onFocusChanged
                        if (state.isFocused) {
                            scope.launch {
                                requestListFocus()
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
                        placeholder = "Search channels",
                        showFocusRing = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .focusProperties { left = railFocusRequester },
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
                    IptvCategoryItem(
                        category = category,
                        isSelected = index == selectedGroupIndex,
                        modifier = Modifier
                            .then(
                                if (index == focusedChannelIndex) {
                                    Modifier.focusRequester(focusedCategoryFocusRequester)
                                } else {
                                    Modifier
                                },
                            )
                            .focusProperties {
                                left = railFocusRequester
                                canFocus = focusedZone == FocusZone.CHANNEL_LIST
                            },
                        onFocused = {
                            focusedChannelIndex = index
                            focusedChannelId = category.name
                            focusedZone = FocusZone.CHANNEL_LIST
                            onContentFocused(focusedCategoryFocusRequester)
                        },
                        onClick = {
                            focusedChannelIndex = index
                            focusedChannelId = category.name
                            selectedChannelId = category.name
                            focusGridZone()
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
                    // Merge on-demand EPG programmes with any existing guide data
                    val effectiveProgrammes = if (onDemandProgrammes.isNotEmpty()) {
                        state.guideProgrammes + onDemandProgrammes
                    } else {
                        state.guideProgrammes
                    }
                    TvEpgGrid(
                        channels = channelsInGroup,
                        guideProgrammes = effectiveProgrammes,
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
