package com.streamvault.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import com.streamvault.domain.model.MediaItem

@Composable
fun TvShowsScreen(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    heroOverlay: (@Composable () -> Unit)? = null,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)? = null,
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    shouldAutoFocus: Boolean = true,
) {
    TvCatalogRailsScreen(
        mediaType = "tv",
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = onMediaClick,
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        onMediaFocused = onMediaFocused,
        onSeeAll = onSeeAll,
        heroOverlay = heroOverlay,
        shouldAutoFocus = shouldAutoFocus,
    )
}
