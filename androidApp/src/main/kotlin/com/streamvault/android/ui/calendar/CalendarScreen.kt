package com.streamvault.android.ui.calendar

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.components.SectionHeader
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberSubtle
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Ruby
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.data.trakt.TraktCalendarEpisode
import com.streamvault.presentation.calendar.CalendarViewModel
import org.koin.compose.koinInject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CalendarScreen(
    onEpisodeClick: (tmdbId: Int) -> Unit,
    viewModel: CalendarViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            !state.traktConnected -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = StreamVault.colors.textTertiary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Connect Trakt to See Your Calendar",
                        style = MaterialTheme.typography.titleMedium,
                        color = StreamVault.colors.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Link your Trakt account in Settings to see upcoming episodes from your watchlist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StreamVault.colors.textSecondary,
                    )
                }
            }

            state.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Amber,
                )
            }

            state.error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.error ?: "Error",
                        color = Ruby,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.refresh() }) {
                        Text("Retry", color = Amber)
                    }
                }
            }

            state.episodes.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = StreamVault.colors.textTertiary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No Upcoming Episodes",
                        style = MaterialTheme.typography.titleMedium,
                        color = StreamVault.colors.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Episodes from your Trakt watchlist will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StreamVault.colors.textSecondary,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    item(key = "title") {
                        Text(
                            text = "Calendar",
                            style = MaterialTheme.typography.headlineMedium,
                            color = StreamVault.colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        )
                    }

                    state.groupedEpisodes.forEach { (dateLabel, episodes) ->
                        item(key = "header_$dateLabel") {
                            SectionHeader(title = dateLabel)
                        }

                        items(
                            items = episodes,
                            key = { ep ->
                                "${ep.showTitle}_s${ep.season}e${ep.episode}_${ep.firstAired}"
                            },
                        ) { episode ->
                            CalendarEpisodeCard(
                                episode = episode,
                                onClick = {
                                    episode.showTmdbId?.let { tmdbId ->
                                        onEpisodeClick(tmdbId)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarEpisodeCard(
    episode: TraktCalendarEpisode,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Charcoal,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.showTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = StreamVault.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "S${episode.season}E${episode.episode}" +
                        if (episode.episodeTitle.isNotBlank()) " \u2022 ${episode.episodeTitle}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamVault.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatAirTime(episode.firstAired),
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                )
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = AmberSubtle,
            ) {
                Text(
                    text = "S${episode.season}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun formatAirTime(isoDate: String): String {
    return try {
        val zdt = ZonedDateTime.parse(isoDate)
        val localTime = zdt.withZoneSameInstant(java.time.ZoneId.systemDefault())
        localTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    } catch (_: Exception) {
        isoDate
    }
}
