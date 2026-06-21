package com.torve.android.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

@Composable
fun ProfileTabScreen(
    onSettingsClick: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onSubscriptionClick: () -> Unit = {},
    onProfilesClick: () -> Unit = {},
    onChannelsClick: () -> Unit = {},
    onStatsClick: () -> Unit = {},
    settingsViewModel: SettingsViewModel = koinInject(),
) {
    val state by settingsViewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Profile header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar
            val initial = state.traktUser?.username?.firstOrNull()?.uppercase() ?: "T"
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Amber),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Obsidian,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.width(16.dp))

            Column {
                Text(
                    text = state.traktUser?.username ?: stringResource(R.string.profile_torve_user),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Snow,
                    fontWeight = FontWeight.Bold,
                )
                if (state.traktConnected) {
                    Text(
                        text = stringResource(R.string.profile_trakt_connected),
                        style = MaterialTheme.typography.bodySmall,
                        color = Torve.colors.textSecondary,
                    )
                }
            }
        }

        // Stats summary
        if (state.traktStats != null) {
            val stats = state.traktStats!!
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
                    StatItem(value = "${stats.moviesWatched}", label = stringResource(R.string.stats_movies))
                    StatItem(value = "${stats.episodesWatched}", label = stringResource(R.string.stats_episodes))
                    StatItem(value = "${stats.minutesWatched / 60}h", label = stringResource(R.string.stats_watch_time))
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        // Navigation links
        Text(
            stringResource(R.string.profile_quick_access),
            style = MaterialTheme.typography.titleMedium,
            color = Amber,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Charcoal),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column {
                ProfileNavItem(
                    icon = Icons.Filled.CalendarMonth,
                    label = stringResource(R.string.profile_calendar),
                    subtitle = stringResource(R.string.profile_calendar_sub),
                    onClick = onCalendarClick,
                )
                ProfileNavItem(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.profile_downloads),
                    subtitle = stringResource(R.string.profile_downloads_sub),
                    onClick = onDownloadsClick,
                )
                ProfileNavItem(
                    icon = Icons.Filled.BarChart,
                    label = stringResource(R.string.profile_stats),
                    subtitle = stringResource(R.string.profile_stats_sub),
                    onClick = onStatsClick,
                )
                ProfileNavItem(
                    icon = Icons.Filled.LiveTv,
                    label = stringResource(R.string.profile_channels),
                    subtitle = stringResource(R.string.profile_channels_sub),
                    onClick = onChannelsClick,
                )
                ProfileNavItem(
                    icon = Icons.Filled.Star,
                    label = stringResource(R.string.profile_subscription),
                    subtitle = stringResource(R.string.profile_subscription_sub),
                    onClick = onSubscriptionClick,
                )
                ProfileNavItem(
                    icon = Icons.Filled.Person,
                    label = stringResource(R.string.profile_profiles),
                    subtitle = stringResource(R.string.profile_profiles_sub),
                    onClick = onProfilesClick,
                )
                ProfileNavItem(
                    icon = Icons.Filled.Settings,
                    label = stringResource(R.string.profile_settings),
                    subtitle = stringResource(R.string.profile_settings_sub),
                    onClick = onSettingsClick,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // App version
        Text(
            text = "Torve v0.5.0",
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textTertiary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = Amber,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Torve.colors.textSecondary,
        )
    }
}

@Composable
private fun ProfileNavItem(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Amber,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Snow,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Torve.colors.textSecondary,
            )
        }
        Text(
            text = ">",
            style = MaterialTheme.typography.bodyMedium,
            color = Torve.colors.textTertiary,
        )
    }
}
