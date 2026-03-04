package com.streamvault.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil3.compose.AsyncImage
import com.streamvault.android.R
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberSubtle
import com.streamvault.android.ui.theme.Ash
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Coral
import com.streamvault.android.ui.theme.Graphite
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.Steel
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.EnrichedChannel
import com.streamvault.domain.model.EpgProgramme
import com.streamvault.domain.model.canonicalEpgChannelKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

internal const val IPTV_EPG_WINDOW_HOURS = 2
private const val EPG_SLOT_MINUTES = 30
private const val EPG_SLOT_WIDTH_DP = 160
private const val EPG_CHANNEL_COL_WIDTH_DP = 180
private const val EPG_ROW_HEIGHT_DP = 52
private val epgTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val epgDateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

private data class EpgWindowCell(
    val startMs: Long,
    val endMs: Long,
    val programme: EpgProgramme?,
)

@Composable
internal fun TvEpgGrid(
    channels: List<EnrichedChannel>,
    guideProgrammes: Map<String, List<EpgProgramme>>,
    windowStartMs: Long,
    windowEndMs: Long,
    canPageBackward: Boolean,
    canPageForward: Boolean,
    focusRowIndex: Int,
    focusColIndex: Int,
    focusRequestToken: Int,
    isFocusEnabled: Boolean,
    onChannelFocused: (EnrichedChannel, EpgProgramme?) -> Unit,
    onGridCellFocused: (rowIndex: Int, colIndex: Int) -> Unit,
    onChannelPlay: (Channel) -> Unit,
    onTimeForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slotMillis = EPG_SLOT_MINUTES * 60_000L
    val slotCount = ((windowEndMs - windowStartMs) / slotMillis).toInt().coerceAtLeast(1)
    val timelineWidth = (slotCount * EPG_SLOT_WIDTH_DP).dp
    val listState = rememberLazyListState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val targetRow = focusRowIndex.coerceIn(0, (channels.lastIndex).coerceAtLeast(0))
    val targetCol = focusColIndex.coerceAtLeast(0)

    LaunchedEffect(focusRequestToken, targetRow, channels.size) {
        if (channels.isEmpty()) return@LaunchedEffect
        runCatching { listState.scrollToItem(targetRow) }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Graphite.copy(alpha = 0.55f))
            .border(1.dp, Steel.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .focusGroup(),
    ) {
        EpgTimeHeader(
            windowStartMs = windowStartMs,
            slotCount = slotCount,
            slotMillis = slotMillis,
        )

        if (channels.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.tv_iptv_no_channels_in_group),
                    color = Silver,
                    fontSize = 14.sp,
                )
            }
            return
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    items = channels,
                    key = { _, enriched -> enriched.channel.url },
                ) { rowIndex, enriched ->
                    val programmes = remember(enriched.channel, guideProgrammes) {
                        val key = canonicalEpgChannelKey(
                            playlistId = enriched.channel.playlistId,
                            channel = enriched.channel,
                        )
                        if (key.isNullOrBlank()) {
                            emptyList()
                        } else {
                            guideProgrammes[key].orEmpty()
                        }
                    }
                    val cells = remember(programmes, windowStartMs, windowEndMs) {
                        buildWindowCells(
                            programmes = programmes,
                            windowStartMs = windowStartMs,
                            windowEndMs = windowEndMs,
                        )
                    }
                    EpgChannelRow(
                        rowNumber = rowIndex + 1,
                        channel = enriched,
                        cells = cells,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        timelineWidth = timelineWidth,
                        canPageBackward = canPageBackward,
                        canPageForward = canPageForward,
                        rowIndex = rowIndex,
                        focusTargetRow = targetRow,
                        focusTargetCol = targetCol,
                        focusRequestToken = focusRequestToken,
                        isFocusEnabled = isFocusEnabled,
                        nowMs = nowMs,
                        onChannelFocused = { programme -> onChannelFocused(enriched, programme) },
                        onGridCellFocused = { colIndex ->
                            onGridCellFocused(rowIndex, colIndex)
                        },
                        onChannelPlay = { onChannelPlay(enriched.channel) },
                        onTimeForward = onTimeForward,
                    )
                }
            }

            if (nowMs in windowStartMs..windowEndMs) {
                val nowFraction = ((nowMs - windowStartMs).toFloat() / (windowEndMs - windowStartMs).toFloat())
                    .coerceIn(0f, 1f)
                val nowOffsetDp = timelineWidth * nowFraction
                Box(
                    modifier = Modifier
                        .offset(x = EPG_CHANNEL_COL_WIDTH_DP.dp + nowOffsetDp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Coral),
                )
            }
        }
    }
}

