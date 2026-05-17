package com.torve.android.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.MediaItem

private const val TV_BACKDROP_WATCHED_THRESHOLD = 0.9f

@Composable
fun TvBackdropCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    progress: Float? = null,
    showTmdbRating: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.06f else 1f, label = "backdropScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "backdropBorder",
    )

    LaunchedEffect(focused, item) {
        if (focused) onFocused()
    }
    val isWatched = (progress ?: 0f) >= TV_BACKDROP_WATCHED_THRESHOLD

    Box(
        modifier = modifier
            .width(320.dp)
            .aspectRatio(16f / 9f)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        val imageUrl = item.backdropUrl ?: item.posterUrl
        if (imageUrl.isNullOrBlank()) {
            // Fallback: dark card with title centered when no image is available
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A2E)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Obsidian.copy(alpha = 0.9f),
                        ),
                    ),
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Snow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                val subtitle = buildString {
                    item.year?.let { append(it) }
                    if (focused && showTmdbRating && item.rating != null) {
                        if (isNotBlank()) append("  ")
                        append(String.format("%.1f", item.rating))
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = Silver,
                    )
                }
            }
        }

        if (isWatched) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Obsidian.copy(alpha = 0.82f))
                    .border(1.dp, Amber.copy(alpha = 0.58f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.tv_watched),
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberLight,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (progress != null && progress > 0f && !isWatched) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Snow.copy(alpha = 0.27f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Amber),
                )
            }
        }
    }
}
