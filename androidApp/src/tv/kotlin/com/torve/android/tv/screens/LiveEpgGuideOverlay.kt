package com.torve.android.tv.screens

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Coral
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.Channel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.programmesForEpgChannel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val CHANNEL_COL_WIDTH_DP = 200
private const val SLOT_WIDTH_DP = 180 // width per 30-min slot
private const val EPG_WINDOW_HOURS = 6
private const val EPG_MAX_FORWARD_HOURS = 12
private const val EPG_WINDOW_DURATION_MS = EPG_WINDOW_HOURS * 3600_000L
private const val EPG_MAX_PAGE_OFFSET = EPG_MAX_FORWARD_HOURS / EPG_WINDOW_HOURS
private const val SLOTS = EPG_WINDOW_HOURS * 2 // 12 half-hour slots
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun LiveEpgGuideOverlay(
    guideChannels: List<EnrichedChannel>,
    guideProgrammes: Map<String, List<EpgProgramme>>,
    playlistId: String? = null,
    currentChannelUrl: String,
    onTuneChannel: (Channel) -> Unit,
    onShowChannelList: () -> Unit,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var windowPageOffset by remember { mutableIntStateOf(0) }
    // Throttle EPG page changes so rapid LEFT/RIGHT repeats don't cascade redraws.
    var lastPageChangeMs by remember { mutableLongStateOf(0L) }
    val pageThrottleMs = 250L // One page change per 250ms max
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val alignedWindowStart = remember(nowMs) {
        Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.MINUTE, if (get(Calendar.MINUTE) < 30) 0 else 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val windowStart = alignedWindowStart + (windowPageOffset * EPG_WINDOW_DURATION_MS)
    val windowEnd = windowStart + EPG_WINDOW_DURATION_MS
    val canPageBackward = windowPageOffset > 0
    val canPageForward = windowPageOffset < EPG_MAX_PAGE_OFFSET
    val totalWidthDp = SLOTS * SLOT_WIDTH_DP

    val listState = rememberLazyListState()
    val firstChannelFocus = remember { FocusRequester() }

    // Compute "now" indicator offset from window start
    val nowOffsetFraction = ((nowMs - windowStart).toFloat() / (windowEnd - windowStart).toFloat()).coerceIn(0f, 1f)
    val nowOffsetDp = (totalWidthDp * nowOffsetFraction).dp

    LaunchedEffect(Unit) {
        try { firstChannelFocus.requestFocus() } catch (_: IllegalStateException) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.95f)),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
            // Title row + non-blocking recording-availability hint
            // (Prompt 10B). Recordings are managed on desktop today;
            // surfacing the copy here keeps TV users informed without
            // breaking guide focus or D-pad nav.
            Row(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.tv_live_tv_guide),
                    color = Snow,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Recordings managed on desktop",
                    color = Silver,
                    fontSize = 12.sp,
                )
            }

            // Time ruler row
            Row(modifier = Modifier.fillMaxWidth()) {
                // Channel column header
                Box(
                    modifier = Modifier
                        .width(CHANNEL_COL_WIDTH_DP.dp)
                        .height(36.dp)
                        .background(Charcoal),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(R.string.tv_live_channels),
                        color = Silver,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                // Time slots
                Row {
                    repeat(SLOTS) { i ->
                        val slotTime = windowStart + i * 30 * 60_000L
                        Box(
                            modifier = Modifier
                                .width(SLOT_WIDTH_DP.dp)
                                .height(36.dp)
                                .background(if (i % 2 == 0) Charcoal else Graphite),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = timeFormat.format(Date(slotTime)),
                                color = Silver,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }

            // Channel rows + programme grid
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        guideChannels,
                        key = { index, ch -> "${index}_${ch.channel.url}" },
                    ) { index, enrichedChannel ->
                        val isCurrentChannel = enrichedChannel.channel.url == currentChannelUrl
                        val channelProgs = remember(enrichedChannel.channel, guideProgrammes, playlistId) {
                            val lookupPlaylistId = enrichedChannel.channel.playlistId
                                .takeIf { it.isNotBlank() }
                                ?: playlistId.orEmpty()
                            programmesForEpgChannel(
                                programmesByChannelKey = guideProgrammes,
                                playlistId = lookupPlaylistId,
                                channel = enrichedChannel.channel,
                            )
                        }

                        EpgChannelRow(
                            enrichedChannel = enrichedChannel,
                            programmes = channelProgs,
                            isCurrentChannel = isCurrentChannel,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            nowMs = nowMs,
                            totalWidthDp = totalWidthDp,
                            canPageBackward = canPageBackward,
                            canPageForward = canPageForward,
                            focusRequester = if (index == 0) firstChannelFocus else null,
                            onTune = { onTuneChannel(enrichedChannel.channel) },
                            onFocused = { },
                            onTimeBackward = {
                                val now = System.currentTimeMillis()
                                if (windowPageOffset > 0 && now - lastPageChangeMs >= pageThrottleMs) {
                                    lastPageChangeMs = now
                                    windowPageOffset -= 1
                                }
                            },
                            onTimeForward = {
                                val now = System.currentTimeMillis()
                                if (windowPageOffset < EPG_MAX_PAGE_OFFSET && now - lastPageChangeMs >= pageThrottleMs) {
                                    lastPageChangeMs = now
                                    windowPageOffset += 1
                                }
                            },
                            onAtLeftEdge = onShowChannelList,
                        )
                    }
                }

                // "Now" time indicator (red vertical line)
                Box(
                    modifier = Modifier
                        .offset(x = CHANNEL_COL_WIDTH_DP.dp + nowOffsetDp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Coral),
                )
            }
        }
    }
}

