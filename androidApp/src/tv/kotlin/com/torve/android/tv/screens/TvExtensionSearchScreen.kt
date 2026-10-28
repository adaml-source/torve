package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.torve.android.tv.components.rememberTvVisibleMetadata
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.data.addon.StremioAddonClient
import com.torve.data.addon.toMediaItem
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.data.contentpolicy.ContentPolicyRepository
import com.torve.domain.model.*
import com.torve.domain.repository.AddonRepository
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.compose.koinInject

/** All installed title searches share one TV toolbar, filter panel and result grid. */
@Composable
internal fun TvExtensionSearchScreen(
    railFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    shouldAutoFocus: Boolean,
    onManage: () -> Unit,
    initialQuery: String = "",
    initialFilterType: String? = null,
) {
    val settings: SettingsViewModel = koinInject()
    val repository: AddonRepository = koinInject()
    val client: StremioAddonClient = koinInject()
    val ids: UserIdProvider = koinInject()
    val policy: ContentPolicyRepository = koinInject()
    val filter: ContentPolicyFilter = koinInject()
    val preferences by settings.state.collectAsState()
    val policyState by policy.state.collectAsState()
    val watchedRevision by produceState(0, filter, preferences.personalDataUserId) {
        filter.awaitProfileBoundary()
        filter.discoveryChanges().collect { value += 1 }
    }
    val model: TvExtensionSearchState = viewModel(key = "tv-extension-search:${preferences.personalDataUserId}",
        factory = remember { viewModelFactory { initializer { TvExtensionSearchState() } } })
    var sources by remember(preferences.personalDataUserId) { mutableStateOf<List<TvExtensionSearchSource>>(emptyList()) }
    LaunchedEffect(initialQuery, initialFilterType) {
        if (initialQuery.isNotBlank() && model.query != initialQuery) { model.query = initialQuery; model.revision++ }
        if (initialFilterType != null) model.filters = model.filters.copy(type = initialFilterType)
    }
    LaunchedEffect(preferences.personalDataUserId) {
        sources = withContext(Dispatchers.IO) { ids.withPersonalDataScope { tvExtensionSearchSources(repository.getEnabledAddons()) } }
        model.hasAi = sources.any { it.isAi }
        model.sourceCount = sources.count { !it.isAi }
    }
    val requestKey = "${model.query}:${model.aiMode}:${model.revision}:${sources.map { it.key }}"
    LaunchedEffect(preferences.personalDataUserId, requestKey) {
        val query = model.query.trim()
        if (query.length < 2 || sources.isEmpty() || model.loadedRequest == requestKey) return@LaunchedEffect
        val targets = sources.filter { it.isAi == model.aiMode }
        model.items = emptyList(); model.error = null; model.busy = true
        try {
            if (targets.isEmpty()) { model.error = "No title-search extension is available. Add a searchable catalogue in Extensions."; return@LaunchedEffect }
            val budget = Semaphore(3)
            val notices = mutableListOf<String>()
            supervisorScope {
                targets.map { source -> async {
                    budget.withPermit {
                        try {
                            val response = withTimeout(9_000) { withContext(Dispatchers.IO) { ids.withPersonalDataScope {
                                client.fetchCatalog(source.addon.manifestUrl.removeSuffix("/manifest.json").trimEnd('/'),
                                    source.catalog.type, source.catalog.id, search = query, failOnError = true)
                            } } }
                            currentCoroutineContext().ensureActive()
                            val incoming = response.metas.map { it.copy(type = it.type.ifBlank { source.catalog.type }).toMediaItem() }
                            notices += incoming.mapNotNull { it.catalogueNotice }
                            val valid = incoming.filter { it.catalogueNotice == null && !it.isContentPlaceholder }
                            model.items = (model.items + valid).groupBy { it.tvSearchStableKey() }.values
                                .map { duplicates -> duplicates.reduce(::mergeTvSearchDuplicate) }.take(180)
                        } catch (_: TimeoutCancellationException) { notices += "A search extension took too long to respond." }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { notices += "A search extension could not be reached." }
                    }
                } }.awaitAll()
            }
            if (notices.isNotEmpty()) model.error = notices.distinct().joinToString("\n\n").take(1600)
            model.loadedRequest = requestKey
        } finally { model.busy = false }
    }
    val enriched = rememberTvVisibleMetadata(model.items, "extension-search", maximumCandidates = 80,
        needsDetails = model.filters.genre != null || model.filters.fromYear != null || model.filters.throughYear != null || model.filters.runtime != null)
    @Suppress("UNUSED_VARIABLE") val visibilityVersion = watchedRevision
    val permitted = filter.filterItems(policyState, ContentAccessContext.DIRECT_SEARCH, enriched, ContentSourceType.ADDON).items
    TvExtensionSearchContent(model, permitted, railFocusRequester, onMediaClick, onFirstContentRequester,
        onContentFocused, shouldAutoFocus, onManage, preferences.ratingPrefs,
        com.torve.android.tv.settings.rememberTvFocusPreviewPolicy(preferences.personalDataUserId).hasPreview("search"))
}

