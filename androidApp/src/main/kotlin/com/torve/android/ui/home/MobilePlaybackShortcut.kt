package com.torve.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.presentation.detail.playbackPositionLabel
import com.torve.presentation.watching.WatchingEpisode

@Composable
internal fun MobilePlaybackShortcut(
    progress: WatchProgress?,
    next: WatchingEpisode?,
    onResume: (WatchProgress) -> Unit,
    onEpisode: (WatchingEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (progress == null && next == null) return
    val title = progress?.showTitle?.takeIf { it.isNotBlank() } ?: progress?.title ?: next!!.show.title
    val season = progress?.seasonNumber ?: next?.season
    val episode = progress?.episodeNumber ?: next?.episode?.episodeNumber
    val resume = progress != null || next?.resume == true
    val episodeLabel = if (season != null && episode != null) {
        stringResource(if (resume) R.string.detail_resume_episode else R.string.detail_watch_episode, season, episode)
    } else if (progress != null) stringResource(R.string.detail_resume_position, playbackPositionLabel(progress.positionMs))
    else stringResource(R.string.detail_watch_now)
    val artwork = progress?.backdropUrl ?: progress?.posterUrl ?: next?.show?.backdropUrl ?: next?.show?.posterUrl
    Card(
        onClick = { if (progress != null) onResume(progress) else next?.let(onEpisode) },
        modifier = modifier.fillMaxWidth().testTag("home_playback_shortcut"),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(width = 76.dp, height = 64.dp).clip(RoundedCornerShape(8.dp)).background(Gunmetal),
                contentAlignment = Alignment.Center) {
                Icon(if (progress?.mediaType == MediaType.MOVIE) Icons.Rounded.Movie else Icons.Rounded.Tv,
                    null, tint = Torve.colors.textTertiary)
                AsyncImage(model = artwork, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(episodeLabel, color = Amber, style = MaterialTheme.typography.labelLarge)
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, color = Torve.colors.textPrimary)
                progress?.takeIf { it.durationMs > 0 }?.let {
                    LinearProgressIndicator(progress = { it.progressPercent.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp), color = Amber,
                        trackColor = Torve.colors.textTertiary.copy(alpha = 0.25f))
                }
            }
            FilledIconButton(onClick = { if (progress != null) onResume(progress) else next?.let(onEpisode) },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Amber, contentColor = Obsidian)) {
                Icon(Icons.Default.PlayArrow, contentDescription = episodeLabel)
            }
        }
    }
}
