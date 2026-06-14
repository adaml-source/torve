package com.torve.android.ui.calendar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.notification.EpisodeNotificationWorker
import com.torve.android.ui.components.SectionHeader
import com.torve.android.ui.components.ShimmerBox
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.presentation.calendar.CalendarStaleReason
import com.torve.presentation.calendar.CalendarViewModel
import org.koin.compose.koinInject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CalendarScreen(
    onEpisodeClick: (tmdbId: Int) -> Unit,
    onBack: () -> Unit = {},
    viewModel: CalendarViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setEpisodeNotificationsEnabled(true)
            EpisodeNotificationWorker.schedule(context)
        }
    }
    val onNotificationToggle: (Boolean) -> Unit = { enabled ->
        if (enabled) {
            val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                viewModel.setEpisodeNotificationsEnabled(true)
                EpisodeNotificationWorker.schedule(context)
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            viewModel.setEpisodeNotificationsEnabled(false)
            EpisodeNotificationWorker.cancel(context)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.requiresTraktReconnect -> {
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
                        tint = Torve.colors.textHint,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.calendar_reconnect_trakt),
                        style = MaterialTheme.typography.titleMedium,
                        color = Torve.colors.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.calendar_reconnect_trakt_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Torve.colors.textSecondary,
                    )
                }
            }

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
                        tint = Torve.colors.textHint,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.calendar_connect_trakt),
                        style = MaterialTheme.typography.titleMedium,
                        color = Torve.colors.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.calendar_connect_trakt_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Torve.colors.textSecondary,
                    )
                }
            }

            state.isLoading -> {
                CalendarSkeletonLoader()
            }

            state.error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = com.torve.android.error.resolveErrorKey(androidx.compose.ui.platform.LocalContext.current, state.error) ?: stringResource(R.string.calendar_error),
                        color = Ruby,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { viewModel.refresh() }) {
                        Text(stringResource(R.string.common_retry))
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
                        tint = Torve.colors.textHint,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.calendar_no_episodes),
                        style = MaterialTheme.typography.titleMedium,
                        color = Torve.colors.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.calendar_no_episodes_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Torve.colors.textSecondary,
                    )
                    Spacer(Modifier.height(20.dp))
                    CalendarNotificationToggle(
                        enabled = state.episodeNotificationsEnabled,
                        onCheckedChange = onNotificationToggle,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    // ── Cinematic Header ──
                    item(key = "header") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .background(
                                    Brush.verticalGradient(listOf(Graphite, Obsidian)),
                                ),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    Column {
                                        Text(
                                            text = stringResource(R.string.calendar_title),
                                            style = MaterialTheme.typography.displayMedium,
                                            color = Snow,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(R.string.calendar_upcoming_count, state.episodes.size),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Amber,
                                        )
                                    }
                                    CalendarNotificationToggle(
                                        enabled = state.episodeNotificationsEnabled,
                                        onCheckedChange = onNotificationToggle,
                                    )
                                }
                            }
                        }
                    }

                    state.refreshWarning?.let { warningKey ->
                        item(key = "refresh_warning") {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = AmberSubtle,
                            ) {
                                Text(
                                    text = stringResource(warningKey.stringResourceId()),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Amber,
                                )
                            }
                        }
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

        // ── Back Button ──
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = Snow,
            )
        }
    }
}

private fun CalendarStaleReason.stringResourceId(): Int =
    when (this) {
        CalendarStaleReason.RATE_LIMITED -> R.string.calendar_stale_rate_limited
        CalendarStaleReason.NETWORK -> R.string.calendar_stale_network
        CalendarStaleReason.SERVER -> R.string.calendar_stale_server
        CalendarStaleReason.UNKNOWN -> R.string.calendar_stale_unknown
    }

@Composable
private fun CalendarNotificationToggle(
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Charcoal.copy(alpha = 0.78f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.calendar_notify_toggle),
                    style = MaterialTheme.typography.labelLarge,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.calendar_notify_toggle_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = Torve.colors.textSecondary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onCheckedChange,
            )
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
        ) {
            // Play icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Gunmetal),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.showTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = Torve.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.episode_format, episode.season, episode.episode) +
                        if (episode.episodeTitle.isNotBlank()) " \u2022 ${episode.episodeTitle}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Torve.colors.textSecondary,
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

            // Season badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = AmberSubtle,
            ) {
                Text(
                    text = stringResource(R.string.episode_season, episode.season),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CalendarSkeletonLoader() {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header shimmer
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        )

        Spacer(Modifier.height(16.dp))

        // Section header shimmer
        ShimmerBox(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .width(100.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp)),
        )

        Spacer(Modifier.height(12.dp))

        // Card shimmers
        repeat(6) {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(68.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
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
