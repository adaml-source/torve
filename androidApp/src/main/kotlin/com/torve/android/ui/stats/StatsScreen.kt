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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.presentation.stats.StatsViewModel
import java.time.DayOfWeek
import java.time.format.TextStyle as DayTextStyle
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
                    contentDescription = stringResource(R.string.common_back_cd),
                    tint = Snow,
                )
            }
            Text(
                text = stringResource(R.string.stats_title),
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
                    StatBox(value = "${state.totalMovies}", label = stringResource(R.string.stats_movies))
                    StatBox(value = "${state.totalEpisodes}", label = stringResource(R.string.stats_episodes))
                    val hours = state.totalMinutes / 60
                    StatBox(value = "${hours}h", label = stringResource(R.string.stats_watch_time))
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
                        label = stringResource(R.string.stats_this_week),
                    )
                    val monthHrs = state.thisMonthMinutes / 60
                    val monthMin = state.thisMonthMinutes % 60
                    StatBox(
                        value = if (monthHrs > 0) "${monthHrs}h ${monthMin}m" else "${monthMin}m",
                        label = stringResource(R.string.stats_this_month),
                    )
                    StatBox(
                        value = "${state.longestStreak}",
                        label = stringResource(R.string.stats_day_streak),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            if (state.topGenres.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.stats_top_genres),
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
                            text = stringResource(R.string.stats_watched_count, genre.count),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Torve.colors.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            if (state.activityByDay.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.stats_most_active_days),
                    style = MaterialTheme.typography.titleMedium,
                    color = Snow,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val maxCount = state.activityByDay.values.maxOrNull() ?: 1
                val locale = LocalConfiguration.current.locales[0]
                val dayOrder = listOf(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
                )
                dayOrder.forEach { dayOfWeek ->
                    val englishKey = dayOfWeek.getDisplayName(DayTextStyle.FULL, java.util.Locale.ENGLISH)
                    val count = state.activityByDay[englishKey] ?: 0
                    val localizedShort = dayOfWeek.getDisplayName(DayTextStyle.SHORT, locale)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = localizedShort,
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
                            stringResource(R.string.stats_no_data),
                            style = MaterialTheme.typography.titleMedium,
                            color = Torve.colors.textSecondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.stats_no_data_desc),
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
