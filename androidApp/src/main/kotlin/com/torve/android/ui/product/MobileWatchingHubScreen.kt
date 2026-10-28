package com.torve.android.ui.product

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.presentation.detail.playbackPositionLabel
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.torve.android.notification.EpisodeNotificationWorker
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import androidx.compose.material3.*
import com.torve.android.ui.components.MobileGlassIconButton as IconButton
import com.torve.android.ui.components.MobileGlassOutlinedButton as OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import androidx.compose.foundation.background
import com.torve.presentation.watching.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.koin.compose.koinInject

@Composable
fun MobileWatchingHubScreen(onBack: (() -> Unit)?, onEpisode: (WatchingEpisode) -> Unit,
    initialCalendar: Boolean = false, embedded: Boolean = false, viewModel: WatchingHubViewModel = koinInject()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val person = state.personalDataUserId
    val alertChecks = remember(context) { context.getSharedPreferences("episode_alert_checks", android.content.Context.MODE_PRIVATE) }
    var lastAlertCheck by remember(person) { mutableLongStateOf(person?.let { alertChecks.getLong("$it:completed", 0) } ?: 0) }
    var alertCheckStatus by remember(person) { mutableStateOf(person?.let { alertChecks.getString("$it:state", "") }.orEmpty()) }
    DisposableEffect(alertChecks, person) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "$person:completed") lastAlertCheck = prefs.getLong(key, 0)
            if (key == "$person:state") alertCheckStatus = prefs.getString(key, "").orEmpty()
        }
        alertChecks.registerOnSharedPreferenceChangeListener(listener)
        onDispose { alertChecks.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var permissionDenied by remember { mutableStateOf(false) }
    fun runtimePermissionGranted() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(EpisodeNotificationWorker.notificationsAllowed(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { hasPermission = EpisodeNotificationWorker.notificationsAllowed(context); if (hasPermission) permissionDenied = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val setAlerts: (Boolean) -> Unit = { enabled -> scope.launch {
        viewModel.setEpisodeNotificationsEnabled(enabled)
        if (enabled) EpisodeNotificationWorker.schedule(context) else EpisodeNotificationWorker.cancel(context)
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val allowed = granted && EpisodeNotificationWorker.notificationsAllowed(context)
        permissionDenied = !allowed
        hasPermission = allowed
        setAlerts(allowed)
    }
    MobileWatchingHubContent(state, onBack, onEpisode, { viewModel.refresh(force = true) }, viewModel::markWatched, initialCalendar,
        viewModel::setHideSpoilers, { enabled ->
            if (enabled && !hasPermission) {
                if (!runtimePermissionGranted()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { permissionDenied = true; setAlerts(false) }
            } else setAlerts(enabled)
        }, permissionDenied || (state.episodeNotificationsEnabled && !hasPermission),
        lastAlertCheck = lastAlertCheck, alertCheckStatus = alertCheckStatus,
        onCheckAlerts = { person?.let { EpisodeNotificationWorker.checkNow(context, it) } }, embedded = embedded)
}

@Composable
internal fun MobileWatchingHubContent(state: WatchingHubState, onBack: (() -> Unit)?, onEpisode: (WatchingEpisode) -> Unit,
    onRefresh: () -> Unit, onMarkWatched: (WatchingEpisode) -> Unit, initialCalendar: Boolean = false,
    onHideSpoilers: (Boolean) -> Unit = {}, onAlerts: (Boolean) -> Unit = {}, notificationPermissionMissing: Boolean = false,
    lastAlertCheck: Long = 0, alertCheckStatus: String = "", onCheckAlerts: () -> Unit = {}, embedded: Boolean = false) {
    var calendar by rememberSaveable { mutableStateOf(initialCalendar) }
    var dateWindow by rememberSaveable { mutableIntStateOf(14) }
    var options by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val upNextScroll = rememberLazyListState()
    val calendarScroll = rememberLazyListState()
    val entries = if (calendar) state.calendar.filter { it.date?.let { date ->
        date >= (if (dateWindow == -7) state.today.minus(7, DateTimeUnit.DAY) else state.today) &&
            date <= (if (dateWindow == -7) state.today else state.today.plus(dateWindow, DateTimeUnit.DAY))
    } == true } else state.upNext
    ProductPage(stringResource(R.string.flow_your_shows), stringResource(if (calendar) R.string.flow_shows_calendar_sub else R.string.flow_shows_next_sub), onBack,
        showHeader = !embedded,
        actions = {
            IconButton(onClick = { options = true }, modifier = Modifier.testTag("shows_options")) {
                Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.flow_shows_options))
            }
            IconButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.testTag("shows_refresh")) {
                if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.flow_shows_refresh))
            }
        }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !calendar, onClick = { calendar = false }, label = { Text(stringResource(R.string.flow_up_next)) })
            FilterChip(selected = calendar, onClick = { calendar = true }, label = { Text(stringResource(R.string.calendar_title)) })
            Spacer(Modifier.weight(1f))
            if (embedded) {
                IconButton(onClick = { options = true }, modifier = Modifier.testTag("shows_options")) {
                    Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.flow_shows_options))
                }
                IconButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.testTag("shows_refresh")) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.flow_shows_refresh))
                }
            } else Text("${entries.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (calendar) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to R.string.flow_range_today, 14 to R.string.flow_range_weeks, 30 to R.string.flow_range_month, -7 to R.string.flow_range_past).forEach { (days, label) ->
                FilterChip(selected = dateWindow == days, onClick = { dateWindow = days }, label = { Text(stringResource(label)) },
                    modifier = Modifier.testTag("calendar_range_$days"))
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("shows_list"), state = if (calendar) calendarScroll else upNextScroll,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.error != null || state.errorKind != null) item("error") { watchingErrorMessage(state)?.let { ProductFeedback(it, isError = true, onRetry = onRefresh) } }
            if (entries.isEmpty()) item("empty") {
                ProductEmptyState(stringResource(if (state.loading) R.string.flow_shows_loading else if (calendar) R.string.flow_shows_no_releases else R.string.flow_shows_empty),
                    stringResource(if (state.loading) R.string.flow_shows_loading_sub else R.string.flow_shows_empty_sub))
            }
            if (calendar) {
                entries.groupBy { it.date }.forEach { (date, dayEntries) ->
                    item("date_$date") { Text(watchingDateLabel(date?.toString(), state.today.toString()),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) }
                    items(dayEntries, key = { "calendar_${it.show.id}_${it.season}_${it.episode.episodeNumber}" }) { entry ->
                        MobileWatchingEpisodeRow(entry, state, onEpisode, onMarkWatched)
                    }
                }
            } else items(entries, key = { "next_${it.show.id}_${it.season}_${it.episode.episodeNumber}" }) { entry ->
                MobileWatchingEpisodeRow(entry, state, onEpisode, onMarkWatched)
            }
        }
    }
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text(stringResource(R.string.flow_episode_preferences)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(stringResource(R.string.flow_hide_spoilers), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.flow_hide_spoilers_sub), style = MaterialTheme.typography.bodySmall) }
                Switch(state.hideSpoilers, onHideSpoilers, modifier = Modifier.testTag("shows_spoilers"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(stringResource(R.string.flow_episode_alerts), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.flow_episode_alerts_sub), style = MaterialTheme.typography.bodySmall) }
                Switch(state.episodeNotificationsEnabled && !notificationPermissionMissing, onAlerts, modifier = Modifier.testTag("shows_alerts"))
            }
            if (notificationPermissionMissing) Text(stringResource(R.string.flow_notifications_blocked),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.gap_alerts_schedule), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (lastAlertCheck > 0) stringResource(R.string.gap_alerts_last,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(lastAlertCheck)))
                else stringResource(R.string.gap_alerts_never), style = MaterialTheme.typography.bodySmall)
            if (alertCheckStatus == "retry") Text(stringResource(R.string.common_retry), color = MaterialTheme.colorScheme.error)
            if (state.episodeNotificationsEnabled && !notificationPermissionMissing) OutlinedButton(onClick = onCheckAlerts,
                modifier = Modifier.heightIn(min = 48.dp).testTag("shows_alerts_check")) { Text(stringResource(R.string.gap_alerts_check)) }
            if (notificationPermissionMissing) OutlinedButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.flow_android_settings)) }
        }
    }, confirmButton = { TextButton(onClick = { options = false }) { Text(stringResource(R.string.common_done)) } })
}

