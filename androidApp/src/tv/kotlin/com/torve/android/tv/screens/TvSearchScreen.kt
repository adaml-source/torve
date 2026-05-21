package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.tv.components.TvClickToEditOutlinedTextField
import com.torve.android.ui.components.PreferredRatingPills
import com.torve.android.ui.theme.*
import com.torve.android.voice.VoiceInputPhase
import com.torve.android.voice.rememberVoiceInputController
import com.torve.data.ai.KeywordSearchService
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.hasAnyEnabledDisplayValue
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.Calendar

private enum class SearchMode { STANDARD, AI }

private data class TvSearchFilterGroup(
    val key: String,
    val label: String,
    val options: List<Pair<Int, String>>,
)

private data class TvSearchFilterChipSpec(
    val groupKey: String,
    val id: Int,
    val label: String,
)

private data class TvSearchFilterVisualRow(
    val groupLabel: String?,
    val chipIndices: List<Int>,
)

@Composable
fun TvSearchScreen(
    railFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    initialQuery: String = "",
    shouldAutoFocus: Boolean = true,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val keywordSearchService: KeywordSearchService = koinInject()
    val settingsState by settingsViewModel.state.collectAsState()

    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var baseResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchMode by rememberSaveable { mutableStateOf(SearchMode.STANDARD) }
    var filterType by rememberSaveable { mutableStateOf<String?>(null) } // "movie", "tv", or null (all)
    var selectedGenreIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedStudioIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    var selectedMinRating by remember { mutableStateOf<Double?>(null) }
    var aiResultTitle by remember { mutableStateOf<String?>(null) }
    var aiFallback by remember { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var focusResultsAfterClosingFilters by remember { mutableStateOf(false) }
    var selectedResult by remember { mutableStateOf<MediaItem?>(null) }
    val inputFocusRequester = remember { FocusRequester() }
    val voiceButtonFocusRequester = remember { FocusRequester() }
    val filterToggleRequester = remember { FocusRequester() }
    val standardModeRequester = remember { FocusRequester() }
    val aiModeRequester = remember { FocusRequester() }
    val closeFiltersRequester = remember { FocusRequester() }
    val clearFiltersRequester = remember { FocusRequester() }
    val allTypeRequester = remember { FocusRequester() }
    val movieTypeRequester = remember { FocusRequester() }
    val tvTypeRequester = remember { FocusRequester() }
    val firstFilterRequester = remember { FocusRequester() }
    val firstResultRequester = remember { FocusRequester() }
    val voiceController = rememberVoiceInputController(
        prompt = "Search for movies and shows",
        onTranscript = { spokenQuery ->
            query = spokenQuery
        },
    )

    val hasAiKey = settingsState.activeAiApiKey.isNotBlank()
    val availableGenres = remember(baseResults, filterType) {
        baseResults.availableTvSearchGenres()
            .ifEmpty { tvSearchDefaultGenres(filterType) }
            .take(18)
    }
    val availableStudios = remember(baseResults) { baseResults.availableTvSearchStudios().take(12) }
    val availableYears = remember(baseResults) {
        val fromResults = baseResults.mapNotNull { it.year }
            .filter { it in 1900..2100 }
            .distinct()
            .sortedDescending()
            .take(12)
        if (fromResults.isNotEmpty()) {
            fromResults
        } else {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            (currentYear downTo (currentYear - 24)).toList()
        }
    }

    val popularQueries = remember {
        listOf("Action", "Comedy", "Sci-Fi", "Drama", "Thriller", "Animation")
    }
    val resultsMode = query.trim().length >= 2 || results.isNotEmpty() || loading || error != null

    LaunchedEffect(results) {
        selectedResult = selectedResult?.let { current ->
            results.firstOrNull { it.tvSearchStableKey() == current.tvSearchStableKey() }
        } ?: results.firstOrNull()
    }

    LaunchedEffect(showFilters, focusResultsAfterClosingFilters, results.size) {
        if (showFilters || !focusResultsAfterClosingFilters) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        runCatching { filterToggleRequester.requestFocus() }
        focusResultsAfterClosingFilters = false
    }

    LaunchedEffect(showFilters) {
        if (showFilters) {
            kotlinx.coroutines.delay(90)
            runCatching { firstFilterRequester.requestFocus() }
        }
    }

    LaunchedEffect(showFilters, results.size) {
        if (!showFilters || results.isEmpty()) return@LaunchedEffect
        val targets = results
            .filter { it.tmdbId != null && (it.genres.isEmpty() || it.studios.isEmpty()) }
            .take(48)
        if (targets.isEmpty()) return@LaunchedEffect
        launch(Dispatchers.IO) {
            val hydrated = targets.mapNotNull { item ->
                val tmdbId = item.tmdbId ?: return@mapNotNull null
                val type = if (item.type == MediaType.SERIES) "tv" else "movie"
                val detail = runCatching { metadataRepo.getDetail(type, tmdbId) }.getOrNull()
                    ?: return@mapNotNull null
                item.tvSearchStableKey() to item.copy(
                    imdbId = item.imdbId ?: detail.imdbId,
                    genres = item.genres.ifEmpty { detail.genres },
                    genreIds = item.genreIds.ifEmpty { detail.genreIds },
                    studios = item.studios.ifEmpty { detail.studios },
                    rating = item.rating ?: detail.rating,
                    ratings = item.ratings ?: detail.ratings,
                    year = item.year ?: detail.year,
                    releaseDate = item.releaseDate ?: detail.releaseDate,
                    posterUrl = item.posterUrl ?: detail.posterUrl,
                    backdropUrl = item.backdropUrl ?: detail.backdropUrl,
                    logoUrl = item.logoUrl ?: detail.logoUrl,
                )
            }.toMap()
            if (hydrated.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                baseResults = baseResults.map { hydrated[it.tvSearchStableKey()] ?: it }
                results = baseResults.filterTvSearchItems(
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                )
            }
        }
    }

    BackHandler(enabled = resultsMode && showFilters) {
        showFilters = false
        focusResultsAfterClosingFilters = true
    }

    LaunchedEffect(Unit) {
        onFirstContentRequester(inputFocusRequester)
    }

    LaunchedEffect(shouldAutoFocus) {
        if (shouldAutoFocus) {
            inputFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(initialQuery) {
        val normalized = initialQuery.trim()
        if (normalized.isNotBlank() && normalized != query) {
            query = normalized
        }
    }

    LaunchedEffect(baseResults, selectedGenreIds, selectedStudioIds, selectedYear, selectedMinRating) {
        results = baseResults.filterTvSearchItems(
            genreIds = selectedGenreIds,
            studioIds = selectedStudioIds,
            year = selectedYear,
            minRating = selectedMinRating,
        )
    }

    // Standard search
    LaunchedEffect(query, searchMode, filterType, selectedGenreIds, selectedYear, selectedMinRating) {
        if (searchMode != SearchMode.STANDARD) return@LaunchedEffect
        aiResultTitle = null
        aiFallback = false
        val trimmedQuery = query.trim()
        loading = true
        error = null
        try {
            delay(if (trimmedQuery.length >= 2) 250 else 80)
            val raw = if (trimmedQuery.length >= 2) {
                metadataRepo.searchMulti(trimmedQuery, 1).take(60)
            } else {
                metadataRepo.loadTvSearchBrowseResults(
                    filterType = filterType,
                    genreIds = selectedGenreIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                )
            }
            val typed = if (filterType != null) {
                raw.filter {
                    when (filterType) {
                        "movie" -> it.type == MediaType.MOVIE
                        "tv" -> it.type == MediaType.SERIES
                        else -> true
                    }
                }
            } else raw
            baseResults = typed
            results = typed.filterTvSearchItems(
                genreIds = selectedGenreIds,
                studioIds = selectedStudioIds,
                year = selectedYear,
                minRating = selectedMinRating,
            )
        } catch (t: Throwable) {
            baseResults = emptyList()
            results = emptyList()
            error = tvSearchSafeError(t)
        } finally {
            loading = false
        }
    }

    // AI search
    LaunchedEffect(query, searchMode) {
        if (searchMode != SearchMode.AI) return@LaunchedEffect
        aiFallback = false
        if (query.length < 2 || !hasAiKey) {
            baseResults = emptyList()
            results = emptyList()
            loading = false
            error = null
            aiResultTitle = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        aiResultTitle = null
        try {
            delay(300)
            val aiResult = keywordSearchService.searchWithAi(
                settingsState.aiProvider,
                settingsState.activeAiApiKey,
                query,
            )
            aiResultTitle = aiResult.title

            val resolvedItems: List<MediaItem> = when {
                aiResult.mode == "specific" && aiResult.specificItems.isNotEmpty() -> {
                    aiResult.specificItems.mapNotNull { item ->
                        runCatching { metadataRepo.getDetail(item.mediaType, item.tmdbId) }.getOrNull()
                    }
                }
                aiResult.mode == "person_credits" && aiResult.personId != null -> {
                    metadataRepo.getPersonCredits(aiResult.personId!!)
                }
                aiResult.mode == "person_filtered" && aiResult.specificItems.isNotEmpty() -> {
                    aiResult.specificItems.mapNotNull { item ->
                        runCatching { metadataRepo.getDetail(item.mediaType, item.tmdbId) }.getOrNull()
                    }
                }
                aiResult.mode == "person_filtered" && aiResult.personId != null -> {
                    val type = aiResult.mediaType ?: "movie"
                    val castParam = if (!aiResult.isDirector) aiResult.personId.toString() else null
                    val crewParam = if (aiResult.isDirector) aiResult.personId.toString() else null
                    metadataRepo.discover(
                        type = type,
                        sortBy = aiResult.sortBy,
                        withGenres = aiResult.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                        minRating = aiResult.minRating,
                        year = aiResult.yearFrom,
                        yearTo = aiResult.yearTo,
                        withCast = castParam,
                        withCrew = crewParam,
                    ).items.take(60)
                }
                else -> { // "discover"
                    val type = aiResult.mediaType ?: "movie"
                    metadataRepo.discover(
                        type = type,
                        sortBy = aiResult.sortBy,
                        withGenres = aiResult.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                        withKeywords = aiResult.keywordIds.takeIf { it.isNotEmpty() }?.joinToString("|"),
                        minRating = aiResult.minRating,
                        year = aiResult.yearFrom,
                        yearTo = aiResult.yearTo,
                    ).items.take(60)
                }
            }

            // If AI resolution returned nothing, fall back to standard search
            if (resolvedItems.isEmpty()) {
                aiFallback = true
                aiResultTitle = null
                baseResults = metadataRepo.searchMulti(query, 1).take(60)
                results = baseResults.filterTvSearchItems(
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                )
            } else {
                baseResults = resolvedItems.take(60)
                results = baseResults.filterTvSearchItems(
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                )
            }
        } catch (_: Throwable) {
            // Fallback to standard search
            aiFallback = true
            aiResultTitle = null
            try {
                baseResults = metadataRepo.searchMulti(query, 1).take(60)
                results = baseResults.filterTvSearchItems(
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                )
            } catch (t: Throwable) {
                baseResults = emptyList()
                results = emptyList()
                error = tvSearchSafeError(t)
            }
        } finally {
            loading = false
        }
    }

    val activeFilterCount = selectedGenreIds.size +
        selectedStudioIds.size +
        (if (selectedYear != null) 1 else 0) +
        (if (selectedMinRating != null) 1 else 0) +
        (if (filterType != null) 1 else 0) +
        (if (searchMode == SearchMode.AI) 1 else 0)
    val selectedBackdrop = selectedResult?.backdropUrl?.takeIf { it.isNotBlank() }
        ?: selectedResult?.posterUrl?.takeIf { it.isNotBlank() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian),
    ) {
        if (!selectedBackdrop.isNullOrBlank()) {
            AsyncImage(
                model = selectedBackdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x332A3959),
                            Color.Transparent,
                            Obsidian.copy(alpha = 0.96f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Obsidian.copy(alpha = 0.98f),
                            Obsidian.copy(alpha = 0.90f),
                            Obsidian.copy(alpha = 0.96f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 106.dp, top = 22.dp, end = 34.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .width(800.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Charcoal.copy(alpha = 0.66f))
                    .border(1.dp, Snow.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                    .padding(start = 16.dp, end = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvClickToEditOutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search movies, shows, channels") },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFocusRequester)
                        .focusProperties {
                            left = railFocusRequester
                            right = voiceButtonFocusRequester
                            down = allTypeRequester
                        }
                        .onFocusChanged { if (it.isFocused) onContentFocused(inputFocusRequester) },
                )
                IconButton(
                    onClick = { voiceController.launch() },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Gunmetal.copy(alpha = 0.66f))
                        .border(1.dp, Snow.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                        .focusRequester(voiceButtonFocusRequester)
                        .focusProperties {
                            left = inputFocusRequester
                            down = filterToggleRequester
                        }
                        .onFocusChanged {
                            if (it.isFocused) onContentFocused(voiceButtonFocusRequester)
                        },
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.common_search),
                        tint = Amber,
                    )
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item("type_all") {
                    TvSearchChip(
                        text = stringResource(R.string.tv_search_all),
                        selected = filterType == null,
                        modifier = Modifier
                            .focusRequester(allTypeRequester)
                            .focusProperties {
                                left = railFocusRequester
                                right = movieTypeRequester
                                up = inputFocusRequester
                                down = if (results.isNotEmpty()) firstResultRequester else FocusRequester.Default
                            },
                        onFocused = { onContentFocused(allTypeRequester) },
                        onClick = { filterType = null },
                    )
                }
                item("type_movies") {
                    TvSearchChip(
                        text = stringResource(R.string.tv_search_movies),
                        selected = filterType == "movie",
                        modifier = Modifier
                            .focusRequester(movieTypeRequester)
                            .focusProperties {
                                left = allTypeRequester
                                right = tvTypeRequester
                                up = inputFocusRequester
                                down = if (results.isNotEmpty()) firstResultRequester else FocusRequester.Default
                            },
                        onFocused = { onContentFocused(movieTypeRequester) },
                        onClick = { filterType = "movie" },
                    )
                }
                item("type_tv") {
                    TvSearchChip(
                        text = stringResource(R.string.tv_search_tv_shows),
                        selected = filterType == "tv",
                        modifier = Modifier
                            .focusRequester(tvTypeRequester)
                            .focusProperties {
                                left = movieTypeRequester
                                right = standardModeRequester
                                up = inputFocusRequester
                                down = if (results.isNotEmpty()) firstResultRequester else FocusRequester.Default
                            },
                        onFocused = { onContentFocused(tvTypeRequester) },
                        onClick = { filterType = "tv" },
                    )
                }
                item("mode_standard") {
                    TvSearchChip(
                        text = stringResource(R.string.tv_search_mode_standard),
                        selected = searchMode == SearchMode.STANDARD,
                        modifier = Modifier
                            .focusRequester(standardModeRequester)
                            .focusProperties {
                                left = tvTypeRequester
                                right = aiModeRequester
                                up = inputFocusRequester
                                down = if (results.isNotEmpty()) firstResultRequester else FocusRequester.Default
                            },
                        onFocused = { onContentFocused(standardModeRequester) },
                        onClick = { searchMode = SearchMode.STANDARD },
                    )
                }
                item("mode_ai") {
                    TvSearchChip(
                        text = if (hasAiKey) {
                            stringResource(R.string.tv_search_mode_ai)
                        } else {
                            "${stringResource(R.string.tv_search_mode_ai)} (${stringResource(R.string.tv_search_ai_configure)})"
                        },
                        selected = searchMode == SearchMode.AI,
                        modifier = Modifier
                            .focusRequester(aiModeRequester)
                            .focusProperties {
                                left = standardModeRequester
                                right = filterToggleRequester
                                up = inputFocusRequester
                                down = if (results.isNotEmpty()) firstResultRequester else FocusRequester.Default
                            },
                        onFocused = { onContentFocused(aiModeRequester) },
                        onClick = { if (hasAiKey) searchMode = SearchMode.AI },
                    )
                }
                item("filters") {
                    TvSearchChip(
                        text = if (showFilters) {
                            "Close filters"
                        } else if (activeFilterCount > 0) {
                            "Filters · $activeFilterCount active"
                        } else {
                            "Filters"
                        },
                        selected = showFilters || activeFilterCount > 0,
                        modifier = Modifier
                            .focusRequester(filterToggleRequester)
                            .focusProperties {
                                left = aiModeRequester
                                up = inputFocusRequester
                                down = if (showFilters) {
                                    closeFiltersRequester
                                } else if (results.isNotEmpty()) {
                                    firstResultRequester
                                } else {
                                    FocusRequester.Default
                                }
                            },
                        onFocused = { onContentFocused(filterToggleRequester) },
                        onClick = { showFilters = !showFilters },
                    )
                }
            }

            if (showFilters) {
                val drawerScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .width(720.dp)
                        .heightIn(max = 430.dp)
                        .focusGroup()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Charcoal.copy(alpha = 0.78f))
                        .border(1.dp, Snow.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Refine results",
                            style = MaterialTheme.typography.titleMedium,
                            color = Snow,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TvSearchChip(
                            text = "Clear all",
                            selected = activeFilterCount > 0,
                            modifier = Modifier
                                .focusRequester(clearFiltersRequester)
                                .focusProperties {
                                    left = closeFiltersRequester
                                    right = closeFiltersRequester
                                    up = clearFiltersRequester
                                    down = firstFilterRequester
                                },
                            onFocused = { onContentFocused(clearFiltersRequester) },
                            onClick = {
                                selectedGenreIds = emptySet()
                                selectedStudioIds = emptySet()
                                selectedYear = null
                                selectedMinRating = null
                                filterType = null
                                searchMode = SearchMode.STANDARD
                            },
                        )
                        TvSearchChip(
                            text = "Close",
                            modifier = Modifier
                                .focusRequester(closeFiltersRequester)
                                .focusProperties {
                                    left = clearFiltersRequester
                                    right = closeFiltersRequester
                                    up = closeFiltersRequester
                                    down = firstFilterRequester
                                },
                            onFocused = { onContentFocused(closeFiltersRequester) },
                            onClick = {
                                showFilters = false
                                focusResultsAfterClosingFilters = true
                            },
                        )
                    }
                    Box {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 350.dp)
                                .verticalScroll(drawerScrollState)
                                .padding(bottom = 26.dp),
                        ) {
                            TvSearchFilterRows(
                                genres = availableGenres,
                                selectedGenreIds = selectedGenreIds,
                                onToggleGenre = { id ->
                                    selectedGenreIds = if (id in selectedGenreIds) selectedGenreIds - id else selectedGenreIds + id
                                },
                                studios = emptyList(),
                                selectedStudioIds = selectedStudioIds,
                                onToggleStudio = { id ->
                                    selectedStudioIds = if (id in selectedStudioIds) selectedStudioIds - id else selectedStudioIds + id
                                },
                                years = availableYears.take(14),
                                selectedYear = selectedYear,
                                onSelectYear = { year -> selectedYear = if (selectedYear == year) null else year },
                                selectedMinRating = selectedMinRating,
                                onSelectRating = { rating -> selectedMinRating = if (selectedMinRating == rating) null else rating },
                                railFocusRequester = firstFilterRequester,
                                firstFilterRequester = firstFilterRequester,
                                upRequester = closeFiltersRequester,
                                resultsRequester = null,
                                onContentFocused = onContentFocused,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(42.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Charcoal.copy(alpha = 0.92f)),
                                    ),
                                ),
                        )
                    }
                }
            }

            when (voiceController.uiState.value.phase) {
                VoiceInputPhase.Listening -> Text(stringResource(R.string.tv_voice_listening), color = Amber)
                VoiceInputPhase.Processing -> Text(stringResource(R.string.tv_voice_processing), color = Silver)
                VoiceInputPhase.Error,
                VoiceInputPhase.Unsupported,
                -> Text(
                    text = voiceController.uiState.value.message ?: stringResource(R.string.tv_voice_unavailable),
                    color = Ruby.copy(alpha = 0.78f),
                )
                VoiceInputPhase.Idle -> Unit
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                TvSearchSelectedPreview(
                    item = selectedResult,
                    ratingPrefs = settingsState.ratingPrefs,
                    resultCount = results.size,
                    modifier = Modifier
                        .width(312.dp)
                        .height(318.dp),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val resultTitle = when {
                        loading -> "Loading matches"
                        error != null -> "Search unavailable"
                        results.isEmpty() && activeFilterCount > 0 -> "No matches for these filters"
                        results.isEmpty() -> "Start typing to search"
                        aiResultTitle != null && searchMode == SearchMode.AI -> stringResource(R.string.tv_search_ai_label, aiResultTitle!!)
                        aiFallback -> stringResource(R.string.tv_search_ai_fallback)
                        else -> "Top matches · ${results.size} results"
                    }
                    Text(
                        text = resultTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (error != null) Ruby.copy(alpha = 0.82f) else Snow,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        loading -> TvSearchLoadingSkeletonGrid()
                        error != null -> TvSearchEmptyState(
                            title = error.orEmpty(),
                            subtitle = "Try again or broaden the search.",
                            quickTerms = listOf("Popular", "Movies", "TV Shows"),
                            onQuickTerm = { term -> query = term },
                        )
                        results.isEmpty() -> TvSearchEmptyState(
                            title = if (activeFilterCount > 0) "No matches for these filters" else "Start typing to search",
                            subtitle = if (activeFilterCount > 0) "Clear a filter or switch back to All." else "Or pick a quick filter.",
                            quickTerms = listOf("Popular", "Recently Added", "Action", "Drama", "Comedy"),
                            onQuickTerm = { term -> query = term },
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(118.dp),
                            verticalArrangement = Arrangement.spacedBy(22.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 26.dp, start = 2.dp, end = 8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                results,
                                key = { index, item -> item.tvSearchStableKey() + "_$index" },
                            ) { index, item ->
                                val requester = remember(item.tvSearchStableKey()) { FocusRequester() }
                                val activeRequester = if (index == 0) firstResultRequester else requester
                                TvSearchResultCard(
                                    item = item,
                                    modifier = Modifier
                                        .focusRequester(activeRequester)
                                        .aspectRatio(2f / 3f)
                                        .focusProperties {
                                            if (index % 8 == 0) {
                                                left = railFocusRequester
                                            }
                                            up = filterToggleRequester
                                        },
                                    onFocused = {
                                        selectedResult = item
                                        onContentFocused(activeRequester)
                                    },
                                    onClick = { onMediaClick(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    return
/*
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF07101C),
                        Obsidian,
                        Color(0xFF05070D),
                    ),
                ),
            )
            .padding(
                start = 122.dp,
                top = if (resultsMode) 30.dp else 54.dp,
                end = 56.dp,
                bottom = 34.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(if (resultsMode) 12.dp else 20.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (resultsMode) 50.dp else 72.dp)
                    .clip(RoundedCornerShape(if (resultsMode) 16.dp else 22.dp))
                    .background(Charcoal.copy(alpha = 0.62f))
                    .border(1.dp, Snow.copy(alpha = 0.14f), RoundedCornerShape(if (resultsMode) 16.dp else 22.dp))
                    .padding(start = 18.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvClickToEditOutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tv_search_hint)) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFocusRequester)
                        .focusProperties {
                            left = railFocusRequester
                            right = voiceButtonFocusRequester
                            down = filterToggleRequester
                        }
                        .onFocusChanged { if (it.isFocused) onContentFocused(inputFocusRequester) },
                )
                IconButton(
                    onClick = { voiceController.launch() },
                    modifier = Modifier
                        .focusRequester(voiceButtonFocusRequester)
                        .focusProperties {
                            left = inputFocusRequester
                            down = filterToggleRequester
                        }
                        .onFocusChanged {
                            if (it.isFocused) {
                                onContentFocused(voiceButtonFocusRequester)
                            }
                        },
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.common_search),
                        tint = Amber,
                    )
                }
            }
            TvSearchChip(
                text = stringResource(
                    if (showFilters) R.string.tv_search_hide_filters else R.string.tv_search_show_filters,
                ),
                modifier = Modifier
                    .focusRequester(filterToggleRequester)
                    .focusProperties {
                        left = railFocusRequester
                        up = inputFocusRequester
                        down = if (showFilters) {
                            standardModeRequester
                        } else if (results.isNotEmpty()) {
                            firstResultRequester
                        } else {
                            FocusRequester.Default
                        }
                    },
                onFocused = { onContentFocused(filterToggleRequester) },
                onClick = {
                    if (showFilters) {
                        showFilters = false
                        focusResultsAfterClosingFilters = true
                    } else {
                        showFilters = true
                    }
                },
            )

            if (showFilters) {
            // Search mode toggle row
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvSearchChip(
                    text = stringResource(R.string.tv_search_mode_standard),
                    selected = searchMode == SearchMode.STANDARD,
                    modifier = Modifier
                        .focusRequester(standardModeRequester)
                        .focusProperties {
                            left = railFocusRequester
                            right = aiModeRequester
                            up = filterToggleRequester
                            down = allTypeRequester
                        },
                    onFocused = { onContentFocused(standardModeRequester) },
                    onClick = { searchMode = SearchMode.STANDARD },
                )

                if (hasAiKey) {
                    TvSearchChip(
                        text = stringResource(R.string.tv_search_mode_ai),
                        selected = searchMode == SearchMode.AI,
                        modifier = Modifier
                            .focusRequester(aiModeRequester)
                            .focusProperties {
                                left = standardModeRequester
                                right = closeFiltersRequester
                                up = filterToggleRequester
                                down = allTypeRequester
                            },
                        onFocused = { onContentFocused(aiModeRequester) },
                        onClick = { searchMode = SearchMode.AI },
                    )
                } else {
                    TvSearchChip(
                        text = "${stringResource(R.string.tv_search_mode_ai)} (${stringResource(R.string.tv_search_ai_configure)})",
                        selected = false,
                        modifier = Modifier
                            .focusRequester(aiModeRequester)
                            .focusProperties {
                                left = standardModeRequester
                                right = closeFiltersRequester
                                up = filterToggleRequester
                                down = allTypeRequester
                            },
                        onFocused = { onContentFocused(aiModeRequester) },
                        onClick = { /* disabled — no API key */ },
                    )
                }
                TvSearchChip(
                    text = stringResource(R.string.common_close),
                    modifier = Modifier
                        .focusRequester(closeFiltersRequester)
                        .focusProperties {
                            left = aiModeRequester
                            up = filterToggleRequester
                            down = allTypeRequester
                        },
                    onFocused = { onContentFocused(closeFiltersRequester) },
                    onClick = {
                        showFilters = false
                        focusResultsAfterClosingFilters = true
                    },
                )
            }

            // Type filter row
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvSearchChip(
                    text = stringResource(R.string.tv_search_all),
                    selected = filterType == null,
                    modifier = Modifier
                        .focusRequester(allTypeRequester)
                        .focusProperties {
                            left = railFocusRequester
                            right = movieTypeRequester
                            up = standardModeRequester
                            down = firstFilterRequester
                        },
                    onFocused = { onContentFocused(allTypeRequester) },
                    onClick = { filterType = null },
                )
                TvSearchChip(
                    text = stringResource(R.string.tv_search_movies),
                    selected = filterType == "movie",
                    modifier = Modifier
                        .focusRequester(movieTypeRequester)
                        .focusProperties {
                            left = allTypeRequester
                            right = tvTypeRequester
                            up = standardModeRequester
                            down = firstFilterRequester
                        },
                    onFocused = { onContentFocused(movieTypeRequester) },
                    onClick = { filterType = "movie" },
                )
                TvSearchChip(
                    text = stringResource(R.string.tv_search_tv_shows),
                    selected = filterType == "tv",
                    modifier = Modifier
                        .focusRequester(tvTypeRequester)
                        .focusProperties {
                            left = movieTypeRequester
                            up = standardModeRequester
                            down = firstFilterRequester
                        },
                    onFocused = { onContentFocused(tvTypeRequester) },
                    onClick = { filterType = "tv" },
                )
            }

            TvSearchFilterRows(
                genres = availableGenres,
                selectedGenreIds = selectedGenreIds,
                onToggleGenre = { id ->
                    selectedGenreIds = if (id in selectedGenreIds) selectedGenreIds - id else selectedGenreIds + id
                },
                studios = availableStudios,
                selectedStudioIds = selectedStudioIds,
                onToggleStudio = { id ->
                    selectedStudioIds = if (id in selectedStudioIds) selectedStudioIds - id else selectedStudioIds + id
                },
                years = availableYears,
                selectedYear = selectedYear,
                onSelectYear = { year -> selectedYear = if (selectedYear == year) null else year },
                selectedMinRating = selectedMinRating,
                onSelectRating = { rating -> selectedMinRating = if (selectedMinRating == rating) null else rating },
                railFocusRequester = railFocusRequester,
                firstFilterRequester = firstFilterRequester,
                upRequester = allTypeRequester,
                resultsRequester = firstResultRequester.takeIf { results.isNotEmpty() },
                onContentFocused = onContentFocused,
            )

            when (voiceController.uiState.value.phase) {
                VoiceInputPhase.Listening -> {
                    Text(
                        text = stringResource(R.string.tv_voice_listening),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Amber,
                    )
                }

                VoiceInputPhase.Processing -> {
                    Text(
                        text = stringResource(R.string.tv_voice_processing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Silver,
                    )
                }

                VoiceInputPhase.Error,
                VoiceInputPhase.Unsupported,
                -> {
                    Text(
                        text = voiceController.uiState.value.message
                            ?: stringResource(R.string.tv_voice_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ruby.copy(alpha = 0.7f),
                    )
                }

                VoiceInputPhase.Idle -> Unit
            }
            } // end showFilters (mode chips, filter chips, voice status)
        }

        if (showFilters && !resultsMode) {
        Text(
            text = stringResource(R.string.tv_section_popular_searches),
            style = MaterialTheme.typography.titleLarge,
            color = Snow,
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 12.dp),
        ) {
            items(popularQueries) { term ->
                val requester = remember(term) { FocusRequester() }
                TvSearchChip(
                    text = term,
                    modifier = Modifier
                        .focusRequester(requester)
                        .focusProperties { left = railFocusRequester },
                    onFocused = { onContentFocused(requester) },
                    onClick = { query = term },
                )
            }
        }
        } // end showFilters

        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Amber)
                }
            }

            error != null -> {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ruby.copy(alpha = 0.7f),
                )
            }

            results.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Charcoal.copy(alpha = 0.54f))
                        .border(1.dp, Steel.copy(alpha = 0.42f), RoundedCornerShape(24.dp))
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "Start typing or pick a popular search",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Snow,
                    )
                    Text(
                        text = "Find movies, shows, channels, and addons across your sources.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Silver,
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 8.dp),
                    ) {
                        items(listOf("Recently added", "4K movies", "Anime", "New episodes")) { term ->
                            val requester = remember(term) { FocusRequester() }
                            TvSearchChip(
                                text = term,
                                modifier = Modifier
                                    .focusRequester(requester)
                                    .focusProperties { left = railFocusRequester },
                                onFocused = { onContentFocused(requester) },
                                onClick = { query = term },
                            )
                        }
                    }
                }
            }

            else -> {
                // AI result label or fallback notice
                if (aiResultTitle != null && searchMode == SearchMode.AI) {
                    Text(
                        text = stringResource(R.string.tv_search_ai_label, aiResultTitle!!),
                        style = MaterialTheme.typography.titleLarge,
                        color = Amber,
                    )
                } else if (aiFallback) {
                    Text(
                        text = stringResource(R.string.tv_search_ai_fallback),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Silver,
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp, start = 2.dp, end = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        results,
                        key = { index, item ->
                            item.tmdbId?.let { "s_${item.type}_$it" } ?: "${item.type}_${item.id}_$index"
                        },
                    ) { index, item ->
                        val requester = remember(item.id) { FocusRequester() }
                        val activeRequester = if (index == 0) firstResultRequester else requester
                        TvSearchResultCard(
                            item = item,
                            modifier = Modifier
                                .focusRequester(activeRequester)
                                .aspectRatio(2f / 3f)
                                .focusProperties {
                                    if (index % 6 == 0) {
                                        left = railFocusRequester
                                    }
                                    up = if (showFilters) firstFilterRequester else inputFocusRequester
                                },
                            onFocused = {
                                onContentFocused(activeRequester)
                            },
                            onClick = { onMediaClick(item) },
                        )
                    }
                }
            }
        }
    }
