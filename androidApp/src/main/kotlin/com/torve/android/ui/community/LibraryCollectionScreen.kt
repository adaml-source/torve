package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.torve.android.ui.components.*
import com.torve.android.ui.product.ProductPage
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.torve.domain.model.*
import com.torve.domain.sports.SportBucket
import com.torve.presentation.apple.*
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatform

/** The same owner-scoped browser and provider contracts used by the Apple applications. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun LibraryCollectionScreen(kind: String, folder: String = "", initialProvider: String = "REAL_DEBRID",
    onBack: () -> Unit, onOpen: (AppleBrowseRow, String) -> Unit,
    onPlay: (String, AppleBrowseRow) -> Unit, onSetup: () -> Unit,
    isTv: Boolean = false,
    settings: SettingsViewModel = koinInject()) {
    if (kind == "sports") {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val preferences by settings.state.collectAsState()
    key(preferences.personalDataUserId, kind, folder) {
        val browser = remember { AppleLibraryBrowser(KoinPlatform.getKoin()) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val keyboard = LocalSoftwareKeyboardController.current
        val imeVisible = WindowInsets.isImeVisible
        val entryFocus = remember { FocusRequester() }
        BackHandler { if (imeVisible) keyboard?.hide() else onBack() }
        LaunchedEffect(isTv) { if (isTv) { withFrameNanos { }; entryFocus.requestFocus() } }
        var query by rememberSaveable { mutableStateOf("") }
        var provider by rememberSaveable { mutableStateOf(initialProvider) }
        var mediaFilter by rememberSaveable { mutableStateOf("") }
        var filter by rememberSaveable { mutableStateOf("") }
        var days by rememberSaveable { mutableIntStateOf(30) }
        var order by rememberSaveable { mutableStateOf("RECENT") }
        var revision by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var page by remember { mutableStateOf<AppleBrowsePage?>(null) }
        var status by remember { mutableStateOf<String?>(null) }
        var remove by remember { mutableStateOf<AppleBrowseRow?>(null) }
        val grid = rememberPageGrid("${if (isTv) "tv_" else ""}collection_$kind", if (isTv) 5 else 3)
        val artwork = kind in setOf("favorites", "history", "requests")
        val title = mapOf("favorites" to "Favorites", "history" to "Viewing history", "requests" to "Requests", "cloud" to "Cloud files", "sports" to "Sports")[kind] ?: "Library"
        LaunchedEffect(query, provider, filter, days, order, revision) {
            busy = true; error = null
            try {
                if (revision > 0) browser.invalidate()
                page = Json.decodeFromString<AppleBrowsePage>(browser.load(kind, query, filter, days, order, folder, provider))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Couldn’t load this collection. Check your connection and try again." }
            finally { busy = false }
        }
        fun request(row: AppleBrowseRow, deleting: Boolean) {
            if (busy) return
            scope.launch {
                busy = true; error = null
                try { browser.requestAction(row.id, deleting); revision++ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Couldn’t update this request. Refresh and try again." }
                finally { busy = false }
            }
        }
        fun open(row: AppleBrowseRow) {
            if (row.kind != "stream") { onOpen(row, provider); return }
            if (busy || !row.ready) return
            scope.launch {
                busy = true; error = null
                try { val url = browser.play(row.id) { status = it }; onPlay(url, row) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Couldn’t prepare this file. Check its provider connection and try again." }
                finally { busy = false; status = null }
            }
        }
        val values = page?.items.orEmpty().filter { mediaFilter.isEmpty() || it.mediaType == mediaFilter }
        CompositionLocalProvider(LocalCardStyle provides resolveCardStyle(preferences.cardStylePresets, null, preferences.globalDefaultPresetId),
            LocalRatingPrefs provides preferences.ratingPrefs) {
            ProductPage(title, onBack = onBack, actions = {
                if (artwork) PageGridMenu(grid)
                IconButton(onClick = { revision++ }, modifier = Modifier.focusRequester(entryFocus)) { Icon(Icons.Rounded.Refresh, localizedUiText("Refresh")) }
            }) {
                if (isTv) com.torve.android.tv.components.TvClickToEditSearchField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                else OutlinedTextField(query, { query = it }, label = { Text(localizedUiText("Search this collection")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("collection.search"))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (artwork) listOf("" to "All", "movie" to "Movies", "tv" to "TV Shows").forEach { (value, label) ->
                        FilterChip(mediaFilter == value, { mediaFilter = value }, label = { Text(localizedUiText(label)) }) }
                    listOf("RECENT" to "Recent", "NAME" to "Name").forEach { (value, label) -> FilterChip(order == value, { order = value }, label = { Text(localizedUiText(label)) }) }
                    if (kind == "cloud") listOf("SIZE_DESC" to "Largest", "SIZE_ASC" to "Smallest").forEach { (value, label) -> FilterChip(order == value, { order = value }, label = { Text(localizedUiText(label)) }) }
                }
                if (kind == "cloud" && folder.isEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("REAL_DEBRID" to "Real-Debrid", "TORBOX" to "TorBox").forEach { (value, label) -> FilterChip(provider == value, { provider = value }, label = { Text(localizedUiText(label)) }) }
                }
                if (kind == "sports") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter.isEmpty(), { filter = "" }, label = { Text(localizedUiText("All sports")) })
                    SportBucket.entries.forEach { value -> FilterChip(filter == value.name, { filter = value.name }, label = { Text(value.label) }) }
                    listOf(7, 30, 90).forEach { value -> FilterChip(days == value, { days = value }, label = { Text(localizedUiText("$value days")) }) }
                }
                val message = error ?: page?.error ?: page?.notice
                if (!message.isNullOrBlank()) Text(localizedUiText(message), Modifier.padding(20.dp), color = if (error != null || page?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (page?.setup != null) TextButton(onClick = onSetup, modifier = Modifier.padding(horizontal = 20.dp)) { Text(localizedUiText("Open connection settings")) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                status?.let { Text(it, Modifier.padding(20.dp)) }
                if (!busy && values.isEmpty() && message == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(localizedUiText("No items to show"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else if (artwork) LazyVerticalGrid(GridCells.Fixed(grid.adaptiveColumns()), Modifier.weight(1f).testTag("collection.grid"),
                    contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(values, key = { it.id }) { row -> Column {
                        PosterCard(row.toMediaItem(), { open(row) }, fillCell = true)
                        if (row.subtitle.isNotBlank()) Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (row.canRetry) TextButton(onClick = { request(row, false) }, enabled = !busy) { Text(localizedUiText("Retry request")) }
                        if (row.canDelete) TextButton(onClick = { remove = row }, enabled = !busy) { Text(localizedUiText("Remove request")) }
                    } }
                } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(values, key = { it.id }) { row -> Surface(onClick = { open(row) }, enabled = row.kind != "stream" || row.ready,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (row.kind == "folder") Icons.Rounded.Folder else Icons.Rounded.PlayCircle, null)
                            Column(Modifier.weight(1f)) { Text(row.title, style = MaterialTheme.typography.titleMedium); Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (row.size > 0) Text(android.text.format.Formatter.formatShortFileSize(context, row.size), style = MaterialTheme.typography.bodySmall) }
                        }
                    } }
                }
            }
        }
        remove?.let { row -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(localizedUiText("Remove request?")) }, text = { Text(row.title) },
            confirmButton = { TextButton(onClick = { remove = null; request(row, true) }) { Text(localizedUiText("Remove")) } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(localizedUiText("Cancel")) } }) }
    }
}

private fun AppleBrowseRow.toMediaItem(): MediaItem {
    fun number(provider: String) = ratings.firstOrNull { it.provider.equals(provider, true) }?.value?.trimEnd('%')?.toFloatOrNull()
    return MediaItem(id = mediaId.ifBlank { id }, type = if (mediaType == "tv") MediaType.SERIES else MediaType.MOVIE,
        title = title, posterUrl = artwork, backdropUrl = backdrop, year = year,
        ratings = MediaRatings(imdbScore = number("imdb"), tmdbScore = number("tmdb"), rottenTomatoesScore = number("ROTTEN_TOMATOES")?.toInt(), rtAudienceScore = number("RT_AUDIENCE")?.toInt(), metacriticScore = number("METACRITIC")?.toInt(), letterboxdScore = number("LETTERBOXD"), traktScore = number("TRAKT")?.times(10), mdblistScore = number("MDBLIST")?.times(10), malScore = number("MAL")))
}
