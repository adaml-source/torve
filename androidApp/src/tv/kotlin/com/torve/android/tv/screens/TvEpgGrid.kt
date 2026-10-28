package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import com.torve.android.tv.components.tvLiquidGlass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
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
import com.torve.presentation.channels.guideProgrammeSlots
import com.torve.domain.model.stableChannelId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

internal const val IPTV_EPG_WINDOW_HOURS = 2
private const val EPG_SLOT_MINUTES = 30
private const val EPG_CHANNEL_COL_WIDTH_DP = 178
private const val EPG_ROW_HEIGHT_DP = 48
private const val CHANNEL_LIST_ROW_HEIGHT_DP = 48
private val epgTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val epgDateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

// Navigation tuning
private const val VERTICAL_DEBOUNCE_MS = 120L
private const val FOCUS_SETTLE_MS = 40L

internal data class EpgWindowCell(
    val startMs: Long,
    val endMs: Long,
    val programme: EpgProgramme?,
) {
    fun isCurrentAt(nowMs: Long): Boolean = programme != null && nowMs >= startMs && nowMs < endMs
}

@OptIn(ExperimentalFoundationApi::class)
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
    onProgrammeActions: (Channel, EpgProgramme?) -> Unit,
    onTimeForward: () -> Unit,
    modifier: Modifier = Modifier,
    channelColumnWidth: Dp = EPG_CHANNEL_COL_WIDTH_DP.dp,
    showGuide: Boolean? = null,
    favoriteIds: Set<String> = emptySet(),
    showEmptyMessage: Boolean = true,
    onGridFocusRequester: (FocusRequester) -> Unit = {},
    focusCenterInRoot: Float? = null,
) {
    var rowViewportTop by remember { mutableFloatStateOf(0f) }
    var rowViewportHeight by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { EPG_ROW_HEIGHT_DP.dp.toPx() }
    val rowCenter = (focusCenterInRoot?.minus(rowViewportTop) ?: (rowViewportHeight / 2f))
        .coerceIn(0f, rowViewportHeight)
    val centerBringIntoViewSpec = remember(rowCenter) {
        TvChannelCenterBringIntoViewSpec(rowCenter.takeIf { it > 0f })
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides centerBringIntoViewSpec) {
    BoxWithConstraints(modifier) {
    val slotMillis = EPG_SLOT_MINUTES * 60_000L
    val slotCount = ((windowEndMs - windowStartMs) / slotMillis).toInt().coerceAtLeast(1)
    val timelineWidth = (maxWidth - channelColumnWidth).coerceAtLeast(1.dp)
    val slotWidth = timelineWidth / slotCount
    val listState = rememberLazyListState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val guideCoverageCount = remember(channels, guideProgrammes, playlistId) {
        channels.count { enriched ->
            val lookupPlaylistId = enriched.channel.playlistId
                .takeIf { it.isNotBlank() }
                ?: playlistId.orEmpty()
            programmesForEpgChannel(
                programmesByChannelKey = guideProgrammes,
                playlistId = lookupPlaylistId,
                channel = enriched.channel,
            ).isNotEmpty()
        }
    }
    val useChannelListLayout = showGuide?.not() ?: (!isGuideLoading &&
        channels.isNotEmpty() &&
        guideCoverageCount * 5 < channels.size)

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val targetRow = focusRowIndex.coerceIn(0, (channels.lastIndex).coerceAtLeast(0))
    val targetCol = focusColIndex.coerceAtLeast(0)
    var lastVerticalMoveMs by remember { mutableLongStateOf(0L) }

    // Compose an offscreen restored row before its requester runs. Visible rows
    // use the same centre pivot for channel lists and the programme guide.
    LaunchedEffect(focusRequestToken, targetRow, channels.size, isFocusEnabled) {
        if (channels.isEmpty() || !isFocusEnabled) return@LaunchedEffect
        val layout = listState.layoutInfo
        if (layout.visibleItemsInfo.none { it.index == targetRow }) {
            val centerOffset = (rowCenter - rowHeightPx / 2f - layout.beforeContentPadding).toInt()
            runCatching { listState.scrollToItem(targetRow, -centerOffset) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .tvLiquidGlass(cornerRadius = 16.dp, quiet = true, baseTint = Obsidian.copy(alpha = .24f))
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
        if (useChannelListLayout) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Obsidian.copy(alpha = 0.78f))
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.tv_live_channels_list),
                    color = Snow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            EpgTimeHeader(
                windowStartMs = windowStartMs,
                slotCount = slotCount,
                slotMillis = slotMillis,
                channelColumnWidth = channelColumnWidth,
                slotWidth = slotWidth,
                nowMs = nowMs,
                windowEndMs = windowEndMs,
            )
        }

        if (channels.isEmpty()) {
            if (showEmptyMessage) {
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
            }
            return@Column
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("channels-row-viewport")
                    .onGloballyPositioned {
                        rowViewportTop = it.positionInRoot().y
                        rowViewportHeight = it.size.height.toFloat()
                    },
            ) {
                itemsIndexed(
                    items = channels,
                    key = { _, enriched -> tvChannelBrowseKey(enriched.channel) },
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
                    if (useChannelListLayout) {
                        val currentProgramme = programmes.firstOrNull {
                            nowMs >= it.startTime && nowMs < it.endTime
                        }
                        val nextProgramme = programmes.firstOrNull { it.startTime > nowMs }
                        CompactChannelRow(
                            rowNumber = rowIndex + 1,
                            channel = enriched,
                            programme = currentProgramme,
                            rowIndex = rowIndex,
                            focusTargetRow = targetRow,
                            focusRequestToken = focusRequestToken,
                            isFocusEnabled = isFocusEnabled,
                            isFavorite = stableChannelId(enriched.channel) in favoriteIds,
                            onFocused = { onChannelFocused(enriched.copy(currentProgramme = currentProgramme, nextProgramme = nextProgramme), currentProgramme) },
                            onGridCellFocused = { onGridCellFocused(rowIndex, 0) },
                            onFocusRequester = onGridFocusRequester,
                            onPlay = {
                                if (tvChannelPresentation(enriched.channel).status == TvChannelEventStatus.FINISHED)
                                    onProgrammeActions(enriched.channel, currentProgramme)
                                else onChannelPlay(enriched.channel)
                            },
                            onOpenActions = { onProgrammeActions(enriched.channel, currentProgramme) },
                        )
                    } else {
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
                            onChannelFocused = { programme -> onChannelFocused(enriched.copy(
                                currentProgramme = programmes.firstOrNull { nowMs >= it.startTime && nowMs < it.endTime },
                                nextProgramme = programmes.firstOrNull { it.startTime > nowMs }), programme) },
                            onGridCellFocused = { colIndex ->
                                onGridCellFocused(rowIndex, colIndex)
                            },
                            onFocusRequester = onGridFocusRequester,
                            onChannelPlay = { onChannelPlay(enriched.channel) },
                            onProgrammeActions = { programme ->
                                onProgrammeActions(enriched.channel, programme)
                            },
                            onTimeForward = onTimeForward,
                        )
                    }
                }
            }

            if (!useChannelListLayout && nowMs in windowStartMs..windowEndMs) {
                val nowFraction = ((nowMs - windowStartMs).toFloat() / (windowEndMs - windowStartMs).toFloat())
                    .coerceIn(0f, 1f)
                val nowOffsetDp = timelineWidth * nowFraction
                Box(
                    modifier = Modifier
                        .testTag("epg_current_time_line")
                        .offset(x = channelColumnWidth + nowOffsetDp, y = 0.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Amber.copy(alpha = 0.86f)),
                )
            }
        }
    }
    }
    }
}

