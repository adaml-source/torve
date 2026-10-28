package com.torve.android.ui.community

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.torve.data.mdblist.RatingsEnricher
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.torve.android.ui.components.*
import com.torve.android.ui.product.ProductPage
import com.torve.data.addon.toMediaItem
import com.torve.data.addon.StremioAddonClient
import com.torve.data.addon.catalogueShelfId
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.domain.model.*
import com.torve.domain.repository.AddonRepository
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

private data class BrowserCatalogue(val addon: InstalledAddon, val catalog: AddonCatalog) {
    val key get() = addon.manifest.catalogueShelfId(catalog)
    val title get() = "${addon.manifest.name} · ${catalog.name ?: catalog.id}"
}

private class BrowserPageCache : ViewModel() {
    val values = mutableStateOf<List<MediaItem>>(emptyList())
    val hasMore = mutableStateOf(false)
    val nextOffset = mutableIntStateOf(0)
    var request: String? = null
}

/** Provider catalogues retain their own search, pagination, genres and title identities. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtensionBrowserScreen(initialShelfId: String = "", searchMode: Boolean = false, mediaType: String = "all",
    isTv: Boolean = false, requestEntryFocus: Boolean = true, onEntryFocused: (FocusRequester) -> Unit = {},
    onFirstContentRequester: (FocusRequester) -> Unit = {}, railFocusRequester: FocusRequester? = null,
    onBack: (() -> Unit)? = null, onMedia: (MediaItem) -> Unit, onManage: () -> Unit,
    ratings: RatingsEnricher = koinInject(), settings: SettingsViewModel = koinInject(), repository: AddonRepository = koinInject(),
    client: StremioAddonClient = koinInject(), ids: UserIdProvider = koinInject(),
    policy: ContentPolicyRepository = koinInject(), contentFilter: ContentPolicyFilter = koinInject()) {
    val preferences by settings.state.collectAsState()
    val policyState by policy.state.collectAsState()
    key(preferences.personalDataUserId, searchMode, initialShelfId, mediaType) {
        val pageCache: BrowserPageCache = viewModel(key = "extension:${preferences.personalDataUserId}:$searchMode:$initialShelfId:$mediaType",
            factory = remember { viewModelFactory { initializer { BrowserPageCache() } } })
        var catalogues by remember { mutableStateOf<List<BrowserCatalogue>>(emptyList()) }
        var selected by rememberSaveable { mutableStateOf(initialShelfId.removePrefix("addon:")) }
        var query by rememberSaveable { mutableStateOf("") }
        var submitted by rememberSaveable { mutableStateOf("") }
        var genre by rememberSaveable { mutableStateOf<String?>(null) }
        var skip by rememberSaveable { mutableIntStateOf(0) }
        var values by pageCache.values
        var hasMore by pageCache.hasMore
        var nextOffset by pageCache.nextOffset
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var menu by remember { mutableStateOf(false) }
        var revision by remember { mutableIntStateOf(0) }
        val grid = rememberPageGrid(if (isTv) "tv_extension_catalogue" else "extension_catalogue", if (isTv) 5 else 3)
        val entry = remember { FocusRequester() }
        LaunchedEffect(entry) { if (isTv) onFirstContentRequester(entry) }
        val keyboard = LocalSoftwareKeyboardController.current
        val imeVisible = WindowInsets.isImeVisible
        BackHandler(enabled = onBack != null || imeVisible) { if (imeVisible) { keyboard?.hide(); if (isTv) entry.requestFocus() } else onBack?.invoke() }
        LaunchedEffect(requestEntryFocus) { if (isTv && requestEntryFocus) { withFrameNanos { }; entry.requestFocus() } }
        LaunchedEffect(preferences.personalDataUserId) {
            ids.withPersonalDataScope {
                catalogues = repository.getEnabledAddons().flatMap { addon -> addon.manifest.catalogs.filter { cat ->
                    (mediaType == "all" || cat.type == mediaType || (mediaType == "tv" && cat.type == "series")) &&
                        (!searchMode || cat.extra.any { it.name == "search" }) && !AiRecommendationCatalogPolicy.isRemoved(cat.id, cat.name.orEmpty())
                }.map { BrowserCatalogue(addon, it) } }
                if (catalogues.none { it.key == selected }) selected = catalogues.firstOrNull()?.key.orEmpty()
            }
        }
        val current = catalogues.firstOrNull { it.key == selected }
        val requiredSearch = current?.catalog?.extra?.any { it.name == "search" && it.isRequired } == true
        LaunchedEffect(current?.key, submitted, genre, skip, revision) {
            val catalogue = current ?: return@LaunchedEffect
            if (pageCache.request == null && skip > 0) { skip = 0; return@LaunchedEffect }
            val request = "${catalogue.key}:$submitted:$genre:$skip:$revision"
            if (pageCache.request == request) return@LaunchedEffect
            if (skip == 0) values = emptyList()
            if ((requiredSearch || searchMode) && submitted.trim().length < 2) { hasMore = false; return@LaunchedEffect }
            busy = true; error = null
            try {
                ids.withPersonalDataScope {
                    val result = client.fetchCatalog(catalogue.addon.manifestUrl.removeSuffix("/manifest.json").trimEnd('/'),
                        catalogue.catalog.type, catalogue.catalog.id,
                        skip = skip.takeIf { catalogue.catalog.extra.any { it.name == "skip" } }, genre = genre,
                        search = submitted.takeIf { it.isNotBlank() && catalogue.catalog.extra.any { it.name == "search" } }, failOnError = true)
                    val incoming = result.metas.map { it.copy(type = it.type.ifBlank { catalogue.catalog.type }).toMediaItem() }
                    val previous = if (skip == 0) emptyList() else values
                    values = (previous + incoming).distinctBy { "${it.type}:${it.id}" }
                    nextOffset = skip + incoming.size
                    hasMore = incoming.isNotEmpty() && values.size > previous.size && catalogue.catalog.extra.any { it.name == "skip" }
                    pageCache.request = request
                    busy = false
                    // The same bounded IMDb/RT enrichment pipeline used by the other shelves.
                    for (batch in values.chunked(12)) {
                        val enriched = ratings.enrichList(batch, preferences.mdblistApiKey).associateBy { "${it.type}:${it.id}" }
                        values = values.map { enriched["${it.type}:${it.id}"] ?: it }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Couldn’t load this catalogue. Check the extension connection and try again." }
            finally { busy = false }
        }
        val allowed = contentFilter.filterItems(policyState, if (searchMode) ContentAccessContext.DIRECT_SEARCH else ContentAccessContext.ADDON_SHELF, values, ContentSourceType.ADDON).items
        CompositionLocalProvider(LocalCardStyle provides resolveCardStyle(preferences.cardStylePresets, null, preferences.globalDefaultPresetId), LocalRatingPrefs provides preferences.ratingPrefs) {
            ProductPage(localizedUiText(if (searchMode) "Search" else "Catalogue"), onBack = onBack, actions = { PageGridMenu(grid) }) {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    TextButton(onClick = { menu = true }, modifier = Modifier.focusRequester(entry)
                        .focusProperties { railFocusRequester?.let { left = it } }
                        .onFocusChanged { if (it.isFocused) onEntryFocused(entry) }) { Text(localizedUiText(current?.title ?: "Choose a catalogue")) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) { catalogues.forEach { value ->
                        DropdownMenuItem(text = { Text(value.title) }, onClick = { selected = value.key; skip = 0; genre = null; menu = false })
                    } }
                }
                if (current?.catalog?.extra?.any { it.name == "search" } == true) Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isTv) com.torve.android.tv.components.TvClickToEditSearchField(query, { query = it }, Modifier.weight(1f))
                    else OutlinedTextField(query, { query = it }, label = { Text(localizedUiText("Search this catalogue")) }, modifier = Modifier.weight(1f), singleLine = true)
                    Button(onClick = { submitted = query.trim(); skip = 0; revision++ }, enabled = !busy && query.trim().length >= 2) { Text(localizedUiText("Search")) }
                }
                val genres = current?.catalog?.extra?.firstOrNull { it.name == "genre" }?.options.orEmpty()
                if (genres.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(genre == null, { genre = null; skip = 0 }, label = { Text(localizedUiText("All genres")) })
                    genres.forEach { value -> FilterChip(genre == value, { genre = value; skip = 0 }, label = { Text(value) }) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
                error?.let { Text(localizedUiText(it), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error); TextButton(onClick = { revision++ }) { Text(localizedUiText("Try again")) } }
                if (catalogues.isEmpty()) {
                    Text(localizedUiText(if (searchMode) "No installed catalogue supports search." else "No catalogue is installed."), Modifier.padding(20.dp))
                    TextButton(onClick = onManage, modifier = Modifier.padding(horizontal = 20.dp)) { Text(localizedUiText("Manage extensions")) }
                } else if (!busy && allowed.isEmpty() && error == null) Text(localizedUiText(if ((searchMode || requiredSearch) && submitted.isBlank()) "Enter a search to see titles from this catalogue." else "No titles to show."), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyVerticalGrid(GridCells.Fixed(grid.adaptiveColumns()), Modifier.weight(1f), contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(allowed, key = { "${it.type}:${it.id}" }) { item -> PosterCard(item, { onMedia(item) }, fillCell = true) }
                    if (hasMore) item(span = { GridItemSpan(maxLineSpan) }) { TextButton(onClick = { skip = nextOffset }, enabled = !busy) { Text(localizedUiText("Load more")) } }
                }
            }
        }
    }
}
