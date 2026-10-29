package com.torve.android.tv.components

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.torve.android.remote.TorveRemotePopup as Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.TV_ROW_END_GUTTER
import com.torve.android.tv.TvImagePrefetcher
import com.torve.android.tv.settings.rememberTvPosterRatingsPreference
import com.torve.android.tv.focus.TvFocusTargetId
import com.torve.android.tv.focus.rememberRegisteredTvFocusRequester
import com.torve.android.tv.focus.rememberTvModalFocusRestoreController
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.ui.home.ALL_STREAMING_SERVICES
import com.torve.android.ui.home.StreamingProviderBrandArtwork
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.MediaItem
import com.torve.domain.model.allowsTmdbRatingProvider
import com.torve.presentation.seeall.SeeAllViewModel
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.koin.compose.koinInject

enum class TvCardStyle { POSTER, BACKDROP, SERVICE }

enum class TvBrowseLayout { POSTER_ONLY, INFO_PANEL }

enum class TvRailsPresentationMode {
    CatalogHero,
    LibraryHero,
    Plain,
}

data class TvMediaContextMenuAction(
    val id: String,
    val label: String,
    val isSecondary: Boolean = false,
    val isDestructive: Boolean = false,
    val isLocked: Boolean = false,
)

data class TvContentRail(
    val key: String,
    val title: String,
    val items: List<MediaItem>,
    val cardStyle: TvCardStyle = TvCardStyle.POSTER,
    val progressByMediaId: Map<String, Float> = emptyMap(),
    val seeAllItems: List<MediaItem>? = null,
    val showSeeAllCard: Boolean = true,
    val allowCrossRailDuplicates: Boolean = false,
    val remainingEpisodesByMediaId: Map<String, Int> = emptyMap(),
)

private fun TvContentRail.progressFor(item: MediaItem): Float? {
    return progressByMediaId[item.id]
        ?: item.tmdbId?.let { tmdbId ->
            progressByMediaId[tmdbId.toString()] ?: progressByMediaId["tmdb:$tmdbId"]
        }
}

private const val TV_CARD_WATCHED_THRESHOLD = 0.9f

private fun MediaItem.upcomingScheduleMetadata(): String? {
    if (!id.startsWith("trakt-calendar:")) return null
    val raw = releaseDate?.trim()?.takeIf { it.isNotEmpty() }
        ?: id.split(":", limit = 5).getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    return runCatching {
        java.time.ZonedDateTime.parse(raw)
            .withZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", java.util.Locale.US))
    }.getOrElse {
        raw.take(16)
            .replace('T', ' ')
            .removeSuffix("Z")
            .takeIf { it.isNotBlank() }
    }
}

internal data class TvMediaContextMenuState(
    val item: MediaItem,
    val progress: Float?,
    val anchorBounds: Rect?,
    val actions: List<TvMediaContextMenuAction>,
)

/**
 * Cross-rail deduplication: each item appears only in the first rail
 * where it was found. Uses tmdbId as the primary key, falling back
 * to "${type}:${id}". Empty rails are removed.
 */
fun List<TvContentRail>.dedupeAcrossRails(minItemsPerRail: Int = 20): List<TvContentRail> {
    val seen = mutableSetOf<String>()
    return mapNotNull { rail ->
        val localSeen = mutableSetOf<String>()
        val filtered = rail.items.filter { item ->
            val key = item.tvRailItemKey()
            (rail.allowCrossRailDuplicates || key !in seen) && localSeen.add(key)
        }
        val allowsSparse = rail.key.startsWith("continue_watching") ||
            rail.key.startsWith("watchlist") ||
            rail.key == "recently_watched" ||
            rail.key == "upcoming_schedule" ||
            rail.key == "streaming_services" ||
            rail.key.startsWith("popular_actors") ||
            rail.key.startsWith("popular_directors")
        val result = if (!allowsSparse && filtered.size < minItemsPerRail) emptyList() else filtered
        result.forEach { item ->
            val key = item.tvRailItemKey()
            seen.add(key)
        }
        if (result.isEmpty()) null else rail.copy(items = result)
    }
}

private fun shouldSeedPendingSeeAllItems(railKey: String): Boolean {
    return canShowSeeAllForRail(railKey)
}

private fun canShowSeeAllForRail(railKey: String): Boolean =
    railKey != "featured" &&
        railKey != "streaming_services" &&
        !railKey.startsWith("popular_actors") &&
        !railKey.startsWith("popular_directors")

class TvFocusMemory {
    var lastFocusedRowKey: String? by mutableStateOf(null)
    val lastFocusedIndexByRow = mutableStateMapOf<String, Int>()
    val lastFocusedItemKeyByRow = mutableStateMapOf<String, String>()

    companion object {
        // Keep identities across tab disposal, never the now-detached requesters.
        internal val Saver = listSaver<TvFocusMemory, String>(
            save = { memory ->
                buildList {
                    add(memory.lastFocusedRowKey.orEmpty())
                    (memory.lastFocusedIndexByRow.keys + memory.lastFocusedItemKeyByRow.keys).forEach { row ->
                        add(row)
                        add((memory.lastFocusedIndexByRow[row] ?: 0).toString())
                        add(memory.lastFocusedItemKeyByRow[row].orEmpty())
                    }
                }
            },
            restore = { saved ->
                TvFocusMemory().apply {
                    lastFocusedRowKey = saved.first().takeIf { it.isNotEmpty() }
                    saved.drop(1).chunked(3).forEach { (row, index, item) ->
                        lastFocusedIndexByRow[row] = index.toInt()
                        if (item.isNotEmpty()) lastFocusedItemKeyByRow[row] = item
                    }
                }
            },
        )
    }
}

