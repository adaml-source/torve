package com.torve.desktop.ui.v2.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorvePageHeader
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2FloatingBackButton
import com.torve.presentation.stats.StatsViewModel

@Composable
fun V2StatsPage(
    viewModel: StatsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val colors = TorveDesktopThemeTokens.colors

    LaunchedEffect(Unit) { viewModel.loadStats() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TorvePageHeader(
            title = ds("Stats"),
            subtitle = ds("Your watching at a glance."),
            trailing = {
                V2FloatingBackButton(onBack = onBack, contentDescription = ds("Back"))
            },
        )

        if (state.isLoading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        state.error?.let {
            TorveBanner(title = ds("Stats failed to load"), description = it, tone = TorveBannerTone.Error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                label = ds("Movies watched"),
                value = state.totalMovies.toString(),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = ds("Episodes watched"),
                value = state.totalEpisodes.toString(),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = ds("Total hours"),
                value = formatHours(state.totalMinutes),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = ds("Longest streak"),
                value = "${state.longestStreak} ${ds("days")}",
                modifier = Modifier.weight(1f),
            )
        }

        TorveSectionCard(
            title = ds("Recent activity"),
            supportingText = ds("Last 7 vs last 30 days."),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text(ds("This week"), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Text(
                        text = formatHours(state.thisWeekMinutes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                }
                Column {
                    Text(ds("This month"), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Text(
                        text = formatHours(state.thisMonthMinutes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                }
            }
        }

        if (state.topGenres.isNotEmpty()) {
            TorveSectionCard(
                title = ds("Top genres"),
                supportingText = ds("Most-watched genres across your history."),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val max = state.topGenres.maxOf { it.count }.coerceAtLeast(1)
                    state.topGenres.forEach { genre ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = genre.name,
                                modifier = Modifier.width(140.dp),
                                color = colors.textPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.borderSubtle),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(genre.count.toFloat() / max.toFloat())
                                        .background(colors.accent),
                                )
                            }
                            TorveBadge(text = genre.count.toString(), tone = TorveBadgeTone.Neutral)
                        }
                    }
                }
            }
        }

        if (state.activityByDay.isNotEmpty()) {
            TorveSectionCard(
                title = ds("By day of week"),
                supportingText = ds("When you watch most."),
            ) {
                val orderedDays = listOf(
                    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
                )
                val max = state.activityByDay.values.max().coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    orderedDays.forEach { day ->
                        val count = state.activityByDay[day] ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = day,
                                modifier = Modifier.width(100.dp),
                                color = colors.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.borderSubtle),
                            ) {
                                if (count > 0) Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(count.toFloat() / max.toFloat())
                                        .background(colors.accent),
                                )
                            }
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.cardSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
    }
}

private fun formatHours(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}
