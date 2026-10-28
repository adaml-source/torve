@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.torve.desktop.ui.v2.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.data.catalog.CatalogGroupRepository
import com.torve.desktop.ui.components.*
import com.torve.desktop.ui.l10n.ds
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import com.torve.domain.model.*
import com.torve.domain.repository.ProfileRepository
import com.torve.presentation.home.HomeUiState
import com.torve.presentation.home.catalogGroupSources
import com.torve.presentation.lists.TraktListsViewModel
import com.torve.presentation.profile.ProfileViewModel
import com.torve.presentation.watching.WatchingEpisode
import com.torve.presentation.watching.WatchingHubViewModel
import com.torve.presentation.watching.forPresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

enum class DesktopLibraryTool(val label: String) {
    SHOWS("Your shows"), GROUPS("Catalog groups"), LISTS("Trakt lists"), PROFILES("Household profiles")
}

/** A single modal keeps Library's browsing position intact while tools are open. */
@Composable
fun DesktopLibraryTools(
    tool: DesktopLibraryTool,
    home: HomeUiState,
    customSections: List<CustomSection>,
    onDismiss: () -> Unit,
    onOpenEpisode: (WatchingEpisode) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    target: MediaItem? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 960.dp),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(ds(tool.label)) },
        text = {
            Column(Modifier.width(820.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tool) {
                    DesktopLibraryTool.SHOWS -> DesktopWatchingHub(onOpenEpisode)
                    DesktopLibraryTool.GROUPS -> DesktopCatalogGroups(home, customSections, onOpenDetail)
                    DesktopLibraryTool.LISTS -> DesktopTraktLists(target, onOpenDetail)
                    DesktopLibraryTool.PROFILES -> DesktopProfiles()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(ds("Close")) } },
    )
}

@Composable
private fun DesktopWatchingHub(onOpenEpisode: (WatchingEpisode) -> Unit) {
    val vm = remember { KoinPlatform.getKoin().get<WatchingHubViewModel>() }
    val state by vm.state.collectAsState()
    var calendar by remember { mutableStateOf(false) }
    val alerts = remember { KoinPlatform.getKoin().get<com.torve.desktop.reminders.DesktopEpisodeAlerts>() }
    val alertState by alerts.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<WatchingEpisode?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TorveFilterChip(ds("Up Next"), !calendar, { calendar = false })
        TorveFilterChip(ds("Calendar"), calendar, { calendar = true })
        TorveGhostButton(ds("Refresh"), { vm.refresh(true) }, enabled = !state.loading)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(state.hideSpoilers, vm::setHideSpoilers)
        Text(ds("Hide unwatched episode spoilers"))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(state.episodeNotificationsEnabled, { enabled -> scope.launch { vm.setEpisodeNotificationsEnabled(enabled); if (enabled) alerts.check() } })
        Text(ds("Episode alerts"))
    }
    if (state.episodeNotificationsEnabled) {
        Text(ds("Checks every six hours while Torve is running, including in the system tray."), style = MaterialTheme.typography.bodySmall)
        TorveGhostButton(ds("Check episodes now"), alerts::check, enabled = !alertState.checking)
        if (alertState.person == state.personalDataUserId) {
            alertState.completedAt?.let { Text("${ds("Last completed check")}: ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}") }
            alertState.error?.let { Text(ds(it), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { Text(ds(it), color = MaterialTheme.colorScheme.error) }
    val rows = if (calendar) state.calendar else state.upNext
    if (!state.loading && rows.isEmpty()) Text(ds("Shows you watch or save will appear here."))
    var lastDate: String? = null
    rows.forEach { row ->
        if (calendar && row.date?.toString() != lastDate) {
            lastDate = row.date?.toString()
            Text(lastDate.orEmpty(), style = MaterialTheme.typography.titleMedium)
        }
        DesktopWatchingRow(row, state.hideSpoilers, { onOpenEpisode(row) }) {
            val marking = "${row.show.id}_${row.season}_${row.episode.episodeNumber}" in state.markingEpisodes
            if (!row.watched && row.date?.let { it <= state.today } == true) {
                TorveGhostButton(ds("Mark watched"), { confirm = row }, enabled = !marking)
            }
        }
    }
    confirm?.let { row ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(ds("Mark this episode watched?")) },
            text = { Text("${row.show.title} · S${row.season} E${row.episode.episodeNumber}") },
            confirmButton = { TextButton(onClick = { vm.markWatched(row); confirm = null }) { Text(ds("Mark watched")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(ds("Cancel")) } })
    }
}

@Composable
fun DesktopWatchingRow(
    row: WatchingEpisode,
    hideSpoilers: Boolean,
    onOpen: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val episode = row.episode.forPresentation(hideSpoilers, row.watched)
    val art = rememberCachedBitmap(episode.stillUrl ?: row.show.posterUrl)
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            art?.let { Image(it, null, Modifier.width(96.dp).height(60.dp), contentScale = ContentScale.Crop) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.show.title, style = MaterialTheme.typography.titleSmall)
                Text("S${row.season} E${episode.episodeNumber} · ${episode.name}", style = MaterialTheme.typography.bodyMedium)
                row.positionMs?.takeIf { row.resume }?.let { Text("${ds("Resume")} · ${it / 60_000}:${(it / 1000 % 60).toString().padStart(2, '0')}") }
                row.progressPercent?.let { LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorveGhostButton(ds("Open episode"), onOpen)
                    actions()
                }
            }
        }
    }
}

