package com.torve.android.tv.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.torve.android.R
import com.torve.android.ui.components.ratingSourceIconRes
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.CastMember
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.RatingSource
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Left-side contextual info panel for TV browsing.
 * Displays title, metadata, ratings, and synopsis for the currently focused item.
 * Updates via AnimatedContent when the focused item changes.
 */
@Composable
fun TvFocusDetailsPanel(
    focusedItem: MediaItem?,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    logoLookupInFlight: Boolean = false,
    logoLookupResolved: Boolean = false,
) {
    val metadataRepo: MetadataRepository = koinInject()
    var lastResolvedLogoUrl by remember { mutableStateOf<String?>(null) }
    val castCache = remember { mutableStateMapOf<String, List<CastMember>>() }
    var resolvedCast by remember { mutableStateOf<List<CastMember>>(emptyList()) }

    LaunchedEffect(focusedItem?.logoUrl) {
        val resolved = focusedItem?.logoUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        lastResolvedLogoUrl = resolved
    }

    LaunchedEffect(focusedItem?.let { "${it.type}:${it.tmdbId ?: it.id}" }) {
        val item = focusedItem
        if (item == null) {
            resolvedCast = emptyList()
            return@LaunchedEffect
        }

        val itemKey = "${item.type}:${item.tmdbId ?: item.id}"
        val immediateCast = item.cast.take(8)
        if (immediateCast.isNotEmpty()) {
            castCache[itemKey] = immediateCast
            resolvedCast = immediateCast
            return@LaunchedEffect
        }

        castCache[itemKey]?.let { cached ->
            resolvedCast = cached
            return@LaunchedEffect
        }

        val tmdbId = item.tmdbId
        if (tmdbId == null) {
            castCache[itemKey] = emptyList()
            resolvedCast = emptyList()
            return@LaunchedEffect
        }

        val mediaType = if (item.type == MediaType.MOVIE) "movie" else "tv"
        val fetchedCast = withContext(Dispatchers.IO) {
            runCatching { metadataRepo.getDetail(mediaType, tmdbId).cast.take(8) }
                .getOrDefault(emptyList())
        }
        castCache[itemKey] = fetchedCast
        if (focusedItem?.let { "${it.type}:${it.tmdbId ?: it.id}" } == itemKey) {
            resolvedCast = fetchedCast
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Obsidian.copy(alpha = 0.95f),
                        Obsidian.copy(alpha = 0.85f),
                        Obsidian.copy(alpha = 0f),
                    ),
                ),
            ),
    ) {
        AnimatedContent(
            targetState = focusedItem,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(150))
            },
            contentKey = { it?.let { "${it.type}:${it.tmdbId ?: it.id}" } },
            label = "focusPanel",
        ) { item ->
            if (item != null) {
                FocusPanelContent(
                    item = item,
                    progress = progress,
                    logoLookupInFlight = logoLookupInFlight,
                    logoLookupResolved = logoLookupResolved,
                    retainedLogoUrl = lastResolvedLogoUrl,
                    cast = resolvedCast,
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun FocusPanelContent(
    item: MediaItem,
    progress: Float?,
    logoLookupInFlight: Boolean,
    logoLookupResolved: Boolean,
    retainedLogoUrl: String?,
    cast: List<CastMember>,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 16.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        // 1. Clearlogo / title artwork — or fallback text title
        ClearlogoOrTitle(
            item = item,
            logoLookupInFlight = logoLookupInFlight,
            logoLookupResolved = logoLookupResolved,
            retainedLogoUrl = retainedLogoUrl,
        )

        Spacer(Modifier.height(10.dp))

        // 2. Metadata line — Kodi-style: year • genre • runtime/seasons • status
        MetadataBlock(item)

        // 3. Progress indicator (if applicable)
        if (progress != null && progress > 0f) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Steel),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Amber, RoundedCornerShape(2.dp)),
                    )
                }
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Silver,
                )
            }
        }

        // 4. Ratings row
        val ratings = item.ratings
        if (ratings != null && hasAnyRating(ratings)) {
            Spacer(Modifier.height(14.dp))
            RatingsRow(ratings)
        } else if (item.rating != null && item.rating!! > 0) {
            Spacer(Modifier.height(14.dp))
            FallbackRatingRow(item.rating!!)
        }

        // 5. Synopsis — TV-readable size, expanded with poster removed
        if (!item.overview.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = item.overview!!,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                ),
                color = Snow.copy(alpha = 0.8f),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (cast.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            CastStrip(cast = cast)
        }
    }
}

/**
 * Renders the TMDB clearlogo (transparent title artwork) when available,
 * falling back to a bold text title when no logo asset exists or if loading fails.
 * Fixed-height container prevents layout jumping between items.
 */
