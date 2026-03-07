package com.torve.android.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.presentation.stats.StatsViewModel
import org.koin.compose.koinInject

@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding(),
    ) {
        // Top bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Snow,
                )
            }
            Text(
                text = "Your Stats",
                style = MaterialTheme.typography.titleLarge,
                color = Snow,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Amber, modifier = Modifier.size(40.dp))
            }
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Main stats card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Charcoal),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatBox(value = "${state.totalMovies}", label = "Movies")
                    StatBox(value = "${state.totalEpisodes}", label = "Episodes")
                    val hours = state.totalMinutes / 60
                    StatBox(value = "${hours}h", label = "Watch Time")
                }
            }

            Spacer(Modifier.height(16.dp))

            // This Week / This Month / Streak
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Charcoal),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val weekHrs = state.thisWeekMinutes / 60
                    val weekMin = state.thisWeekMinutes % 60
                    StatBox(
                        value = if (weekHrs > 0) "${weekHrs}h ${weekMin}m" else "${weekMin}m",
                        label = "This Week",
                    )
                    val monthHrs = state.thisMonthMinutes / 60
                    val monthMin = state.thisMonthMinutes % 60
                    StatBox(
                        value = if (monthHrs > 0) "${monthHrs}h ${monthMin}m" else "${monthMin}m",
                        label = "This Month",
                    )
                    StatBox(
                        value = "${state.longestStreak}",
                        label = "Day Streak",
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            if (state.topGenres.isNotEmpty()) {
                Text(
                    text = "Top Genres",
                    style = MaterialTheme.typography.titleMedium,
                    color = Snow,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                state.topGenres.forEach { genre ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = genre.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Snow,
                        )
                        Text(
                            text = "${genre.count} watched",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Torve.colors.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            if (state.activityByDay.isNotEmpty()) {
                Text(
                    text = "Most Active Days",
                    style = MaterialTheme.typography.titleMedium,
                    color = Snow,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val maxCount = state.activityByDay.values.maxOrNull() ?: 1
                val dayOrder = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                dayOrder.forEach { day ->
                    val count = state.activityByDay[day] ?: 0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = day.take(3),
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textSecondary,
                            modifier = Modifier.width(40.dp),
                        )
                        Box(
                            modifier = Modifier
                                .height(12.dp)
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction = if (maxCount > 0) count.toFloat() / maxCount else 0f)
                                    .background(
                                        Amber,
                                        shape = RoundedCornerShape(4.dp),
                                    ),
                            )
                        }
                        Text(
                            text = "$count",
                            style = MaterialTheme.typography.bodySmall,
                            color = Torve.colors.textSecondary,
                            modifier = Modifier.width(30.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            if (state.totalMovies == 0 && state.totalEpisodes == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No watch data yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = Torve.colors.textSecondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Start watching to see your stats",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Torve.colors.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBox(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = Amber,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textSecondary,
        )
    }
}
