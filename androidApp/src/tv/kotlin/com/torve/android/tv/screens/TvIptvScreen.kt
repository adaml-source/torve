package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import com.torve.android.tv.components.tvLiquidGlass

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
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
import com.torve.presentation.channels.userMessage
import com.torve.presentation.channels.ChannelsViewModel
import com.torve.presentation.channels.ChannelsFilterType
import com.torve.android.ui.player.ActivePlaybackState
import com.torve.presentation.tvhome.TvHomeOutcomeViewModel
import kotlinx.coroutines.Dispatchers
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

private val IPTV_CATEGORY_PANEL_WIDTH = 208.dp
private const val MAX_FORWARD_HOURS = 72
private const val PAGE_DURATION_MS = IPTV_EPG_WINDOW_HOURS * 60L * 60L * 1000L
private const val IPTV_SCREEN_CACHE_KEY = "tv_iptv_screen_state"
private const val IPTV_STARTUP_LOG_TAG = "TvStartupRecovery"
private const val TV_STAGED_SHELF_WARMUP_DELAY_MS = 1_200L
private const val IPTV_VISIBLE_EPG_CHANNEL_LIMIT = 260
private val iptvHeaderTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val iptvHeaderDateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

private enum class FocusZone {
    CHANNEL_LIST,
    EPG_GRID,
    GUIDE_TOOLS,
}

private enum class LeftFocusTarget {
    SEARCH,
    MANAGE,
    OPTIONS,
    CATEGORY,
}

private data class GuideProgrammeActionTarget(
    val channel: Channel,
    val programme: EpgProgramme?,
    val replayUrl: String?,
)

private data class TvIptvScreenCacheState(
    val focusedChannelId: String? = null,
    val selectedChannelId: String? = null,
    val focusedZone: String = FocusZone.CHANNEL_LIST.name,
    val leftFocusTarget: String = LeftFocusTarget.CATEGORY.name,
    val focusedChannelIndex: Int = 0,
    val lastGridRowIndex: Int = 0,
    val lastGridColIndex: Int = 0,
    val windowPageOffset: Int = 0,
    val guideAnchorMs: Long? = null,
    val guideMode: String = TvChannelGuideMode.AUTO.name,
    val eventFilter: String = TvChannelEventFilter.ACTIVE.name,
    val quality: String = ChannelsFilterType.ALL.name,
    val country: String? = null,
    val sort: String = TvChannelBrowseSort.RECOMMENDED.name,
)

private data class UnifiedIptvSearchResult(
    val query: String,
    val playlistNameMatches: Boolean,
    val channels: List<EnrichedChannel>,
)

private fun unifiedIptvSearch(
    query: String,
    selectedPlaylistId: String?,
    playlists: List<com.torve.domain.model.ChannelPlaylist>,
    channels: List<EnrichedChannel>,
    repositoryMatches: List<Channel>,
    programmesByChannelKey: Map<String, List<EpgProgramme>>,
): UnifiedIptvSearchResult {
    val needle = query.trim()
    val activeChannels = (channels + repositoryMatches.map { channel -> EnrichedChannel(channel = channel) })
        .filter { enriched ->
            val playlistId = enriched.channel.playlistId
            selectedPlaylistId.isNullOrBlank() || playlistId.isBlank() || playlistId == selectedPlaylistId
        }
        .filter { isPlayableTvBrowseChannel(it.channel) }
        .distinctBy { tvChannelBrowseKey(it.channel) }
    if (needle.isBlank()) {
        return UnifiedIptvSearchResult(needle, playlistNameMatches = false, channels = activeChannels)
    }

    val playlistNameMatches = playlists
        .firstOrNull { it.id == selectedPlaylistId }
        ?.name
        ?.contains(needle, ignoreCase = true) == true
    if (playlistNameMatches) {
        return UnifiedIptvSearchResult(needle, playlistNameMatches = true, channels = activeChannels)
    }

    val matches = activeChannels.filter { enriched ->
        val channel = enriched.channel
        channel.name.contains(needle, ignoreCase = true) ||
            channel.tvgName?.contains(needle, ignoreCase = true) == true ||
            channel.groupTitle?.contains(needle, ignoreCase = true) == true ||
            programmesForEpgChannel(
                programmesByChannelKey = programmesByChannelKey,
                playlistId = channel.playlistId.ifBlank { selectedPlaylistId.orEmpty() },
                channel = channel,
            ).any { programme -> programme.matchesIptvSearch(needle) }
    }
    return UnifiedIptvSearchResult(needle, playlistNameMatches = false, channels = matches)
}

private fun EpgProgramme.matchesIptvSearch(query: String): Boolean =
    title.contains(query, ignoreCase = true) ||
        subTitle?.contains(query, ignoreCase = true) == true ||
        description?.contains(query, ignoreCase = true) == true ||
        category?.contains(query, ignoreCase = true) == true

