package com.torve.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.torve.android.R
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Badge1080p
import com.torve.android.ui.theme.Badge4K
import com.torve.android.ui.theme.Badge720p
import com.torve.android.ui.theme.BadgeDV
import com.torve.android.ui.theme.BadgeHDR
import com.torve.android.ui.theme.BadgeSD
import com.torve.android.ui.theme.CardGradient
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.CardOrientation
import com.torve.domain.model.CardStyle
import com.torve.domain.model.CardTitlePosition
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.hasAnyEnabledDisplayValue
import com.torve.domain.model.isOutside
import com.torve.domain.model.resolvedAspectRatio
import com.torve.domain.model.resolvedWidthDp
import com.torve.domain.model.WatchState
import com.torve.domain.model.withFallbackTmdbScore

val LocalCardStyle = compositionLocalOf { CardStyle() }

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

private data class CardLayoutSpec(
    val width: Dp,
    val aspectRatio: Float,
    val useBackdrop: Boolean,
)

private fun CardSize.toLayoutSpec(): CardLayoutSpec = CardLayoutSpec(
    width = width,
    aspectRatio = aspectRatio,
    useBackdrop = this == CardSize.LANDSCAPE || this == CardSize.WIDE,
)

private fun resolveCardLayoutSpec(sizeOverride: CardSize?, cardStyle: CardStyle): CardLayoutSpec {
    return if (sizeOverride != null) {
        sizeOverride.toLayoutSpec()
    } else {
        val width = cardStyle.size.resolvedWidthDp().dp
        val ratio = cardStyle.size.resolvedAspectRatio()
        val useBackdrop = cardStyle.size.orientation == CardOrientation.LANDSCAPE
        CardLayoutSpec(width = width, aspectRatio = ratio, useBackdrop = useBackdrop)
    }
}

private fun MediaItem.upcomingScheduleMetadata(): String? {
    if (!id.startsWith("trakt-calendar:")) return null
    return formatUpcomingScheduleDateTime(
        releaseDate ?: id.split(":", limit = 5).getOrNull(4),
    )
}

private fun formatUpcomingScheduleDateTime(value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        java.time.ZonedDateTime.parse(raw)
            .withZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", java.util.Locale.US))
    }.getOrElse {
        raw.take(16)
            .replace('T', ' ')
            .removeSuffix("Z")
            .takeIf { it.isNotBlank() }
    }
}

