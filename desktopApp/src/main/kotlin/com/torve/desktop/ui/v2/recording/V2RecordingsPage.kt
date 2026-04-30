package com.torve.desktop.ui.v2.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePageHeader
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.recording.Recording
import com.torve.domain.recording.RecordingFailureReason
import com.torve.domain.recording.RecordingStatus
import com.torve.presentation.recording.RecordingsViewModel
import org.koin.mp.KoinPlatform

/**
 * "My Recordings" desktop surface (Prompt 10B).
 *
 * Three sections — active, completed, failed — sourced from
 * [RecordingsViewModel.state]. Each row carries title, channel,
 * window, duration, and action buttons:
 *
 *   * Active: Cancel.
 *   * Completed: Play (local file via `onPlayLocal`).
 *   * Failed: actionable copy from the recording's
 *     [RecordingFailureReason] + [Recording.failureMessage].
 */
@Composable
fun V2RecordingsPage(
    onBack: () -> Unit,
    onPlayLocal: (Recording) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val vm = remember {
        KoinPlatform.getKoin().get<RecordingsViewModel>()
    }
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TorvePageHeader(
            title = "My Recordings",
            subtitle = "Scheduled, active, completed, and failed IPTV recordings.",
            trailing = { TorveGhostButton(text = "Back", onClick = onBack) },
        )

        if (state.totalCount == 0) {
            Text(
                text = "No recordings yet. Schedule one from the EPG by right-clicking a programme.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            return@Column
        }

        if (state.active.isNotEmpty()) {
            RecordingSection(
                title = "Scheduled and recording",
                rows = state.active,
                action = { row ->
                    TorveGhostButton(text = "Cancel", onClick = { vm.cancel(row.id) })
                },
            )
        }
        if (state.completed.isNotEmpty()) {
            RecordingSection(
                title = "Completed",
                rows = state.completed,
                action = { row ->
                    val canPlay = row.filePath != null
                    TorvePrimaryButton(
                        text = "Play local",
                        onClick = { onPlayLocal(row) },
                        enabled = canPlay,
                    )
                },
            )
        }
        if (state.failed.isNotEmpty()) {
            RecordingSection(
                title = "Failed and cancelled",
                rows = state.failed,
                action = { /* read-only */ },
            )
        }
    }
}

@Composable
private fun RecordingSection(
    title: String,
    rows: List<Recording>,
    action: @Composable (Recording) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    TorveSectionCard(title = title, supportingText = "${rows.size} item(s)") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = row.programmeTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = "${row.channelName} • ${formatWindow(row.startMs, row.endMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        if (row.status == RecordingStatus.FAILED) {
                            Text(
                                text = "${row.failureReason?.name ?: "Failed"}: ${row.failureMessage ?: "Unknown"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.error,
                            )
                        }
                        row.fileSizeBytes?.let { bytes ->
                            Text(
                                text = "${bytes / (1024 * 1024)} MB on disk",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                    }
                    StatusPill(row.status)
                    Spacer(Modifier.width(4.dp))
                    action(row)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: RecordingStatus) {
    val (label, tone) = when (status) {
        RecordingStatus.SCHEDULED -> "Scheduled" to TorveBadgeTone.Accent
        RecordingStatus.RECORDING -> "Recording" to TorveBadgeTone.Live
        RecordingStatus.COMPLETED -> "Done" to TorveBadgeTone.Success
        RecordingStatus.FAILED -> "Failed" to TorveBadgeTone.Error
        RecordingStatus.CANCELLED -> "Cancelled" to TorveBadgeTone.Neutral
    }
    TorveBadge(text = label, tone = tone)
}

private fun formatWindow(startMs: Long, endMs: Long): String {
    val fmt = java.time.format.DateTimeFormatter
        .ofPattern("EEE HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
    val start = fmt.format(java.time.Instant.ofEpochMilli(startMs))
    val end = fmt.format(java.time.Instant.ofEpochMilli(endMs))
    return "$start – $end"
}
