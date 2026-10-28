package com.torve.android.ui.replays

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.data.replays.ReplayEvent
import com.torve.data.replays.ReplaySourceOption
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** One explicit remote order in both directions, across lazy artwork and source groups. */
@Composable
internal fun ReplayDetailContent(
    event: ReplayEvent?, isTv: Boolean, options: List<ReplaySourceOption>, busy: Boolean, needsWebsite: Boolean,
    onBack: () -> Unit, onPlay: (ReplaySourceOption) -> Unit, onHighlights: (String?) -> Unit,
    onRefresh: () -> Unit, onOpenWebsite: () -> Unit,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var focusedAction by remember { mutableStateOf<String?>(null) }
    var focusMove by remember { mutableStateOf<Job?>(null) }
    val groups = options.groupBy { it.gameTitle }.values.toList()
    val groupStart = if (options.isEmpty()) 3 else 4
    val footer = groupStart + groups.size + if (needsWebsite) 1 else 0
    val stops = buildList {
        add("back" to 0)
        if (event != null) {
            add("highlights" to 1)
            groups.forEachIndexed { index, group ->
                if (!busy) group.forEach { add("source:${it.id}" to groupStart + index) }
                group.first().gameTitle?.let { add("game:$it" to groupStart + index) }
            }
            add("website" to footer)
            if (!busy) add("refresh" to footer)
        }
    }
    // Source loading can change the stops without replacing the header's focus identity.
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    stops.forEach { requesters.getOrPut(it.first) { FocusRequester() } }
    LaunchedEffect(isTv) {
        if (isTv) {
            withFrameNanos { }
            requesters.getValue("back").requestFocus()
        }
    }
    fun actionFocus(id: String): Modifier {
        if (!isTv || id !in requesters) return Modifier
        return Modifier.focusRequester(requesters.getValue(id)).onFocusChanged {
            if (it.isFocused) focusedAction = id
            else if (focusedAction == id) focusedAction = null
        }.onPreviewKeyEvent { eventKey ->
            if (eventKey.key != Key.DirectionUp && eventKey.key != Key.DirectionDown) false
            else {
                if (eventKey.type == KeyEventType.KeyDown) {
                    // A held remote press must stop on Highlights instead of repeating
                    // straight through it to Back (or the first playback source).
                    if (id == "highlights" && eventKey.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                    val index = stops.indexOfFirst { it.first == id }
                    val next = stops.getOrNull(index + if (eventKey.key == Key.DirectionDown) 1 else -1)
                    if (next != null) {
                        focusMove?.cancel()
                        focusMove = scope.launch {
                            val target = requesters.getValue(next.first)
                            runCatching { target.requestFocus() }
                            withFrameNanos { }
                            // A lazy item can be detached or reject focus without throwing.
                            if (focusedAction != next.first) {
                                list.scrollToItem(next.second)
                                withFrameNanos { }
                                target.requestFocus()
                            }
                        }
                    }
                }
                true
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
        state = list, contentPadding = PaddingValues(horizontal = if (isTv) 48.dp else 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ReplayTopBar(localizedUiText("Replay"), isTv, onBack, backModifier = actionFocus("back").testTag("replay.back"), requestInitialFocus = false) }
        if (event == null) item { Text(localizedUiText("This replay is no longer in your enabled websites.")) }
        else {
            item { ReplayDetailHeader(event, isTv, actionFocus("highlights")) { onHighlights(null) } }
            item { if (busy) Text(localizedUiText("Preparing replay…")) }
            if (options.isNotEmpty()) item {
                Text(localizedUiText("Full replay"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(localizedUiText("Alternative uploads of the same event. Quality and commentary are shown when supplied by the website. Seeking depends on the source."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(groups, key = { it.first().id }) { group ->
                ReplaySourcesCard(group, busy, actionModifier = ::actionFocus, onPlay = onPlay,
                    onHighlights = { onHighlights(it) })
            }
            if (needsWebsite) item { Text(localizedUiText("This replay needs its website. Open it below or choose another replay.")) }
            item {
                ReplayAction(localizedUiText("Open website"), actionFocus("website"), onClick = onOpenWebsite)
                ReplayAction(localizedUiText("Refresh sources"), actionFocus("refresh"), enabled = !busy, onClick = onRefresh)
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}
