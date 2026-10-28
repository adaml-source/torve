package com.torve.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.torve.android.tv.components.*
import com.torve.android.ui.theme.*
import com.torve.presentation.watching.*
import com.torve.android.R
import androidx.compose.ui.res.stringResource
import com.torve.android.ui.product.presentedEpisode
import com.torve.android.ui.product.watchingDateLabel
import com.torve.android.ui.product.watchingErrorMessage
import com.torve.presentation.detail.playbackPositionLabel
import kotlinx.datetime.*
import org.koin.compose.koinInject
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TvWatchingHubScreen(onBack: () -> Unit, onEpisode: (WatchingEpisode) -> Unit,
    onContentFocused: (FocusRequester) -> Unit, viewModel: WatchingHubViewModel = koinInject()) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }
    TvWorkflowSurface("watching_hub") {
        TvWatchingHubContent(state, onBack, onEpisode, onContentFocused,
            onRefresh = { viewModel.refresh(force = true) }, onMarkWatched = viewModel::markWatched,
            onHideSpoilers = viewModel::setHideSpoilers)
    }
}

@Composable
internal fun TvWatchingHubContent(state: WatchingHubState, onBack: () -> Unit,
    onEpisode: (WatchingEpisode) -> Unit, onContentFocused: (FocusRequester) -> Unit,
    onRefresh: () -> Unit, onMarkWatched: (WatchingEpisode) -> Unit, onHideSpoilers: (Boolean) -> Unit = {}) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    var dateWindow by rememberSaveable { mutableStateOf(14) }
    val entry = remember { FocusRequester() }
    val optionsRequester = remember { FocusRequester() }
    val hideRequester = remember { FocusRequester() }
    val firstEpisodeRequester = remember { FocusRequester() }
    var options by remember { mutableStateOf(false) }
    val today = state.today
    val episodes = if (calendar) state.calendar.filter { next -> next.date?.let { date ->
        date >= (if (dateWindow == -7) today.minus(7, DateTimeUnit.DAY) else today) &&
        date <= (if (dateWindow == -7) today else today.plus(dateWindow, DateTimeUnit.DAY))
    } == true } else state.upNext
    val toolbarDown = Modifier.focusProperties { if (episodes.isNotEmpty()) down = firstEpisodeRequester }
    val error = watchingErrorMessage(state)
    TvWorkflowPage(stringResource(R.string.flow_your_shows), error ?: stringResource(if (calendar) R.string.flow_shows_calendar_sub else R.string.flow_shows_next_sub),
        onBack, entry, onContentFocused, compactHeader = true, toolbar = {
            TvWorkflowAction(stringResource(R.string.flow_up_next), { calendar = false }, toolbarDown, selected = !calendar, appearance = TvWorkflowActionAppearance.COMPACT)
            TvWorkflowAction(stringResource(R.string.calendar_title), { calendar = true }, toolbarDown, selected = calendar, appearance = TvWorkflowActionAppearance.COMPACT)
            if (calendar) {
                val range = stringResource(when (dateWindow) { -7 -> R.string.flow_range_past; 1 -> R.string.flow_range_today; 14 -> R.string.flow_range_weeks; else -> R.string.flow_range_month })
                val rangeDescription = stringResource(R.string.flow_range_change, range)
                TvWorkflowAction(range, { dateWindow = when(dateWindow) { -7 -> 1; 1 -> 14; 14 -> 30; else -> -7 } },
                    toolbarDown.semantics { stateDescription = rangeDescription }, appearance = TvWorkflowActionAppearance.COMPACT,
                    leading = { Icon(Icons.Filled.DateRange, null, tint = Silver, modifier = Modifier.size(16.dp)) })
            }
            Spacer(Modifier.weight(1f))
            Text("${episodes.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            val refreshDescription = stringResource(if (state.loading) R.string.flow_shows_loading else R.string.flow_shows_refresh)
            TvWorkflowAction(stringResource(R.string.flow_refresh), onRefresh, toolbarDown.semantics { stateDescription = refreshDescription },
                appearance = TvWorkflowActionAppearance.COMPACT, leading = {
                    if (state.loading) CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 1.5.dp)
                    else Icon(Icons.Filled.Refresh, null, tint = Silver, modifier = Modifier.size(16.dp))
                })
            TvWorkflowAction(stringResource(R.string.flow_view_options), { options = true }, toolbarDown, requester = optionsRequester,
                appearance = TvWorkflowActionAppearance.COMPACT)
        }) {
        if (episodes.isEmpty()) item("empty") { WatchingEmptyState(calendar, state.loading,
            state.incompleteShows > 0 || state.errorKind == WatchingHubError.REFRESH_FAILED || state.errorKind == WatchingHubError.PARTIAL_REFRESH) }
        if (calendar) {
            episodes.groupBy { it.date }.forEach { (date, releases) ->
                item("date:$date") {
                    Text(watchingDateLabel(date?.toString(), today.toString()), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                }
                items(releases, key = { "${it.show.id}:${it.season}:${it.episode.episodeNumber}" }) { next ->
                    WatchingEpisodeRow(next, true, today, onEpisode, onMarkWatched, onContentFocused, state,
                        firstEpisodeRequester.takeIf { next == episodes.firstOrNull() })
                }
            }
        } else itemsIndexed(episodes, key = { _, next -> "${next.show.id}:next" }) { index, next ->
            WatchingEpisodeRow(next, false, today, onEpisode, onMarkWatched, onContentFocused, state,
                firstEpisodeRequester.takeIf { index == 0 })
        }
    }
    if (options) TvWorkflowDialog(stringResource(R.string.flow_episode_preferences), { options = false }, optionsRequester, width = 440.dp, initialFocusRequester = hideRequester) {
        Text(stringResource(R.string.flow_hide_spoilers_sub), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        TvWorkflowAction(stringResource(if (state.hideSpoilers) R.string.flow_show_spoilers else R.string.flow_hide_spoilers),
            { onHideSpoilers(!state.hideSpoilers); options = false }, requester = hideRequester)
    }
}

@Composable
private fun WatchingEpisodeRow(next: WatchingEpisode, calendar: Boolean, today: LocalDate,
    onEpisode: (WatchingEpisode) -> Unit, onMarkWatched: (WatchingEpisode) -> Unit,
    onContentFocused: (FocusRequester) -> Unit, state: WatchingHubState, firstRequester: FocusRequester? = null) {
    val ownRequester = remember(next.show.id, calendar) { FocusRequester() }
    val requester = firstRequester ?: ownRequester
    val code = "S${next.season} E${next.episode.episodeNumber}"
    val episode = next.presentedEpisode(state.hideSpoilers)
    val actionsRequester = remember(next.show.id, calendar) { FocusRequester() }
    val markRequester = remember { FocusRequester() }
    var showActions by remember(next.show.id, next.season, next.episode.episodeNumber) { mutableStateOf(false) }
    val pending = "${next.show.id}_${next.season}_${next.episode.episodeNumber}" in state.markingEpisodes
    val canMark = !next.watched && next.date?.let { it <= today } == true
    val actionsDescription = stringResource(R.string.flow_episode_actions)
    val position = next.positionMs
    val fraction = next.progressPercent
    val primaryLabel = stringResource(when {
        next.resume && position != null -> R.string.flow_resume_at
        next.resume -> R.string.flow_resume_episode
        next.date?.let { it > today } == true -> R.string.flow_episode_details
        else -> R.string.flow_open_episode
    }, *if (next.resume && position != null) arrayOf(playbackPositionLabel(position)) else emptyArray())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvWorkflowAction(next.show.title, { onEpisode(next) }, Modifier.weight(1f).heightIn(min = 88.dp)
            .testTag("watching_episode_${next.show.id}_${next.episode.episodeNumber}")
            .semantics { stateDescription = "$code. ${episode.name}. $primaryLabel" }
            .focusProperties { if (canMark) right = actionsRequester },
            requester = requester, onFocused = { onContentFocused(requester) }, appearance = TvWorkflowActionAppearance.MEDIA,
            content = {
                WatchingArtwork(next.copy(episode = episode))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(next.show.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(code, color = AmberLight, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp), fontWeight = FontWeight.SemiBold)
                        Text(episode.name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .88f), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    if (next.resume && fraction != null) LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = AmberLight)
                    Text(if (next.watched) stringResource(R.string.flow_watched) else primaryLabel,
                        color = if (next.watched) Silver else AmberLight, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = Silver, modifier = Modifier.size(18.dp))
            })
        if (canMark) {
            TvWorkflowAction(stringResource(R.string.flow_episode_actions), { if (!pending) showActions = true },
                Modifier.size(48.dp).testTag("watching_actions_${next.show.id}_${next.episode.episodeNumber}")
                    .semantics { contentDescription = actionsDescription }.focusProperties { left = requester },
                requester = actionsRequester, onFocused = { onContentFocused(actionsRequester) },
                appearance = TvWorkflowActionAppearance.ICON, content = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (pending) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2.dp)
                        else Icon(Icons.Filled.MoreHoriz, null, tint = Silver, modifier = Modifier.size(24.dp))
                    }
                })
        }
    }
    if (showActions) TvWorkflowDialog(next.show.title, { showActions = false }, requester,
        width = 460.dp, initialFocusRequester = markRequester) {
        Text("$code · ${episode.name}", color = MaterialTheme.colorScheme.onSurface)
        if (next.remainingAired > 0) Text(stringResource(R.string.flow_aired_remaining, next.remainingAired), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.flow_mark_episode_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        TvWorkflowAction(stringResource(R.string.flow_mark_watched), { showActions = false; if (!pending) onMarkWatched(next) },
            Modifier.testTag("watching_mark_confirm"), requester = markRequester)
    }
}

