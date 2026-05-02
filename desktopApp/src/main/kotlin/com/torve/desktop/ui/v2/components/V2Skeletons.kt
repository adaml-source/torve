package com.torve.desktop.ui.v2.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/**
 * Pulsing placeholder for a poster card. Used while a shelf is fetching
 * its initial data - five of these in a row beats a blank stripe of
 * empty space.
 *
 * Animation: opacity oscillates between 0.4 and 0.8 over 900ms via an
 * infinite tween. Cheap, keyed on [contentColor], and respects the
 * surrounding theme.
 */
@Composable
fun V2PosterCardSkeleton(modifier: Modifier = Modifier) {
    val colors = TorveDesktopThemeTokens.colors
    val transition = rememberInfiniteTransition(label = "posterSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "posterSkeletonAlpha",
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.fieldSurface.copy(alpha = alpha))
                .height(150.dp * 1.5f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.fieldSurface.copy(alpha = alpha)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.fieldSurface.copy(alpha = alpha)),
        )
    }
}

/**
 * A row of [V2PosterCardSkeleton]s with a faded section header on top -
 * mimics what a real shelf will look like once data loads. Drop into a
 * page where you'd otherwise have a blank gap.
 */
@Composable
fun V2ShelfSkeleton(
    modifier: Modifier = Modifier,
    cardWidth: androidx.compose.ui.unit.Dp = 150.dp,
    cardCount: Int = 6,
) {
    val colors = TorveDesktopThemeTokens.colors
    val transition = rememberInfiniteTransition(label = "shelfSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shelfSkeletonAlpha",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.fieldSurface.copy(alpha = alpha)),
        )
        Row(
            modifier = Modifier.padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(cardCount) {
                V2PosterCardSkeleton(modifier = Modifier.width(cardWidth))
            }
        }
    }
}
