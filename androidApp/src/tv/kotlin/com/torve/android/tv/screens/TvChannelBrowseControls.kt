package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.tv.components.tvLiquidGlass
import com.torve.domain.model.ChannelPlaylist
import com.torve.presentation.channels.ChannelsFilterType
import com.torve.presentation.channels.EpgState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TvChannelGuideToolbar(
    showGuide: Boolean,
    windowStartMs: Long,
    firstFocusRequester: FocusRequester,
    dayFocusRequester: FocusRequester,
    isFocusEnabled: Boolean,
    onFocused: (FocusRequester) -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onSelectList: () -> Unit,
    onSelectGuide: () -> Unit,
    onNow: () -> Unit,
    onTonight: () -> Unit,
    onDay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requesters = remember { List(7) { FocusRequester() } }
    val labels = if (showGuide) {
        listOf("List", "Guide", "Now", "Tonight", SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(windowStartMs)), "‹", "›")
    } else listOf("List", "Guide")
    val actions = listOf(onSelectList, onSelectGuide, onNow, onTonight, onDay, onPrevious, onNext)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            val requester = when (index) { 0 -> firstFocusRequester; 4 -> dayFocusRequester; else -> requesters[index] }
            TvChannelControl(
                label = label,
                onClick = actions[index],
                isSelected = (index == 0 && !showGuide) || (index == 1 && showGuide),
                modifier = Modifier
                    .testTag("channels-guide-tool-$index")
                    .focusRequester(requester)
                    .focusProperties { canFocus = isFocusEnabled; up = requester }
                    .onFocusChanged { if (it.isFocused) onFocused(requester) }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.DirectionDown -> { onMoveDown(); true }
                            event.key == Key.DirectionLeft && index == 0 -> { onMoveLeft(); true }
                            event.key == Key.DirectionRight && index == labels.lastIndex -> true
                            else -> false
                        }
                    },
            )
        }
    }
}

@Composable
internal fun TvChannelControl(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, isSelected: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { selected = isSelected }.onFocusChanged { focused = it.isFocused }
            .tvLiquidGlass(focused, isSelected, cornerRadius = 12.dp, focusScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(localizedUiText(label), color = Snow, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp), maxLines = 1)
    }
}

@Composable
internal fun TvChannelBrowseOptions(
    quality: ChannelsFilterType,
    country: String?,
    countries: List<String>,
    eventFilter: TvChannelEventFilter,
    sort: TvChannelBrowseSort,
    playlists: List<ChannelPlaylist>,
    selectedPlaylistId: String?,
    epgState: EpgState,
    onQuality: (ChannelsFilterType) -> Unit,
    onCountry: (String?) -> Unit,
    onEvents: (TvChannelEventFilter) -> Unit,
    onSort: (TvChannelBrowseSort) -> Unit,
    onPlaylist: (String) -> Unit,
    onRetryGuide: () -> Unit,
    onManageSources: () -> Unit,
    onManageGroups: () -> Unit,
    onDismiss: () -> Unit,
) {
    var panel by remember { mutableStateOf("main") }
    val playlistName = playlists.firstOrNull { it.id == selectedPlaylistId }?.name.orEmpty()
    val options = when (panel) {
        "countries" -> listOf("All countries" to { onCountry(null); panel = "main" }) + countries.map { code ->
            "${countryPrimaryLabel(code)} · ${resolveCountryName(code) ?: code}${if (country.equals(code, true)) "  ✓" else ""}" to
                { onCountry(code); panel = "main" }
        }
        "sources" -> playlists.map { playlist ->
            "${playlist.name}${if (playlist.id == selectedPlaylistId) "  ✓" else ""}" to
                { onPlaylist(playlist.id); onDismiss() }
        }
        else -> buildList<Pair<String, () -> Unit>> {
            add("Quality: ${if (quality == ChannelsFilterType.ALL) "All qualities" else filterLabel(quality)}" to {
                val values = listOf(ChannelsFilterType.ALL, ChannelsFilterType.HD, ChannelsFilterType.FHD, ChannelsFilterType.UHD)
                onQuality(values[(values.indexOf(quality).coerceAtLeast(0) + 1) % values.size])
            })
            add("Country: ${country?.let { resolveCountryName(it) ?: it } ?: "All countries"}" to { panel = "countries" })
            add("Events: ${eventFilter.label}" to { onEvents(TvChannelEventFilter.entries[(eventFilter.ordinal + 1) % TvChannelEventFilter.entries.size]) })
            add("Sort: ${sort.label}" to { onSort(if (sort == TvChannelBrowseSort.RECOMMENDED) TvChannelBrowseSort.NAME else TvChannelBrowseSort.RECOMMENDED) })
            if (playlists.size > 1) add("Source: $playlistName" to { panel = "sources" })
            if (epgState != EpgState.NotConfigured && epgState != EpgState.Loading) add("Refresh programme guide" to { onRetryGuide(); onDismiss() })
            add("Channel sources & programme guide" to onManageSources)
            add("Manage channel groups" to onManageGroups)
        }
    }
    TvChannelChoiceDialog(
        title = localizedUiText(when (panel) { "countries" -> "Country"; "sources" -> "Channel source"; else -> "Browse options" }),
        subtitle = (if (panel == "main") playlistName.takeIf(String::isNotBlank) else null)?.let { localizedUiText(it) },
        options = options,
        hint = localizedUiText(if (panel == "main") "Select to change · Back to return to channels" else "Back to browse options"),
        onDismiss = { if (panel != "main") panel = "main" else onDismiss() },
    )
}

@Composable
internal fun TvChannelGuideDayPicker(nowMs: Long, onDay: (Long?) -> Unit, onDismiss: () -> Unit) {
    val format = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    TvChannelChoiceDialog(
        title = localizedUiText("Programme guide"),
        subtitle = localizedUiText("Browse schedules; replay is available where the source supports it."),
        options = listOf("Now" to { onDay(null) }) + (-2..3).map { offset ->
            val start = tvGuideDayStart(nowMs, offset)
            val label = when (offset) { -1 -> "Yesterday"; 0 -> "Today"; 1 -> "Tomorrow"; else -> format.format(Date(start)) }
            "$label · ${format.format(Date(start))}" to { onDay(start) }
        },
        hint = localizedUiText("Back to return to the guide"),
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvChannelChoiceDialog(
    title: String,
    options: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    hint: String = "Back to close",
) {
    val firstFocus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(title) { delay(60); runCatching { firstFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Obsidian.copy(alpha = 0.64f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(480.dp).heightIn(max = 460.dp)
            .tvLiquidGlass(cornerRadius = 24.dp, baseTint = Graphite.copy(alpha = .94f))
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()
            .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Snow, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, color = Silver, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(options) { index, option ->
                    TvChannelControl(option.first, option.second, Modifier.fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocus).focusProperties { up = firstFocus } else Modifier))
                }
            }
            Text(hint, color = Silver, fontSize = 11.sp)
        }
    }
}
