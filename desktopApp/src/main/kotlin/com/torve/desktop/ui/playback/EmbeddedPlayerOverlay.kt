package com.torve.desktop.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Embedded player layout - video fills the entire area.
 * All controls are handled by the VlcPlayerChromeHost overlay in the Swing layer.
 */
@Composable
fun EmbeddedPlayerLayout(
    videoContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        videoContent(Modifier.fillMaxSize())
    }
}
