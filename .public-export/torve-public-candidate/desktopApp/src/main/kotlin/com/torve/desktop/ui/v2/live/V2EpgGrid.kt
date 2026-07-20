package com.torve.desktop.ui.v2.live

import androidx.compose.foundation.background
import com.torve.desktop.ui.l10n.ds
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePlaceholderState
import com.torve.desktop.ui.components.TorveSearchField
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.model.Channel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.LiveTvEpgResolver
import com.torve.domain.model.LiveTvChannelLogoResolver
import com.torve.domain.model.canonicalEpgChannelKey
import com.torve.presentation.channels.GuideSortMode
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val PIXELS_PER_MINUTE = 4f
private val ChannelColumnWidth = 220.dp
private val RowHeight = 56.dp
private val RowSpacing = 2.dp
private val TimeHeaderHeight = 32.dp
private const val WINDOW_HOURS_BEHIND = 1L
private const val WINDOW_HOURS_AHEAD = 11L

@Composable
fun V2EpgGrid(
    playlistId: String?,
    guideChannels: List<EnrichedChannel>,
    guideProgrammes: Map<String, List<EpgProgramme>>,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    canCatchup: (Channel) -> Boolean,
    resolveCatchupUrl: (Channel, EpgProgramme) -> String?,
    onPlayChannel: (Channel) -> Unit,
    onPlayCatchup: (Channel, EpgProgramme, String) -> Unit,
    modifier: Modifier = Modifier,
    onSwitchToChannels: (() -> Unit)? = null,
    epgProgrammeCount: Int = 0,
    isFavorite: (Channel) -> Boolean = { false },
    onToggleFavorite: (Channel) -> Unit = {},
    isReminderSet: (Channel, EpgProgramme) -> Boolean = { _, _ -> false },
    onToggleReminder: (Channel, EpgProgramme) -> Unit = { _, _ -> },
    /**
     * Per-slot recording status - drives the small pill on the row and
     * the Record / Cancel-recording label in the programme dropdown.
     * Defaults to NONE so callers that don't wire DVR keep working.
     */
    recordingStatusFor: (Channel, EpgProgramme) -> com.torve.presentation.recording.RecordingSlotStatus =
        { _, _ -> com.torve.presentation.recording.RecordingSlotStatus.NONE },
    onToggleRecord: (Channel, EpgProgramme) -> Unit = { _, _ -> },
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    sortMode: GuideSortMode = GuideSortMode.NUMBER,
    onSortModeChange: (GuideSortMode) -> Unit = {},
) {
    val colors = TorveDesktopThemeTokens.colors

    // Apply search + sort to the channel list. Search matches channel
    // name (case-insensitive, substring). Sort modes:
    //   NUMBER    - playlist order (default; preserves Xtream catalogue
    //               ordering or m3u file order)
    //   NAME      - alphabetical
    //   EPG_FIRST - channels with EPG-matched programmes ahead of the
    //               empty rows, then alphabetical within each group
    val displayChannels: List<EnrichedChannel> = remember(
        guideChannels, guideProgrammes, searchQuery, sortMode,
    ) {
        val needle = searchQuery.trim().lowercase()
        val filtered = if (needle.isEmpty()) {
            guideChannels
        } else {
            guideChannels.filter { it.channel.name.lowercase().contains(needle) }
        }
        when (sortMode) {
            GuideSortMode.NUMBER -> filtered
            GuideSortMode.NAME -> filtered.sortedBy { it.channel.name.lowercase() }
            GuideSortMode.EPG_FIRST -> filtered.sortedWith(
                compareByDescending<EnrichedChannel> { enriched ->
                    val key = canonicalEpgChannelKey(
                        playlistId = playlistId.orEmpty(),
                        channel = enriched.channel,
                    )
                    !key.isNullOrBlank() && !guideProgrammes[key].isNullOrEmpty()
                }.thenBy { it.channel.name.lowercase() },
            )
        }
    }

    if (error != null && displayChannels.isEmpty()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TorvePlaceholderState(
                title = ds("Guide failed to load"),
                description = "Unable to load guide data right now.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveGhostButton(text = ds("Retry"), onClick = onRetry)
                onSwitchToChannels?.let {
                    TorveGhostButton(text = ds("Open Channels"), onClick = it)
                }
            }
        }
        return
    }

    if (isLoading && displayChannels.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            CircularProgressIndicator()
            Text(ds("Building guide..."), color = colors.textSecondary)
            Text(
                "Loading channels and matching them to EPG entries.",
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    if (guideChannels.isEmpty() || playlistId == null) {
        // Distinguish "EPG arrived but no channels matched" from "no EPG at all"
        // - the recovery action is different. With matched programmes, the user
        // needs to surface channels (load via Channels mode); without, they
        // need to configure an EPG URL.
        val hasEpg = epgProgrammeCount > 0
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TorvePlaceholderState(
                title = if (hasEpg) "No channels in your Guide yet" else "Guide is empty",
                description = if (hasEpg) {
                    "Your EPG has $epgProgrammeCount programmes loaded, but no channels are " +
                        "selected for the Guide yet. Open Channels, browse a category, and the " +
                        "matching channels will appear here automatically."
                } else {
                    "Guide data is still loading or this playlist does not have matched EPG entries yet."
                },
                emoji = if (hasEpg) "📺" else "📡",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onSwitchToChannels?.let {
                    TorveGhostButton(text = ds("Open Channels"), onClick = it)
                }
                TorveGhostButton(text = ds("Retry EPG"), onClick = onRetry)
            }
        }
        return
    }

    val nowMs = remember { System.currentTimeMillis() }
    val gridStartMs = remember(nowMs) {
        alignToHalfHour(nowMs - WINDOW_HOURS_BEHIND * 3_600_000L)
    }
    val gridEndMs = remember(gridStartMs) {
        gridStartMs + (WINDOW_HOURS_BEHIND + WINDOW_HOURS_AHEAD) * 3_600_000L
    }
    val totalMinutes = ((gridEndMs - gridStartMs) / 60_000L).toInt()
    val totalWidth: Dp = (totalMinutes * PIXELS_PER_MINUTE).dp

    val timeScroll = rememberScrollState()
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun jumpToNow() {
        val targetPx = with(density) {
            val minutesFromStart = ((nowMs - gridStartMs) / 60_000L).coerceAtLeast(0L)
            (minutesFromStart * PIXELS_PER_MINUTE).dp.toPx() - 120.dp.toPx()
        }.toInt().coerceAtLeast(0)
        coroutineScope.launch { timeScroll.animateScrollTo(targetPx) }
    }

    LaunchedEffect(playlistId, displayChannels.size) {
        // Initial jump so "now" is visible without manual scrolling.
        if (displayChannels.isNotEmpty()) jumpToNow()
    }

    Column(modifier = modifier.fillMaxSize()) {
        GuideControlsRow(
            nowMs = nowMs,
            channelCount = displayChannels.size,
            totalChannelCount = guideChannels.size,
            isLoading = isLoading,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            sortMode = sortMode,
            onSortModeChange = onSortModeChange,
            onJumpToNow = ::jumpToNow,
            onRefresh = onRetry,
        )

        Spacer(Modifier.height(8.dp))

        // Time header - fixed at top, scrolls horizontally with the grid.
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(ChannelColumnWidth).height(TimeHeaderHeight))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TimeHeaderHeight)
                    .horizontalScroll(timeScroll),
            ) {
                TimeAxisHeader(
                    gridStartMs = gridStartMs,
                    totalMinutes = totalMinutes,
                    totalWidth = totalWidth,
                )
            }
        }

        // "Find other airings" sheet: when the user picks that menu item we
        // capture the title and open a list of every matching airing across
        // the whole guideProgrammes map (different channels, different times).
        var otherAiringsTitle by remember { mutableStateOf<String?>(null) }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(RowSpacing),
            ) {
                itemsIndexed(
                    items = displayChannels,
                    key = { index, ch -> "${epgRowKey(ch)}#$index" },
                ) { _, enriched ->
                    val programmes = programmesFor(
                        playlistId = playlistId,
                        enriched = enriched,
                        guideProgrammes = guideProgrammes,
                        gridStartMs = gridStartMs,
                        gridEndMs = gridEndMs,
                    )
                    GuideRow(
                        channel = enriched.channel,
                        currentProgramme = enriched.currentProgramme,
                        programmes = programmes,
                        gridStartMs = gridStartMs,
                        gridEndMs = gridEndMs,
                        totalWidth = totalWidth,
                        timeScroll = timeScroll,
                        nowMs = nowMs,
                        canCatchup = canCatchup(enriched.channel),
                        isFavorite = isFavorite(enriched.channel),
                        isReminderSet = { p -> isReminderSet(enriched.channel, p) },
                        onPlayLive = { onPlayChannel(enriched.channel) },
                        onPlayFromStart = { p ->
                            val url = resolveCatchupUrl(enriched.channel, p)
                            if (!url.isNullOrBlank()) onPlayCatchup(enriched.channel, p, url)
                        },
                        onToggleFavorite = { onToggleFavorite(enriched.channel) },
                        onToggleReminder = { p -> onToggleReminder(enriched.channel, p) },
                        recordingStatusFor = { p -> recordingStatusFor(enriched.channel, p) },
                        onToggleRecord = { p -> onToggleRecord(enriched.channel, p) },
                        onFindOtherAirings = { p -> otherAiringsTitle = p.title },
                        onCopyInfo = { p ->
                            val text = buildString {
                                append(p.title)
                                append('\n')
                                append(enriched.channel.name)
                                append(" • ")
                                append(formatTimeRange(p.startTime, p.endTime))
                                p.description?.takeIf { it.isNotBlank() }?.let {
                                    append("\n\n"); append(it)
                                }
                            }
                            copyToClipboard(text)
                        },
                        onClickChannel = { onPlayChannel(enriched.channel) },
                    )
                }
            }

            // Now-indicator overlay - rendered on top of the grid, offset by scroll.
            NowIndicatorOverlay(
                nowMs = nowMs,
                gridStartMs = gridStartMs,
                gridEndMs = gridEndMs,
                timeScrollPx = timeScroll.value,
            )

            PremiumVerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            PremiumHorizontalScrollbar(
                adapter = rememberScrollbarAdapter(timeScroll),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = ChannelColumnWidth, end = 10.dp)
                    .fillMaxWidth(),
            )

            otherAiringsTitle?.let { title ->
                OtherAiringsSheet(
                    title = title,
                    nowMs = nowMs,
                    guideChannels = displayChannels,
                    guideProgrammes = guideProgrammes,
                    playlistId = playlistId,
                    onPlayChannel = onPlayChannel,
                    onDismiss = { otherAiringsTitle = null },
                )
            }
        }
    }
}

