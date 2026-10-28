package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.theme.Amber
import com.torve.android.device.DeviceFormFactor
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.LocalRatingPrefs
import com.torve.domain.model.resolveCardStyle
import com.torve.domain.model.resolvedWidthDp
import com.torve.domain.model.MediaItem
import com.torve.presentation.apple.*
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.getKoin

/** Same saved identities, paging, metadata and account boundaries used by Apple clients. */
@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    movieId: Int? = null,
    collectionId: Int? = null,
    collectionName: String? = null,
    settings: SettingsViewModel = koinInject(),
) {
    val koin = getKoin()
    val service = remember(koin) { AppleCollections(koin) }
    val prefs by settings.state.collectAsState()
    val owner = prefs.personalDataUserId
    val scope = rememberCoroutineScope()
    var selectedSource by rememberSaveable(owner, movieId) { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable(owner, movieId) { mutableStateOf(0) }
    var selectedName by rememberSaveable(owner, movieId) { mutableStateOf("") }
    var query by rememberSaveable(owner, movieId) { mutableStateOf("") }
    var submitted by remember(owner, movieId) { mutableStateOf("") }
    var bookmarks by remember(owner) { mutableStateOf(emptyList<AppleCollectionBookmark>()) }
    var choices by remember(owner, movieId) { mutableStateOf(emptyList<AppleCollectionChoice>()) }
    var items by remember(owner, selectedSource, selectedId) { mutableStateOf(emptyList<MediaItem>()) }
    var page by remember(owner, selectedSource, selectedId, submitted) { mutableIntStateOf(1) }
    var more by remember(owner, selectedSource, selectedId, submitted) { mutableStateOf(false) }
    var loading by remember(owner) { mutableStateOf(false) }
    var saving by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var revision by remember(owner) { mutableIntStateOf(0) }
    fun back() { if (selectedSource != null) selectedSource = null else onBack() }
    BackHandler(onBack = ::back)
    LaunchedEffect(owner, revision, selectedSource, selectedId, submitted, page) {
        loading = true; error = null
        try {
            bookmarks = service.bookmarks()
            val source = selectedSource
            if (source != null) {
                val result = service.contents(source, selectedId, page)
                selectedName = result.name; more = result.hasMore
                items = (if (page == 1) result.items else items + result.items).distinctBy { "${it.type}:${it.id}" }
                loading = false
                // Paint first; enrich every page in bounded batches rather than stopping at twelve.
                for (batch in result.items.chunked(12)) {
                    try {
                        val enriched = service.enrich(batch).associateBy { "${it.type}:${it.id}" }
                        items = items.map { enriched["${it.type}:${it.id}"] ?: it }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Keep source ratings. */ }
                }
            } else {
                val result = service.choices(movieId, submitted, page)
                choices = (if (page == 1) result.options else choices + result.options).distinctBy { it.id }
                more = result.hasMore; error = result.warning
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Collections could not be loaded. Check your connection and try again." }
        finally { loading = false }
    }
    fun open(source: String, id: Int, name: String) {
        selectedId = id; selectedName = name; selectedSource = source; page = 1
    }
    val tv = DeviceFormFactor.isTv(LocalContext.current)
    val backEntry = communityEntryFocus()
    val cardStyle = resolveCardStyle(prefs.cardStylePresets, null, prefs.globalDefaultPresetId)
    CompositionLocalProvider(LocalCardStyle provides cardStyle, LocalRatingPrefs provides prefs.ratingPrefs) {
    PersonalBackground {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cardStyle.size.resolvedWidthDp().dp.coerceAtLeast(if (tv) 148.dp else 110.dp)),
            modifier = Modifier.fillMaxSize().testTag("collections.screen"),
            contentPadding = PaddingValues(if (tv) 32.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CommunityAction(localizedUiText("Back"), ::back, backEntry)
                    Text(localizedUiText(if (selectedSource == null) "Collections" else selectedName),
                        style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                }
            }
            if (selectedSource != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val saved = bookmarks.any { it.source == selectedSource && it.sourceId == selectedId }
                    Column {
                        Text(localizedUiText("Save this group without adding its movies to your watchlist."))
                        CommunityAction(localizedUiText(if (saved) "Remove saved collection" else "Save collection"), {
                            val source = selectedSource ?: return@CommunityAction
                            val id = selectedId
                            scope.launch {
                                saving = true; error = null
                                try {
                                    if (saved) service.remove(source, id)
                                    else service.save(source, id)
                                    bookmarks = service.bookmarks()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "Could not save this change. Please try again." }
                                finally { saving = false }
                            }
                        }, enabled = !loading && !saving)
                    }
                }
                items(items.sortedByDescending { it.ratings?.imdbScore ?: -1f }, key = { "${it.type}:${it.id}" }) { item ->
                    PosterCard(item = item, onClick = { onMediaClick(item) }, fillCell = true)
                }
            } else {
                if (collectionId != null && collectionId > 0) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CommunityAction(localizedUiText(collectionName ?: "Movie franchise"), {
                            open("tmdb", collectionId, collectionName ?: "Collection")
                        }, Modifier.fillMaxWidth())
                    }
                }
                if (bookmarks.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text(localizedUiText("Saved collections"), style = MaterialTheme.typography.titleMedium) }
                    items(bookmarks, key = { "saved:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { saved ->
                        CommunityAction(saved.name, { open(saved.source, saved.sourceId, saved.name) }, Modifier.fillMaxWidth())
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(localizedUiText("Choose a franchise or a broader community list. Preview its movies before saving."))
                        OutlinedTextField(query, { query = it }, label = { Text(localizedUiText("Search collections and MDBList")) },
                            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("collections.query"))
                        CommunityAction(localizedUiText("Search"), { page = 1; submitted = query; revision++ }, enabled = !loading)
                    }
                }
                items(choices, key = { "choice:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { choice ->
                    Column {
                        CommunityAction(choice.name, { open(choice.source, choice.sourceId, choice.name) }, Modifier.fillMaxWidth())
                        Text(localizedUiText("${if (choice.source == "mdblist") "MDBList" else "TMDB"}${if (choice.itemCount > 0) " · ${choice.itemCount} movies" else ""}"),
                            style = MaterialTheme.typography.labelMedium)
                        if (choice.summary.isNotBlank()) Text(choice.summary, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Column { Text(localizedUiText(error!!)); CommunityAction(localizedUiText("Retry"), { revision++ }) }
            }
            if (more && !loading) item(span = { GridItemSpan(maxLineSpan) }) { CommunityAction(localizedUiText("Load more"), { page++ }) }
            if (!loading && selectedSource != null && items.isEmpty() && error == null)
                item(span = { GridItemSpan(maxLineSpan) }) { Text(localizedUiText("This collection has no available movies.")) }
        }
    }
    }
}

@Composable
fun CommunityAction(label: String, action: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (label == "Back" || label == localizedUiText("Back")) {
        com.torve.android.ui.components.BackButton(onClick = action, modifier = modifier, enabled = enabled)
        return
    }
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(action, enabled = enabled,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 0.dp, if (focused) Amber else Color.Transparent, MaterialTheme.shapes.large)) {
        Text(localizedUiText(label))
    }
}

@Composable
fun FullSynopsis(item: MediaItem, settings: SettingsViewModel = koinInject()) {
    val koin = getKoin()
    val prefs by settings.state.collectAsState()
    var story by remember(item.id, prefs.personalDataUserId, prefs.appLanguage) {
        mutableStateOf(ApplePlaybackSynopsis(item.title, item.overview.orEmpty()))
    }
    LaunchedEffect(item.id, item.tmdbId, prefs.personalDataUserId, prefs.appLanguage) {
        story = AppleNativeFeatures(koin).fullSynopsis(item, null, null, item.overview.orEmpty())
    }
    ExpandableStory(localizedUiText("Synopsis"), story.overview, story.source)
}

@Composable
fun ExpandableStory(title: String, text: String, source: String? = null) {
    if (text.isBlank()) return
    var expanded by rememberSaveable(title, text) { mutableStateOf(false) }
    val tv = DeviceFormFactor.isTv(LocalContext.current)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("story.$title")) {
        com.torve.android.ui.components.SectionHeader(title, contentPadding = PaddingValues(0.dp))
        Text(text, maxLines = if (expanded && !tv) Int.MAX_VALUE else 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (text.length > 200) CommunityAction(localizedUiText(if (expanded && !tv) "Show less" else "Read more"), { expanded = !expanded })
        source?.let { Text(localizedUiText("Source: $it"), style = MaterialTheme.typography.labelSmall) }
    }
    if (tv && expanded) SynopsisDialog(ApplePlaybackSynopsis(title, text, source), false, false) { expanded = false }
}

/** Focusable paragraphs let a TV remote read the entire plot without moving playback. */
@Composable
fun SynopsisDialog(story: ApplePlaybackSynopsis?, loading: Boolean, hideSpoilers: Boolean, onClose: () -> Unit) {
    var revealed by remember(story?.title) { mutableStateOf(false) }
    val tv = DeviceFormFactor.isTv(LocalContext.current)
    val closeEntry = communityEntryFocus()
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(localizedUiText("Synopsis"), style = MaterialTheme.typography.titleLarge)
                    CommunityAction(localizedUiText("Done"), onClose, closeEntry)
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        loading -> CircularProgressIndicator()
                        hideSpoilers && !revealed -> CommunityAction(localizedUiText("Reveal synopsis"), { revealed = true })
                        else -> {
                            Text(story?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            val text = story?.overview?.takeIf { it.isNotBlank() } ?: "A synopsis is not available for this title."
                            for (part in readableParagraphs(text)) {
                                if (tv) SynopsisParagraph(part) else Text(part)
                            }
                            story?.source?.let { Text(localizedUiText("Source: $it"), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SynopsisParagraph(text: String) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Text(text, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clip(shape)
            .border(if (focused) 2.dp else 0.dp, if (focused) Amber else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }.clickable {}.padding(14.dp))
}

internal fun readableParagraphs(text: String): List<String> = text.split(Regex("\\n\\s*\\n")).flatMap { paragraph ->
    val parts = mutableListOf<String>(); var remaining = paragraph.trim()
    while (remaining.length > 450) {
        val cut = remaining.lastIndexOf(' ', 450).takeIf { it > 100 } ?: 450
        parts += remaining.take(cut); remaining = remaining.drop(cut).trimStart()
    }
    if (remaining.isNotBlank()) parts += remaining
    parts
}

/** A lazy destination requests entry focus only after its button is attached and placed. */
@Composable
fun communityEntryFocus(): Modifier {
    val requester = remember { FocusRequester() }
    var placed by remember { mutableStateOf(false) }
    val tv = DeviceFormFactor.isTv(LocalContext.current)
    LaunchedEffect(placed) { if (placed && tv) requester.requestFocus() }
    return Modifier.focusRequester(requester).onGloballyPositioned { placed = true }
}
