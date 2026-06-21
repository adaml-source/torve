package com.torve.android.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.torve.android.ui.components.TorveSearchField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.Channel

private val CHANNEL_COL_WIDTH = 100.dp
private val ROW_HEIGHT = 64.dp
private val TIME_HEADER_HEIGHT = 32.dp
private const val PIXELS_PER_MINUTE = 3f // 3dp per minute = 180dp per hour
private const val GUIDE_HOURS = 6 // show 6 hours of timeline

@Composable
fun ChannelsGuideContent(
    channels: List<EnrichedChannel>,
    guideProgrammes: Map<String, List<EpgProgramme>>,
    isLoading: Boolean,
    onChannelPlay: (Channel) -> Unit,
    canReplayProgramme: (Channel, EpgProgramme) -> Boolean = { _, _ -> false },
    onProgrammePlay: ((Channel, EpgProgramme) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = Amber,
                strokeWidth = 3.dp,
            )
        }
        return
    }

    if (channels.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.LiveTv,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Torve.colors.textHint,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.channels_no_epg_data),
                    style = MaterialTheme.typography.titleMedium,
                    color = Torve.colors.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.channels_no_epg_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textTertiary,
                )
            }
        }
        return
    }

    val now = remember { System.currentTimeMillis() }
    // Round down to current hour
    val baseTime = now - (now % 3600000L)
    val timelineWidthDp = (GUIDE_HOURS * 60 * PIXELS_PER_MINUTE).dp
    val scrollState = rememberScrollState()

    // Pre-calculate initial scroll to current time
    val density = LocalDensity.current
    val initialScroll = remember {
        val minutesFromBase = ((now - baseTime) / 60000f)
        (minutesFromBase * PIXELS_PER_MINUTE * density.density).toInt()
    }

    // Scroll to current time on first composition
    androidx.compose.runtime.LaunchedEffect(Unit) {
        scrollState.scrollTo((initialScroll - 50 * density.density).toInt().coerceAtLeast(0))
    }

    // In-screen search filters the channel list locally by name.
    // Pure client-side — no VM call — so it stays responsive even on
    // playlists with thousands of channels.
    var searchQuery by remember { mutableStateOf("") }
    val filteredChannels = remember(channels, searchQuery) {
        if (searchQuery.isBlank()) channels
        else {
            val needle = searchQuery.trim().lowercase()
            channels.filter { it.channel.name.lowercase().contains(needle) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TorveSearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = stringResource(R.string.channels_guide_search_placeholder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        // Time header row
        Row(modifier = Modifier.fillMaxWidth()) {
            // Channel column header
            Box(
                modifier = Modifier
                    .width(CHANNEL_COL_WIDTH)
                    .height(TIME_HEADER_HEIGHT)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.channels_channel_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = Torve.colors.textTertiary,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Scrollable time header
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(TIME_HEADER_HEIGHT)
                    .horizontalScroll(scrollState),
            ) {
                TimeHeaderRow(baseTime = baseTime, totalWidth = timelineWidthDp, now = now)
            }
        }

        HorizontalDivider(
            color = Torve.colors.border.copy(alpha = 0.3f),
            thickness = 0.5.dp,
        )

        if (filteredChannels.isEmpty() && searchQuery.isNotBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.channels_no_channels_matching, searchQuery),
                    color = Torve.colors.textSecondary,
                )
            }
            return@Column
        }

        // Channel rows with timeline
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredChannels, key = { "guide_${it.channel.playlistId}_${it.channel.url}" }) { enriched ->
                val progs = remember(enriched.channel, guideProgrammes) {
                    val chKey = canonicalEpgChannelKey(
                        playlistId = enriched.channel.playlistId,
                        channel = enriched.channel,
                    )
                    if (chKey.isNullOrBlank()) {
                        emptyList()
                    } else {
                        guideProgrammes[chKey].orEmpty()
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT),
                ) {
                    // Fixed channel column
                    ChannelCell(
                        enriched = enriched,
                        onPlay = { onChannelPlay(enriched.channel) },
                    )

                    // Scrollable programme timeline
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(scrollState),
                    ) {
                        ProgrammeTimeline(
                            channel = enriched.channel,
                            programmes = progs,
                            baseTime = baseTime,
                            now = now,
                            totalWidth = timelineWidthDp,
                            onPlay = { onChannelPlay(enriched.channel) },
                            canReplayProgramme = canReplayProgramme,
                            onProgrammePlay = onProgrammePlay,
                        )
                    }
                }

                HorizontalDivider(
                    color = Torve.colors.border.copy(alpha = 0.15f),
                    thickness = 0.5.dp,
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun TimeHeaderRow(baseTime: Long, totalWidth: Dp, now: Long) {
    val nowLineColor = Amber
    Box(modifier = Modifier.width(totalWidth).height(TIME_HEADER_HEIGHT)) {
        // Hour markers
        for (h in 0 until GUIDE_HOURS) {
            val offsetDp = (h * 60 * PIXELS_PER_MINUTE).dp
            val hourTime = baseTime + h * 3600000L
            val isNowHour = now in hourTime until hourTime + 3600000L

            val label = remember(hourTime) {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = hourTime }
                String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            }

            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isNowHour) FontWeight.Bold else FontWeight.Normal,
                color = if (isNowHour) Amber else Torve.colors.textTertiary,
                modifier = Modifier
                    .offset(x = offsetDp + 4.dp)
                    .padding(top = 8.dp),
            )
        }

        // NOW indicator line
        val nowOffset = ((now - baseTime) / 60000f * PIXELS_PER_MINUTE).dp
        Box(
            modifier = Modifier
                .offset(x = nowOffset)
                .width(2.dp)
                .fillMaxHeight()
                .background(nowLineColor),
        )
    }
}

