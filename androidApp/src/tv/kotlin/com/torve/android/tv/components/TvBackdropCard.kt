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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.components.PreferredRatingPills
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.MediaItem
import com.torve.domain.model.withFallbackTmdbScore

private const val TV_BACKDROP_WATCHED_THRESHOLD = 0.9f

private fun MediaItem.upcomingScheduleMetadata(): String? {
    if (!id.startsWith("trakt-calendar:")) return null
    val raw = releaseDate?.trim()?.takeIf { it.isNotEmpty() }
        ?: id.split(":", limit = 5).getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    return runCatching {
        java.time.ZonedDateTime.parse(raw)
            .withZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", java.util.Locale.US))
    }.getOrElse {
        raw.take(16).replace('T', ' ').removeSuffix("Z").takeIf { it.isNotBlank() }
    }
}

@Composable
fun TvBackdropCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    width: Dp = 292.dp,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    progress: Float? = null,
    showTitles: Boolean = true,
    showTmdbRating: Boolean = true,
    ratingPrefs: RatingDisplayPrefs,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.05f else 1f, label = "backdropScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "backdropBorder",
    )

    val isWatched = (progress ?: 0f) >= TV_BACKDROP_WATCHED_THRESHOLD

    Column(
        modifier = modifier
            .width(width)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.spacedBy(if (showTitles) 7.dp else 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Amber.copy(alpha = if (focused) 0.30f else 0f),
                            Amber.copy(alpha = if (focused) 0.13f else 0f),
                            Color.Transparent,
                        ),
                        radius = 520f,
                    ),
                    RoundedCornerShape(16.dp),
                )
                .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp)),
        ) {
            val imageUrl = item.backdropUrl ?: item.posterUrl
            if (imageUrl.isNullOrBlank()) {
                // Fallback: dark card with title centered when no image is available.
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

            if (isWatched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Obsidian.copy(alpha = 0.82f))
                        .border(1.dp, Amber.copy(alpha = 0.58f), RoundedCornerShape(999.dp))
                        .padding(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.tv_watched),
                        tint = AmberLight,
                        modifier = Modifier.width(18.dp).height(18.dp),
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

        if (showTitles) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Snow,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                val subtitle = buildString {
                    val scheduleMetadata = item.upcomingScheduleMetadata()
                    if (scheduleMetadata != null) {
                        append(scheduleMetadata)
                    } else {
                        item.year?.let { append(it) }
                    }
                    if (focused && showTmdbRating && item.rating != null) {
                        if (isNotBlank()) append("  ")
                        append(String.format("%.1f", item.rating))
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Silver,
                    )
                }
                item.ratings.withFallbackTmdbScore(item.rating)?.let { ratings ->
                    PreferredRatingPills(
                        ratings = ratings,
                        prefs = ratingPrefs.tvExternalCardRatingPrefs(),
                    )
                }
            }
        }
    }
}
