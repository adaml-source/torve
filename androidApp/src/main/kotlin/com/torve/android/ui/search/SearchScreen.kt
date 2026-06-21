package com.torve.android.ui.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.torve.android.sync.SyncCoordinator
import com.torve.android.voice.VoiceInputPhase
import com.torve.android.voice.VoiceInputUiState
import com.torve.android.ui.components.CardSize
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
    viewModel: SearchViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
    syncCoordinator: SyncCoordinator = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val syncState by syncCoordinator.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDevicePicker by remember { mutableStateOf(false) }
    var voiceInputState by remember { mutableStateOf(VoiceInputUiState()) }
    val settingsState by settingsViewModel.state.collectAsState()
    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )

    CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
        LocalRatingPrefs provides settingsState.ratingPrefs,
    ) {
        val tvTargets = syncCoordinator.targetDevices()
            .filter { it.deviceType.contains("tv", ignoreCase = true) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
        // ── Search Input Row ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Gunmetal)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                BasicTextField(
                    value = state.query,
                    onValueChange = { viewModel.updateQuery(it) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Snow),
                    cursorBrush = SolidColor(Amber),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Box {
                            if (state.query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.search_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Torve.colors.textHint,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (state.query.isNotEmpty() || state.hasActiveSearch) {
                    IconButton(
                        onClick = { viewModel.clearSearch() },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(24.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.search_clear),
                            tint = Torve.colors.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = Torve.colors.textTertiary,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(20.dp),
                    )
                }
            }

            // Voice search
            VoiceSearchButton(
                onResult = { viewModel.updateQuery(it) },
                onStateChanged = { voiceInputState = it },
            )

            IconButton(
                onClick = {
                    if (!syncState.isAuthenticated) {
                        Toast.makeText(context, context.getString(R.string.search_create_profile_first), Toast.LENGTH_SHORT).show()
                        return@IconButton
                    }
                    if (tvTargets.isEmpty()) {
                        syncCoordinator.refreshDevices()
                        Toast.makeText(context, context.getString(R.string.search_no_paired_tv), Toast.LENGTH_SHORT).show()
                        return@IconButton
                    }
                    showDevicePicker = true
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Tv,
                    contentDescription = stringResource(R.string.search_send_to_device_cd),
                    tint = if (syncState.isAuthenticated) Amber else Torve.colors.textTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }

            // Filter button with active indicator
            val activeCount = state.filter.activeCount
            BadgedBox(
                badge = {
                    if (activeCount > 0) {
                        Badge(containerColor = Amber, contentColor = Obsidian) {
                            Text("$activeCount")
                        }
                    }
                },
            ) {
                IconButton(onClick = { viewModel.toggleFilterSheet() }) {
                    Icon(
                        Icons.Rounded.FilterList,
                        contentDescription = stringResource(R.string.catalog_filters),
                        tint = if (state.filter.isActive) Amber else Torve.colors.textTertiary,
                    )
                }
            }
        }

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

        // ── Active Filter Chips ──
        if (state.filter.isActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.filter.mediaType?.let { type ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(mediaType = null)) },
                        label = { Text(if (type == "movie") stringResource(R.string.nav_movies) else stringResource(R.string.nav_tv_shows)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                if (state.filter.genreIds.isNotEmpty()) {
                    val names = state.filter.genreIds.mapNotNull { id ->
                        genreOptions.find { it.first == id }?.second
                    }
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(genreIds = emptyList())) },
                        label = { Text(names.joinToString(", ").take(30)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                state.filter.minRating?.let { rating ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(minRating = null)) },
                        label = { Text("★ %.1f+".format(rating)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                state.filter.minImdbScore?.let { imdb ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(minImdbScore = null)) },
                        label = { Text("IMDb %.1f+".format(imdb)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                state.filter.minTmdbScore?.let { tmdb ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(minTmdbScore = null)) },
                        label = { Text("TMDB %.1f+".format(tmdb)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                state.filter.minTorveScore?.let { torve ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(minTorveScore = null)) },
                        label = { Text("Torve %.0f+".format(torve)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                if (state.filter.yearFrom != null || state.filter.yearTo != null) {
                    val yearLabel = when {
                        state.filter.yearFrom != null && state.filter.yearTo != null ->
                            "${state.filter.yearFrom}-${state.filter.yearTo}"
                        state.filter.yearFrom != null -> "${state.filter.yearFrom}+"
                        else -> "≤${state.filter.yearTo}"
                    }
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(yearFrom = null, yearTo = null)) },
                        label = { Text(yearLabel) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                state.filter.runtimeFilter?.let { runtime ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(runtimeFilter = null)) },
                        label = { Text(runtime.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                if (state.filter.sortBy != SortOption.POPULARITY_DESC) {
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(sortBy = SortOption.POPULARITY_DESC)) },
                        label = { Text(state.filter.sortBy.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
                if (state.filter.providersAvailabilityOnly) {
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.applyFilter(state.filter.copy(providersAvailabilityOnly = false)) },
                        label = { Text(stringResource(R.string.search_provider_ready)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }
        }

        // ── Active Search Banner ──
        // `displayItems` and `isLoading` are derived on the state itself so
        // the composable cannot compose an inconsistent view from partial
        // field updates. The shared-module combine stage guarantees that
        // state.renderPhase, state.results, and state.discoverResults are
        // produced atomically for a single generation.
        val displayItems = state.displayItems
        val isLoading = state.renderPhase == SearchRenderPhase.SEARCHING ||
            state.renderPhase == SearchRenderPhase.DISCOVERING

        if (state.hasActiveSearch && displayItems.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Charcoal)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "\"${state.query}\"",
                    color = Snow,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.search_results_count, displayItems.size),
                    color = Silver,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { viewModel.clearSearch() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.common_clear), color = Amber, style = MaterialTheme.typography.labelSmall)
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
        if (state.hasActiveSearch && (state.peopleResults.isNotEmpty() || state.userLists.isNotEmpty())) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                if (state.peopleResults.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.search_people_prefix) + state.peopleResults.take(3).joinToString(", ") { it.name },
                        color = Silver,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.userLists.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.search_lists_prefix) + state.userLists.joinToString(" • "),
                        color = Torve.colors.textTertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
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
                            onMediaClick = onMediaClick,
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
                        onMediaClick = onMediaClick,
                    )
                }

                SearchRenderPhase.EMPTY,
                SearchRenderPhase.ERROR -> {
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
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 130.dp),
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
                onClick = { onMediaClick(item) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchFilterSheet(
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
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
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
                items(SortOption.entries) { option ->
                    FilterChip(
                        selected = selectedSort == option,
                        onClick = { selectedSort = option },
                        label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
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
                "Genre${if (selectedGenreIds.isNotEmpty()) " (${selectedGenreIds.size})" else ""}",
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = providersAvailabilityOnly,
                    onClick = { providersAvailabilityOnly = !providersAvailabilityOnly },
                    label = { Text(stringResource(R.string.search_provider_ready)) },
                    shape = RoundedCornerShape(16.dp),
                )
                FilterChip(
                    selected = minImdbScore != null,
                    onClick = { minImdbScore = if (minImdbScore == null) 7f else null },
                    label = { Text("IMDb 7.0+") },
                    shape = RoundedCornerShape(16.dp),
                )
                FilterChip(
                    selected = minTmdbScore != null,
                    onClick = { minTmdbScore = if (minTmdbScore == null) 7f else null },
                    label = { Text("TMDB 7.0+") },
                    shape = RoundedCornerShape(16.dp),
                )
                FilterChip(
                    selected = minTorveScore != null,
                    onClick = { minTorveScore = if (minTorveScore == null) 75f else null },
                    label = { Text("Torve 75+") },
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
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = yearFromText,
                        onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) yearFromText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(Amber),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            Box {
                                if (yearFromText.isEmpty()) {
                                    Text(stringResource(R.string.catalog_from), style = MaterialTheme.typography.bodyMedium, color = Torve.colors.textHint)
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = yearToText,
                        onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) yearToText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(Amber),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            Box {
                                if (yearToText.isEmpty()) {
                                    Text(stringResource(R.string.catalog_to), style = MaterialTheme.typography.bodyMedium, color = Torve.colors.textHint)
                                }
                                innerTextField()
                            }
                        },
                    )
                }
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
                        label = { Text(runtime.label, style = MaterialTheme.typography.labelSmall) },
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