*/
}

@Composable
private fun TvSearchSelectedPreview(
    item: MediaItem?,
    ratingPrefs: RatingDisplayPrefs,
    resultCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Charcoal.copy(alpha = 0.64f))
            .border(1.dp, Snow.copy(alpha = 0.10f), RoundedCornerShape(24.dp)),
    ) {
        if (item == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Search",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Start typing or choose a filter to browse movies, shows, and channels.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Silver,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$resultCount results",
                    style = MaterialTheme.typography.labelLarge,
                    color = Amber,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            return
        }

        val artUrl = item.backdropUrl?.takeIf { it.isNotBlank() } ?: item.posterUrl?.takeIf { it.isNotBlank() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp),
        ) {
            if (!artUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Gunmetal.copy(alpha = 0.84f)),
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Obsidian.copy(alpha = 0.58f),
                                Obsidian.copy(alpha = 0.94f),
                            ),
                        ),
                    ),
            )
            item.logoUrl?.takeIf { it.isNotBlank() }?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp)
                        .fillMaxWidth(0.72f)
                        .heightIn(min = 32.dp, max = 54.dp),
                )
            } ?: Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
            )
        }

        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val meta = item.tvSearchMetadataLine()
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelLarge,
                    color = Silver,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val ratings = item.ratings.withFallbackTmdbScore(item.rating)
            if (ratings != null && ratings.hasAnyEnabledDisplayValue(ratingPrefs)) {
                PreferredRatingPills(
                    ratings = ratings,
                    prefs = ratingPrefs.copy(maxRatingsOnCard = 2),
                )
            }
            item.genres.takeIf { it.isNotEmpty() }?.let { genres ->
                Text(
                    text = genres.take(2).joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.overview?.takeIf { it.isNotBlank() } ?: "Focus a poster to see title details, ratings, and source context.",
                style = MaterialTheme.typography.bodyMedium,
                color = Snow.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "OK for Details",
                style = MaterialTheme.typography.labelLarge,
                color = Amber,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TvSearchLoadingSkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(118.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 26.dp, start = 2.dp, end = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(18) {
            Box(
                modifier = Modifier
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Gunmetal.copy(alpha = 0.82f),
                                Charcoal.copy(alpha = 0.92f),
                            ),
                        ),
                    )
                    .border(1.dp, Snow.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            )
        }
    }
}

