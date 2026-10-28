package com.torve.android.ui.search

import com.torve.android.ui.community.parityLabel as localizedUiText

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.torve.android.ui.components.MobileGlassButton as FilledTonalButton
import com.torve.android.ui.components.MobileGlassFilterChip as FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import com.torve.android.ui.components.MobileGlassIconButton as IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import com.torve.android.ui.system.TorveModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.torve.android.sync.SyncCoordinator
import com.torve.android.voice.VoiceInputPhase
import com.torve.android.voice.VoiceInputUiState
import com.torve.android.ui.community.adaptiveColumns
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.mobileGlassSurface
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.LocalRatingPrefs
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.components.mediaItemLazyKey
import com.torve.android.ui.sync.SyncDevicePickerDialog
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.MediaItem
import com.torve.domain.model.resolveCardStyle
import com.torve.presentation.catalog.RuntimeFilter
import com.torve.presentation.catalog.SortOption
import com.torve.presentation.settings.SettingsViewModel
import com.torve.presentation.search.SearchFilter
import com.torve.presentation.search.SearchRenderPhase
import com.torve.presentation.search.SearchViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val genreOptions = listOf(
    null to "All Genres",
    28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
    80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
    14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
    9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi", 53 to "Thriller",
    10752 to "War", 37 to "Western",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onMediaClick: (MediaItem) -> Unit,
    onPersonClick: ((Int) -> Unit)? = null,
    isActive: Boolean = true,
    viewModel: SearchViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    syncCoordinator: SyncCoordinator = koinInject(),
    userIds: com.torve.data.auth.UserIdProvider = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel, isActive) {
        if (isActive) viewModel.ensureDiscovery()
    }
    val syncState by syncCoordinator.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDevicePicker by remember { mutableStateOf(false) }
    var voiceInputState by remember { mutableStateOf(VoiceInputUiState()) }
    val settingsState by settingsViewModel.state.collectAsState()
    if (settingsState.tmdbExtensionsLoaded && !settingsState.tmdbCatalogueEnabled) {
        var manage by rememberSaveable { mutableStateOf(false) }
        if (manage) com.torve.android.ui.community.ExtensionsScreen(onBack = { manage = false })
        else com.torve.android.ui.community.ExtensionBrowserScreen(searchMode = true, onMedia = onMediaClick, onManage = { manage = true })
        return
    }
    val recentOwner = userIds.currentDataUserId()
    val recentStore = remember(context) { context.getSharedPreferences("torve_mobile_search", android.content.Context.MODE_PRIVATE) }
    var recentQueries by remember(recentOwner) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(recentOwner) {
        recentQueries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { val array = org.json.JSONArray(recentStore.getString("recent:$recentOwner", "[]"))
                List(array.length().coerceAtMost(12)) { array.getString(it) }.filter(String::isNotBlank) }.getOrDefault(emptyList())
        }
    }
    fun rememberQuery() {
        val term = state.query.trim().take(120)
        if (term.length < 2 || recentOwner != userIds.currentDataUserId()) return
        val next = (listOf(term) + recentQueries.filterNot { it.equals(term, true) }).take(12)
        recentQueries = next
        val owner = recentOwner
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { recentStore.edit().putString("recent:$owner", org.json.JSONArray(next).toString()).apply() }
    }
    val openMedia: (MediaItem) -> Unit = { item -> rememberQuery(); onMediaClick(item) }
    val pageGrid = com.torve.android.ui.community.rememberPageGrid("search")
    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )
    val advancedFilter = state.filter.copy(mediaType = null)

    CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
        LocalRatingPrefs provides settingsState.ratingPrefs,
    ) {
        val tvTargets = syncCoordinator.targetDevices()
            .filter { it.deviceType.contains("tv", ignoreCase = true) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.torve.android.ui.product.MobileSearchField(
                query = state.query, onQuery = viewModel::updateQuery, onClear = viewModel::clearSearch,
                onSearch = { rememberQuery(); viewModel.submitSearch() }, recent = recentQueries,
                placeholder = localizedUiText(if (state.aiSearchEnabled) stringResource(R.string.search_ai_placeholder) else "Search movies and shows"),
                onForgetRecent = { recentQueries = emptyList(); recentStore.edit().remove("recent:$recentOwner").apply() },
                modifier = Modifier.weight(1f),
            )
            SearchAiControls(state.aiSearchEnabled, state.query.trim().length >= 2, state.isSearching,
                onEnabled = viewModel::setAiSearchEnabled,
                onSearch = { rememberQuery(); viewModel.submitSearch() })
            // Voice search
            VoiceSearchButton(
                onResult = { viewModel.updateQuery(it) },
                onStateChanged = { voiceInputState = it },
            )
        }
        SearchBrowseToolbar(
            category = state.category,
            onCategory = viewModel::selectCategory,
            pageGrid = pageGrid,
            activeFilterCount = advancedFilter.activeCount,
            onFilters = viewModel::toggleFilterSheet,
            authenticated = syncState.isAuthenticated,
            summary = localizedUiText(if (state.hasActiveSearch && state.displayItems.isNotEmpty())
                stringResource(R.string.search_results_count, state.displayItems.size) else "Discover"),
            onSendToTv = {
                    if (!syncState.isAuthenticated) {
                        Toast.makeText(context, context.getString(R.string.search_create_profile_first), Toast.LENGTH_SHORT).show()
                    } else if (tvTargets.isEmpty()) {
                        syncCoordinator.refreshDevices()
                        Toast.makeText(context, context.getString(R.string.search_no_paired_tv), Toast.LENGTH_SHORT).show()
                    } else showDevicePicker = true
            },
        )


        when (voiceInputState.phase) {
            VoiceInputPhase.Listening -> {
                Text(
                    text = stringResource(R.string.search_listening),
                    style = MaterialTheme.typography.labelMedium,
                    color = Amber,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
            }

            VoiceInputPhase.Processing -> {
                Text(
                    text = stringResource(R.string.search_processing_voice),
                    style = MaterialTheme.typography.labelMedium,
                    color = Torve.colors.textTertiary,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
            }

            VoiceInputPhase.Error,
            VoiceInputPhase.Unsupported,
            -> {
                Text(
                    text = voiceInputState.message ?: stringResource(R.string.voice_input_unavailable),
                    style = MaterialTheme.typography.labelMedium,
                    color = Amber,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
            }

            VoiceInputPhase.Idle -> Unit
        }

        // Active filters stay summarized by the filter badge; edit or clear them in Filters.

        // ── Active Search Banner ──
        // `displayItems` and `isLoading` are derived on the state itself so
        // the composable cannot compose an inconsistent view from partial
        // field updates. The shared-module combine stage guarantees that
        // state.renderPhase, state.results, and state.discoverResults are
        // produced atomically for a single generation.
        val displayItems = state.displayItems
        val isLoading = state.renderPhase == SearchRenderPhase.SEARCHING ||
            state.renderPhase == SearchRenderPhase.DISCOVERING

        if (settingsState.hideWatched) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(localizedUiText("Watched titles hidden"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { settingsViewModel.setHideWatched(false) }) { Text(localizedUiText("Show watched")) }
            }
        }
        if (state.query.length >= 2 && !state.aiSearchEnabled && !isLoading && displayItems.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                items(displayItems.take(5), key = { "suggestion:${it.type}:${it.id}" }) { item ->
                    androidx.compose.material3.SuggestionChip(onClick = { openMedia(item) },
                        label = { Text(listOfNotNull(item.title, item.year?.toString()).joinToString(" · "), maxLines = 1) })
                }
            }
        }

        // ── Results ──
        if (state.hiddenResultsCount > 0) {
            Text(
                text = stringResource(R.string.search_hidden_results_notice),
                color = Torve.colors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            // Single-decision render. `state.renderPhase` is authoritative —
            // `displayItems.isNotEmpty()` and `isLoading` are only used as
            // secondary hints (e.g., keep prior results visible while a
            // newer search loads, so the grid doesn't blank out).
            when (state.renderPhase) {
                SearchRenderPhase.SEARCHING,
                SearchRenderPhase.DISCOVERING -> {
                    if (displayItems.isNotEmpty()) {
                        // Keep the old grid visible while the newer search
                        // is in flight — avoids a blank flash when typing.
                        SearchResultsGrid(
                            items = displayItems,
                            onMediaClick = openMedia,
                            generationKey = "${state.query.trim()}|${state.filter}",
                            hasMore = if (state.query.length >= 2) state.searchHasMore else state.discoverHasMore,
                            isLoadingMore = state.isLoadingMore,
                            onLoadMore = viewModel::loadMore,
                            people = state.peopleResults, onPersonClick = onPersonClick?.let { open -> { id -> rememberQuery(); open(id) } },
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(40.dp),
                            color = Amber,
                            strokeWidth = 3.dp,
                        )
                    }
                }

                SearchRenderPhase.RESULTS -> {
                    SearchResultsGrid(
                        items = displayItems,
                        onMediaClick = openMedia,
                        generationKey = "${state.query.trim()}|${state.filter}",
                        hasMore = if (state.query.length >= 2) state.searchHasMore else state.discoverHasMore,
                        isLoadingMore = state.isLoadingMore,
                        onLoadMore = viewModel::loadMore,
                            people = state.peopleResults, onPersonClick = onPersonClick?.let { open -> { id -> rememberQuery(); open(id) } },
                    )
                }

                SearchRenderPhase.ERROR -> {
                    com.torve.android.ui.product.ProductFeedback(localizedUiText(state.error ?: "Search could not load. Check your connection and try again."),
                        isError = true, onRetry = viewModel::retry, modifier = Modifier.padding(20.dp).align(Alignment.Center))
                }
                SearchRenderPhase.EMPTY -> {
                    if (state.peopleResults.isNotEmpty() && onPersonClick != null) SearchResultsGrid(
                        items = emptyList(), onMediaClick = openMedia, generationKey = state.query,
                        hasMore = false, isLoadingMore = false, onLoadMore = {},
                        people = state.peopleResults, onPersonClick = { id -> rememberQuery(); onPersonClick(id) })
                    else Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Torve.colors.textHint,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.titleMedium,
                            color = Torve.colors.textPrimary,
                        )
                        Text(
                            text = stringResource(R.string.search_try_different),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Torve.colors.textTertiary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (advancedFilter.isActive) TextButton(onClick = { viewModel.applyFilter(SearchFilter(mediaType = state.filter.mediaType)) }) { Text(localizedUiText("Reset filters")) }
                    }
                }

                SearchRenderPhase.IDLE -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Torve.colors.textHint,
                        )
                        Text(
                            text = stringResource(R.string.search_prompt),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Torve.colors.textTertiary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    // ── Filter Bottom Sheet ──
    if (state.showFilterSheet) {
        SearchFilterSheet(
            currentFilter = state.filter,
            onApply = { viewModel.applyFilter(it) },
            onDismiss = { viewModel.dismissFilterSheet() },
        )
    }

    if (showDevicePicker) {
        SyncDevicePickerDialog(
            title = stringResource(R.string.search_send_to_device_title),
            devices = tvTargets,
            onSelectDevice = { device ->
                showDevicePicker = false
                val queryToSend = state.query.trim()
                if (queryToSend.isBlank()) {
                    Toast.makeText(context, context.getString(R.string.search_enter_query_first), Toast.LENGTH_SHORT).show()
                    return@SyncDevicePickerDialog
                }
                scope.launch {
                    val result = syncCoordinator.sendSearchPush(
                        targetDeviceId = device.id,
                        query = queryToSend,
                    )
                    if (result.isSuccess) {
                        Toast.makeText(context, context.getString(R.string.search_sent_to, device.deviceName), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            context,
                            result.exceptionOrNull()?.message ?: "Failed to send search",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = { showDevicePicker = false },
        )
    }
}
}

/** Three optional type toggles; no selection means all media types. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchBrowseToolbar(
    category: String,
    onCategory: (String) -> Unit,
    pageGrid: com.torve.android.ui.community.PageGridState,
    activeFilterCount: Int,
    onFilters: () -> Unit,
    authenticated: Boolean,
    summary: String,
    onSendToTv: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = listOf(Triple("movie", "Movies", Icons.Rounded.Movie),
        Triple("tv", "TV shows", Icons.Rounded.Tv), Triple("people", "People", Icons.Rounded.People))
    var moreExpanded by remember { mutableStateOf(false) }
    var gridOptions by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val showSummary = maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.3f
        Row(Modifier.fillMaxWidth().testTag("search.toolbar"),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.forEach { (key, label, icon) ->
                    val selected = category == key
                    androidx.compose.material3.FilledIconToggleButton(
                        checked = selected,
                        onCheckedChange = { onCategory(if (selected) "all" else key) },
                        modifier = Modifier.size(48.dp)
                            .mobileGlassSurface(CircleShape, selected = selected, quiet = !selected)
                            .testTag("search.category.$key").semantics { contentDescription = label },
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            checkedContainerColor = Color.Transparent, checkedContentColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(icon, null, Modifier.size(24.dp))
                    }
                }
            }
            if (showSummary) Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = Torve.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            else Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BadgedBox(badge = {
                    if (activeFilterCount > 0) Badge(containerColor = Amber, contentColor = Obsidian) { Text("$activeFilterCount") }
                }) {
                    IconButton(onFilters, Modifier.testTag("search.filters")) {
                        Icon(Icons.Rounded.FilterList, stringResource(R.string.catalog_filters),
                            tint = if (activeFilterCount > 0) Amber else Torve.colors.textTertiary)
                    }
                }
                Box {
                    IconButton({ gridOptions = false; moreExpanded = true }, Modifier.testTag("search.more")) {
                        Icon(Icons.Rounded.MoreHoriz, localizedUiText("More search options"))
                    }
                    androidx.compose.material3.DropdownMenu(moreExpanded, { moreExpanded = false }, Modifier.widthIn(min = 220.dp)) {
                        if (gridOptions) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text(localizedUiText("Poster columns")) },
                                leadingIcon = { Icon(Icons.Rounded.ArrowBack, null) }, onClick = { gridOptions = false })
                            (2..6).forEach { columns ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(localizedUiText("$columns columns")) },
                                    trailingIcon = { if (pageGrid.columns == columns) Icon(Icons.Rounded.Check, localizedUiText("Selected")) },
                                    onClick = { pageGrid.select(columns); moreExpanded = false },
                                    modifier = Modifier.testTag("search.grid.$columns"))
                            }
                        } else {
                            androidx.compose.material3.DropdownMenuItem(text = { Text(localizedUiText("Poster columns · ${pageGrid.columns}")) },
                                leadingIcon = { Icon(Icons.Rounded.GridView, null) },
                                onClick = { gridOptions = true }, modifier = Modifier.testTag("search.grid"))
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.search_send_to_device_cd)) },
                                leadingIcon = { Icon(Icons.Filled.Tv, null, tint = if (authenticated) Amber else Torve.colors.textTertiary) },
                                onClick = { moreExpanded = false; onSendToTv() }, modifier = Modifier.testTag("search.send"))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the filtered display items. Pulled out so both the RESULTS phase
 * and the "new search in flight, keep old grid visible" branch share one
 * key-stable implementation. Item keys are derived purely from the media
 * item's stable id + type via [mediaItemLazyKey], so identical items
 * preserve their card state across generation changes.
 */
@Composable
private fun SearchResultsGrid(
    items: List<com.torve.domain.model.MediaItem>,
    onMediaClick: (com.torve.domain.model.MediaItem) -> Unit,
    generationKey: String,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    people: List<com.torve.domain.model.PersonSummary> = emptyList(),
    onPersonClick: ((Int) -> Unit)? = null,
) {
    val density = com.torve.android.ui.community.rememberPageGrid("search")
    val gridState = rememberLazyGridState()
    var lastGenerationKey by rememberSaveable { mutableStateOf(generationKey) }
    LaunchedEffect(generationKey) {
        if (generationKey != lastGenerationKey) {
            gridState.scrollToItem(0)
            lastGenerationKey = generationKey
        }
    }
    val shouldLoadMore by remember(items.size, hasMore, isLoadingMore) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            items.isNotEmpty() && lastVisible >= items.lastIndex - 6 && hasMore && !isLoadingMore
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(density.adaptiveColumns()),
        state = gridState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(
            items = items,
            // Stable key: the same MediaItem id always maps to the same
            // grid slot, so swapping the list contents doesn't reset
            // card state or re-fire image requests for preserved items.
            key = { item -> mediaItemLazyKey(item, 0) },
        ) { item ->
            PosterCard(
                item = item,
                sizeOverride = CardSize.MEDIUM,
                fillCell = true,
                onClick = { onMediaClick(item) },
            )
        }
        if (people.isNotEmpty() && onPersonClick != null) {
            item(key = "people_heading", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(localizedUiText("People"), style = MaterialTheme.typography.titleLarge)
            }
            items(people.filter { it.id > 0 && it.name.isNotBlank() }.distinctBy { it.id },
                key = { "person:${it.id}" }, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { person ->
                TextButton(onClick = { onPersonClick(person.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(person.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (isLoadingMore) {
            item(key = "search_loading_more", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Amber, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchFilterSheet(
    currentFilter: SearchFilter,
    onApply: (SearchFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var mediaType by remember { mutableStateOf(currentFilter.mediaType) }
    var selectedGenreIds by remember { mutableStateOf(currentFilter.genreIds) }
    var minRating by remember { mutableFloatStateOf(currentFilter.minRating ?: 0f) }
    var hasRatingFilter by remember { mutableStateOf(currentFilter.minRating != null) }
    var yearFromText by remember { mutableStateOf(currentFilter.yearFrom?.toString() ?: "") }
    var yearToText by remember { mutableStateOf(currentFilter.yearTo?.toString() ?: "") }
    var providersAvailabilityOnly by remember { mutableStateOf(currentFilter.providersAvailabilityOnly) }
    var minImdbScore by remember { mutableStateOf(currentFilter.minImdbScore) }
    var minTmdbScore by remember { mutableStateOf(currentFilter.minTmdbScore) }
    var minTorveScore by remember { mutableStateOf(currentFilter.minTorveScore) }
    var selectedRuntime by remember { mutableStateOf(currentFilter.runtimeFilter) }
    var selectedSort by remember { mutableStateOf(currentFilter.sortBy) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .84f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.search_filters_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = {
                    mediaType = null
                    selectedGenreIds = emptyList()
                    minRating = 0f
                    hasRatingFilter = false
                    yearFromText = ""
                    yearToText = ""
                    providersAvailabilityOnly = false
                    minImdbScore = null
                    minTmdbScore = null
                    minTorveScore = null
                    selectedRuntime = null
                    selectedSort = SortOption.POPULARITY_DESC
                }) {
                    Text(stringResource(R.string.catalog_clear_all), color = Amber)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Type ──
            Text(
                stringResource(R.string.search_type),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "All", "movie" to "Movies", "tv" to "TV Shows").forEach { (type, label) ->
                    FilterChip(
                        selected = mediaType == type,
                        onClick = { mediaType = type },
                        label = { Text(localizedUiText(label), style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber,
                            selectedLabelColor = MaterialTheme.colorScheme.background,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Sort By ──
            Text(
                stringResource(R.string.catalog_sort_by),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SortOption.entries.sortedBy { it != SortOption.IMDB_SCORE_DESC }) { option ->
                    FilterChip(
                        selected = selectedSort == option,
                        onClick = { selectedSort = option },
                        label = { Text(localizedUiText(option.label), style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber,
                            selectedLabelColor = MaterialTheme.colorScheme.background,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Genre (Multi-select) ──
            Text(
                localizedUiText("Genre${if (selectedGenreIds.isNotEmpty()) " (${selectedGenreIds.size})" else ""}"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            // Skip first "All Genres" entry; multi-select toggles
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(genreOptions.drop(1)) { (id, name) ->
                    val genId = id ?: return@items
                    val selected = genId in selectedGenreIds
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedGenreIds = if (selected) {
                                selectedGenreIds - genId
                            } else {
                                selectedGenreIds + genId
                            }
                        },
                        label = { Text(name, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber,
                            selectedLabelColor = MaterialTheme.colorScheme.background,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Minimum Rating ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.catalog_min_rating),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = hasRatingFilter,
                    onClick = { hasRatingFilter = !hasRatingFilter },
                    label = {
                        Text(
                            if (hasRatingFilter) "%.1f+".format(minRating) else stringResource(R.string.catalog_any),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AmberSubtle,
                        selectedLabelColor = Amber,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    shape = RoundedCornerShape(16.dp),
                )
            }
            if (hasRatingFilter) {
                Slider(
                    value = minRating,
                    onValueChange = { minRating = it },
                    valueRange = 0f..9f,
                    steps = 17,
                    colors = SliderDefaults.colors(
                        thumbColor = Amber,
                        activeTrackColor = Amber,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("0", style = MaterialTheme.typography.labelSmall, color = Torve.colors.textTertiary)
                    Text("9+", style = MaterialTheme.typography.labelSmall, color = Torve.colors.textTertiary)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Year Range ──
            Text(
                stringResource(R.string.search_power_filters),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = providersAvailabilityOnly,
                    onClick = { providersAvailabilityOnly = !providersAvailabilityOnly },
                    label = { Text(stringResource(R.string.search_provider_ready)) },
                    shape = RoundedCornerShape(16.dp),
                )
                FilterChip(
                    selected = minImdbScore != null,
                    onClick = { minImdbScore = if (minImdbScore == null) 7f else null },
                    label = { Text(localizedUiText("IMDb 7.0+")) },
                    shape = RoundedCornerShape(16.dp),
                )
                FilterChip(
                    selected = minTmdbScore != null,
                    onClick = { minTmdbScore = if (minTmdbScore == null) 7f else null },
                    label = { Text(localizedUiText("TMDB 7.0+")) },
                    shape = RoundedCornerShape(16.dp),
                )
                FilterChip(
                    selected = minTorveScore != null,
                    onClick = { minTorveScore = if (minTorveScore == null) 75f else null },
                    label = { Text(localizedUiText("Torve 75+")) },
                    shape = RoundedCornerShape(16.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.catalog_release_year),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val presets = listOf(
                    "2026" to (2026 to 2026),
                    "2025" to (2025 to 2025),
                    "2020s" to (2020 to 2029),
                    "2010s" to (2010 to 2019),
                    "Classic" to (1900 to 1999),
                )
                items(presets) { (label, range) ->
                    val selected = yearFromText == range.first.toString() && yearToText == range.second.toString()
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (selected) {
                                yearFromText = ""
                                yearToText = ""
                            } else {
                                yearFromText = range.first.toString()
                                yearToText = range.second.toString()
                            }
                        },
                        label = { Text(localizedUiText(label), style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                com.torve.android.ui.components.MobileGlassTextField(yearFromText,
                    { if (it.length <= 4 && it.all(Char::isDigit)) yearFromText = it },
                    modifier = Modifier.weight(1f).testTag("search_filter_year_from"),
                    placeholder = { Text(stringResource(R.string.catalog_from)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                com.torve.android.ui.components.MobileGlassTextField(yearToText,
                    { if (it.length <= 4 && it.all(Char::isDigit)) yearToText = it },
                    modifier = Modifier.weight(1f).testTag("search_filter_year_to"),
                    placeholder = { Text(stringResource(R.string.catalog_to)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }

            Spacer(Modifier.height(20.dp))

            // ── Runtime ──
            Text(
                stringResource(R.string.catalog_runtime),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeFilter.entries.forEach { runtime ->
                    val selected = selectedRuntime == runtime
                    FilterChip(
                        selected = selected,
                        onClick = { selectedRuntime = if (selected) null else runtime },
                        label = { Text(localizedUiText(runtime.label), style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Apply ──
            FilledTonalButton(
                onClick = {
                    val yearFrom = yearFromText.toIntOrNull()?.takeIf { it in 1900..2030 }
                    val yearTo = yearToText.toIntOrNull()?.takeIf { it in 1900..2030 }
                    onApply(
                        SearchFilter(
                            mediaType = mediaType,
                            genreIds = selectedGenreIds,
                            minRating = if (hasRatingFilter && minRating > 0f) minRating else null,
                            minImdbScore = minImdbScore,
                            minTmdbScore = minTmdbScore,
                            minTorveScore = minTorveScore,
                            providersAvailabilityOnly = providersAvailabilityOnly,
                            yearFrom = yearFrom,
                            yearTo = yearTo,
                            runtimeFilter = selectedRuntime,
                            sortBy = selectedSort,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    stringResource(R.string.catalog_apply_filters),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