@Composable
private fun ChannelCell(
    enriched: EnrichedChannel,
    onPlay: () -> Unit,
) {
    val channel = enriched.channel

    Row(
        modifier = Modifier
            .width(CHANNEL_COL_WIDTH)
            .fillMaxHeight()
            .clickable(onClick = onPlay)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Small logo
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Torve.colors.inputBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.tvgLogo != null) {
                AsyncImage(
                    model = channel.tvgLogo,
                    contentDescription = channel.name,
                    modifier = Modifier.size(32.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = channel.name.take(2).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.width(4.dp))

        Text(
            text = channel.name,
            style = MaterialTheme.typography.labelSmall,
            color = Torve.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProgrammeTimeline(
    channel: Channel,
    programmes: List<EpgProgramme>,
    baseTime: Long,
    now: Long,
    totalWidth: Dp,
    onPlay: () -> Unit,
    canReplayProgramme: (Channel, EpgProgramme) -> Boolean,
    onProgrammePlay: ((Channel, EpgProgramme) -> Unit)?,
) {
    val nowLineColor = Amber
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .width(totalWidth)
            .fillMaxHeight()
            .drawBehind {
                // Draw NOW line
                val nowX = ((now - baseTime) / 60000f * PIXELS_PER_MINUTE * density.density)
                drawLine(
                    color = nowLineColor,
                    start = Offset(nowX, 0f),
                    end = Offset(nowX, size.height),
                    strokeWidth = 2f * density.density,
                )

                // Draw hour grid lines
                for (h in 0 until GUIDE_HOURS) {
                    val x = h * 60 * PIXELS_PER_MINUTE * density.density
                    drawLine(
                        color = nowLineColor.copy(alpha = 0.1f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f,
                    )
                }
            },
    ) {
        if (programmes.isEmpty()) {
            // No programme data — show placeholder
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp, horizontal = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Torve.colors.inputBackground.copy(alpha = 0.5f))
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.channels_no_programme),
                    style = MaterialTheme.typography.labelSmall,
                    color = Torve.colors.textHint,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        } else {
            // Programme blocks
            Row(
                modifier = Modifier.fillMaxHeight().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                programmes.forEach { prog ->
                    val startOffset = ((prog.startTime - baseTime).coerceAtLeast(0) / 60000f * PIXELS_PER_MINUTE).dp
                    val durationMin = ((prog.endTime - prog.startTime).coerceAtLeast(60000) / 60000f)
                    val blockWidth = (durationMin * PIXELS_PER_MINUTE).dp.coerceAtLeast(30.dp)

                    val isCurrent = now in prog.startTime until prog.endTime
                    val isPast = prog.endTime <= now
                    val canReplay = isPast && canReplayProgramme(channel, prog)

                    val bgColor = when {
                        isCurrent -> Amber.copy(alpha = 0.2f)
                        isPast -> Torve.colors.inputBackground.copy(alpha = 0.3f)
                        else -> Torve.colors.inputBackground.copy(alpha = 0.7f)
                    }
                    val borderColor = if (isCurrent) Amber else Torve.colors.border.copy(alpha = 0.3f)

                    Box(
                        modifier = Modifier
                            .offset(x = startOffset)
                            .width(blockWidth)
                            .fillMaxHeight()
                            .padding(end = 1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(bgColor)
                            .drawBehind {
                                // Left border accent for current programme
                                if (isCurrent) {
                                    drawRect(
                                        color = nowLineColor,
                                        size = size.copy(width = 3f * density.density),
                                    )
                                }
                            }
                            .clickable(
                                onClick = {
                                    if (canReplay && onProgrammePlay != null) {
                                        onProgrammePlay(channel, prog)
                                    } else {
                                        onPlay()
                                    }
                                },
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column {
                            if (canReplay) {
                                Text(
                                    text = "Replay",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Amber,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                text = prog.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) Amber else Torve.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Show time range
                            val timeText = remember(prog) {
                                val startCal = java.util.Calendar.getInstance().apply { timeInMillis = prog.startTime }
                                val endCal = java.util.Calendar.getInstance().apply { timeInMillis = prog.endTime }
                                String.format(
                                    "%02d:%02d-%02d:%02d",
                                    startCal.get(java.util.Calendar.HOUR_OF_DAY),
                                    startCal.get(java.util.Calendar.MINUTE),
                                    endCal.get(java.util.Calendar.HOUR_OF_DAY),
                                    endCal.get(java.util.Calendar.MINUTE),
                                )
                            }
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = Torve.colors.textTertiary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