@Composable
private fun GuideControlsRow(
    nowMs: Long,
    channelCount: Int,
    totalChannelCount: Int,
    isLoading: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    sortMode: GuideSortMode,
    onSortModeChange: (GuideSortMode) -> Unit,
    onJumpToNow: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TorveBadge(
            text = formatDayLabel(nowMs),
            tone = TorveBadgeTone.Accent,
        )
        Text(
            text = if (channelCount == totalChannelCount) {
                "$channelCount channels"
            } else {
                "$channelCount of $totalChannelCount channels"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )

        Spacer(Modifier.width(4.dp))

        LiveTvSearchBar(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.width(260.dp),
        )

        Box {
            LiveTvGlassButton(
                text = when (sortMode) {
                    GuideSortMode.NUMBER -> ds("Sort: Number")
                    GuideSortMode.NAME -> ds("Sort: Name")
                    GuideSortMode.EPG_FIRST -> ds("Sort: EPG first")
                },
                onClick = { sortMenuExpanded = true },
            )
            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(ds("Number (playlist order)")) },
                    onClick = {
                        onSortModeChange(GuideSortMode.NUMBER)
                        sortMenuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(ds("Name (A→Z)")) },
                    onClick = {
                        onSortModeChange(GuideSortMode.NAME)
                        sortMenuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(ds("EPG-matched first")) },
                    onClick = {
                        onSortModeChange(GuideSortMode.EPG_FIRST)
                        sortMenuExpanded = false
                    },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.width(16.dp).height(16.dp),
                strokeWidth = 2.dp,
            )
        }
        LiveTvGlassButton(text = ds("Now"), onClick = onJumpToNow, contentDescription = "Jump to current time")
        LiveTvGlassButton(text = ds("Refresh"), onClick = onRefresh, enabled = !isLoading)
    }
}