@Composable
fun rememberTvFocusMemory(): TvFocusMemory = rememberSaveable(saver = TvFocusMemory.Saver) { TvFocusMemory() }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.tvLeadingContentItem(
    pinned: Boolean,
    content: @Composable () -> Unit,
) {
    if (pinned) {
        stickyHeader {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Obsidian.copy(alpha = 0.68f),
                                Graphite.copy(alpha = 0.32f),
                                Obsidian.copy(alpha = 0.10f),
                            ),
                        ),
                    ),
            ) {
                content()
            }
        }
    } else {
        item { content() }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TvMediaRails(
    rails: List<TvContentRail>,
    railFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
    screenId: String = "tv_media_rails",
    headerFocusRequester: FocusRequester? = null,
    focusMemory: TvFocusMemory = rememberTvFocusMemory(),
    loading: Boolean = false,
    emptyMessage: String = "",
    onMediaFocused: ((MediaItem) -> Unit)? = null,
    onRailMediaClick: ((railKey: String, item: MediaItem) -> Boolean)? = null,
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    heroOverlay: (@Composable () -> Unit)? = null,
    heroOverlayFocusRequester: FocusRequester? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    leadingContentFocusRequester: FocusRequester? = null,
    leadingContentVisible: Boolean = true,
    pinLeadingContent: Boolean = false,
    focusExclusive: Boolean = false,
    shouldAutoFocus: Boolean = true,
    browseLayout: TvBrowseLayout = TvBrowseLayout.INFO_PANEL,
    progressResolver: ((MediaItem, Float?) -> Float?)? = null,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)? = null,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)? = null,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
    autoFocusRequestNonce: Int = 0,
    sourceAwareRatings: Boolean = false,
    showSeeAllCards: Boolean = true,
    presentationMode: TvRailsPresentationMode = TvRailsPresentationMode.Plain,
    forcePosterTitles: Boolean = false,
    compactPosterTitles: Boolean = false,
    compactRailTitleSpacing: Boolean = false,
    posterColumns: Int? = null,
    entryFocusRequester: FocusRequester? = null,
    onExpandedRowFocused: (Boolean) -> Unit = {},
    compactPreviewRows: Boolean = false,
    onExitUp: (() -> Unit)? = null,
    restoreFocusAcrossRows: Boolean = false,
    initialFocusFollowsTop: Boolean = false,
    onContentLaidOut: (() -> Unit)? = null,
) {
    val catalogHeroMode = presentationMode == TvRailsPresentationMode.CatalogHero
    val libraryHeroMode = presentationMode == TvRailsPresentationMode.LibraryHero
    val settingsViewModel: SettingsViewModel = koinInject()
    val settingsState by settingsViewModel.state.collectAsState()
    val ratingPrefs = settingsState.ratingPrefs
    val tvCardRatingPrefs = remember(ratingPrefs) { ratingPrefs.tvExternalCardRatingPrefs() }
    val showTmdbRating = tvCardRatingPrefs.allowsTmdbRatingProvider()
    val showPosterRatings = rememberTvPosterRatingsPreference()
    val previewPolicy = com.torve.android.tv.settings.rememberTvFocusPreviewPolicy(settingsState.personalDataUserId)
    val previewPage = com.torve.android.tv.settings.tvFocusPreviewPage(screenId)
    val expandingPosters = rails.any { it.cardStyle != TvCardStyle.SERVICE && it.items.any(MediaItem::isTvPreviewTitle) && previewPolicy.enabled(previewPage, it.key) }
    val context = LocalContext.current
    val tvPrefs = remember { context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE) }
    val showTitlesOnCards = tvPrefs.getBoolean("tv_show_poster_titles", true) &&
        browseLayout == TvBrowseLayout.POSTER_ONLY
    val prefetchSignature = remember(rails) {
        rails.take(4).joinToString("|") { rail ->
            "${rail.key}:${rail.items.take(8).joinToString(",") { item -> item.id }}"
        }
    }

    val requesterMap = remember { mutableMapOf<String, FocusRequester>() }
    val rowListStateByKey = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val modalFocusRestoreController = rememberTvModalFocusRestoreController(key = "media_rails_$screenId")
    val columnListState = rememberLazyListState()
    val showLeadingContentBeforeRails = leadingContent != null
    val railListItemOffset = if (showLeadingContentBeforeRails) 1 else 0
    val coroutineScope = rememberCoroutineScope()
    val allowInactivePeekRows = true
    val effectiveFocusExclusive = focusExclusive && (libraryHeroMode || catalogHeroMode)
    // Real rows keep their height and lazy-list identity during vertical moves.
    // Replacing the previous row with a decorative peek moved the anchor twice.
    val stablePreviewRows = effectiveFocusExclusive && expandingPosters
    // The outer list suppresses native scrolling while focus-frame geometry
    // positions its rows. Ordinary replay/service rows still need horizontal
    // bring-into-view; capture their native policy before the outer override.
    val ordinaryRowBringIntoViewSpec = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
    val signature = remember(rails) { rails.joinToString("|") { r -> "${r.key}:${r.items.joinToString(",") { it.tvRailItemKey() }}" } }
    var contextMenuState by remember { mutableStateOf<TvMediaContextMenuState?>(null) }
    var activeExclusiveRowKey by remember(screenId) {
        mutableStateOf(focusMemory.lastFocusedRowKey)
    }
    var transitioningFromRowKey by remember(screenId) { mutableStateOf<String?>(null) }
    // Only visible rows belong to the vertical list. Empty lazy items still
    // consume custom spacing and allow scroll anchors to land inside a card.
    val activeRowIndex = rails.indexOfFirst { it.key == activeExclusiveRowKey }.takeIf { it >= 0 }
        ?: rails.indexOfFirst { it.key == focusMemory.lastFocusedRowKey }.takeIf { it >= 0 } ?: 0
    val nearbyRowIndices = listOf(activeRowIndex, activeRowIndex + 1, activeRowIndex - 1)
    // Warm the remembered destination in either direction before the remaining
    // visible posters. Vertical focus should not start its artwork request.
    val previewDestinations = nearbyRowIndices.mapNotNull { index ->
        val row = rails.getOrNull(index) ?: return@mapNotNull null
        val selected = focusMemory.lastFocusedIndexByRow[row.key] ?: 0
        row.items.getOrNull(selected.coerceIn(0, (row.items.size - 1).coerceAtLeast(0)))
    }
    val priorityItems = (previewDestinations + nearbyRowIndices.flatMap { index ->
        val row = rails.getOrNull(index) ?: return@flatMap emptyList()
        val first = rowListStateByKey[row.key]?.firstVisibleItemIndex ?: 0
        row.items.drop(first).take(if (index == activeRowIndex) 12 else 4)
    }).distinctBy { it.tvRailItemKey() }
    val visibleItems = rememberTvVisibleMetadata(priorityItems, screenId, expandingPosters, needsDetails = expandingPosters)
    DisposableEffect(visibleItems.map { listOf(it.posterUrl, it.backdropUrl, it.titlelessBackdropUrl, it.logoUrl) }) {
        val cancel = TvImagePrefetcher.prefetchMediaItems(context, screenId, visibleItems,
            maxImages = 20, heroCandidateCount = previewDestinations.size)
        onDispose { cancel() }
    }
    val renderedRowIndices = if (effectiveFocusExclusive && !stablePreviewRows) rails.indices.filter { index ->
        index == activeRowIndex || index == activeRowIndex + 1 || rails[index].key == transitioningFromRowKey
    } else rails.indices.toList()
    fun columnIndex(rowIndex: Int): Int {
        if (!effectiveFocusExclusive || stablePreviewRows) return railListItemOffset + rowIndex
        val active = rails.indexOfFirst { it.key == activeExclusiveRowKey }.coerceAtLeast(0)
        val visible = rails.indices.filter { it == active || it == active + 1 || rails[it].key == transitioningFromRowKey }
        return railListItemOffset + visible.indexOf(rowIndex).coerceAtLeast(0)
    }
    val visibleRowSignature = renderedRowIndices.map { rails[it].key }
    remember(visibleRowSignature, activeRowIndex, effectiveFocusExclusive, stablePreviewRows) {
        if (effectiveFocusExclusive && !stablePreviewRows) columnListState.requestScrollToItem(columnIndex(activeRowIndex), 0)
        Unit
    }

    var contentFocusSignal by remember(screenId) { mutableIntStateOf(0) }
    var pendingMediaFocusItem by remember(screenId) { mutableStateOf<MediaItem?>(null) }
    var leadingContentHasFocus by remember(screenId) { mutableStateOf(false) }
    var leadingContentEntryPending by remember(screenId) { mutableStateOf(false) }
    var focusedRowIndex by remember(screenId) { mutableStateOf<Int?>(null) }
    var focusedCardKey by remember(screenId) { mutableStateOf<String?>(null) }
    var focusedSeeAllRowKey by remember(screenId) { mutableStateOf<String?>(null) }
    var navigationSignal by remember(screenId) { mutableIntStateOf(0) }
    // A cold Home can render local service tiles before its saved/personal
    // rows arrive. Follow the first row until the viewer uses the remote;
    // afterwards, normal item restoration owns focus, including tab re-entry.
    var initialTopEntryPending by rememberSaveable(screenId) {
        mutableStateOf(initialFocusFollowsTop && focusMemory.lastFocusedRowKey == null)
    }
    val followInitialTop = initialFocusFollowsTop && initialTopEntryPending
    val contentModifier = modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) initialTopEntryPending = false
        false
    }
    var rowFocusJob by remember(screenId) { mutableStateOf<Job?>(null) }
    val previewRowIndex = if (transitioningFromRowKey != null) activeRowIndex else focusedRowIndex
    val expandedRowFocused = (focusedCardKey != null || focusedSeeAllRowKey != null || transitioningFromRowKey != null) && previewRowIndex?.let { index ->
        rails.getOrNull(index)?.let { it.cardStyle != TvCardStyle.SERVICE && it.items.any(MediaItem::isTvPreviewTitle) && previewPolicy.enabled(previewPage, it.key) }
    } == true
    LaunchedEffect(expandedRowFocused) { onExpandedRowFocused(expandedRowFocused) }
    // A grid toolbar is not a second place to present the selected film. Keep
    // its hero hidden while the active row uses an expanded landscape preview.
    val suppressHeroForPreview = expandedRowFocused || (compactPreviewRows &&
        rails.getOrNull(activeRowIndex)?.let { previewPolicy.enabled(previewPage, it.key) } == true)
    var previewMenuRow by remember { mutableStateOf<String?>(null) }
    val focusedTitleItem = focusedCardKey?.let { key ->
        val index = key.substringAfterLast(':').toIntOrNull() ?: return@let null
        rails.getOrNull(focusedRowIndex ?: return@let null)?.items?.getOrNull(index)
    }
    RememberFocusedTvImdb(focusedTitleItem, screenId)
    RememberFocusedTvPreviewDetails(focusedTitleItem?.takeIf { expandedRowFocused }, screenId)
    // Capture the real owner before lazy children are replaced by new data.
    val focusBeforeRailsUpdate = remember(signature) {
        modalFocusRestoreController.focusedTarget.takeIf { focusedCardKey != null }
    }
    val navigationSignalBeforeRailsUpdate = remember(signature) { navigationSignal }

    // Each entry, restoration and remote row transition below owns both its
    // scroll and its focus request. A separate alignment effect would cancel
    // the transition's scroll coroutine before it can focus the expanded row.

    val currentOnMediaFocused by rememberUpdatedState(onMediaFocused)
    LaunchedEffect(pendingMediaFocusItem) {
        val item = pendingMediaFocusItem ?: return@LaunchedEffect
        currentOnMediaFocused?.invoke(item)
    }

    fun queueMediaFocus(item: MediaItem) {
        if (onMediaFocused != null) pendingMediaFocusItem = item
    }

    LaunchedEffect(signature, effectiveFocusExclusive) {
        if (!effectiveFocusExclusive) return@LaunchedEffect
        val current = activeExclusiveRowKey
        if (current == null || rails.none { it.key == current }) {
            activeExclusiveRowKey = focusMemory.lastFocusedRowKey?.takeIf { key ->
                rails.any { it.key == key }
            } ?: rails.firstOrNull()?.key
        }
    }

    fun moveExclusiveRowFocus(fromRowIndex: Int, delta: Int) {
        if (!effectiveFocusExclusive) return
        val destinationIndex = (if (stablePreviewRows && rowFocusJob?.isActive == true) activeRowIndex else fromRowIndex) + delta
        val targetRow = rails.getOrNull(destinationIndex) ?: return
        leadingContentEntryPending = false
        val beforeFocusSignal = contentFocusSignal
        val sourceRowKey = modalFocusRestoreController.focusedTarget?.rowKey
            ?: transitioningFromRowKey ?: activeExclusiveRowKey
        rowFocusJob?.cancel()
        // Keep the old focused node alive until the expanded destination has
        // been measured and aligned. Disposing it early lets lazy focus fallback
        // select the clipped preview card before the scroll has finished.
        transitioningFromRowKey = sourceRowKey?.takeUnless { it == targetRow.key }
        activeExclusiveRowKey = targetRow.key
        focusMemory.lastFocusedRowKey = targetRow.key
        val targetIndex = focusMemory.lastFocusedIndexByRow[targetRow.key]
            ?.coerceIn(0, (targetRow.items.size - 1).coerceAtLeast(0))
            ?: 0
        targetRow.items.getOrNull(targetIndex)?.let { item ->
            queueMediaFocus(item)
        }
        rowFocusJob = coroutineScope.launch {
            try {
            if (stablePreviewRows) {
                // Align immediately, then transfer focus on the next layout.
                // Waiting for an animated scroll kept the destination collapsed
                // throughout the animation and made every preview feel late.
                columnListState.scrollToItem(columnIndex(destinationIndex), 0)
                repeat(8) {
                    withFrameNanos { }
                    val requester = requesterMap["${targetRow.key}:$targetIndex"]
                        ?: requesterMap["${targetRow.key}:0"]
                    if (requester != null && runCatching { requester.requestFocus(); true }.getOrDefault(false)) return@launch
                }
                return@launch
            }
            runCatching {
                columnListState.scrollToItem(
                    index = columnIndex(destinationIndex),
                    scrollOffset = 0,
                )
            }
            // Collapsed catalog rows must be measured and become focusable
            // before a remote row change is complete. A single delayed request
            // can miss that point on slower Fire TVs and leave focus on the rail.
            repeat(if (catalogHeroMode) 24 else 1) {
                withFrameNanos { }
                kotlinx.coroutines.delay(24)
                if (contentFocusSignal != beforeFocusSignal &&
                    (focusedCardKey == "${targetRow.key}:$targetIndex" || focusedCardKey == "${targetRow.key}:0")) {
                    return@launch
                }
                if (catalogHeroMode) {
                    // A zero-height target can be skipped by the first scroll
                    // before recomposition expands it. Align the expanded row
                    // again so its card is composed before requesting focus.
                    columnListState.scrollToItem(columnIndex(destinationIndex), scrollOffset = 0)
                    withFrameNanos { }
                }
                val targetRequester = requesterMap["${targetRow.key}:$targetIndex"]
                    ?: requesterMap["${targetRow.key}:0"]
                if (targetRequester != null) {
                    focusMemory.lastFocusedIndexByRow[targetRow.key] = targetIndex
                    runCatching { targetRequester.requestFocus() }
                }
            }
            } finally {
                if (rowFocusJob == kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    transitioningFromRowKey = null
                }
            }
        }
    }

    fun moveToActiveExclusiveRowFocus() {
        if (!effectiveFocusExclusive) return
        leadingContentEntryPending = false
        rowFocusJob?.cancel()
        transitioningFromRowKey = null
        val leadingEntryVisible = showLeadingContentBeforeRails && leadingContentVisible
        val targetRowKey = if (leadingEntryVisible) {
            rails.firstOrNull()?.key
        } else {
            activeExclusiveRowKey?.takeIf { key -> rails.any { it.key == key } }
                ?: rails.firstOrNull()?.key
        } ?: return
        val targetRowIndex = rails.indexOfFirst { it.key == targetRowKey }.takeIf { it >= 0 } ?: return
        activeExclusiveRowKey = targetRowKey
        val targetIndex = focusMemory.lastFocusedIndexByRow[targetRowKey]
            ?.coerceIn(0, (rails[targetRowIndex].items.size - 1).coerceAtLeast(0))
            ?: 0
        rails[targetRowIndex].items.getOrNull(targetIndex)?.let { item ->
            queueMediaFocus(item)
        }
        rowFocusJob = coroutineScope.launch {
            runCatching {
                columnListState.scrollToItem(
                    index = columnIndex(targetRowIndex),
                    scrollOffset = 0,
                )
            }
            withFrameNanos { }
            kotlinx.coroutines.delay(24)
            val targetRequester = requesterMap["$targetRowKey:$targetIndex"]
                ?: requesterMap["$targetRowKey:0"]
            if (targetRequester != null) {
                focusMemory.lastFocusedRowKey = targetRowKey
                focusMemory.lastFocusedIndexByRow[targetRowKey] = targetIndex
                runCatching { targetRequester.requestFocus() }
            }
        }
    }

    fun moveToLeadingContent() {
        val requester = leadingContentFocusRequester ?: return
        rowFocusJob?.cancel()
        transitioningFromRowKey = null
        // Keep expansion separate from observed focus: a newly measured group
        // can report hasFocus=false before the requester reaches its child.
        leadingContentEntryPending = true
        rowFocusJob = coroutineScope.launch {
            try {
            runCatching {
                columnListState.scrollToItem(0, scrollOffset = 0)
            }
            repeat(8) {
                withFrameNanos { }
                // The first scroll can still see the collapsed zero-height
                // header and canonicalize to the first rail. Remeasure after
                // expansion so the Search node is composed and placed again.
                columnListState.scrollToItem(0, scrollOffset = 0)
                withFrameNanos { }
                kotlinx.coroutines.delay(24)
                if (leadingContentHasFocus) return@launch
                runCatching { requester.requestFocus() }
            }
            } finally {
                if (rowFocusJob == kotlinx.coroutines.currentCoroutineContext()[Job]) leadingContentEntryPending = false
            }
        }
    }

    fun moveToRowFocus(targetRowIndex: Int) {
        val targetRow = rails.getOrNull(targetRowIndex) ?: return
        leadingContentEntryPending = false
        rowFocusJob?.cancel()
        transitioningFromRowKey = null
        if (effectiveFocusExclusive) activeExclusiveRowKey = targetRow.key
        val targetIndex = focusMemory.lastFocusedIndexByRow[targetRow.key]
            ?.coerceIn(0, (targetRow.items.size - 1).coerceAtLeast(0))
            ?: 0
        rowFocusJob = coroutineScope.launch {
            runCatching {
                columnListState.scrollToItem(
                    index = columnIndex(targetRowIndex),
                    scrollOffset = 0,
                )
            }
            withFrameNanos { }
            kotlinx.coroutines.delay(24)
            val targetRequester = requesterMap["${targetRow.key}:$targetIndex"]
                ?: requesterMap["${targetRow.key}:0"]
                ?: requesterMap["${targetRow.key}:see_all"]
            if (targetRequester != null) {
                focusMemory.lastFocusedRowKey = targetRow.key
                focusMemory.lastFocusedIndexByRow[targetRow.key] = targetIndex
                runCatching { targetRequester.requestFocus() }
            }
        }
    }

    DisposableEffect(registerFocusHandle, modalFocusRestoreController, screenId) {
        registerFocusHandle?.invoke(
            TvScreenFocusHandle(
                captureFocusedOrigin = {
                    modalFocusRestoreController.captureFocusedOrigin(
                        screenId = screenId,
                        outerListState = columnListState,
                        innerListStateForRowKey = { rowKey -> rowListStateByKey[rowKey] },
                    )
                },
                requestRestore = { origin, reason ->
                    modalFocusRestoreController.requestRestore(origin = origin, reason = reason)
                },
                isOriginFocused = { modalFocusRestoreController.isOriginFocused(it, restoreFocusAcrossRows) },
            ),
        )
        onDispose {
            registerFocusHandle?.invoke(null)
        }
    }

    fun openContextMenu(
        item: MediaItem,
        progress: Float?,
        target: TvFocusTargetId,
        anchorBounds: Rect?,
        rowListState: androidx.compose.foundation.lazy.LazyListState,
    ) {
        val actions = contextMenuActionsForItem?.invoke(item, progress).orEmpty().filter { it.label.isNotBlank() } +
            if (!previewPolicy.global && rails.firstOrNull { it.key == target.rowKey }?.cardStyle == TvCardStyle.POSTER) {
                listOf(TvMediaContextMenuAction("focus_preview_row", if (previewPolicy.enabled(previewPage, target.rowKey))
                    "Use posters for this row" else "Use expanding landscape for this row"))
            } else emptyList()
        if (actions.isEmpty()) return
        modalFocusRestoreController.captureOrigin(
            target = target,
            outerListState = columnListState,
            innerListState = rowListState,
        )
        contextMenuState = TvMediaContextMenuState(
            item = item,
            progress = progress,
            anchorBounds = anchorBounds,
            actions = actions,
        )
        previewMenuRow = target.rowKey
    }

    fun dismissContextMenu(restoreFocus: Boolean = true) {
        if (restoreFocus && contextMenuState != null) {
            modalFocusRestoreController.requestRestore()
        }
        contextMenuState = null
    }

    // The callback is recreated on hero/focus updates; only its presence changes
    // which targets exist. Rebuilding thousands of IDs for it blocks the remote.
    val validModalFocusTargets = remember(rails, screenId, showSeeAllCards, onSeeAll != null) {
        buildSet {
            rails.forEachIndexed { rowIndex, rail ->
                rail.items.forEachIndexed { itemIndex, item ->
                    add(
                        TvFocusTargetId(
                            screenId = screenId,
                            rowKey = rail.key,
                            itemKey = item.tvRailItemKey(),
                            rowIndex = rowIndex,
                            itemIndex = itemIndex,
                            targetType = "card",
                        ),
                    )
                }
                if (showSeeAllCards && rail.showSeeAllCard && onSeeAll != null && canShowSeeAllForRail(rail.key)) {
                    add(
                        TvFocusTargetId(
                            screenId = screenId,
                            rowKey = rail.key,
                            itemKey = "see_all",
                            rowIndex = rowIndex,
                            itemIndex = rail.items.size,
                            targetType = "see_all",
                        ),
                    )
                }
            }
        }
    }

    // Prune stale entries when rails change so disposed FocusRequesters
    // don't accumulate and become invalid restoration candidates.
    LaunchedEffect(validModalFocusTargets) {
        val validKeys = mutableSetOf<String>()
        val validRowKeys = mutableSetOf<String>()
        for (rail in rails) {
            validRowKeys += rail.key
            for (i in rail.items.indices) { validKeys.add("${rail.key}:$i") }
            validKeys.add("${rail.key}:see_all")
        }
        requesterMap.keys.retainAll(validKeys)
        rowListStateByKey.keys.retainAll(validRowKeys)
        modalFocusRestoreController.pruneInactiveTargets(validModalFocusTargets)
    }

    val autoFocusNavigationSignal = remember(signature, shouldAutoFocus, autoFocusRequestNonce) { navigationSignal }
    LaunchedEffect(signature, shouldAutoFocus, autoFocusRequestNonce, followInitialTop) {
        if (!shouldAutoFocus && !followInitialTop) return@LaunchedEffect
        if (rails.isEmpty()) return@LaunchedEffect
        if (navigationSignal != autoFocusNavigationSignal) return@LaunchedEffect
        val preferredRow = focusMemory.lastFocusedRowKey.takeUnless { followInitialTop }
        val destination = tvRailFocusDestination(
            rails, preferredRow, preferredRow?.let { focusMemory.lastFocusedItemKeyByRow[it] },
            preferredRow?.let { focusMemory.lastFocusedIndexByRow[it] } ?: 0,
            searchAcrossRows = restoreFocusAcrossRows,
        ) ?: return@LaunchedEffect
        if (focusedCardKey == "${destination.rowKey}:${destination.itemIndex}") return@LaunchedEffect
        rowFocusJob?.cancel()
        if (effectiveFocusExclusive) activeExclusiveRowKey = destination.rowKey
        repeat(30) {
            // Entry restoration must yield to a real remote action, and an
            // already-focused destination needs no retry. Otherwise an old
            // entry job can keep scrolling the previous row after DOWN/UP.
            if (navigationSignal != autoFocusNavigationSignal) return@LaunchedEffect
            if (focusedCardKey == "${destination.rowKey}:${destination.itemIndex}") return@LaunchedEffect
            withFrameNanos { }
            if (navigationSignal != autoFocusNavigationSignal) return@LaunchedEffect
            // Expand, measure and compose the destination before asking it
            // for focus. A cached requester may belong to a zero-height row.
            columnListState.scrollToItem(columnIndex(destination.rowIndex))
            rowListStateByKey[destination.rowKey]?.let { rowState ->
                if (rowState.layoutInfo.visibleItemsInfo.none { it.index == destination.itemIndex }) {
                    rowState.scrollToItem(destination.itemIndex)
                }
            }
            withFrameNanos { }
            if (navigationSignal != autoFocusNavigationSignal) return@LaunchedEffect
            val target = requesterMap["${destination.rowKey}:${destination.itemIndex}"]
            if (target != null) {
                val beforeFocusSignal = contentFocusSignal
                val requested = runCatching { target.requestFocus() }.isSuccess
                if (requested) {
                    withFrameNanos { }
                    if (focusedCardKey == "${destination.rowKey}:${destination.itemIndex}" || contentFocusSignal != beforeFocusSignal) {
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    LaunchedEffect(signature) {
        if (followInitialTop) return@LaunchedEffect
        val origin = focusBeforeRailsUpdate ?: return@LaunchedEffect
        withFrameNanos { }
        if (contextMenuState != null || navigationSignal != navigationSignalBeforeRailsUpdate) return@LaunchedEffect
        val destination = tvRailFocusDestination(rails, origin.rowKey, origin.itemKey, origin.itemIndex, origin.rowIndex, restoreFocusAcrossRows)
            ?: return@LaunchedEffect
        if (effectiveFocusExclusive) activeExclusiveRowKey = destination.rowKey
        rowFocusJob?.cancel()
        repeat(30) {
            withFrameNanos { }
            // Removing a focused node can make Compose focus a neighbour
            // automatically. Only a new remote action supersedes restoration.
            if (contextMenuState != null || navigationSignal != navigationSignalBeforeRailsUpdate) return@LaunchedEffect
            columnListState.scrollToItem(columnIndex(destination.rowIndex))
            rowListStateByKey[destination.rowKey]?.let { rowState ->
                if (rowState.layoutInfo.visibleItemsInfo.none { it.index == destination.itemIndex }) rowState.scrollToItem(destination.itemIndex)
            }
            withFrameNanos { }
            val expected = "${destination.rowKey}:${destination.itemIndex}"
            if (focusedCardKey == expected) return@LaunchedEffect
            requesterMap[expected]?.let { runCatching { it.requestFocus() } }
        }
    }

    LaunchedEffect(contextMenuState, modalFocusRestoreController.pendingRestore?.restoreToken, signature) {
        if (contextMenuState != null) return@LaunchedEffect
        val origin = modalFocusRestoreController.pendingRestore
        if (effectiveFocusExclusive && origin != null) {
            val destination = tvRailFocusDestination(rails, origin.rowKey, origin.itemKey, origin.itemIndex, origin.rowIndex, restoreFocusAcrossRows)
                ?: return@LaunchedEffect
            rowFocusJob?.cancel()
            activeExclusiveRowKey = destination.rowKey
            withFrameNanos { }
            columnListState.scrollToItem(columnIndex(destination.rowIndex))
            withFrameNanos { }
            if (restoreFocusAcrossRows) rowListStateByKey[destination.rowKey]?.let { state ->
                if (state.layoutInfo.visibleItemsInfo.none { it.index == destination.itemIndex }) state.scrollToItem(destination.itemIndex)
            }
        }
        modalFocusRestoreController.restorePendingFocus(
            screenId = screenId,
            // Old offsets describe the previous order. Keep the expanded
            // destination in view instead of scrolling back to its old index.
            outerListState = columnListState.takeUnless { effectiveFocusExclusive },
            innerListStateForRowKey = { rowKey -> rowListStateByKey[rowKey] },
            matchAcrossRows = restoreFocusAcrossRows,
        )
    }

    when {
        loading && rails.isEmpty() -> {
            val loadingRequester = remember { FocusRequester() }
            onFirstContentRequester(loadingRequester)
            Box(
                modifier = contentModifier
                    .fillMaxSize()
                    .padding(horizontal = 44.dp)
                    .focusRequester(loadingRequester)
                    .focusProperties { left = railFocusRequester }
                    .onFocusChanged { if (it.isFocused) onContentFocused(loadingRequester) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Amber)
            }
        }

        rails.isEmpty() -> {
            val emptyRequester = remember { FocusRequester() }
            onFirstContentRequester(emptyRequester)
            Box(
                modifier = contentModifier
                    .fillMaxSize()
                    .padding(horizontal = 44.dp)
                    .focusRequester(emptyRequester)
                    .focusProperties { left = railFocusRequester }
                    .onFocusChanged { if (it.isFocused) onContentFocused(emptyRequester) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = localizedUiText(emptyMessage),
                    style = MaterialTheme.typography.titleLarge,
                    color = Silver,
                )
            }
        }

        else -> {
            val railStartPad = TV_PAGE_CONTENT_GUTTER
            val rowItemSpacing = if (libraryHeroMode) 14.dp else 12.dp
            val rowVerticalFocusInset = if (libraryHeroMode) 10.dp else 8.dp
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides
                    if (stablePreviewRows) TvAnchoredPreviewBringIntoViewSpec else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current,
            ) { BoxWithConstraints(modifier = contentModifier.fillMaxSize().padding(top = if (compactPreviewRows) 12.dp else 24.dp)) {
                val availableRailWidth = maxWidth
                val density = LocalDensity.current
                val posterTitleStyle = if (compactPosterTitles) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
                val posterCaptionHeight = if (expandingPosters || forcePosterTitles || showTitlesOnCards || rails.any { it.key == "upcoming_schedule" }) {
                    // Keep a stable budget for two title lines and an air date.
                    with(density) { if (expandingPosters) posterTitleStyle.lineHeight.toDp() * 3f
                        else posterTitleStyle.lineHeight.toDp() * 2f + MaterialTheme.typography.labelSmall.lineHeight.toDp() } + 9.dp
                } else 0.dp
                val headingHeight = with(density) { MaterialTheme.typography.headlineSmall.lineHeight.toDp() }
                val rowChromeHeight = headingHeight + 12.dp + rowVerticalFocusInset * 2f + 24.dp
                val catalogRowBottomPadding = 16.dp
                // Include the largest focus scale (See All), as well as the
                // section padding outside LazyRow. Unscaled card height alone
                // let captions reach the clipped bottom during row navigation.
                val catalogFocusScale = 1.045f
                // Reserve both caption lines, the focus outline and a bottom safe margin.
                // Larger poster preferences must still fit a complete row on a 540dp TV.
                val catalogPosterWidthLimit = (
                    ((maxHeight - 180.dp - rowChromeHeight - catalogRowBottomPadding) / catalogFocusScale - posterCaptionHeight) / 1.5f
                )
                    .coerceIn(82.dp, 170.dp)
                val catalogRequestedPosterWidth = posterColumns?.let { requestedColumns ->
                    val columns = requestedColumns.coerceIn(4, 10)
                    ((availableRailWidth - railStartPad - TV_ROW_END_GUTTER - rowItemSpacing * (columns - 1)) / columns.toFloat())
                        .coerceIn(82.dp, 170.dp)
                }
                val catalogRowHeight = maxOf(
                    (catalogRequestedPosterWidth ?: 104.dp).coerceAtMost(catalogPosterWidthLimit) * 1.5f + posterCaptionHeight,
                    230.dp * 0.5625f + 64.dp,
                )
                val railPageHeight = maxHeight
                val fixedHeroRailViewport = heroOverlay != null &&
                    (catalogHeroMode || libraryHeroMode)
                val railTop = if (fixedHeroRailViewport && !suppressHeroForPreview) {
                    if (libraryHeroMode) {
                        // Library Watchlist/Favorites need a physical rail boundary.
                        // Rail rows, poster cards, rating badges, and focus glow must
                        // live below this clipped viewport so old rows cannot draw
                        // through the hero when vertical focus scrolls between rails.
                        (maxHeight * 0.255f).coerceIn(260.dp, 292.dp)
                    } else {
                        // Home, Movies, and TV Shows keep the fixed hero contract:
                        // the hero is a sibling layer pinned at the top while rails
                        // scroll in the bounded viewport below it.
                        (maxHeight * 0.275f).coerceIn(268.dp, 304.dp)
                            .coerceAtMost((maxHeight - catalogRowHeight * catalogFocusScale - rowChromeHeight - catalogRowBottomPadding).coerceAtLeast(0.dp))
                    }
                } else {
                    0.dp
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(2f),
                    ) {
                        if (railTop > 0.dp) {
                            Spacer(modifier = Modifier.height(railTop))
                        }
                        Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("catalog_rails_viewport")
                            .then(
                                if (fixedHeroRailViewport) {
                                    Modifier
                                        .graphicsLayer {
                                            compositingStrategy = CompositingStrategy.Offscreen
                                            clip = true
                                        }
                                        .clipToBounds()
                                } else {
                                    Modifier
                                },
                            )
                    ) {
                        LazyColumn(
                            state = columnListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (fixedHeroRailViewport) {
                                        Modifier
                                            .graphicsLayer {
                                                compositingStrategy = CompositingStrategy.Offscreen
                                                clip = true
                                            }
                                            .clipToBounds()
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentPadding = PaddingValues(
                                bottom = when {
                                    libraryHeroMode -> 80.dp
                                    catalogHeroMode -> 360.dp
                                    else -> 32.dp
                                },
                            ),
                            verticalArrangement = Arrangement.spacedBy(
                                settingsState.railSpacingDp?.dp ?: when {
                                    catalogHeroMode && effectiveFocusExclusive -> 0.dp
                                    libraryHeroMode && compactRailTitleSpacing -> 8.dp
                                    libraryHeroMode -> 18.dp
                                    else -> 16.dp
                                },
                            ),
                        ) {
                    if (heroOverlay != null && !fixedHeroRailViewport && !suppressHeroForPreview) {
                        item(key = "hero_overlay") {
                            heroOverlay()
                        }
                    }

                    leadingContent?.takeIf { showLeadingContentBeforeRails }?.let { content ->
                        if (pinLeadingContent) {
                            if (leadingContentVisible) {
                                leadingContentFocusRequester?.let { onFirstContentRequester(it) }
                            }
                            tvLeadingContentItem(pinned = true) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    content()
                                }
                            }
                            return@let
                        }
                        item(key = "leading_content") {
                            if (leadingContentVisible) {
                                leadingContentFocusRequester?.let { onFirstContentRequester(it) }
                            }
                            Box(
                                modifier = Modifier
                                    // An invisible Search header must not keep
                                    // consuming a full row of the card viewport.
                                    // Keep its focus node attached so UP can
                                    // expand it before requesting focus.
                                    .then(
                                        if (catalogHeroMode && !leadingContentHasFocus && !leadingContentEntryPending && focusedCardKey != null) {
                                            Modifier.height(0.dp)
                                        } else Modifier,
                                    )
                                    .graphicsLayer { alpha = if (catalogHeroMode && !leadingContentHasFocus && !leadingContentEntryPending) 0f else 1f }
                                    .onFocusChanged {
                                        leadingContentHasFocus = it.hasFocus
                                        if (it.hasFocus) leadingContentEntryPending = false
                                    }
                                    .focusGroup()
                                    .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) navigationSignal += 1
                                    if (
                                        effectiveFocusExclusive &&
                                        event.key == Key.DirectionDown &&
                                        event.type == KeyEventType.KeyDown
                                    ) {
                                        moveToActiveExclusiveRowFocus()
                                        true
                                    } else {
                                        effectiveFocusExclusive &&
                                            event.key == Key.DirectionDown &&
                                            event.type == KeyEventType.KeyUp
                                    }
                                },
                            ) {
                                content()
                            }
                        }
                    }

                    itemsIndexed(renderedRowIndices, key = { _, index -> rails[index].key }) { _, rowIndex ->
                    val row = rails[rowIndex]
                    val rowListState = remember(row.key) {
                        rowListStateByKey.getOrPut(row.key) { androidx.compose.foundation.lazy.LazyListState() }
                    }
                    rowListStateByKey[row.key] = rowListState
                    val leadingEntryVisible = showLeadingContentBeforeRails && leadingContentVisible
                    // The leading filters define the entry point, but they must
                    // not permanently pin the first rail as active. Otherwise
                    // focus can move to a later rail while that rail is still
                    // rendered at zero height, producing an apparent blank row.
                    val activeExclusiveKey =
                        activeExclusiveRowKey?.takeIf { key -> rails.any { rail -> rail.key == key } }
                            ?: focusMemory.lastFocusedRowKey?.takeIf { key -> rails.any { rail -> rail.key == key } }
                            ?: rails.firstOrNull()?.key
                    val activeExclusiveIndex = rails.indexOfFirst { it.key == activeExclusiveKey }
                    val rowIsActive = !effectiveFocusExclusive || row.key == activeExclusiveKey
                    val rowIsTransitionSource = effectiveFocusExclusive && row.key == transitioningFromRowKey
                    val rowIsPeek = !stablePreviewRows && allowInactivePeekRows &&
                        !rowIsTransitionSource &&
                        effectiveFocusExclusive &&
                        (!leadingEntryVisible || (catalogHeroMode && !leadingContentHasFocus)) &&
                        activeExclusiveIndex >= 0 &&
                        rowIndex == activeExclusiveIndex + 1
                    val rowIsVisible = stablePreviewRows || rowIsActive || rowIsPeek || rowIsTransitionSource
                    // Keep a keyed, zero-height entry and its scroll state, but
                    // do not build headings, image requests or focus nodes for
                    // collapsed rows. Zero-height full rows defeat lazy-list
                    // virtualization and used to compose hundreds of cards.
                    if (!rowIsVisible) return@itemsIndexed
                    val itemKeys = remember(row.items) { tvRailLazyItemKeys(row.items) }
                    val rowCanReceiveFocus = rowIsActive || rowIsTransitionSource
                    val rowExpands = row.cardStyle != TvCardStyle.SERVICE && row.items.any { it.isTvPreviewTitle() } && previewPolicy.enabled(previewPage, row.key)
                    // Reserve all typography first, then use the remaining height for
                    // landscape artwork. This also fits accessibility text sizes.
                    val previewTextHeight = with(density) {
                        MaterialTheme.typography.bodySmall.lineHeight.toDp() +
                            MaterialTheme.typography.bodyMedium.lineHeight.toDp() * 3f +
                            MaterialTheme.typography.labelMedium.lineHeight.toDp()
                    } + 32.dp
                    val homePreviewWidth = (availableRailWidth * .48f).coerceIn(320.dp, 440.dp)
                    val gridColumns = (posterColumns ?: 5).coerceIn(4, 10)
                    val gridImageHeight = ((availableRailWidth - railStartPad - TV_ROW_END_GUTTER -
                        rowItemSpacing * (gridColumns - 1)) / (16f / 9f + (gridColumns - 1) * 2f / 3f))
                    val previewImageHeight = if (compactPreviewRows) {
                        (railPageHeight - previewTextHeight - rowVerticalFocusInset * 2f - 32.dp)
                            .coerceIn(80.dp, gridImageHeight.coerceAtLeast(80.dp))
                    } else (railPageHeight - rowChromeHeight - previewTextHeight - 48.dp)
                        .coerceIn(80.dp, homePreviewWidth * 9f / 16f)
                    val previewWidth = if (compactPreviewRows) previewImageHeight * 16f / 9f else homePreviewWidth
                    // Focus owns the horizontal anchor. Set the next measure's
                    // position together with the final card widths; animating
                    // widths and scrolling separately briefly squeezed the new
                    // card and moved the whole row a second time on every key.
                    val anchoredIndex = focusedCardKey?.takeIf { it.startsWith("${row.key}:") }
                        ?.substringAfterLast(':')?.toIntOrNull()
                    remember(rowExpands, anchoredIndex) {
                        if (rowExpands && anchoredIndex != null) rowListState.requestScrollToItem(anchoredIndex, 0)
                        Unit
                    }
                    val isHeroLayout = heroOverlay != null
                    val isContinueRail = row.key.startsWith("continue_watching")
                    val backdropCardWidth = if (libraryHeroMode) {
                        when {
                            isHeroLayout && isContinueRail -> 216.dp
                            isHeroLayout -> 263.dp
                            else -> 292.dp
                        }
                    } else {
                        230.dp
                    }
                    val basePosterWidth = if (libraryHeroMode) {
                        if (isHeroLayout && rowIndex == 0) 106.dp else 118.dp
                    } else {
                        if (isHeroLayout && rowIndex == 0) 94.dp else 104.dp
                    }
                    val requestedPosterCardWidth = posterColumns?.let { requestedColumns ->
                        val columns = requestedColumns.coerceIn(4, 10)
                        (
                            (
                                availableRailWidth -
                                    railStartPad -
                                    TV_ROW_END_GUTTER -
                                    rowItemSpacing * (columns - 1)
                                ) / columns.toFloat()
                            ).coerceIn(82.dp, 170.dp)
                    }
                    val unconstrainedPosterCardWidth = requestedPosterCardWidth ?: if (libraryHeroMode) {
                        when {
                            isHeroLayout && isContinueRail -> basePosterWidth * 0.67f
                            isHeroLayout -> basePosterWidth * 0.9f
                            else -> basePosterWidth
                        }
                    } else {
                        basePosterWidth
                    }
                    val posterCardWidth = if (rowExpands) previewImageHeight / 1.5f
                        else if (catalogHeroMode) unconstrainedPosterCardWidth.coerceAtMost(catalogPosterWidthLimit)
                        else unconstrainedPosterCardWidth
                    val visibleSectionModifier = if (libraryHeroMode && !effectiveFocusExclusive && !rowExpands) {
                        val sectionPadding = if (compactRailTitleSpacing) 64.dp else 104.dp
                        when (row.cardStyle) {
                            TvCardStyle.POSTER -> Modifier.height(posterCardWidth * 1.5f + sectionPadding)
                            TvCardStyle.BACKDROP -> Modifier.height(backdropCardWidth * 0.5625f + sectionPadding)
                            TvCardStyle.SERVICE -> Modifier.height(176.dp)
                        }
                    } else {
                        Modifier
                    }
                    val shouldClipRowSection = libraryHeroMode || catalogHeroMode || !rowIsVisible || rowIsPeek
                    val rowAlpha = when {
                        rowIsPeek -> 0.48f
                        !rowIsVisible -> 0f
                        else -> 1f
                    }
                    Column(
                        modifier = Modifier
                            .testTag("catalog_rail_section:${row.key}")
                            .padding(bottom = if (catalogHeroMode && effectiveFocusExclusive && rowIsVisible) catalogRowBottomPadding else 0.dp)
                            .then(
                                if (!rowIsVisible) {
                                    Modifier
                                        .height(0.dp)
                                        .clipToBounds()
                                } else if (rowIsPeek) {
                                    Modifier
                                        .height(
                                            when {
                                                libraryHeroMode -> 122.dp
                                                catalogHeroMode -> 118.dp
                                                else -> 74.dp
                                            },
                                        )
                                        .clipToBounds()
                                } else {
                                    visibleSectionModifier
                                },
                            )
                            .graphicsLayer {
                                clip = shouldClipRowSection
                                alpha = rowAlpha
                            }
                            .then(if (shouldClipRowSection) Modifier.clipToBounds() else Modifier)
                            .then(if (rowCanReceiveFocus) Modifier else Modifier.clearAndSetSemantics {
                                this[androidx.compose.ui.semantics.SemanticsProperties.TestTag] = "catalog_rail_peek:${row.key}"
                            }),
                        verticalArrangement = Arrangement.spacedBy(
                            if (libraryHeroMode && compactRailTitleSpacing) 4.dp
                            else if (libraryHeroMode) 14.dp
                            else 12.dp,
                        ),
                    ) {
                        if (row.title.isNotBlank()) Row(
                            Modifier.padding(start = railStartPad), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.width(3.dp).height(22.dp).background(AmberLight, RoundedCornerShape(2.dp)))
                            Text(
                            text = localizedUiText(tvRailDisplayTitle(row)),
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (libraryHeroMode) Snow.copy(alpha = 0.98f) else Snow,
                            fontWeight = if (libraryHeroMode) FontWeight.Bold else FontWeight.SemiBold,
                        )
                        }

                        if (rowIsPeek) {
                            // The clipped next-row preview is decorative. Building
                            // complete clickable cards here doubles initial focus
                            // registration and layout work on slower Fire TVs.
                            Row(
                                modifier = Modifier.padding(start = railStartPad, top = rowVerticalFocusInset)
                                    .clipToBounds(),
                                horizontalArrangement = Arrangement.spacedBy(rowItemSpacing),
                            ) {
                                row.items.take(8).forEach { item ->
                                    val item = localizedTvPresentation(withCachedTvBrowsePreviewEnrichment(item),
                                        settingsState.personalDataUserId, settingsState.appLanguage.code, settingsState.tmdbEnrichmentEnabled)
                                    val landscape = row.cardStyle == TvCardStyle.BACKDROP
                                    AsyncImage(
                                        model = if (landscape) item.backdropUrl ?: item.posterUrl else item.posterUrl,
                                        onSuccess = { com.torve.android.debug.StartupMetrics.posterReady() },
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        alignment = Alignment.TopCenter,
                                        modifier = Modifier.width(if (landscape) backdropCardWidth else posterCardWidth)
                                            // The preview has less height than a full poster.
                                            // aspectRatio can exceed that height and Compose
                                            // centers the overflow over the section heading.
                                            // Respect the remaining height and crop from the top.
                                            .height(if (landscape) backdropCardWidth * 0.5625f else posterCardWidth * 1.5f)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Graphite),
                                    )
                                }
                            }
                        } else androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides
                                if (rowExpands) TvAnchoredPreviewBringIntoViewSpec else ordinaryRowBringIntoViewSpec,
                        ) { LazyRow(
                            state = rowListState,
                            modifier = if (libraryHeroMode || catalogHeroMode || rowExpands) {
                                Modifier
                                    .then(if (rowExpands) Modifier.height(previewImageHeight + previewTextHeight + if (compactPreviewRows) 23.dp else 38.dp) else Modifier)
                                    .then(if (rowExpands) Modifier.padding(start = railStartPad) else Modifier)
                                    .graphicsLayer { clip = true }
                                    .clipToBounds()
                            } else {
                                Modifier
                            },
                            horizontalArrangement = Arrangement.spacedBy(rowItemSpacing),
                            contentPadding = PaddingValues(
                                start = if (rowExpands) 0.dp else railStartPad,
                                top = when {
                                    libraryHeroMode && compactRailTitleSpacing -> 6.dp
                                    libraryHeroMode -> 26.dp
                                    else -> rowVerticalFocusInset
                                },
                                end = TV_ROW_END_GUTTER,
                                bottom = when {
                                    libraryHeroMode && compactRailTitleSpacing -> 14.dp
                                    libraryHeroMode -> 30.dp
                                    else -> rowVerticalFocusInset
                                },
                            ),
                        ) {
                            itemsIndexed(
                                items = row.items,
                                key = { itemIndex, _ ->
                                    itemKeys[itemIndex]
                                },
                            ) { itemIndex, sourceItem ->
                                val item = localizedTvPresentation(withCachedTvBrowsePreviewEnrichment(sourceItem),
                                    settingsState.personalDataUserId, settingsState.appLanguage.code, settingsState.tmdbEnrichmentEnabled)
                                val mapKey = "${row.key}:$itemIndex"
                                val target = remember(row.key, rowIndex, itemIndex, item.id, item.tmdbId) {
                                    TvFocusTargetId(
                                        screenId = screenId,
                                        rowKey = row.key,
                                        itemKey = item.tvRailItemKey(),
                                        rowIndex = rowIndex,
                                        itemIndex = itemIndex,
                                        targetType = "card",
                                    )
                                }
                                val baseRequester = remember(mapKey) { if (rowIndex == 0 && itemIndex == 0) entryFocusRequester ?: FocusRequester() else FocusRequester() }
                                val focusRequester = rememberRegisteredTvFocusRequester(
                                    controller = modalFocusRestoreController,
                                    target = target,
                                    externalRequester = baseRequester,
                                )
                                DisposableEffect(mapKey, focusRequester) {
                                    requesterMap[mapKey] = focusRequester
                                    onDispose {
                                        if (requesterMap[mapKey] == focusRequester) requesterMap.remove(mapKey)
                                    }
                                }

                                val isFirstItem = rowIndex == 0 && itemIndex == 0
                                val isActiveEntryItem = effectiveFocusExclusive && rowIsActive && itemIndex == 0
                                if (
                                    (isFirstItem || isActiveEntryItem) &&
                                    (!showLeadingContentBeforeRails || !leadingContentVisible)
                                ) {
                                    onFirstContentRequester(focusRequester)
                                }

                                val railProgress = row.progressFor(item)
                                val progress = progressResolver?.invoke(item, railProgress) ?: railProgress
                                var cardHasFocus by remember { mutableStateOf(false) }
                                LaunchedEffect(item) { if (cardHasFocus) onMediaFocused?.invoke(item) }
                                val cardModifier = Modifier
                                    .onGloballyPositioned {
                                        if (rowIndex == 0 && itemIndex == 0 && it.size.height > 0) {
                                            onContentLaidOut?.invoke()
                                            if (screenId == "home") com.torve.android.debug.StartupMetrics.homeContentLaidOut()
                                        }
                                    }
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { state ->
                                        cardHasFocus = state.isFocused
                                        if (state.isFocused) { focusedCardKey = mapKey; focusedSeeAllRowKey = null }
                                        else if (focusedCardKey == mapKey) focusedCardKey = null
                                    }
                                    .focusProperties {
                                        if (effectiveFocusExclusive || catalogHeroMode) {
                                            canFocus = rowCanReceiveFocus
                                        }
                                        if (itemIndex == 0) {
                                            left = railFocusRequester
                                        }
                                        // Only redirect UP to the header when the hero overlay
                                        // is actually rendered — otherwise the FocusRequester
                                        // is unattached and causes a crash.
                                        if (rowIndex == 0 && showLeadingContentBeforeRails && leadingContentFocusRequester != null) {
                                            up = leadingContentFocusRequester
                                        } else if (rowIndex == 0 && heroOverlayFocusRequester != null && !expandedRowFocused) {
                                            up = heroOverlayFocusRequester
                                        } else if (rowIndex == 0 && headerFocusRequester != null) {
                                            up = headerFocusRequester
                                        }
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) navigationSignal += 1
                                        if (!effectiveFocusExclusive) return@onPreviewKeyEvent false
                                        when {
                                            event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown -> {
                                                moveExclusiveRowFocus(rowIndex, 1)
                                                true
                                            }
                                            event.key == Key.DirectionDown && event.type == KeyEventType.KeyUp -> true
                                            event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown -> {
                                                if (rowIndex == 0 && onExitUp != null) {
                                                    onExitUp()
                                                } else if (rowIndex == 0 && leadingContentFocusRequester != null) {
                                                    moveToLeadingContent()
                                                } else if (rowIndex == 0 && heroOverlayFocusRequester != null && !expandedRowFocused) {
                                                    runCatching { heroOverlayFocusRequester.requestFocus() }
                                                    onContentFocused(heroOverlayFocusRequester)
                                                } else if (rowIndex == 0 && headerFocusRequester != null) {
                                                    runCatching { headerFocusRequester.requestFocus() }
                                                    onContentFocused(headerFocusRequester)
                                                } else {
                                                    moveExclusiveRowFocus(rowIndex, -1)
                                                }
                                                true
                                            }
                                            event.key == Key.DirectionUp && event.type == KeyEventType.KeyUp -> true
                                            else -> false
                                        }
                                    }

                                val onItemFocused: () -> Unit = onItemFocused@{
                                    // Lazy focus fallback may emit a late event for the
                                    // row being collapsed. It must not reverse a remote
                                    // move that has already expanded another row.
                                    if (effectiveFocusExclusive && activeExclusiveRowKey != null && activeExclusiveRowKey != row.key) return@onItemFocused
                                    val previousFocusedRowKey = focusMemory.lastFocusedRowKey
                                    leadingContentHasFocus = false
                                    modalFocusRestoreController.markFocused(target)
                                    if (effectiveFocusExclusive) {
                                        activeExclusiveRowKey = row.key
                                    }
                                    focusMemory.lastFocusedRowKey = row.key
                                    focusMemory.lastFocusedIndexByRow[row.key] = itemIndex
                                    focusMemory.lastFocusedItemKeyByRow[row.key] = item.tvRailItemKey()
                                    onContentFocused(focusRequester)
                                    focusedRowIndex = rowIndex
                                    contentFocusSignal += 1
                                    queueMediaFocus(item)
                                    if (previousFocusedRowKey != row.key && !effectiveFocusExclusive) {
                                        coroutineScope.launch {
                                            runCatching {
                                                columnListState.scrollToItem(
                                                    index = columnIndex(rowIndex),
                                                    scrollOffset = 0,
                                                )
                                            }
                                        }
                                    }
                                }

                                LaunchedEffect(target) {
                                    // Stable lazy keys preserve the focused node when
                                    // its index changes. Refresh the controller identity
                                    // even though Android emits no new focus event.
                                    if (cardHasFocus) {
                                        focusedCardKey = mapKey
                                        onItemFocused()
                                    }
                                }

                                val onItemClick: () -> Unit = {
                                    val consumed = onRailMediaClick?.invoke(row.key, item) == true
                                    if (!consumed) onMediaClick(item)
                                }

                                when {
                                    row.cardStyle == TvCardStyle.SERVICE -> {
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = if (libraryHeroMode) 8.dp else 0.dp)
                                        ) {
                                            TvStreamingServiceCard(
                                                item = item,
                                                modifier = cardModifier
                                                    .width(256.dp)
                                                    .height(144.dp),
                                                onClick = onItemClick,
                                                onFocused = onItemFocused,
                                            )
                                        }
                                    }

                                    row.cardStyle == TvCardStyle.BACKDROP && !rowExpands -> {
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = if (libraryHeroMode) 8.dp else 0.dp)
                                        ) {
                                            TvBackdropCard(
                                                item = item,
                                                modifier = cardModifier,
                                                width = backdropCardWidth,
                                                onClick = onItemClick,
                                                onFocused = onItemFocused,
                                                progress = progress,
                                                showTitles = forcePosterTitles || showTitlesOnCards,
                                                showTmdbRating = showTmdbRating,
                                                ratingPrefs = tvCardRatingPrefs,
                                                showRatings = showPosterRatings,
                                            )
                                            com.torve.android.ui.community.RemainingEpisodesBadge(row.remainingEpisodesByMediaId[item.id] ?: 0,
                                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                                        }
                                    }

                                    else -> {
                                        // Remember selection for navigation, but expand only the
                                        // poster which actually owns focus. Other rows and See All
                                        // must never retain a second landscape card.
                                        val expanded = rowExpands && cardHasFocus
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = if (libraryHeroMode) 8.dp else 0.dp)
                                        ) {
                                            TvPosterCard(
                                                item = item,
                                                modifier = cardModifier
                                                    .width(if (expanded) previewWidth else posterCardWidth),
                                                imageHeight = if (rowExpands) previewImageHeight else null,
                                                expandedCaptionHeight = previewTextHeight,
                                                expanded = expanded,
                                                onClick = onItemClick,
                                                onFocused = onItemFocused,
                                                progress = progress,
                                                showTitles = !rowExpands && (item.id.startsWith("person:") || forcePosterTitles || showTitlesOnCards),
                                                artworkOnlyTitles = rowExpands,
                                                compactTitles = compactPosterTitles,
                                                showMetadata = !catalogHeroMode,
                                                showTmdbRating = showTmdbRating,
                                                sourceAwareRatings = sourceAwareRatings,
                                                ratingPrefs = tvCardRatingPrefs,
                                                showRatings = showPosterRatings,
                                                maxPosterRatings = if (posterCardWidth < 112.dp) 1 else 2,
                                                onContextMenuRequested = { anchorBounds ->
                                                    openContextMenu(
                                                        item = item,
                                                        progress = progress,
                                                        target = target,
                                                        anchorBounds = anchorBounds,
                                                        rowListState = rowListState,
                                                    )
                                                },
                                            )
                                            com.torve.android.ui.community.RemainingEpisodesBadge(row.remainingEpisodesByMediaId[item.id] ?: 0,
                                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                                        }
                                    }
                                }
                            }

                            if (showSeeAllCards && row.showSeeAllCard && onSeeAll != null && canShowSeeAllForRail(row.key)) {
                                item(key = "${row.key}_see_all") {
                                    val seeAllTarget = remember(row.key, rowIndex) {
                                        TvFocusTargetId(
                                            screenId = screenId,
                                            rowKey = row.key,
                                            itemKey = "see_all",
                                            rowIndex = rowIndex,
                                            itemIndex = row.items.size,
                                            targetType = "see_all",
                                        )
                                    }
                                    val seeAllBaseRequester = remember("${row.key}_see_all") { FocusRequester() }
                                    val seeAllRequester = rememberRegisteredTvFocusRequester(
                                        controller = modalFocusRestoreController,
                                        target = seeAllTarget,
                                        externalRequester = seeAllBaseRequester,
                                    )
                                    DisposableEffect(row.key, seeAllRequester) {
                                        val mapKey = "${row.key}:see_all"
                                        requesterMap[mapKey] = seeAllRequester
                                        onDispose {
                                            if (requesterMap[mapKey] == seeAllRequester) requesterMap.remove(mapKey)
                                        }
                                    }
                                    val seeAllWidth = when (row.cardStyle) {
                                        TvCardStyle.BACKDROP -> backdropCardWidth * 0.82f
                                        TvCardStyle.POSTER -> posterCardWidth
                                        TvCardStyle.SERVICE -> 180.dp
                                    }
                                    val seeAllAspectRatio = when (row.cardStyle) {
                                        TvCardStyle.BACKDROP -> 16f / 9f
                                        TvCardStyle.POSTER -> 2f / 3f
                                        TvCardStyle.SERVICE -> 11f / 6f
                                    }
                                    TvSeeAllButton(
                                        modifier = Modifier
                                            .focusRequester(seeAllRequester)
                                            .onFocusChanged {
                                                if (it.isFocused) focusedSeeAllRowKey = row.key
                                                else if (focusedSeeAllRowKey == row.key) focusedSeeAllRowKey = null
                                            }
                                            .focusProperties {
                                                if (effectiveFocusExclusive || catalogHeroMode) {
                                                    canFocus = rowCanReceiveFocus
                                                }
                                                if (rowIndex == 0 && showLeadingContentBeforeRails && leadingContentFocusRequester != null) {
                                                    up = leadingContentFocusRequester
                                                } else if (rowIndex == 0 && heroOverlayFocusRequester != null) {
                                                    up = heroOverlayFocusRequester
                                                } else if (rowIndex == 0 && headerFocusRequester != null && heroOverlay != null) {
                                                    up = headerFocusRequester
                                                }
                                            },
                                        onMoveDown = {
                                            if (effectiveFocusExclusive) {
                                                moveExclusiveRowFocus(rowIndex, 1)
                                            } else {
                                                moveToRowFocus(rowIndex + 1)
                                            }
                                        },
                                        onMoveUp = {
                                            if (effectiveFocusExclusive) {
                                                if (rowIndex == 0 && onExitUp != null) {
                                                    onExitUp()
                                                } else if (rowIndex == 0 && leadingContentFocusRequester != null) {
                                                    moveToLeadingContent()
                                                } else {
                                                    moveExclusiveRowFocus(rowIndex, -1)
                                                }
                                            } else if (rowIndex > 0) {
                                                moveToRowFocus(rowIndex - 1)
                                            } else if (showLeadingContentBeforeRails && leadingContentFocusRequester != null) {
                                                moveToLeadingContent()
                                            } else if (heroOverlayFocusRequester != null) {
                                                runCatching { heroOverlayFocusRequester.requestFocus() }
                                            } else if (headerFocusRequester != null && heroOverlay != null) {
                                                runCatching { headerFocusRequester.requestFocus() }
                                            }
                                        },
                                        onClick = {
                                            if (shouldSeedPendingSeeAllItems(row.key)) {
                                                SeeAllViewModel.pendingItems[row.key] = row.title to (row.seeAllItems ?: row.items)
                                            } else {
                                                SeeAllViewModel.pendingItems.remove(row.key)
                                            }
                                            onSeeAll(row.key, row.title)
                                        },
                                        onFocused = seeAllFocused@{
                                            if (effectiveFocusExclusive && activeExclusiveRowKey != null && activeExclusiveRowKey != row.key) return@seeAllFocused
                                            modalFocusRestoreController.markFocused(seeAllTarget)
                                            if (effectiveFocusExclusive) {
                                                activeExclusiveRowKey = row.key
                                            }
                                            focusMemory.lastFocusedRowKey = row.key
                                            onContentFocused(seeAllRequester)
                                            focusedRowIndex = rowIndex
                                            contentFocusSignal += 1
                                        },
                                        width = seeAllWidth,
                                        aspectRatio = seeAllAspectRatio,
                                    )
                                }
                            }
                            if (rowExpands) item(key = "${row.key}_preview_scroll_space") {
                                Spacer(Modifier.width(availableRailWidth.coerceAtLeast(0.dp)))
                            }
                        }
                        }

                        if (row.key == "streaming_services") {
                            Text(
                                text = stringResource(R.string.home_tmdb_credit),
                                style = MaterialTheme.typography.labelSmall,
                                color = Silver,
                                modifier = Modifier.padding(start = railStartPad),
                            )
                        }
                    }
                    }
                    }

                }
                }

                    if (fixedHeroRailViewport && !suppressHeroForPreview) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(railTop)
                                .align(Alignment.TopStart)
                                .zIndex(5f)
                                .clipToBounds()
                        ) {
                            heroOverlay()
                        }
                    }
                }
            }

            }
            contextMenuState?.let { menuState ->
                TvMediaContextMenu(
                    state = menuState,
                    onDismiss = { dismissContextMenu(restoreFocus = true) },
                    onAction = { action ->
                        if (action.id == "focus_preview_row") previewMenuRow?.let { row ->
                            com.torve.android.tv.settings.setTvFocusPreview(context, settingsState.personalDataUserId,
                                "row:$previewPage:$row", !previewPolicy.enabled(previewPage, row))
                        } else onContextMenuAction?.invoke(menuState.item, action, menuState.progress)
                        dismissContextMenu(restoreFocus = true)
                    },
                )
            }
        }
    }
}

