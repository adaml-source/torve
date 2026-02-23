package com.streamvault.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Badge1080p
import com.streamvault.android.ui.theme.Badge4K
import com.streamvault.android.ui.theme.Badge720p
import com.streamvault.android.ui.theme.BadgeDV
import com.streamvault.android.ui.theme.BadgeHDR
import com.streamvault.android.ui.theme.BadgeSD
import com.streamvault.android.ui.theme.CardGradient
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Graphite
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.MediaItem

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Poster Card — The primary content card used everywhere.
// Clean poster image with minimal overlay. Title below card,
// not inside — avoids obscuring artwork.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

enum class CardSize(val width: Dp, val aspectRatio: Float) {
    SMALL(120.dp, 2f / 3f),
    MEDIUM(140.dp, 2f / 3f),
    LARGE(160.dp, 2f / 3f),
    LANDSCAPE(240.dp, 16f / 9f),
    WIDE(280.dp, 16f / 9f),
}

@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: CardSize = CardSize.MEDIUM,
    showTitle: Boolean = true,
    showRating: Boolean = true,
    isDownloaded: Boolean = false,
) {
    Column(
        modifier = modifier.width(size.width),
    ) {
        // Poster image
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(size.aspectRatio)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        ) {
            SubcomposeAsyncImage(
                model = when (size) {
                    CardSize.LANDSCAPE, CardSize.WIDE -> item.backdropUrl ?: item.posterUrl
                    else -> item.posterUrl
                },
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = { ShimmerBox(modifier = Modifier.fillMaxSize()) },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Graphite),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.title.take(2).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = StreamVault.colors.textTertiary,
                        )
                    }
                },
            )

            // Rating badge — top right, only on poster cards
            if (showRating && size != CardSize.LANDSCAPE && size != CardSize.WIDE) {
                item.rating?.let { rating ->
                    if (rating > 0) {
                        RatingPill(
                            rating = rating,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp),
                        )
                    }
                }
            }

            // Downloaded badge — top left
            if (isDownloaded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Amber),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.DownloadDone,
                        contentDescription = "Downloaded",
                        tint = Obsidian,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // For landscape cards, show title overlaid at bottom
            if (size == CardSize.LANDSCAPE || size == CardSize.WIDE) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(CardGradient),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Snow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Title below poster (not inside card)
        if (showTitle && size != CardSize.LANDSCAPE && size != CardSize.WIDE) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = StreamVault.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            val year = item.year?.toString() ?: ""
            if (year.isNotBlank()) {
                Text(
                    text = year,
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamVault.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Rating Pill — Compact rating display
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun RatingPill(
    rating: Double,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = Obsidian.copy(alpha = 0.75f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                Icons.Rounded.Star,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = Amber,
            )
            Text(
                text = "%.1f".format(rating),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = Snow,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Quality Badge — Used in stream picker and detail
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun QualityBadge(
    quality: String,
    modifier: Modifier = Modifier,
) {
    val color = when (quality.uppercase()) {
        "4K", "2160P" -> Badge4K
        "1080P" -> Badge1080p
        "720P" -> Badge720p
        "HDR", "HDR10", "HDR10+" -> BadgeHDR
        "DV", "DOLBY VISION" -> BadgeDV
        else -> BadgeSD
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = quality.uppercase(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Shimmer Loading — Skeleton placeholder while loading
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_translate",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            Graphite,
            Graphite.copy(alpha = 0.5f),
            Graphite,
        ),
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f),
    )

    Box(modifier = modifier.background(shimmerBrush))
}

@Composable
fun ShimmerPosterCard(
    modifier: Modifier = Modifier,
    size: CardSize = CardSize.MEDIUM,
) {
    Column(modifier = modifier.width(size.width)) {
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(size.aspectRatio)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(6.dp))
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.height(4.dp))
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(10.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Cast Avatar — Circular profile image for cast shelves
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun CastAvatar(
    name: String,
    character: String?,
    profileUrl: String?,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(72.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (profileUrl != null) {
            AsyncImage(
                model = profileUrl,
                contentDescription = name,
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Gunmetal),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.split(" ").map { it.firstOrNull() ?: "" }.take(2).joinToString(""),
                    style = MaterialTheme.typography.titleSmall,
                    color = StreamVault.colors.textTertiary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = StreamVault.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        character?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = StreamVault.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Section Header — Consistent shelf title style
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = StreamVault.colors.textPrimary,
        )
        if (action != null && onActionClick != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = StreamVault.colors.accent,
                modifier = Modifier.clickable(onClick = onActionClick),
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Live Indicator Dot — Pulsing red dot for IPTV/live
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "live_pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live_alpha",
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Color.Red.copy(alpha = alpha)),
    )
}