@Composable
private fun DesktopProfiles() {
    val scope = rememberCoroutineScope()
    val vm = remember { ProfileViewModel(KoinPlatform.getKoin().get<ProfileRepository>(), scope) }
    val state by vm.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<UserProfile?>(null) }
    Text(ds("Each profile keeps its own progress, history, watchlist and tracking connections."))
    if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { Text(ds(it), color = MaterialTheme.colorScheme.error) }
    state.profiles.forEach { profile ->
        TorveListRow(profile.name, if (profile.isActive) ds("Active profile") else if (!profile.pin.isNullOrBlank()) ds("PIN protected") else "",
            selected = profile.isActive,
            trailing = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorveGhostButton(ds("Switch"), { vm.switchProfile(profile.id) }, enabled = !profile.isActive && !state.isSavingProfile)
                    TorveGhostButton(ds("Edit"), { vm.requestProfileManagement(profile.id) }, enabled = !state.isSavingProfile)
                }
            })
    }
    TorveTextField(name, { name = it }, ds("Profile name"))
    TorvePrimaryButton(ds("Create profile"), { vm.createProfile(name); name = "" },
        enabled = name.isNotBlank() && !state.isSavingProfile && state.profiles.size < ProfileViewModel.MAX_PROFILES)
    state.pinPromptProfileId?.let { id ->
        var pin by remember(id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = vm::dismissPinPrompt, title = { Text(ds("Enter profile PIN")) },
            text = { Column {
                TorveTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, ds("PIN"), visualTransformation = PasswordVisualTransformation())
                state.pinError?.let { Text(ds(it), color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = { vm.verifyPinAndSwitch(id, pin); pin = "" }, enabled = pin.length == 4) { Text(ds("Continue")) } },
            dismissButton = { TextButton(onClick = vm::dismissPinPrompt) { Text(ds("Cancel")) } })
    }
    state.editingProfile?.let { profile ->
        var editName by remember(profile.id) { mutableStateOf(profile.name) }
        var rating by remember(profile.id) { mutableStateOf(profile.maxContentRating) }
        var changePin by remember(profile.id) { mutableStateOf(false) }
        var pin by remember(profile.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = vm::dismissEditDialog, title = { Text(ds("Edit profile")) },
            text = { Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TorveTextField(editName, { editName = it }, ds("Profile name"))
                Text(ds("Maximum content rating"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TorveFilterChip(ds("All"), rating == null, { rating = null })
                    ContentRating.entries.forEach { value -> TorveFilterChip(value.name, rating == value, { rating = value }) }
                }
                Row { Checkbox(changePin, { changePin = it }); Text(ds("Change or remove PIN")) }
                if (changePin) TorveTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, ds("New PIN (empty removes PIN)"), visualTransformation = PasswordVisualTransformation())
                state.error?.let { Text(ds(it), color = MaterialTheme.colorScheme.error) }
                TorveGhostButton(ds("Delete profile"), { deleting = profile }, enabled = profile.id != "default" && !profile.isActive && state.profiles.size > 1 && !state.isSavingProfile)
            } },
            confirmButton = { TextButton(onClick = { vm.saveProfile(profile.id, editName, rating, pin, changePin) },
                enabled = editName.isNotBlank() && !state.isSavingProfile && (!changePin || pin.isEmpty() || pin.length == 4)) { Text(ds("Save")) } },
            dismissButton = { TextButton(onClick = vm::dismissEditDialog) { Text(ds("Cancel")) } })
    }
    deleting?.let { profile ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(ds("Delete profile?")) }, text = { Text(profile.name) },
            confirmButton = { TextButton(onClick = { vm.deleteProfile(profile.id); deleting = null }) { Text(ds("Delete")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(ds("Cancel")) } })
    }
}

