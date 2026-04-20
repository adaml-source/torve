package com.torve.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.R
import com.torve.android.ui.components.QualityBadge
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.data.addon.ParsedStream
import com.torve.domain.model.StartupCandidate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamPickerSheet(
    streams: List<ParsedStream>,
    startupCandidates: List<StartupCandidate> = emptyList(),
    isResolving: Boolean,
    isLoadingMoreSources: Boolean = false,
    playbackStartupStatus: com.torve.presentation.detail.PlaybackStartupStatus? = null,
    onStreamSelected: (ParsedStream) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val groups = groupPlaybackOptionStreams(streams, startupCandidates)
    val startupCandidateMap = startupCandidates.associateBy { it.streamKey }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Charcoal,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        Torve.colors.border,
                        RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.picker_select_stream),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Snow,
                    )
                    Text(
                        text = stringResource(R.string.picker_streams_found, streams.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = Torve.colors.textTertiary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = Torve.colors.textTertiary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            DetailPlaybackReadinessCard(
                streams = streams,
                startupCandidates = startupCandidates,
                startupStatus = playbackStartupStatus,
                isLoadingStreams = false,
                isResolving = isResolving,
                isLoadingMoreSources = isLoadingMoreSources,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (isResolving) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = Amber,
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.stream_resolving),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Torve.colors.textSecondary,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    groups.forEach { group ->
                        item {
                            GroupHeader(group.title, group.subtitle)
                        }
                        items(group.items) { stream ->
                            StreamItem(
                                stream = stream,
                                startupCandidate = startupCandidateMap[stream.streamUiKey()],
                                onClick = { onStreamSelected(stream) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, subtitle: String?) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Amber,
            fontWeight = FontWeight.SemiBold,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = Torve.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun StreamItem(
    stream: ParsedStream,
    startupCandidate: StartupCandidate?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = Graphite,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Score badge
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = when {
                            stream.score >= 80 -> Emerald.copy(alpha = 0.2f)
                            stream.score >= 60 -> Amber.copy(alpha = 0.2f)
                            else -> Torve.colors.border
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${stream.score}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = when {
                        stream.score >= 80 -> Emerald
                        stream.score >= 60 -> Amber
                        else -> Torve.colors.textTertiary
                    },
                )
            }

            Spacer(Modifier.width(8.dp))

            // Quality badge
            QualityBadge(quality = stream.quality)

            Spacer(Modifier.width(10.dp))

            // Stream info
            Column(modifier = Modifier.weight(1f)) {
                StreamReadinessLabel(
                    stream = stream,
                    startupCandidate = startupCandidate,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                // Stremio Stream.title may be multi-line. Panda puts resolution,
                // size, video codec, and audio-language tags (e.g. 🗣️ DE, EN) on
                // subsequent lines — rendering only the first line drops the
                // language info entirely.
                val titleLines = stream.title.split('\n')
                Text(
                    text = titleLines.first(),
                    style = MaterialTheme.typography.titleSmall,
                    color = Snow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (titleLines.size > 1) {
                    Text(
                        text = titleLines.drop(1).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Silver,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    stream.size?.let { MetaChip(it) }
                    if (!stream.codec.isNullOrBlank()) MetaChip(stream.codec!!)
                    stream.hdr?.let { MetaChip(it) }
                    stream.audioCodec?.let { MetaChip(it) }
                    if (stream.languages.isNotEmpty()) {
                        MetaChip("\uD83D\uDDE3 " + stream.languages.joinToString(", "))
                    }
                    stream.seeds?.let { MetaChip("$it seeds") }
                }
                Spacer(Modifier.height(6.dp))
                StreamExperienceBadges(
                    stream = stream,
                    startupCandidate = startupCandidate,
                )
            }

            Spacer(Modifier.width(8.dp))

            // Addon name + cached indicator
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stream.addonName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                )
                if (stream.isCached) {
                    Spacer(Modifier.height(2.dp))
                    Icon(
                        Icons.Rounded.CloudDone,
                        contentDescription = stringResource(R.string.picker_cached),
                        modifier = Modifier.size(14.dp),
                        tint = Emerald,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Torve.colors.textTertiary,
    )
}
