package com.streamvault.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.streamvault.android.ui.theme.Obsidian

/** Collapsed rail width — content always starts here, never shifts. */
internal val RAIL_COLLAPSED_WIDTH = 52.dp

@Composable
fun TvScaffold(
    leftRail: @Composable () -> Unit,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
    isFullscreen: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian),
    ) {
        // Content area always starts after the collapsed rail width.
        // This prevents layout shifts when the rail expands/collapses.
        // In fullscreen mode (e.g. player), remove the padding so content fills the screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isFullscreen) Modifier else Modifier.padding(start = RAIL_COLLAPSED_WIDTH)),
        ) {
            // Layer 1: full-bleed hero backdrop
            background()
            // Layer 2: scrollable content overlaid on top
            content()
        }

        // Rail renders on top (overlays content when expanded).
        Box(modifier = Modifier.zIndex(1f)) {
            leftRail()
        }
    }
}