@Composable
private fun DesktopCatalogGroups(home: HomeUiState, customSections: List<CustomSection>, onOpenDetail: (MediaItem) -> Unit) {
    val repo = remember { KoinPlatform.getKoin().get<CatalogGroupRepository>() }
    val groups by repo.groups.collectAsState()
    val sources = catalogGroupSources(home, customSections)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CatalogGroup?>(null) }
    var deleting by remember { mutableStateOf<CatalogGroup?>(null) }
    var browsing by remember { mutableStateOf<CatalogGroup?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun mutate(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Could not save catalog groups. Try again." }
            finally { busy = false }
        }
    }
    LaunchedEffect(repo) { repo.refresh() }
    error?.let { Text(ds(it), color = MaterialTheme.colorScheme.error) }
    TorvePrimaryButton(ds("Create group"), { editing = CatalogGroup(java.util.UUID.randomUUID().toString(), "", emptyList()) }, enabled = !busy)
    groups.forEach { group ->
        TorveListRow(group.name, "${group.catalogIds.size} ${ds("catalogs")} · ${if (group.pinned) ds("Pinned to Home") else ds("Not pinned")}",
            onClick = { browsing = group }, trailing = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorveGhostButton(ds("Edit"), { editing = group }, enabled = !busy)
                    TorveGhostButton(ds("Delete"), { deleting = group }, enabled = !busy)
                }
            })
    }
    if (groups.isEmpty()) Text(ds("Combine catalogs into a personal group and pin it to Home."))
    browsing?.let { group ->
        Text(group.name, style = MaterialTheme.typography.titleMedium)
        mergeCatalogGroup(group, sources).forEach { item -> TorveListRow(item.title, item.year?.toString().orEmpty(), onClick = { onOpenDetail(item) }) }
    }
    editing?.let { group ->
        var name by remember(group.id) { mutableStateOf(group.name) }
        var ids by remember(group.id) { mutableStateOf(group.catalogIds.toSet()) }
        var pinned by remember(group.id) { mutableStateOf(group.pinned) }
        var presentation by remember(group.id) { mutableStateOf(group.presentation) }
        AlertDialog(onDismissRequest = { if (!busy) editing = null }, title = { Text(ds("Catalog group")) },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TorveTextField(name, { name = it }, ds("Group name"))
                Row { Checkbox(pinned, { pinned = it }); Text(ds("Pin to Home")) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CatalogGroupPresentation.entries.forEach { value -> TorveFilterChip(ds(value.name.lowercase().replaceFirstChar(Char::uppercase)), presentation == value, { presentation = value }) }
                }
                sources.forEach { source -> Row { Checkbox(source.id in ids, { ids = if (it) ids + source.id else ids - source.id }); Text(source.title) } }
                // Preserve temporarily unavailable sources while allowing explicit removal.
                (ids - sources.map { it.id }.toSet()).forEach { id -> Row { Checkbox(true, { ids = ids - id }); Text("$id (${ds("Unavailable")})") } }
                if (sources.isEmpty()) Text(ds("Load your Home catalogs before choosing sources."))
                error?.let { Text(ds(it), color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = { mutate { repo.save(group.copy(name = name, catalogIds = ids.toList(), pinned = pinned, presentation = presentation)); editing = null } },
                enabled = name.isNotBlank() && ids.isNotEmpty() && !busy) { Text(ds("Save")) } },
            dismissButton = { TextButton(onClick = { editing = null }, enabled = !busy) { Text(ds("Cancel")) } })
    }
    deleting?.let { group -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(ds("Delete group?")) }, text = { Text(group.name) },
        confirmButton = { TextButton(onClick = { mutate { repo.delete(group.id); deleting = null; browsing = null } }, enabled = !busy) { Text(ds("Delete")) } },
        dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text(ds("Cancel")) } }) }
}

