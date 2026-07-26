package com.torve.android.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.torve.android.ui.theme.Obsidian
import com.torve.domain.model.MediaItem

/**
 * Full-bleed hero background layer. Renders a backdrop image that fills the
 * entire content area behind the scrollable rails. Text and buttons are NOT
 * here — they live in TvHeroOverlay inside the scrollable LazyColumn.
 *
 * Uses the same HeroGradient from mobile's HeroSlide for visual consistency.
 */
@Composable
fun TvHeroBackground(
    featuredItem: MediaItem?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val imageUrl = featuredItem?.backdropUrl ?: featuredItem?.posterUrl
        val scale = ContentScale.Crop

        if (imageUrl != null) {
            // The root already waits for content readiness and coalesces rapid
            // route changes before attaching this image. Avoid a Crossfade here:
            // it keeps two full-screen bitmaps composed during the most
            // performance-sensitive TV transition window.
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = scale,
            )
        }

        // Cinematic gradient — same as mobile HeroGradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.12f),
                            Obsidian.copy(alpha = 0.20f),
                            Obsidian.copy(alpha = 0.36f),
                            Obsidian.copy(alpha = 0.58f),
                            Obsidian.copy(alpha = 0.80f),
                            Obsidian.copy(alpha = 0.94f),
                            Obsidian,
                        ),
                    ),
                ),
        )

        // Left-side readability gradient for text overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.84f),
                            Obsidian.copy(alpha = 0.54f),
                            Obsidian.copy(alpha = 0.16f),
                            Obsidian.copy(alpha = 0f),
                        ),
                        endX = 900f,
                    ),
                ),
        )
    }
}
