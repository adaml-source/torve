package com.torve.android.ui.product

import com.torve.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.presentation.watching.*
import com.torve.presentation.detail.playbackPositionLabel

@Composable
internal fun MobileUpNextShelf(state: WatchingHubState, onEpisode: (WatchingEpisode) -> Unit, onAll: () -> Unit,
    returningViewer: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("home_up_next")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.flow_up_next), Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onAll, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.flow_your_shows)) }
        }
        if (returningViewer) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.upNext.take(3).forEach { entry ->
                    val episode = entry.presentedEpisode(state.hideSpoilers)
                    Surface(onClick = { onEpisode(entry) }, shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().testTag("home_next_${entry.show.id}_${entry.season}_${episode.episodeNumber}")) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(88.dp, 56.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Tv, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                AsyncImage(episode.stillUrl ?: entry.show.backdropUrl ?: entry.show.posterUrl,
                                    null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                com.torve.android.ui.community.RemainingEpisodesBadge(entry.remainingAired, state.showRemainingEpisodes, Modifier.align(Alignment.TopEnd).padding(6.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(entry.show.title, style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("S${entry.season} E${episode.episodeNumber} · ${episode.name}",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (entry.resume && entry.positionMs != null)
                                    stringResource(R.string.flow_resume_at, playbackPositionLabel(entry.positionMs!!))
                                    else stringResource(R.string.flow_open_episode),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                entry.progressPercent?.let { progress ->
                                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(2.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (state.upNext.size > 3) TextButton(onClick = onAll, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.gap_home_more_shows, state.upNext.size))
                }
            }
            return@Column
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.upNext, key = { "${it.show.id}:${it.season}:${it.episode.episodeNumber}" }) { entry ->
                val episode = entry.presentedEpisode(state.hideSpoilers)
                Surface(onClick = { onEpisode(entry) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.width(244.dp).testTag("home_next_${entry.show.id}_${entry.season}_${episode.episodeNumber}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            AsyncImage(episode.stillUrl ?: entry.show.backdropUrl ?: entry.show.posterUrl, contentDescription = null,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                com.torve.android.ui.community.RemainingEpisodesBadge(entry.remainingAired, state.showRemainingEpisodes, Modifier.align(Alignment.TopEnd).padding(6.dp))
                        }
                        Text(entry.show.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("S${entry.season} E${episode.episodeNumber} · ${episode.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (entry.resume) stringResource(R.string.flow_resume_episode) else stringResource(R.string.flow_open_episode), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
