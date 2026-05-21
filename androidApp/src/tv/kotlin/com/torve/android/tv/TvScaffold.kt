package com.torve.android.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.torve.android.ui.theme.Obsidian

/** Collapsed rail width — content always starts here, never shifts. */
internal val RAIL_COLLAPSED_WIDTH = 52.dp

/** Width of the left contextual info panel. */
internal val INFO_PANEL_WIDTH = 280.dp

@Composable
fun TvScaffold(
    leftRail: @Composable () -> Unit,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
    isFullscreen: Boolean = false,
    infoPanel: (@Composable () -> Unit)? = null,
    showInfoPanel: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian),
    ) {
        // Full-bleed hero backdrop sits behind content and the collapsed rail.
        background()

        // Content area starts after the collapsed rail width.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isFullscreen) Modifier else Modifier.padding(start = RAIL_COLLAPSED_WIDTH)),
        ) {
            // Info panel + scrollable content side by side.
            Row(modifier = Modifier.fillMaxSize()) {
                // Left info panel — slides in/out
                if (infoPanel != null) {
                    AnimatedVisibility(
                        visible = showInfoPanel,
                        enter = fadeIn(tween(250)) + slideInHorizontally(
                            initialOffsetX = { -it / 3 },
                            animationSpec = tween(300),
                        ),
                        exit = fadeOut(tween(200)) + slideOutHorizontally(
                            targetOffsetX = { -it / 3 },
                            animationSpec = tween(250),
                        ),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(INFO_PANEL_WIDTH)
                                .fillMaxHeight(),
                        ) {
                            infoPanel()
                        }
                    }
                }

                // Right content area takes remaining space.
                // clipToBounds prevents LazyRow items from rendering
                // partially under the info panel — only needed when the panel is visible.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (showInfoPanel) Modifier.clipToBounds() else Modifier),
                ) {
                    content()
                }
            }
        }

        // Rail renders on top (overlays content when expanded).
        Box(modifier = Modifier.zIndex(1f)) {
            leftRail()
        }
    }
}
