package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import com.torve.android.tv.components.tvLiquidGlass
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.ui.community.SettingsMenuEntry
import com.torve.android.ui.community.SettingsMenus
import com.torve.android.tv.components.tvSettingsLabel as parityLabel

internal val tvSettingsGroups = listOf(
    SettingsMenuEntry("account", "Account", "Sign-in, profiles and devices", children = listOf(
        SettingsMenuEntry("account_devices", "Account & devices", "Sign-in and paired devices", "account"),
        SettingsMenuEntry("profiles", "Profiles", "Manage your household", "profiles"))),
    SettingsMenuEntry("connections", "Sources", "Playback, tracking and connected libraries", children = SettingsMenus.sources(tv = true).filterNot { it.id == "lists" } + listOf(
        SettingsMenuEntry("source_tools", "Additional sources", "Ratings and Kodi devices", children = listOf(
            SettingsMenuEntry("omdb", "OMDb", "Additional ratings and full plots", "OMDb"),
            SettingsMenuEntry("kodi", "Kodi devices", "Connect a Kodi player", "Kodi"))))),
    SettingsMenuEntry("playback", "Playback", "Video, audio, subtitles and episodes", children = listOf(
        SettingsMenuEntry("video", "Video & sources", "Quality, file size and automatic source selection", "Playback"),
        SettingsMenuEntry("audio", "Audio & subtitles", "Languages, subtitles and source scanning", "Audio & subtitles"),
        SettingsMenuEntry("episodes", "Episodes", "Autoplay and next-episode behaviour", "Episodes"),
        SettingsMenuEntry("advanced_playback", "Advanced playback", "Source rules, buffering and remote controls", "advanced_playback"))),
    SettingsMenuEntry("appearance", "Appearance", "Display, cards and Home layout", children = listOf(
        SettingsMenuEntry("display", "Theme & language", "Display mode and app language", "Display"),
        SettingsMenuEntry("background", "Background & spacing", "Personal background and space between rows", "background"),
        SettingsMenuEntry("home_cards", "Home & cards", "Content rows, card styles and ratings", children = listOf(
            SettingsMenuEntry("home_layout", "Home layout", "Choose and arrange your content rows", "home"),
            SettingsMenuEntry("browsing_style", "Browsing style", "Posters or expanding landscape cards", "Browsing"),
            SettingsMenuEntry("cards", "Card appearance", "Global card styles", "cards"),
            SettingsMenuEntry("ratings", "Ratings", "Providers and card badges", "ratings"),
            SettingsMenuEntry("episode_badge", "Episode badges", "Show or hide remaining episode counts", "Badges"))))),
    SettingsMenuEntry("library", "Library & sync", "Lists, collections and backups", children = listOf(
        SettingsMenuEntry("channels", "Channels", "Playlists and programme guide", "Channels"),
        SettingsMenuEntry("library_collections", "Saved collections", "Keep franchises together", "collections"),
        SettingsMenuEntry("library_mdblist", "MDBList", "Manage your lists", "MDBList"),
        SettingsMenuEntry("backup", "Cloud backup", "Save and restore library versions", "backup"))),
    SettingsMenuEntry("advanced", "Help & advanced", "Connection tests, AI Search and support", children = listOf(
        SettingsMenuEntry("tests", "Connection tests", "Check configured services", "tests"),
        SettingsMenuEntry("ai", "AI Search", "Search assistant provider and API key", "AI"),
        SettingsMenuEntry("features", "App features", "Choose which optional sections appear", "features"),
        SettingsMenuEntry("about", "About & help", "App version and support", "about"))),
)