@Composable
internal fun TvExtensionSearchContent(
    model: TvExtensionSearchState,
    items: List<MediaItem>,
    rail: FocusRequester,
    onMedia: (MediaItem) -> Unit,
    publish: (FocusRequester) -> Unit,
    focused: (FocusRequester) -> Unit,
    autoFocus: Boolean,
    manage: () -> Unit,
    ratingPrefs: RatingDisplayPrefs = RatingDisplayPrefs(),
    focusPreview: Boolean = false,
) {
    val toolbar = remember { List(6) { FocusRequester() } }
    val firstResult = remember { FocusRequester() }
    val firstNoticeAction = remember { FocusRequester() }
    val manageRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var filtersOpen by remember { mutableStateOf(false) }
    var density by remember { mutableStateOf(TvSearchDensity.RELAXED) }
    var recent by remember { mutableStateOf(emptyList<String>()) }
    val results = filterTvExtensionSearch(items, model.filters)
    val columns = if (density == TvSearchDensity.COMPACT) 6 else 5
    LaunchedEffect(Unit) { publish(toolbar[0]) }
    LaunchedEffect(autoFocus) { if (autoFocus) { withFrameNanos { }; toolbar[0].requestFocus() } }
    fun moveDown() { runCatching {
        when {
            model.error != null -> firstNoticeAction.requestFocus()
            results.isNotEmpty() -> firstResult.requestFocus()
            model.sourceCount == 0 && !model.busy && model.query.isBlank() -> manageRequester.requestFocus()
        }
    } }
    fun belowNotice() { if (results.isNotEmpty()) runCatching { firstResult.requestFocus() } }
    fun closeFilters() {
        filtersOpen = false
        scope.launch { withFrameNanos { }; toolbar[4].requestFocus() }
    }
    BackHandler(!filtersOpen) { rail.requestFocus() }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 22.dp, end = 28.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TvSearchToolbar("extension-search", model.query, model.aiMode, model.hasAi, model.filters.type,
            model.filters.count, density, recent,
            toolbar[0], toolbar[1], toolbar[2], toolbar[3], toolbar[4], toolbar[5], rail, firstResult,
            onMoveDown = ::moveDown,
            onQueryCommitted = { query -> model.query = query; model.revision++; recent = (listOf(query) + recent).filter { it.isNotBlank() }.distinct().take(8) },
            onRemoveRecent = { recent = recent - it }, onClearRecent = { recent = emptyList() },
            onToggleAi = { model.aiMode = !model.aiMode },
            onFilterTypeChange = { model.filters = model.filters.copy(type = it) },
            onOpenFilters = { filtersOpen = true }, onDensityChange = { density = it }, onContentFocused = focused)
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        model.error?.let { notice ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("search.provider.notice")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(localizedUiText(if (model.aiMode) "AI search extension unavailable" else "Search extension needs attention"), color = Snow, style = MaterialTheme.typography.titleMedium)
                    Text(localizedUiText(notice), color = Silver, maxLines = 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val actionModifier = Modifier.focusProperties { up = toolbar[0] }
                        if (model.aiMode) TvSearchToolbarButton(localizedUiText("Use title search"), actionModifier.focusRequester(firstNoticeAction),
                            onFocused = {}, onClick = { toolbar[0].requestFocus(); model.aiMode = false }, onMoveDown = ::belowNotice)
                        TvSearchToolbarButton(localizedUiText("Try again"), actionModifier.then(if (!model.aiMode) Modifier.focusRequester(firstNoticeAction) else Modifier),
                            onFocused = {}, onClick = { toolbar[0].requestFocus(); model.revision++ }, onMoveDown = ::belowNotice)
                        TvSearchToolbarButton(localizedUiText("Extensions"), Modifier.focusProperties { up = toolbar[0] }, onFocused = {}, onClick = manage, onMoveDown = ::belowNotice)
                    }
                }
            }
        }
        Text(localizedUiText(when {
            model.query.length < 2 -> "Search movies and shows across your installed extensions. Filters are available before you search."
            model.busy && results.isEmpty() -> "Searching…"
            results.isEmpty() && model.filters.count > 0 -> "No matching titles. Try adjusting your filters."
            results.isEmpty() -> "No matching titles. Try another title."
            else -> "${results.size} titles · ${if (model.aiMode) "AI extension search" else "Title search"}"
        }), color = Silver, style = MaterialTheme.typography.bodyMedium)
        if (model.sourceCount == 0 && !model.busy && model.query.isBlank()) {
            TvSearchToolbarButton(localizedUiText("Manage extensions"), Modifier.focusRequester(manageRequester).focusProperties { up = toolbar[0]; left = rail },
                onFocused = {}, onClick = manage, onMoveDown = {})
        }
        if (focusPreview && results.isNotEmpty()) com.torve.android.tv.components.TvFocusPreviewGrid(
            items = results, screenId = "tv_search:extensions", columns = columns,
            railFocusRequester = rail, entryRequester = firstResult, headerRequester = toolbar[0],
            modifier = Modifier.fillMaxWidth().weight(1f), onContentFocused = focused, onClick = onMedia)
        else LazyVerticalGrid(GridCells.Fixed(columns),
            Modifier.fillMaxWidth().weight(1f).testTag("search.extension.results"),
            verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
            itemsIndexed(results, key = { _, item -> item.tvSearchStableKey() }) { index, item ->
                val requester = remember(item.tvSearchStableKey()) { FocusRequester() }
                TvSearchResultCard(item, density, Modifier.fillMaxWidth().aspectRatio(2f / 3f).focusRequester(requester)
                    .then(if (index == 0) Modifier.focusRequester(firstResult) else Modifier)
                    .focusProperties { if (index < columns) up = toolbar[0]; if (index % columns == 0) left = rail },
                    onFocused = { focused(requester) }, onClick = { onMedia(item) }, ratingPrefs = ratingPrefs)
            }
        }
    }
    if (filtersOpen) TvExtensionSearchFilterDialog(model.filters,
        genres = (listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "Horror", "Mystery", "Romance", "Science Fiction", "Thriller") +
            items.flatMap { it.genres.map { genre -> genre.name } }).distinct().sorted(),
        apply = { model.filters = it; closeFilters() }, dismiss = ::closeFilters)
}
