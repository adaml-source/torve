package com.torve.android.tv.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.flow.first

/** Episode entry and playback return own their focus; metadata must not focus Play afterwards. */
@Composable
internal fun TvDetailsPrimaryEntryFocus(
    contentId: String?,
    episodeEntry: Boolean,
    sourcePickerVisible: Boolean,
    playRequester: FocusRequester,
) {
    var assigned by rememberSaveable(contentId) { mutableStateOf(false) }
    LaunchedEffect(contentId, episodeEntry, sourcePickerVisible) {
        if (contentId == null || assigned || sourcePickerVisible) return@LaunchedEffect
        assigned = true
        if (episodeEntry) return@LaunchedEffect
        withFrameNanos { }
        runCatching { playRequester.requestFocus() }
    }
}

/** The old empty layout can still be installed when asynchronously loaded metadata arrives. */
internal suspend fun LazyListState.scrollToComposedTvDetailsRow(index: Int) {
    snapshotFlow { layoutInfo.totalItemsCount }.first { it > index }
    requestScrollToItem(index)
    withFrameNanos { }
}
