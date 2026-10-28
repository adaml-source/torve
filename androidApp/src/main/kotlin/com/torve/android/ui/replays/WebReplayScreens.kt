package com.torve.android.ui.replays

import com.torve.android.ui.community.parityLabel as localizedUiText

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.testTag
import com.torve.android.tv.components.tvSettingsFocusFrame
import com.torve.android.tv.components.TvClickToEditOutlinedTextField
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import coil3.compose.AsyncImage
import com.torve.data.auth.UserIdProvider
import com.torve.data.replays.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** All entry points use these same destinations and the one shared configuration. */
fun NavGraphBuilder.webReplayRoutes(nav: NavHostController, isTv: Boolean,
    onPlay: (ReplayEvent, ResolvedReplay, Long) -> Unit) {
    composable("web_replays") {
        WebReplayBrowser(isTv, { nav.popBackStack() },
            { nav.navigate("web_replay_settings") }, { nav.navigate("web_replay/${Uri.encode(it)}") })
    }
    composable("web_replay_settings") {
        WebReplaySettings(isTv, { nav.popBackStack() }, { nav.navigate("web_replays") })
    }
    composable("web_replay/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
        WebReplayDetail(entry.arguments?.getString("id").orEmpty(), isTv, { nav.popBackStack() }, onPlay)
    }
}

@Composable private fun replayState(catalogue: WebReplayCatalogue): WebReplayState {
    val state by catalogue.state.collectAsState()
    val ids: UserIdProvider = koinInject()
    return state.takeIf { it.owner == ids.currentDataUserId() } ?: WebReplayState()
}

internal enum class ReplayActionTone { STANDARD, PLAY, HIGHLIGHTS }