@Composable
private fun EpgTimeHeader(
    windowStartMs: Long,
    slotCount: Int,
    slotMillis: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(Charcoal.copy(alpha = 0.9f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(EPG_CHANNEL_COL_WIDTH_DP.dp)
                .fillMaxHeight()
                .background(Obsidian.copy(alpha = 0.65f))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = epgDateFormat.format(Date(windowStartMs)),
                color = Snow,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row {
            repeat(slotCount) { index ->
                val slotStart = windowStartMs + index * slotMillis
                Box(
                    modifier = Modifier
                        .width(EPG_SLOT_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .background(if (index % 2 == 0) Charcoal else Graphite),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = epgTimeFormat.format(Date(slotStart)),
                        color = Silver,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EpgChannelRow(
    rowNumber: Int,
    channel: EnrichedChannel,
    cells: List<EpgWindowCell>,
    windowStartMs: Long,
    windowEndMs: Long,
    timelineWidth: androidx.compose.ui.unit.Dp,
    canPageBackward: Boolean,
    canPageForward: Boolean,
    rowIndex: Int,
    focusTargetRow: Int,
    focusTargetCol: Int,
    focusRequestToken: Int,
    isFocusEnabled: Boolean,
    nowMs: Long,
    onChannelFocused: (EpgProgramme?) -> Unit,
    onGridCellFocused: (colIndex: Int) -> Unit,
    onChannelPlay: () -> Unit,
    onTimeForward: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(EPG_ROW_HEIGHT_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .width(EPG_CHANNEL_COL_WIDTH_DP.dp)
                .fillMaxHeight()
                .background(Obsidian.copy(alpha = 0.4f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val channelNumber = channel.channel.channelNumber ?: rowNumber
            Text(
                text = channelNumber.toString(),
                color = Ash,
                fontSize = 11.sp,
                modifier = Modifier.width(24.dp),
            )
            channel.channel.tvgLogo?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    modifier = Modifier
                        .width(30.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                text = channel.channel.name,
                color = Snow,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            )
        }

        Row(
            modifier = Modifier
                .width(timelineWidth)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.Start,
        ) {
            val targetColForRow = when {
                focusTargetCol >= 0 -> focusTargetCol.coerceAtMost(cells.lastIndex)
                else -> cells.indexOfFirst { nowMs >= it.startMs && nowMs < it.endMs }.takeIf { it >= 0 } ?: 0
            }
            cells.forEachIndexed { index, cell ->
                val cellFraction = ((cell.endMs - cell.startMs).toFloat() / (windowEndMs - windowStartMs).toFloat())
                    .coerceIn(0f, 1f)
                val cellWidth = (timelineWidth * cellFraction).coerceAtLeast(48.dp)
                EpgProgrammeCell(
                    programme = cell.programme,
                    width = cellWidth,
                    isNow = cell.programme?.let { it.startTime <= nowMs && it.endTime > nowMs } == true,
                    isLastFocusableCell = index == cells.lastIndex,
                    canPageForward = canPageForward,
                    applyFocusTarget = rowIndex == focusTargetRow && index == targetColForRow,
                    focusRequestToken = focusRequestToken,
                    isFocusEnabled = isFocusEnabled,
                    onFocused = { onChannelFocused(cell.programme) },
                    onCellFocused = { onGridCellFocused(index) },
                    onPlay = onChannelPlay,
                    onTimeForward = onTimeForward,
                )
            }
        }
    }
}

@Composable
private fun EpgProgrammeCell(
    programme: EpgProgramme?,
    width: androidx.compose.ui.unit.Dp,
    isNow: Boolean,
    isLastFocusableCell: Boolean,
    canPageForward: Boolean,
    applyFocusTarget: Boolean,
    focusRequestToken: Int,
    isFocusEnabled: Boolean,
    onFocused: () -> Unit,
    onCellFocused: () -> Unit,
    onPlay: () -> Unit,
    onTimeForward: () -> Unit,
) {
    val cellFocusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(focusRequestToken, applyFocusTarget, isFocusEnabled) {
        if (!isFocusEnabled || !applyFocusTarget) return@LaunchedEffect
        runCatching { cellFocusRequester.requestFocus() }
    }

    Surface(
        onClick = onPlay,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .focusRequester(cellFocusRequester)
            .focusProperties { canFocus = isFocusEnabled }
            .graphicsLayer {
                val scale = if (isFocused) 1.03f else 1f
                scaleX = scale
                scaleY = scale
            }
            .border(
                width = if (isFocused) 2.dp else 0.5.dp,
                color = if (isFocused) Amber else Steel.copy(alpha = 0.35f),
                shape = RoundedCornerShape(0.dp),
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    onFocused()
                    onCellFocused()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onPlay()
                        true
                    }

                    Key.DirectionRight -> {
                        if (!isLastFocusableCell) return@onPreviewKeyEvent false
                        if (canPageForward) {
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
            containerColor = if (isNow) AmberSubtle else Graphite.copy(alpha = 0.45f),
            focusedContainerColor = Amber.copy(alpha = 0.42f),
        ),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = 0.5.dp,
                    color = Steel.copy(alpha = 0.35f),
                ),
                shape = RoundedCornerShape(0.dp),
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = 4.dp,
                    color = Amber,
                ),
                shape = RoundedCornerShape(0.dp),
            ),
        ),
    ) {
        Text(
            text = programme?.title ?: "No EPG for this channel",
            color = if (isFocused || isNow) Snow else Silver,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

private fun buildWindowCells(
    programmes: List<EpgProgramme>,
    windowStartMs: Long,
    windowEndMs: Long,
): List<EpgWindowCell> {
    if (windowEndMs <= windowStartMs) {
        return listOf(EpgWindowCell(startMs = windowStartMs, endMs = windowStartMs + 1L, programme = null))
    }

    val cells = mutableListOf<EpgWindowCell>()
    var cursor = windowStartMs
    var hadVisibleProgramme = false

    // Programmes are already windowed and ordered by repository query:
    // ORDER BY epg_channel_key, start_time.
    programmes.forEach { programme ->
        if (programme.endTime <= windowStartMs) return@forEach
        if (programme.startTime >= windowEndMs) return@forEach
        val start = programme.startTime.coerceAtLeast(windowStartMs)
        val end = programme.endTime.coerceAtMost(windowEndMs)
        if (end <= start) return@forEach
        hadVisibleProgramme = true
        if (start > cursor) {
            cells += EpgWindowCell(
                startMs = cursor,
                endMs = start,
                programme = null,
            )
        }
        cells += EpgWindowCell(
            startMs = start,
            endMs = end,
            programme = programme,
        )
        cursor = end
    }

    if (!hadVisibleProgramme) {
        return listOf(EpgWindowCell(startMs = windowStartMs, endMs = windowEndMs, programme = null))
    }

    if (cursor < windowEndMs) {
        cells += EpgWindowCell(
            startMs = cursor,
            endMs = windowEndMs,
            programme = null,
        )
    }
    return if (cells.isEmpty()) {
        listOf(EpgWindowCell(startMs = windowStartMs, endMs = windowEndMs, programme = null))
    } else {
        cells
    }
}
