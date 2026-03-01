package com.streamvault.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import com.streamvault.domain.model.MediaItem

@Composable
fun TvMoviesScreen(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
) {
    TvCatalogRailsScreen(
        mediaType = "movie",
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = onMediaClick,
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
    )
}

