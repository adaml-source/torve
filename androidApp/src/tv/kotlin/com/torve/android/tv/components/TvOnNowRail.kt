package com.torve.android.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.EnrichedChannel

/**
 * "On Now" / Live-TV rail for TV Home (Prompt 11C).
 *
 * Renders a horizontal LazyRow of currently-airing channels. Each tile
 * shows the channel name, current programme title, and ends with a
 * timestamp range so the user knows what's actually on right now.
 * Tile click invokes [onChannelClick] which the caller wires to the
 * existing live-player route — TV-Home does NOT navigate to
 * media-details for channel tiles.
 *
 * Visual style is intentionally distinct from the MediaItem poster
 * rail so the user sees this is a different tap target.
 */
@Composable
internal fun TvOnNowRail(
    title: String,
    channels: List<EnrichedChannel>,
    onChannelClick: (EnrichedChannel) -> Unit,
    modifier: Modifier = Modifier,
    firstTileFocusRequester: FocusRequester? = null,
    railFocusRequester: FocusRequester? = null,
    onContentFocused: ((FocusRequester) -> Unit)? = null,
) {
    if (channels.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp),
        ) {
            items(channels, key = { it.channel.url }) { channel ->
                val isFirst = channel == channels.firstOrNull()
                OnNowTile(
                    channel = channel,
                    onClick = { onChannelClick(channel) },
                    focusRequester = firstTileFocusRequester?.takeIf { isFirst },
                    railFocusRequester = railFocusRequester?.takeIf { isFirst },
                    onFocused = {
                        if (isFirst && firstTileFocusRequester != null) {
                            onContentFocused?.invoke(firstTileFocusRequester)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OnNowTile(
    channel: EnrichedChannel,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    railFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val container = if (focused) Color(0x3326C6DA) else Color(0x14FFFFFF)
    val borderColor = if (focused) Color(0xFF26C6DA) else Color(0x33FFFFFF)
    Box(
        modifier = Modifier
            .width(280.dp)
            .height(120.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (railFocusRequester != null) Modifier.focusProperties { left = railFocusRequester } else Modifier)
            .background(container, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "LIVE  •  ${channel.channel.name}",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF26C6DA),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val programme = channel.currentProgramme
            Text(
                text = programme?.title ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            channel.channel.groupTitle?.takeIf { it.isNotBlank() }?.let { group ->
                Text(
                    text = group,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
