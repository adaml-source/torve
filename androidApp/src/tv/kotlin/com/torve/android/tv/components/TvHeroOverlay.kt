package com.torve.android.tv.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.MediaItem

/**
 * Hero overlay with title, metadata, overview, and action buttons.
 * Sits INSIDE the scrollable LazyColumn as the first item, so it
 * scrolls up naturally — exactly like mobile's HeroSlide but with
 * D-pad focus support.
 */
@Composable
fun TvHeroOverlay(
    featuredItem: MediaItem?,
    sectionTitle: String,
    subtitle: String,
    primaryActionFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    onPlayFeatured: () -> Unit,
    onOpenFeatured: () -> Unit,
    isInWatchlist: Boolean = false,
    onWatchlistToggle: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Keep more of the hero artwork visible while still reserving space for metadata/actions.
        Spacer(modifier = Modifier.height(64.dp))

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
                    .defaultMinSize(minHeight = 176.dp)
                    .padding(start = 40.dp, end = 40.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Genre tags (matching mobile HeroSlide pattern)
                if (item != null && item.genres.isNotEmpty()) {
                    Text(
                        text = item.genres.take(3).joinToString(" \u2022 ") { it.name },
                        style = MaterialTheme.typography.labelMedium,
                        color = Amber,
                    )
                    Spacer(Modifier.height(4.dp))
                } else {
                    // Section label when no specific item focused
                    Text(
                        text = sectionTitle.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Amber,
                        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // Title
                Text(
                    text = item?.title ?: subtitle,
                    style = MaterialTheme.typography.displayMedium,
                    color = Snow,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Metadata: year • rating • runtime (matching mobile HeroSlide)
                if (item != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        item.year?.let {
                            Text(
                                text = it.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Snow.copy(alpha = 0.7f),
                            )
                        }
                        item.rating?.let { rating ->
                            if (rating > 0) {
                                Text(
                                    text = "  \u2022  \u2605 ${"%.1f".format(rating)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Amber.copy(alpha = 0.9f),
                                )
                            }
                        }
                        item.runtime?.let { runtime ->
                            Text(
                                text = "  \u2022  ${runtime}m",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Snow.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                // Overview
                if (!item?.overview.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item?.overview.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Snow.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(680.dp),
                    )
                }

                // Action buttons
                if (item != null) {
                    Spacer(Modifier.height(14.dp))
                    val detailsFocusRequester = remember { FocusRequester() }

                    val watchlistFocusRequester = remember { FocusRequester() }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Play button — Amber CTA
                        Button(
                            onClick = onPlayFeatured,
                            modifier = Modifier
                                .focusRequester(primaryActionFocusRequester)
                                .focusProperties {
                                    left = railFocusRequester
                                    right = detailsFocusRequester
                                },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = Obsidian,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.tv_action_play),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // Details button — PROPERLY FOCUSABLE
                        var detailsFocused by remember { mutableStateOf(false) }
                        val detailsScale by animateFloatAsState(
                            targetValue = if (detailsFocused) 1.05f else 1f,
                            label = "detailsScale",
                        )
                        val detailsBorderColor by animateColorAsState(
                            targetValue = if (detailsFocused) Amber else Color.Transparent,
                            label = "detailsBorderColor",
                        )

                        Box(
                            modifier = Modifier
                                .zIndex(if (detailsFocused) 1f else 0f)
                                .scale(detailsScale)
                                .border(2.dp, detailsBorderColor, RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (detailsFocused) Amber.copy(alpha = 0.25f)
                                    else Snow.copy(alpha = 0.15f),
                                )
                                .focusRequester(detailsFocusRequester)
                                .onFocusChanged {
                                    detailsFocused = it.isFocused
                                }
                                .focusProperties {
                                    left = primaryActionFocusRequester
                                    right = watchlistFocusRequester
                                }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onOpenFeatured,
                                )
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.tv_action_details),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (detailsFocused) Amber else Snow,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // Watchlist button
                        var watchlistFocused by remember { mutableStateOf(false) }
                        val watchlistScale by animateFloatAsState(
                            targetValue = if (watchlistFocused) 1.05f else 1f,
                            label = "watchlistScale",
                        )
                        val watchlistBorderColor by animateColorAsState(
                            targetValue = if (watchlistFocused) Amber else Color.Transparent,
                            label = "watchlistBorderColor",
                        )

                        Box(
                            modifier = Modifier
                                .zIndex(if (watchlistFocused) 1f else 0f)
                                .scale(watchlistScale)
                                .border(2.dp, watchlistBorderColor, RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (watchlistFocused) Amber.copy(alpha = 0.25f)
                                    else Snow.copy(alpha = 0.15f),
                                )
                                .focusRequester(watchlistFocusRequester)
                                .onFocusChanged {
                                    watchlistFocused = it.isFocused
                                }
                                .focusProperties {
                                    left = detailsFocusRequester
                                }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onWatchlistToggle,
                                )
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (isInWatchlist) {
                                    stringResource(R.string.tv_action_remove_watchlist)
                                } else {
                                    stringResource(R.string.tv_action_add_watchlist)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = if (watchlistFocused) Amber else Snow,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
