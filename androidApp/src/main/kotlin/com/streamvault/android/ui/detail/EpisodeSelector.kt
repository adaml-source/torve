package com.streamvault.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Season

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeSelector(
    seasons: List<Season>,
    selectedSeason: Int,
    seasonDetail: Season?,
    isLoadingSeasonDetail: Boolean,
    onSeasonSelected: (Int) -> Unit,
    onEpisodePlay: (season: Int, episode: Int) -> Unit,
    onEpisodeDownload: (season: Int, episode: Int) -> Unit,
    onDownloadSeason: (season: Int) -> Unit,
    onDownloadAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (seasons.isEmpty()) return

    var seasonExpanded by remember { mutableStateOf(false) }
    val filteredSeasons = seasons.filter { it.seasonNumber > 0 }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Episodes",
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
                value = "Season $selectedSeason",
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
                                "Season ${season.seasonNumber}" +
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

        Spacer(Modifier.height(12.dp))

        // Download buttons row
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
                Text("Season $selectedSeason", style = MaterialTheme.typography.labelMedium)
            }
            if (filteredSeasons.size > 1) {
                OutlinedButton(
                    onClick = onDownloadAll,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Rounded.FileDownload, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("All Seasons", style = MaterialTheme.typography.labelMedium)
                }
            }
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
                        EpisodeCard(
                            episode = episode,
                            season = selectedSeason,
                            onPlay = { onEpisodePlay(selectedSeason, episode.episodeNumber) },
                            onDownload = { onEpisodeDownload(selectedSeason, episode.episodeNumber) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    season: Int,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onPlay)
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
                contentDescription = "Play",
                modifier = Modifier.size(28.dp),
                tint = Amber.copy(alpha = 0.9f),
            )
        }

        Spacer(Modifier.width(10.dp))

        // Episode info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "E${episode.episodeNumber} · ${episode.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = StreamVault.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.overview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamVault.colors.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            episode.runtime?.let { rt ->
                if (rt > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${rt}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = StreamVault.colors.textTertiary,
                    )
                }
            }
        }

        // Download button
        IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = "Download",
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