@Composable
private fun TvSearchEmptyState(
    title: String,
    subtitle: String,
    quickTerms: List<String>,
    onQuickTerm: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Charcoal.copy(alpha = 0.54f))
            .border(1.dp, Snow.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = Silver,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp),
        ) {
            items(quickTerms) { term ->
                val requester = remember(term) { FocusRequester() }
                TvSearchChip(
                    text = term,
                    modifier = Modifier.focusRequester(requester),
                    onFocused = {},
                    onClick = { onQuickTerm(term) },
                )
            }
        }
    }
}

@Composable
private fun TvSearchChip(
    text: String,
    modifier: Modifier,
    selected: Boolean = false,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.03f else 1f, label = "chipScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && selected -> Snow
            selected -> Amber
            focused -> AmberLight
            else -> Snow.copy(alpha = 0.10f)
        },
        label = "chipBorder",
    )
    val bgColor = when {
        selected -> Amber.copy(alpha = 0.16f)
        focused -> Graphite.copy(alpha = 0.78f)
        else -> Charcoal.copy(alpha = 0.58f)
    }
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused && selected) Snow else if (selected) Amber else Snow,
        )
    }
}

@Composable
private fun TvSearchFilterRows(
    genres: List<Pair<Int, String>>,
    selectedGenreIds: Set<Int>,
    onToggleGenre: (Int) -> Unit,
    studios: List<Pair<Int, String>>,
    selectedStudioIds: Set<Int>,
    onToggleStudio: (Int) -> Unit,
    years: List<Int>,
    selectedYear: Int?,
    onSelectYear: (Int) -> Unit,
    selectedMinRating: Double?,
    onSelectRating: (Double) -> Unit,
    railFocusRequester: FocusRequester,
    firstFilterRequester: FocusRequester,
    upRequester: FocusRequester,
    resultsRequester: FocusRequester?,
    onContentFocused: (FocusRequester) -> Unit,
) {
    val columns = 5
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val groups = remember(genres, studios, years) {
            buildList {
                if (genres.isNotEmpty()) add(TvSearchFilterGroup("genre", "Genre", genres))
                if (studios.isNotEmpty()) add(TvSearchFilterGroup("studio", "Network / Studio", studios))
                if (years.isNotEmpty()) add(TvSearchFilterGroup("year", "Year", years.map { it to it.toString() }))
                add(TvSearchFilterGroup("rating", "Rating", listOf(7 to "7+", 8 to "8+", 9 to "9+")))
            }
        }
        val chips = remember(groups) {
            groups.flatMap { group ->
                group.options.map { (id, label) -> TvSearchFilterChipSpec(group.key, id, label) }
            }
        }
        val visualRows = remember(groups) {
            val rows = mutableListOf<TvSearchFilterVisualRow>()
            var chipIndex = 0
            groups.forEach { group ->
                group.options.chunked(columns).forEachIndexed { chunkIndex, chunk ->
                    rows += TvSearchFilterVisualRow(
                        groupLabel = group.label.takeIf { chunkIndex == 0 },
                        chipIndices = List(chunk.size) { offset -> chipIndex + offset },
                    )
                    chipIndex += chunk.size
                }
            }
            rows
        }
        val chipKeys = remember(chips) { chips.map { "${it.groupKey}:${it.id}" } }
        val chipRequesters = remember(chipKeys, firstFilterRequester) {
            List(chipKeys.size) { index -> if (index == 0) firstFilterRequester else FocusRequester() }
        }

        visualRows.forEachIndexed { rowIndex, row ->
            row.groupLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Silver,
                    modifier = Modifier.padding(top = if (rowIndex == 0) 0.dp else 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.chipIndices.forEachIndexed { columnIndex, chipIndex ->
                    val chip = chips[chipIndex]
                    val requester = chipRequesters[chipIndex]
                    val previousInRow = row.chipIndices.getOrNull(columnIndex - 1)?.let { chipRequesters[it] }
                    val nextInRow = row.chipIndices.getOrNull(columnIndex + 1)?.let { chipRequesters[it] }
                    val previousRow = visualRows.getOrNull(rowIndex - 1)
                        ?.chipIndices
                        ?.let { indices -> indices.getOrNull(columnIndex) ?: indices.lastOrNull() }
                        ?.let { chipRequesters[it] }
                    val nextRow = visualRows.getOrNull(rowIndex + 1)
                        ?.chipIndices
                        ?.let { indices -> indices.getOrNull(columnIndex) ?: indices.lastOrNull() }
                        ?.let { chipRequesters[it] }
                    val selected = when (chip.groupKey) {
                        "genre" -> chip.id in selectedGenreIds
                        "studio" -> chip.id in selectedStudioIds
                        "year" -> selectedYear == chip.id
                        "rating" -> selectedMinRating?.toInt() == chip.id
                        else -> false
                    }
                TvSearchChip(
                    text = chip.label,
                    selected = selected,
                    modifier = Modifier
                        .focusRequester(requester)
                        .focusProperties {
                            left = previousInRow ?: requester
                            right = nextInRow ?: requester
                            up = previousRow ?: upRequester
                            down = nextRow ?: requester
                        },
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        when (chip.groupKey) {
                            "genre" -> onToggleGenre(chip.id)
                            "studio" -> onToggleStudio(chip.id)
                            "year" -> onSelectYear(chip.id)
                            "rating" -> onSelectRating(chip.id.toDouble())
                        }
                    },
                )
                }
            }
        }
    }
}

