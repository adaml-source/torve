package com.streamvault.android.ui.detail

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.domain.model.Season

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeSelector(
    seasons: List<Season>,
    onEpisodeSelected: (season: Int, episode: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (seasons.isEmpty()) return

    var selectedSeason by remember { mutableIntStateOf(seasons.firstOrNull()?.seasonNumber ?: 1) }
    var seasonExpanded by remember { mutableStateOf(false) }
    val currentSeason = seasons.find { it.seasonNumber == selectedSeason }
    val episodeCount = currentSeason?.episodeCount ?: 0

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
                seasons.filter { it.seasonNumber > 0 }.forEach { season ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Season ${season.seasonNumber}" +
                                    if (season.episodeCount > 0) " (${season.episodeCount} eps)" else "",
                            )
                        },
                        onClick = {
                            selectedSeason = season.seasonNumber
                            seasonExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Episode grid
        if (episodeCount > 0) {
            val columns = 5
            val rows = (episodeCount + columns - 1) / columns
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0 until rows) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (col in 0 until columns) {
                            val ep = row * columns + col + 1
                            if (ep <= episodeCount) {
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onEpisodeSelected(selectedSeason, ep) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = ep.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
