package com.torve.android.tv.components

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.TV_ROW_END_GUTTER
import com.torve.android.tv.TvImagePrefetcher
import com.torve.android.tv.focus.TvFocusTargetId
import com.torve.android.tv.focus.rememberRegisteredTvFocusRequester
import com.torve.android.tv.focus.rememberTvModalFocusRestoreController
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.ui.home.ALL_STREAMING_SERVICES
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.android.ui.components.PreferredRatingPills
import com.torve.domain.model.MediaItem
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingSource
import com.torve.domain.model.allowsTmdbRatingProvider
import com.torve.domain.model.bestDisplayRating
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.presentation.seeall.SeeAllViewModel
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.launch
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

private data class TvMediaContextMenuState(
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
            val key = item.tmdbId?.let { "${item.type}:$it" } ?: "${item.type}:${item.id}"
            key !in seen && localSeen.add(key)
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
            val key = item.tmdbId?.let { "${item.type}:$it" } ?: "${item.type}:${item.id}"
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
}

@Composable
fun rememberTvFocusMemory(): TvFocusMemory = remember { TvFocusMemory() }

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
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    heroOverlay: (@Composable () -> Unit)? = null,
    heroOverlayFocusRequester: FocusRequester? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    leadingContentFocusRequester: FocusRequester? = null,
    leadingContentVisible: Boolean = true,
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
) {
    val catalogHeroMode = presentationMode == TvRailsPresentationMode.CatalogHero
    val libraryHeroMode = presentationMode == TvRailsPresentationMode.LibraryHero
    val settingsViewModel: SettingsViewModel = koinInject()
    val settingsState by settingsViewModel.state.collectAsState()
    val ratingPrefs = settingsState.ratingPrefs
    val tvCardRatingPrefs = remember(ratingPrefs) { ratingPrefs.tvExternalCardRatingPrefs() }
    val showTmdbRating = tvCardRatingPrefs.allowsTmdbRatingProvider()
    val context = LocalContext.current
    val tvPrefs = remember { context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE) }
    val showTitlesOnCards = tvPrefs.getBoolean("tv_show_poster_titles", true) &&
        browseLayout == TvBrowseLayout.POSTER_ONLY

    LaunchedEffect(rails) {
        if (rails.isNotEmpty()) {
            TvImagePrefetcher.prefetchRails(
                context = context,
                screenName = screenId,
                rails = rails,
                maxItems = 36,
            )
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
    val signature = remember(rails) { rails.joinToString("|") { r -> "${r.key}:${r.items.size}:${r.items.take(5).joinToString(",") { it.id }}" } }
    var contextMenuState by remember { mutableStateOf<TvMediaContextMenuState?>(null) }
    var activeExclusiveRowKey by remember(screenId) {
        mutableStateOf(focusMemory.lastFocusedRowKey)
    }
    var contentFocusSignal by remember(screenId) { mutableIntStateOf(0) }

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
        val targetRow = rails.getOrNull(fromRowIndex + delta) ?: return
        activeExclusiveRowKey = targetRow.key
        focusMemory.lastFocusedRowKey = targetRow.key
        val targetIndex = focusMemory.lastFocusedIndexByRow[targetRow.key]
            ?.coerceIn(0, (targetRow.items.size - 1).coerceAtLeast(0))
            ?: 0
        targetRow.items.getOrNull(targetIndex)?.let { item ->
            onMediaFocused?.invoke(item)
        }
        coroutineScope.launch {
            runCatching {
                columnListState.scrollToItem(
                    index = railListItemOffset + fromRowIndex + delta,
                    scrollOffset = 0,
                )
            }
            withFrameNanos { }
            kotlinx.coroutines.delay(24)
            val targetRequester = requesterMap["${targetRow.key}:$targetIndex"]
                ?: requesterMap["${targetRow.key}:0"]
            if (targetRequester != null) {
                focusMemory.lastFocusedIndexByRow[targetRow.key] = targetIndex
                runCatching { targetRequester.requestFocus() }
            }
        }
    }

    fun moveToActiveExclusiveRowFocus() {
        if (!effectiveFocusExclusive) return
        val leadingEntryVisible = showLeadingContentBeforeRails && leadingContentVisible
        val targetRowKey = if (leadingEntryVisible) {
            rails.firstOrNull()?.key
        } else {
            activeExclusiveRowKey?.takeIf { key -> rails.any { it.key == key } }
                ?: rails.firstOrNull()?.key
        } ?: return
        val targetRowIndex = rails.indexOfFirst { it.key == targetRowKey }.takeIf { it >= 0 } ?: return
        val targetIndex = focusMemory.lastFocusedIndexByRow[targetRowKey]
            ?.coerceIn(0, (rails[targetRowIndex].items.size - 1).coerceAtLeast(0))
            ?: 0
        rails[targetRowIndex].items.getOrNull(targetIndex)?.let { item ->
            onMediaFocused?.invoke(item)
        }
        coroutineScope.launch {
            runCatching {
                columnListState.scrollToItem(
                    index = railListItemOffset + targetRowIndex,
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
        coroutineScope.launch {
            runCatching {
                columnListState.scrollToItem(0, scrollOffset = 0)
            }
            withFrameNanos { }
            kotlinx.coroutines.delay(24)
            runCatching { requester.requestFocus() }
        }
    }

    fun moveToRowFocus(targetRowIndex: Int) {
        val targetRow = rails.getOrNull(targetRowIndex) ?: return
        val targetIndex = focusMemory.lastFocusedIndexByRow[targetRow.key]
            ?.coerceIn(0, (targetRow.items.size - 1).coerceAtLeast(0))
            ?: 0
        coroutineScope.launch {
            runCatching {
                columnListState.scrollToItem(
                    index = railListItemOffset + targetRowIndex,
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
        val actionsProvider = contextMenuActionsForItem ?: return
        val actions = actionsProvider(item, progress).filter { it.label.isNotBlank() }
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
    }

    fun dismissContextMenu(restoreFocus: Boolean = true) {
        if (restoreFocus && contextMenuState != null) {
            modalFocusRestoreController.requestRestore()
        }
        contextMenuState = null
    }

    // Prune stale entries when rails change so disposed FocusRequesters
    // don't accumulate and cause crashes on focus restore.
    LaunchedEffect(signature) {
        val validKeys = mutableSetOf<String>()
        val validRowKeys = mutableSetOf<String>()
        for (rail in rails) {
            validRowKeys += rail.key
            for (i in rail.items.indices) { validKeys.add("${rail.key}:$i") }
            validKeys.add("${rail.key}:see_all")
        }
        requesterMap.keys.retainAll(validKeys)
        rowListStateByKey.keys.retainAll(validRowKeys)
        // TODO: modalFocusRestoreController does not expose a pruneStaleTargets() method,
        // so stale entries in its internal requesterByTarget map are not cleaned up here.
        // Targets are individually unregistered via DisposableEffect in rememberRegisteredTvFocusRequester,
        // but orphaned entries could linger if composition disposal is delayed.
    }

    LaunchedEffect(signature, shouldAutoFocus, autoFocusRequestNonce) {
        if (!shouldAutoFocus) return@LaunchedEffect
        if (rails.isEmpty()) return@LaunchedEffect
        val firstKey = rails.first().key
        repeat(30) {
            withFrameNanos { }
            kotlinx.coroutines.delay(50)
            val targetRowKey = focusMemory.lastFocusedRowKey?.takeIf { row -> rails.any { it.key == row } }
                ?: firstKey
            val targetIndex = focusMemory.lastFocusedIndexByRow[targetRowKey] ?: 0
            val target = requesterMap["$targetRowKey:$targetIndex"] ?: requesterMap["$firstKey:0"]
            if (target != null) {
                val beforeFocusSignal = contentFocusSignal
                val requested = runCatching { target.requestFocus() }.isSuccess
                if (requested) {
                    withFrameNanos { }
                    if (contentFocusSignal != beforeFocusSignal) {
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    LaunchedEffect(contextMenuState, modalFocusRestoreController.pendingRestore?.restoreToken) {
        if (contextMenuState != null) return@LaunchedEffect
        modalFocusRestoreController.restorePendingFocus(
            screenId = screenId,
            outerListState = columnListState,
            innerListStateForRowKey = { rowKey -> rowListStateByKey[rowKey] },
        )
    }

    when {
        loading && rails.isEmpty() -> {
            val loadingRequester = remember { FocusRequester() }
            onFirstContentRequester(loadingRequester)
            Box(
                modifier = modifier
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
                modifier = modifier
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
                    text = emptyMessage,
                    style = MaterialTheme.typography.titleLarge,
                    color = Silver,
                )
            }
        }

        else -> {
            val railStartPad = TV_PAGE_CONTENT_GUTTER
            val rowItemSpacing = if (libraryHeroMode) 14.dp else 12.dp
            val rowVerticalFocusInset = if (libraryHeroMode) 10.dp else 8.dp
            BoxWithConstraints(modifier = modifier.fillMaxSize()) {
                val fixedHeroRailViewport = heroOverlay != null &&
                    (catalogHeroMode || libraryHeroMode)
                val railTop = if (fixedHeroRailViewport) {
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
                            verticalArrangement = Arrangement.spacedBy(if (libraryHeroMode) 18.dp else 16.dp),
                        ) {
                    if (heroOverlay != null && !fixedHeroRailViewport) {
                        item(key = "hero_overlay") {
                            heroOverlay()
                        }
                    }

                    leadingContent?.takeIf { showLeadingContentBeforeRails }?.let { content ->
                        item(key = "leading_content") {
                            if (leadingContentVisible) {
                                leadingContentFocusRequester?.let { onFirstContentRequester(it) }
                            }
                            Box(
                                modifier = Modifier.onPreviewKeyEvent { event ->
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

                    itemsIndexed(rails, key = { _, row -> row.key }) { rowIndex, row ->
                    val rowListState = rememberLazyListState()
                    rowListStateByKey[row.key] = rowListState
                    val leadingEntryVisible = showLeadingContentBeforeRails && leadingContentVisible
                    val activeExclusiveKey = if (leadingEntryVisible) {
                        rails.firstOrNull()?.key
                    } else {
                        activeExclusiveRowKey?.takeIf { key -> rails.any { rail -> rail.key == key } }
                            ?: focusMemory.lastFocusedRowKey?.takeIf { key -> rails.any { rail -> rail.key == key } }
                            ?: rails.firstOrNull()?.key
                    }
                    val activeExclusiveIndex = rails.indexOfFirst { it.key == activeExclusiveKey }
                    val rowIsActive = !effectiveFocusExclusive || row.key == activeExclusiveKey
                    val rowIsPeek = allowInactivePeekRows &&
                        effectiveFocusExclusive &&
                        !leadingEntryVisible &&
                        activeExclusiveIndex >= 0 &&
                        rowIndex == activeExclusiveIndex + 1
                    val rowIsVisible = rowIsActive || rowIsPeek
                    val rowCanReceiveFocus = rowIsActive
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
                    val posterCardWidth = if (libraryHeroMode) {
                        when {
                            isHeroLayout && isContinueRail -> basePosterWidth * 0.67f
                            isHeroLayout -> basePosterWidth * 0.9f
                            else -> basePosterWidth
                        }
                    } else {
                        basePosterWidth
                    }
                    val visibleSectionModifier = if (libraryHeroMode && !effectiveFocusExclusive) {
                        when (row.cardStyle) {
                            TvCardStyle.POSTER -> Modifier.height(posterCardWidth * 1.5f + 104.dp)
                            TvCardStyle.BACKDROP -> Modifier.height(backdropCardWidth * 0.5625f + 104.dp)
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
                            .then(if (rowIsActive) Modifier else Modifier.clearAndSetSemantics { }),
                        verticalArrangement = Arrangement.spacedBy(if (libraryHeroMode) 14.dp else 12.dp),
                    ) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (libraryHeroMode) Snow.copy(alpha = 0.98f) else Snow,
                            fontWeight = if (libraryHeroMode) FontWeight.Bold else FontWeight.SemiBold,
                            modifier = Modifier.padding(start = railStartPad),
                        )

                        LazyRow(
                            state = rowListState,
                            modifier = if (libraryHeroMode || catalogHeroMode) {
                                Modifier
                                    .graphicsLayer { clip = true }
                                    .clipToBounds()
                            } else {
                                Modifier
                            },
                            horizontalArrangement = Arrangement.spacedBy(rowItemSpacing),
                            contentPadding = PaddingValues(
                                start = railStartPad,
                                top = if (libraryHeroMode) 26.dp else rowVerticalFocusInset,
                                end = TV_ROW_END_GUTTER,
                                bottom = if (libraryHeroMode) 30.dp else rowVerticalFocusInset,
                            ),
                        ) {
                            itemsIndexed(
                                items = row.items,
                                key = { itemIndex, item ->
                                    item.tmdbId?.let { "tmdb_${item.type}_${it}_$itemIndex" }
                                        ?: "${item.type}_${item.id}_$itemIndex"
                                },
                            ) { itemIndex, item ->
                                val mapKey = "${row.key}:$itemIndex"
                                val target = remember(row.key, itemIndex, item.id, item.tmdbId) {
                                    TvFocusTargetId(
                                        screenId = screenId,
                                        rowKey = row.key,
                                        itemKey = item.tmdbId?.let { "tmdb_${item.type}_$it" } ?: "${item.type}_${item.id}",
                                        rowIndex = rowIndex,
                                        itemIndex = itemIndex,
                                        targetType = "card",
                                    )
                                }
                                val baseRequester = remember(mapKey) { FocusRequester() }
                                val focusRequester = rememberRegisteredTvFocusRequester(
                                    controller = modalFocusRestoreController,
                                    target = target,
                                    externalRequester = baseRequester,
                                )
                                requesterMap[mapKey] = focusRequester

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
                                val cardModifier = Modifier
                                    .focusRequester(focusRequester)
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
                                        } else if (rowIndex == 0 && heroOverlayFocusRequester != null) {
                                            up = heroOverlayFocusRequester
                                        } else if (rowIndex == 0 && headerFocusRequester != null && heroOverlay != null) {
                                            up = headerFocusRequester
                                        }
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (!effectiveFocusExclusive) return@onPreviewKeyEvent false
                                        when {
                                            event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown -> {
                                                moveExclusiveRowFocus(rowIndex, 1)
                                                true
                                            }
                                            event.key == Key.DirectionDown && event.type == KeyEventType.KeyUp -> true
                                            event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown -> {
                                                if (rowIndex == 0 && leadingContentFocusRequester != null) {
                                                    moveToLeadingContent()
                                                } else if (rowIndex == 0 && headerFocusRequester != null && heroOverlay != null) {
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

                                val onItemFocused: () -> Unit = {
                                    val previousFocusedRowKey = focusMemory.lastFocusedRowKey
                                    modalFocusRestoreController.markFocused(target)
                                    if (effectiveFocusExclusive) {
                                        activeExclusiveRowKey = row.key
                                    }
                                    focusMemory.lastFocusedRowKey = row.key
                                    focusMemory.lastFocusedIndexByRow[row.key] = itemIndex
                                    onContentFocused(focusRequester)
                                    contentFocusSignal += 1
                                    onMediaFocused?.invoke(item)
                                    if (effectiveFocusExclusive && previousFocusedRowKey != row.key) {
                                        coroutineScope.launch {
                                            runCatching {
                                                columnListState.scrollToItem(
                                                    index = railListItemOffset + rowIndex,
                                                    scrollOffset = 0,
                                                )
                                            }
                                        }
                                    } else if (previousFocusedRowKey != null && previousFocusedRowKey != row.key) {
                                        coroutineScope.launch {
                                            runCatching {
                                                columnListState.scrollToItem(
                                                    index = railListItemOffset + rowIndex,
                                                    scrollOffset = 0,
                                                )
                                            }
                                        }
                                    }
                                }

                                when (row.cardStyle) {
                                    TvCardStyle.SERVICE -> {
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = if (libraryHeroMode) 8.dp else 0.dp)
                                        ) {
                                            TvStreamingServiceCard(
                                                item = item,
                                                modifier = cardModifier
                                                    .width(220.dp)
                                                    .height(120.dp),
                                                onClick = { onMediaClick(item) },
                                                onFocused = onItemFocused,
                                            )
                                        }
                                    }

                                    TvCardStyle.BACKDROP -> {
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = if (libraryHeroMode) 8.dp else 0.dp)
                                        ) {
                                            TvBackdropCard(
                                                item = item,
                                                modifier = cardModifier,
                                                width = backdropCardWidth,
                                                onClick = { onMediaClick(item) },
                                                onFocused = onItemFocused,
                                                progress = progress,
                                                showTmdbRating = showTmdbRating,
                                                ratingPrefs = tvCardRatingPrefs,
                                            )
                                        }
                                    }

                                    TvCardStyle.POSTER -> {
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = if (libraryHeroMode) 8.dp else 0.dp)
                                        ) {
                                            TvPosterCard(
                                                item = item,
                                                modifier = cardModifier
                                                    .width(posterCardWidth)
                                                    .aspectRatio(2f / 3f),
                                                onClick = { onMediaClick(item) },
                                                onFocused = onItemFocused,
                                                progress = progress,
                                                showTitles = showTitlesOnCards,
                                                showTmdbRating = showTmdbRating,
                                                ratingPrefs = tvCardRatingPrefs,
                                                sourceAwareRatings = sourceAwareRatings,
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
                                        }
                                    }
                                }
                            }

                            if (showSeeAllCards && onSeeAll != null && canShowSeeAllForRail(row.key)) {
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
                                    requesterMap["${row.key}:see_all"] = seeAllRequester
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
                                                if (rowIndex == 0 && leadingContentFocusRequester != null) {
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
                                                SeeAllViewModel.pendingItems[row.key] = row.title to row.items
                                            } else {
                                                SeeAllViewModel.pendingItems.remove(row.key)
                                            }
                                            onSeeAll(row.key, row.title)
                                        },
                                        onFocused = {
                                            modalFocusRestoreController.markFocused(seeAllTarget)
                                            if (effectiveFocusExclusive) {
                                                activeExclusiveRowKey = row.key
                                            }
                                            focusMemory.lastFocusedRowKey = row.key
                                            onContentFocused(seeAllRequester)
                                        },
                                        width = seeAllWidth,
                                        aspectRatio = seeAllAspectRatio,
                                    )
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

                    if (fixedHeroRailViewport) {
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

            contextMenuState?.let { menuState ->
                TvMediaContextMenu(
                    state = menuState,
                    onDismiss = { dismissContextMenu(restoreFocus = true) },
                    onAction = { action ->
                        onContextMenuAction?.invoke(menuState.item, action, menuState.progress)
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
    val brandColor = ALL_STREAMING_SERVICES
        .firstOrNull { it.tmdbProviderId == providerId }
        ?.brandColor
        ?: Charcoal
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        brandColor.copy(alpha = if (focused) 0.42f else 0.30f),
                        Charcoal.copy(alpha = 0.92f),
                        Obsidian.copy(alpha = 0.96f),
                    ),
                ),
            )
            .border(3.dp, borderColor, RoundedCornerShape(18.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 28.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        val logoUrl = item.posterUrl ?: item.backdropUrl
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = Snow,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TvPosterCard(
    item: MediaItem,
    modifier: Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    progress: Float? = null,
    showTitles: Boolean = true,
    showTmdbRating: Boolean = true,
    ratingPrefs: RatingDisplayPrefs,
    sourceAwareRatings: Boolean = false,
    onContextMenuRequested: ((Rect?) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.052f else 1f, label = "posterScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "posterBorder",
    )
    val cardShape = RoundedCornerShape(16.dp)
    val focusShape = RoundedCornerShape(18.dp)

    val isWatched = (progress ?: 0f) >= TV_CARD_WATCHED_THRESHOLD

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                shape = focusShape
                clip = true
                scaleX = scale
                scaleY = scale
            }
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
            .clip(cardShape)
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
    ) {
        val posterImageUrl = item.posterUrl ?: item.backdropUrl
        if (posterImageUrl.isNullOrBlank()) {
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
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Snow,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    item.upcomingScheduleMetadata()?.let { metadata ->
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
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        val shouldShowTitle = showTitles || item.id.startsWith("trakt-calendar:")
        if (shouldShowTitle) {
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
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Snow,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val scheduleMetadata = item.upcomingScheduleMetadata()
                        if (scheduleMetadata != null) {
                            Text(
                                text = scheduleMetadata,
                                style = MaterialTheme.typography.labelMedium,
                                color = Silver,
                            )
                        } else {
                            item.year?.let { year ->
                                Text(
                                    text = year.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Silver,
                                )
                            }
                        }
                        if (!sourceAwareRatings && showTmdbRating && item.rating != null) {
                            if (scheduleMetadata != null || item.year != null) {
                                Spacer(modifier = Modifier.width(2.dp))
                            }
                            Image(
                                painter = painterResource(id = R.drawable.tmbd_logo),
                                contentDescription = "TMDB",
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = String.format("%.1f", item.rating),
                                style = MaterialTheme.typography.labelMedium,
                                color = Silver,
                            )
                        }
                    }
                }
            }
        }

        if (sourceAwareRatings) {
            TvLibraryRatingPills(
                item = item,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp),
            )
        } else {
            item.ratings.withFallbackTmdbScore(item.rating)?.let { ratings ->
                PreferredRatingPills(
                    ratings = ratings,
                    prefs = ratingPrefs.copy(maxRatingsOnCard = 2),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(7.dp),
                )
            }
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
}

@Composable
private fun TvLibraryRatingPills(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    val primary = item.bestDisplayRating()
    val rottenTomatoes = item.ratings?.rottenTomatoesScore?.takeIf { it in 1..100 }
    if (primary == null && rottenTomatoes == null) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        primary?.let { rating ->
            TvSourceAwareRatingChip(
                source = rating.source,
                value = rating.value,
            )
        }
        rottenTomatoes?.let { score ->
            TvPercentRatingChip(
                label = "RT",
                value = score,
                accent = if (score >= 60) Color(0xFF67B346) else Color(0xFFFA320A),
            )
        }
    }
}

@Composable
private fun TvSourceAwareRatingChip(
    source: RatingSource,
    value: Double,
    modifier: Modifier = Modifier,
) {
    val sourceLabel = when (source) {
        RatingSource.IMDB -> "IMDb"
        RatingSource.MDBLIST -> "MDB"
        RatingSource.TRAKT -> "Trakt"
        RatingSource.TMDB -> "TMDB"
        else -> source.displayName
    }
    val accent = when (source) {
        RatingSource.IMDB -> Color(0xFFF5C518)
        RatingSource.MDBLIST -> Color(0xFFFF6B00)
        RatingSource.TRAKT -> Color(0xFFED1C24)
        RatingSource.TMDB -> Color(0xFF01D277)
        else -> Amber
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Obsidian.copy(alpha = 0.88f))
            .border(1.dp, accent.copy(alpha = 0.58f), RoundedCornerShape(999.dp))
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = sourceLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.5.sp, letterSpacing = 0.sp),
            color = accent,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            text = String.format("%.1f", value),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.sp),
            color = Snow,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TvPercentRatingChip(
    label: String,
    value: Int,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Obsidian.copy(alpha = 0.88f))
            .border(1.dp, accent.copy(alpha = 0.58f), RoundedCornerShape(999.dp))
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.5.sp, letterSpacing = 0.sp),
            color = accent,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            text = "$value%",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.sp),
            color = Snow,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TvPosterWatchedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Charcoal.copy(alpha = 0.86f))
            .border(1.dp, Amber.copy(alpha = 0.58f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.tv_watched),
            style = MaterialTheme.typography.labelSmall,
            color = AmberLight,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TvMediaContextMenu(
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
                .clip(RoundedCornerShape(16.dp))
                .background(Charcoal.copy(alpha = 0.95f))
                .border(2.dp, Steel.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
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
                    focused -> Obsidian.copy(alpha = 0.7f)
                    action.isSecondary -> Obsidian.copy(alpha = 0.42f)
                    else -> Obsidian.copy(alpha = 0.56f)
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
