package com.torve.android.ui.replays

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.ui.components.*
import com.torve.android.ui.components.MobileGlassIconButton as IconButton
import com.torve.data.replays.*

/** The phone owns one compact toolbar; website and sport choices share one row. */
@Composable
internal fun MobileReplayBrowserContent(
    state: WebReplayState, query: String, site: String, sport: String,
    onQuery: (String) -> Unit, onSite: (String) -> Unit, onSport: (String) -> Unit,
    onBack: () -> Unit, onSettings: () -> Unit, onRefresh: () -> Unit,
    onEvent: (String) -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier,
) {
    val events = remember(state.events, query, site, sport) { state.events.filter {
        (site.isBlank() || it.websiteId == site) && (sport.isBlank() || it.sport == sport) &&
            (query.isBlank() || it.title.contains(query.trim(), true) || it.competition.contains(query.trim(), true))
    } }
    var showErrors by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize()
        .background(if (com.torve.android.ui.community.LocalPersonalAppearance.current.active) Color.Transparent else colors.background)
        .safeDrawingPadding().imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().testTag("replays.header"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackButton(onClick = onBack)
            Text(localizedUiText("Sports Replays"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onSettings, Modifier.testTag("replays.websites")) { Icon(Icons.Rounded.Language, localizedUiText("Replay websites")) }
            IconButton(onRefresh, Modifier.testTag("replays.refresh"), enabled = !state.loading) {
                if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, localizedUiText("Refresh replays"))
            }
        }
        MobileGlassTextField(query, onQuery, Modifier.fillMaxWidth().testTag("replays.search"),
            placeholder = { Text(localizedUiText("Find a replay")) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = if (query.isNotEmpty()) { { IconButton({ onQuery("") }) { Icon(Icons.Rounded.Close, localizedUiText("Clear replay search")) } } } else null)
        Row(Modifier.fillMaxWidth().testTag("replays.filters"), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ReplayChoice(localizedUiText(state.websites.firstOrNull { it.id == site }?.name ?: "All websites"),
                listOf("" to "All websites") + state.websites.filter { it.enabled }.map { it.id to it.name }, site, onSite,
                Modifier.weight(1f).testTag("replays.site"))
            val sports = state.events.map { it.sport }.distinct().sorted()
            if (sports.size > 1 || sport.isNotBlank()) ReplayChoice(localizedUiText(sport.ifBlank { "All sports" }),
                listOf("" to "All sports") + sports.map { it to it }, sport, onSport,
                Modifier.weight(1f).testTag("replays.sport"))
            Text("${events.size}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        if (state.error != null && events.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(localizedUiText("Some websites couldn’t update"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            TextButton({ showErrors = true }) { Text(localizedUiText("Details")) }
        }
        if (events.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().testTag("replays.empty"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            if (state.loading) CircularProgressIndicator(Modifier.size(36.dp))
            else Icon(Icons.Rounded.SportsBasketball, null, Modifier.size(42.dp), tint = colors.onSurfaceVariant)
            Text(localizedUiText(when { state.loading -> "Finding replays…"; !state.enabled -> "Sports Replays is off"
                query.isNotBlank() || sport.isNotBlank() || site.isNotBlank() -> "No matching replays"
                state.error != null -> "Couldn’t load replays"; else -> "Your replay collection" }),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(localizedUiText(when { !state.enabled -> "Turn on Sports Replays in your website settings."
                state.loading -> "Checking your saved websites."
                query.isNotBlank() || sport.isNotBlank() || site.isNotBlank() -> "Try another website, sport or search."
                state.error != null -> "Check the website details below, then try again."
                else -> "Refresh your saved websites to find recent replays." }),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (!state.loading) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobileGlassOutlinedButton(if (state.enabled) onRefresh else onSettings) { Text(localizedUiText(if (state.enabled) "Try again" else "Website settings")) }
                if (state.error != null) TextButton({ showErrors = true }) { Text(localizedUiText("Website details")) }
            }
        } else LazyVerticalGrid(GridCells.Adaptive(156.dp), Modifier.weight(1f).testTag("replays.grid"),
            contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(events, key = { it.id }) { event ->
                MobileGlassCard(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { onEvent(event.id) }) {
                    AsyncImage(event.artwork, event.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9).background(colors.surfaceVariant.copy(alpha = .35f)))
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(event.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(listOf(event.competition.ifBlank { event.sport }, event.date).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.hasMore) item(span = { GridItemSpan(maxLineSpan) }) {
                MobileGlassOutlinedButton(onMore, Modifier.fillMaxWidth(), enabled = !state.loading) { Text(localizedUiText(if (state.loading) "Updating…" else "Load more replays")) }
            }
        }
    }
    if (showErrors) AlertDialog(onDismissRequest = { showErrors = false }, title = { Text(localizedUiText("Website updates")) },
        text = { Text(localizedUiText(state.error.orEmpty()), Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ showErrors = false; onRefresh() }, enabled = !state.loading) { Text(localizedUiText("Try again")) } },
        dismissButton = { TextButton({ showErrors = false }) { Text(localizedUiText("Done")) } })
}

@Composable private fun ReplayChoice(label: String, options: List<Pair<String, String>>, selected: String,
    onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        MobileGlassOutlinedButton({ expanded = true }, Modifier.fillMaxWidth().height(44.dp)) {
            Text(localizedUiText(label), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (key, name) -> DropdownMenuItem(text = { Text(name) },
                trailingIcon = if (key == selected) { { Icon(Icons.Rounded.Check, localizedUiText("Selected")) } } else null,
                onClick = { onSelect(key); expanded = false }) }
        }
    }
}