@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeOverride: CardSize? = null,
    showTitle: Boolean = true,
    showRating: Boolean = true,
    isDownloaded: Boolean = false,
    watchState: WatchState = WatchState(),
    cardStyle: CardStyle = LocalCardStyle.current,
) {
    val hoverPrefs = cardStyle.hover
    val appearance = cardStyle.appearance
    val cornerRadius = appearance.cornerRadiusDp.dp
    val ratingPrefs = LocalRatingPrefs.current
    val layoutSpec = resolveCardLayoutSpec(sizeOverride, cardStyle)
    val isLandscapeCard = layoutSpec.useBackdrop

    // Hover / Focus zoom
    var isFocused by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }
    val isActive = (isFocused || isPressed) && hoverPrefs.enabled

    val scale by animateFloatAsState(
        targetValue = if (isActive) hoverPrefs.scalePercent / 100f else 1f,
        animationSpec = tween(hoverPrefs.animationDurationMs),
        label = "card_scale",
    )

    Column(
        modifier = Modifier
            .width(layoutSpec.width)
            .then(modifier)
            .then(
                if (scale != 1f) Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                } else Modifier,
            )
            .zIndex(if (isActive) 10f else 0f)
            .onFocusChanged { isFocused = it.isFocused },
    ) {
        val showCardRatings = showRating &&
            (!isLandscapeCard || ratingPrefs.allowRatingsOnLandscapeCards) &&
            ratingPrefs.maxRatingsOnCard > 0
        val effectiveRatings = item.ratings.withFallbackTmdbScore(item.rating)
        // Let MultiRatingPills handle empty-pill gracefully; avoid extra gating
        val showInsideRatings = showCardRatings && effectiveRatings != null && !ratingPrefs.pillPosition.isOutside()
        val showOutsideRatings = showCardRatings && effectiveRatings != null && ratingPrefs.pillPosition.isOutside()
        // Poster image
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(layoutSpec.aspectRatio)
                .clip(RoundedCornerShape(cornerRadius))
                .then(
                    if (isActive && hoverPrefs.borderOnHover) {
                        Modifier.border(2.dp, Amber, RoundedCornerShape(cornerRadius))
                    } else if (appearance.showBorder) {
                        Modifier.border(1.dp, Steel, RoundedCornerShape(cornerRadius))
                    } else Modifier,
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { onClick() },
                    )
                },
        ) {
            // Content-policy hardening: locked/placeholder items get neutral art immediately.
            // This prevents any real poster/backdrop request and avoids brief leakage from
            // recycled rows, cache hits, or recomposition lag.
            val imageModel = if (item.isContentPlaceholder || item.isStubDetail) {
                null
            } else if (isLandscapeCard) {
                item.backdropUrl ?: item.posterUrl
            } else {
                item.posterUrl
            }

            SubcomposeAsyncImage(
                model = imageModel,
                contentDescription = if (item.isContentPlaceholder || item.isStubDetail) null else item.title,
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
                            text = if (item.isContentPlaceholder || item.isStubDetail) "" else item.title.take(2).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = Torve.colors.textTertiary,
                        )
                    }
                },
            )

            // Watched overlay
            val watchedPrefs = cardStyle.watched
            if (watchedPrefs.enabled && watchState.isStarted) {
                WatchedOverlay(
                    watchState = watchState,
                    prefs = watchedPrefs,
                    cornerRadiusDp = appearance.cornerRadiusDp,
                    modifier = Modifier.matchParentSize(),
                )
            }

            // Rating badge — inside overlay
            if (showInsideRatings && effectiveRatings != null) {
                MultiRatingPills(
                    ratings = effectiveRatings,
                    prefs = ratingPrefs,
                    modifier = Modifier
                        .testTag("poster_ratings_inside")
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
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
                        contentDescription = stringResource(R.string.downloaded_cd),
                        tint = Obsidian,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // Type badge (Movie / TV)
            if (appearance.showTypeBadge) {
                val typeLabel = when (item.type) {
                    MediaType.MOVIE -> "MOVIE"
                    MediaType.SERIES -> "TV"
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = if (isDownloaded) 32.dp else 6.dp, top = 6.dp)
                        .background(Obsidian.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(typeLabel, color = Snow, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Runtime badge
            if (appearance.showRuntime && item.runtime != null && item.runtime!! > 0) {
                val runtime = item.runtime!!
                val text = if (runtime >= 60) "${runtime / 60}h${runtime % 60}m" else "${runtime}m"
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Obsidian.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(text, color = Snow, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Genre tags
            if (appearance.showGenreTags && item.genres.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = if (appearance.showRuntime && item.runtime != null) 22.dp else 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    item.genres.take(2).forEach { genre ->
                        Text(
                            text = genre.name,
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 8.sp,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Overlay title (when title position is OVERLAY_BOTTOM)
            if (appearance.titlePosition == CardTitlePosition.OVERLAY_BOTTOM &&
                !isLandscapeCard
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(CardGradient))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Snow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // For landscape cards, show title overlaid at bottom
            if (isLandscapeCard) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(CardGradient),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Snow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.upcomingScheduleMetadata()?.let { metadata ->
                            Text(
                                text = metadata,
                                style = MaterialTheme.typography.bodySmall,
                                color = Snow.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // Ratings outside poster image
        if (showOutsideRatings && effectiveRatings != null) {
            Spacer(Modifier.height(6.dp))
            MultiRatingPills(
                ratings = effectiveRatings,
                prefs = ratingPrefs,
                modifier = Modifier
                    .testTag("poster_ratings_outside")
                    .fillMaxWidth(),
            )
        }

        // Title below poster (not inside card) — only when BELOW
        val showBelowTitle = showTitle &&
            !isLandscapeCard &&
            appearance.titlePosition == CardTitlePosition.BELOW
        if (showBelowTitle) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = Torve.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            if (appearance.showYear) {
                val metadata = item.upcomingScheduleMetadata() ?: item.year?.toString().orEmpty()
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = Torve.colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
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
    sizeOverride: CardSize? = null,
    cardStyle: CardStyle = LocalCardStyle.current,
) {
    val layoutSpec = resolveCardLayoutSpec(sizeOverride, cardStyle)
    Column(modifier = modifier.width(layoutSpec.width)) {
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(layoutSpec.aspectRatio)
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
                    color = Torve.colors.textTertiary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = Torve.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        character?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Torve.colors.textTertiary,
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
    showCustomize: Boolean = false,
    onCustomizeClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Torve.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (showCustomize && onCustomizeClick != null) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = stringResource(R.string.common_customize_cd),
                tint = Ash,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onCustomizeClick)
                    .padding(end = 0.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        if (action != null && onActionClick != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = Torve.colors.accent,
                modifier = Modifier.clickable(onClick = onActionClick),
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Back Button — Circular translucent navigation button
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Peek Password Transformation — shows last-typed character
// for 1 second before masking. Use for all sensitive fields
// that are in hidden mode to give standard password-entry UX.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * A [VisualTransformation] that briefly reveals the most recently typed
 * character (1 second) then masks it with a bullet (•). This is the standard
 * mobile password-entry experience. Pass the current field value so the
 * composable can detect new characters.
 */
@Composable
fun rememberPeekPasswordTransformation(currentValue: String): VisualTransformation {
    var revealIndex by remember { mutableStateOf(-1) }
    val prevLength = remember { intArrayOf(0) }

    LaunchedEffect(currentValue) {
        if (currentValue.length > prevLength[0] && currentValue.isNotEmpty()) {
            revealIndex = currentValue.lastIndex
            prevLength[0] = currentValue.length
            delay(1.seconds)
            revealIndex = -1
        } else {
            prevLength[0] = currentValue.length
            revealIndex = -1
        }
    }

    val idx = revealIndex
    return remember(idx) {
        VisualTransformation { text ->
            val masked = buildString {
                text.forEachIndexed { i, c ->
                    append(if (i == idx) c else '\u2022')
                }
            }
            TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun FloatingBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Back",
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val active = focused || hovered
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.98f
            active -> 1.035f
            else -> 1f
        },
        label = "floatingBackScale",
    )
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (active) 0.70f else 0.54f,
        label = "floatingBackBackgroundAlpha",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> Amber.copy(alpha = 0.72f)
            hovered -> Amber.copy(alpha = 0.48f)
            else -> Color.White.copy(alpha = 0.14f)
        },
        label = "floatingBackBorder",
    )
    val glowColor = if (active) Amber.copy(alpha = 0.26f) else Color.Black.copy(alpha = 0.40f)
    val shape = RoundedCornerShape(999.dp)

    Box(
        modifier = modifier
            .then(if (compact) Modifier.size(48.dp) else Modifier.height(48.dp).widthIn(min = 104.dp))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (active) 22.dp else 12.dp,
                shape = shape,
                ambientColor = glowColor,
                spotColor = glowColor,
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (active) 0.13f else 0.08f),
                        Color(0xFF0B1220).copy(alpha = backgroundAlpha),
                        Color(0xFF030711).copy(alpha = (backgroundAlpha + 0.08f).coerceAtMost(0.78f)),
                    ),
                ),
            )
            .border(1.dp, borderColor, shape)
            .hoverable(interactionSource)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onBack,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .padding(horizontal = if (compact) 14.dp else 18.dp)
                .background(Color.White.copy(alpha = if (active) 0.26f else 0.16f)),
        )
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 0.dp else 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(20.dp),
            )
            if (!compact) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.92f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Preview(name = "FloatingBackButtonDefaultPreview", backgroundColor = 0xFF05070D, showBackground = true)
@Composable
private fun FloatingBackButtonDefaultPreview() {
    FloatingBackButton(onBack = {}, label = "Back", modifier = Modifier.padding(16.dp))
}

@Preview(name = "FloatingBackButtonFocusedPreview", backgroundColor = 0xFF101827, showBackground = true)
@Composable
private fun FloatingBackButtonFocusedPreview() {
    FloatingBackButton(onBack = {}, label = "Back", modifier = Modifier.padding(16.dp))
}

@Preview(name = "FloatingBackButtonCompactPreview", backgroundColor = 0xFF05070D, showBackground = true)
@Composable
private fun FloatingBackButtonCompactPreview() {
    FloatingBackButton(onBack = {}, compact = true, label = "Back", modifier = Modifier.padding(16.dp))
}

@Preview(name = "BrightBackdropFloatingBackPreview", backgroundColor = 0xFFE6EEF7, showBackground = true)
@Composable
private fun BrightBackdropFloatingBackPreview() {
    FloatingBackButton(onBack = {}, label = "Back", modifier = Modifier.padding(16.dp))
}

@Preview(name = "ScrolledFloatingBackPreview", backgroundColor = 0xFF05070D, showBackground = true)
@Composable
private fun ScrolledFloatingBackPreview() {
    FloatingBackButton(onBack = {}, compact = true, label = "Back", modifier = Modifier.padding(16.dp))
}

@Preview(name = "MovieDetailsWithFloatingBackPreview", backgroundColor = 0xFF05070D, showBackground = true)
@Composable
private fun MovieDetailsWithFloatingBackPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF25344B), Color(0xFF05070D)),
                ),
            )
            .padding(16.dp),
    ) {
        FloatingBackButton(onBack = {}, label = "Back", modifier = Modifier.align(Alignment.TopStart))
        Text(
            text = "Movie Title",
            color = Snow,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@Preview(name = "TvShowDetailsWithFloatingBackPreview", backgroundColor = 0xFF05070D, showBackground = true)
@Composable
private fun TvShowDetailsWithFloatingBackPreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF382B22), Color(0xFF05070D)),
                ),
            )
            .padding(16.dp),
    ) {
        FloatingBackButton(onBack = {}, label = "Back", modifier = Modifier.align(Alignment.TopStart))
        Text(
            text = "TV Show Title",
            color = Snow,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Gunmetal.copy(alpha = 0.65f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.common_back_cd),
            tint = Snow,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Live Indicator Dot — Pulsing red dot for live channels
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