@Composable
private fun CompactChannelRow(
    rowNumber: Int,
    channel: EnrichedChannel,
    programme: EpgProgramme?,
    rowIndex: Int,
    focusTargetRow: Int,
    focusRequestToken: Int,
    isFocusEnabled: Boolean,
    onFocused: () -> Unit,
    onGridCellFocused: () -> Unit,
    onFocusRequester: (FocusRequester) -> Unit,
    onPlay: () -> Unit,
    onOpenActions: () -> Unit,
    isFavorite: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    val display = remember(channel.channel) { tvChannelPresentation(channel.channel) }

    LaunchedEffect(focusRequestToken, focusTargetRow, rowIndex, isFocusEnabled) {
        if (!isFocusEnabled || rowIndex != focusTargetRow) return@LaunchedEffect
        delay(FOCUS_SETTLE_MS)
        runCatching { focusRequester.requestFocus() }
    }

    Surface(
        onClick = onPlay,
        modifier = Modifier
            .fillMaxWidth()
            .height(CHANNEL_LIST_ROW_HEIGHT_DP.dp)
            .padding(horizontal = 5.dp, vertical = 3.dp)
            .focusRequester(focusRequester)
            .focusProperties { canFocus = isFocusEnabled }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    onFocused()
                    onGridCellFocused()
                    onFocusRequester(focusRequester)
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Menu -> {
                        onOpenActions()
                        true
                    }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .tvLiquidGlass(isFocused, cornerRadius = 10.dp, focusScale = 1f, quiet = true)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = (channel.channel.channelNumber ?: rowNumber).toString(),
                color = Ash,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.widthIn(min = 40.dp).testTag("channel_number:${channel.channel.channelNumber ?: rowNumber}"),
            )
            TvChannelLogo(channel.channel, size = 32.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = display.title,
                    color = Snow,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = programme?.title?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(display.detail, display.startMs?.let {
                        SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(it))
                    }).joinToString(" · ").takeIf(String::isNotBlank)
                    ?: tvChannelCountry(channel.channel)?.let { resolveCountryName(it) ?: it }
                if (subtitle != null) Text(
                    text = subtitle,
                    color = Silver,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (display.status.label.isNotBlank()) Text(display.status.label,
                color = if (display.status == TvChannelEventStatus.LIVE) Color(0xFF8ED6B1) else Silver,
                fontSize = 10.sp, maxLines = 1)
            display.quality?.let { QualityBadge(it) }
            if (isFavorite) Icon(Icons.Filled.Star, contentDescription = localizedUiText("Favorite channel"), tint = Amber, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun EpgTimeHeader(
    windowStartMs: Long,
    slotCount: Int,
    slotMillis: Long,
    channelColumnWidth: Dp,
    slotWidth: Dp,
    nowMs: Long,
    windowEndMs: Long,
) {
    val timelineWidth = slotWidth * slotCount
    val showNow = windowEndMs > windowStartMs && nowMs in windowStartMs..windowEndMs
    val nowFraction = ((nowMs - windowStartMs).toFloat() / (windowEndMs - windowStartMs).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    val badgeWidth = 42.dp.coerceAtMost(timelineWidth)
    val badgeLeft = (timelineWidth * nowFraction - badgeWidth / 2).coerceIn(0.dp, (timelineWidth - badgeWidth).coerceAtLeast(0.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
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
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
        Box(Modifier.width(timelineWidth).fillMaxHeight().testTag("epg_timeline")) {
        Row {
            repeat(slotCount) { index ->
                val slotStart = windowStartMs + index * slotMillis
                Box(
                    modifier = Modifier
                        .width(slotWidth)
                        .fillMaxHeight()
                        .background(
                            if (index % 2 == 0) Charcoal.copy(alpha = 0.34f)
                            else Graphite.copy(alpha = 0.28f),
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val labelLeft = slotWidth * index + 8.dp
                    if (!showNow || labelLeft + 40.dp <= badgeLeft || labelLeft >= badgeLeft + badgeWidth) Text(
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
        if (showNow) Box(Modifier.offset(x = badgeLeft).width(badgeWidth).height(20.dp)
            .align(Alignment.CenterStart).testTag("epg_current_time")
            .background(Amber, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Text(epgTimeFormat.format(Date(nowMs)), color = Obsidian, fontSize = 11.sp,
                lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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
    onFocusRequester: (FocusRequester) -> Unit,
    onChannelPlay: () -> Unit,
    onProgrammeActions: (EpgProgramme?) -> Unit,
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
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val channelNumber = channel.channel.channelNumber ?: rowNumber
            Text(
                text = channelNumber.toString(),
                color = Ash,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.widthIn(min = 40.dp).testTag("channel_number:$channelNumber"),
            )
            TvChannelLogo(channel.channel, size = 26.dp)
            Text(
                text = remember(channel.channel) { tvChannelPresentation(channel.channel).title },
                color = Snow,
                fontSize = 12.sp,
                lineHeight = 15.sp,
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
                val cellWidth = (timelineWidth * cellFraction).coerceAtLeast(1.dp)
                EpgProgrammeCell(
                    programme = cell.programme,
                    fallbackTitle = if (isGuideLoading) "Loading schedule" else "Schedule unavailable",
                    width = cellWidth,
                    isNow = cell.isCurrentAt(nowMs),
                    isLastFocusableCell = index == cells.lastIndex,
                    canPageForward = canPageForward,
                    applyFocusTarget = rowIndex == focusTargetRow && index == targetColForRow,
                    focusRequestToken = focusRequestToken,
                    isFocusEnabled = isFocusEnabled,
                    onFocused = { onChannelFocused(cell.programme) },
                    onCellFocused = { onGridCellFocused(index) },
                    onFocusRequester = onFocusRequester,
                    onPlay = {
                        if (tvProgrammeNeedsDetails(cell.programme, System.currentTimeMillis())) onProgrammeActions(cell.programme)
                        else onChannelPlay()
                    },
                    onOpenActions = { onProgrammeActions(cell.programme) },
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
    onFocusRequester: (FocusRequester) -> Unit,
    onPlay: () -> Unit,
    onOpenActions: () -> Unit,
    onTimeForward: () -> Unit,
) {
    val cellFocusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    // Single focus request per cell: only fires when this cell becomes the
    // explicit target. The short settle delay lets the LazyColumn compose
    // the target row before we request focus.
    LaunchedEffect(focusRequestToken, applyFocusTarget, isFocusEnabled) {
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
                    onFocusRequester(cellFocusRequester)
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Menu -> {
                        onOpenActions()
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .tvLiquidGlass(isFocused, selected = isNow, cornerRadius = 9.dp, focusScale = 1f, quiet = true)
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

internal fun buildWindowCells(
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

    // Feed entries may overlap or repeat. Lay out non-overlapping display slots, while
    // retaining original programme times for details and replay.
    guideProgrammeSlots(programmes).forEach { slot ->
        if (slot.endTime <= windowStartMs) return@forEach
        if (slot.startTime >= windowEndMs) return@forEach
        val start = slot.startTime.coerceAtLeast(windowStartMs)
        val end = slot.endTime.coerceAtMost(windowEndMs)
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
            programme = slot.programme,
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
