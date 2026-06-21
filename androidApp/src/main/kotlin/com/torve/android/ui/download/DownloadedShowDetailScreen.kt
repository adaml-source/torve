package com.torve.android.ui.download

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.DownloadGroupType
import com.torve.domain.model.DownloadedItem
import com.torve.presentation.download.DownloadCatalogueViewModel
import org.koin.compose.koinInject

@Composable
fun DownloadedShowDetailScreen(
    mediaId: String,
    onBack: () -> Unit,
    onPlayEpisode: (DownloadedItem) -> Unit,
    catalogueVM: DownloadCatalogueViewModel = koinInject(),
) {
    val state by catalogueVM.state.collectAsState()

    // Find the show group by mediaId
    val group = remember(state.catalogue) {
        (state.catalogue.specialSections.flatMap { it.items } +
            state.catalogue.sections.flatMap { it.items })
            .find { it.mediaId == mediaId && it.type == DownloadGroupType.SHOW }
    }

    if (group == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.download_show_not_found), color = Silver)
        }
        return
    }

    val seasons = group.seasons ?: return
    var expandedSeason by remember { mutableIntStateOf(seasons.firstOrNull()?.seasonNumber ?: 1) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Backdrop header
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp),
        ) {
            AsyncImage(
                model = group.backdropUrl ?: group.posterUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                        ),
                    ),
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = Snow)
            }
        }

        // Title + info
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                group.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Snow,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.year?.let { Text("$it", color = Silver, fontSize = 13.sp) }
                Text(stringResource(R.string.download_episodes_count, group.itemCount), color = Silver, fontSize = 13.sp)
                Text(formatFileSize(group.totalSizeBytes), color = Amber, fontSize = 13.sp)
                Text(
                    stringResource(R.string.download_watched_count, group.watchedCount, group.totalCount),
                    color = Silver,
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Season tabs
        if (seasons.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = seasons.indexOfFirst { it.seasonNumber == expandedSeason }
                    .coerceAtLeast(0),
                containerColor = Color.Transparent,
                contentColor = Amber,
                edgePadding = 16.dp,
            ) {
                seasons.forEach { season ->
                    Tab(
                        selected = expandedSeason == season.seasonNumber,
                        onClick = { expandedSeason = season.seasonNumber },
                        text = {
                            Text(
                                stringResource(R.string.episode_season, season.seasonNumber),
                                fontWeight = if (expandedSeason == season.seasonNumber)
                                    FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }
        }

        // Episode list
        val currentSeason = seasons.find { it.seasonNumber == expandedSeason }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Season header
            currentSeason?.let { season ->
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.download_episodes_count, season.episodes.size), color = Silver, fontSize = 12.sp)
                        Text(formatFileSize(season.totalSizeBytes), color = Silver, fontSize = 12.sp)
                        Text(
                            stringResource(R.string.download_watched_count, season.watchedCount, season.episodes.size),
                            color = Silver,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                items(season.episodes, key = { it.id }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        onPlay = { onPlayEpisode(episode) },
                        onDelete = { catalogueVM.deleteEpisode(episode.id) },
                    )
                }

                // Delete season button
                item {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { catalogueVM.deleteSeason(group.mediaId, expandedSeason) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ruby),
                        border = BorderStroke(1.dp, Ruby.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.download_delete_season, expandedSeason))
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: DownloadedItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Episode number circle
            Box(
                Modifier
                    .size(36.dp)
                    .background(
                        if (episode.isWatched) Emerald.copy(alpha = 0.2f)
                        else Color(0xFF2A2A3E),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (episode.isWatched) {
                    Icon(Icons.Default.Check, stringResource(R.string.detail_watched), tint = Emerald, modifier = Modifier.size(18.dp))
                } else {
                    Text(
                        "${episode.episodeNumber ?: "?"}",
                        color = Snow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Episode info
            Column(Modifier.weight(1f)) {
                Text(
                    episode.episodeTitle ?: "Episode ${episode.episodeNumber}",
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatFileSize(episode.fileSizeBytes), color = Silver, fontSize = 11.sp)
                    episode.resolution?.let { Text(it, color = Silver, fontSize = 11.sp) }
                    episode.audioCodec?.let { Text(it, color = Silver, fontSize = 11.sp) }
                    episode.runtime?.let { Text("${it}m", color = Silver, fontSize = 11.sp) }
                }
                // Progress bar
                if (episode.watchProgress > 0f && !episode.isWatched) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { episode.watchProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp)),
                        color = Amber,
                        trackColor = Color(0xFF2A2A3E),
                    )
                }
            }

            // Play button
            IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.PlayArrow, stringResource(R.string.common_play), tint = Amber)
            }

            // Delete button
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete, stringResource(R.string.common_delete),
                    tint = Ruby.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
