package com.torve.android.tv.screens.stats

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.stats.WatchStatsRatingBand

internal enum class TvWatchStatsTimeRange(val label: String) {
    ALL_TIME("All time"),
    THIS_YEAR("This year"),
    THIS_MONTH("This month"),
    LAST_30_DAYS("Last 30 days"),
    LAST_7_DAYS("Last 7 days"),
}

internal enum class TvWatchStatsContentFilter(val label: String) {
    ALL("All"),
    MOVIES("Movies"),
    SHOWS("Shows"),
}

internal enum class TvWatchStatsStatusFilter(val label: String) {
    ALL("All statuses"),
    COMPLETED("Completed"),
    PARTIAL("Partial"),
    MANUAL("Manual"),
    IMPORTED("Imported"),
}

internal enum class TvWatchStatsSourceFilter(val label: String) {
    ALL("All sources"),
    TORVE("Torve"),
    TRAKT("Trakt"),
    SIMKL("SIMKL"),
    MANUAL("Manual"),
}

internal data class TvWatchStatsFilterSelection(
    val timeRange: TvWatchStatsTimeRange = TvWatchStatsTimeRange.ALL_TIME,
    val content: TvWatchStatsContentFilter = TvWatchStatsContentFilter.ALL,
    val status: TvWatchStatsStatusFilter = TvWatchStatsStatusFilter.ALL,
    val source: TvWatchStatsSourceFilter = TvWatchStatsSourceFilter.ALL,
    val genre: String? = null,
    val year: Int? = null,
    val decade: Int? = null,
    val ratingBand: WatchStatsRatingBand? = null,
    val actor: String? = null,
    val director: String? = null,
    val studio: String? = null,
    val network: String? = null,
    val provider: String? = null,
    val certification: String? = null,
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TvStatsFocusCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    meta: String? = null,
    selected: Boolean = false,
    locked: Boolean = false,
    enabled: Boolean = true,
    showHeader: Boolean = true,
    compact: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    content: @Composable (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var keyDownReceivedHere by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> Amber.copy(alpha = 0.66f)
            selected -> Amber.copy(alpha = 0.3f)
            locked -> Amber.copy(alpha = 0.18f)
            else -> Color.White.copy(alpha = 0.07f)
        },
        label = "watchStatsCardBorder",
    )
    val background = Brush.linearGradient(
        colors = when {
            focused -> listOf(Color(0xFF15120B).copy(alpha = 0.92f), Color(0xFF060707).copy(alpha = 0.97f))
            selected -> listOf(Color(0xFF120F0A).copy(alpha = 0.9f), Color(0xFF060707).copy(alpha = 0.96f))
            locked -> listOf(Color(0xFF100D09).copy(alpha = 0.86f), Color(0xFF060606).copy(alpha = 0.95f))
            else -> listOf(Color(0xFF0D0E0E).copy(alpha = 0.92f), Color(0xFF050606).copy(alpha = 0.97f))
        },
    )
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier = modifier
            .background(background, shape)
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .focusProperties {
                left?.let { this.left = it }
                right?.let { this.right = it }
                up?.let { this.up = it }
                down?.let { this.down = it }
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                val wasFocused = focused
                focused = it.isFocused
                if (it.isFocused && !wasFocused) {
                    keyDownReceivedHere = false
                    onFocused()
                }
            }
            .onPreviewKeyEvent { event ->
                if (!enabled) return@onPreviewKeyEvent false
                when {
                    event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        keyDownReceivedHere = true
                        true
                    }
                    event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        if (keyDownReceivedHere) {
                            keyDownReceivedHere = false
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled = enabled)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 8.dp else 11.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (focused || selected) AmberLight else Snow,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .weight(1f)
                        .basicMarquee(),
                )
                if (locked) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = Amber,
                    )
                }
            }
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (focused) Snow else Silver,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.basicMarquee(),
                )
            }
            meta?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ash,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        content?.invoke()
    }
}

@Composable
internal fun TvStatsChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    TvStatsFocusCard(
        title = label,
        selected = selected,
        focusRequester = focusRequester,
        left = left,
        right = right,
        onFocused = onFocused,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun TvStatsPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    left: FocusRequester? = null,
    right: FocusRequester? = null,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var keyDownReceivedHere by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> Amber.copy(alpha = 0.66f)
            selected -> Amber.copy(alpha = 0.32f)
            muted -> Color.White.copy(alpha = 0.05f)
            else -> Color.White.copy(alpha = 0.09f)
        },
        label = "watchStatsPillBorder",
    )
    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected -> AmberSubtle.copy(alpha = 0.24f)
            focused -> Color(0xFF15120C).copy(alpha = 0.92f)
            muted -> Color(0xFF090A0A).copy(alpha = 0.74f)
            else -> Color(0xFF0B0C0C).copy(alpha = 0.88f)
        },
        label = "watchStatsPillBackground",
    )

    Box(
        modifier = modifier
            .height(36.dp)
            .widthIn(min = 58.dp)
            .background(backgroundColor, shape)
            .border(if (focused) 1.5.dp else 1.dp, borderColor, shape)
            .focusProperties {
                left?.let { this.left = it }
                right?.let { this.right = it }
                up?.let { this.up = it }
                down?.let { this.down = it }
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                val wasFocused = focused
                focused = it.isFocused
                if (it.isFocused && !wasFocused) {
                    keyDownReceivedHere = false
                    onFocused()
                }
            }
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        keyDownReceivedHere = true
                        true
                    }
                    event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        if (keyDownReceivedHere) {
                            keyDownReceivedHere = false
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                focused || selected -> AmberLight
                muted -> Ash
                else -> Silver
            },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
internal fun TvStatsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Ash,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
internal fun TvStatsMetricLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Silver,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun TvStatsSourceChip(text: String) {
    Box(
        modifier = Modifier
            .background(Color(0xFF110F0A).copy(alpha = 0.86f), RoundedCornerShape(999.dp))
            .border(1.dp, Amber.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = AmberLight,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun TvStatsSmallSpacer() {
    Spacer(Modifier.height(6.dp))
}
