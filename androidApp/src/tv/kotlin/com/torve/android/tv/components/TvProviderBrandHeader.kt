package com.torve.android.tv.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torve.android.ui.home.StreamingProviderBrandArtwork
import com.torve.android.ui.home.StreamingService

/**
 * Shared, start-anchored provider wordmark used by provider Browse and Search.
 *
 * The image is always fitted inside responsive maximum bounds. Start alignment
 * is intentional: many provider files contain transparent canvas around the
 * visible wordmark, and centering that canvas makes the brand appear detached
 * from the content grid.
 */
@Composable
fun TvProviderBrandHeader(
    service: StreamingService,
    modifier: Modifier = Modifier,
    maxArtworkWidth: Dp = 288.dp,
) {
    BoxWithConstraints(modifier = modifier) {
        val artworkWidth = maxWidth.coerceAtMost(maxArtworkWidth)
        Box(
            modifier = Modifier
                .width(artworkWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            StreamingProviderBrandArtwork(
                service = service,
                transparentBackground = true,
                forceFitArtwork = true,
                artworkAlignment = Alignment.CenterStart,
                fallbackHorizontalPadding = 0.dp,
                trimTransparentPadding = true,
                highResolutionBranding = true,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    }
}