@Composable
private fun ClearlogoOrTitle(
    item: MediaItem,
    logoLookupInFlight: Boolean,
    logoLookupResolved: Boolean,
    retainedLogoUrl: String?,
) {
    // Fixed height so the panel does not jump between logo and text titles
    val logoHeight = 56.dp
    val currentLogoUrl = item.logoUrl?.takeIf { it.isNotBlank() }
    var currentLogoFailed by remember(currentLogoUrl) { mutableStateOf(false) }
    var retainedLogoFailed by remember(retainedLogoUrl) { mutableStateOf(false) }

    val displayLogoUrl = when {
        currentLogoUrl != null && !currentLogoFailed -> currentLogoUrl
        logoLookupInFlight && !retainedLogoUrl.isNullOrBlank() && !retainedLogoFailed -> retainedLogoUrl
        else -> null
    }

    if (displayLogoUrl != null) {
        AsyncImage(
            model = displayLogoUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) {
                    if (displayLogoUrl == currentLogoUrl) {
                        currentLogoFailed = true
                    } else if (displayLogoUrl == retainedLogoUrl) {
                        retainedLogoFailed = true
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(logoHeight),
        )
        return
    }

    val showFallbackText = currentLogoFailed || retainedLogoFailed || logoLookupResolved || !logoLookupInFlight
    if (showFallbackText) {
        FallbackTextTitle(item.title, logoHeight)
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(logoHeight),
        )
    }
}

@Composable
private fun FallbackTextTitle(title: String, minHeight: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(minHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                lineHeight = 28.sp,
            ),
            color = Snow,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Rich metadata block: renders 1-2 lines of Kodi-style contextual info.
 * Line 1: year • genre(s) • runtime or season count
 * Line 2 (series only): status if available
 */
@Composable
private fun MetadataBlock(item: MediaItem) {
    val primaryParts = buildList {
        // Year
        item.year?.let { add(it.toString()) }

        // Genres (up to 2, concise)
        if (item.genres.isNotEmpty()) {
            add(item.genres.take(2).joinToString(" / ") { it.name })
        }

        // Runtime or season count
        when (item.type) {
            MediaType.MOVIE -> {
                item.runtime?.let { rt ->
                    val h = rt / 60
                    val m = rt % 60
                    add(if (h > 0) "${h}h ${m}m" else "${m}m")
                }
            }
            MediaType.SERIES -> {
                val seasonCount = item.seasons.size
                if (seasonCount > 0) {
                    add("$seasonCount Season${if (seasonCount != 1) "s" else ""}")
                }
            }
        }
    }

    if (primaryParts.isNotEmpty()) {
        Text(
            text = primaryParts.joinToString("  \u2022  "),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            color = Silver,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // Status line for series (Ended, Returning Series, etc.)
    if (item.type == MediaType.SERIES) {
        val statusText = item.status?.takeIf { it.isNotBlank() }
        if (statusText != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = Amber.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun CastStrip(cast: List<CastMember>) {
    val castRows = cast.take(6).chunked(3)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Cast",
            style = MaterialTheme.typography.labelLarge,
            color = Silver,
            fontWeight = FontWeight.SemiBold,
        )
        castRows.forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { castMember ->
                    CastChip(castMember)
                }
            }
        }
    }
}

@Composable
private fun CastChip(castMember: CastMember) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val profileUrl = castMember.profileUrl
        if (!profileUrl.isNullOrBlank()) {
            AsyncImage(
                model = profileUrl,
                contentDescription = castMember.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Steel.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = castMember.name.take(1),
                    style = MaterialTheme.typography.labelMedium,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = castMember.name,
            style = MaterialTheme.typography.labelSmall,
            color = Snow.copy(alpha = 0.88f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
        )
    }
}

private fun hasAnyRating(r: MediaRatings): Boolean {
    return r.imdbScore != null || r.tmdbScore != null ||
        r.rottenTomatoesScore != null || r.rtAudienceScore != null ||
        r.metacriticScore != null
}

@Composable
private fun RatingsRow(ratings: MediaRatings) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ratings.imdbScore?.let { score ->
            RatingBadge(
                iconRes = ratingSourceIconRes(RatingSource.IMDB, ratings),
                value = "%.1f".format(score),
            )
        }
        ratings.rottenTomatoesScore?.let { score ->
            RatingBadge(
                iconRes = ratingSourceIconRes(RatingSource.ROTTEN_TOMATOES, ratings),
                value = "${score}%",
            )
        }
        ratings.rtAudienceScore?.let { score ->
            RatingBadge(
                iconRes = ratingSourceIconRes(RatingSource.RT_AUDIENCE, ratings),
                value = "${score}%",
            )
        }
        ratings.tmdbScore?.let { score ->
            RatingBadge(
                iconRes = ratingSourceIconRes(RatingSource.TMDB, ratings),
                value = "%.1f".format(score),
            )
        }
    }
}

@Composable
private fun RatingBadge(iconRes: Int?, value: String) {
    val scale = 1.2f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(Charcoal.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp * scale),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = MaterialTheme.typography.labelMedium.fontSize * scale,
            ),
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FallbackRatingRow(tmdbRating: Double) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RatingBadge(
            iconRes = R.drawable.tmbd_logo,
            value = "%.1f".format(tmdbRating),
        )
    }
}