@Composable
private fun TvStreamingServiceCard(
    item: MediaItem,
    modifier: Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.04f else 1f, label = "serviceCardScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "serviceCardBorder",
    )
    val providerId = item.id.removePrefix("provider:").toIntOrNull()
    val service = ALL_STREAMING_SERVICES
        .firstOrNull { it.tmdbProviderId == providerId }
        ?: com.torve.android.ui.home.StreamingService(
            name = item.title,
            brandColor = Charcoal,
            tmdbProviderId = providerId ?: -1,
        )
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .clip(shape)
            .border(2.dp, borderColor, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        StreamingProviderBrandArtwork(
            service = service,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TvPosterCard(
    item: MediaItem,
    modifier: Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    imageHeight: Dp? = null,
    expandedCaptionHeight: Dp? = null,
    expanded: Boolean = false,
    artworkOnlyTitles: Boolean = false,
    progress: Float? = null,
    showTitles: Boolean = true,
    compactTitles: Boolean = false,
    showMetadata: Boolean = true,
    showTmdbRating: Boolean = true,
    sourceAwareRatings: Boolean = false,
    ratingPrefs: com.torve.domain.model.RatingDisplayPrefs,
    showRatings: Boolean = true,
    maxPosterRatings: Int = 1,
    onContextMenuRequested: ((Rect?) -> Unit)? = null,
) {
    if (item.catalogueNotice != null) {
        com.torve.android.ui.community.CatalogueNoticeCard(item, modifier, onFocused)
        return
    }
    var focused by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val scale by animateFloatAsState(
        targetValue = if (focused && !expanded) 1.035f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "posterScale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        animationSpec = tween(durationMillis = 100),
        label = "posterBorder",
    )
    val cardShape = RoundedCornerShape(16.dp)
    val focusShape = RoundedCornerShape(18.dp)

    val isWatched = (progress ?: 0f) >= TV_CARD_WATCHED_THRESHOLD

    val shouldShowCaption = expanded || (!artworkOnlyTitles && (showTitles || item.id.startsWith("trakt-calendar:")))

    Column(
        modifier = modifier
            .testTag("catalog_poster_card:${item.id}")
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onGloballyPositioned { coordinates ->
                anchorBounds = coordinates.boundsInWindow()
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val isMenuKey = event.key == Key.Menu
                val isCenterLongPress = (
                    (event.key == Key.DirectionCenter || event.key == Key.Enter) &&
                        (event.nativeKeyEvent as? NativeKeyEvent)?.repeatCount?.let { it > 0 } == true
                    )
                if (isMenuKey || isCenterLongPress) {
                    onContextMenuRequested?.invoke(anchorBounds)
                    true
                } else {
                    false
                }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = {
                    onContextMenuRequested?.invoke(anchorBounds)
                },
            ),
        verticalArrangement = Arrangement.spacedBy(if (shouldShowCaption) 7.dp else 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("catalog_poster_frame:${item.id}")
                .then(if (imageHeight != null) Modifier.height(imageHeight) else Modifier.aspectRatio(2f / 3f))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Amber.copy(alpha = if (focused) 0.34f else 0f),
                            Amber.copy(alpha = if (focused) 0.16f else 0f),
                            Color.Transparent,
                        ),
                    ),
                    focusShape,
                )
                .border(if (focused) 3.dp else 1.dp, borderColor, cardShape)
                .clip(cardShape),
        ) {
            val (posterImageUrl, artworkFailed) = com.torve.android.ui.community.stableArtwork(item, expanded, preferTitleless = expanded)
            val personId = item.id.takeIf { it.startsWith("person:") }?.substringAfter(':')?.toIntOrNull()
            if (personId != null) {
                TvPersonPortrait(personId, item.title, item.posterUrl, Modifier.fillMaxSize())
            } else if (posterImageUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A1A2E)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(8.dp),
                    ) {
                        if (!expanded) Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = Snow,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        item.upcomingScheduleMetadata()?.takeUnless { expanded }?.let { metadata ->
                            Text(
                                text = metadata,
                                style = MaterialTheme.typography.labelSmall,
                                color = Silver,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                AsyncImage(
                    model = posterImageUrl,
                    onSuccess = { com.torve.android.debug.StartupMetrics.posterReady() },
                    onError = { artworkFailed() },
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (expanded) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Transparent, .4f to Color.Transparent, 1f to Color.Black.copy(alpha = .9f),
                )))
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvTitleArtworkOrText(
                        item = item.copy(title = item.tvPreviewShowTitle()),
                        modifier = Modifier.weight(1f).height(64.dp).testTag("catalog_expanded_logo:${item.id}"),
                        textStyle = MaterialTheme.typography.titleLarge,
                        textAlignment = Alignment.BottomStart,
                    )
                    item.tvPreviewEpisodeCode()?.let { episode ->
                        Text(episode, color = Snow, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("catalog_expanded_episode:${item.id}"))
                    }
                }
            } else if (artworkOnlyTitles) {
                item.tvPreviewEpisodeCode()?.let { episode ->
                    Text(episode, color = Snow, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            .background(Obsidian.copy(alpha = .86f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }

            if (showRatings && !expanded && (!artworkOnlyTitles || item.tvPreviewEpisodeCode() == null)) {
                TvPosterRatingPills(
                    item = item,
                    prefs = ratingPrefs,
                    maxRatings = maxPosterRatings,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                )
            }

            if (isWatched) {
                TvPosterWatchedBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }

            if (progress != null && progress > 0f && !isWatched) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Snow.copy(alpha = 0.27f)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Amber),
                    )
                }
            }
        }

        if (shouldShowCaption) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("catalog_poster_caption:${item.id}")
                    .then(if (expanded && expandedCaptionHeight != null) Modifier.height(expandedCaptionHeight) else Modifier)
                    .padding(horizontal = if (expanded) 0.dp else 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!expanded) Text(
                    text = item.title,
                    style = if (compactTitles) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expanded) {
                    TvExpandedPosterMetadata(item, ratingPrefs, showRatings)
                } else if (showMetadata || item.id.startsWith("trakt-calendar:")) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                    val scheduleMetadata = item.upcomingScheduleMetadata()
                    if (scheduleMetadata != null) {
                        Text(
                            text = scheduleMetadata,
                            style = MaterialTheme.typography.labelSmall,
                            color = Silver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        item.year?.let { year ->
                            Text(
                                text = year.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Silver,
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
internal fun TvExpandedPosterMetadata(item: MediaItem, prefs: com.torve.domain.model.RatingDisplayPrefs, showRatings: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val metadata = listOfNotNull(item.year?.toString(), item.runtime?.takeIf { it > 0 }?.let { minutes ->
            if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}min"
        })
        Row(Modifier.fillMaxWidth().testTag("catalog_expanded_metadata:${item.id}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), color = Silver,
                style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (showRatings) TvPosterRatingPills(item, prefs, maxRatings = 2)
            val imdbVotes = item.ratings?.imdbVotes?.takeIf { it > 0 }
            val votes = imdbVotes ?: item.voteCount?.takeIf { it > 0 }
            votes?.let {
                val count = when { it >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", it / 1_000_000f)
                    it >= 1000 -> String.format(java.util.Locale.US, "%.1fK", it / 1000f)
                    else -> it.toString() }
                Text(localizedUiText("$count ${if (imdbVotes != null) "IMDb votes" else "TMDB votes"}"),
                    color = Silver, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("catalog_expanded_votes:${item.id}"))
            }
        }
        Text(item.overview.orEmpty(), style = MaterialTheme.typography.bodyMedium,
            color = Snow.copy(alpha = .94f), minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("catalog_expanded_synopsis:${item.id}"))
        Text(item.cast.take(6).map { it.name }.filter { it.isNotBlank() }.joinToString(", "),
            style = MaterialTheme.typography.labelMedium, color = Silver, minLines = 1, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("catalog_expanded_cast:${item.id}"))
    }
}

@Composable
private fun TvPosterWatchedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Charcoal.copy(alpha = 0.86f))
            .border(1.dp, Amber.copy(alpha = 0.58f), RoundedCornerShape(999.dp))
            .padding(5.dp),
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.tv_watched),
            tint = AmberLight,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
internal fun TvMediaContextMenu(
    state: TvMediaContextMenuState,
    onDismiss: () -> Unit,
    onAction: (TvMediaContextMenuAction) -> Unit,
) {
    val density = LocalDensity.current
    val menuWidth = 320.dp
    val menuOffsetPx = with(density) { 14.dp.roundToPx() }
    val menuX = ((state.anchorBounds?.right ?: 120f).toInt() + menuOffsetPx).coerceAtLeast(menuOffsetPx)
    val menuY = ((state.anchorBounds?.top ?: 100f).toInt()).coerceAtLeast(menuOffsetPx)
    val firstActionRequester = remember(state.item.id, state.actions.size) { FocusRequester() }

    BackHandler(onBack = onDismiss)

    LaunchedEffect(state.item.id, state.actions.size) {
        repeat(10) {
            kotlinx.coroutines.delay(50)
            val success = runCatching { firstActionRequester.requestFocus() }.isSuccess
            if (success) return@LaunchedEffect
        }
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(menuX, menuY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .width(menuWidth)
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(com.torve.android.ui.theme.menuPanelColor(true))
                .border(1.dp, com.torve.android.ui.theme.menuOutlineColor(), RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.actions.forEachIndexed { index, action ->
                if (index > 0 && action.isSecondary && !state.actions[index - 1].isSecondary) {
                    HorizontalDivider(
                        color = Steel.copy(alpha = 0.35f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                TvMediaContextMenuRow(
                    action = action,
                    modifier = if (index == 0) Modifier.focusRequester(firstActionRequester) else Modifier,
                    onClick = { onAction(action) },
                )
            }
        }
    }
}

@Composable
private fun TvMediaContextMenuRow(
    action: TvMediaContextMenuAction,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && action.isDestructive -> Color(0xFFEF6C6C)
            focused && action.isLocked -> AmberLight
            focused -> Amber
            else -> Color.Transparent
        },
        label = "contextMenuRowBorder",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    focused && action.isDestructive -> Color(0xFF4A2727)
                    focused && action.isLocked -> Color(0xFF3D3622)
                    focused -> Amber.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = action.label,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    action.isDestructive -> Color(0xFFFFB4B4)
                    action.isLocked -> if (focused) AmberLight else Silver
                    focused -> Amber
                    else -> Snow
                },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (action.isLocked) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (focused) AmberLight else Silver,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TvSeeAllButton(
    modifier: Modifier = Modifier,
    onMoveDown: () -> Unit,
    onMoveUp: () -> Unit = {},
    onClick: () -> Unit,
    onFocused: () -> Unit,
    width: Dp = 106.dp,
    aspectRatio: Float = 2f / 3f,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.045f else 1f, label = "seeAllScale")
    val borderColor = if (focused) AmberLight else Steel.copy(alpha = 0.35f)

    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(aspectRatio)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Amber.copy(alpha = if (focused) 0.24f else 0.06f),
                        Charcoal.copy(alpha = if (focused) 0.92f else 0.78f),
                        Obsidian.copy(alpha = 0.92f),
                    ),
                ),
                RoundedCornerShape(16.dp),
            )
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown -> {
                        onMoveDown()
                        true
                    }
                    event.key == Key.DirectionDown && event.type == KeyEventType.KeyUp -> true
                    event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown -> {
                        onMoveUp()
                        true
                    }
                    event.key == Key.DirectionUp && event.type == KeyEventType.KeyUp -> true
                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.tv_see_all),
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) AmberLight else Amber,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) AmberLight else Amber,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
