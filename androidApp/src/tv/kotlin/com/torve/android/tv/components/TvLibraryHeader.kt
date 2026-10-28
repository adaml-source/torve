package com.torve.android.tv.components

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.torve.android.remote.TorveRemoteDialog as Dialog
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.TV_PAGE_END_GUTTER
import com.torve.android.tv.TV_PAGE_TOP_GUTTER
import com.torve.android.ui.theme.*

/** A shared physical boundary keeps scrolling titles and posters below the menu. */
@Composable
internal fun TvLibraryPage(header: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        header()
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("library.content")) { content() }
    }
}

/** Library actions share the same baseline and focus treatment as the tabs. */
@Composable
internal fun TvLibraryHeader(
    labels: List<String>, selectedIndex: Int, toolsRequester: FocusRequester,
    tabRequesters: List<FocusRequester>, railRequester: FocusRequester,
    onTools: () -> Unit, onTab: (Int) -> Unit,
    onFocused: (FocusRequester) -> Unit, modifier: Modifier = Modifier,
    onFirstRequester: (FocusRequester) -> Unit = {},
    yourShowsRequester: FocusRequester? = null, onYourShows: (() -> Unit)? = null,
    filesRequester: FocusRequester? = null, onFiles: (() -> Unit)? = null,
    yourShowsSelected: Boolean = false,
) {
    LazyRow(modifier.testTag("library_header"),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(start = TV_PAGE_CONTENT_GUTTER, end = TV_PAGE_END_GUTTER,
            top = TV_PAGE_TOP_GUTTER, bottom = 8.dp)) {
        item("library_workflows") {
            LibraryHeaderChip(localizedUiText("Tools"), false, toolsRequester,
                Modifier.testTag("library_tools_button").focusProperties { left = railRequester; right = filesRequester ?: tabRequesters.first() },
                { onFocused(toolsRequester) }, onTools, Icons.Filled.Tune)
        }
        if (filesRequester != null && onFiles != null) item("library_files") {
            LibraryHeaderChip(androidx.compose.ui.res.stringResource(com.torve.android.R.string.gap_files_title), false, filesRequester,
                Modifier.testTag("library_files_button").focusProperties { left = toolsRequester; right = tabRequesters.first() },
                { onFocused(filesRequester) }, onFiles, Icons.Filled.FolderOpen)
        }
        labels.forEachIndexed { index, label ->
            val requester = tabRequesters[index]
            item("tab_$index") {
            if (index == 0) onFirstRequester(requester)
            LibraryHeaderChip(label, index == selectedIndex, requester,
                Modifier.testTag("library_tab_$index").focusProperties {
                    if (index == 0) { left = filesRequester ?: toolsRequester; if (yourShowsRequester != null) right = yourShowsRequester }
                    if (index == 1 && yourShowsRequester != null) left = yourShowsRequester
                },
                { onFocused(requester) }, { onTab(index) })
            }
            if (index == 0 && yourShowsRequester != null && onYourShows != null) item("your_shows") {
                LibraryHeaderChip(localizedUiText("Your shows"), yourShowsSelected, yourShowsRequester,
                    Modifier.testTag("library_your_shows").focusProperties {
                        left = requester; right = tabRequesters.getOrElse(1) { requester }
                    }, { onFocused(yourShowsRequester) }, onYourShows)
            }
        }
    }
}

@Composable
private fun LibraryHeaderChip(label: String, selected: Boolean, requester: FocusRequester,
    modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit, icon: ImageVector? = null) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.height(36.dp).focusRequester(requester).onFocusChanged {
        focused = it.isFocused; if (it.isFocused) onFocused()
    }.tvGlassControl(focused, selected, cornerRadius = 18.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, tint = if (focused) AmberLight else Silver, modifier = Modifier.size(17.dp))
        Text(localizedUiText(label), color = if (selected || focused) Snow else Silver, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

internal enum class TvLibraryTool(val title: String, val description: String, val icon: ImageVector) {
    CATALOGS("Catalog groups", "Combine catalogs & pin collections", Icons.Filled.Folder),
    TRAKT("Trakt lists", "Browse & manage your personal lists", Icons.Filled.BookmarkBorder),
    PROFILES("Household profiles", "Separate watchlists & progress", Icons.Filled.PeopleAlt),
}

/** A focused, compact menu instead of a full-width form dialog. */
@Composable
internal fun TvLibraryToolsMenu(available: List<TvLibraryTool>, onSelect: (TvLibraryTool) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) {
            repeat(16) {
                withFrameNanos { }
                if (hasFocus) return@LaunchedEffect
                runCatching { first.requestFocus() }
                withFrameNanos { }
                if (hasFocus) return@LaunchedEffect
            }
        }
        val shape = RoundedCornerShape(18.dp)
        Column(Modifier.testTag("library_tools_menu").width(440.dp).onFocusChanged { hasFocus = it.hasFocus }.focusGroup()
            .background(Charcoal, shape).border(1.dp, Steel.copy(alpha = .3f), shape).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(localizedUiText("Library tools"), color = Snow, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(localizedUiText("Explore & organize"), color = Silver, style = MaterialTheme.typography.bodySmall)
                }
                LibraryMenuClose(if (available.isEmpty()) Modifier.focusRequester(first) else Modifier, onDismiss)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                available.forEachIndexed { index, tool -> key(tool) {
                    LibraryMenuRow(tool, if (index == 0) Modifier.focusRequester(first) else Modifier) { onSelect(tool) }
                } }
            }
        }
    }
}

@Composable
private fun LibraryMenuRow(tool: TvLibraryTool, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.testTag("library_tool_${tool.name.lowercase()}").fillMaxWidth().height(64.dp)
        .onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 1.dp, if (focused) AmberLight else Color.Transparent, shape)
        .background(if (focused) AmberLight.copy(alpha = .10f) else Color.Transparent, shape)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(tool.icon, null, tint = if (focused) AmberLight else Silver, modifier = Modifier.size(23.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(localizedUiText(tool.title), color = Snow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(localizedUiText(tool.description), color = Silver, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = if (focused) AmberLight else Steel, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LibraryMenuClose(modifier: Modifier, onDismiss: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.size(36.dp).onFocusChanged { focused = it.isFocused }
        .border(1.5.dp, if (focused) AmberLight else Color.Transparent, shape)
        .background(if (focused) AmberLight.copy(alpha = .10f) else Color.Transparent, shape)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Close, localizedUiText("Close library tools"), tint = if (focused) AmberLight else Silver, modifier = Modifier.size(20.dp))
    }
}
