package com.torve.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import com.torve.domain.model.MediaItem

@Composable
fun TvMoviesScreen(
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
        mediaType = "movie",
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