@Composable
private fun EpgChannelRow(
    enrichedChannel: EnrichedChannel,
    programmes: List<EpgProgramme>,
    isCurrentChannel: Boolean,
    windowStart: Long,
    windowEnd: Long,
    nowMs: Long,
    totalWidthDp: Int,
    canPageBackward: Boolean,
    canPageForward: Boolean,
    focusRequester: FocusRequester?,
    onTune: () -> Unit,
    onFocused: () -> Unit,
    onTimeBackward: () -> Unit,
    onTimeForward: () -> Unit,
    onAtLeftEdge: () -> Unit,
) {
    val ch = enrichedChannel.channel
    val rowHeight = 56.dp
    val bgColor = if (isCurrentChannel) AmberSubtle else Charcoal.copy(alpha = 0.6f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(bgColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Channel info column (fixed left)
        Surface(
            onClick = onTune,
            modifier = Modifier
                .width(CHANNEL_COL_WIDTH_DP.dp)
                .fillMaxHeight()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                        if (canPageBackward) {
                            onTimeBackward()
                        } else {
                            onAtLeftEdge()
                        }
                        true
                    } else {
                        false
                    }
                },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Graphite,
                focusedContainerColor = Amber.copy(alpha = 0.2f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ch.tvgLogo?.let { logo ->
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = ch.name,
                    color = if (isCurrentChannel) Amber else Snow,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Programme cells
        val firstVisibleIndex = programmes.indexOfFirst { it.endTime > windowStart && it.startTime < windowEnd }
        val lastVisibleIndex = programmes.indexOfLast { it.endTime > windowStart && it.startTime < windowEnd }
        if (firstVisibleIndex < 0 || lastVisibleIndex < firstVisibleIndex) {
            // No programme data - single empty cell
            Box(
                modifier = Modifier
                    .width(totalWidthDp.dp)
                    .fillMaxHeight()
                    .background(Obsidian.copy(alpha = 0.4f))
                    .padding(start = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.tv_live_no_programme_data),
                    color = Silver.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }
        } else {
            Row {
                for (index in firstVisibleIndex..lastVisibleIndex) {
                    val prog = programmes[index]
                    val clampedStart = prog.startTime.coerceAtLeast(windowStart)
                    val clampedEnd = prog.endTime.coerceAtMost(windowEnd)
                    if (clampedEnd <= clampedStart) continue
                    val fraction = (clampedEnd - clampedStart).toFloat() / (windowEnd - windowStart).toFloat()
                    val cellWidth = (totalWidthDp * fraction).dp.coerceAtLeast(40.dp)
                    val isNow = prog.startTime <= nowMs && prog.endTime > nowMs

                    ProgrammeCell(
                        programme = prog,
                        width = cellWidth,
                        height = rowHeight,
                        isNow = isNow,
                        isFirstFocusableCell = index == firstVisibleIndex,
                        isLastFocusableCell = index == lastVisibleIndex,
                        canPageBackward = canPageBackward,
                        canPageForward = canPageForward,
                        onTune = onTune,
                        onTimeBackward = onTimeBackward,
                        onTimeForward = onTimeForward,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCell(
    programme: EpgProgramme,
    width: Dp,
    height: Dp,
    isNow: Boolean,
    isFirstFocusableCell: Boolean,
    isLastFocusableCell: Boolean,
    canPageBackward: Boolean,
    canPageForward: Boolean,
    onTune: () -> Unit,
    onTimeBackward: () -> Unit,
    onTimeForward: () -> Unit,
) {
    Surface(
        onClick = onTune,
        modifier = Modifier
            .width(width)
            .height(height)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (!isFirstFocusableCell) {
                            false
                        } else if (canPageBackward) {
                            onTimeBackward()
                            true
                        } else {
                            false
                        }
                    }

                    Key.DirectionRight -> {
                        if (!isLastFocusableCell) {
                            false
                        } else if (canPageForward) {
                            onTimeForward()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isNow) AmberSubtle else Obsidian.copy(alpha = 0.3f),
            focusedContainerColor = Amber.copy(alpha = 0.45f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = programme.title,
                color = Snow,
                fontSize = 12.sp,
                fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${timeFormat.format(Date(programme.startTime))}-${timeFormat.format(Date(programme.endTime))}",
                color = Silver.copy(alpha = 0.7f),
                fontSize = 10.sp,
            )
        }
    }
}
