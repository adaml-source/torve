package com.torve.android.tv.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    modifier: Modifier = Modifier,
) {
    val settingsViewModel: SettingsViewModel = koinInject()
    val settingsState by settingsViewModel.state.collectAsState()
    val ratingPrefs = remember(settingsState.ratingPrefs) {
        settingsState.ratingPrefs.tvExternalCardRatingPrefs()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(56.dp))

        AnimatedContent(
            targetState = featuredItem,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(200))
            },
            contentKey = { it?.id },
            label = "heroOverlay",
        ) { item ->
            Column(
                modifier = Modifier
                    .padding(start = 56.dp, end = TV_PAGE_END_GUTTER, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val logoUrl = item?.logoUrl?.takeIf { it.isNotBlank() }
                var logoFailed by remember(logoUrl) { mutableStateOf(false) }
                if (!logoUrl.isNullOrBlank() && !logoFailed) {
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
                            .heightIn(min = 32.dp, max = 54.dp),
                    )
                } else {
                    val titleText = item?.title ?: subtitle
                    if (titleText.isNotBlank() && (!logoLookupInFlight || logoFailed)) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = 38.sp,
                                lineHeight = 42.sp,
                                letterSpacing = 0.6.sp,
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.82f),
                                    offset = Offset(0f, 3f),
                                    blurRadius = 18f,
                                ),
                            ),
                            color = Snow,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.38f),
                        )
                    } else {
                        Spacer(modifier = Modifier.height(44.dp))
                    }
                }

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
                                text = if (votes >= 1000) "${votes / 1000}K votes" else "$votes votes",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Snow.copy(alpha = 0.62f),
                            )
                        }
                    }
                }

                val overview = item?.overview.orEmpty()
                if (overview.isNotBlank()) {
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Snow.copy(alpha = 0.84f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(680.dp),
                    )
                }
            }
        }
    }
}
