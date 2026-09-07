package com.torve.android.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.BuildConfig
import com.torve.android.R
import com.torve.domain.player.PlaybackSegment
import com.torve.domain.player.SegmentType

@Composable
internal fun PlaybackSegmentOverlay(
    activeSegment: PlaybackSegment?,
    segments: List<PlaybackSegment>,
    positionMs: Long,
    focusCoordinator: PlaybackFocusCoordinator,
    onSkip: (PlaybackSegment) -> Unit,
) {
    activeSegment?.let { segment ->
        val requester = remember(segment.id) { FocusRequester() }
        RegisterFocusRegion(focusCoordinator, PlaybackFocusRegion.SegmentAction) {
            runCatching { requester.requestFocus() }.isSuccess
        }
        Button(
            onClick = { onSkip(segment) },
            modifier = Modifier
                .padding(end = 24.dp, bottom = 80.dp)
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused) focusCoordinator.reportFocusedRegion(PlaybackFocusRegion.SegmentAction, segment.id)
                }
                .focusable(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.9f),
                contentColor = Color.Black,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = stringResource(if (segment.type == SegmentType.RECAP) R.string.player_skip_recap else R.string.player_skip_intro),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    if (BuildConfig.DEBUG && segments.isNotEmpty()) {
        val current = segments.firstOrNull { it.contains(positionMs) }
        Column(
            modifier = Modifier
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("Segment: ${current?.type ?: SegmentType.UNKNOWN}", color = Color.White)
            current?.let { segment ->
                Text(
                    "${debugTime(segment.startMs)} -> ${debugTime(segment.endMs)}  ${segment.confidenceLevel}",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    segment.evidence.joinToString { "${it.detectorId} ${confidenceText(it.confidence)}" },
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun debugTime(ms: Long): String {
    val seconds = (ms / 1_000L).coerceAtLeast(0L)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun confidenceText(value: Double): String = ((value * 100).toInt() / 100.0).toString()
