package com.torve.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import com.torve.android.tv.components.TvBrowseLayout
import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.domain.model.MediaItem

@Composable
internal fun TvMoviesScreen(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    heroOverlay: (@Composable () -> Unit)? = null,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)? = null,
    onClearMediaFocus: (() -> Unit)? = null,
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    shouldAutoFocus: Boolean = true,
    initialSearchQuery: String? = null,
    browseLayout: TvBrowseLayout = TvBrowseLayout.INFO_PANEL,
    progressResolver: ((MediaItem, Float?) -> Float?)? = null,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)? = null,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)? = null,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
    autoFocusRequestNonce: Int = 0,
) {
    TvCatalogRailsScreen(
        mediaType = "movie",
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = onMediaClick,
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        onMediaFocused = onMediaFocused,
        onClearMediaFocus = onClearMediaFocus,
        onSeeAll = onSeeAll,
        heroOverlay = heroOverlay,
        shouldAutoFocus = shouldAutoFocus,
        initialSearchQuery = initialSearchQuery,
        browseLayout = browseLayout,
        progressResolver = progressResolver,
        contextMenuActionsForItem = contextMenuActionsForItem,
        onContextMenuAction = onContextMenuAction,
        registerFocusHandle = registerFocusHandle,
        autoFocusRequestNonce = autoFocusRequestNonce,
    )
}
