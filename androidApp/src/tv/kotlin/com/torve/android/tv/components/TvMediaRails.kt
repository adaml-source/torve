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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.tv.focus.TvFocusTargetId
import com.torve.android.tv.focus.rememberRegisteredTvFocusRequester
import com.torve.android.tv.focus.rememberTvModalFocusRestoreController
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.MediaItem
import com.torve.domain.model.allowsTmdbRatingProvider
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

enum class TvCardStyle { POSTER, BACKDROP }

enum class TvBrowseLayout { POSTER_ONLY, INFO_PANEL }

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
        val filtered = rail.items.filter { item ->
            val key = item.tmdbId?.let { "${item.type}:$it" } ?: "${item.type}:${item.id}"
            seen.add(key)
        }
        // Keep original items if deduping would drop below the minimum
        val result = if (filtered.size < minItemsPerRail) rail.items else filtered
        result.forEach { item ->
            val key = item.tmdbId?.let { "${item.type}:$it" } ?: "${item.type}:${item.id}"
            seen.add(key)
        }
        if (result.isEmpty()) null else rail.copy(items = result)
    }
}

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
    shouldAutoFocus: Boolean = true,
    browseLayout: TvBrowseLayout = TvBrowseLayout.INFO_PANEL,
    progressResolver: ((MediaItem, Float?) -> Float?)? = null,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)? = null,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)? = null,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
) {
    val settingsViewModel: SettingsViewModel = koinInject()
    val settingsState by settingsViewModel.state.collectAsState()
    val showTmdbRating = settingsState.ratingPrefs.allowsTmdbRatingProvider()
    val context = LocalContext.current
    val tvPrefs = remember { context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE) }
    val showTitlesOnCards = tvPrefs.getBoolean("tv_show_poster_titles", true) &&
        browseLayout == TvBrowseLayout.POSTER_ONLY

    val requesterMap = remember { mutableMapOf<String, FocusRequester>() }
    val rowListStateByKey = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val modalFocusRestoreController = rememberTvModalFocusRestoreController(key = "media_rails_$screenId")
    val columnListState = rememberLazyListState()
    val signature = remember(rails) { rails.joinToString("|") { r -> "${r.key}:${r.items.size}:${r.items.take(5).joinToString(",") { it.id }}" } }
    var contextMenuState by remember { mutableStateOf<TvMediaContextMenuState?>(null) }

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

    LaunchedEffect(signature, shouldAutoFocus) {
        if (!shouldAutoFocus) return@LaunchedEffect
        if (rails.isEmpty()) return@LaunchedEffect
        val firstKey = rails.first().key
        val targetRowKey = focusMemory.lastFocusedRowKey?.takeIf { row ->
            rails.any { it.key == row }
        } ?: firstKey
        val targetIndex = focusMemory.lastFocusedIndexByRow[targetRowKey] ?: 0
        val target = requesterMap["$targetRowKey:$targetIndex"] ?: requesterMap["$firstKey:0"]
        try { target?.requestFocus() } catch (_: Throwable) { }
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
            val railStartPad = if (browseLayout == TvBrowseLayout.INFO_PANEL) 24.dp else 40.dp
            val rowItemSpacing = 12.dp
            val rowVerticalFocusInset = 10.dp
            LazyColumn(
                state = columnListState,
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                // Hero overlay scrolls with content
                if (heroOverlay != null) {
                    item(key = "hero_overlay") {
                        heroOverlay()
                    }
                }

                if (leadingContent != null) {
                    item(key = "leading_content") {
                        leadingContent()
                    }
                }

                itemsIndexed(rails, key = { _, row -> row.key }) { rowIndex, row ->
                    val rowListState = rememberLazyListState()
                    rowListStateByKey[row.key] = rowListState
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Snow,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = railStartPad),
                        )

                        LazyRow(
                            state = rowListState,
                            modifier = Modifier.clipToBounds(),
                            horizontalArrangement = Arrangement.spacedBy(rowItemSpacing),
                            contentPadding = PaddingValues(
                                start = railStartPad,
                                top = rowVerticalFocusInset,
                                end = 32.dp,
                                bottom = rowVerticalFocusInset,
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
                                if (isFirstItem) {
                                    onFirstContentRequester(focusRequester)
                                }

                                val railProgress = row.progressFor(item)
                                val progress = progressResolver?.invoke(item, railProgress) ?: railProgress
                                val cardModifier = Modifier
                                    .focusRequester(focusRequester)
                                    .focusProperties {
                                        if (itemIndex == 0) {
                                            left = railFocusRequester
                                        }
                                        // Only redirect UP to the header when the hero overlay
                                        // is actually rendered — otherwise the FocusRequester
                                        // is unattached and causes a crash.
                                        if (rowIndex == 0 && leadingContentFocusRequester != null) {
                                            up = leadingContentFocusRequester
                                        } else if (rowIndex == 0 && heroOverlayFocusRequester != null) {
                                            up = heroOverlayFocusRequester
                                        } else if (rowIndex == 0 && headerFocusRequester != null && heroOverlay != null) {
                                            up = headerFocusRequester
                                        }
                                    }

                                val onItemFocused: () -> Unit = {
                                    modalFocusRestoreController.markFocused(target)
                                    focusMemory.lastFocusedRowKey = row.key
                                    focusMemory.lastFocusedIndexByRow[row.key] = itemIndex
                                    onContentFocused(focusRequester)
                                    onMediaFocused?.invoke(item)
                                }

                                when (row.cardStyle) {
                                    TvCardStyle.BACKDROP -> {
                                        TvBackdropCard(
                                            item = item,
                                            modifier = cardModifier,
                                            onClick = { onMediaClick(item) },
                                            onFocused = onItemFocused,
                                            progress = progress,
                                            showTmdbRating = showTmdbRating,
                                        )
                                    }

                                    TvCardStyle.POSTER -> {
                                        val posterWidth = if (heroOverlay != null && rowIndex == 0) {
                                            118.dp
                                        } else {
                                            132.dp
                                        }
                                        TvPosterCard(
                                            item = item,
                                            modifier = cardModifier
                                                .width(posterWidth)
                                                .aspectRatio(2f / 3f),
                                            onClick = { onMediaClick(item) },
                                            onFocused = onItemFocused,
                                            progress = progress,
                                            showTitles = showTitlesOnCards,
                                            showTmdbRating = showTmdbRating,
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

                            if (onSeeAll != null) {
                                item(key = "${row.key}_see_all") {
                                    val seeAllRequester = remember("${row.key}_see_all") { FocusRequester() }
                                    requesterMap["${row.key}:see_all"] = seeAllRequester
                                    TvSeeAllButton(
                                        modifier = Modifier
                                            .focusRequester(seeAllRequester)
                                            .focusProperties {
                                                if (rowIndex == 0 && leadingContentFocusRequester != null) {
                                                    up = leadingContentFocusRequester
                                                } else if (rowIndex == 0 && heroOverlayFocusRequester != null) {
                                                    up = heroOverlayFocusRequester
                                                } else if (rowIndex == 0 && headerFocusRequester != null && heroOverlay != null) {
                                                    up = headerFocusRequester
                                                }
                                            },
                                        onClick = { onSeeAll(row.key, row.title) },
                                        onFocused = {
                                            focusMemory.lastFocusedRowKey = row.key
                                            onContentFocused(seeAllRequester)
                                        },
                                    )
                                }
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
                        onContextMenuAction?.invoke(menuState.item, action, menuState.progress)
                        dismissContextMenu(restoreFocus = true)
                    },
                )
            }
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
    onContextMenuRequested: ((Rect?) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.06f else 1f, label = "posterScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "posterBorder",
    )

    LaunchedEffect(focused, item) {
        if (focused) onFocused()
    }
    val isWatched = (progress ?: 0f) >= TV_CARD_WATCHED_THRESHOLD

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
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
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            AsyncImage(
                model = posterImageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showTitles) {
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
                        item.year?.let { year ->
                            Text(
                                text = year.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = Silver,
                            )
                        }
                        if (showTmdbRating && item.rating != null) {
                            if (item.year != null) {
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
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.06f else 1f, label = "seeAllScale")
    val borderColor = if (focused) AmberLight else Steel.copy(alpha = 0.4f)

    Box(
        modifier = modifier
            .width(120.dp)
            .aspectRatio(2f / 3f)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(Charcoal)
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.tv_see_all),
            style = MaterialTheme.typography.titleMedium,
            color = Amber,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