@Composable
private fun DesktopTraktLists(target: MediaItem?, onOpenDetail: (MediaItem) -> Unit) {
    val vm = remember { KoinPlatform.getKoin().get<TraktListsViewModel>() }
    val state by vm.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<com.torve.data.trakt.TraktPersonalList?>(null) }
    var deleting by remember { mutableStateOf<com.torve.data.trakt.TraktPersonalList?>(null) }
    LaunchedEffect(vm, target) { vm.load(target) }
    DisposableEffect(vm) { onDispose { vm.clear() } }
    if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (!state.connected) Text(ds("Connect Trakt in Settings > Integrations to manage your lists."))
    state.error?.let { Text(ds(it), color = MaterialTheme.colorScheme.error) }
    state.message?.let { Text(ds(it)) }
    TorveGhostButton(ds("Refresh"), { vm.load(target) }, enabled = !state.loading && !state.busy)
    if (state.connected && state.selected == null) {
        target?.let { Text(it.title, style = MaterialTheme.typography.titleMedium) }
        state.lists.forEach { list ->
            TorveListRow(list.name, list.description.orEmpty(), trailing = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (target != null) TorveGhostButton(ds(if (state.membership[list.ids.trakt] == true) "Remove title" else "Add title"), { vm.toggleMembership(list) }, enabled = !state.busy)
                    TorveGhostButton(ds("Open"), { vm.browse(list) }, enabled = !state.busy)
                    TorveGhostButton(ds("Rename"), { name = list.name; renaming = list }, enabled = !state.busy)
                    TorveGhostButton(ds("Delete"), { deleting = list }, enabled = !state.busy)
                }
            })
        }
        TorveTextField(name, { name = it }, ds("List name"))
        TorvePrimaryButton(ds("Create list"), { vm.create(name.trim()) { name = "" } }, enabled = name.isNotBlank() && !state.busy)
    }
    state.selected?.let { list ->
        TorveGhostButton(ds("Back to lists"), vm::closeList, enabled = !state.busy)
        Text(list.name, style = MaterialTheme.typography.titleMedium)
        if (state.items.isEmpty()) Text(ds("This list is empty."))
        state.items.forEach { item -> TorveListRow(item.title, item.year?.toString().orEmpty(), onClick = { onOpenDetail(item) }) }
    }
    renaming?.let { list -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text(ds("Rename list")) },
        text = { Column { TorveTextField(name, { name = it }, ds("List name")); state.error?.let { Text(ds(it)) } } },
        confirmButton = { TextButton(onClick = { vm.rename(list, name.trim()) { renaming = null; name = "" } }, enabled = name.isNotBlank() && !state.busy) { Text(ds("Save")) } },
        dismissButton = { TextButton(onClick = { renaming = null }, enabled = !state.busy) { Text(ds("Cancel")) } }) }
    deleting?.let { list -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(ds("Delete list?")) }, text = { Column { Text(list.name); state.error?.let { Text(ds(it)) } } },
        confirmButton = { TextButton(onClick = { vm.delete(list) { deleting = null } }, enabled = !state.busy) { Text(ds("Delete")) } },
        dismissButton = { TextButton(onClick = { deleting = null }, enabled = !state.busy) { Text(ds("Cancel")) } }) }
}
