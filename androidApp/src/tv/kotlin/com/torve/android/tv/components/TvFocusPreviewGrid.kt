package com.torve.android.tv.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.torve.domain.model.MediaItem
import com.torve.android.tv.focus.TvScreenFocusHandle

/** Grid pages use the same focus, metadata and left-anchoring behavior as Home. */
@Composable
internal fun TvFocusPreviewGrid(
    items: List<MediaItem>, screenId: String, columns: Int,
    railFocusRequester: FocusRequester, entryRequester: FocusRequester,
    headerRequester: FocusRequester? = null, modifier: Modifier = Modifier,
    onFirstContentRequester: (FocusRequester) -> Unit = {},
    onContentFocused: (FocusRequester) -> Unit = {},
    onFocused: (MediaItem, Int) -> Unit = { _, _ -> },
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
    contextActions: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)? = null,
    onContextAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)? = null,
    hero: (@Composable () -> Unit)? = null,
    onExitUp: (() -> Unit)? = null,
    singleRow: Boolean = false,
    wrapRows: Boolean = false,
    onClick: (MediaItem) -> Unit,
) {
    val rows = remember(items, columns, screenId, singleRow) {
        val groups = if (singleRow) listOf(items) else items.chunked(columns.coerceAtLeast(1))
        groups.mapIndexed { index, row ->
            TvContentRail("$screenId:row:$index", "", row, showSeeAllCard = false, allowCrossRailDuplicates = true)
        }
    }
    TvMediaRails(rows, railFocusRequester, onClick, onFirstContentRequester, onContentFocused,
        modifier = modifier, screenId = screenId, entryFocusRequester = entryRequester,
        headerFocusRequester = headerRequester, shouldAutoFocus = false, posterColumns = columns,
        browseLayout = TvBrowseLayout.POSTER_ONLY, forcePosterTitles = true,
        presentationMode = TvRailsPresentationMode.CatalogHero, focusExclusive = true,
        compactPreviewRows = true, onExitUp = onExitUp, restoreFocusAcrossRows = true,
        wrapRowFocus = wrapRows,
        heroOverlay = hero, showSeeAllCards = false, registerFocusHandle = registerFocusHandle,
        contextMenuActionsForItem = contextActions, onContextMenuAction = onContextAction,
        onMediaFocused = { item -> onFocused(item, items.indexOfFirst { it.id == item.id && it.type == item.type }.coerceAtLeast(0)) })
}