@Composable
private fun TvEmptyChannelsAction(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(220.dp)
            .height(54.dp)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (focused) Amber.copy(alpha = 0.20f) else Graphite.copy(alpha = 0.90f),
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Amber else Steel.copy(alpha = 0.55f),
                shape = RoundedCornerShape(16.dp),
            )
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onFocused()
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = localizedUiText(label),
            color = if (focused) Snow else Silver,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal val tvIptvBackgroundBrush = Brush.linearGradient(listOf(Obsidian, Color(0xFF171D2D), Obsidian))

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun TvIptvScreen(
    railFocusRequester: FocusRequester,
    heroOverlay: (@Composable () -> Unit)? = null,
    onChannelPlay: (Channel) -> Unit,
    onProgrammePlayFromBeginning: (Channel, EpgProgramme, String) -> Unit,
    onOpenEpgSettings: () -> Unit = {},
    onOpenChannelSources: () -> Unit = onOpenEpgSettings,
    onOpenAccountSettings: () -> Unit = {},
    isSignedIn: Boolean = true,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    viewModel: ChannelsViewModel = koinInject(),
    outcomeViewModel: TvHomeOutcomeViewModel = koinInject(),
    localSettingsRepo: DeviceLocalSettingsRepository = koinInject(),
    shouldAutoFocus: Boolean = true,
    isActive: Boolean = true,
    isSubRouteActive: Boolean = false,
    isRailFocused: Boolean = false,
    isRailExpanded: Boolean = false,
    onCollapseRail: () -> Unit = {},
    onNavigateUp: () -> Unit = {},
    drawBackground: Boolean = true,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel, outcomeViewModel) {
        outcomeViewModel.attachChannels(viewModel)
    }

    val cachedScreenState = remember {
        TvScreenCache.get<TvIptvScreenCacheState>(IPTV_SCREEN_CACHE_KEY) ?: TvIptvScreenCacheState()
    }

    var focusedChannelId by rememberSaveable { mutableStateOf(cachedScreenState.focusedChannelId) }
    var selectedChannelId by rememberSaveable { mutableStateOf(cachedScreenState.selectedChannelId) }
    var focusedChannel by remember { mutableStateOf<EnrichedChannel?>(null) }
    var focusedProgramme by remember { mutableStateOf<EpgProgramme?>(null) }
    var windowPageOffset by rememberSaveable { mutableIntStateOf(cachedScreenState.windowPageOffset) }
    var guideAnchorMs by rememberSaveable { mutableStateOf(cachedScreenState.guideAnchorMs) }
    var guideMode by rememberSaveable { mutableStateOf(runCatching { TvChannelGuideMode.valueOf(cachedScreenState.guideMode) }.getOrDefault(TvChannelGuideMode.AUTO)) }
    var eventFilter by rememberSaveable { mutableStateOf(runCatching { TvChannelEventFilter.valueOf(cachedScreenState.eventFilter) }.getOrDefault(TvChannelEventFilter.ACTIVE)) }
    var browseQuality by rememberSaveable { mutableStateOf(runCatching { ChannelsFilterType.valueOf(cachedScreenState.quality) }.getOrDefault(ChannelsFilterType.ALL)) }
    var browseCountry by rememberSaveable { mutableStateOf(cachedScreenState.country) }
    var browseSort by rememberSaveable { mutableStateOf(runCatching { TvChannelBrowseSort.valueOf(cachedScreenState.sort) }.getOrDefault(TvChannelBrowseSort.RECOMMENDED)) }
    var showBrowseOptions by remember { mutableStateOf(false) }
    var showGuideDayPicker by remember { mutableStateOf(false) }
    var leftPanelFocusToken by remember { mutableIntStateOf(0) }
    val guideToolbarFocusRequester = remember { FocusRequester() }
    val guideDayFocusRequester = remember { FocusRequester() }
    var lastGuideToolRequester by remember { mutableStateOf(guideToolbarFocusRequester) }
    var lastLeftFocusTarget by rememberSaveable {
        mutableStateOf(
            runCatching { LeftFocusTarget.valueOf(cachedScreenState.leftFocusTarget) }
                .getOrDefault(LeftFocusTarget.CATEGORY),
        )
    }
    var focusedZone by rememberSaveable {
        mutableStateOf(
            runCatching { FocusZone.valueOf(cachedScreenState.focusedZone) }
                .getOrDefault(FocusZone.CHANNEL_LIST),
        )
    }
    var pendingGroupFocusReturn by remember { mutableStateOf(false) }
    var actualFocusedGroupName by remember { mutableStateOf<String?>(null) }
    val currentScreenActive by rememberUpdatedState(isActive)
    val currentRailFocused by rememberUpdatedState(isRailFocused)
    var focusedChannelIndex by rememberSaveable { mutableIntStateOf(cachedScreenState.focusedChannelIndex) }
    var lastGridRowIndex by rememberSaveable { mutableIntStateOf(cachedScreenState.lastGridRowIndex) }
    var lastGridColIndex by rememberSaveable { mutableIntStateOf(cachedScreenState.lastGridColIndex) }
    var gridFocusRequestToken by rememberSaveable { mutableIntStateOf(0) }
    var guideProgrammeActionTarget by remember { mutableStateOf<GuideProgrammeActionTarget?>(null) }
    var modalReturnZone by remember { mutableStateOf(FocusZone.CHANNEL_LIST) }
    var modalReturnLeftTarget by remember { mutableStateOf(LeftFocusTarget.CATEGORY) }
    var modalReturnGuideRequester by remember { mutableStateOf(guideToolbarFocusRequester) }
    var pendingLeftFocusTarget by remember { mutableStateOf<LeftFocusTarget?>(null) }
    var wasActive by remember { mutableStateOf(false) }

    val sidebarFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val manageFocusRequester = remember { FocusRequester() }
    val optionsFocusRequester = remember { FocusRequester() }
    val sidebarListState = rememberLazyListState()
    var groupFocusMovePending by remember { mutableStateOf(false) }
    var screenCenterY by remember { mutableFloatStateOf(0f) }
    var groupViewportTop by remember { mutableFloatStateOf(0f) }
    var groupViewportHeight by remember { mutableFloatStateOf(0f) }
    val groupCenter = (screenCenterY - groupViewportTop).coerceIn(0f, groupViewportHeight)
    val groupBringIntoViewSpec = remember(groupCenter) { TvChannelCenterBringIntoViewSpec(groupCenter.takeIf { it > 0f }) }
    var iptvSearchFieldFocused by remember { mutableStateOf(false) }
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

    val showCategoryPanel = isRailFocused || focusedZone == FocusZone.CHANNEL_LIST || pendingGroupFocusReturn
    val contentStartPadding by animateDpAsState(
        targetValue = if (showCategoryPanel) TV_PAGE_CONTENT_GUTTER else 24.dp,
        animationSpec = tween(160), label = "channelsContentInset",
    )
    LaunchedEffect(isActive, focusedZone, isRailFocused, pendingGroupFocusReturn) {
        TvIptvRailState.hideRail.value = isActive && !isRailFocused && (!showCategoryPanel || pendingGroupFocusReturn)
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
        guideAnchorMs, guideMode, eventFilter, browseQuality, browseCountry, browseSort,
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
                guideAnchorMs = guideAnchorMs,
                guideMode = guideMode.name,
                eventFilter = eventFilter.name,
                quality = browseQuality.name,
                country = browseCountry,
                sort = browseSort.name,
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

    LaunchedEffect(state.playlists) {
        if (state.playlists.isNotEmpty() && state.selectedPlaylistId == null) {
            viewModel.selectPlaylist(state.playlists.first().id)
        }
    }

    if (!state.isLoading && !state.isLoadingChannels && state.playlists.isEmpty()) {
        val primaryActionRequester = remember(isSignedIn) { FocusRequester() }
        // Publish the requester on every composition so the root never retains
        // the detached normal-sidebar requester while this empty state is shown.
        onFirstContentRequester(primaryActionRequester)
        LaunchedEffect(primaryActionRequester, shouldAutoFocus, isRailFocused) {
            if (shouldAutoFocus && !isRailFocused) {
                delay(60L)
                runCatching { primaryActionRequester.requestFocus() }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 60.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    if (isSignedIn) {
                        R.string.tv_iptv_no_playlists_title
                    } else {
                        R.string.tv_iptv_sign_in_title
                    },
                ),
                style = MaterialTheme.typography.headlineMedium,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    if (isSignedIn) {
                        R.string.tv_iptv_no_playlists_subtitle
                    } else {
                        R.string.tv_iptv_sign_in_subtitle
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = Silver,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvEmptyChannelsAction(
                    label = stringResource(
                        if (isSignedIn) R.string.tv_iptv_sync_sources else R.string.tv_iptv_sign_in_action,
                    ),
                    focusRequester = primaryActionRequester,
                    onFocused = { onContentFocused(primaryActionRequester) },
                    onClick = {
                        if (isSignedIn) {
                            viewModel.loadPlaylists(recoverEmptyCatalog = true)
                            viewModel.loadFavorites()
                        } else {
                            onOpenAccountSettings()
                        }
                    },
                )
                if (isSignedIn) {
                    TvEmptyChannelsAction(
                        label = stringResource(R.string.tv_iptv_manage_sources),
                        onClick = onOpenEpgSettings,
                    )
                }
            }
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

    onFirstContentRequester(sidebarFocusRequester)

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

    LaunchedEffect(iptvSearchQuery) {
        viewModel.updateSearchQuery(iptvSearchQuery)
    }

    val baseDisplayCategories = remember(
        state.categories,
        state.favorites,
        state.recentlyViewedChannels,
        state.selectedCountries,
        state.selectedPlaylistId,
        browseCountry, browseQuality, eventFilter,
        allChannelsLabel,
        favoritesLabel,
        recentlyViewedLabel,
    ) {
        val actualCategories = state.categories
            .filter { it.channels.isNotEmpty() || it.channelCount > 0 }
            .filterNot { it.name.startsWith("VOD:", ignoreCase = true) || it.name.equals("VOD", ignoreCase = true) }
            .filter { browseCountry == null || it.countryCode?.let(::tvNormalizeCountry) == browseCountry }
            .sortedWith(compareBy<ChannelCategory>({ (it.countryCode ?: "ZZZ").uppercase() }, { it.name.lowercase() }))

        fun personalChannels(channels: List<Channel>) = tvBrowseChannels(
            channels.filter {
                (it.playlistId.isBlank() || it.playlistId == state.selectedPlaylistId) &&
                    channelMatchesCountryFilter(it, state.selectedCountries)
            }.map { EnrichedChannel(it) },
            eventFilter = eventFilter, quality = browseQuality, country = browseCountry, preserveOrder = true,
        )
        val filteredFavorites = personalChannels(state.favorites)
        val filteredRecentlyViewed = personalChannels(state.recentlyViewedChannels)

        buildList {
            add(ChannelCategory(name = favoritesLabel, channelCount = filteredFavorites.size,
                channels = filteredFavorites, countryCode = "\u2764"))
            add(ChannelCategory(name = recentlyViewedLabel, channelCount = filteredRecentlyViewed.size,
                channels = filteredRecentlyViewed, countryCode = "\u23F2"))
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
            addAll(actualCategories)
        }
    }

    // Provider playlists can contain tens of thousands of channels. Building
    // the cross-category/EPG projection on the Compose thread made each remote
    // keystroke concatenate, hash, deduplicate and scan the complete catalog
    // before a frame could be drawn. Keep cached categories immediate and do
    // the cancellable projection away from the UI thread.
    val searchedDisplayCategories by produceState(
        initialValue = baseDisplayCategories,
        iptvSearchQuery,
        baseDisplayCategories,
        state.searchQuery,
        state.searchResults,
        state.playlists,
        state.selectedPlaylistId,
        state.channels,
        state.guideChannels,
        state.categories,
        state.guideProgrammes,
        loadedShelvesByCategory,
    ) {
        val requestedQuery = iptvSearchQuery.trim()
        if (requestedQuery.isBlank()) {
            value = baseDisplayCategories
            return@produceState
        }

        delay(120L)
        val repositoryMatches = if (state.searchQuery.trim() == requestedQuery) {
            state.searchResults
        } else {
            emptyList()
        }
        value = withContext(Dispatchers.Default) {
            val search = unifiedIptvSearch(
                query = requestedQuery,
                selectedPlaylistId = state.selectedPlaylistId,
                playlists = state.playlists,
                channels = state.channels +
                    state.guideChannels +
                    state.categories.flatMap { it.channels } +
                    loadedShelvesByCategory.values.flatMap { it.channels },
                repositoryMatches = repositoryMatches,
                programmesByChannelKey = state.guideProgrammes,
            )
            if (search.query.isBlank()) {
                baseDisplayCategories
            } else if (search.playlistNameMatches) {
                baseDisplayCategories
            } else {
                val matchingIds = search.channels.mapTo(mutableSetOf()) { stableChannelId(it.channel) }
                val matchedCategories = baseDisplayCategories.mapNotNull { category ->
                    val groupMatches = category.name.contains(search.query, ignoreCase = true)
                    val matchingChannels = category.channels.filter {
                        stableChannelId(it.channel) in matchingIds
                    }
                    when {
                        groupMatches -> category
                        matchingChannels.isNotEmpty() -> category.copy(
                            channelCount = matchingChannels.size,
                            channels = matchingChannels,
                        )
                        else -> null
                    }
                }
                val representedIds = matchedCategories
                    .flatMap { it.channels }
                    .mapTo(mutableSetOf()) { stableChannelId(it.channel) }
                val unmatched = search.channels.filter {
                    stableChannelId(it.channel) !in representedIds
                }
                if (unmatched.isEmpty()) {
                    matchedCategories
                } else {
                    listOf(
                        ChannelCategory(
                            name = "Search results",
                            channelCount = unmatched.size,
                            channels = unmatched,
                        ),
                    ) + matchedCategories
                }
            }
        }
    }

    // The unfiltered catalog is already cheap and ready. Read it directly so a
    // one-frame-old search projection cannot briefly select an empty pinned row.
    val displayCategories = if (iptvSearchQuery.isBlank()) baseDisplayCategories else searchedDisplayCategories

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

    LaunchedEffect(displayCategories, hasRenderableCatalog) {
        // Pinned personal groups exist before the first catalog arrives. They
        // must not replace the saved group while that initial load is pending.
        if (!hasRenderableCatalog) return@LaunchedEffect
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
            focusedChannelId = persistedGroupName ?: displayCategories.firstOrNull {
                it.channelCount > 0 && it.name != allChannelsLabel
            }?.name ?: displayCategories[focusedChannelIndex].name
        }
        val current = selectedChannelId
        if (current == null || displayCategories.none { it.name == current }) {
            selectedChannelId = persistedGroupName ?: focusedChannelId
        }
        focusedChannelId?.let { restoredGroup ->
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

    val focusedGroupIndex = remember(displayCategories, focusedChannelId, focusedChannelIndex, state.selectedGroup) {
        if (displayCategories.isEmpty()) {
            -1
        } else {
            displayCategories.indexOfFirst { it.name == (focusedChannelId ?: state.selectedGroup) }.takeIf { it >= 0 }
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
        val targetIndex = displayCategories.indexOfFirst { it.name == (focusedChannelId ?: state.selectedGroup) }.takeIf { it >= 0 }
            ?: focusedChannelIndex.coerceIn(0, maxCategoryIndex)
        return categoryRequesterAt(targetIndex)
    }

    fun leftEntryRequester(): FocusRequester = when {
        displayCategories.isEmpty() || lastLeftFocusTarget == LeftFocusTarget.SEARCH -> searchFocusRequester
        lastLeftFocusTarget == LeftFocusTarget.OPTIONS -> optionsFocusRequester
        lastLeftFocusTarget == LeftFocusTarget.MANAGE -> manageFocusRequester
        else -> focusedCategoryRequester()
    }

    LaunchedEffect(state.selectedPlaylistId) {
        if (!state.selectedPlaylistId.isNullOrBlank()) {
            viewModel.hydrateCachedEpgOnly()
        }
    }

    LaunchedEffect(displayCategories, focusedGroupIndex, lastLeftFocusTarget) {
        if (displayCategories.isEmpty()) {
            lastLeftFocusTarget = LeftFocusTarget.SEARCH
            onFirstContentRequester(searchFocusRequester)
            if (isActive && !isRailFocused && !iptvSearchFieldFocused) {
                delay(32)
                runCatching { searchFocusRequester.requestFocus() }
            }
        } else {
            onFirstContentRequester(leftEntryRequester())
        }
    }

    val rawChannelsInGroup = remember(
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
    val browseProjectionKey = "${state.selectedPlaylistId}|${focusedCategory?.name}|$eventFilter|$browseQuality|$browseCountry|$browseSort"
    val browseProjection by produceState(initialValue = Pair<String?, List<EnrichedChannel>>(null, emptyList()),
        browseProjectionKey, rawChannelsInGroup, focusedCategory, state.selectedCountries, browseCountry, browseQuality, eventFilter, browseSort) {
        val projected = withContext(Dispatchers.Default) {
            tvBrowseChannels(
                channels = rawChannelsInGroup.filter { channelMatchesCountryFilter(it.channel, state.selectedCountries) },
                eventFilter = eventFilter, quality = browseQuality, country = browseCountry,
                categoryCountry = focusedCategory?.countryCode, sort = browseSort,
                preserveOrder = focusedCategory?.name in listOf(favoritesLabel, recentlyViewedLabel),
            )
        }
        value = browseProjectionKey to projected
    }
    val isBrowseProjectionReady = browseProjection.first == browseProjectionKey
    val channelsInGroup = if (isBrowseProjectionReady) browseProjection.second else emptyList()
    // Recompute the anchor every 30 s so the EPG timeline tracks real time.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val currentWindowAnchorMs = remember(nowMs) { alignedHalfHour(nowMs) }
    val windowAnchorMs = guideAnchorMs ?: currentWindowAnchorMs
    val windowStartMs = windowAnchorMs + (windowPageOffset * PAGE_DURATION_MS)
    val windowEndMs = windowStartMs + PAGE_DURATION_MS
    val activePlaylistId = state.selectedPlaylistId.orEmpty()
    val shelfProgrammes = focusedCategory?.let { loadedShelvesByCategory[it.name]?.programmes }.orEmpty()
    val effectiveProgrammes = remember(state.guideProgrammes, shelfProgrammes, visibleDirectProgrammes) {
        mergeEpgProgrammeMaps(
            primary = mergeEpgProgrammeMaps(primary = state.guideProgrammes, secondary = shelfProgrammes),
            secondary = visibleDirectProgrammes,
        )
    }
    val guideCoverageCount = remember(channelsInGroup, effectiveProgrammes, activePlaylistId) {
        channelsInGroup.count { enriched -> programmesForEpgChannel(effectiveProgrammes,
            enriched.channel.playlistId.ifBlank { activePlaylistId }, enriched.channel).isNotEmpty() }
    }
    val showGuide = tvShouldShowChannelGuide(guideMode, channelsInGroup.size, guideCoverageCount, state.epgState is EpgState.Loading)
    val guideUnavailable = guideMode == TvChannelGuideMode.GUIDE && !showGuide && channelsInGroup.isNotEmpty() && isBrowseProjectionReady
    val modalOpen = showBrowseOptions || showGuideDayPicker || guideProgrammeActionTarget != null
    val favoriteIds = remember(state.favorites) { state.favorites.mapTo(mutableSetOf()) { stableChannelId(it) } }
    val availableBrowseCountries = remember(state.availableCountries, state.categories) {
        (state.availableCountries + state.categories.mapNotNull { it.countryCode })
            .filter { it.length in 2..3 && it.all(Char::isLetter) }.map(::tvNormalizeCountry).distinct().sorted()
    }

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

    fun focusOffscreenGroup(targetIndex: Int, direction: Int) {
        if (groupFocusMovePending || targetIndex !in displayCategories.indices) return
        groupFocusMovePending = true
        scope.launch {
            try {
                val layout = sidebarListState.layoutInfo
                val edge = if (direction > 0) layout.visibleItemsInfo.lastOrNull() else layout.visibleItemsInfo.firstOrNull()
                if (edge != null) {
                    val distance = if (direction > 0) edge.offset + edge.size * 2 - layout.viewportEndOffset
                    else edge.offset - edge.size - layout.viewportStartOffset
                    sidebarListState.scrollBy(distance.toFloat())
                }
                delay(48)
                if (sidebarListState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
                    sidebarListState.scrollToItem(targetIndex)
                    delay(48)
                }
                if (focusedZone == FocusZone.CHANNEL_LIST && !showBrowseOptions && !showGuideDayPicker && guideProgrammeActionTarget == null) {
                    runCatching { categoryRequesterAt(targetIndex).requestFocus() }
                }
            } finally {
                groupFocusMovePending = false
            }
        }
    }

    suspend fun requestListFocus(reason: String = "unspecified") {
        if (!hasRenderableCatalog) return
        if (showBrowseOptions || showGuideDayPicker || guideProgrammeActionTarget != null) return
        if (isRailFocused) return
        if (displayCategories.isEmpty()) return
        // Allow the persisted group restoration effect to commit before the first
        // request can turn the initial Favorites fallback into a user selection.
        if (focusedChannelId == null) delay(48)
        val itemIndex = (displayCategories.indexOfFirst { it.name == (focusedChannelId ?: state.selectedGroup) }.takeIf { it >= 0 }
            ?: focusedChannelIndex).coerceIn(0, maxCategoryIndex)
        val targetCategory = displayCategories.getOrNull(itemIndex)
        val targetRequester = categoryRequesterAt(itemIndex)
        val lazyItemIndex = itemIndex
        val visibleItemIndexes = sidebarListState.layoutInfo.visibleItemsInfo.map { it.index }
        if (lazyItemIndex !in visibleItemIndexes) {
            runCatching { sidebarListState.scrollToItem(lazyItemIndex) }
        }
        // The animated panel may still be composing or have zero width. Keep the
        // return intent until its real group target accepts focus, rather than
        // treating a non-throwing focus request as a successful handoff.
        repeat(32) {
            if (!currentScreenActive || currentRailFocused ||
                showBrowseOptions || showGuideDayPicker || guideProgrammeActionTarget != null ||
                (reason == "focus_list_zone" && !pendingGroupFocusReturn) ||
                (focusedZone != FocusZone.CHANNEL_LIST && !pendingGroupFocusReturn)) return
            withFrameNanos { }
            if (sidebarListState.layoutInfo.visibleItemsInfo.any { it.index == lazyItemIndex }) {
                runCatching { targetRequester.requestFocus() }
                if (targetCategory != null && actualFocusedGroupName == targetCategory.name) {
                    Log.d("TvIptvFocus", "requestListFocus reason=$reason index=$itemIndex success=true")
                    return
                }
            }
        }
        Log.w("TvIptvFocus", "requestListFocus not accepted reason=$reason target=${targetCategory?.name}")
        runCatching { sidebarFocusRequester.requestFocus() }
    }

    suspend fun requestLeftPanelFocus(reason: String = "unspecified", target: LeftFocusTarget = lastLeftFocusTarget) {
        if (!hasRenderableCatalog) return
        if (showBrowseOptions || showGuideDayPicker || guideProgrammeActionTarget != null) return
        if (isRailFocused) return
        when (target) {
            LeftFocusTarget.SEARCH -> {
                delay(32)
                lastLeftFocusTarget = target
                runCatching { searchFocusRequester.requestFocus() }
                    .onFailure { Log.w("TvIptvFocus", "requestSearchFocus failed reason=$reason: ${it.message}") }
            }
            LeftFocusTarget.MANAGE -> {
                delay(32)
                lastLeftFocusTarget = target
                runCatching { manageFocusRequester.requestFocus() }
                    .onFailure { Log.w("TvIptvFocus", "requestManageFocus failed reason=$reason: ${it.message}") }
            }
            LeftFocusTarget.OPTIONS -> {
                delay(48)
                lastLeftFocusTarget = target
                val result = runCatching { optionsFocusRequester.requestFocus() }
                Log.d("TvIptvFocus", "requestOptionsFocus reason=$reason success=${result.getOrDefault(false)}")
            }
            LeftFocusTarget.CATEGORY -> {
                lastLeftFocusTarget = target
                if (displayCategories.isEmpty()) {
                    lastLeftFocusTarget = LeftFocusTarget.SEARCH
                    delay(32)
                    runCatching { searchFocusRequester.requestFocus() }
                } else {
                    requestListFocus(reason)
                }
            }
        }
    }

    LaunchedEffect(leftPanelFocusToken) {
        if (leftPanelFocusToken > 0 && isActive && !modalOpen) {
            requestLeftPanelFocus("explicit_return", pendingLeftFocusTarget ?: lastLeftFocusTarget)
            pendingLeftFocusTarget = null
        }
    }

    LaunchedEffect(focusedZone, lastLeftFocusTarget, shouldAutoFocus, hasRenderableCatalog) {
        if (!isActive || isRailFocused) return@LaunchedEffect
        if (!shouldAutoFocus) return@LaunchedEffect
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
        pendingGroupFocusReturn = true
        actualFocusedGroupName = null
        focusedZone = FocusZone.CHANNEL_LIST
        lastLeftFocusTarget = LeftFocusTarget.CATEGORY
        onContentFocused(focusedCategoryRequester())
        scope.launch {
            try {
                requestListFocus("focus_list_zone")
            } finally {
                pendingGroupFocusReturn = false
            }
        }
    }

    fun focusGridZone() {
        if (displayCategories.isEmpty()) return
        val selectedIndex = (focusedGroupIndex.takeIf { it >= 0 } ?: focusedChannelIndex).coerceIn(0, maxCategoryIndex)
        val targetCategory = displayCategories[selectedIndex]
        focusedChannelId = targetCategory.name
        val selectionChanged = selectedChannelId != targetCategory.name
        selectedChannelId = targetCategory.name
        if (selectionChanged) {
            windowPageOffset = 0
            guideAnchorMs = null
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
        actualFocusedGroupName = null
        focusedZone = FocusZone.EPG_GRID
        onContentFocused(focusedCategoryRequester())
        gridFocusRequestToken += 1
    }

    fun rememberModalReturnFocus() {
        modalReturnZone = focusedZone
        modalReturnLeftTarget = lastLeftFocusTarget
        modalReturnGuideRequester = lastGuideToolRequester
    }

    fun openBrowseOptions() {
        rememberModalReturnFocus()
        showBrowseOptions = true
    }

    fun openProgrammeActions(channel: Channel, programme: EpgProgramme?) {
        rememberModalReturnFocus()
        val resolvedReplayUrl = programme?.let { viewModel.resolveCatchupUrl(channel, it) }
        guideProgrammeActionTarget = GuideProgrammeActionTarget(channel, programme,
            resolvedReplayUrl.takeIf { TvLivePlaybackPolicy.canOfferReplay(programme, resolvedReplayUrl, System.currentTimeMillis()) })
    }

    fun restoreBrowseFocus() {
        focusedZone = modalReturnZone
        if (modalReturnZone == FocusZone.EPG_GRID) {
            if (channelsInGroup.isNotEmpty()) gridFocusRequestToken += 1
            else focusListZone()
        } else if (modalReturnZone == FocusZone.GUIDE_TOOLS) {
            val requester = modalReturnGuideRequester
            scope.launch { delay(60); runCatching { requester.requestFocus() } }
        } else {
            pendingLeftFocusTarget = modalReturnLeftTarget
            leftPanelFocusToken += 1
        }
    }

    fun jumpGuide(startMs: Long?) {
        guideAnchorMs = startMs
        windowPageOffset = 0
        lastGridColIndex = -1
        if (focusedZone == FocusZone.EPG_GRID) gridFocusRequestToken += 1
    }

    fun pageGuide(delta: Int) {
        val next = windowStartMs + delta * PAGE_DURATION_MS
        val now = System.currentTimeMillis()
        if (next < now - 7 * 24 * 60 * 60_000L || next > now + MAX_FORWARD_HOURS * 60 * 60_000L) return
        jumpGuide(next)
    }

    LaunchedEffect(channelsInGroup, isBrowseProjectionReady, modalOpen) {
        if (isActive && !isRailFocused && !modalOpen && focusedZone == FocusZone.EPG_GRID &&
            isBrowseProjectionReady && channelsInGroup.isEmpty() && focusedCategory?.name !in restoringShelfNames) {
            focusListZone()
        }
    }

    BackHandler(enabled = isActive && !modalOpen) {
        when {
            iptvSearchFieldFocused -> {
                iptvKeyboardController?.hide()
                focusListZone()
            }
            focusedZone != FocusZone.CHANNEL_LIST -> focusListZone()
            else -> { pendingGroupFocusReturn = false; onNavigateUp() }
        }
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

    LaunchedEffect(isActive, isRailFocused, state.selectedPlaylistId, displayCategories.size, focusedGroupIndex, focusedZone, shouldAutoFocus, hasRenderableCatalog) {
        val focusKey = "${state.selectedPlaylistId.orEmpty()}:${displayCategories.size}"
        if (
            isActive &&
            !isRailFocused &&
            shouldAutoFocus &&
            hasRenderableCatalog &&
            displayCategories.isNotEmpty() &&
            focusedZone == FocusZone.CHANNEL_LIST &&
            requestedListFocusCatalogKey != focusKey
        ) {
            requestedListFocusCatalogKey = focusKey
            delay(90)
            requestLeftPanelFocus("catalog_ready")
        }
    }

    LaunchedEffect(channelsInGroup, focusedChannelId, state.selectedChannel) {
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
            channelsInGroup.firstOrNull { tvChannelBrowseKey(it.channel) == tvChannelBrowseKey(persisted) }
                ?: channelsInGroup.firstOrNull { stableChannelId(it.channel) == stableChannelId(persisted) }
        }
        // The selected channel can arrive after the locally cached group. While
        // the user is still in the sidebar, let that saved selection replace the
        // initial first-row preview. Once browsing rows, preserve their focus.
        val nextFocused = if (focusedZone == FocusZone.CHANNEL_LIST && restoredFocused != null) {
            restoredFocused
        } else {
            stillVisible ?: restoredFocused ?: channelsInGroup[lastGridRowIndex]
        }
        lastGridRowIndex = channelsInGroup.indexOf(nextFocused).coerceAtLeast(0)
        restoredFocused?.let { restored ->
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

        if (!wasActive && !isRailFocused && shouldAutoFocus) {
            delay(60)
            if (focusedZone == FocusZone.EPG_GRID && channelsInGroup.isNotEmpty()) {
                gridFocusRequestToken += 1
            } else if (focusedZone == FocusZone.CHANNEL_LIST) {
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

    // When the PersistentPlaybackBar is dismissed via Stop, it clears the session
    // but TvRoot's generic focus restore is suppressed while a sub-route is active.
    // Watch the session directly so we can reclaim focus ourselves.
    val activePlaybackSession = ActivePlaybackState.session
    var prevSessionNonNull by remember { mutableStateOf(activePlaybackSession != null) }
    LaunchedEffect(activePlaybackSession) {
        val hasSession = activePlaybackSession != null
        if (prevSessionNonNull && !hasSession && isActive && !isRailFocused) {
            delay(80)
            Log.d("TvIptv", "Playback session stopped: restoring focus to zone=$focusedZone")
            if (focusedZone == FocusZone.EPG_GRID && channelsInGroup.isNotEmpty()) {
                gridFocusRequestToken += 1
            } else {
                requestLeftPanelFocus("session_stopped")
            }
        }
        prevSessionNonNull = hasSession
    }

    Box(Modifier.fillMaxSize()
        .then(if (drawBackground) Modifier.background(tvIptvBackgroundBrush) else Modifier)
        .testTag("channels-browser")
        .onGloballyPositioned { screenCenterY = it.boundsInRoot().center.y }) {
        Row(
            modifier = Modifier.fillMaxSize()
                .padding(start = contentStartPadding, end = 24.dp, top = 22.dp, bottom = TV_PAGE_BOTTOM_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AnimatedVisibility(
                visible = showCategoryPanel,
                enter = expandHorizontally(animationSpec = tween(160), expandFrom = Alignment.Start) + fadeIn(tween(160)),
                exit = shrinkHorizontally(animationSpec = tween(160), shrinkTowards = Alignment.Start) + fadeOut(tween(160)),
            ) {
            Column(
                modifier = Modifier.width(IPTV_CATEGORY_PANEL_WIDTH).fillMaxHeight()
                    .focusRequester(sidebarFocusRequester)
                    .focusProperties { enter = { leftEntryRequester() } }
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        if (!isActive || modalOpen || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionRight -> {
                                if (lastLeftFocusTarget == LeftFocusTarget.CATEGORY) { focusGridZone(); true }
                                else false
                            }
                            Key.Menu -> { openBrowseOptions(); true }
                            Key.DirectionLeft -> {
                                if (lastLeftFocusTarget == LeftFocusTarget.MANAGE) false
                                else { runCatching { railFocusRequester.requestFocus() }; true }
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.torve.android.ui.components.TorveSearchField(
                    value = iptvSearchQuery,
                    onValueChange = { iptvSearchQuery = it },
                    placeholder = stringResource(R.string.tv_iptv_search_hint),
                    showFocusRing = true, editOnClick = true,
                    onMoveDownFromEdit = {
                        lastLeftFocusTarget = LeftFocusTarget.OPTIONS
                        runCatching { optionsFocusRequester.requestFocus() }
                    },
                    modifier = Modifier.fillMaxWidth().height(38.dp).focusRequester(searchFocusRequester)
                        .focusProperties {
                            left = railFocusRequester; up = searchFocusRequester; down = optionsFocusRequester
                            right = searchFocusRequester; canFocus = focusedZone == FocusZone.CHANNEL_LIST
                        }
                        .onFocusChanged {
                            iptvSearchFieldFocused = it.hasFocus
                            if (it.hasFocus) { lastLeftFocusTarget = LeftFocusTarget.SEARCH; onContentFocused(searchFocusRequester) }
                        },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IptvPanelActionRow(Icons.Filled.Tune, localizedUiText("Filters"), onClick = { openBrowseOptions() },
                        modifier = Modifier.weight(1f).height(32.dp).focusRequester(optionsFocusRequester)
                            .focusProperties {
                                left = railFocusRequester; up = searchFocusRequester; right = manageFocusRequester
                                down = categoryFocusRequesters.firstOrNull() ?: searchFocusRequester
                                canFocus = focusedZone == FocusZone.CHANNEL_LIST
                            },
                        onFocused = { lastLeftFocusTarget = LeftFocusTarget.OPTIONS; onContentFocused(optionsFocusRequester) })
                    IptvPanelActionRow(Icons.Filled.Settings, localizedUiText("Manage"), onClick = onOpenEpgSettings,
                        modifier = Modifier.width(102.dp).height(32.dp).focusRequester(manageFocusRequester)
                            .focusProperties {
                                left = optionsFocusRequester; up = searchFocusRequester; right = manageFocusRequester
                                down = categoryFocusRequesters.firstOrNull() ?: searchFocusRequester
                                canFocus = focusedZone == FocusZone.CHANNEL_LIST
                            },
                        onFocused = { lastLeftFocusTarget = LeftFocusTarget.MANAGE; onContentFocused(manageFocusRequester) })
                }
                Text(localizedUiText("Channel groups"), color = Silver, fontSize = 10.sp, lineHeight = 13.sp)
                CompositionLocalProvider(LocalBringIntoViewSpec provides groupBringIntoViewSpec) {
                    LazyColumn(state = sidebarListState,
                        modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds().testTag("channels-groups")
                            .onGloballyPositioned { groupViewportTop = it.positionInRoot().y; groupViewportHeight = it.size.height.toFloat() }) {
                        itemsIndexed(displayCategories, key = { _, category -> category.name }) { index, category ->
                            val requester = categoryRequesterAt(index)
                            IptvCategoryItem(
                                category = category, isSelected = index == selectedGroupIndex,
                                guideOwnsFocus = focusedZone != FocusZone.CHANNEL_LIST && !pendingGroupFocusReturn,
                                modifier = Modifier.focusRequester(requester).focusProperties {
                                    left = railFocusRequester
                                    up = if (index > 0) categoryRequesterAt(index - 1) else optionsFocusRequester
                                    down = if (index < displayCategories.lastIndex) categoryRequesterAt(index + 1) else requester
                                    canFocus = focusedZone == FocusZone.CHANNEL_LIST || pendingGroupFocusReturn
                                }.onFocusChanged {
                                    if (it.isFocused) actualFocusedGroupName = category.name
                                    else if (actualFocusedGroupName == category.name) actualFocusedGroupName = null
                                }.onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown || focusedZone != FocusZone.CHANNEL_LIST) return@onPreviewKeyEvent false
                                    val direction = when (event.key) { Key.DirectionDown -> 1; Key.DirectionUp -> -1; else -> 0 }
                                    val target = index + direction
                                    if (direction != 0 && target in displayCategories.indices &&
                                        sidebarListState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                                        focusOffscreenGroup(target, direction)
                                        true
                                    } else false
                                },
                                onFocused = {
                                    actualFocusedGroupName = category.name
                                    lastLeftFocusTarget = LeftFocusTarget.CATEGORY
                                    focusedChannelIndex = index
                                    val changed = focusedChannelId != category.name
                                    focusedChannelId = category.name
                                    selectedChannelId = category.name
                                    if (changed) { lastGridRowIndex = 0; lastGridColIndex = -1; guideMode = TvChannelGuideMode.AUTO }
                                    if (!isCategoryShelfReady(category)) restoreShelfIfNeeded(category)
                                    focusedZone = FocusZone.CHANNEL_LIST
                                    pendingGroupFocusReturn = false
                                    onContentFocused(requester)
                                },
                                onClick = { focusGridZone() },
                            )
                        }
                    }
                }
            }

            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvIptvHeader(channelCount = channelsInGroup.size, groupName = focusedCategory?.name, showGroupName = true)
                TvEpgPreviewPanel(
                    focusedChannel = focusedChannel, focusedProgramme = focusedProgramme,
                    isActive = isActive && !isSubRouteActive, epgState = state.epgState,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TvChannelGuideToolbar(
                        showGuide = showGuide, windowStartMs = windowStartMs,
                        firstFocusRequester = guideToolbarFocusRequester, dayFocusRequester = guideDayFocusRequester,
                        isFocusEnabled = isActive && !pendingGroupFocusReturn,
                        onFocused = { requester -> focusedZone = FocusZone.GUIDE_TOOLS; lastGuideToolRequester = requester; onContentFocused(requester) },
                        onMoveDown = { focusedZone = FocusZone.EPG_GRID; gridFocusRequestToken += 1 },
                        onMoveLeft = { focusListZone() },
                        onSelectList = { guideMode = TvChannelGuideMode.LIST; lastGridColIndex = -1 },
                        onSelectGuide = { guideMode = TvChannelGuideMode.GUIDE; lastGridColIndex = -1 },
                        onNow = { jumpGuide(null) },
                        onTonight = { jumpGuide(tvGuideTonightStart(System.currentTimeMillis())) },
                        onDay = { rememberModalReturnFocus(); showGuideDayPicker = true },
                        onPrevious = { pageGuide(-1) }, onNext = { pageGuide(1) },
                    )
                    if (!showGuide) Text(localizedUiText("${eventFilter.label} · ${if (browseQuality == ChannelsFilterType.ALL) "All qualities" else filterLabel(browseQuality)}"),
                        color = Silver, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp).weight(1f))
                }
                if (guideUnavailable) Text(localizedUiText("No programme information is supplied for this group. You can still watch its channels."),
                    color = Silver, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2)
                Box(Modifier.fillMaxWidth().weight(1f).onPreviewKeyEvent { event ->
                    if (isActive && !modalOpen && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft &&
                        focusedZone == FocusZone.EPG_GRID) {
                        if (lastGridColIndex > 0 && showGuide) { lastGridColIndex -= 1; gridFocusRequestToken += 1 }
                        else focusListZone()
                        true
                    } else false
                }) {
                    TvEpgGrid(
                        channels = channelsInGroup, guideProgrammes = effectiveProgrammes,
                        isGuideLoading = state.epgState is EpgState.Loading, playlistId = activePlaylistId,
                        windowStartMs = windowStartMs, windowEndMs = windowEndMs,
                        canPageBackward = true, canPageForward = windowStartMs < nowMs + MAX_FORWARD_HOURS * 60 * 60_000L,
                        focusRowIndex = lastGridRowIndex, focusColIndex = lastGridColIndex,
                        focusRequestToken = gridFocusRequestToken,
                        isFocusEnabled = focusedZone == FocusZone.EPG_GRID && isActive,
                        showGuide = showGuide, favoriteIds = favoriteIds, showEmptyMessage = false,
                        onChannelFocused = { channel, programme ->
                            if (!pendingGroupFocusReturn) {
                                focusedChannel = channel; focusedProgramme = programme
                            }
                        },
                        onGridCellFocused = { row, col ->
                            if (!pendingGroupFocusReturn) {
                                lastGridRowIndex = row; lastGridColIndex = col; focusedZone = FocusZone.EPG_GRID
                            }
                        },
                        onGridFocusRequester = onContentFocused,
                        focusCenterInRoot = screenCenterY.takeIf { it > 0f },
                        onMoveVertical = { delta ->
                            if (delta < 0 && lastGridRowIndex == 0) {
                                focusedZone = FocusZone.GUIDE_TOOLS
                                scope.launch { delay(50); runCatching { guideToolbarFocusRequester.requestFocus() } }
                            } else {
                                val target = (lastGridRowIndex + delta).coerceIn(0, channelsInGroup.lastIndex.coerceAtLeast(0))
                                if (target != lastGridRowIndex) { lastGridRowIndex = target; gridFocusRequestToken += 1 }
                            }
                        },
                        onChannelPlay = { channel -> viewModel.recordChannelViewed(channel); onChannelPlay(channel) },
                        onProgrammeActions = { channel, programme -> openProgrammeActions(channel, programme) },
                        onTimeForward = { pageGuide(1) }, modifier = Modifier.fillMaxSize(), channelColumnWidth = 178.dp,
                    )
                    if (channelsInGroup.isEmpty()) {
                        val categoryName = focusedCategory?.name.orEmpty()
                        val restoring = categoryName in restoringShelfNames || !isBrowseProjectionReady
                        Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (restoring) CircularProgressIndicator(color = Amber)
                            Text(localizedUiText(when {
                                restoring -> "Loading $categoryName…"
                                categoryName == favoritesLabel -> "Your favorite channels appear here"
                                categoryName == recentlyViewedLabel -> "Channels you watch appear here"
                                else -> "No channels match these filters"
                            }), color = Snow, fontSize = 14.sp, textAlign = TextAlign.Center)
                            Text(localizedUiText(if (categoryName == favoritesLabel) "Open a channel’s Menu and choose Add to Favorites."
                                else if (eventFilter == TvChannelEventFilter.ACTIVE) "Finished events are available in Filters."
                                else "Change the country, quality or event filter to see more channels."),
                                color = Silver, fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(localizedUiText("OK Watch · Menu Options · Back Groups"), color = Silver, fontSize = 10.sp, lineHeight = 13.sp)
                    Text(localizedUiText(if (guideCoverageCount == 0) "No programme guide" else "Guide: $guideCoverageCount of ${channelsInGroup.size} channels"), color = Silver, fontSize = 10.sp, lineHeight = 13.sp)
                }
                if (state.epgState is EpgState.Loading) Text(localizedUiText(state.epgRefreshProgress?.userMessage() ?: "Updating programme guide…"), color = Silver, fontSize = 10.sp)
            }
        }

        heroOverlay?.invoke()

        if (showBrowseOptions) {
            TvChannelBrowseOptions(
                quality = browseQuality, country = browseCountry, countries = availableBrowseCountries,
                eventFilter = eventFilter, sort = browseSort, playlists = state.playlists,
                selectedPlaylistId = state.selectedPlaylistId, epgState = state.epgState,
                onQuality = { browseQuality = it }, onCountry = { browseCountry = it },
                onEvents = { eventFilter = it }, onSort = { browseSort = it },
                onPlaylist = { id -> focusedChannelId = null; selectedChannelId = null; lastGridRowIndex = 0; viewModel.selectPlaylist(id) },
                onRetryGuide = { viewModel.retryGuideLoad() },
                onManageSources = { showBrowseOptions = false; lastLeftFocusTarget = LeftFocusTarget.MANAGE; onOpenChannelSources() },
                onManageGroups = { showBrowseOptions = false; lastLeftFocusTarget = LeftFocusTarget.MANAGE; onOpenEpgSettings() },
                onDismiss = { showBrowseOptions = false; restoreBrowseFocus() },
            )
        }
        if (showGuideDayPicker) {
            TvChannelGuideDayPicker(nowMs = System.currentTimeMillis(),
                onDay = { start -> jumpGuide(start); showGuideDayPicker = false; restoreBrowseFocus() },
                onDismiss = { showGuideDayPicker = false; restoreBrowseFocus() })
        }

        guideProgrammeActionTarget?.let { target ->
            TvGuideProgrammeActionOverlay(
                channel = target.channel,
                programme = target.programme,
                canStartFromBeginning = !target.replayUrl.isNullOrBlank(),
                onWatchLive = {
                    guideProgrammeActionTarget = null
                    viewModel.recordChannelViewed(target.channel)
                    onChannelPlay(target.channel)
                },
                onStartFromBeginning = {
                    val programme = target.programme
                    val replayUrl = target.replayUrl
                    guideProgrammeActionTarget = null
                    if (programme != null && !replayUrl.isNullOrBlank()) {
                        viewModel.recordChannelViewed(target.channel)
                        onProgrammePlayFromBeginning(target.channel, programme, replayUrl)
                    } else {
                        gridFocusRequestToken += 1
                    }
                },
                // Android TV recording is exposed only when a platform recording
                // service is configured. No such service is bound in this build.
                onRecord = null,
                isFavorite = stableChannelId(target.channel) in favoriteIds,
                onToggleFavorite = { viewModel.toggleFavorite(target.channel) },
                onDismiss = {
                    guideProgrammeActionTarget = null
                    restoreBrowseFocus()
                },
            )
        }
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
            .tvLiquidGlass(focused, cornerRadius = 10.dp, focusScale = 1.02f)
            .padding(horizontal = if (label.isBlank()) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = (if (label.isBlank()) "Manage channels and programme guide" else null)?.let { localizedUiText(it) },
            tint = if (focused) Amber else Silver,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = localizedUiText(label),
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
            .height(30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .tvLiquidGlass(cornerRadius = 999.dp, quiet = true)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = localizedUiText(if (showGroupName && !groupName.isNullOrBlank()) {
                    "$groupName · $channelCount shown"
                } else {
                    "$channelCount shown"
                }),
                color = Silver,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .tvLiquidGlass(cornerRadius = 999.dp, quiet = true)
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