@Composable
private fun TvSearchResultCard(
    item: MediaItem,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.07f else 1f, label = "resultScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "resultBorder",
    )
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(3.dp, borderColor, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)),
    ) {
        val imageUrl = item.posterUrl ?: item.backdropUrl
        if (imageUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Charcoal.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Snow,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp),
                )
            }
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun MediaItem.tvSearchStableKey(): String {
    val imdb = imdbId?.takeIf { it.isNotBlank() }
    return when {
        tmdbId != null -> "${type.name}:tmdb:$tmdbId"
        imdb != null -> "${type.name}:imdb:${imdb.lowercase()}"
        else -> "${type.name}:title:${title.tvSearchNormalizeTitle()}:${year ?: 0}"
    }
}

private fun String.tvSearchNormalizeTitle(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
        .trim()

private fun MediaItem.tvSearchMetadataLine(): String {
    val parts = buildList {
        year?.let { add(it.toString()) }
        add(if (type == MediaType.SERIES) "Series" else "Movie")
        runtime?.takeIf { it > 0 }?.let { add("${it}m") }
        if (type == MediaType.SERIES && seasons.isNotEmpty()) {
            add("${seasons.size} season${if (seasons.size == 1) "" else "s"}")
        }
        voteCount?.takeIf { it > 0 }?.let { add("$it votes") }
    }
    return parts.joinToString(" · ")
}

private fun mergeTvSearchDuplicate(primary: MediaItem, other: MediaItem): MediaItem =
    primary.copy(
        tmdbId = primary.tmdbId ?: other.tmdbId,
        imdbId = primary.imdbId ?: other.imdbId,
        year = primary.year ?: other.year,
        overview = primary.overview ?: other.overview,
        posterUrl = primary.posterUrl ?: other.posterUrl,
        backdropUrl = primary.backdropUrl ?: other.backdropUrl,
        logoUrl = primary.logoUrl ?: other.logoUrl,
        rating = primary.rating ?: other.rating,
        voteCount = primary.voteCount ?: other.voteCount,
        runtime = primary.runtime ?: other.runtime,
        genres = primary.genres.ifEmpty { other.genres },
        genreIds = primary.genreIds.ifEmpty { other.genreIds },
        studios = primary.studios.ifEmpty { other.studios },
        seasons = primary.seasons.ifEmpty { other.seasons },
        ratings = primary.ratings ?: other.ratings,
        popularity = primary.popularity ?: other.popularity,
    )

private fun List<MediaItem>.dedupeTvSearchResults(): List<MediaItem> {
    val byKey = LinkedHashMap<String, MediaItem>()
    for (item in this) {
        val key = item.tvSearchStableKey()
        val existing = byKey[key]
        byKey[key] = if (existing == null) item else mergeTvSearchDuplicate(existing, item)
    }
    return byKey.values.toList()
}

private suspend fun MetadataRepository.loadTvSearchBrowseResults(
    filterType: String?,
    genreIds: Set<Int>,
    year: Int?,
    minRating: Double?,
): List<MediaItem> {
    val types = when (filterType) {
        "movie" -> listOf("movie")
        "tv" -> listOf("tv")
        else -> listOf("movie", "tv")
    }
    val genres = genreIds.takeIf { it.isNotEmpty() }?.joinToString(",")
    val sortBy = if (minRating != null) "vote_average.desc" else "popularity.desc"
    return types
        .flatMap { type ->
            (1..2).flatMap { page ->
                runCatching {
                    discover(
                        type = type,
                        page = page,
                        sortBy = sortBy,
                        withGenres = genres,
                        minRating = minRating?.toFloat(),
                        year = year,
                    ).items
                }.getOrDefault(emptyList())
            }
        }
        .distinctBy { it.tvSearchStableKey() }
        .take(80)
}

private fun tvSearchSafeError(error: Throwable): String {
    val message = error.message.orEmpty().lowercase()
    return when {
        "gzip" in message ||
            "exhausting source" in message ||
            "unexpected end" in message ||
            "timeout" in message ||
            "failed to connect" in message -> "Couldn't load search results. Please try again."
        else -> "Search failed. Please try again."
    }
}

private fun tvSearchDefaultGenres(filterType: String?): List<Pair<Int, String>> {
    val movie = listOf(
        28 to "Action",
        12 to "Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        14 to "Fantasy",
        27 to "Horror",
        9648 to "Mystery",
        10749 to "Romance",
        878 to "Science Fiction",
        53 to "Thriller",
        37 to "Western",
    )
    val tv = listOf(
        10759 to "Action & Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        10762 to "Kids",
        9648 to "Mystery",
        10764 to "Reality",
        10765 to "Sci-Fi & Fantasy",
        10766 to "Soap",
        10767 to "Talk",
        10768 to "War & Politics",
    )
    return when (filterType) {
        "movie" -> movie
        "tv" -> tv
        else -> (movie + tv).distinctBy { it.first to it.second }
    }
}

private fun List<MediaItem>.availableTvSearchGenres(): List<Pair<Int, String>> =
    flatMap { item ->
        item.genres.map { it.id to it.name }
            .ifEmpty { item.genreIds.mapNotNull { id -> tvSearchGenreLabel(id)?.let { id to it } } }
    }
        .filter { it.first > 0 && it.second.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }

private fun List<MediaItem>.availableTvSearchStudios(): List<Pair<Int, String>> =
    flatMap { it.studios }
        .filter { it.id > 0 && it.name.isNotBlank() }
        .groupingBy { it.id to it.name }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }

private fun List<MediaItem>.filterTvSearchItems(
    genreIds: Set<Int>,
    studioIds: Set<Int>,
    year: Int?,
    minRating: Double?,
): List<MediaItem> =
    filter { item ->
        (genreIds.isEmpty() || item.genreIds.any { it in genreIds } || item.genres.any { it.id in genreIds }) &&
            (studioIds.isEmpty() || item.studios.any { it.id in studioIds }) &&
            (year == null || item.year == year) &&
            (minRating == null || (item.ratings?.imdbScore?.toDouble() ?: item.rating ?: 0.0) >= minRating)
    }
        .dedupeTvSearchResults()
        .sortedWith(
            compareByDescending<MediaItem> { it.popularity ?: 0.0 }
                .thenByDescending { it.ratings?.imdbScore?.toDouble() ?: it.rating ?: 0.0 }
                .thenByDescending { it.year ?: 0 },
        )

private fun tvSearchGenreLabel(id: Int): String? = when (id) {
    28 -> "Action"
    12 -> "Adventure"
    16 -> "Animation"
    35 -> "Comedy"
    80 -> "Crime"
    99 -> "Documentary"
    18 -> "Drama"
    10751 -> "Family"
    14 -> "Fantasy"
    36 -> "History"
    27 -> "Horror"
    10402 -> "Music"
    9648 -> "Mystery"
    10749 -> "Romance"
    878 -> "Science Fiction"
    10770 -> "TV Movie"
    53 -> "Thriller"
    10752 -> "War"
    37 -> "Western"
    10759 -> "Action & Adventure"
    10762 -> "Kids"
    10763 -> "News"
    10764 -> "Reality"
    10765 -> "Sci-Fi & Fantasy"
    10766 -> "Soap"
    10767 -> "Talk"
    10768 -> "War & Politics"
    else -> null
}