@Composable
private fun WatchingArtwork(next: WatchingEpisode) {
    val primary = next.episode.stillUrl?.takeIf(String::isNotBlank)
    val fallback = next.show.backdropUrl?.takeIf(String::isNotBlank) ?: next.show.posterUrl
    var failed by remember(primary) { mutableStateOf(false) }
    val url = if (failed) fallback else primary ?: fallback
    Box(Modifier.testTag("watching_artwork_${next.show.id}_${next.episode.episodeNumber}").size(width = 120.dp, height = 68.dp)
        .clip(RoundedCornerShape(8.dp)).background(Gunmetal), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Tv, null, tint = Steel, modifier = Modifier.size(26.dp))
        if (!url.isNullOrBlank()) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            onState = { if (url == primary && it is AsyncImagePainter.State.Error) failed = true })
    }
}

@Composable
private fun WatchingEmptyState(calendar: Boolean, loading: Boolean, unavailable: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (unavailable) Icons.Filled.CloudOff else if (calendar) Icons.Filled.DateRange else Icons.Filled.CheckCircleOutline, null, tint = Silver, modifier = Modifier.size(38.dp))
        Text(stringResource(if (loading) R.string.flow_shows_loading else if (unavailable) R.string.flow_shows_schedule_unavailable else if (calendar) R.string.flow_shows_no_releases else R.string.flow_shows_caught_up),
            color = Snow, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(if (unavailable) R.string.flow_shows_schedule_unavailable_sub else if (calendar) R.string.flow_shows_no_releases_sub else R.string.flow_shows_empty_sub),
            color = Silver, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(max = 440.dp))
    }
}