@Composable
private fun TimeAxisHeader(
    gridStartMs: Long,
    totalMinutes: Int,
    totalWidth: Dp,
) {
    val colors = TorveDesktopThemeTokens.colors
    Box(Modifier.width(totalWidth).fillMaxHeight()) {
        // 30-minute tick marks. Labels every hour.
        var offsetMinutes = 0
        while (offsetMinutes <= totalMinutes) {
            val tickTimeMs = gridStartMs + offsetMinutes * 60_000L
            val xDp = (offsetMinutes * PIXELS_PER_MINUTE).dp
            val isHour = offsetMinutes % 60 == 0
            Column(
                modifier = Modifier.offset(x = xDp).fillMaxHeight(),
                verticalArrangement = Arrangement.Top,
            ) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(if (isHour) 10.dp else 6.dp)
                        .background(if (isHour) colors.borderStrong else colors.borderSubtle),
                )
                if (isHour) {
                    Text(
                        text = formatHourLabel(tickTimeMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }
            offsetMinutes += 30
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    currentProgramme: EpgProgramme?,
    programmes: List<EpgProgramme>,
    gridStartMs: Long,
    gridEndMs: Long,
    totalWidth: Dp,
    timeScroll: androidx.compose.foundation.ScrollState,
    nowMs: Long,
    canCatchup: Boolean,
    isFavorite: Boolean,
    isReminderSet: (EpgProgramme) -> Boolean,
    onPlayLive: () -> Unit,
    onPlayFromStart: (EpgProgramme) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleReminder: (EpgProgramme) -> Unit,
    recordingStatusFor: (EpgProgramme) -> com.torve.presentation.recording.RecordingSlotStatus,
    onToggleRecord: (EpgProgramme) -> Unit,
    onFindOtherAirings: (EpgProgramme) -> Unit,
    onCopyInfo: (EpgProgramme) -> Unit,
    onClickChannel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(RowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLabelCell(
            channel = channel,
            nowTitle = currentProgramme?.title,
            canCatchup = canCatchup,
            isFavorite = isFavorite,
            onClick = onClickChannel,
            modifier = Modifier.width(ChannelColumnWidth).fillMaxHeight(),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .horizontalScroll(timeScroll),
        ) {
            ProgrammeTrack(
                programmes = programmes,
                gridStartMs = gridStartMs,
                gridEndMs = gridEndMs,
                totalWidth = totalWidth,
                nowMs = nowMs,
                canCatchup = canCatchup,
                isFavorite = isFavorite,
                isReminderSet = isReminderSet,
                onPlayLive = onPlayLive,
                onPlayFromStart = onPlayFromStart,
                onToggleFavorite = onToggleFavorite,
                onToggleReminder = onToggleReminder,
                recordingStatusFor = recordingStatusFor,
                onToggleRecord = onToggleRecord,
                onFindOtherAirings = onFindOtherAirings,
                onCopyInfo = onCopyInfo,
            )
        }
    }
}

@Composable
private fun ChannelLabelCell(
    channel: Channel,
    nowTitle: String?,
    canCatchup: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val logo = remember(channel.name, channel.tvgLogo) {
        LiveTvChannelLogoResolver.resolveLogo(channel)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.cardSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveTvChannelLogo(
                logo = logo,
                channelName = channel.name,
                modifier = Modifier.size(34.dp),
                maxLogoWidth = 34.dp,
                maxLogoHeight = 26.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isFavorite) {
                TorveBadge("★", tone = TorveBadgeTone.Warning)
            }
            if (canCatchup) {
                TorveBadge("CU", tone = TorveBadgeTone.Accent)
            }
        }
        if (!nowTitle.isNullOrBlank()) {
            Text(
                text = nowTitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
            }
        }
    }
}

@Composable
private fun ProgrammeTrack(
    programmes: List<EpgProgramme>,
    gridStartMs: Long,
    gridEndMs: Long,
    totalWidth: Dp,
    nowMs: Long,
    canCatchup: Boolean,
    isFavorite: Boolean,
    isReminderSet: (EpgProgramme) -> Boolean,
    onPlayLive: () -> Unit,
    onPlayFromStart: (EpgProgramme) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleReminder: (EpgProgramme) -> Unit,
    recordingStatusFor: (EpgProgramme) -> com.torve.presentation.recording.RecordingSlotStatus,
    onToggleRecord: (EpgProgramme) -> Unit,
    onFindOtherAirings: (EpgProgramme) -> Unit,
    onCopyInfo: (EpgProgramme) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Box(Modifier.width(totalWidth).fillMaxHeight()) {
        programmes.forEach { programme ->
            // Clip the programme block to the grid window so very-long shows
            // don't push a 20-hour cell out of frame.
            val clippedStart = programme.startTime.coerceAtLeast(gridStartMs)
            val clippedEnd = programme.endTime.coerceAtMost(gridEndMs)
            if (clippedEnd <= clippedStart) return@forEach

            val startMinutes = ((clippedStart - gridStartMs) / 60_000L).toInt()
            val durationMinutes = ((clippedEnd - clippedStart) / 60_000L).toInt().coerceAtLeast(1)
            val xDp = (startMinutes * PIXELS_PER_MINUTE).dp
            val widthDp = (durationMinutes * PIXELS_PER_MINUTE).dp

            val isLive = programme.startTime <= nowMs && programme.endTime > nowMs
            val isPast = programme.endTime <= nowMs
            val isFuture = programme.startTime > nowMs
            val container = when {
                isLive -> colors.accentContainer
                isPast -> colors.fieldSurface
                else -> colors.cardSurface
            }
            val content = if (isPast) colors.textSecondary else colors.textPrimary
            val reminderActive = isReminderSet(programme)

            ProgrammeCell(
                programme = programme,
                xDp = xDp,
                widthDp = widthDp,
                container = container,
                contentColor = content,
                accentBorder = if (isLive) colors.accentContainerStrong else colors.borderSubtle,
                showReminderDot = reminderActive,
                isLive = isLive,
                isPast = isPast,
                isFuture = isFuture,
                canCatchup = canCatchup,
                isFavorite = isFavorite,
                recordingStatus = recordingStatusFor(programme),
                onPlayLive = onPlayLive,
                onPlayFromStart = { onPlayFromStart(programme) },
                onToggleFavorite = onToggleFavorite,
                onToggleReminder = { onToggleReminder(programme) },
                onToggleRecord = { onToggleRecord(programme) },
                onFindOtherAirings = { onFindOtherAirings(programme) },
                onCopyInfo = { onCopyInfo(programme) },
            )
        }
    }
}

@Composable
private fun ProgrammeCell(
    programme: EpgProgramme,
    xDp: Dp,
    widthDp: Dp,
    container: Color,
    contentColor: Color,
    accentBorder: Color,
    showReminderDot: Boolean,
    isLive: Boolean,
    isPast: Boolean,
    isFuture: Boolean,
    canCatchup: Boolean,
    isFavorite: Boolean,
    recordingStatus: com.torve.presentation.recording.RecordingSlotStatus,
    onPlayLive: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleReminder: () -> Unit,
    onToggleRecord: () -> Unit,
    onFindOtherAirings: () -> Unit,
    onCopyInfo: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    var menuOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .offset(x = xDp)
            .width(widthDp)
            .fillMaxHeight()
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(container)
            .border(
                width = 1.dp,
                color = accentBorder,
                shape = RoundedCornerShape(4.dp),
            )
            .clickable { menuOpen = true }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = programme.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatTimeRange(programme.startTime, programme.endTime),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = contentColor.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
            // Status pills are layered: recording state takes priority
            // over reminder when both are active so the user sees the
            // higher-stakes signal first.
            val recordingMark = when (recordingStatus) {
                com.torve.presentation.recording.RecordingSlotStatus.RECORDING -> "🔴"
                com.torve.presentation.recording.RecordingSlotStatus.SCHEDULED -> "⏺"
                com.torve.presentation.recording.RecordingSlotStatus.COMPLETED -> "✓"
                com.torve.presentation.recording.RecordingSlotStatus.FAILED -> "⚠"
                com.torve.presentation.recording.RecordingSlotStatus.CANCELLED -> "✕"
                com.torve.presentation.recording.RecordingSlotStatus.NONE -> null
            }
            recordingMark?.let { Text(it, fontSize = 10.sp) }
            if (showReminderDot && recordingStatus == com.torve.presentation.recording.RecordingSlotStatus.NONE) {
                Text("🔔", fontSize = 10.sp)
            }
        }

        // Programme context menu - Play, catchup, favorite, reminder, find
        // repeats, copy info. Anchored to the cell; auto-dismisses on outside
        // click.
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = colors.drawerSurface,
        ) {
            DropdownMenuItem(
                text = { Text("▶  Play channel") },
                onClick = { menuOpen = false; onPlayLive() },
            )
            if (isPast && canCatchup) {
                DropdownMenuItem(
                    text = { Text("⏮  Watch from start (catch-up)") },
                    onClick = { menuOpen = false; onPlayFromStart() },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (isFavorite) "★  Remove from favorites" else "☆  Add to favorites") },
                onClick = { menuOpen = false; onToggleFavorite() },
            )
            if (isFuture) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (showReminderDot) "🔕  Cancel reminder"
                            else "🔔  Set reminder",
                        )
                    },
                    onClick = { menuOpen = false; onToggleReminder() },
                )
            }
            // Record / cancel-record. Available for future programmes
            // (one-off schedule) and currently-airing slots; past
            // programmes route through Catch-up instead.
            if (isFuture || isLive) {
                val recordLabel = when (recordingStatus) {
                    com.torve.presentation.recording.RecordingSlotStatus.SCHEDULED ->
                        "✕  Cancel scheduled recording"
                    com.torve.presentation.recording.RecordingSlotStatus.RECORDING ->
                        "■  Stop recording"
                    com.torve.presentation.recording.RecordingSlotStatus.NONE ->
                        "⏺  Record this programme"
                    com.torve.presentation.recording.RecordingSlotStatus.COMPLETED ->
                        "✓  Recording saved"
                    com.torve.presentation.recording.RecordingSlotStatus.FAILED ->
                        "⚠  Recording failed - try again"
                    com.torve.presentation.recording.RecordingSlotStatus.CANCELLED ->
                        "⏺  Record this programme"
                }
                DropdownMenuItem(
                    text = { Text(recordLabel) },
                    onClick = { menuOpen = false; onToggleRecord() },
                    enabled = recordingStatus != com.torve.presentation.recording.RecordingSlotStatus.COMPLETED,
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("🔍  Find other airings") },
                onClick = { menuOpen = false; onFindOtherAirings() },
            )
            DropdownMenuItem(
                text = { Text("📋  Copy show info") },
                onClick = { menuOpen = false; onCopyInfo() },
            )
            // Read-only context - reminds the user what they're acting on.
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            programme.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatTimeRange(programme.startTime, programme.endTime),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                        )
                        programme.description?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it.take(180),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                onClick = { menuOpen = false },
                enabled = false,
            )
        }
    }
}

