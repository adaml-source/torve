package com.torve.desktop.ui.v2.recording

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2FloatingBackButton
import com.torve.desktop.ui.v2.live.LiveTvChannelLogo
import com.torve.desktop.ui.v2.live.LiveTvGlassButton
import com.torve.desktop.ui.v2.live.LiveTvGlassPanel
import com.torve.desktop.ui.v2.live.LiveTvPanelTitle
import com.torve.desktop.ui.v2.live.LiveTvPlayButton
import com.torve.desktop.ui.v2.live.LiveTvQualityBadge
import com.torve.desktop.ui.v2.live.LiveTvStatusPill
import com.torve.desktop.ui.v2.live.LiveTvTabPill
import com.torve.desktop.ui.v2.live.PremiumVerticalScrollbar
import com.torve.desktop.ui.v2.live.qualityBadgesFor
import com.torve.domain.model.LiveTvChannelLogoResolver
import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingEpgMatchStatus
import com.torve.domain.recording.RecordingFailureReason
import com.torve.domain.recording.RecordingKind
import com.torve.domain.recording.RecordingStatus
import com.torve.presentation.recording.RecordingsViewModel
import org.koin.mp.KoinPlatform
import java.awt.Desktop
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class RecordingFilter { ALL, SCHEDULED, RECORDING, COMPLETED, FAILED }

