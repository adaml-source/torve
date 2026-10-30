package com.torve.android.tv.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.torve.android.tv.TV_PAGE_END_GUTTER
import com.torve.android.ui.components.PreferredRatingPills
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.MediaItem
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

/**
 * Cinematic TV hero overlay.
 *
 * The poster grid remains the action surface. This overlay intentionally has no
 * Play/Trailer/Watchlist buttons because those controls were hard to reach from
 * the rail and created dead focus targets.
 */
@Composable
fun TvHeroOverlay(
    featuredItem: MediaItem?,
    sectionTitle: String,
    subtitle: String,
    primaryActionFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    onDetailsFeatured: () -> Unit,
    onTrailerFeatured: () -> Unit,
    isInWatchlist: Boolean = false,
    onWatchlistToggle: () -> Unit = {},
    logoLookupInFlight: Boolean = false,
    allowLogoArtwork: Boolean = true,
    topPadding: Dp = 56.dp,
    overviewMaxLines: Int = 3,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val settingsViewModel: SettingsViewModel = koinInject()
    val settingsState by settingsViewModel.state.collectAsState()
    val ratingPrefs = remember(settingsState.ratingPrefs) {
        settingsState.ratingPrefs.tvExternalCardRatingPrefs()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(topPadding))

        AnimatedContent(
            targetState = featuredItem,
            transitionSpec = {
                // Keep the title change legible without retaining two complete
                // hero trees for hundreds of milliseconds on Fire TV.
                (fadeIn(tween(90)) togetherWith fadeOut(tween(60))).using(null)
            },
            contentKey = { it?.id },
            label = "heroOverlay",
        ) { item ->
            Column(
                modifier = Modifier
                    .padding(start = 56.dp, end = TV_PAGE_END_GUTTER, bottom = if (compact) 8.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            ) {
                val logoUrl = item?.logoUrl?.takeIf { it.isNotBlank() }
                var logoFailed by remember(logoUrl) { mutableStateOf(false) }
                val artworkPending = !logoFailed && (!allowLogoArtwork || logoLookupInFlight)
                Box(Modifier.fillMaxWidth().height(if (compact) 48.dp else 72.dp).testTag("tv.hero.title"), contentAlignment = Alignment.CenterStart) {
                if (allowLogoArtwork && !logoUrl.isNullOrBlank() && !logoFailed) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        onState = { state ->
                            if (state is coil3.compose.AsyncImagePainter.State.Error) {
                                logoFailed = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.42f)
                            .heightIn(min = 24.dp, max = if (compact) 40.dp else 54.dp),
                    )
                } else if (!artworkPending) {
                    val titleText = item?.title ?: subtitle
                    if (titleText.isNotBlank()) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = if (compact) 24.sp else 30.sp,
                                lineHeight = if (compact) 28.sp else 34.sp,
                                letterSpacing = 0.6.sp,
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.82f),
                                    offset = Offset(0f, 3f),
                                    blurRadius = 18f,
                                ),
                            ),
                            color = Snow,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (compact) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.6f),
                        )
                    } else {
                        Spacer(modifier = Modifier.height(44.dp))
                    }
                }

                }

                Box(if (compact) Modifier.heightIn(min = 24.dp) else Modifier.height(24.dp)) {
                if (item != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item.year?.let {
                            Text(
                                text = it.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Snow.copy(alpha = 0.72f),
                            )
                        }
                        item.ratings.withFallbackTmdbScore(item.rating)?.let { ratings ->
                            PreferredRatingPills(
                                ratings = ratings,
                                prefs = ratingPrefs,
                            )
                        }
                        item.runtime?.takeIf { it > 0 }?.let { runtime ->
                            Text(
                                text = "${runtime}m",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Snow.copy(alpha = 0.72f),
                            )
                        }
                        item.voteCount?.takeIf { it > 0 }?.let { votes ->
                            Text(
                                text = com.torve.android.tv.components.tvSettingsLabel(if (votes >= 1000) "${votes / 1000}K votes" else "$votes votes"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Snow.copy(alpha = 0.62f),
                            )
                        }
                    }
                }

                }

                val overview = item?.overview.orEmpty()
                if (!compact) Box(Modifier.fillMaxWidth().height(48.dp).testTag("tv.hero.synopsis")) {
                if (overview.isNotBlank()) {
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Snow.copy(alpha = 0.96f),
                        maxLines = overviewMaxLines.coerceAtMost(2),
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                }
                }
            }
        }
    }
}