@Composable
private fun OtherAiringsSheet(
    title: String,
    nowMs: Long,
    guideChannels: List<EnrichedChannel>,
    guideProgrammes: Map<String, List<EpgProgramme>>,
    playlistId: String?,
    onPlayChannel: (Channel) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val matches = remember(title, guideChannels, guideProgrammes, playlistId) {
        // Walk every channel × every cached programme; case-insensitive title
        // equality. Sorted by start time so the upcoming airings come first.
        val pid = playlistId ?: return@remember emptyList()
        guideChannels.flatMap { enriched ->
            LiveTvEpgResolver.resolveProgrammes(enriched.channel, pid, guideProgrammes).asSequence()
                .filter { it.title.equals(title, ignoreCase = true) }
                .map { enriched.channel to it }
                .toList()
        }.sortedBy { it.second.startTime }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = colors.elevatedSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .width(520.dp)
                .heightIn(max = 480.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                .clickable(enabled = false, onClick = {}),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = ds("Other airings"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                if (matches.isEmpty()) {
                    Text(
                        text = ds("No other airings of this title were found in the loaded EPG window."),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(matches) { (channel, programme) ->
                            val isLive = programme.startTime <= nowMs && programme.endTime > nowMs
                            Surface(
                                color = colors.cardSurface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPlayChannel(channel)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 2.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = channel.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = formatTimeRange(programme.startTime, programme.endTime) +
                                                if (isLive) "  • Live now" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.textSecondary,
                                        )
                                    }
                                    if (isLive) TorveBadge("LIVE", tone = TorveBadgeTone.Accent)
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    TorveGhostButton(text = ds("Close"), onClick = onDismiss)
                }
            }
        }
    }
}

private fun copyToClipboard(text: String) {
    runCatching {
        val selection = java.awt.datatransfer.StringSelection(text)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }
}

@Composable
private fun NowIndicatorOverlay(
    nowMs: Long,
    gridStartMs: Long,
    gridEndMs: Long,
    timeScrollPx: Int,
) {
    if (nowMs < gridStartMs || nowMs > gridEndMs) return
    val density = LocalDensity.current
    val nowOffsetDp = remember(nowMs, gridStartMs) {
        val minutes = ((nowMs - gridStartMs) / 60_000L).toFloat()
        (minutes * PIXELS_PER_MINUTE).dp
    }
    val scrollDp = with(density) { timeScrollPx.toDp() }
    val leftOffset = ChannelColumnWidth + nowOffsetDp - scrollDp

    // Hide if we've scrolled past the indicator to the left of the time column.
    if (leftOffset < ChannelColumnWidth - 4.dp) return

    Box(
        modifier = Modifier
            .offset(x = leftOffset)
            .width(2.dp)
            .fillMaxHeight()
            .background(Color(0xFFE53935)),
    )
}

private fun programmesFor(
    playlistId: String,
    enriched: EnrichedChannel,
    guideProgrammes: Map<String, List<EpgProgramme>>,
    gridStartMs: Long,
    gridEndMs: Long,
): List<EpgProgramme> {
    return LiveTvEpgResolver.resolveProgrammesForRange(
        channel = enriched.channel,
        playlistId = playlistId,
        programmesByChannelKey = guideProgrammes,
        rangeStartMs = gridStartMs,
        rangeEndMs = gridEndMs,
    )
}

private fun epgRowKey(enriched: EnrichedChannel): String {
    val ch = enriched.channel
    return (ch.tvgId ?: ch.url).ifBlank { ch.name }
}

private fun alignToHalfHour(ms: Long): Long {
    val halfHour = 30L * 60_000L
    return (ms / halfHour) * halfHour
}

private val HourMinuteFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")
private val DayLabelFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM")

private fun formatHourLabel(ms: Long): String {
    return HourMinuteFormatter.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}

private fun formatTimeRange(startMs: Long, endMs: Long): String {
    val zone = ZoneId.systemDefault()
    val start = HourMinuteFormatter.format(Instant.ofEpochMilli(startMs).atZone(zone))
    val end = HourMinuteFormatter.format(Instant.ofEpochMilli(endMs).atZone(zone))
    return "$start-$end"
}

private fun formatDayLabel(ms: Long): String {
    val day = DayLabelFormatter.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    return "Today • $day"
}