@Composable internal fun ReplayAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    tone: ReplayActionTone = ReplayActionTone.STANDARD, icon: ImageVector? = null, detail: String? = null,
    onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val symbol = icon ?: when (tone) {
        ReplayActionTone.PLAY -> Icons.Default.PlayArrow
        ReplayActionTone.HIGHLIGHTS -> Icons.Default.Slideshow
        else -> null
    }
    val context = LocalContext.current
    if (com.torve.android.device.DeviceFormFactor.isTv(context)) {
        ReplayGlassButton(modifier, enabled, onClick) { _ ->
            symbol?.let { image ->
                Box(Modifier.size(38.dp).background(Color.White.copy(alpha = .09f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Icon(image, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = .94f))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label.removePrefix("▶ "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .67f)) }
            }
        }
        return
    }
    OutlinedButton(onClick, enabled = enabled, border = BorderStroke(0.dp, Color.Transparent), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (tone == ReplayActionTone.PLAY) .65f else .32f),
            contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier.heightIn(min = 56.dp).onFocusChanged { focused = it.isFocused }.tvSettingsFocusFrame(focused)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            symbol?.let { Icon(it, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label.removePrefix("▶ "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable private fun ReplayMetadata(values: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.filter { it.isNotBlank() }.forEach { value ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)) {
                Text(value, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun ReplayFilter(selected: Boolean, label: String, onClick: () -> Unit) {
    ReplayAction(label, tone = ReplayActionTone.STANDARD, icon = if (selected) Icons.Default.Check else null, onClick = onClick)
}

@Composable internal fun ReplayTopBar(title: String, isTv: Boolean, onBack: () -> Unit, backModifier: Modifier = Modifier,
    requestInitialFocus: Boolean = true, trailing: @Composable () -> Unit = {}) {
    val focus = remember { FocusRequester() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        com.torve.android.ui.components.BackButton(onClick = onBack, modifier = Modifier.focusRequester(focus).then(backModifier))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        trailing()
    }
    LaunchedEffect(isTv, requestInitialFocus) { if (isTv && requestInitialFocus) focus.requestFocus() }
}

@Composable private fun ReplayCard(event: ReplayEvent, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    OutlinedCard(onClick, modifier.onFocusChanged { focused = it.hasFocus }.tvSettingsFocusFrame(focused),
        shape = RoundedCornerShape(20.dp), border = BorderStroke(0.dp, Color.Transparent),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .28f))) {
        AsyncImage(event.artwork, contentDescription = event.title, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(event.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(listOf(event.competition.ifBlank { event.sport }, event.date).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun WebReplayRail(onEvent: (String) -> Unit, onBrowse: () -> Unit, onSettings: () -> Unit,
    catalogue: WebReplayCatalogue = koinInject()) {
    val state = replayState(catalogue)
    if (!state.enabled) return
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(24.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Text(localizedUiText("Sports Replays"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onSettings) { Icon(Icons.Default.Settings, localizedUiText("Replay websites")) }
            TextButton(onBrowse) { Text(localizedUiText("See all")) }
        }
        if (state.events.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.events.take(20), key = { it.id }) { event -> ReplayCard(event, Modifier.width(260.dp)) { onEvent(event.id) } }
        } else Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localizedUiText(if (state.websites.none { it.enabled }) "Add a replay website to get started." else if (state.loading)
                "Finding replays…" else "Browse your websites or refresh to find replays."))
            ReplayAction(localizedUiText(if (state.websites.isEmpty()) "Add website" else "Browse replays"), onClick = if (state.websites.isEmpty()) onSettings else onBrowse)
        }
    }
}

@Composable fun WebReplaySettings(isTv: Boolean, onBack: () -> Unit, onBrowse: () -> Unit,
    catalogue: WebReplayCatalogue = koinInject(), embedded: Boolean = false,
    entryFocus: FocusRequester? = null, onFocused: (FocusRequester) -> Unit = {}) {
    if (com.torve.android.ui.profile.FamilySettingsLocked()) return
    val state = replayState(catalogue)
    val scope = rememberCoroutineScope()
    var url by rememberSaveable(state.owner) { mutableStateOf("") }
    var name by rememberSaveable(state.owner) { mutableStateOf("") }
    var busy by remember(state.owner) { mutableStateOf(false) }
    val copyProfiles by produceState<List<com.torve.domain.model.UserProfile>>(emptyList(), state.owner) { value = catalogue.copyProfiles() }
    var message by remember(state.owner) { mutableStateOf<String?>(null) }
    fun update(operation: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Check the website address and try again." }
            finally { busy = false }
        }
    }
    var addDialog by rememberSaveable(state.owner) { mutableStateOf(false) }
    var copyDialog by rememberSaveable(state.owner) { mutableStateOf(false) }
    var selectedSite by remember(state.owner) { mutableStateOf<ReplayWebsite?>(null) }
    val homeFocus = entryFocus ?: remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val copyFocus = remember { FocusRequester() }
    val siteFocus = remember(state.websites.map { it.id }) { state.websites.associate { it.id to FocusRequester() } }
    val scopeModifier = if (embedded) Modifier else Modifier.background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()
    @Composable fun setting(title: String, detail: String, requester: FocusRequester, enabled: Boolean = true, click: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        OutlinedCard(click, modifier = Modifier.fillMaxWidth().focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused(requester) }
            .tvSettingsFocusFrame(focused),
            enabled = enabled, shape = RoundedCornerShape(20.dp), border = BorderStroke(0.dp, Color.Transparent),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .3f))) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    @Composable fun dialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
        Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = !isTv)) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.widthIn(max = if (isTv) 760.dp else 560.dp).fillMaxWidth(if (isTv) .9f else 1f).heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    content()
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().then(scopeModifier).testTag("replays.settings"),
        contentPadding = PaddingValues(horizontal = if (embedded) 0.dp else if (isTv) 48.dp else 20.dp,
            vertical = if (embedded) 0.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!embedded) item { ReplayTopBar(localizedUiText("Sports Replays"), isTv, onBack) }
        item { setting(localizedUiText("Show on Home"), localizedUiText(if (state.enabled) "On · Your replays appear in a Home row" else "Off · Your websites stay saved"),
            homeFocus, state.loaded && !busy) { update { catalogue.configure(!state.enabled) } } }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(localizedUiText("Your websites"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("${state.websites.size} / 12", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { setting(localizedUiText("Add website"), localizedUiText("Enter a replay page or competition address"), addFocus, !busy && state.websites.size < 12) {
            message = null; addDialog = true
        } }
        if (state.websites.isEmpty()) item {
            Text(localizedUiText("Add a website to keep its games and replay sources together."),
                modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(state.websites, key = { it.id }) { site ->
            setting(site.name, localizedUiText("${if (site.enabled) "Enabled" else "Disabled"} · ${Uri.parse(site.url).host.orEmpty()}"),
                siteFocus.getValue(site.id), !busy) { selectedSite = site }
        }
        item { Text(localizedUiText("Across your devices"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
        item { Text(localizedUiText("Websites sync with the same account and profile. Viewing progress stays separate for each profile."),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        if (copyProfiles.isNotEmpty()) item { setting(localizedUiText("Copy websites to profile"), localizedUiText("Add these websites to another profile"), copyFocus,
            !busy && state.websites.isNotEmpty()) { copyDialog = true } }
        item { message?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (!embedded) item { ReplayAction(localizedUiText("Browse replays"), enabled = state.enabled, onClick = onBrowse) }
        item { Spacer(Modifier.height(if (isTv) 28.dp else 88.dp)) }
    }
    if (addDialog) dialog(localizedUiText("Add replay website"), { addDialog = false; addFocus.requestFocus() }) {
        val first = remember { FocusRequester() }
        Text(localizedUiText("Use a page with replay cards, such as a sport or competition page."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isTv) {
            TvClickToEditOutlinedTextField(url, { url = it }, { Text(localizedUiText("Website address")) }, true,
                Modifier.fillMaxWidth().focusRequester(first).testTag("replays.website.address"))
            TvClickToEditOutlinedTextField(name, { name = it }, { Text(localizedUiText("Name (optional)")) }, true, Modifier.fillMaxWidth())
            Text(localizedUiText("Press OK to edit a field. Press Back to finish typing."), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(localizedUiText("Website address")) }, singleLine = true)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(localizedUiText("Name (optional)")) }, singleLine = true)
        }
        message?.let { Text(localizedUiText(it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReplayAction(localizedUiText(if (busy) "Adding…" else "Add website"), enabled = url.isNotBlank() && !busy) {
                update { catalogue.addWebsite(url, name); url = ""; name = ""; addDialog = false; addFocus.requestFocus() }
            }
            ReplayAction(localizedUiText("Cancel"), enabled = !busy) { addDialog = false; addFocus.requestFocus() }
        }
        LaunchedEffect(isTv) { if (isTv) { withFrameNanos { }; first.requestFocus() } }
    }
    if (copyDialog) dialog(localizedUiText("Copy websites to profile"), { copyDialog = false; copyFocus.requestFocus() }) {
        val first = remember { FocusRequester() }
        Text(localizedUiText("Existing websites are kept. Each profile keeps its own replay progress."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        copyProfiles.forEachIndexed { index, profile ->
            ReplayAction(profile.name, if (index == 0) Modifier.fillMaxWidth().focusRequester(first) else Modifier.fillMaxWidth(), enabled = !busy) {
                update { catalogue.copyToProfile(profile.id); copyDialog = false; message = "Websites copied to ${profile.name}."; copyFocus.requestFocus() }
            }
        }
        ReplayAction(localizedUiText("Cancel"), enabled = !busy) { copyDialog = false; copyFocus.requestFocus() }
        LaunchedEffect(isTv) { if (isTv) { withFrameNanos { }; first.requestFocus() } }
    }
    selectedSite?.let { site -> dialog(site.name, { selectedSite = null; siteFocus[site.id]?.requestFocus() }) {
        val first = remember { FocusRequester() }
        Text(site.url, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReplayAction(localizedUiText(if (site.enabled) "Disable website" else "Enable website"), Modifier.fillMaxWidth().focusRequester(first), !busy) {
            update { catalogue.setWebsiteEnabled(site.id, !site.enabled); selectedSite = null; siteFocus[site.id]?.requestFocus() }
        }
        ReplayAction(localizedUiText("Remove website"), Modifier.fillMaxWidth(), !busy) {
            update { catalogue.removeWebsite(site.id); selectedSite = null; addFocus.requestFocus() }
        }
        ReplayAction(localizedUiText("Done"), Modifier.fillMaxWidth(), !busy) { selectedSite = null; siteFocus[site.id]?.requestFocus() }
        LaunchedEffect(isTv) { if (isTv) { withFrameNanos { }; first.requestFocus() } }
    } }
}

@Composable fun WebReplayBrowser(isTv: Boolean, onBack: () -> Unit, onSettings: () -> Unit, onEvent: (String) -> Unit,
    catalogue: WebReplayCatalogue = koinInject()) {
    val state = replayState(catalogue)
    var query by rememberSaveable(state.owner) { mutableStateOf("") }
    var site by rememberSaveable(state.owner) { mutableStateOf("") }
    var sport by rememberSaveable(state.owner) { mutableStateOf("") }
    val events = remember(state.events, query, site, sport) { state.events.filter {
        (site.isBlank() || site == it.websiteId) && (sport.isBlank() || sport == it.sport) &&
            (query.isBlank() || it.title.contains(query.trim(), true) || it.competition.contains(query.trim(), true)) } }
    LaunchedEffect(catalogue) { catalogue.refresh() }
    if (!isTv) {
        MobileReplayBrowserContent(state, query, site, sport, { query = it }, { site = it }, { sport = it },
            onBack, onSettings, catalogue::reload, onEvent, catalogue::loadMore)
        return
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()
        .padding(horizontal = if (isTv) 48.dp else 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReplayTopBar(localizedUiText("Sports Replays"), isTv, onBack) { ReplayAction(localizedUiText("Websites"), onClick = onSettings) }
        if (isTv) TvClickToEditOutlinedTextField(query, { query = it }, { Text(localizedUiText("Find a replay")) }, true, Modifier.fillMaxWidth())
        else OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(localizedUiText("Find a replay")) })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ReplayFilter(site.isBlank(), localizedUiText("All websites")) { site = "" } }
            items(state.websites.filter { it.enabled }, key = { it.id }) { value ->
                ReplayFilter(site == value.id, value.name) { site = value.id } }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ReplayFilter(sport.isBlank(), localizedUiText("All sports")) { sport = "" } }
            items(state.events.map { it.sport }.distinct().sorted()) { value -> ReplayFilter(sport == value, value) { sport = value } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ReplayAction(localizedUiText("Refresh"), enabled = !state.loading) { catalogue.reload() }
            if (state.loading) Text(localizedUiText("Updating replays…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (events.isEmpty()) {
            Text(localizedUiText(if (!state.enabled) "Enable Sports Replays in Websites to begin." else if (state.loading) "Finding replays…"
                else if (query.isNotBlank() || sport.isNotBlank()) "No replays match these filters." else "Your websites may need to open in a browser. You can refresh or manage them in Websites."))
        }
        LazyVerticalGrid(GridCells.Adaptive(if (isTv) 240.dp else 160.dp), Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 112.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            gridItems(events, key = { it.id }) { event -> ReplayCard(event) { onEvent(event.id) } }
            if (state.hasMore) item { ReplayAction(localizedUiText(if (state.loading) "Updating…" else "Load more replays"), enabled = !state.loading) { catalogue.loadMore() } }
        }
    }
}

@Composable fun WebReplayDetail(id: String, isTv: Boolean, onBack: () -> Unit,
    onPlay: (ReplayEvent, ResolvedReplay, Long) -> Unit, catalogue: WebReplayCatalogue = koinInject(),
    playback: ReplayPlaybackStore = koinInject()) {
    val state = replayState(catalogue)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val eventState = produceState<ReplayEvent?>(null, id, state.owner, state.enabled) {
        value = if (state.enabled) catalogue.event(id) else null
    }
    val event = eventState.value
    var showHighlights by remember(id, state.owner) { mutableStateOf(false) }
    var highlightGame by remember(id, state.owner) { mutableStateOf<String?>(null) }
    var options by remember(id, state.owner) { mutableStateOf<List<ReplaySourceOption>>(emptyList()) }
    var busy by remember(id, state.owner) { mutableStateOf(false) }
    var needsWebsite by remember(id, state.owner) { mutableStateOf(false) }
    fun find() { scope.launch {
        busy = true; needsWebsite = false
        try { options = event?.let { catalogue.sources(it) }.orEmpty(); needsWebsite = options.isEmpty() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { needsWebsite = true }
        finally { busy = false }
    } }
    fun play(preferred: ReplaySourceOption) { if (event == null || busy) return
        scope.launch {
            busy = true; needsWebsite = false
            try {
                val stream = catalogue.resolveAvailable(event, preferred, options)
                if (stream == null) needsWebsite = true else {
                    val start = playback.startPosition(id)
                    if (catalogue.state.value.owner == state.owner && catalogue.event(id) != null) {
                        playback.stage(stream); onPlay(event, stream, start)
                    }
                }
            } finally { busy = false }
        }
    }
    LaunchedEffect(id, state.owner, event?.id) { if (event != null) find() }
    ReplayDetailContent(event, isTv, options, busy, needsWebsite, onBack, ::play,
        onHighlights = { game -> highlightGame = game; showHighlights = true }, onRefresh = ::find,
        onOpenWebsite = {
            event?.let { current ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { needsWebsite = true }
            }
        })
    if (showHighlights && event != null) ReplayHighlightsChooser(event, highlightGame, isTv, catalogue) { showHighlights = false }
}

/** Artwork has a real measured height; an aspect-ratio child cannot draw over the actions. */
@Composable internal fun ReplayDetailHeader(event: ReplayEvent, isTv: Boolean, highlightsModifier: Modifier = Modifier, onHighlights: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val artworkHeight = (maxWidth * 9f / 16f).coerceAtMost(if (isTv) 220.dp else 260.dp)
            Box(Modifier.fillMaxWidth().height(artworkHeight).clip(RoundedCornerShape(16.dp)).testTag("replay.hero")) {
                AsyncImage(event.artwork, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(event.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            ReplayMetadata(listOf(event.competition.ifBlank { event.sport }, event.date))
        }
        ReplayAction(localizedUiText("Highlights"), Modifier.fillMaxWidth().then(highlightsModifier).testTag("replay.highlights"), tone = ReplayActionTone.HIGHLIGHTS, detail = localizedUiText("Short and extended videos from YouTube"), onClick = onHighlights)
    }
}

@Composable internal fun ReplaySourcesCard(options: List<ReplaySourceOption>, busy: Boolean,
    actionModifier: (String) -> Modifier = { Modifier },
    onPlay: (ReplaySourceOption) -> Unit, onHighlights: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .2f)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.firstOrNull()?.gameTitle?.let { Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            options.forEach { option ->
                ReplayAction(localizedUiText("Play · ${com.torve.data.replays.ReplaySourceLabels.title(option, options)}"), Modifier.fillMaxWidth().then(actionModifier("source:${option.id}")).testTag("replay.source.${option.id}"), enabled = !busy, tone = ReplayActionTone.PLAY,
                    detail = com.torve.data.replays.ReplaySourceLabels.detail(option)) { onPlay(option) }
            }
            options.firstOrNull()?.gameTitle?.let { game ->
                ReplayAction(localizedUiText("Game highlights"), Modifier.fillMaxWidth().then(actionModifier("game:$game")).testTag("replay.game.$game"), tone = ReplayActionTone.HIGHLIGHTS,
                    detail = localizedUiText("Choose a shorter version")) { onHighlights(game) }
            }
        }
    }
}

@Composable private fun ReplayHighlightsChooser(event: ReplayEvent, game: String?, isTv: Boolean,
    catalogue: WebReplayCatalogue, dismiss: () -> Unit) {
    val context = LocalContext.current
    var videos by remember(event.id, game) { mutableStateOf<List<ReplayHighlight>>(emptyList()) }
    var loading by remember(event.id, game) { mutableStateOf(true) }
    var browsingUrl by remember { mutableStateOf<String?>(null) }
    var playingHighlight by remember(event.id, game) { mutableStateOf<ReplayHighlight?>(null) }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(event.id, game) {
        try { videos = catalogue.highlights(event, game) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { videos = emptyList() }
        finally { loading = false }
    }
    fun open(url: String) {
        browsingUrl = url
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = !isTv)) {
        val secondary = if (isTv) Color.White.copy(alpha = .72f) else MaterialTheme.colorScheme.onSurfaceVariant
        Surface(shape = RoundedCornerShape(28.dp),
            modifier = if (isTv) Modifier.border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(28.dp)) else Modifier,
            color = if (isTv) Color(0xE51B2230) else MaterialTheme.colorScheme.surface,
            contentColor = if (isTv) Color.White else MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().heightIn(max = if (isTv) 480.dp else 700.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(localizedUiText("Highlights"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(game ?: event.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = secondary)
                    }
                    ReplayAction(localizedUiText("Close"), Modifier.focusRequester(closeFocus), icon = Icons.Default.Close, onClick = dismiss)
                }
                ReplayMetadata(listOf(ReplayHighlights.channelName(event), event.date))
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (loading) item { Text(localizedUiText("Finding highlights…"), color = secondary) }
                    items(videos, key = { it.id }) { video ->
                        ReplayHighlightChoice(video, isTv) { playingHighlight = video }
                    }
                    if (!loading && videos.isEmpty()) item {
                        Text(localizedUiText(if (game == null && event.title.contains("replays", true)) "Choose Game highlights beside a matchup, or browse the official channel below."
                            else "No highlight could be confirmed for this matchup and date yet."), color = secondary)
                    }
                }
                ReplayAction(localizedUiText(if (ReplayHighlights.channelName(event) == "YouTube") "Search YouTube" else "Browse official ${ReplayHighlights.channelName(event)} highlights"),
                    Modifier.fillMaxWidth(), icon = Icons.Default.Slideshow, detail = localizedUiText("YouTube browsing may reveal scores or results")) { open(ReplayHighlights.searchUrl(event, game)) }
                Text(if (isTv) stringResource(R.string.replay_highlight_tv_playback) else
                    localizedUiText("Matchup, coverage and runtime are shown without scores or results. Highlights play inside Torve."), style = MaterialTheme.typography.bodySmall,
                    color = secondary)
            }
        }
        LaunchedEffect(isTv) { if (isTv) { withFrameNanos { }; closeFocus.requestFocus() } }
    }
    playingHighlight?.let { video ->
        ReplayHighlightPlayer(video, onDismiss = { playingHighlight = null }, onOpenExternal = { playingHighlight = null; open(video.url) }, isTv = isTv)
    }
    browsingUrl?.let { url -> ReplayYouTubeBrowser(url) { browsingUrl = null } }
}


@Composable internal fun ReplayHighlightChoice(video: ReplayHighlight, isTv: Boolean, onPlay: () -> Unit) {
    if (isTv) {
        ReplayGlassButton(Modifier.fillMaxWidth().testTag("highlight.choice.${video.id}"), onClick = onPlay) { _ ->
            Box(Modifier.size(54.dp).background(Color.White.copy(alpha = .1f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(30.dp), tint = Color.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(video.matchupLabel.ifBlank { video.contentLabel }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(video.contentLabel, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .9f))
                Text(localizedUiText(listOf(video.channel, localizedUiText(if (video.isOfficial) "Official channel" else "YouTube creator"), video.contextLabel)
                    .filter { it.isNotBlank() }.joinToString(" · ")), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .65f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = .12f), contentColor = Color.White) {
                Text(video.durationLabel, Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        return
    }
    var focused by remember { mutableStateOf(false) }
    OutlinedCard(onPlay, Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }.tvSettingsFocusFrame(focused),
        shape = RoundedCornerShape(20.dp), border = BorderStroke(0.dp, Color.Transparent),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (isTv) 64.dp else 48.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(video.matchupLabel.ifBlank { video.contentLabel }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${video.contentLabel} · ${video.durationLabel}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                if (video.contextLabel.isNotBlank()) Text(video.contextLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(localizedUiText(listOf(video.channel, if (video.isOfficial) "Official channel" else "YouTube creator", video.publishedLabel).filter { it.isNotBlank() }.joinToString(" · ")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.PlayArrow, localizedUiText("Play highlights"), Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