@Composable
private fun MobileWatchingEpisodeRow(entry: WatchingEpisode, state: WatchingHubState,
    onEpisode: (WatchingEpisode) -> Unit, onMarkWatched: (WatchingEpisode) -> Unit) {
    val episodeLabel = "S${entry.season} E${entry.episode.episodeNumber}"
    val pending = "${entry.show.id}_${entry.season}_${entry.episode.episodeNumber}" in state.markingEpisodes
    val date = entry.date
    val position = entry.positionMs
    val fraction = entry.progressPercent
    val episode = entry.presentedEpisode(state.hideSpoilers)
    var showActions by remember(entry.show.id, entry.season, entry.episode.episodeNumber) { mutableStateOf(false) }
    Surface(onClick = { onEpisode(entry) }, modifier = Modifier.fillMaxWidth().testTag("episode_${entry.show.id}_${entry.season}_${entry.episode.episodeNumber}"), shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val art = episode.stillUrl ?: entry.show.backdropUrl ?: entry.show.posterUrl
                    var failed by remember(art) { mutableStateOf(false) }
                    Box(Modifier.size(width = 88.dp, height = 56.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        val image = if (failed) entry.show.backdropUrl ?: entry.show.posterUrl else art
                        if (!image.isNullOrBlank()) AsyncImage(image, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(), onState = { if (it is AsyncImagePainter.State.Error) failed = true })
                        com.torve.android.ui.community.RemainingEpisodesBadge(entry.remainingAired, state.showRemainingEpisodes, Modifier.align(Alignment.TopEnd).padding(3.dp))
                    }
                    Text(entry.show.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    if (date != null && date <= state.today) IconButton(onClick = { showActions = true }, enabled = !pending && !entry.watched,
                        modifier = Modifier.size(48.dp).testTag("mark_${entry.show.id}_${entry.season}_${entry.episode.episodeNumber}")) {
                        if (pending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(if (entry.watched) Icons.Rounded.CheckCircle else Icons.Rounded.MoreHoriz,
                            contentDescription = stringResource(if (entry.watched) R.string.flow_watched else R.string.flow_episode_actions))
                    }
                }
                Text("$episodeLabel · ${episode.name}", style = MaterialTheme.typography.bodyMedium)
                if (entry.resume && fraction != null) LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (date != null && date > state.today) Icons.Rounded.Tv else Icons.Rounded.PlayArrow,
                        null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(when {
                        entry.resume && position != null -> R.string.flow_resume_at
                        entry.resume -> R.string.flow_resume_episode
                        date != null && date > state.today -> R.string.flow_episode_details
                        else -> R.string.flow_open_episode
                    }, *if (entry.resume && position != null) arrayOf(playbackPositionLabel(position)) else emptyArray()),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    if (showActions) AlertDialog(onDismissRequest = { showActions = false },
        title = { Text(entry.show.title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$episodeLabel · ${episode.name}")
            if (state.showRemainingEpisodes && entry.remainingAired > 0) Text(stringResource(R.string.flow_aired_remaining, entry.remainingAired),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.flow_mark_episode_explanation), style = MaterialTheme.typography.bodyMedium)
        } }, confirmButton = { TextButton(onClick = { showActions = false; onMarkWatched(entry) }, enabled = !pending && !entry.watched,
            modifier = Modifier.heightIn(min = 48.dp).testTag("episode_mark_confirm")) { Text(stringResource(R.string.flow_mark_watched)) } },
        dismissButton = { TextButton(onClick = { showActions = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel)) } })
    }
