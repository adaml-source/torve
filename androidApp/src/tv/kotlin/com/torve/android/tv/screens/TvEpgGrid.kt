package com.torve.android.tv.screens

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.Channel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.programmesForEpgChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

internal const val IPTV_EPG_WINDOW_HOURS = 2
private const val EPG_SLOT_MINUTES = 30
private const val EPG_SLOT_WIDTH_DP = 300
private const val EPG_CHANNEL_COL_WIDTH_DP = 178
private const val EPG_ROW_HEIGHT_DP = 46
private val epgTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val epgDateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

// Navigation tuning
private const val VERTICAL_DEBOUNCE_MS = 120L
private const val FOCUS_SETTLE_MS = 40L
// Vertical safe band: don't scroll if the target row is within this
// many rows of the viewport edges. Only scroll when it would leave view.
private const val VERTICAL_SAFE_MARGIN_ROWS = 1

private data class EpgWindowCell(
    val startMs: Long,
    val endMs: Long,
    val programme: EpgProgramme?,
)

@Composable
internal fun TvEpgGrid(
    channels: List<EnrichedChannel>,
    guideProgrammes: Map<String, List<EpgProgramme>>,
    isGuideLoading: Boolean = false,
    playlistId: String? = null,
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
    onMoveVertical: (delta: Int) -> Unit,
    onChannelPlay: (Channel) -> Unit,
    onTimeForward: () -> Unit,
    modifier: Modifier = Modifier,
    channelColumnWidth: Dp = EPG_CHANNEL_COL_WIDTH_DP.dp,
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
    var lastVerticalMoveMs by remember { mutableLongStateOf(0L) }

    // Gentle scroll: only scroll when the target row is outside the visible
    // viewport with a small margin. Never snap the target to the top.
    LaunchedEffect(focusRequestToken, targetRow, channels.size) {
        if (channels.isEmpty()) return@LaunchedEffect
        val firstVisible = listState.firstVisibleItemIndex
        val visibleCount = listState.layoutInfo.visibleItemsInfo.size
        val lastVisible = firstVisible + visibleCount - 1
        val safeTop = firstVisible + VERTICAL_SAFE_MARGIN_ROWS
        val safeBottom = lastVisible - VERTICAL_SAFE_MARGIN_ROWS
        when {
            // Target is above the safe band — scroll up just enough
            targetRow < safeTop -> {
                val scrollTo = (targetRow - VERTICAL_SAFE_MARGIN_ROWS).coerceAtLeast(0)
                runCatching { listState.animateScrollToItem(scrollTo) }
            }
            // Target is below the safe band — scroll down just enough
            targetRow > safeBottom -> {
                val scrollTo = (targetRow - visibleCount + VERTICAL_SAFE_MARGIN_ROWS + 1)
                    .coerceIn(0, (channels.size - visibleCount).coerceAtLeast(0))
                runCatching { listState.animateScrollToItem(scrollTo) }
            }
            // Target is within safe band — no scroll needed
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Charcoal.copy(alpha = 0.72f),
                        Obsidian.copy(alpha = 0.92f),
                    ),
                ),
            )
            .border(1.dp, Steel.copy(alpha = 0.28f), RoundedCornerShape(26.dp))
            .onPreviewKeyEvent { event ->
                // Serialize vertical navigation at the grid level.
                // Debounce prevents stacked DPAD events from causing double-jumps.
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                        val now = System.currentTimeMillis()
                        if (now - lastVerticalMoveMs < VERTICAL_DEBOUNCE_MS) return@onPreviewKeyEvent true
                        lastVerticalMoveMs = now
                        onMoveVertical(if (event.key == Key.DirectionUp) -1 else 1)
                        true
                    }
                    else -> false
                }
            }
            .focusGroup(),
    ) {
        EpgTimeHeader(
            windowStartMs = windowStartMs,
            slotCount = slotCount,
            slotMillis = slotMillis,
            channelColumnWidth = channelColumnWidth,
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
                    key = { index, enriched -> "${enriched.channel.url}#$index" },
                ) { rowIndex, enriched ->
                    val programmes = remember(enriched.channel, guideProgrammes, playlistId) {
                        val lookupPlaylistId = enriched.channel.playlistId
                            .takeIf { it.isNotBlank() }
                            ?: playlistId.orEmpty()
                        programmesForEpgChannel(
                            programmesByChannelKey = guideProgrammes,
                            playlistId = lookupPlaylistId,
                            channel = enriched.channel,
                        )
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
                        channelColumnWidth = channelColumnWidth,
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
                        isGuideLoading = isGuideLoading,
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
                Column(
                    modifier = Modifier
                        .offset(x = channelColumnWidth + nowOffsetDp, y = (-24).dp)
                        .width(42.dp)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Amber)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = epgTimeFormat.format(Date(nowMs)),
                            color = Obsidian,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Amber.copy(alpha = 0.86f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun EpgTimeHeader(
    windowStartMs: Long,
    slotCount: Int,
    slotMillis: Long,
    channelColumnWidth: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(Obsidian.copy(alpha = 0.78f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(channelColumnWidth)
                .fillMaxHeight()
                .background(Obsidian.copy(alpha = 0.70f))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = epgDateFormat.format(Date(windowStartMs)),
                color = Snow,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
        Row {
            repeat(slotCount) { index ->
                val slotStart = windowStartMs + index * slotMillis
                Box(
                    modifier = Modifier
                        .width(EPG_SLOT_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .background(
                            if (index % 2 == 0) Charcoal.copy(alpha = 0.34f)
                            else Graphite.copy(alpha = 0.28f),
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = epgTimeFormat.format(Date(slotStart)),
                        color = Silver,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
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
    channelColumnWidth: Dp,
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
    isGuideLoading: Boolean,
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
                .width(channelColumnWidth)
                .fillMaxHeight()
                .background(Obsidian.copy(alpha = 0.44f))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val channelNumber = channel.channel.channelNumber ?: rowNumber
            Text(
                text = channelNumber.toString(),
                color = Ash,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(32.dp),
            )
            channel.channel.tvgLogo?.let { logo ->
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Charcoal.copy(alpha = 0.26f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            Text(
                text = channel.channel.name,
                color = Snow,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
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
                    fallbackTitle = if (isGuideLoading) "Loading schedule" else "Schedule unavailable",
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
    fallbackTitle: String,
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

    // Single focus request per cell: only fires when this cell becomes the
    // explicit target. The short settle delay lets the LazyColumn compose
    // the target row before we request focus.
    LaunchedEffect(focusRequestToken, applyFocusTarget) {
        if (!isFocusEnabled || !applyFocusTarget) return@LaunchedEffect
        delay(FOCUS_SETTLE_MS)
        runCatching { cellFocusRequester.requestFocus() }
    }

    Surface(
        onClick = onPlay,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .focusRequester(cellFocusRequester)
            .focusProperties { canFocus = isFocusEnabled }
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isNow) Graphite.copy(alpha = 0.58f) else Graphite.copy(alpha = 0.46f),
            focusedContainerColor = Amber.copy(alpha = 0.24f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = when {
                        isFocused -> Amber
                        isNow -> Amber.copy(alpha = 0.22f)
                        else -> Steel.copy(alpha = 0.16f)
                    },
                    shape = RoundedCornerShape(14.dp),
                )
                .background(
                    if (isFocused) {
                        Brush.radialGradient(
                            colors = listOf(
                                Amber.copy(alpha = 0.22f),
                                Color.Transparent,
                            ),
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Obsidian.copy(alpha = 0.22f)),
                        )
                    },
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = programme?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                color = when {
                    programme == null -> Silver.copy(alpha = 0.72f)
                    isFocused || isNow -> Snow
                    else -> Silver
                },
                fontSize = if (isFocused && programme != null) 13.sp else 12.sp,
                fontWeight = when {
                    programme == null -> FontWeight.Normal
                    isFocused -> FontWeight.SemiBold
                    else -> FontWeight.Medium
                },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
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