/** Menu focus previews its section; Right enters it and Back restores the clicked row. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TvGroupedSettingsBrowser(
    rail: FocusRequester, entry: FocusRequester?, isActive: Boolean, railFocused: Boolean,
    publish: (FocusRequester) -> Unit, contentFocused: (FocusRequester) -> Unit, moveToRail: () -> Unit,
    openAction: (String) -> Boolean,
    initialAction: String? = null, entryNonce: Int = 0, onInitialActionHandled: () -> Unit = {},
    form: @Composable (action: String, first: FocusRequester, categoryFocus: FocusRequester,
        focused: (FocusRequester) -> Unit, openSetting: (String) -> Unit, back: () -> Unit) -> Unit,
) {
    var pageId by rememberSaveable { mutableStateOf(SettingsMenus.leafForAction(tvSettingsGroups, initialAction)?.id ?: "account") }
    var lastFocusId by rememberSaveable { mutableStateOf("root:account") }
    var focusByPage by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    var history by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val pageState = rememberSaveableStateHolder()
    val page = SettingsMenus.find(tvSettingsGroups, pageId) ?: tvSettingsGroups.first()
    val group = tvSettingsGroups.first { it.id == page.id || SettingsMenus.find(it.children, page.id) != null }
    val leaf = page.action != null
    val entries = page.children
    val roots = remember { tvSettingsGroups.associate { it.id to FocusRequester() } }
    val rows = remember(pageId) { entries.associate { it.id to FocusRequester() } }
    val first = remember(pageId) { FocusRequester() }
    var lastFormRequester by remember(pageId) { mutableStateOf<FocusRequester?>(null) }
    var pendingFocus by remember { mutableStateOf(!railFocused && (leaf || lastFocusId in rows)) }
    val validEntry = if (leaf) lastFormRequester ?: first else rows[lastFocusId] ?: roots[group.id]!!

    LaunchedEffect(initialAction, entryNonce) {
        SettingsMenus.leafForAction(tvSettingsGroups, initialAction)?.let {
            history = emptyList(); pageId = it.id; lastFocusId = ""; pendingFocus = true
            onInitialActionHandled()
        }
    }
    LaunchedEffect(isActive, pageId, pendingFocus, validEntry) {
        if (isActive) {
            publish(validEntry)
            if (pendingFocus) {
                val target = if (leaf) lastFormRequester ?: first else rows[lastFocusId] ?: rows.values.firstOrNull() ?: roots.getValue(group.id)
                withFrameNanos { }
                pendingFocus = false
                if (runCatching { target.requestFocus() }.isFailure) {
                    roots.getValue(group.id).requestFocus()
                }
            }
        }
    }
    fun toRail() { moveToRail(); rail.requestFocus() }
    fun up() {
        val previous = history.lastOrNull()
        val parent = previous?.let { SettingsMenus.find(tvSettingsGroups, it) }
            ?: SettingsMenus.parent(tvSettingsGroups, pageId)
        if (parent == null) toRail()
        else {
            if (previous != null) history = history.dropLast(1)
            if (parent.action == null) focusByPage = focusByPage + (parent.id to pageId)
            lastFocusId = focusByPage[parent.id].orEmpty(); pageId = parent.id
            pendingFocus = true
        }
    }
    BackHandler(isActive && !railFocused) { up() }
    fun open(item: SettingsMenuEntry) {
        lastFocusId = item.id
        focusByPage = focusByPage + (pageId to item.id)
        rows[item.id]?.let(contentFocused)
        if (item.action != null && openAction(item.action)) return
        history = history + pageId
        pageId = item.id
        lastFocusId = focusByPage[item.id].orEmpty(); pendingFocus = true
    }
    fun openSetting(action: String) {
        val target = SettingsMenus.leafForAction(tvSettingsGroups, action) ?: return
        if (openAction(action)) return
        history = history + pageId
        pageId = target.id; lastFocusId = ""; pendingFocus = true
    }
    fun previewGroup(item: SettingsMenuEntry) {
        if (item.id != group.id) {
            // Browsing the menu changes the right pane without entering a form
            // or replaying the focus restoration of a previously opened form.
            pageId = item.id
            history = emptyList()
            pendingFocus = false
        }
        lastFocusId = "root:${item.id}"
    }
    fun chooseGroup(item: SettingsMenuEntry) {
        val destination = if (item.id == group.id) pageId else item.id
        pageId = destination
        lastFocusId = focusByPage[destination].orEmpty()
        pendingFocus = true
    }

    com.torve.android.tv.components.TvSettingsStyle {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxSize().testTag("settings.grouped").padding(top = 16.dp, end = 24.dp)) {
        LazyColumn(Modifier.width(204.dp).fillMaxHeight().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text(parityLabel("Settings"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp)) }
            itemsIndexed(tvSettingsGroups, key = { _, item -> item.id }) { index, item ->
                var focused by remember { mutableStateOf(false) }
                val requester = roots.getValue(item.id)
                Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(requester)
                            .then(if (entry != null && validEntry === requester) Modifier.focusRequester(entry) else Modifier)
                            .focusProperties {
                                left = rail
                                up = if (index == 0) FocusRequester.Cancel else roots.getValue(tvSettingsGroups[index - 1].id)
                                down = if (index == tvSettingsGroups.lastIndex) FocusRequester.Cancel else roots.getValue(tvSettingsGroups[index + 1].id)
                            }
                            .onFocusChanged { focused = it.isFocused; if (it.isFocused) {
                                if (!pendingFocus) previewGroup(item)
                                contentFocused(requester)
                            } }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) false else when(event.key) {
                                    Key.DirectionRight -> { chooseGroup(item); true }
                                    Key.DirectionLeft -> { toRail(); true }
                                    else -> false
                                }
                            }
                            .padding(4.dp).tvLiquidGlass(focused, selected = group.id == item.id, quiet = true)
                            .clickable { chooseGroup(item) }.testTag("settings.category.${item.id}").padding(horizontal = 12.dp, vertical = 14.dp)) {
                    Icon(settingsMenuIcon(item.id), null, Modifier.size(18.dp),
                        tint = if (group.id == item.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(parityLabel(item.title), style = MaterialTheme.typography.titleMedium,
                        fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp)
            .focusProperties {
                exit = { direction ->
                    if (direction == FocusDirection.Up || direction == FocusDirection.Down) FocusRequester.Cancel
                    else FocusRequester.Default
                }
            }.focusGroup().testTag("settings.content")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (page.id != group.id) Text(listOfNotNull(group.title,
                        SettingsMenus.parent(tvSettingsGroups, pageId)?.takeIf { it.id != group.id }?.title)
                        .map { parityLabel(it) }.joinToString(" / "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(parityLabel(page.title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    if (!leaf) Text(parityLabel(page.summary), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
            pageState.SaveableStateProvider(pageId) {
                if (leaf) Box(Modifier.fillMaxSize().then(if (entry != null) Modifier.focusRequester(entry).focusGroup() else Modifier)
                    .focusProperties { left = roots.getValue(group.id) }
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionLeft) false
                        else {
                            if (event.type == KeyEventType.KeyDown) roots.getValue(group.id).requestFocus()
                            true
                        }
                    }.testTag("settings.form.${page.id}")) {
                    form(page.action!!, first, roots.getValue(group.id), { requester ->
                        if (!pendingFocus) lastFormRequester = requester
                        contentFocused(requester)
                    }, ::openSetting, ::up)
                } else LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().testTag("settings.menu.$pageId"),
                    contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(entries, key = { _, item -> item.id }) { index, item ->
                        var focused by remember { mutableStateOf(false) }
                        val requester = rows.getValue(item.id)
                        Surface(onClick = { open(item) }, shape = RoundedCornerShape(20.dp), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().focusRequester(requester)
                                .then(if (entry != null && validEntry === requester) Modifier.focusRequester(entry) else Modifier)
                                .focusProperties {
                                    left = if (index % 2 == 0) roots.getValue(group.id) else rows.getValue(entries[index - 1].id)
                                    right = if (index % 2 == 0 && index + 1 < entries.size) rows.getValue(entries[index + 1].id) else FocusRequester.Cancel
                                    up = if (index >= 2) rows.getValue(entries[index - 2].id) else FocusRequester.Cancel
                                    down = if (index + 2 < entries.size) rows.getValue(entries[index + 2].id) else FocusRequester.Cancel
                                }
                                .onFocusChanged { focused = it.isFocused; if (it.isFocused) {
                                    if (!pendingFocus) {
                                        lastFocusId = item.id
                                        focusByPage = focusByPage + (pageId to item.id)
                                    }
                                    contentFocused(requester)
                                } }
                                .padding(4.dp).tvLiquidGlass(focused)
                                .testTag("settings.item.${item.id}")) {
                            Column(Modifier.height(112.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(30.dp).tvLiquidGlass(quiet = true, cornerRadius = 10.dp), contentAlignment = Alignment.Center) {
                                        Icon(settingsMenuIcon(item.id), null, Modifier.size(18.dp))
                                    }
                                    Text(parityLabel(item.title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(parityLabel(item.summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
}
}

private fun settingsMenuIcon(id: String): ImageVector = when (id) {
    "account", "account_devices" -> Icons.Outlined.AccountCircle
    "profiles" -> Icons.Outlined.People
    "connections", "streaming", "debrid" -> Icons.Outlined.CloudQueue
    "extensions" -> Icons.Outlined.Extension
    "sports_replays", "web_replays" -> Icons.Outlined.PlayCircle
    "nzb", "nzbdav" -> Icons.Outlined.Storage
    "subtitles", "opensubtitles", "audio" -> Icons.Outlined.Subtitles
    "playback", "video", "episodes" -> Icons.Outlined.Tv
    "appearance", "display", "background" -> Icons.Outlined.Palette
    "library", "channels", "library_collections" -> Icons.Outlined.Folder
    "home_cards", "home_layout", "cards" -> Icons.Outlined.GridView
    "advanced", "about" -> Icons.Outlined.HelpOutline
    "tracking", "backup" -> Icons.Outlined.Sync
    else -> Icons.Outlined.Tune
}
