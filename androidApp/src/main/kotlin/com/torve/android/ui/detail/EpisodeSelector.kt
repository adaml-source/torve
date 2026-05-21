package com.torve.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.ui.components.PreferredRatingPills
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.Episode
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.Season
import com.torve.domain.model.withFallbackTmdbScore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeSelector(
    seasons: List<Season>,
    selectedSeason: Int,
    seasonDetail: Season?,
    isLoadingSeasonDetail: Boolean,
    watchedEpisodes: Set<String> = emptySet(),
    seriesRatings: MediaRatings? = null,
    onSeasonSelected: (Int) -> Unit,
    onEpisodePlay: (season: Int, episode: Int) -> Unit,
    onEpisodeDownload: (season: Int, episode: Int) -> Unit,
    onDownloadSeason: (season: Int) -> Unit,
    onDownloadAll: () -> Unit,
    onMarkSeasonWatched: (season: Int) -> Unit = {},
    onToggleEpisodeWatched: (season: Int, episode: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (seasons.isEmpty()) return

    var seasonExpanded by remember { mutableStateOf(false) }
    val filteredSeasons = seasons.filter { it.seasonNumber > 0 }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_episodes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))

        // Season dropdown
        ExposedDropdownMenuBox(
            expanded = seasonExpanded,
            onExpandedChange = { seasonExpanded = !seasonExpanded },
        ) {
            OutlinedTextField(
                value = stringResource(R.string.episode_season, selectedSeason),
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = seasonExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = seasonExpanded,
                onDismissRequest = { seasonExpanded = false },
            ) {
                filteredSeasons.forEach { season ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.episode_season, season.seasonNumber) +
                                    if (season.episodeCount > 0) " (${season.episodeCount} eps)" else "",
                            )
                        },
                        onClick = {
                            onSeasonSelected(season.seasonNumber)
                            seasonExpanded = false
                        },
                    )
                }
            }
        }

        // Season progress bar
        val currentSeasonObj = seasons.find { it.seasonNumber == selectedSeason }
        val totalEps = currentSeasonObj?.episodeCount ?: seasonDetail?.episodes?.size ?: 0
        if (totalEps > 0) {
            val watchedCount = (1..totalEps).count { ep ->
                watchedEpisodes.contains("s${selectedSeason}e$ep")
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(
                    progress = { if (totalEps > 0) watchedCount.toFloat() / totalEps else 0f },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Amber,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = "$watchedCount/$totalEps",
                    style = MaterialTheme.typography.labelSmall,
                    color = Torve.colors.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Download & Mark Watched buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onDownloadSeason(selectedSeason) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.episode_season, selectedSeason), style = MaterialTheme.typography.labelMedium)
            }
            if (filteredSeasons.size > 1) {
                OutlinedButton(
                    onClick = onDownloadAll,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Rounded.FileDownload, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.episode_all_seasons), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Mark Season Watched button
        OutlinedButton(
            onClick = { onMarkSeasonWatched(selectedSeason) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp), tint = Amber)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.episode_mark_season_watched, selectedSeason), style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(12.dp))

        // Episode list
        if (isLoadingSeasonDetail) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Amber, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        } else {
            val episodes = seasonDetail?.episodes ?: emptyList()
            if (episodes.isEmpty()) {
                // Fallback: simple numbered grid (when TMDB season detail is unavailable)
                val currentSeason = seasons.find { it.seasonNumber == selectedSeason }
                val episodeCount = currentSeason?.episodeCount ?: 0
                if (episodeCount > 0) {
                    SimpleEpisodeGrid(
                        episodeCount = episodeCount,
                        selectedSeason = selectedSeason,
                        onEpisodePlay = onEpisodePlay,
                        onEpisodeDownload = onEpisodeDownload,
                    )
                }
            } else {
                // Rich episode cards
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    episodes.forEach { episode ->
                        val key = "s${selectedSeason}e${episode.episodeNumber}"
                        EpisodeCard(
                            episode = episode,
                            season = selectedSeason,
                            isWatched = watchedEpisodes.contains(key),
                            seriesRatings = seriesRatings,
                            onPlay = { onEpisodePlay(selectedSeason, episode.episodeNumber) },
                            onDownload = { onEpisodeDownload(selectedSeason, episode.episodeNumber) },
                            onToggleWatched = { onToggleEpisodeWatched(selectedSeason, episode.episodeNumber) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun EpisodeCard(
    episode: Episode,
    season: Int,
    isWatched: Boolean = false,
    seriesRatings: MediaRatings? = null,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onToggleWatched: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onToggleWatched,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (episode.stillUrl != null) {
                AsyncImage(
                    model = episode.stillUrl,
                    contentDescription = episode.name,
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(16f / 9f),
                    contentScale = ContentScale.Crop,
                )
            }
            // Play overlay
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.common_play),
                modifier = Modifier.size(28.dp),
                tint = Amber.copy(alpha = 0.9f),
            )
        }

        Spacer(Modifier.width(10.dp))

        // Episode info
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isWatched) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.detail_watched),
                        tint = Amber,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = "E${episode.episodeNumber} · ${episode.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isWatched) Torve.colors.textSecondary else Torve.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (episode.overview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val episodeRatings = seriesRatings.withFallbackTmdbScore(episode.rating)
            if ((episode.runtime ?: 0) > 0 || episodeRatings != null) {
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    episode.runtime?.takeIf { it > 0 }?.let { rt ->
                        Text(
                            text = "${rt}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = Torve.colors.textTertiary,
                        )
                    }
                    episodeRatings?.let { ratings ->
                        PreferredRatingPills(ratings = ratings)
                    }
                }
            }
        }

        // Download button
        IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = stringResource(R.string.action_download),
                tint = Amber,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SimpleEpisodeGrid(
    episodeCount: Int,
    selectedSeason: Int,
    onEpisodePlay: (season: Int, episode: Int) -> Unit,
    onEpisodeDownload: (season: Int, episode: Int) -> Unit,
) {
    val columns = 5
    val rows = (episodeCount + columns - 1) / columns
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until columns) {
                    val ep = row * columns + col + 1
                    if (ep <= episodeCount) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onEpisodePlay(selectedSeason, ep) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = ep.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
