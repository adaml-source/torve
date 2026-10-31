package com.torve.android.tv.screens

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Route each remote move explicitly; a grid edge must never fall back to the toolbar. */
internal fun Modifier.tvSearchGridNavigation(
    index: Int,
    count: Int,
    columns: Int,
    state: LazyGridState,
    scope: CoroutineScope,
    requesterAt: (Int) -> FocusRequester?,
    rail: FocusRequester,
    header: FocusRequester,
    onBottom: () -> Unit = {},
): Modifier = onPreviewKeyEvent { event ->
    val rowStart = index / columns * columns
    val rowEnd = minOf(rowStart + columns - 1, count - 1)
    val destination = when (event.key) {
        Key.DirectionRight -> if (index == rowEnd) rowStart else index + 1
        Key.DirectionLeft -> if (index == rowStart) null else index - 1
        Key.DirectionUp -> if (index < columns) null else index - columns
        Key.DirectionDown -> if (rowEnd == count - 1) null else minOf(index + columns, count - 1)
        else -> return@onPreviewKeyEvent false
    }
    if (event.type == KeyEventType.KeyDown) {
        when {
            destination != null -> scope.launch {
                // Lazy grids may not have composed the next row (or the start of
                // a long row). Materialize it before transferring focus.
                val target = requesterAt(destination)
                if (target != null && runCatching { target.requestFocus() }.isSuccess) return@launch
                state.scrollToItem(destination)
                repeat(8) {
                    withFrameNanos { }
                    if (requesterAt(destination)?.let { runCatching { it.requestFocus() }.isSuccess } == true) return@launch
                }
            }
            event.key == Key.DirectionLeft -> runCatching { rail.requestFocus() }
            event.key == Key.DirectionUp -> runCatching { header.requestFocus() }
            event.key == Key.DirectionDown -> onBottom()
        }
    }
    true
}