@Composable
fun V2RecordingsPage(
    onBack: () -> Unit,
    onPlayLocal: (Recording) -> Unit,
) {
    val vm = remember { KoinPlatform.getKoin().get<RecordingsViewModel>() }
    val state by vm.state.collectAsState()
    var filter by remember { mutableStateOf(RecordingFilter.ALL) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val allRows = state.allRows().sortedByDescending { it.startedAtMs ?: it.startMs }
    val rows = remember(allRows, filter) {
        allRows.filter { row ->
            when (filter) {
                RecordingFilter.ALL -> true
                RecordingFilter.SCHEDULED -> row.status == RecordingStatus.SCHEDULED
                RecordingFilter.RECORDING -> row.status == RecordingStatus.RECORDING
                RecordingFilter.COMPLETED -> row.status == RecordingStatus.COMPLETED
                RecordingFilter.FAILED -> row.status == RecordingStatus.FAILED || row.status == RecordingStatus.CANCELLED
            }
        }
    }
    val selected = rows.firstOrNull { it.id == selectedId } ?: rows.firstOrNull() ?: allRows.firstOrNull()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to Color(0xFF050914),
                    0.48f to Color(0xFF07111F),
                    1.0f to TorveDesktopThemeTokens.colors.shellBackground,
                ),
            )
            .padding(start = 72.dp, end = 30.dp, top = 20.dp, bottom = 20.dp),
    ) {
        val listWidth = if (maxWidth < 1180.dp) 360.dp else 430.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RecordingsHeader(onBack = onBack)
            RecordingTabs(
                selected = filter,
                allRows = allRows,
                onSelect = { next ->
                    filter = next
                    selectedId = null
                },
            )
            if (allRows.isEmpty()) {
                LiveTvGlassPanel(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "No recordings yet",
                            color = Color.White.copy(alpha = 0.94f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Schedule one from the Live TV guide or use Record Now while watching a channel.",
                            color = Color.White.copy(alpha = 0.66f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    RecordingList(
                        rows = rows,
                        selectedId = selected?.id,
                        onSelect = { selectedId = it.id },
                        modifier = Modifier.width(listWidth).fillMaxHeight(),
                    )
                    RecordingDetailCard(
                        row = selected,
                        onPlayLocal = onPlayLocal,
                        onDelete = { row -> vm.delete(row.id) },
                        onCancel = { row -> vm.cancel(row.id) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = ds("My Recordings"),
                color = Color.White.copy(alpha = 0.98f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Scheduled, active, completed, and failed IPTV recordings.",
                color = Color.White.copy(alpha = 0.70f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        V2FloatingBackButton(onBack = onBack, contentDescription = ds("Back"))
    }
}

@Composable
private fun RecordingTabs(
    selected: RecordingFilter,
    allRows: List<Recording>,
    onSelect: (RecordingFilter) -> Unit,
) {
    val counts = mapOf(
        RecordingFilter.ALL to allRows.size,
        RecordingFilter.SCHEDULED to allRows.count { it.status == RecordingStatus.SCHEDULED },
        RecordingFilter.RECORDING to allRows.count { it.status == RecordingStatus.RECORDING },
        RecordingFilter.COMPLETED to allRows.count { it.status == RecordingStatus.COMPLETED },
        RecordingFilter.FAILED to allRows.count { it.status == RecordingStatus.FAILED || it.status == RecordingStatus.CANCELLED },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordingFilter.entries.forEach { tab ->
            val label = when (tab) {
                RecordingFilter.ALL -> "All"
                RecordingFilter.SCHEDULED -> "Scheduled"
                RecordingFilter.RECORDING -> "Recording"
                RecordingFilter.COMPLETED -> "Completed"
                RecordingFilter.FAILED -> "Failed"
            }
            LiveTvTabPill(
                text = "$label ${counts.getValue(tab)}",
                selected = selected == tab,
                onClick = { onSelect(tab) },
                contentDescription = "$label recordings",
            )
        }
        Spacer(Modifier.weight(1f))
        LiveTvTabPill(text = "Sort: Newest first", selected = false, onClick = {}, contentDescription = "Sort newest first")
    }
}

@Composable
private fun RecordingList(
    rows: List<Recording>,
    selectedId: String?,
    onSelect: (Recording) -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveTvGlassPanel(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveTvPanelTitle("Recordings", "${rows.size} visible")
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    items(rows, key = { it.id }) { row ->
                        RecordingListCard(
                            row = row,
                            selected = row.id == selectedId,
                            onClick = { onSelect(row) },
                        )
                    }
                }
                PremiumVerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun RecordingListCard(
    row: Recording,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = focused || hovered
    val failed = row.status == RecordingStatus.FAILED
    val scale by animateFloatAsState(if (active) 1.015f else 1f, tween(140), label = "recordingCardScale")
    val background by animateColorAsState(
        when {
            selected -> colors.accent.copy(alpha = 0.14f)
            active -> Color(0xFF111C2F).copy(alpha = 0.70f)
            else -> Color(0xFF08111F).copy(alpha = 0.45f)
        },
        tween(140),
        label = "recordingCardBg",
    )
    val border by animateColorAsState(
        when {
            failed -> Color(0xFFFF6B6B).copy(alpha = if (selected || active) 0.58f else 0.34f)
            selected -> colors.accent.copy(alpha = 0.62f)
            active -> colors.accent.copy(alpha = 0.56f)
            else -> Color.White.copy(alpha = 0.10f)
        },
        tween(140),
        label = "recordingCardBorder",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .focusable(true, interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        color = background,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.height(112.dp).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecordingArtwork(row, compact = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = row.displayTitle,
                    color = Color.White.copy(alpha = 0.96f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.channelName,
                    color = Color.White.copy(alpha = 0.66f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatDate(row.startMs)} - ${formatDuration(row.durationMs)}",
                    color = Color.White.copy(alpha = 0.56f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            RecordingStatusPill(row.status)
        }
    }
}

@Composable
private fun RecordingDetailCard(
    row: Recording?,
    onPlayLocal: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
    onCancel: (Recording) -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveTvGlassPanel(modifier) {
        if (row == null) {
            Text("No recording selected", color = Color.White.copy(alpha = 0.78f))
            return@LiveTvGlassPanel
        }
        val scrollState = rememberScrollState()
        var showDetails by remember(row.id) { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 10.dp)
                    .verticalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                RecordingArtwork(row, compact = false)
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecordingStatusPill(row.status)
                        LiveTvQualityBadge(recordingKindLabel(row))
                        qualityBadgesFor(row.channelName).forEach { LiveTvQualityBadge(it) }
                    }
                    Text(
                        text = row.displayTitle,
                        color = Color.White.copy(alpha = 0.98f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = row.channelName,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RecordingMetadata(row)
                    row.programmeDescription?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = Color.White.copy(alpha = 0.68f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(
                    modifier = Modifier.widthIn(min = 300.dp, max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RecordingStatusPanel(row, showDetails = showDetails, onToggleDetails = { showDetails = !showDetails })
                    RecordingActions(
                        row = row,
                        onPlayLocal = onPlayLocal,
                        onDelete = onDelete,
                        onCancel = onCancel,
                    )
                }
            }
            PremiumVerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun RecordingArtwork(row: Recording, compact: Boolean) {
    val size = if (compact) 74.dp else 240.dp
    val failed = row.status == RecordingStatus.FAILED
    Surface(
        modifier = Modifier.size(size).clip(RoundedCornerShape(if (compact) 16.dp else 24.dp)),
        color = if (failed) Color(0xFF2B1114).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.07f),
        shape = RoundedCornerShape(if (compact) 16.dp else 24.dp),
        border = BorderStroke(1.dp, if (failed) Color(0xFFFF6B6B).copy(alpha = 0.34f) else Color.White.copy(alpha = 0.10f)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val art = row.epgProgrammeIconUrl
            if (!art.isNullOrBlank() && !failed) {
                val logo = remember(row.displayTitle, art) {
                    LiveTvChannelLogoResolver.resolveLogo(
                        channelName = row.displayTitle,
                        epgIconUrl = art,
                    )
                }
                LiveTvChannelLogo(
                    logo = logo,
                    channelName = row.displayTitle,
                    modifier = Modifier.size(size * 0.72f),
                    maxLogoWidth = size * 0.68f,
                    maxLogoHeight = size * 0.42f,
                )
            } else if (failed) {
                Icon(Icons.Filled.Warning, null, tint = Color(0xFFFF9C9C), modifier = Modifier.size(if (compact) 28.dp else 72.dp))
            } else {
                val logo = remember(row.channelName) {
                    LiveTvChannelLogoResolver.resolveLogo(channelName = row.channelName)
                }
                LiveTvChannelLogo(
                    logo = logo,
                    channelName = row.channelName,
                    modifier = Modifier.size(size * 0.72f),
                    maxLogoWidth = size * 0.58f,
                    maxLogoHeight = size * 0.38f,
                )
            }
        }
    }
}

@Composable
private fun RecordingMetadata(row: Recording) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        MetadataLine("Date", formatDate(row.startMs))
        MetadataLine("Time", formatTimeRange(row.startMs, row.endMs))
        MetadataLine("Duration", formatDuration(row.durationMs))
        MetadataLine("Type", recordingKindLabel(row))
        row.startedAtMs?.let { MetadataLine("Started", formatDateTime(it)) }
        row.completedAtMs?.let { MetadataLine("Ended", formatDateTime(it)) }
        row.fileSizeBytes?.let { MetadataLine("File size", formatFileSize(it)) }
        row.epgProgrammeCategory?.let { MetadataLine("Category", it) }
        if (row.epgMatchStatus == RecordingEpgMatchStatus.NO_MATCH_AT_START) {
            MetadataLine("Guide", "No match at recording start")
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.52f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(82.dp))
        Text(value, color = Color.White.copy(alpha = 0.84f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecordingStatusPanel(
    row: Recording,
    showDetails: Boolean,
    onToggleDetails: () -> Unit,
) {
    val failed = row.status == RecordingStatus.FAILED
    Surface(
        color = if (failed) Color(0xFF2B1114).copy(alpha = 0.58f) else Color(0xFF08111F).copy(alpha = 0.48f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (failed) Color(0xFFFF6B6B).copy(alpha = 0.36f) else Color.White.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (failed) "Recording failed" else statusTitle(row.status),
                color = Color.White.copy(alpha = 0.96f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = userFacingStatusBody(row),
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (row.failureReason != null || row.failureMessage != null) {
                LiveTvGlassButton(
                    text = if (showDetails) "Hide details" else "Details",
                    onClick = onToggleDetails,
                )
                if (showDetails) {
                    Text(
                        text = safeFailureDetails(row),
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingActions(
    row: Recording,
    onPlayLocal: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
    onCancel: (Recording) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (row.filePath != null && row.status == RecordingStatus.COMPLETED) {
            LiveTvPlayButton(
                text = "Play",
                onClick = { onPlayLocal(row) },
                modifier = Modifier.fillMaxWidth(),
                contentDescription = "Play recording",
            )
        }
        if (row.status == RecordingStatus.SCHEDULED || row.status == RecordingStatus.RECORDING) {
            LiveTvGlassButton(
                text = "Cancel",
                onClick = { onCancel(row) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (row.filePath != null) {
            LiveTvGlassButton(
                text = "Open folder",
                icon = Icons.Filled.FolderOpen,
                onClick = { openRecordingFolder(row) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LiveTvGlassButton(
            text = "Delete",
            icon = Icons.Filled.Delete,
            onClick = { onDelete(row) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RecordingStatusPill(status: RecordingStatus) {
    val color = when (status) {
        RecordingStatus.SCHEDULED -> Color(0xFF7CB7FF)
        RecordingStatus.RECORDING -> Color(0xFFFFB23F)
        RecordingStatus.COMPLETED -> Color(0xFF70E1A0)
        RecordingStatus.FAILED -> Color(0xFFFF6B6B)
        RecordingStatus.CANCELLED -> Color.White.copy(alpha = 0.62f)
    }
    val label = when (status) {
        RecordingStatus.SCHEDULED -> "Scheduled"
        RecordingStatus.RECORDING -> "Recording"
        RecordingStatus.COMPLETED -> "Completed"
        RecordingStatus.FAILED -> "Failed"
        RecordingStatus.CANCELLED -> "Cancelled"
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = CircleShape,
        border = BorderStroke(1.dp, color.copy(alpha = 0.48f)),
    ) {
        Row(Modifier.height(30.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = color.copy(alpha = 0.95f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun recordingKindLabel(row: Recording): String = when (row.recordingKind) {
    RecordingKind.LIVE -> if (row.epgMatchStatus == RecordingEpgMatchStatus.MATCHED) "Live EPG recording" else "Live recording"
    RecordingKind.SCHEDULED_EPG -> "Scheduled EPG recording"
    RecordingKind.SERIES_EPG -> "Series EPG recording"
}

private fun statusTitle(status: RecordingStatus): String = when (status) {
    RecordingStatus.SCHEDULED -> "Recording scheduled"
    RecordingStatus.RECORDING -> "Recording now"
    RecordingStatus.COMPLETED -> "Recording complete"
    RecordingStatus.FAILED -> "Recording failed"
    RecordingStatus.CANCELLED -> "Recording cancelled"
}

private fun userFacingStatusBody(row: Recording): String = when (row.status) {
    RecordingStatus.FAILED -> when (row.failureReason) {
        RecordingFailureReason.FILE_WRITE_ERROR ->
            "Torve could not write to the selected recordings folder. Check that the folder exists and Torve has permission to write there."
        RecordingFailureReason.OUT_OF_ALLOWLIST ->
            "The selected recordings folder is not allowed. Choose a valid folder in Settings."
        RecordingFailureReason.DISK_FULL ->
            "Recording storage is full or unavailable. Free up space or choose another folder."
        RecordingFailureReason.NETWORK_ERROR ->
            "The channel stream stopped before the recording finished."
        RecordingFailureReason.UPSTREAM_REJECTED ->
            "The channel did not provide recording data."
        RecordingFailureReason.CANCELLED_BY_USER ->
            "The recording was cancelled."
        RecordingFailureReason.UNKNOWN, null ->
            "Torve could not complete this recording."
    }
    RecordingStatus.SCHEDULED -> "Torve will start this recording at the scheduled time."
    RecordingStatus.RECORDING -> "Torve is writing this channel to your recordings folder."
    RecordingStatus.COMPLETED -> "The recording is saved locally and ready to play."
    RecordingStatus.CANCELLED -> "This recording was cancelled before it completed."
}

private fun safeFailureDetails(row: Recording): String {
    val code = row.failureReason?.name ?: "UNKNOWN"
    val folder = row.failureMessage
        ?.substringAfter("Folder:", missingDelimiterValue = "")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return buildString {
        append("Code: ")
        append(code)
        folder?.let {
            append('\n')
            append("Folder: ")
            append(it)
        }
    }
}

private fun openRecordingFolder(row: Recording) {
    val folder = row.filePath?.let { File(it).parentFile } ?: return
    runCatching {
        if (folder.exists() && Desktop.isDesktopSupported()) Desktop.getDesktop().open(folder)
    }
}

private val DateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d").withZone(ZoneId.systemDefault())
private val DateTimeFormatterFull: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d HH:mm").withZone(ZoneId.systemDefault())
private val TimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun formatDate(ms: Long): String = DateFormatter.format(Instant.ofEpochMilli(ms))
private fun formatDateTime(ms: Long): String = DateTimeFormatterFull.format(Instant.ofEpochMilli(ms))
private fun formatTimeRange(startMs: Long, endMs: Long): String =
    "${TimeFormatter.format(Instant.ofEpochMilli(startMs))} to ${TimeFormatter.format(Instant.ofEpochMilli(endMs))}"

private fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0)
    val hours = minutes / 60
    val rem = minutes % 60
    return if (hours > 0) "${hours}h ${rem}m" else "${rem}m"
}

private fun formatFileSize(bytes: Long): String {
    val mb = bytes / (1024L * 1024L)
    return if (mb >= 1024) "${mb / 1024}.${(mb % 1024) / 102} GB" else "$mb MB"
}
