package com.torve.android.tv.focus

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

internal fun Modifier.tvSettingsRowNavigation(
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
): Modifier = onPreviewKeyEvent { event ->
    val action = when (event.key) {
        Key.DirectionUp -> onUp
        Key.DirectionDown -> onDown
        else -> null
    }
    if (action == null) false else {
        if (event.type == KeyEventType.KeyDown) action()
        true
    }
}

/** Let normal focus scrolling reveal composed rows; only scroll explicitly for virtualized rows. */
internal suspend fun focusTvSettingsRow(
    listState: LazyListState,
    index: Int,
    requester: FocusRequester,
    isFocused: () -> Boolean,
): Boolean {
    runCatching { requester.requestFocus() }
    withFrameNanos { }
    if (isFocused()) return true
    if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
        listState.animateScrollToItem(index)
    }
    repeat(12) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        withFrameNanos { }
        if (isFocused()) return true
    }
    return false
}
