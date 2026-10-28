package com.torve.android.ui.calendar

import com.torve.android.ui.community.parityLabel as localizedUiText

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.notification.EpisodeNotificationWorker
import com.torve.android.ui.components.*
import com.torve.android.ui.product.ProductEmptyState
import com.torve.android.ui.product.ProductFeedback
import com.torve.android.ui.product.ProductPage
import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.domain.model.MediaItem
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.calendar.*
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.compose.koinInject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CalendarScreen(onEpisodeClick: (Int) -> Unit, onBack: (() -> Unit)? = null,
    onCalendarEpisode: ((TraktCalendarEpisode) -> Unit)? = null,
    viewModel: CalendarViewModel = koinInject(), metadata: MetadataRepository = koinInject(), settings: SettingsViewModel = koinInject()) {
    val state by viewModel.state.collectAsState()
    val prefs by settings.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // This screen owns artwork lookups; leaving the screen cancels them, and account/language changes discard them.
    val lookups = remember(prefs.personalDataUserId, prefs.appLanguage) { mutableMapOf<Int, Deferred<MediaItem?>>() }
    val permits = remember { Semaphore(3) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { viewModel.setEpisodeNotificationsEnabled(true); EpisodeNotificationWorker.schedule(context) }
    }
    var options by remember { mutableStateOf(false) }
    MobileCalendarContent(state, onEpisode = { episode ->
        if (onCalendarEpisode != null) onCalendarEpisode(episode) else episode.showTmdbId?.let(onEpisodeClick)
    }, onRefresh = { viewModel.refresh() }, onBack = onBack, onOptions = { options = true }, artwork = { entry ->
        val art by produceState<MediaItem?>(null, entry.showTmdbId, prefs.personalDataUserId, prefs.appLanguage) {
            val id = entry.showTmdbId
            if (id != null) value = lookups.getOrPut(id) {
                scope.async { permits.withPermit { withTimeoutOrNull(6_000) {
                    try { metadata.getDetail("tv", id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                } } }
            }.await()
        }
        art
    })
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text(stringResource(R.string.calendar_title)) }, text = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.calendar_notify_toggle), Modifier.weight(1f))
            Switch(state.episodeNotificationsEnabled, { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { viewModel.setEpisodeNotificationsEnabled(enabled)
                    if (enabled) EpisodeNotificationWorker.schedule(context) else EpisodeNotificationWorker.cancel(context) }
            })
        }
    }, confirmButton = { TextButton(onClick = { options = false }) { Text(stringResource(R.string.common_done)) } })
}

internal fun calendarAirDate(value: String): ZonedDateTime? = runCatching {
    ZonedDateTime.parse(value).withZoneSameInstant(ZoneId.systemDefault())
}.getOrNull()

internal fun calendarDayLabel(day: LocalDate?, today: LocalDate = LocalDate.now()): String = when (day) {
    null -> "Date unavailable"
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MobileCalendarContent(state: CalendarUiState, onEpisode: (TraktCalendarEpisode) -> Unit,
    onRefresh: () -> Unit, onBack: (() -> Unit)? = null, onOptions: () -> Unit = {},
    artwork: @Composable (TraktCalendarEpisode) -> MediaItem? = { null }) {
    val days = remember(state.episodes) {
        state.episodes.sortedBy { calendarAirDate(it.firstAired)?.toInstant() ?: Instant.MAX }
            .groupBy { calendarAirDate(it.firstAired)?.toLocalDate() }
    }
    ProductPage(stringResource(R.string.calendar_title), onBack = onBack, actions = {
        MobileGlassIconButton(onOptions) { Icon(Icons.Rounded.Tune, stringResource(R.string.flow_episode_preferences)) }
        MobileGlassIconButton(onRefresh, enabled = !state.isLoading, modifier = Modifier.testTag("calendar_refresh")) {
            if (state.isLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Refresh, stringResource(R.string.flow_shows_refresh))
        }
    }) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("calendar_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            if (!state.traktConnected || state.requiresTraktReconnect) item {
                ProductEmptyState(stringResource(if (state.requiresTraktReconnect) R.string.calendar_reconnect_trakt else R.string.calendar_connect_trakt),
                    stringResource(if (state.requiresTraktReconnect) R.string.calendar_reconnect_trakt_desc else R.string.calendar_connect_trakt_desc))
            } else {
                state.error?.let { item("error") { ProductFeedback(it, true, onRefresh) } }
                state.refreshWarning?.let { item("warning") { ProductFeedback(localizedUiText("Some episode schedules could not be refreshed."), onRetry = onRefresh) } }
                if (state.episodes.isEmpty()) item("empty") {
                    ProductEmptyState(localizedUiText(if (state.isLoading) "Loading calendar…" else "No upcoming episodes"),
                        localizedUiText(if (state.isLoading) "Refreshing your episode schedule" else "New episodes for the shows you follow will appear here."))
                }
                days.forEach { (day, entries) ->
                    stickyHeader("date_$day") {
                        Row(Modifier.fillMaxWidth().testTag("calendar_date_$day").background(MaterialTheme.colorScheme.background)
                            .semantics { heading() }.padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(calendarDayLabel(day), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text("${entries.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(entries, key = { "${it.showTmdbId}:${it.season}:${it.episode}:${it.firstAired}" }) { entry ->
                        Box(Modifier.padding(bottom = 12.dp)) {
                            MobileCalendarEpisodeCard(entry, artwork(entry)) { onEpisode(entry) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MobileCalendarEpisodeCard(entry: TraktCalendarEpisode, art: MediaItem?, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = entry.showTmdbId != null, shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, contentColor = Color.White,
        modifier = Modifier.fillMaxWidth().testTag("calendar_episode_${entry.showTmdbId}_${entry.season}_${entry.episode}")) {
        Box {
            art?.backdropUrl?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .78f), Color.Black.copy(alpha = .45f)))))
            Row(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(width = 62.dp, height = 92.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Tv, null, tint = Color.White.copy(alpha = .5f))
                    art?.posterUrl?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(entry.showTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("S%02dE%02d".format(entry.season, entry.episode),
                            Modifier.background(Color(0xFFFFC34B), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                            color = Color(0xFF251B00), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(entry.episodeTitle, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Rounded.Schedule, null, Modifier.size(14.dp), tint = Color.White.copy(alpha = .75f))
                        Text(localizedUiText(calendarAirDate(entry.firstAired)?.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())) ?: "Time unavailable"),
                            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .80f))
                    }
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = .7f))
            }
        }
    }
}
