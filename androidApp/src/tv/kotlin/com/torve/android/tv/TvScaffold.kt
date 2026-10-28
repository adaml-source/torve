package com.torve.android.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.torve.android.ui.theme.Obsidian
import com.torve.android.tv.settings.rememberTvReduceMotionPreference

/** Fixed icon strip; focused labels sit below their icon. */
internal val TV_NAV_RAIL_WIDTH = 52.dp
internal val TV_CONTENT_START_INSET = TV_NAV_RAIL_WIDTH + 2.dp
// Reserve the complete collapsed navigation width plus focus-outline room.
// Top-level TV pages add TV_PAGE_CONTENT_GUTTER exactly once. The edge
// gutters below define the normal TV page bounds; avoid
// custom first-layer 0/12/22/24/32/34/40/48.dp edge values unless a page is
// intentionally special, or the value is inner card/chip/focus spacing.
internal val TV_PAGE_CONTENT_GUTTER = 28.dp
internal val TV_PAGE_TOP_GUTTER = 22.dp
internal val TV_PAGE_END_GUTTER = 28.dp
internal val TV_PAGE_BOTTOM_GUTTER = 24.dp
internal val TV_ROW_END_GUTTER = 24.dp
// Use only for nested filter/chip rows that already sit inside a root
// container padded by TV_PAGE_END_GUTTER. TV_ROW_END_GUTTER is for rows that
// own their edge protection, such as poster rails.
internal val TV_FILTER_ROW_NESTED_END_GUTTER = 4.dp

/** Width of the left contextual info panel. */
internal val INFO_PANEL_WIDTH = 280.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvScaffold(
    leftRail: @Composable () -> Unit,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
    isFullscreen: Boolean = false,
    showRail: Boolean = !isFullscreen,
    infoPanel: (@Composable () -> Unit)? = null,
    showInfoPanel: Boolean = false,
    contentFocusEnabled: Boolean = true,
    pageBackground: @Composable () -> Unit = {},
) {
    val allowContentFocus by rememberUpdatedState(contentFocusEnabled)
    // Opening navigation must not resize the rails or move the focused poster.
    val contentInset = if (isFullscreen) 0.dp else TV_CONTENT_START_INSET
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (com.torve.android.ui.community.LocalPersonalAppearance.current.active) androidx.compose.ui.graphics.Color.Transparent else Obsidian),
    ) {
        // Full-bleed hero backdrop sits behind content and the collapsed rail.
        background()

        if (!isFullscreen && !com.torve.android.ui.community.LocalPersonalAppearance.current.active) Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(940.dp)
                .background(
                    Brush.horizontalGradient(
                        0f to Obsidian.copy(alpha = 0.88f),
                        0.62f to Obsidian.copy(alpha = 0.66f),
                        1f to Obsidian.copy(alpha = 0f),
                    ),
                ),
        )

        // A page-owned backdrop covers the whole viewport, including beneath
        // the transparent menu. Only foreground controls receive the inset.
        pageBackground()

        // Keep browse geometry stable; detail and playback remain full-screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = contentInset)
                // Page previews can compose their own entry/restore effects.
                // Keep those requests inside the page until Right/OK grants entry.
                .focusProperties {
                    enter = { if (allowContentFocus) FocusRequester.Default else FocusRequester.Cancel }
                }
                .focusGroup(),
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

        // Navigation stays inside its fixed strip while content keeps its width.
        Box(modifier = Modifier.zIndex(1f)) {
            leftRail()
        }
    }
}
