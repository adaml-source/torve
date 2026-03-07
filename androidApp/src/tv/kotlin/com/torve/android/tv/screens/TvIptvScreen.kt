package com.torve.android.tv.screens

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.torve.presentation.channels.EpgState
import com.torve.presentation.channels.ChannelsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val SIDEBAR_WEIGHT = 0.30f
private const val PREVIEW_WEIGHT = 0.35f
private const val GRID_WEIGHT = 0.65f
private const val MAX_FORWARD_HOURS = 12
private const val PAGE_DURATION_MS = IPTV_EPG_WINDOW_HOURS * 60L * 60L * 1000L
private const val MAX_PAGE_OFFSET = MAX_FORWARD_HOURS / IPTV_EPG_WINDOW_HOURS

private enum class FocusZone {
    CHANNEL_LIST,
    EPG_GRID,
}

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
    isRailFocused: Boolean = false,
    isRailExpanded: Boolean = false,
    onCollapseRail: () -> Unit = {},
    onNavigateUp: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    var focusedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedChannel by remember { mutableStateOf<EnrichedChannel?>(null) }
    var focusedProgramme by remember { mutableStateOf<EpgProgramme?>(null) }
    var windowPageOffset by rememberSaveable { mutableIntStateOf(0) }
    var focusedZone by rememberSaveable { mutableStateOf(FocusZone.CHANNEL_LIST) }
    var focusedChannelIndex by rememberSaveable { mutableIntStateOf(0) }
    var lastGridRowIndex by rememberSaveable { mutableIntStateOf(0) }
    var lastGridColIndex by rememberSaveable { mutableIntStateOf(0) }
    var gridFocusRequestToken by rememberSaveable { mutableIntStateOf(0) }
    var wasActive by remember { mutableStateOf(false) }

    val sidebarFocusRequester = remember { FocusRequester() }
    val focusedCategoryFocusRequester = remember { FocusRequester() }
    val sidebarListState = rememberLazyListState()

    onFirstContentRequester(focusedCategoryFocusRequester)

    LaunchedEffect(isActive, focusedZone) {
        TvIptvRailState.hideRail.value = isActive && focusedZone == FocusZone.EPG_GRID
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
            state.showCountryFilter -> viewModel.setCountryFilterVisible(false)
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

    if (state.isLoading || state.isLoadingChannels) {
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

    val displayCategories = remember(
        state.categories,
        state.favorites,
        state.recentlyViewedChannels,
        state.selectedCountries,
    ) {
        val actualCategories = state.categories
            .filter { it.channels.isNotEmpty() }
            .sortedWith(compareBy<ChannelCategory>({ (it.countryCode ?: "ZZZ").uppercase() }, { it.name.lowercase() }))

        val filteredFavorites = state.favorites.filter {
            channelMatchesCountryFilter(it, state.selectedCountries)
        }
        val filteredRecentlyViewed = state.recentlyViewedChannels.filter {
            channelMatchesCountryFilter(it, state.selectedCountries)
        }

        buildList {
            val allChannels = actualCategories.flatMap { it.channels }
            if (allChannels.isNotEmpty()) {
                add(
                    ChannelCategory(
                        name = allChannelsLabel,
                        channelCount = allChannels.size,
                        channels = allChannels,
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
        if (focusedCurrent == null || displayCategories.none { it.name == focusedCurrent }) {
            focusedChannelId = displayCategories[focusedChannelIndex].name
        }
        val current = selectedChannelId
        if (current == null || displayCategories.none { it.name == current }) {
            selectedChannelId = focusedChannelId
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
    val channelsInGroup = focusedCategory?.channels.orEmpty()
    val windowAnchorMs = remember { alignedHalfHour(System.currentTimeMillis()) }
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
        } else if (targetCategory.channels.isNotEmpty()) {
            lastGridRowIndex = lastGridRowIndex.coerceIn(0, targetCategory.channels.lastIndex)
        }
        if (targetCategory.channels.isEmpty()) return
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
        val nextFocused = stillVisible ?: channelsInGroup[lastGridRowIndex]
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

    LaunchedEffect(isActive, state.showCountryFilter) {
        if (!isActive || state.showCountryFilter) {
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

    var wasCountryFilterOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.showCountryFilter, isActive) {
        if (!isActive) {
            wasCountryFilterOpen = state.showCountryFilter
            return@LaunchedEffect
        }
        if (!state.showCountryFilter && wasCountryFilterOpen) {
            delay(80)
            if (focusedZone == FocusZone.EPG_GRID && channelsInGroup.isNotEmpty()) {
                gridFocusRequestToken += 1
            } else {
                focusedZone = FocusZone.CHANNEL_LIST
                requestListFocus()
            }
        }
        wasCountryFilterOpen = state.showCountryFilter
    }

    val countryFilterLabel = if (state.selectedCountries.isEmpty()) {
        stringResource(R.string.tv_iptv_countries_all)
    } else {
        stringResource(R.string.tv_iptv_countries_selected, state.selectedCountries.size)
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
                item(key = "list_controls") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.tv_iptv_lists_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = Silver,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                        TvIptvControlChip(
                            label = stringResource(
                                R.string.tv_iptv_filter_chip,
                                filterLabel(state.activeFilter),
                            ),
                            onClick = { viewModel.setFilter(nextFilter(state.activeFilter)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TvIptvControlChip(
                            label = stringResource(
                                R.string.tv_iptv_sort_chip,
                                sortLabel(state.activeSort),
                            ),
                            onClick = { viewModel.setSort(nextSort(state.activeSort)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TvIptvControlChip(
                            label = countryFilterLabel,
                            onClick = { viewModel.setCountryFilterVisible(true) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
                    isActive = isActive,
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
                    val noChannelProgrammeText = "No EPG for this channel"
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
                                    if (lastGridColIndex > 0) return@onPreviewKeyEvent false
                                    if (windowPageOffset > 0) {
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
                    TvEpgGrid(
                        channels = channelsInGroup,
                        guideProgrammes = state.guideProgrammes,
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
                        onChannelPlay = { channel ->
                            viewModel.recordChannelViewed(channel)
                            onChannelPlay(channel)
                        },
                        onTimeForward = {
                            if (windowPageOffset < MAX_PAGE_OFFSET) {
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
                                Text(text = "Loading EPG..", color = Silver)
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
                                    text = "EPG not configured. Open Settings > Channels to add XMLTV URL.",
                                    color = Silver,
                                    textAlign = TextAlign.Center,
                                )
                                TvIptvControlChip(
                                    label = "Open Settings",
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

        if (state.showCountryFilter) {
            TvCountryFilterOverlay(
                availableCountries = state.availableCountries,
                selectedCountries = state.selectedCountries,
                onToggleCountry = { viewModel.toggleCountry(it) },
                onSelectAll = { viewModel.setCountryFilter(state.availableCountries.toSet()) },
                onSetCountries = { viewModel.setCountryFilter(it) },
                onClearAll = { viewModel.clearCountryFilter() },
                onDismiss = { viewModel.setCountryFilterVisible(false) },
            )
        }

        heroOverlay?.invoke()
    }
}
