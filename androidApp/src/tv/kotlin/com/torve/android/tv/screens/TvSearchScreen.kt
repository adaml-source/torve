package com.torve.android.tv.screens

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.tv.TV_FILTER_ROW_NESTED_END_GUTTER
import com.torve.android.tv.TV_PAGE_BOTTOM_GUTTER
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.TV_PAGE_TOP_GUTTER
import com.torve.android.tv.TvImagePrefetcher
import com.torve.android.tv.TvScreenCache
import com.torve.android.tv.components.tvExternalCardRatingPrefs
import com.torve.android.tv.focus.TvFocusTargetId
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.focus.rememberRegisteredTvFocusRequester
import com.torve.android.tv.focus.rememberTvModalFocusRestoreController
import com.torve.android.ui.theme.*
import com.torve.data.ai.KeywordSearchService
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.model.hasExternalRatingLookupIdentity
import com.torve.domain.model.needsExternalRatingEnrichment
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.sourceavailability.SourceAvailabilityAggregator
import com.torve.domain.sourceavailability.SourceAvailabilityRecord
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.Calendar

private enum class SearchMode { STANDARD, AI }

private enum class TvSearchDensity(val columns: Int, val label: String) {
    SHOWCASE(3, "3"),
    RELAXED(4, "4"),
    BALANCED(5, "5"),
    COMPACT(6, "6"),
}

internal enum class TvSearchSort(val label: String) {
    RELEVANCE("Best match"),
    IMDB_DESC("IMDb rating high"),
    IMDB_ASC("IMDb rating low"),
    ROTTEN_TOMATOES_DESC("Rotten Tomatoes high"),
    ROTTEN_TOMATOES_ASC("Rotten Tomatoes low"),
    RT_AUDIENCE_DESC("RT audience high"),
    RT_AUDIENCE_ASC("RT audience low"),
    TMDB_DESC("TMDB rating high"),
    TMDB_ASC("TMDB rating low"),
    VOTES_DESC("IMDb votes high"),
    VOTES_ASC("IMDb votes low"),
    POPULARITY_DESC("Popular"),
    POPULARITY_ASC("Least popular"),
    TITLE_ASC("A-Z"),
    TITLE_DESC("Z-A"),
}

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

private const val TV_SEARCH_DISCOVER_PAGE_COUNT = 2
private const val TV_SEARCH_RATING_DISCOVER_PAGE_COUNT = 6
private const val TV_SEARCH_MAX_RESULTS = 300
private const val TV_SEARCH_PREFETCH_RESULT_COUNT = 96
private const val TV_SEARCH_RATING_CACHE_COUNT = 180
private const val TV_SEARCH_RATING_ENRICH_COUNT = 12
private const val TV_SEARCH_DETAIL_HYDRATE_COUNT = 12
private const val TV_SEARCH_LOAD_AHEAD_ROWS = 2
private const val TV_SEARCH_TOP_RATED_LOAD_AHEAD_ROWS = 4
private const val TV_SEARCH_RESULT_PAGE_SIZE = 40
private const val TV_SEARCH_VERIFIED_INITIAL_RESULT_COUNT = 12
private const val TV_SEARCH_VERIFIED_RESULT_INCREMENT = 12
private const val TV_SEARCH_VERIFIED_MAX_CANDIDATE_PAGES_PER_BATCH = 1
private const val TV_SEARCH_MAX_CANDIDATES = 10_000
internal const val TV_SEARCH_TOP_RATED_FILTER = -1.0
internal const val TV_SEARCH_TOP_RATED_MIN_SCORE = 7.0
internal const val TV_SEARCH_TOP_RATED_MIN_VOTES = 500
private val TV_SEARCH_IMDB_VOTE_THRESHOLDS = listOf(1_000, 5_000, 10_000, 25_000, 50_000, 100_000)
private val TV_SEARCH_YEAR_RANGE_STARTS = listOf(2020, 2010, 2000, 1990, 1980, 1970, 0)

private data class TvSearchCandidatePage(
    val items: List<MediaItem>,
    val page: Int,
    val totalPages: Int,
)

private data class TvSearchVerifiedBrowseBatch(
    val candidates: List<MediaItem>,
    val results: List<MediaItem>,
    val nextPage: Int,
    val totalPages: Int,
    val canLoadMore: Boolean,
)

@Composable
internal fun TvSearchScreen(
    railFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    initialQuery: String = "",
    initialFilterType: String? = null,
    instanceKey: String = "main",
    onClose: (() -> Unit)? = null,
    shouldAutoFocus: Boolean = true,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val keywordSearchService: KeywordSearchService = koinInject()
    val ratingsEnricher: RatingsEnricher = koinInject()
    val prefsRepo: PreferencesRepository = koinInject()
    val secretStore: IntegrationSecretStore = koinInject()
    val availabilityAggregator: SourceAvailabilityAggregator = koinInject()
    val settingsState by settingsViewModel.state.collectAsState()
    val context = LocalContext.current
    val screenId = remember(instanceKey) { "tv_search:$instanceKey" }
    val routeCacheKey = remember(instanceKey) {
        if (instanceKey == "main") TV_SEARCH_ROUTE_CACHE_KEY
        else "$TV_SEARCH_ROUTE_CACHE_KEY:$instanceKey"
    }
    val focusRestoreController = rememberTvModalFocusRestoreController(key = screenId)
    val restoredRouteCache = remember(routeCacheKey) {
        TvScreenCache.get<TvSearchRouteCacheState>(routeCacheKey)
            ?.takeIf { it.isFresh() }
            ?.also {
                Log.d(
                    "TvSearchCache",
                    "search_cache_restore queryLength=${it.query.length} results=${it.results.size}",
                )
            }
    }
    val restoredContentCache = restoredRouteCache?.takeIf { it.results.isNotEmpty() }

    var query by rememberSaveable(instanceKey) {
        mutableStateOf(restoredContentCache?.query ?: initialQuery)
    }
    var baseResults by remember { mutableStateOf(restoredContentCache?.baseResults.orEmpty()) }
    var results by remember { mutableStateOf(restoredContentCache?.results.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchMode by rememberSaveable { mutableStateOf(restoredContentCache?.searchMode ?: SearchMode.STANDARD) }
    var filterType by rememberSaveable(instanceKey) {
        mutableStateOf(restoredContentCache?.filterType ?: initialFilterType)
    } // "movie", "tv", or null (all)
    var selectedGenreIds by remember { mutableStateOf(restoredContentCache?.selectedGenreIds.orEmpty()) }
    var selectedStudioIds by remember { mutableStateOf(restoredContentCache?.selectedStudioIds.orEmpty()) }
    var selectedYear by remember { mutableStateOf(restoredContentCache?.selectedYear) }
    var selectedMinRating by remember { mutableStateOf(restoredContentCache?.selectedMinRating) }
    var selectedMinImdbVotes by remember { mutableStateOf(restoredContentCache?.selectedMinImdbVotes) }
    var selectedRuntimeFilter by remember { mutableStateOf(restoredContentCache?.selectedRuntimeFilter) }
    var selectedLanguageFilter by remember { mutableStateOf(restoredContentCache?.selectedLanguageFilter) }
    var selectedAvailabilityFilter by remember { mutableStateOf(restoredContentCache?.selectedAvailabilityFilter) }
    var sourceAvailabilityByTmdbId by remember { mutableStateOf<Map<Int, SourceAvailabilityRecord>>(emptyMap()) }
    var selectedSort by remember { mutableStateOf(restoredContentCache?.selectedSort ?: TvSearchSort.RELEVANCE) }
    var sortApplyNonce by remember { mutableStateOf(0) }
    var aiResultTitle by remember { mutableStateOf(restoredContentCache?.aiResultTitle) }
    var aiFallback by remember { mutableStateOf(restoredContentCache?.aiFallback ?: false) }
    var filtersVisible by rememberSaveable { mutableStateOf(restoredContentCache?.filtersVisible ?: false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var density by rememberSaveable { mutableStateOf(restoredContentCache?.density ?: TvSearchDensity.BALANCED) }
    var browseRefreshNonce by remember { mutableStateOf(0) }
    var focusResultsAfterClosingFilters by remember { mutableStateOf(false) }
    var focusCompactFiltersAfterHide by remember { mutableStateOf(false) }
    var focusFirstFilterAfterShow by remember { mutableStateOf(false) }
    var restoreAiFocusAfterToggle by remember { mutableStateOf(false) }
    var focusInputAfterClear by remember { mutableStateOf(false) }
    var selectedResultKey by remember { mutableStateOf(restoredContentCache?.selectedMediaKey) }
    var selectedResult by remember {
        mutableStateOf(
            restoredContentCache?.selectedMediaKey?.let { key ->
                restoredContentCache.results.firstOrNull { it.tvSearchStableKey() == key }
            } ?: restoredContentCache?.results?.firstOrNull(),
        )
    }
    var lastLoadedSearchStateKey by remember { mutableStateOf(restoredContentCache?.loadStateKey) }
    var nextSearchPage by remember { mutableStateOf(restoredContentCache?.nextSearchPage ?: 2) }
    var searchTotalPages by remember { mutableStateOf(restoredContentCache?.searchTotalPages ?: 1) }
    var searchCanLoadMore by remember { mutableStateOf(restoredContentCache?.searchCanLoadMore ?: false) }
    var loadingMoreResults by remember { mutableStateOf(false) }
    var pagingRetryBlockedResultKey by remember { mutableStateOf<String?>(null) }
    var loadMoreRequestNonce by remember { mutableStateOf(0) }
    var handledLoadMoreRequestNonce by remember { mutableStateOf(0) }
    var loadMoreRequestPending by remember { mutableStateOf(false) }
    var resultFocusRequestNonce by remember { mutableStateOf(0) }
    var focusedResultKey by remember { mutableStateOf<String?>(null) }
    val inputFocusRequester = remember { FocusRequester() }
    val clearSearchRequester = remember { FocusRequester() }
    val filterToggleRequester = remember { FocusRequester() }
    val advancedFiltersRequester = remember { FocusRequester() }
    val aiModeRequester = remember { FocusRequester() }
    val closeFiltersRequester = remember { FocusRequester() }
    val clearFiltersRequester = remember { FocusRequester() }
    val allTypeRequester = remember { FocusRequester() }
    val movieTypeRequester = remember { FocusRequester() }
    val tvTypeRequester = remember { FocusRequester() }
    val sortRequester = remember { FocusRequester() }
    val firstFilterRequester = remember { FocusRequester() }
    val firstResultRequester = remember { FocusRequester() }
    val resultRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val resultsGridState = rememberLazyGridState()
    val lastVisibleResultIndex by remember {
        derivedStateOf {
            resultsGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
    }
    val densityRequesters = remember {
        TvSearchDensity.entries.associateWith { FocusRequester() }
    }
    val activeDensityRequester = densityRequesters.getValue(density)
    var lastVisibleFilterRequester by remember { mutableStateOf<FocusRequester?>(null) }

    DisposableEffect(registerFocusHandle, focusRestoreController, screenId) {
        registerFocusHandle?.invoke(
            TvScreenFocusHandle(
                captureFocusedOrigin = {
                    focusRestoreController.captureFocusedOrigin(screenId = screenId)
                },
                requestRestore = { origin, reason ->
                    focusRestoreController.requestRestore(origin = origin, reason = reason)
                },
                isOriginFocused = focusRestoreController::isOriginFocused,
            ),
        )
        onDispose {
            registerFocusHandle?.invoke(null)
        }
    }

    fun requestSearchBodyFocus(): Boolean {
        if (!showFilters && results.isNotEmpty()) {
            focusedResultKey = null
            resultFocusRequestNonce += 1
            return true
        }
        val candidates = buildList {
            if (showFilters) {
                add(closeFiltersRequester)
                add(firstFilterRequester)
            } else if (filtersVisible) {
                selectedResultKey?.let { key -> resultRequesters[key]?.let(::add) }
                if (results.isNotEmpty()) add(firstResultRequester)
                lastVisibleFilterRequester?.let(::add)
                add(advancedFiltersRequester)
                add(allTypeRequester)
                add(filterToggleRequester)
                add(activeDensityRequester)
            } else {
                selectedResultKey?.let { key -> resultRequesters[key]?.let(::add) }
                if (results.isNotEmpty()) add(firstResultRequester)
                add(filterToggleRequester)
                add(activeDensityRequester)
            }
        }.distinct()

        candidates.forEach { requester ->
            val focused = runCatching { requester.requestFocus() }.isSuccess
            if (focused) {
                onContentFocused(requester)
                return true
            }
        }
        return false
    }

    fun requestSearchHeaderDownFocus(): Boolean {
        val requester = when {
            showFilters -> closeFiltersRequester
            filtersVisible -> allTypeRequester
            else -> filterToggleRequester
        }
        val focused = runCatching { requester.requestFocus() }.isSuccess
        if (focused) {
            onContentFocused(requester)
        }
        return focused
    }

    // A result-focus request is a one-shot response to an explicit Down press.
    // Keying this effect to results.size caused every later filter refresh or
    // paging update to replay the old request and steal focus from the chips.
    LaunchedEffect(resultFocusRequestNonce) {
        if (resultFocusRequestNonce == 0 || results.isEmpty()) return@LaunchedEffect

        repeat(8) {
            val visibleResultIndices = resultsGridState.layoutInfo.visibleItemsInfo
                .map { it.index }
                .filter { it in results.indices }
                .distinct()
                .ifEmpty { listOf(0) }
            for (index in visibleResultIndices) {
                val itemKey = results[index].tvSearchStableKey()
                val requester = resultRequesters[itemKey]
                    ?: firstResultRequester.takeIf { index == 0 }
                    ?: continue
                val requested = runCatching { requester.requestFocus() }.isSuccess
                if (requested) {
                    delay(32)
                }
                if (focusedResultKey == itemKey) {
                    onContentFocused(requester)
                    return@LaunchedEffect
                }
            }
            delay(32)
        }
    }

    val hasAiKey = settingsState.activeAiApiKey.isNotBlank()
    val availableGenres = remember(filterType) {
        tvSearchDefaultGenres(filterType)
    }
    // Keep these choices fixed while results hydrate/page. Exact years made the
    // drawer both incomplete and unstable because they were derived from only
    // the currently loaded result page.
    val availableYears = remember { TV_SEARCH_YEAR_RANGE_STARTS }

    val popularQueries = remember {
        listOf("Action", "Comedy", "Sci-Fi", "Drama", "Thriller", "Animation")
    }

    LaunchedEffect(results) {
        val resultKeys = results.map { it.tvSearchStableKey() }.toSet()
        val focusedKeyWasRemoved = focusedResultKey?.let { it !in resultKeys } == true
        resultRequesters.keys.retainAll(resultKeys)
        val preferredKey = selectedResultKey ?: selectedResult?.tvSearchStableKey()
        selectedResult = preferredKey?.let { key ->
            results.firstOrNull { it.tvSearchStableKey() == key }
        } ?: results.firstOrNull()
        selectedResultKey = selectedResult?.tvSearchStableKey()
        if (focusedKeyWasRemoved && results.isNotEmpty()) {
            // A filter/hydration race can legitimately remove a focused row.
            // Keep focus inside the visible grid instead of allowing the root
            // navigation rail's default requester to take ownership.
            focusedResultKey = null
            resultFocusRequestNonce += 1
        }
    }

    // Sorting is an explicit user action. Re-sorting whenever ratings or other
    // metadata hydrate moves posters underneath the current D-pad focus.
    LaunchedEffect(selectedSort, sortApplyNonce) {
        val sorted = results.sortedForTvSearch(selectedSort)
        if (sorted != results) results = sorted
    }

    LaunchedEffect(focusRestoreController.pendingRestore?.restoreToken, results.size) {
        if (results.isEmpty()) return@LaunchedEffect
        focusRestoreController.restorePendingFocus(
            screenId = screenId,
            isScreenActive = { results.isNotEmpty() },
            maxAttempts = 12,
        )
    }

    LaunchedEffect(showFilters, focusResultsAfterClosingFilters, results.size) {
        if (showFilters || !focusResultsAfterClosingFilters) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        val restoreRequester = firstResultRequester.takeIf { results.isNotEmpty() }
            ?: if (filtersVisible) advancedFiltersRequester else filterToggleRequester
        runCatching { restoreRequester.requestFocus() }
        onContentFocused(restoreRequester)
        focusResultsAfterClosingFilters = false
    }

    LaunchedEffect(filtersVisible, focusCompactFiltersAfterHide) {
        if (filtersVisible || !focusCompactFiltersAfterHide) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        runCatching { filterToggleRequester.requestFocus() }
        focusCompactFiltersAfterHide = false
    }

    LaunchedEffect(filtersVisible, focusFirstFilterAfterShow) {
        if (!filtersVisible || !focusFirstFilterAfterShow) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        runCatching { allTypeRequester.requestFocus() }
        focusFirstFilterAfterShow = false
    }

    LaunchedEffect(searchMode, restoreAiFocusAfterToggle) {
        if (!restoreAiFocusAfterToggle) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        runCatching { aiModeRequester.requestFocus() }
        onContentFocused(aiModeRequester)
        restoreAiFocusAfterToggle = false
    }

    LaunchedEffect(focusInputAfterClear) {
        if (!focusInputAfterClear) return@LaunchedEffect
        focusInputAfterClear = false
        kotlinx.coroutines.yield()
        runCatching { inputFocusRequester.requestFocus() }
        onContentFocused(inputFocusRequester)
    }

    LaunchedEffect(showFilters) {
        if (showFilters) {
            kotlinx.coroutines.delay(90)
            runCatching { firstFilterRequester.requestFocus() }
        }
    }

    val requiresImdbFilterData =
        (selectedMinRating != null && selectedMinRating != TV_SEARCH_TOP_RATED_FILTER) ||
            selectedMinImdbVotes != null
    val resultHydrationKey = remember(baseResults, results, selectedMinRating, selectedMinImdbVotes) {
        val candidates = if (requiresImdbFilterData) baseResults else results
        buildString {
            append(candidates.size)
            append('|')
            append(candidates.lastOrNull()?.tvSearchStableKey().orEmpty())
            append('|')
            append(candidates.take(TV_SEARCH_PREFETCH_RESULT_COUNT).joinToString("|") { it.tvSearchStableKey() })
        }
    }

    LaunchedEffect(resultHydrationKey, selectedMinRating, selectedMinImdbVotes) {
        if (selectedMinRating == TV_SEARCH_TOP_RATED_FILTER) return@LaunchedEffect
        if (results.isEmpty() && (!requiresImdbFilterData || baseResults.isEmpty())) return@LaunchedEffect
        var workingResults = if (requiresImdbFilterData) baseResults else results
        val cachedRatings = withContext(Dispatchers.IO) {
            ratingsEnricher.hydrateListFromCache(workingResults.take(TV_SEARCH_RATING_CACHE_COUNT))
        }
        if (cachedRatings.zip(workingResults).any { (cached, current) -> cached.ratings != current.ratings }) {
            val cachedByKey = cachedRatings.associateBy { it.tvSearchStableKey() }
            baseResults = baseResults.map { cachedByKey[it.tvSearchStableKey()] ?: it }
            workingResults = workingResults.map { cachedByKey[it.tvSearchStableKey()] ?: it }
            val filtered = workingResults.filterTvSearchItems(
                genreIds = selectedGenreIds,
                studioIds = selectedStudioIds,
                year = selectedYear,
                minRating = selectedMinRating,
                minImdbVotes = selectedMinImdbVotes,
                runtimeFilter = selectedRuntimeFilter,
                availabilityFilter = selectedAvailabilityFilter,
                sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
            )
            results = stableTvSearchProjection(results, filtered, TV_SEARCH_MAX_RESULTS)
        }
        val targets = workingResults
            .filter { it.tmdbId != null && (it.genres.isEmpty() || it.studios.isEmpty()) }
            .take(TV_SEARCH_DETAIL_HYDRATE_COUNT)
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
                    posterUrl = item.posterUrl?.takeIf { it.isNotBlank() }
                        ?: detail.posterUrl?.takeIf { it.isNotBlank() },
                    backdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() }
                        ?: detail.backdropUrl?.takeIf { it.isNotBlank() },
                    logoUrl = item.logoUrl?.takeIf { it.isNotBlank() }
                        ?: detail.logoUrl?.takeIf { it.isNotBlank() },
                )
            }.toMap()
            if (hydrated.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                baseResults = baseResults.map { hydrated[it.tvSearchStableKey()] ?: it }
                val filtered = baseResults.filterTvSearchItems(
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                    minImdbVotes = selectedMinImdbVotes,
                    runtimeFilter = selectedRuntimeFilter,
                    availabilityFilter = selectedAvailabilityFilter,
                    sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
                )
                results = stableTvSearchProjection(results, filtered, TV_SEARCH_MAX_RESULTS)
            }
        }
    }

    // Enrich only the newly focused card beyond the initial visible batch.
    // The debounce prevents fast D-pad navigation from producing requests,
    // and the result is merged in place so neither focus nor poster order moves.
    val focusedResultEnrichmentKey = remember(focusedResultKey, results) {
        focusedResultKey?.let { itemKey ->
            results.firstOrNull { it.tvSearchStableKey() == itemKey }?.let { item ->
                listOf(
                    itemKey,
                    item.tmdbId,
                    item.imdbId,
                    item.ratings?.imdbScore,
                    item.ratings?.rottenTomatoesScore,
                ).joinToString("|")
            }
        }
    }
    LaunchedEffect(focusedResultEnrichmentKey) {
        val itemKey = focusedResultKey ?: return@LaunchedEffect
        delay(320)
        val currentItem = results.firstOrNull { it.tvSearchStableKey() == itemKey }
            ?: return@LaunchedEffect
        if (!currentItem.needsTvSearchPrimaryRatingEnrichment()) return@LaunchedEffect
        val apiKey = withContext(Dispatchers.IO) {
            runCatching {
                secretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            }.getOrDefault(MdbListApi.DEFAULT_API_KEY)
        }
        val enriched = withContext(Dispatchers.IO) {
            ratingsEnricher.enrichList(
                items = listOf(currentItem),
                apiKey = apiKey,
                requirePrimaryProviders = true,
            ).firstOrNull()
        } ?: return@LaunchedEffect
        if (enriched.ratings == currentItem.ratings) return@LaunchedEffect
        baseResults = baseResults.map { if (it.tvSearchStableKey() == itemKey) enriched else it }
        results = results.map { if (it.tvSearchStableKey() == itemKey) enriched else it }
    }

    LaunchedEffect(resultHydrationKey, selectedMinRating, selectedMinImdbVotes) {
        if (selectedMinRating == TV_SEARCH_TOP_RATED_FILTER) return@LaunchedEffect
        if (results.isEmpty() && (!requiresImdbFilterData || baseResults.isEmpty())) return@LaunchedEffect
        val snapshot = (if (requiresImdbFilterData) baseResults else results).take(TV_SEARCH_RATING_ENRICH_COUNT)
        val needsRatingEnrichment = if (
            selectedMinRating == TV_SEARCH_TOP_RATED_FILTER || selectedMinImdbVotes != null
        ) {
            snapshot.any { item ->
                item.ratings?.imdbVotes == null ||
                    (selectedMinRating == TV_SEARCH_TOP_RATED_FILTER && item.ratings?.imdbScore == null)
            }
        } else {
            snapshot.needsTvSearchRatingEnrichment()
        }
        if (!needsRatingEnrichment) return@LaunchedEffect

        val apiKey = withContext(Dispatchers.IO) {
            runCatching {
                secretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            }.getOrDefault(MdbListApi.DEFAULT_API_KEY)
        }

        var current = snapshot
        repeat(3) { attempt ->
            val enriched = withContext(Dispatchers.IO) {
                ratingsEnricher.enrichList(
                    items = current,
                    apiKey = apiKey,
                    requirePrimaryProviders = true,
                )
            }
            if (tvSearchRatingsChanged(current, enriched)) {
                val enrichedByKey = current.zip(enriched).associate { (before, after) ->
                    before.tvSearchStableKey() to after
                }
                current = current.map { enrichedByKey[it.tvSearchStableKey()] ?: it }
                baseResults = baseResults.map { enrichedByKey[it.tvSearchStableKey()] ?: it }
                val filtered = baseResults.filterTvSearchItems(
                        genreIds = selectedGenreIds,
                        studioIds = selectedStudioIds,
                        year = selectedYear,
                        minRating = selectedMinRating,
                        minImdbVotes = selectedMinImdbVotes,
                        runtimeFilter = selectedRuntimeFilter,
                        availabilityFilter = selectedAvailabilityFilter,
                        sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
                    )
                results = stableTvSearchProjection(results, filtered, TV_SEARCH_MAX_RESULTS)
            }
            val remainingMs = ratingsEnricher.rateLimitRemainingMs()
            if (remainingMs <= 0L || attempt == 2) return@LaunchedEffect
            delay(remainingMs + 2_000L)
        }
    }

    BackHandler(enabled = showFilters) {
        showFilters = false
        focusResultsAfterClosingFilters = true
    }
    BackHandler(enabled = onClose != null && !showFilters) {
        if (focusedResultKey != null) {
            focusedResultKey = null
            runCatching { filterToggleRequester.requestFocus() }
            onContentFocused(filterToggleRequester)
        } else {
            onClose?.invoke()
        }
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

    LaunchedEffect(
        baseResults,
        selectedGenreIds,
        selectedStudioIds,
        selectedYear,
        selectedMinRating,
        selectedMinImdbVotes,
        selectedRuntimeFilter,
        selectedAvailabilityFilter,
        sourceAvailabilityByTmdbId,
    ) {
        val filtered = baseResults.filterTvSearchItems(
            genreIds = selectedGenreIds,
            studioIds = selectedStudioIds,
            year = selectedYear,
            minRating = selectedMinRating,
            minImdbVotes = selectedMinImdbVotes,
            runtimeFilter = selectedRuntimeFilter,
            availabilityFilter = selectedAvailabilityFilter,
            sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
        )
        results = if (selectedMinRating == TV_SEARCH_TOP_RATED_FILTER) {
            val visibleLimit = maxOf(
                TV_SEARCH_RESULT_PAGE_SIZE,
                ((results.size + TV_SEARCH_RESULT_PAGE_SIZE - 1) / TV_SEARCH_RESULT_PAGE_SIZE) *
                    TV_SEARCH_RESULT_PAGE_SIZE,
            ).coerceAtMost(TV_SEARCH_MAX_RESULTS)
            stableTvSearchProjection(results, filtered, visibleLimit)
        } else {
            stableTvSearchProjection(results, filtered, TV_SEARCH_MAX_RESULTS)
        }
    }

    LaunchedEffect(baseResults, selectedAvailabilityFilter) {
        if (selectedAvailabilityFilter != TvSearchAvailabilityFilter.READY || baseResults.isEmpty()) {
            return@LaunchedEffect
        }
        val lookupItems = baseResults
            .mapNotNull { item -> item.tmdbId?.let { it to item.type } }
            .distinctBy { it.first }
            .filterNot { (tmdbId, _) -> tmdbId in sourceAvailabilityByTmdbId }
            .take(80)
        if (lookupItems.isEmpty()) return@LaunchedEffect
        val records = withContext(Dispatchers.IO) {
            runCatching { availabilityAggregator.lookupBatch(lookupItems) }.getOrDefault(emptyMap())
        }
        if (records.isNotEmpty()) {
            sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId + records
        }
    }

    // Standard search
    LaunchedEffect(
        query,
        searchMode,
        filterType,
        selectedGenreIds,
        selectedStudioIds,
        selectedYear,
        selectedMinRating,
        selectedMinImdbVotes,
        selectedRuntimeFilter,
        selectedLanguageFilter,
        selectedAvailabilityFilter,
        browseRefreshNonce,
    ) {
        if (searchMode != SearchMode.STANDARD) return@LaunchedEffect
        aiResultTitle = null
        aiFallback = false
        val trimmedQuery = query.trim()
        val loadStateKey = tvSearchLoadStateKey(
            query = trimmedQuery,
            searchMode = SearchMode.STANDARD,
            filterType = filterType,
            selectedGenreIds = selectedGenreIds,
            selectedStudioIds = selectedStudioIds,
            selectedYear = selectedYear,
            selectedMinRating = selectedMinRating,
            selectedMinImdbVotes = selectedMinImdbVotes,
            selectedRuntimeFilter = selectedRuntimeFilter,
            selectedLanguageFilter = selectedLanguageFilter,
            selectedAvailabilityFilter = selectedAvailabilityFilter,
        )
        if (lastLoadedSearchStateKey == loadStateKey && (baseResults.isNotEmpty() || results.isNotEmpty())) {
            Log.d("TvSearchCache", "search_cache_restore loadKey=$loadStateKey results=${results.size}")
            loading = false
            error = null
            return@LaunchedEffect
        }
        Log.d("TvSearchCache", "search_cache_miss loadKey=$loadStateKey")
        pagingRetryBlockedResultKey = null
        loading = true
        error = null
        try {
            delay(if (trimmedQuery.length >= 2) 250 else 80)
            val isBrowseRequest = trimmedQuery.length < 2 || selectedMinRating == TV_SEARCH_TOP_RATED_FILTER
            if (isBrowseRequest && requiresImdbFilterData) {
                val apiKey = withContext(Dispatchers.IO) {
                    runCatching {
                        secretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                            ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                            ?: MdbListApi.DEFAULT_API_KEY
                    }.getOrDefault(MdbListApi.DEFAULT_API_KEY)
                }
                val batch = metadataRepo.loadVerifiedBrowseBatch(
                    ratingsEnricher = ratingsEnricher,
                    apiKey = apiKey,
                    existingCandidates = emptyList(),
                    existingResults = emptyList(),
                    startPage = 1,
                    targetResultCount = TV_SEARCH_VERIFIED_INITIAL_RESULT_COUNT,
                    filterType = filterType,
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                    minImdbVotes = selectedMinImdbVotes,
                    runtimeFilter = selectedRuntimeFilter,
                    languageFilter = selectedLanguageFilter,
                    availabilityFilter = selectedAvailabilityFilter,
                    regionCode = settingsState.regionCode,
                    sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
                )
                baseResults = batch.candidates
                results = batch.results
                nextSearchPage = batch.nextPage
                searchTotalPages = batch.totalPages
                searchCanLoadMore = batch.canLoadMore
            } else {
                val raw = if (trimmedQuery.length >= 2) {
                    val page = metadataRepo.searchMultiPaged(
                        tvSearchEffectiveQuery(trimmedQuery, selectedAvailabilityFilter),
                        1,
                        filterType,
                    )
                    nextSearchPage = page.page + 1
                    searchTotalPages = page.totalPages
                    searchCanLoadMore = page.page < page.totalPages &&
                        page.items.isNotEmpty() &&
                        page.items.size < TV_SEARCH_MAX_RESULTS
                    page.items
                } else {
                    val page = metadataRepo.loadTvSearchBrowsePage(
                        filterType = filterType,
                        genreIds = selectedGenreIds,
                        year = selectedYear,
                        minRating = selectedMinRating,
                        minImdbVotes = selectedMinImdbVotes,
                        runtimeFilter = selectedRuntimeFilter,
                        languageFilter = selectedLanguageFilter,
                        availabilityFilter = selectedAvailabilityFilter,
                        regionCode = settingsState.regionCode,
                        page = 1,
                    )
                    nextSearchPage = page.page + 1
                    searchTotalPages = page.totalPages
                    searchCanLoadMore = page.page < page.totalPages && page.items.isNotEmpty()
                    page.items
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
                    minImdbVotes = selectedMinImdbVotes,
                    runtimeFilter = selectedRuntimeFilter,
                    availabilityFilter = selectedAvailabilityFilter,
                    sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
                ).take(TV_SEARCH_MAX_RESULTS)
            }
            lastLoadedSearchStateKey = loadStateKey
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
        val loadStateKey = tvSearchLoadStateKey(
            query = query,
            searchMode = SearchMode.AI,
            filterType = filterType,
            selectedGenreIds = selectedGenreIds,
            selectedStudioIds = selectedStudioIds,
            selectedYear = selectedYear,
            selectedMinRating = selectedMinRating,
            selectedMinImdbVotes = selectedMinImdbVotes,
            selectedRuntimeFilter = selectedRuntimeFilter,
            selectedLanguageFilter = selectedLanguageFilter,
            selectedAvailabilityFilter = selectedAvailabilityFilter,
        )
        if (lastLoadedSearchStateKey == loadStateKey && (baseResults.isNotEmpty() || results.isNotEmpty())) {
            Log.d("TvSearchCache", "search_cache_restore loadKey=$loadStateKey results=${results.size}")
            loading = false
            error = null
            return@LaunchedEffect
        }
        if (query.length < 2 || !hasAiKey) {
            // AI mode is only a search interpretation mode. Toggling it on
            // without a real query must not remove the browse/results surface,
            // because that detaches poster focus targets from the TV focus graph.
            loading = false
            error = null
            aiResultTitle = null
            return@LaunchedEffect
        }
        Log.d("TvSearchCache", "search_cache_miss loadKey=$loadStateKey")
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
                    ).items.take(TV_SEARCH_MAX_RESULTS)
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
                    ).items.take(TV_SEARCH_MAX_RESULTS)
                }
            }

            // If AI resolution returned nothing, fall back to standard search
            if (resolvedItems.isEmpty()) {
                aiFallback = true
                aiResultTitle = null
                val fallbackPage = metadataRepo.searchMultiPaged(
                    tvSearchEffectiveQuery(query, selectedAvailabilityFilter),
                    1,
                    filterType,
                )
                nextSearchPage = fallbackPage.page + 1
                searchTotalPages = fallbackPage.totalPages
                searchCanLoadMore = fallbackPage.page < fallbackPage.totalPages &&
                    fallbackPage.items.isNotEmpty() &&
                    fallbackPage.items.size < TV_SEARCH_MAX_RESULTS
                baseResults = fallbackPage.items
                results = baseResults.filterTvSearchItems(
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                    minImdbVotes = selectedMinImdbVotes,
                    runtimeFilter = selectedRuntimeFilter,
                    availabilityFilter = selectedAvailabilityFilter,
                    sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
                )
            } else {
                baseResults = resolvedItems.take(TV_SEARCH_MAX_RESULTS)
                results = baseResults.filterTvSearchItems(
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                    minImdbVotes = selectedMinImdbVotes,
                    runtimeFilter = selectedRuntimeFilter,
                    availabilityFilter = selectedAvailabilityFilter,
                    sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
                )
            }
            lastLoadedSearchStateKey = loadStateKey
        } catch (_: Throwable) {
            // Fallback to standard search
            aiFallback = true
            aiResultTitle = null
            try {
                val fallbackPage = metadataRepo.searchMultiPaged(
                    tvSearchEffectiveQuery(query, selectedAvailabilityFilter),
                    1,
                    filterType,
                )
                nextSearchPage = fallbackPage.page + 1
                searchTotalPages = fallbackPage.totalPages
                searchCanLoadMore = fallbackPage.page < fallbackPage.totalPages &&
                    fallbackPage.items.isNotEmpty() &&
                    fallbackPage.items.size < TV_SEARCH_MAX_RESULTS
                baseResults = fallbackPage.items
                results = baseResults.filterTvSearchItems(
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                    minImdbVotes = selectedMinImdbVotes,
                    runtimeFilter = selectedRuntimeFilter,
                    availabilityFilter = selectedAvailabilityFilter,
                    sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
                )
            lastLoadedSearchStateKey = loadStateKey
        } catch (t: Throwable) {
            Log.w("TvSearchInitial", "search_initial_failed loadKey=$loadStateKey", t)
            nextSearchPage = 2
            searchTotalPages = 1
            searchCanLoadMore = false
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
        (if (selectedMinImdbVotes != null) 1 else 0) +
        (if (selectedRuntimeFilter != null) 1 else 0) +
        (if (selectedLanguageFilter != null) 1 else 0) +
        (if (selectedAvailabilityFilter != null) 1 else 0) +
        (if (filterType != null) 1 else 0)

    LaunchedEffect(
        selectedResultKey,
        lastVisibleResultIndex,
        results.size,
        baseResults.size,
        searchCanLoadMore,
        loading,
        loadingMoreResults,
        loadMoreRequestPending,
        searchMode,
        query,
        filterType,
        selectedGenreIds,
        selectedStudioIds,
        selectedYear,
        selectedMinRating,
        selectedMinImdbVotes,
        selectedRuntimeFilter,
        selectedLanguageFilter,
        selectedAvailabilityFilter,
        density,
    ) {
        if (searchMode != SearchMode.STANDARD) return@LaunchedEffect
        if (loading || loadingMoreResults || loadMoreRequestPending || !searchCanLoadMore) return@LaunchedEffect
        if (results.size >= TV_SEARCH_MAX_RESULTS || baseResults.size >= TV_SEARCH_MAX_CANDIDATES) return@LaunchedEffect
        val trimmedQuery = query.trim()
        val isTopRated = selectedMinRating == TV_SEARCH_TOP_RATED_FILTER
        val isBrowseRequest = trimmedQuery.length < 2 || isTopRated
        val focusedIndex = selectedResultKey?.let { key ->
            results.indexOfFirst { it.tvSearchStableKey() == key }
        } ?: -1
        val loadAheadRows = if (isBrowseRequest) TV_SEARCH_TOP_RATED_LOAD_AHEAD_ROWS else TV_SEARCH_LOAD_AHEAD_ROWS
        val furthestVisibleIndex = maxOf(focusedIndex, lastVisibleResultIndex)
        val nearLoadedEnd = furthestVisibleIndex >= 0 &&
            furthestVisibleIndex >= results.size - (density.columns * loadAheadRows)
        val needsFirstBrowsePage = isBrowseRequest && results.size < TV_SEARCH_RESULT_PAGE_SIZE
        val filteredToEmpty = results.isEmpty() && activeFilterCount > 0 && baseResults.isNotEmpty()
        if (!nearLoadedEnd && !needsFirstBrowsePage && !filteredToEmpty) return@LaunchedEffect
        if (pagingRetryBlockedResultKey != null && pagingRetryBlockedResultKey == selectedResultKey) {
            return@LaunchedEffect
        }

        loadMoreRequestPending = true
        loadMoreRequestNonce += 1
    }

    LaunchedEffect(
        loadMoreRequestNonce,
        searchMode,
        query,
        filterType,
        selectedGenreIds,
        selectedStudioIds,
        selectedYear,
        selectedMinRating,
        selectedMinImdbVotes,
        selectedRuntimeFilter,
        selectedLanguageFilter,
        selectedAvailabilityFilter,
    ) {
        if (loadMoreRequestNonce <= handledLoadMoreRequestNonce) return@LaunchedEffect
        handledLoadMoreRequestNonce = loadMoreRequestNonce
        if (searchMode != SearchMode.STANDARD || loading) {
            loadMoreRequestPending = false
            return@LaunchedEffect
        }
        if (results.size >= TV_SEARCH_MAX_RESULTS || baseResults.size >= TV_SEARCH_MAX_CANDIDATES) {
            loadMoreRequestPending = false
            return@LaunchedEffect
        }
        val trimmedQuery = query.trim()
        val isTopRated = selectedMinRating == TV_SEARCH_TOP_RATED_FILTER
        val isBrowseRequest = trimmedQuery.length < 2 || isTopRated
        val usesVerifiedBrowse = isBrowseRequest && requiresImdbFilterData
        loadingMoreResults = true
        try {
            if (usesVerifiedBrowse) {
                val apiKey = withContext(Dispatchers.IO) {
                    runCatching {
                        secretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                            ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                            ?: MdbListApi.DEFAULT_API_KEY
                    }.getOrDefault(MdbListApi.DEFAULT_API_KEY)
                }
                val targetSize = (results.size + TV_SEARCH_VERIFIED_RESULT_INCREMENT)
                    .coerceAtMost(TV_SEARCH_MAX_RESULTS)
                val batch = metadataRepo.loadVerifiedBrowseBatch(
                    ratingsEnricher = ratingsEnricher,
                    apiKey = apiKey,
                    existingCandidates = baseResults,
                    existingResults = results,
                    startPage = nextSearchPage,
                    targetResultCount = targetSize,
                    filterType = filterType,
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                    minImdbVotes = selectedMinImdbVotes,
                    runtimeFilter = selectedRuntimeFilter,
                    languageFilter = selectedLanguageFilter,
                    availabilityFilter = selectedAvailabilityFilter,
                    regionCode = settingsState.regionCode,
                    sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
                )
                baseResults = batch.candidates
                results = batch.results
                nextSearchPage = batch.nextPage
                searchTotalPages = batch.totalPages
                searchCanLoadMore = batch.canLoadMore
            } else if (isBrowseRequest) {
                val page = withContext(Dispatchers.IO) {
                    runCatching {
                        metadataRepo.loadTvSearchBrowsePage(
                            filterType = filterType,
                            genreIds = selectedGenreIds,
                            year = selectedYear,
                            minRating = selectedMinRating,
                            minImdbVotes = selectedMinImdbVotes,
                            runtimeFilter = selectedRuntimeFilter,
                            languageFilter = selectedLanguageFilter,
                            availabilityFilter = selectedAvailabilityFilter,
                            regionCode = settingsState.regionCode,
                            page = nextSearchPage,
                        )
                    }.getOrElse {
                        delay(350)
                        runCatching {
                            metadataRepo.loadTvSearchBrowsePage(
                                filterType = filterType,
                                genreIds = selectedGenreIds,
                                year = selectedYear,
                                minRating = selectedMinRating,
                                minImdbVotes = selectedMinImdbVotes,
                                runtimeFilter = selectedRuntimeFilter,
                                languageFilter = selectedLanguageFilter,
                                availabilityFilter = selectedAvailabilityFilter,
                                regionCode = settingsState.regionCode,
                                page = nextSearchPage,
                            )
                        }.getOrElse {
                            delay(700)
                            metadataRepo.loadTvSearchBrowsePage(
                                filterType = filterType,
                                genreIds = selectedGenreIds,
                                year = selectedYear,
                                minRating = selectedMinRating,
                                minImdbVotes = selectedMinImdbVotes,
                                runtimeFilter = selectedRuntimeFilter,
                                languageFilter = selectedLanguageFilter,
                                availabilityFilter = selectedAvailabilityFilter,
                                regionCode = settingsState.regionCode,
                                page = nextSearchPage,
                            )
                        }
                    }
                }
                val merged = (baseResults + page.items)
                    .dedupeTvSearchResults()
                    .take(TV_SEARCH_MAX_CANDIDATES)
                val filtered = merged.filterTvSearchItems(
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                    minImdbVotes = selectedMinImdbVotes,
                    runtimeFilter = selectedRuntimeFilter,
                    availabilityFilter = selectedAvailabilityFilter,
                    sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
                )
                baseResults = merged
                results = stableTvSearchProjection(results, filtered, TV_SEARCH_MAX_RESULTS)
                nextSearchPage = page.page + 1
                searchTotalPages = page.totalPages
                searchCanLoadMore = page.page < page.totalPages &&
                    page.items.isNotEmpty() &&
                    merged.size < TV_SEARCH_MAX_CANDIDATES &&
                    results.size < TV_SEARCH_MAX_RESULTS
            } else {
                val page = withContext(Dispatchers.IO) {
                    runCatching {
                        metadataRepo.searchMultiPaged(
                            tvSearchEffectiveQuery(trimmedQuery, selectedAvailabilityFilter),
                            nextSearchPage,
                            filterType,
                        )
                    }.getOrElse {
                        delay(350)
                        runCatching {
                            metadataRepo.searchMultiPaged(
                                tvSearchEffectiveQuery(trimmedQuery, selectedAvailabilityFilter),
                                nextSearchPage,
                                filterType,
                            )
                        }.getOrElse {
                            delay(700)
                            metadataRepo.searchMultiPaged(
                                tvSearchEffectiveQuery(trimmedQuery, selectedAvailabilityFilter),
                                nextSearchPage,
                                filterType,
                            )
                        }
                    }
                }
                val merged = (baseResults + page.items)
                    .dedupeTvSearchResults()
                    .take(TV_SEARCH_MAX_CANDIDATES)
                baseResults = merged
                val filtered = merged.filterTvSearchItems(
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                    minImdbVotes = selectedMinImdbVotes,
                    runtimeFilter = selectedRuntimeFilter,
                    availabilityFilter = selectedAvailabilityFilter,
                    sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
                )
                results = stableTvSearchProjection(results, filtered, TV_SEARCH_MAX_RESULTS)
                nextSearchPage = page.page + 1
                searchTotalPages = page.totalPages
                searchCanLoadMore = page.page < page.totalPages &&
                    page.items.isNotEmpty() &&
                    merged.size < TV_SEARCH_MAX_CANDIDATES &&
                    results.size < TV_SEARCH_MAX_RESULTS
            }
            pagingRetryBlockedResultKey = null
        } catch (t: Throwable) {
            Log.w("TvSearchPaging", "search_next_page_failed page=$nextSearchPage", t)
            // Keep the server's has-more state. Moving focus to another item
            // retries this page instead of turning one transient failure into
            // a permanent 40-result ceiling.
            pagingRetryBlockedResultKey = selectedResultKey
        } finally {
            loadingMoreResults = false
            loadMoreRequestPending = false
        }
    }

    val selectedBackdrop = selectedResult?.backdropUrl?.takeIf { it.isNotBlank() }
        ?: selectedResult?.posterUrl?.takeIf { it.isNotBlank() }

    LaunchedEffect(resultHydrationKey, selectedBackdrop) {
        if (results.isNotEmpty()) {
            TvImagePrefetcher.prefetchMediaItems(
                context = context,
                screenName = "tv_search",
                items = results.take(TV_SEARCH_PREFETCH_RESULT_COUNT),
                maxImages = TV_SEARCH_PREFETCH_RESULT_COUNT,
                includeHeroCandidates = false,
            )
        }
    }

    LaunchedEffect(
        query,
        searchMode,
        filterType,
        selectedGenreIds,
        selectedStudioIds,
        selectedYear,
        selectedMinRating,
        selectedMinImdbVotes,
        selectedRuntimeFilter,
        selectedLanguageFilter,
        selectedAvailabilityFilter,
        selectedSort,
        filtersVisible,
        density,
        baseResults,
        results,
        selectedResultKey,
        aiResultTitle,
        aiFallback,
        lastLoadedSearchStateKey,
        nextSearchPage,
        searchTotalPages,
        searchCanLoadMore,
        loading,
        error,
    ) {
        if (loading || error != null) return@LaunchedEffect
        if (baseResults.isEmpty() && results.isEmpty()) return@LaunchedEffect
        TvScreenCache.put(
            routeCacheKey,
            TvSearchRouteCacheState(
                query = query,
                searchMode = searchMode,
                filterType = filterType,
                selectedGenreIds = selectedGenreIds,
                selectedStudioIds = selectedStudioIds,
                selectedYear = selectedYear,
                selectedMinRating = selectedMinRating,
                selectedMinImdbVotes = selectedMinImdbVotes,
                selectedRuntimeFilter = selectedRuntimeFilter,
                selectedLanguageFilter = selectedLanguageFilter,
                selectedAvailabilityFilter = selectedAvailabilityFilter,
                selectedSort = selectedSort,
                filtersVisible = filtersVisible,
                density = density,
                baseResults = baseResults,
                results = results,
                selectedMediaKey = selectedResultKey,
                aiResultTitle = aiResultTitle,
                aiFallback = aiFallback,
                loadStateKey = lastLoadedSearchStateKey,
                nextSearchPage = nextSearchPage,
                searchTotalPages = searchTotalPages,
                searchCanLoadMore = searchCanLoadMore,
                storedAtMs = System.currentTimeMillis(),
            ),
        )
        Log.d("TvSearchCache", "search_cache_store queryLength=${query.length} results=${results.size}")
    }

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
                .padding(
                    start = TV_PAGE_CONTENT_GUTTER,
                    top = TV_PAGE_TOP_GUTTER,
                    end = 0.dp,
                    bottom = TV_PAGE_BOTTOM_GUTTER,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvSearchHeader(
                query = query,
                onQueryChange = { value ->
                    query = value
                    when {
                        value.trim().equals("top rated", ignoreCase = true) -> {
                            selectedMinRating = TV_SEARCH_TOP_RATED_FILTER
                        }
                        selectedMinRating == TV_SEARCH_TOP_RATED_FILTER && value.isNotBlank() -> {
                            selectedMinRating = null
                        }
                    }
                },
                searchMode = searchMode,
                hasAiKey = hasAiKey,
                onToggleAi = {
                    if (hasAiKey) {
                        restoreAiFocusAfterToggle = true
                        searchMode = if (searchMode == SearchMode.AI) SearchMode.STANDARD else SearchMode.AI
                    }
                },
                inputFocusRequester = inputFocusRequester,
                clearFocusRequester = clearSearchRequester,
                aiFocusRequester = aiModeRequester,
                railFocusRequester = railFocusRequester,
                downFocusRequester = when {
                    showFilters -> closeFiltersRequester
                    filtersVisible -> allTypeRequester
                    else -> filterToggleRequester
                },
                onMoveDown = ::requestSearchHeaderDownFocus,
                onClearSearch = {
                    if (query.trim().equals("top rated", ignoreCase = true) &&
                        selectedMinRating == TV_SEARCH_TOP_RATED_FILTER
                    ) {
                        selectedMinRating = null
                    }
                    query = ""
                    focusInputAfterClear = true
                },
                onContentFocused = onContentFocused,
            )

            TvSearchRefinementArea(
                filterType = filterType,
                onFilterTypeChange = {
                    filterType = it
                    selectedStudioIds = emptySet()
                    lastLoadedSearchStateKey = null
                    browseRefreshNonce += 1
                },
                selectedGenreIds = selectedGenreIds,
                selectedYear = selectedYear,
                selectedMinRating = selectedMinRating,
                selectedSort = selectedSort,
                onSelectSort = {
                    selectedSort = it
                    sortApplyNonce += 1
                },
                onQuickDiscovery = { quick ->
                    when (quick) {
                        "trending" -> {
                            query = ""
                            selectedGenreIds = emptySet()
                            selectedStudioIds = emptySet()
                            selectedYear = null
                            selectedMinRating = null
                            lastLoadedSearchStateKey = null
                            browseRefreshNonce += 1
                        }
                        "top_rated" -> {
                            query = ""
                            selectedStudioIds = emptySet()
                            selectedYear = null
                            selectedMinRating = if (selectedMinRating == TV_SEARCH_TOP_RATED_FILTER) {
                                null
                            } else {
                                TV_SEARCH_TOP_RATED_FILTER
                            }
                            lastLoadedSearchStateKey = null
                            browseRefreshNonce += 1
                        }
                        "new" -> {
                            query = ""
                            selectedStudioIds = emptySet()
                            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                            selectedYear = if (selectedYear == currentYear) null else currentYear
                            lastLoadedSearchStateKey = null
                            browseRefreshNonce += 1
                        }
                        "family" -> {
                            query = ""
                            selectedStudioIds = emptySet()
                            selectedGenreIds = if (10751 in selectedGenreIds) emptySet() else setOf(10751)
                            lastLoadedSearchStateKey = null
                            browseRefreshNonce += 1
                        }
                        "action" -> {
                            query = ""
                            selectedStudioIds = emptySet()
                            val actionGenre = if (filterType == "tv") 10759 else 28
                            selectedGenreIds = if (actionGenre in selectedGenreIds) emptySet() else setOf(actionGenre)
                            lastLoadedSearchStateKey = null
                            browseRefreshNonce += 1
                        }
                        "comedy" -> {
                            query = ""
                            selectedStudioIds = emptySet()
                            selectedGenreIds = if (35 in selectedGenreIds) emptySet() else setOf(35)
                            lastLoadedSearchStateKey = null
                            browseRefreshNonce += 1
                        }
                    }
                },
                filtersVisible = filtersVisible,
                advancedFiltersOpen = showFilters,
                activeFilterCount = activeFilterCount,
                onToggleFilterVisibility = {
                    if (filtersVisible) {
                        focusCompactFiltersAfterHide = true
                    } else {
                        focusFirstFilterAfterShow = true
                    }
                    filtersVisible = !filtersVisible
                },
                onToggleAdvancedFilters = { showFilters = !showFilters },
                allTypeRequester = allTypeRequester,
                movieTypeRequester = movieTypeRequester,
                tvTypeRequester = tvTypeRequester,
                sortRequester = sortRequester,
                filterToggleRequester = filterToggleRequester,
                advancedFiltersRequester = advancedFiltersRequester,
                inputFocusRequester = inputFocusRequester,
                bodyTopRequester = activeDensityRequester,
                railFocusRequester = railFocusRequester,
                onMoveDown = if (results.isNotEmpty()) {
                    { requestSearchBodyFocus() }
                } else {
                    null
                },
                onFilterFocused = {
                    lastVisibleFilterRequester = it
                    focusedResultKey = null
                },
                onContentFocused = onContentFocused,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                val previewWidth = when (density) {
                    TvSearchDensity.SHOWCASE -> 386.dp
                    TvSearchDensity.RELAXED -> 366.dp
                    TvSearchDensity.BALANCED -> 336.dp
                    TvSearchDensity.COMPACT -> 314.dp
                }
                TvSearchSelectedPreviewV2(
                    item = selectedResult,
                    ratingPrefs = settingsState.ratingPrefs.tvExternalCardRatingPrefs(),
                    resultCount = results.size,
                    modifier = Modifier
                        .width(previewWidth)
                        .fillMaxHeight(),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val resultTitle = when {
                        loading -> "Loading discovery"
                        error != null -> "Search unavailable"
                        results.isEmpty() && activeFilterCount > 0 -> "No matches for these filters"
                        results.isEmpty() -> "Explore something new"
                        aiResultTitle != null && searchMode == SearchMode.AI -> stringResource(R.string.tv_search_ai_label, aiResultTitle!!)
                        aiFallback -> stringResource(R.string.tv_search_ai_fallback)
                        else -> "Top matches · ${results.size} results"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = resultTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (error != null) Ruby.copy(alpha = 0.82f) else Snow,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "View",
                            style = MaterialTheme.typography.labelMedium,
                            color = Silver.copy(alpha = 0.82f),
                            maxLines = 1,
                        )
                        TvSearchDensityControl(
                            density = density,
                            onDensityChange = { density = it },
                            densityRequesters = densityRequesters,
                            upRequester = if (filtersVisible) advancedFiltersRequester else filterToggleRequester,
                            downRequester = firstResultRequester.takeIf { results.isNotEmpty() }
                                ?: activeDensityRequester,
                            onMoveDown = { requestSearchBodyFocus() },
                            onContentFocused = onContentFocused,
                        )
                    }
                    when {
                        loading -> TvSearchLoadingSkeletonGrid(columns = density.columns)
                        error != null -> TvSearchEmptyState(
                            title = error.orEmpty(),
                            subtitle = "Try again or broaden the search.",
                            quickTerms = listOf("Popular", "Movies", "TV Shows"),
                            onQuickTerm = { term -> query = term },
                        )
                        results.isEmpty() -> TvSearchExploreState(
                            title = if (activeFilterCount > 0) "No matches for these filters" else "Start typing to search",
                            subtitle = if (activeFilterCount > 0) "Clear a filter or switch back to All." else "Or pick a quick filter.",
                            onQuickTerm = { term -> query = term },
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(density.columns),
                            state = resultsGridState,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp, start = 2.dp, end = 4.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                results,
                                key = { _, item -> item.tvSearchStableKey() },
                            ) { index, item ->
                                val itemKey = item.tvSearchStableKey()
                                val activeRequester = if (index == 0) {
                                    firstResultRequester.also { resultRequesters[itemKey] = it }
                                } else {
                                    resultRequesters.getOrPut(itemKey) { FocusRequester() }
                                }
                                val focusTarget = remember(screenId, itemKey, index, density.columns) {
                                    TvFocusTargetId(
                                        screenId = screenId,
                                        rowKey = "results",
                                        itemKey = itemKey,
                                        rowIndex = index / density.columns,
                                        itemIndex = index,
                                        targetType = "result",
                                    )
                                }
                                val registeredRequester = rememberRegisteredTvFocusRequester(
                                    controller = focusRestoreController,
                                    target = focusTarget,
                                    externalRequester = activeRequester,
                                )
                                val isLastResultRow = index / density.columns == results.lastIndex / density.columns
                                val isRightEdge = index % density.columns == density.columns - 1 || index == results.lastIndex
                                TvSearchResultCard(
                                    item = item,
                                    density = density,
                                    modifier = Modifier
                                        .focusRequester(registeredRequester)
                                        .aspectRatio(2f / 3f)
                                        .onPreviewKeyEvent { event ->
                                            when {
                                                isLastResultRow && event.key == Key.DirectionDown -> {
                                                    if (event.type == KeyEventType.KeyDown &&
                                                        !loading &&
                                                        !loadingMoreResults &&
                                                        !loadMoreRequestPending
                                                    ) {
                                                        pagingRetryBlockedResultKey = null
                                                        loadMoreRequestPending = true
                                                        loadMoreRequestNonce += 1
                                                    }
                                                    true
                                                }
                                                isRightEdge && event.key == Key.DirectionRight -> {
                                                    if (event.type == KeyEventType.KeyDown &&
                                                        index == results.lastIndex &&
                                                        !loading &&
                                                        !loadingMoreResults &&
                                                        !loadMoreRequestPending
                                                    ) {
                                                        pagingRetryBlockedResultKey = null
                                                        loadMoreRequestPending = true
                                                        loadMoreRequestNonce += 1
                                                    }
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                        .focusProperties {
                                            if (index % density.columns == 0) {
                                                left = railFocusRequester
                                            }
                                            if (index < density.columns) {
                                                up = activeDensityRequester
                                            }
                                            if (isLastResultRow) {
                                                down = registeredRequester
                                            }
                                            if (isRightEdge) {
                                                right = registeredRequester
                                            }
                                        },
                                    onFocused = {
                                        focusRestoreController.markFocused(focusTarget)
                                        focusedResultKey = itemKey
                                        selectedResult = item
                                        selectedResultKey = itemKey
                                        onContentFocused(registeredRequester)
                                    },
                                    onClick = { onMediaClick(item) },
                                )
                            }
                            if (loadingMoreResults) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 18.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = Amber,
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Loading more",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Silver,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showFilters) {
            Popup(
                alignment = Alignment.TopStart,
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false,
                ),
                onDismissRequest = {
                    lastVisibleFilterRequester = advancedFiltersRequester
                    showFilters = false
                    focusResultsAfterClosingFilters = true
                },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Obsidian.copy(alpha = 0.42f)),
                ) {
                    TvSearchAdvancedFilterDrawer(
                availableGenres = availableGenres,
                availableYears = availableYears,
                selectedGenreIds = selectedGenreIds,
                selectedStudioIds = selectedStudioIds,
                selectedYear = selectedYear,
                selectedMinRating = selectedMinRating,
                selectedMinImdbVotes = selectedMinImdbVotes,
                selectedRuntimeFilter = selectedRuntimeFilter,
                selectedLanguageFilter = selectedLanguageFilter,
                selectedAvailabilityFilter = selectedAvailabilityFilter,
                activeFilterCount = activeFilterCount,
                closeFiltersRequester = closeFiltersRequester,
                clearFiltersRequester = clearFiltersRequester,
                firstFilterRequester = firstFilterRequester,
                onToggleGenre = { id ->
                    selectedGenreIds = if (id in selectedGenreIds) selectedGenreIds - id else selectedGenreIds + id
                },
                onToggleStudio = { id ->
                    selectedStudioIds = if (id in selectedStudioIds) selectedStudioIds - id else selectedStudioIds + id
                },
                onSelectYear = { year -> selectedYear = if (selectedYear == year) null else year },
                onSelectRating = { rating -> selectedMinRating = if (selectedMinRating == rating) null else rating },
                onSelectMinImdbVotes = { votes -> selectedMinImdbVotes = votes },
                onSelectRuntime = { filter ->
                    selectedRuntimeFilter = if (selectedRuntimeFilter == filter) null else filter
                    lastLoadedSearchStateKey = null
                    browseRefreshNonce += 1
                },
                onSelectLanguage = { filter ->
                    selectedLanguageFilter = if (selectedLanguageFilter == filter) null else filter
                    lastLoadedSearchStateKey = null
                    browseRefreshNonce += 1
                },
                onSelectAvailability = { filter ->
                    selectedAvailabilityFilter = if (selectedAvailabilityFilter == filter) null else filter
                    lastLoadedSearchStateKey = null
                    browseRefreshNonce += 1
                },
                onClearAll = {
                    selectedGenreIds = emptySet()
                    selectedStudioIds = emptySet()
                    selectedYear = null
                    selectedMinRating = null
                    selectedMinImdbVotes = null
                    selectedRuntimeFilter = null
                    selectedLanguageFilter = null
                    selectedAvailabilityFilter = null
                    selectedSort = TvSearchSort.RELEVANCE
                    filterType = null
                    searchMode = SearchMode.STANDARD
                    lastLoadedSearchStateKey = null
                    browseRefreshNonce += 1
                },
                onClose = {
                    lastVisibleFilterRequester = advancedFiltersRequester
                    showFilters = false
                    focusResultsAfterClosingFilters = true
                },
                onContentFocused = { requester ->
                    onContentFocused(requester)
                },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = TV_PAGE_CONTENT_GUTTER, top = 148.dp),
                    )
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
                        onClick = { /* disabled â€” no API key */ },
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
                        items(listOf("Recently added", "Anime", "New episodes")) { term ->
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
private fun TvSearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    searchMode: SearchMode,
    hasAiKey: Boolean,
    onToggleAi: () -> Unit,
    inputFocusRequester: FocusRequester,
    clearFocusRequester: FocusRequester,
    aiFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onMoveDown: () -> Boolean,
    onClearSearch: () -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 960.dp)
                .height(54.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Charcoal.copy(alpha = 0.78f),
                            Gunmetal.copy(alpha = 0.66f),
                            Charcoal.copy(alpha = 0.72f),
                        ),
                    ),
                )
                .border(1.dp, Snow.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
                .padding(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvSearchCompactInput(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Search movies, shows, channels",
                onMoveDown = onMoveDown,
                restingFocusRequester = inputFocusRequester,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(inputFocusRequester)
                    .focusProperties {
                        left = railFocusRequester
                        right = clearFocusRequester
                        down = downFocusRequester
                    }
                    .onFocusChanged { if (it.isFocused) onContentFocused(inputFocusRequester) },
            )
            TvSearchHeaderAction(
                text = "Clear search",
                active = query.isNotBlank(),
                modifier = Modifier
                    .focusRequester(clearFocusRequester)
                    .focusProperties {
                        left = inputFocusRequester
                        right = aiFocusRequester
                        down = downFocusRequester
                    },
                onFocused = { onContentFocused(clearFocusRequester) },
                onMoveDown = onMoveDown,
                onClick = onClearSearch,
            )
            TvSearchAiToggle(
                active = searchMode == SearchMode.AI,
                enabled = hasAiKey,
                modifier = Modifier
                    .focusRequester(aiFocusRequester)
                    .focusProperties {
                        left = clearFocusRequester
                        right = aiFocusRequester
                        down = downFocusRequester
                    },
                onFocused = { onContentFocused(aiFocusRequester) },
                onMoveDown = onMoveDown,
                onClick = onToggleAi,
            )
        }
    }
}

private enum class TvSearchRuntimeFilter(val label: String, val minMinutes: Int?, val maxMinutes: Int?) {
    UNDER_90("Under 90m", null, 89),
    BETWEEN_90_120("90-120m", 90, 120),
    OVER_120("2h+", 121, null),
}

private enum class TvSearchLanguageFilter(val label: String, val code: String) {
    ENGLISH("English", "en"),
    GERMAN("German", "de"),
    SPANISH("Spanish", "es"),
    FRENCH("French", "fr"),
    JAPANESE("Japanese", "ja"),
    KOREAN("Korean", "ko"),
}

private enum class TvSearchAvailabilityFilter(val label: String) {
    READY("Ready"),
    STREAMING("Streaming"),
}

private const val TV_SEARCH_ROUTE_CACHE_KEY = "tv_search_route_cache_v3"
private const val TV_SEARCH_ROUTE_CACHE_TTL_MS = 30 * 60 * 1000L

private data class TvSearchRouteCacheState(
    val query: String,
    val searchMode: SearchMode,
    val filterType: String?,
    val selectedGenreIds: Set<Int>,
    val selectedStudioIds: Set<Int>,
    val selectedYear: Int?,
    val selectedMinRating: Double?,
    val selectedMinImdbVotes: Int?,
    val selectedRuntimeFilter: TvSearchRuntimeFilter?,
    val selectedLanguageFilter: TvSearchLanguageFilter?,
    val selectedAvailabilityFilter: TvSearchAvailabilityFilter?,
    val selectedSort: TvSearchSort,
    val filtersVisible: Boolean,
    val density: TvSearchDensity,
    val baseResults: List<MediaItem>,
    val results: List<MediaItem>,
    val selectedMediaKey: String?,
    val aiResultTitle: String?,
    val aiFallback: Boolean,
    val loadStateKey: String?,
    val nextSearchPage: Int,
    val searchTotalPages: Int,
    val searchCanLoadMore: Boolean,
    val storedAtMs: Long,
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - storedAtMs <= TV_SEARCH_ROUTE_CACHE_TTL_MS
}

@Composable
private fun TvSearchCompactInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onMoveDown: () -> Boolean,
    restingFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val textFieldRequester = remember { FocusRequester() }
    val focusScope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    val iconTint by animateColorAsState(
        targetValue = if (focused || editMode) Amber else Silver.copy(alpha = 0.70f),
        label = "searchInputIconTint",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            editMode -> AmberLight
            focused -> Amber.copy(alpha = 0.92f)
            else -> Color.Transparent
        },
        label = "searchInputBorder",
    )
    val fillColor by animateColorAsState(
        targetValue = when {
            editMode -> Charcoal.copy(alpha = 0.38f)
            focused -> Gunmetal.copy(alpha = 0.44f)
            else -> Color.Transparent
        },
        label = "searchInputFill",
    )

    fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    LaunchedEffect(editMode) {
        if (editMode) {
            runCatching { textFieldRequester.requestFocus() }
            delay(50)
            showKeyboard()
        }
    }

    fun leaveEditMode(restoreRestingFocus: Boolean = false) {
        if (editMode) {
            editMode = false
            hideKeyboard()
            if (restoreRestingFocus) {
                focusScope.launch {
                    kotlinx.coroutines.yield()
                    runCatching { restingFocusRequester.requestFocus() }
                }
            }
        }
    }

    fun handleKey(eventKey: Key): Boolean =
        when (eventKey) {
            Key.DirectionDown -> {
                leaveEditMode()
                onMoveDown()
                true
            }

            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (!editMode) {
                    editMode = true
                    true
                } else {
                    false
                }
            }

            Key.Back -> {
                if (editMode) {
                    leaveEditMode(restoreRestingFocus = true)
                    true
                } else {
                    false
                }
            }

            else -> false
        }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(fillColor)
            .border(if (focused || editMode) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(18.dp))
            .onFocusChanged {
                focused = it.isFocused || it.hasFocus
                if (!it.hasFocus && editMode) {
                    leaveEditMode()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                handleKey(event.key)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { editMode = true },
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { if (editMode) onValueChange(it) },
            readOnly = !editMode,
            singleLine = true,
            cursorBrush = SolidColor(Amber),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Snow),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(textFieldRequester)
                .focusProperties { canFocus = editMode }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    handleKey(event.key)
                },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp),
                    )
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Silver.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                }
            },
        )
    }
}

@Composable
private fun TvSearchHeaderAction(
    text: String,
    active: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onMoveDown: () -> Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    focused -> Amber.copy(alpha = 0.22f)
                    active -> Snow.copy(alpha = 0.09f)
                    else -> Snow.copy(alpha = 0.04f)
                },
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) AmberLight else Snow.copy(alpha = if (active) 0.16f else 0.08f),
                shape = RoundedCornerShape(14.dp),
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown -> {
                        onMoveDown()
                        true
                    }
                    event.key == Key.DirectionDown && event.type == KeyEventType.KeyUp -> true
                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                focused -> AmberLight
                active -> Snow
                else -> Silver.copy(alpha = 0.48f)
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun TvSearchAiToggle(
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onMoveDown: () -> Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        label = "searchAiScale",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            active -> Amber.copy(alpha = 0.42f)
            else -> Snow.copy(alpha = 0.12f)
        },
        label = "searchAiBorder",
    )
    val fillColor by animateColorAsState(
        targetValue = when {
            focused -> Amber.copy(alpha = 0.26f)
            active -> Amber.copy(alpha = 0.18f)
            else -> Obsidian.copy(alpha = 0.34f)
        },
        label = "searchAiFill",
    )

    Row(
        modifier = modifier
            .width(78.dp)
            .height(38.dp)
            .scale(scale)
            .clip(RoundedCornerShape(999.dp))
            .background(fillColor)
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(999.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown -> {
                        onMoveDown()
                        true
                    }
                    event.key == Key.DirectionDown && event.type == KeyEventType.KeyUp -> true
                    else -> false
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "AI",
            style = MaterialTheme.typography.labelLarge,
            color = when {
                focused -> AmberLight
                enabled -> Snow
                else -> Silver.copy(alpha = 0.46f)
            },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (active) Amber.copy(alpha = 0.32f) else Snow.copy(alpha = 0.10f))
                .padding(2.dp),
            contentAlignment = if (active) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) AmberLight else Silver.copy(alpha = 0.68f)),
            )
        }
    }
}

@Composable
private fun TvSearchRefinementArea(
    filterType: String?,
    onFilterTypeChange: (String?) -> Unit,
    selectedGenreIds: Set<Int>,
    selectedYear: Int?,
    selectedMinRating: Double?,
    selectedSort: TvSearchSort,
    onSelectSort: (TvSearchSort) -> Unit,
    onQuickDiscovery: (String) -> Unit,
    filtersVisible: Boolean,
    advancedFiltersOpen: Boolean,
    activeFilterCount: Int,
    onToggleFilterVisibility: () -> Unit,
    onToggleAdvancedFilters: () -> Unit,
    allTypeRequester: FocusRequester,
    movieTypeRequester: FocusRequester,
    tvTypeRequester: FocusRequester,
    sortRequester: FocusRequester,
    filterToggleRequester: FocusRequester,
    advancedFiltersRequester: FocusRequester,
    inputFocusRequester: FocusRequester,
    bodyTopRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    onMoveDown: (() -> Unit)?,
    onFilterFocused: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
) {
    var sortOptionsVisible by remember { mutableStateOf(false) }
    var restoreSortFocus by remember { mutableStateOf(false) }

    LaunchedEffect(sortOptionsVisible, restoreSortFocus) {
        if (sortOptionsVisible || !restoreSortFocus) return@LaunchedEffect
        restoreSortFocus = false
        kotlinx.coroutines.yield()
        runCatching { sortRequester.requestFocus() }
        onContentFocused(sortRequester)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!filtersVisible) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TvSearchChip(
                    text = if (activeFilterCount > 0) "Filters · $activeFilterCount active" else "Filters",
                    selected = activeFilterCount > 0,
                    modifier = Modifier
                        .focusRequester(filterToggleRequester)
                        .focusProperties {
                            left = railFocusRequester
                            up = inputFocusRequester
                            down = bodyTopRequester
                    },
                onFocused = {
                    onFilterFocused(filterToggleRequester)
                    onContentFocused(filterToggleRequester)
                },
                onClick = onToggleFilterVisibility,
            )
            }
            return@Column
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(end = TV_FILTER_ROW_NESTED_END_GUTTER),
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    when {
                        onMoveDown != null &&
                            event.key == Key.DirectionDown &&
                            event.type == KeyEventType.KeyDown -> {
                            onMoveDown()
                            true
                        }
                        onMoveDown != null &&
                            event.key == Key.DirectionDown &&
                            event.type == KeyEventType.KeyUp -> true
                        else -> false
                    }
                },
        ) {
            item("all") {
            TvSearchChip(
                text = stringResource(R.string.tv_search_all),
                selected = filterType == null,
                modifier = Modifier
                    .focusRequester(allTypeRequester)
                    .focusProperties {
                        left = railFocusRequester
                        right = movieTypeRequester
                        up = inputFocusRequester
                        down = bodyTopRequester
                    },
                onFocused = {
                    onFilterFocused(allTypeRequester)
                    onContentFocused(allTypeRequester)
                },
                onClick = {
                    onFilterTypeChange(null)
                },
            )
            }
            item("movies") {
            TvSearchChip(
                text = stringResource(R.string.tv_search_movies),
                selected = filterType == "movie",
                modifier = Modifier
                    .focusRequester(movieTypeRequester)
                    .focusProperties {
                        left = allTypeRequester
                        right = tvTypeRequester
                        up = inputFocusRequester
                        down = bodyTopRequester
                    },
                onFocused = {
                    onFilterFocused(movieTypeRequester)
                    onContentFocused(movieTypeRequester)
                },
                onClick = {
                    onFilterTypeChange(if (filterType == "movie") null else "movie")
                },
            )
            }
            item("tv") {
            TvSearchChip(
                text = stringResource(R.string.tv_search_tv_shows),
                selected = filterType == "tv",
                modifier = Modifier
                    .focusRequester(tvTypeRequester)
                    .focusProperties {
                        left = movieTypeRequester
                        up = inputFocusRequester
                        down = bodyTopRequester
                    },
                onFocused = {
                    onFilterFocused(tvTypeRequester)
                    onContentFocused(tvTypeRequester)
                },
                onClick = {
                    onFilterTypeChange(if (filterType == "tv") null else "tv")
                },
            )
            }
            item("separator") {
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .width(1.dp)
                        .background(Snow.copy(alpha = 0.16f)),
                )
            }
            val quicks = listOf(
                "trending" to "Trending",
                "top_rated" to "Top Rated",
                "new" to "New",
            )
            items(quicks, key = { it.first }) { (key, label) ->
                val requester = remember(key) { FocusRequester() }
                val selected = when (key) {
                    "top_rated" -> selectedMinRating == TV_SEARCH_TOP_RATED_FILTER
                    "new" -> selectedYear == Calendar.getInstance().get(Calendar.YEAR)
                    else -> false
                }
                TvSearchChip(
                    text = label,
                    selected = selected,
                    modifier = Modifier
                        .focusRequester(requester)
                        .focusProperties {
                            up = inputFocusRequester
                            down = bodyTopRequester
                        },
                    onFocused = {
                        onFilterFocused(requester)
                        onContentFocused(requester)
                    },
                    onClick = { onQuickDiscovery(key) },
                )
            }
            item("sort") {
                TvSearchChip(
                    text = "Sort · ${selectedSort.label}",
                    selected = sortOptionsVisible || selectedSort != TvSearchSort.RELEVANCE,
                    modifier = Modifier
                        .focusRequester(sortRequester)
                        .focusProperties {
                            up = inputFocusRequester
                            down = bodyTopRequester
                        },
                    onFocused = {
                        onFilterFocused(sortRequester)
                        onContentFocused(sortRequester)
                    },
                    onClick = {
                        sortOptionsVisible = !sortOptionsVisible
                    },
                )
            }
            if (sortOptionsVisible) {
                items(TvSearchSort.entries, key = { "sort-option:${it.name}" }) { option ->
                    val requester = remember(option) { FocusRequester() }
                    TvSearchChip(
                        text = option.label,
                        selected = selectedSort == option,
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties {
                                up = inputFocusRequester
                                down = bodyTopRequester
                            },
                        onFocused = {
                            onFilterFocused(requester)
                            onContentFocused(requester)
                        },
                        onClick = {
                            onSelectSort(option)
                            sortOptionsVisible = false
                            restoreSortFocus = true
                        },
                    )
                }
            }
            item("advanced") {
                TvSearchChip(
                    text = if (advancedFiltersOpen) {
                        "Close filters"
                    } else if (activeFilterCount > 0) {
                        "Advanced · $activeFilterCount"
                    } else {
                        "Advanced"
                    },
                    selected = advancedFiltersOpen || activeFilterCount > 0,
                    modifier = Modifier
                        .focusRequester(advancedFiltersRequester)
                        .focusProperties {
                            up = inputFocusRequester
                            down = bodyTopRequester
                        },
                    onFocused = {
                        onFilterFocused(advancedFiltersRequester)
                        onContentFocused(advancedFiltersRequester)
                    },
                    onClick = onToggleAdvancedFilters,
                )
            }
            item("hide") {
                TvSearchChip(
                    text = "Hide filters",
                    selected = false,
                    modifier = Modifier
                        .focusRequester(filterToggleRequester)
                        .focusProperties {
                            up = inputFocusRequester
                            down = bodyTopRequester
                        },
                    onFocused = {
                        onFilterFocused(filterToggleRequester)
                        onContentFocused(filterToggleRequester)
                    },
                    onClick = onToggleFilterVisibility,
                )
            }
        }
    }
}

@Composable
private fun TvSearchGroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Silver.copy(alpha = 0.78f),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.padding(end = 2.dp),
    )
}

@Composable
private fun TvSearchDensityControl(
    density: TvSearchDensity,
    onDensityChange: (TvSearchDensity) -> Unit,
    densityRequesters: Map<TvSearchDensity, FocusRequester>,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    onMoveDown: () -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TvSearchDensity.entries.forEachIndexed { index, option ->
            val requester = densityRequesters.getValue(option)
            val previousRequester = TvSearchDensity.entries.getOrNull(index - 1)?.let { densityRequesters.getValue(it) }
            val nextRequester = TvSearchDensity.entries.getOrNull(index + 1)?.let { densityRequesters.getValue(it) }
            TvSearchChip(
                text = option.label,
                selected = density == option,
                modifier = Modifier
                    .focusRequester(requester)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown -> {
                                runCatching { upRequester.requestFocus() }
                                onContentFocused(upRequester)
                                true
                            }
                            event.key == Key.DirectionUp && event.type == KeyEventType.KeyUp -> true
                            event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown -> {
                                onMoveDown()
                                true
                            }
                            event.key == Key.DirectionDown && event.type == KeyEventType.KeyUp -> true
                            else -> false
                        }
                    }
                    .focusProperties {
                        left = previousRequester ?: requester
                        right = nextRequester ?: requester
                        up = upRequester
                        down = downRequester
                    },
                onFocused = { onContentFocused(requester) },
                onClick = { onDensityChange(option) },
            )
        }
    }
}

@Composable
private fun TvSearchAdvancedFilterDrawer(
    availableGenres: List<Pair<Int, String>>,
    availableYears: List<Int>,
    selectedGenreIds: Set<Int>,
    selectedStudioIds: Set<Int>,
    selectedYear: Int?,
    selectedMinRating: Double?,
    selectedMinImdbVotes: Int?,
    selectedRuntimeFilter: TvSearchRuntimeFilter?,
    selectedLanguageFilter: TvSearchLanguageFilter?,
    selectedAvailabilityFilter: TvSearchAvailabilityFilter?,
    activeFilterCount: Int,
    closeFiltersRequester: FocusRequester,
    clearFiltersRequester: FocusRequester,
    firstFilterRequester: FocusRequester,
    onToggleGenre: (Int) -> Unit,
    onToggleStudio: (Int) -> Unit,
    onSelectYear: (Int) -> Unit,
    onSelectRating: (Double) -> Unit,
    onSelectMinImdbVotes: (Int?) -> Unit,
    onSelectRuntime: (TvSearchRuntimeFilter?) -> Unit,
    onSelectLanguage: (TvSearchLanguageFilter?) -> Unit,
    onSelectAvailability: (TvSearchAvailabilityFilter?) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drawerScrollState = rememberScrollState()
    val ratingFirstRequester = remember { FocusRequester() }
    val votesFirstRequester = remember { FocusRequester() }
    val runtimeFirstRequester = remember { FocusRequester() }
    val languageFirstRequester = remember { FocusRequester() }
    val availabilityFirstRequester = remember { FocusRequester() }
    // Results are allowed to update while the drawer is open, but its controls
    // must not reorder or disappear underneath the user's focus.
    val stableDrawerGenres = remember { availableGenres }
    val stableDrawerYears = remember { availableYears }
    val focusManager = LocalFocusManager.current
    val focusScope = rememberCoroutineScope()
    var retainedFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var drawerHadFocus by remember { mutableStateOf(false) }

    fun retainFocus(requester: FocusRequester) {
        retainedFocusRequester = requester
        onContentFocused(requester)
        focusScope.launch {
            repeat(4) { attempt ->
                if (attempt == 0) kotlinx.coroutines.yield() else delay(36)
                runCatching { requester.requestFocus() }
            }
        }
    }

    // Search/result updates can recompose the content behind this drawer after
    // a filter click. Reassert the clicked chip after the new filter state is
    // committed so focus never escapes the modal.
    LaunchedEffect(
        selectedGenreIds,
        selectedStudioIds,
        selectedYear,
        selectedMinRating,
        selectedMinImdbVotes,
        selectedRuntimeFilter,
        selectedLanguageFilter,
        selectedAvailabilityFilter,
    ) {
        val requester = retainedFocusRequester ?: return@LaunchedEffect
        repeat(3) { attempt ->
            if (attempt == 0) kotlinx.coroutines.yield() else delay(48)
            runCatching { requester.requestFocus() }
        }
    }

    LaunchedEffect(Unit) {
        // The drawer can be composed while the underlying results/grid is also
        // settling. A single early request is unreliable on Fire TV, so keep
        // asserting the entry target during that short composition window.
        focusManager.clearFocus(force = true)
        repeat(8) { attempt ->
            delay(if (attempt == 0) 40L else 35L)
            runCatching { firstFilterRequester.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .width(720.dp)
            .heightIn(max = 470.dp)
            .onFocusChanged { focusState ->
                if (focusState.hasFocus) {
                    drawerHadFocus = true
                } else if (drawerHadFocus) {
                    focusScope.launch {
                        delay(24)
                        val requester = retainedFocusRequester ?: firstFilterRequester
                        repeat(3) {
                            runCatching { requester.requestFocus() }
                            delay(32)
                        }
                    }
                }
            }
            .focusGroup()
            .clip(RoundedCornerShape(26.dp))
            .background(Charcoal.copy(alpha = 0.90f))
            .border(1.dp, Snow.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Advanced filters",
                    style = MaterialTheme.typography.titleMedium,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = if (activeFilterCount > 0) "$activeFilterCount active filters" else "Genre, year, rating, sorting, IMDb votes, runtime, availability",
                    style = MaterialTheme.typography.labelMedium,
                    color = Silver,
                    maxLines = 1,
                )
            }
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
                    retainFocus(clearFiltersRequester)
                    onClearAll()
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
                onClick = onClose,
            )
        }
        Box {
            Column(
                modifier = Modifier
                    .heightIn(max = 378.dp)
                    .verticalScroll(drawerScrollState)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvSearchFilterRows(
                    genres = stableDrawerGenres,
                    selectedGenreIds = selectedGenreIds,
                    onToggleGenre = onToggleGenre,
                    studios = emptyList(),
                    selectedStudioIds = selectedStudioIds,
                    onToggleStudio = onToggleStudio,
                    years = stableDrawerYears.take(14),
                    selectedYear = selectedYear,
                    onSelectYear = onSelectYear,
                    selectedMinRating = selectedMinRating,
                    onSelectRating = onSelectRating,
                    railFocusRequester = firstFilterRequester,
                    firstFilterRequester = firstFilterRequester,
                    upRequester = closeFiltersRequester,
                    resultsRequester = null,
                    lastFilterRowRequester = ratingFirstRequester,
                    bottomRequester = votesFirstRequester,
                    onContentFocused = onContentFocused,
                    onFilterActivated = ::retainFocus,
                )
                TvSearchDrawerChoiceSection(
                    title = "Minimum IMDb votes",
                    labels = listOf("Default") + TV_SEARCH_IMDB_VOTE_THRESHOLDS.map { it.tvSearchCompactCount() + "+" },
                    selectedIndex = selectedMinImdbVotes?.let { TV_SEARCH_IMDB_VOTE_THRESHOLDS.indexOf(it) + 1 } ?: 0,
                    firstRequester = votesFirstRequester,
                    upRequester = ratingFirstRequester,
                    downRequester = runtimeFirstRequester,
                    onContentFocused = onContentFocused,
                    onChoiceActivated = ::retainFocus,
                    onSelectIndex = { index ->
                        onSelectMinImdbVotes(TV_SEARCH_IMDB_VOTE_THRESHOLDS.getOrNull(index - 1))
                    },
                )
                TvSearchDrawerChoiceSection(
                    title = "Runtime",
                    labels = listOf("Any") + TvSearchRuntimeFilter.entries.map { it.label },
                    selectedIndex = selectedRuntimeFilter?.let { TvSearchRuntimeFilter.entries.indexOf(it) + 1 } ?: 0,
                    firstRequester = runtimeFirstRequester,
                    upRequester = votesFirstRequester,
                    downRequester = languageFirstRequester,
                    onContentFocused = onContentFocused,
                    onChoiceActivated = ::retainFocus,
                    onSelectIndex = { index ->
                        onSelectRuntime(TvSearchRuntimeFilter.entries.getOrNull(index - 1))
                    },
                )
                TvSearchDrawerChoiceSection(
                    title = "Language",
                    labels = listOf("Any") + TvSearchLanguageFilter.entries.map { it.label },
                    selectedIndex = selectedLanguageFilter?.let { TvSearchLanguageFilter.entries.indexOf(it) + 1 } ?: 0,
                    firstRequester = languageFirstRequester,
                    upRequester = runtimeFirstRequester,
                    downRequester = availabilityFirstRequester,
                    onContentFocused = onContentFocused,
                    onChoiceActivated = ::retainFocus,
                    onSelectIndex = { index ->
                        onSelectLanguage(TvSearchLanguageFilter.entries.getOrNull(index - 1))
                    },
                )
                TvSearchDrawerChoiceSection(
                    title = "Availability",
                    labels = listOf("Any source") + TvSearchAvailabilityFilter.entries.map { it.label },
                    selectedIndex = selectedAvailabilityFilter?.let { TvSearchAvailabilityFilter.entries.indexOf(it) + 1 } ?: 0,
                    firstRequester = availabilityFirstRequester,
                    upRequester = languageFirstRequester,
                    downRequester = closeFiltersRequester,
                    onContentFocused = onContentFocused,
                    onChoiceActivated = ::retainFocus,
                    onSelectIndex = { index ->
                        onSelectAvailability(TvSearchAvailabilityFilter.entries.getOrNull(index - 1))
                    },
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Charcoal.copy(alpha = 0.95f)),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun TvSearchDrawerChoiceSection(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    firstRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    onContentFocused: (FocusRequester) -> Unit,
    onChoiceActivated: (FocusRequester) -> Unit,
    onSelectIndex: (Int) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = Silver,
        modifier = Modifier.padding(top = 4.dp),
    )
    val chipRequesters = remember(title, labels, firstRequester) {
        labels.mapIndexed { index, _ -> if (index == 0) firstRequester else FocusRequester() }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(count = labels.size, key = { index -> labels[index] }) { index ->
            val label = labels[index]
            val requester = chipRequesters[index]
            TvSearchChip(
                text = label,
                selected = index == selectedIndex,
                modifier = Modifier
                    .focusRequester(requester)
                    .focusProperties {
                        left = chipRequesters.getOrNull(index - 1) ?: requester
                        right = chipRequesters.getOrNull(index + 1) ?: requester
                        up = upRequester
                        down = downRequester
                    },
                onFocused = { onContentFocused(requester) },
                onClick = {
                    onChoiceActivated(requester)
                    onSelectIndex(index)
                },
            )
        }
    }
}

private data class TvSearchProviderRatingCardData(
    val provider: String,
    val value: String,
    val detail: String?,
    val iconRes: Int? = null,
)

@Suppress("UNUSED_PARAMETER")
@Composable
private fun TvSearchSelectedPreviewV2(
    item: MediaItem?,
    ratingPrefs: RatingDisplayPrefs,
    resultCount: Int,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Gunmetal.copy(alpha = 0.80f),
                        Charcoal.copy(alpha = 0.88f),
                        Obsidian.copy(alpha = 0.95f),
                    ),
                ),
            )
            .border(1.dp, Snow.copy(alpha = 0.15f), RoundedCornerShape(28.dp)),
    ) {
        val imageHeight = minOf((maxHeight * 0.48f).coerceIn(210.dp, 240.dp), maxHeight * 0.50f)
        if (item == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Explore",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Browse trending picks, refine by mood, or ask AI for something specific.",
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
        } else {
            val backdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() }
            val posterUrl = item.posterUrl?.takeIf { it.isNotBlank() }
            val artUrl = backdropUrl ?: posterUrl
            if (!artUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            if (artUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Obsidian.copy(alpha = 0.16f)),
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.30f to Color.Transparent,
                            0.48f to Obsidian.copy(alpha = 0.30f),
                            0.72f to Obsidian.copy(alpha = 0.58f),
                            1.00f to Obsidian.copy(alpha = 0.86f),
                        ),
                    ),
            )
            if (artUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Gunmetal.copy(alpha = 0.92f),
                                    Obsidian.copy(alpha = 0.96f),
                                ),
                            ),
                        ),
                )
            }
            Column(modifier = Modifier.fillMaxSize()) {
                val meta = item.tvSearchPreviewMetadataLine()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(imageHeight),
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        item.logoUrl?.takeIf { it.isNotBlank() }?.let { logo ->
                            AsyncImage(
                                model = logo,
                                contentDescription = item.title,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.BottomStart,
                                modifier = Modifier
                                    .fillMaxWidth(0.74f)
                                    .heightIn(min = 34.dp, max = 62.dp),
                            )
                        } ?: Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Snow,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (meta.isNotBlank()) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelMedium,
                                color = Snow.copy(alpha = 0.86f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 18.dp, top = 4.dp, end = 18.dp, bottom = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TvSearchProviderRatingCards(item)
                    TvSearchPreviewGenreRow(item)
                    val description = item.overview?.takeIf { it.isNotBlank() } ?: "No description available yet."
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
                        color = Snow.copy(alpha = 0.84f),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp)
                            .heightIn(min = 88.dp)
                            .weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSearchProviderRatingCards(item: MediaItem) {
    val ratings = item.ratings.withFallbackTmdbScore(item.rating)
    val cards = buildList {
        ratings?.imdbScore?.takeIf { it > 0f }?.let { score ->
            add(
                TvSearchProviderRatingCardData(
                    provider = "IMDb",
                    value = "%.1f/10".format(score),
                    detail = ratings.imdbVotes?.takeIf { it > 0 }?.let { it.tvSearchCompactCount() },
                    iconRes = R.drawable.tv_search_logo_imdb,
                ),
            )
        }
        ratings?.rottenTomatoesScore?.takeIf { it in 1..100 }?.let { score ->
            add(
                TvSearchProviderRatingCardData(
                    provider = "Rotten Tomatoes",
                    value = "$score%",
                    detail = "Critics",
                    iconRes = when {
                        score >= 75 -> R.drawable.tv_search_logo_rt_certified_fresh
                        score >= 60 -> R.drawable.tv_search_logo_rt_fresh
                        else -> R.drawable.tv_search_logo_rt_rotten
                    },
                ),
            )
        }
        ratings?.rtAudienceScore?.takeIf { it in 1..100 }?.let { score ->
            add(
                TvSearchProviderRatingCardData(
                    provider = "Audience",
                    value = "$score%",
                    detail = "Audience",
                    iconRes = if (score >= 60) {
                        R.drawable.tv_search_logo_rt_audience_fresh
                    } else {
                        R.drawable.tv_search_logo_rt_audience_rotten
                    },
                ),
            )
        }
        ratings?.tmdbScore?.takeIf { it > 0f }?.let { score ->
            add(
                TvSearchProviderRatingCardData(
                    provider = "TMDB",
                    value = "%.1f/10".format(score),
                    detail = item.voteCount?.takeIf { it > 0 }?.let { it.tvSearchCompactCount() },
                    iconRes = R.drawable.tv_search_logo_tmdb,
                ),
            )
        }
    }
    if (cards.isEmpty()) {
        Text(
            text = "No rating yet",
            style = MaterialTheme.typography.labelMedium,
            color = Silver.copy(alpha = 0.70f),
            maxLines = 1,
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .horizontalScroll(rememberScrollState())
            .padding(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cards.take(4).forEach { card ->
            TvSearchProviderRatingCard(card = card)
        }
    }
}

@Composable
private fun TvSearchProviderRatingCard(card: TvSearchProviderRatingCardData) {
    Row(
        modifier = Modifier
            .height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        card.iconRes?.let { iconRes ->
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = card.provider,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = card.value,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, lineHeight = 11.sp),
                color = Snow,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            card.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp, lineHeight = 8.sp),
                    color = Silver.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvSearchPreviewGenreRow(item: MediaItem) {
    val genres = item.genres
        .flatMap { it.name.tvSearchSplitGenreName() }
        .filter { it.isNotBlank() }
        .distinct()
    if (genres.isEmpty()) return
    val visibleGenres = genres.take(5)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleGenres.forEach { genre ->
            TvSearchMetaBadge(genre)
        }
        if (genres.size > visibleGenres.size) {
            TvSearchMetaBadge("+${genres.size - visibleGenres.size}")
        }
    }
}

private fun String.tvSearchSplitGenreName(): List<String> =
    split(Regex("\\s+(?:&|/)\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun Int.tvSearchCompactCount(): String =
    when {
        this >= 1_000_000 -> "%.1fM".format(this / 1_000_000f)
        this >= 1_000 -> "${this / 1_000}K"
        else -> toString()
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
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Gunmetal.copy(alpha = 0.72f),
                        Charcoal.copy(alpha = 0.86f),
                        Obsidian.copy(alpha = 0.90f),
                    ),
                ),
            )
            .border(1.dp, Snow.copy(alpha = 0.13f), RoundedCornerShape(24.dp)),
    ) {
        if (item == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Explore",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Browse trending picks, refine by mood, or ask AI for something specific.",
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
                .height(214.dp),
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
                        .heightIn(min = 36.dp, max = 66.dp),
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
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
            TvSearchPreviewRatings(item)
            item.genres.takeIf { it.isNotEmpty() }?.let { genres ->
                Text(
                    text = genres.take(2).joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TvSearchBadgeRow(item)
            val descriptionScroll = rememberScrollState()
            Text(
                text = item.overview?.takeIf { it.isNotBlank() } ?: "Focus a poster to see title details, ratings, and source context.",
                style = MaterialTheme.typography.bodyMedium,
                color = Snow.copy(alpha = 0.82f),
                modifier = Modifier
                    .heightIn(min = 70.dp, max = 126.dp)
                    .verticalScroll(descriptionScroll),
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
private fun TvSearchBadgeRow(item: MediaItem) {
    val badges = buildList {
        item.tvSearchPreferredRatingLabel()?.let { add(it) }
        item.runtime?.takeIf { it > 0 }?.let { add("${it}m") }
        if (item.type == MediaType.SERIES && item.seasons.isNotEmpty()) {
            add("${item.seasons.size} season${if (item.seasons.size == 1) "" else "s"}")
        }
        item.voteCount?.takeIf { it > 0 }?.let { add("${it} votes") }
    }.take(4)
    if (badges.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        items(badges, key = { it }) { label ->
            TvSearchMetaBadge(label)
        }
    }
}

@Composable
private fun TvSearchPreviewRatings(item: MediaItem) {
    val ratings = item.ratings.withFallbackTmdbScore(item.rating)
    val chips = buildList {
        ratings?.imdbScore?.takeIf { it > 0f }?.let { score ->
            val votes = ratings.imdbVotes?.takeIf { it > 0 }?.let { " · ${it / 1000}K" }.orEmpty()
            add("IMDb ${"%.1f".format(score)}$votes")
        }
        ratings?.rottenTomatoesScore?.takeIf { it in 1..100 }?.let { add("RT $it%") }
        ratings?.rtAudienceScore?.takeIf { it in 1..100 }?.let { add("Audience $it%") }
        if (isEmpty()) {
            ratings?.tmdbScore?.takeIf { it > 0f }?.let { add("TMDB ${"%.1f".format(it)}") }
        }
    }
    if (chips.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        contentPadding = PaddingValues(end = 8.dp),
    ) {
        items(chips, key = { it }) { label ->
            TvSearchRatingBadge(label)
        }
    }
}

@Composable
private fun TvSearchRatingBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Obsidian.copy(alpha = 0.72f))
            .border(1.dp, Amber.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TvSearchMetaBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Obsidian.copy(alpha = 0.70f))
            .border(1.dp, Snow.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Snow.copy(alpha = 0.86f),
            maxLines = 1,
        )
    }
}

@Composable
private fun TvSearchLoadingSkeletonGrid(columns: Int = 5) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceIn(3, 6)),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
private fun TvSearchExploreState(
    title: String,
    subtitle: String,
    onQuickTerm: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Charcoal.copy(alpha = 0.70f),
                        Gunmetal.copy(alpha = 0.58f),
                        Charcoal.copy(alpha = 0.64f),
                    ),
                ),
            )
            .border(1.dp, Snow.copy(alpha = 0.11f), RoundedCornerShape(26.dp))
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
            contentPadding = PaddingValues(top = 4.dp, end = 16.dp),
        ) {
            items(listOf("Trending movies", "New series", "Family night", "Sci-fi worlds", "Feel-good comedy")) { term ->
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
    lastFilterRowRequester: FocusRequester? = null,
    bottomRequester: FocusRequester? = resultsRequester,
    onContentFocused: (FocusRequester) -> Unit,
    onFilterActivated: (FocusRequester) -> Unit = {},
) {
    val columns = 5
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val groups = remember(genres, studios, years) {
            buildList {
                if (genres.isNotEmpty()) add(TvSearchFilterGroup("genre", "Genre", genres))
                if (studios.isNotEmpty()) add(TvSearchFilterGroup("studio", "Network / Studio", studios))
                if (years.isNotEmpty()) {
                    add(TvSearchFilterGroup("year", "Year", years.map { it to tvSearchYearRangeLabel(it) }))
                }
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
        val chipRequesters = remember(chipKeys, firstFilterRequester, lastFilterRowRequester) {
            List(chipKeys.size) { index ->
                when {
                    index == 0 -> firstFilterRequester
                    chips[index].groupKey == "rating" && chips.getOrNull(index - 1)?.groupKey != "rating" ->
                        lastFilterRowRequester ?: FocusRequester()
                    else -> FocusRequester()
                }
            }
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
                            down = nextRow ?: bottomRequester ?: requester
                        },
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        onFilterActivated(requester)
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
    density: TvSearchDensity,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.045f else 1f, label = "resultScale")
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
            .border(if (focused) 2.dp else 1.dp, if (focused) borderColor else Snow.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
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
        if (density.columns <= 4) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Obsidian.copy(alpha = 0.82f)),
                        ),
                    )
                    .padding(8.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun MediaItem.tvSearchStableKey(): String {
    // `id` belongs to the result row and survives metadata/rating hydration.
    // tmdbId/imdbId can appear later; using them first changes the LazyGrid key,
    // destroys the focused node, and lets the navigation rail steal focus.
    val sourceId = id.trim().takeIf { it.isNotEmpty() }
    val sourceTmdbId = sourceId?.extractTmdbIdOrNull()
    val sourceImdbId = sourceId?.extractImdbIdOrNull()
    val imdb = imdbId?.takeIf { it.isNotBlank() }
    return when {
        sourceTmdbId != null -> "${type.name}:tmdb:$sourceTmdbId"
        sourceImdbId != null -> "${type.name}:imdb:${sourceImdbId.lowercase()}"
        sourceId != null -> "${type.name}:id:${sourceId.lowercase()}"
        tmdbId != null -> "${type.name}:tmdb:$tmdbId"
        imdb != null -> "${type.name}:imdb:${imdb.lowercase()}"
        else -> "${type.name}:title:${title.tvSearchNormalizeTitle()}:${year ?: 0}"
    }
}

private fun tvSearchLoadStateKey(
    query: String,
    searchMode: SearchMode,
    filterType: String?,
    selectedGenreIds: Set<Int>,
    selectedStudioIds: Set<Int>,
    selectedYear: Int?,
    selectedMinRating: Double?,
    selectedMinImdbVotes: Int?,
    selectedRuntimeFilter: TvSearchRuntimeFilter?,
    selectedLanguageFilter: TvSearchLanguageFilter?,
    selectedAvailabilityFilter: TvSearchAvailabilityFilter?,
): String = listOf(
    "mode=${searchMode.name}",
    "query=${query.trim().lowercase()}",
    "type=${filterType ?: "all"}",
    "genres=${selectedGenreIds.sorted().joinToString(",")}",
    "studios=${selectedStudioIds.sorted().joinToString(",")}",
    "year=${selectedYear ?: "-"}",
    "rating=${selectedMinRating ?: "-"}",
    "imdbVotes=${selectedMinImdbVotes ?: "-"}",
    "runtime=${selectedRuntimeFilter?.name ?: "-"}",
    "language=${selectedLanguageFilter?.name ?: "-"}",
    "availability=${selectedAvailabilityFilter?.name ?: "-"}",
).joinToString("|")

private fun tvSearchEffectiveQuery(
    query: String,
    availabilityFilter: TvSearchAvailabilityFilter?,
): String {
    return query.trim()
}

private fun List<MediaItem>.needsTvSearchRatingEnrichment(): Boolean =
    any { item -> item.needsExternalRatingEnrichment() }

internal fun MediaItem.needsTvSearchPrimaryRatingEnrichment(): Boolean =
    hasExternalRatingLookupIdentity() &&
        (ratings?.imdbScore == null || ratings?.rottenTomatoesScore == null)

private fun tvSearchRatingsChanged(
    before: List<MediaItem>,
    after: List<MediaItem>,
): Boolean {
    if (before.size != after.size) return true
    return before.zip(after).any { (left, right) ->
        left.tmdbId != right.tmdbId ||
            left.imdbId != right.imdbId ||
            left.ratings != right.ratings
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

private fun MediaItem.tvSearchPreviewMetadataLine(): String {
    val parts = buildList {
        year?.let { add(it.toString()) }
        add(if (type == MediaType.SERIES) "TV Series" else "Movie")
        runtime?.takeIf { it > 0 }?.let { add("${it}m") }
        if (type == MediaType.SERIES && seasons.isNotEmpty()) {
            add("${seasons.size} season${if (seasons.size == 1) "" else "s"}")
        }
        if (adult == true) add("18+")
    }
    return parts.joinToString(" · ")
}

private fun MediaItem.tvSearchShortMetadata(): String {
    val parts = buildList {
        year?.let { add(it.toString()) }
        add(if (type == MediaType.SERIES) "Series" else "Movie")
        tvSearchPreferredRatingLabel()?.let { add(it) }
    }
    return parts.joinToString(" · ")
}

private fun MediaItem.tvSearchPreferredRatingLabel(): String? {
    ratings?.imdbScore?.takeIf { it > 0f }?.let { score ->
        return "IMDb ${"%.1f".format(score)}"
    }
    ratings?.rottenTomatoesScore?.takeIf { it in 1..100 }?.let { score ->
        return "RT $score%"
    }
    return null
}

internal fun mergeTvSearchDuplicate(primary: MediaItem, other: MediaItem): MediaItem =
    primary.copy(
        tmdbId = primary.tmdbId ?: other.tmdbId,
        imdbId = primary.imdbId ?: other.imdbId,
        year = primary.year ?: other.year,
        overview = primary.overview ?: other.overview,
        posterUrl = primary.posterUrl?.takeIf { it.isNotBlank() }
            ?: other.posterUrl?.takeIf { it.isNotBlank() },
        backdropUrl = primary.backdropUrl?.takeIf { it.isNotBlank() }
            ?: other.backdropUrl?.takeIf { it.isNotBlank() },
        logoUrl = primary.logoUrl?.takeIf { it.isNotBlank() }
            ?: other.logoUrl?.takeIf { it.isNotBlank() },
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

internal fun stableTvSearchProjection(
    existing: List<MediaItem>,
    available: List<MediaItem>,
    limit: Int,
): List<MediaItem> {
    val availableByKey = available.associateByTo(LinkedHashMap()) { it.tvSearchStableKey() }
    val retained = existing.mapNotNull { item ->
        availableByKey.remove(item.tvSearchStableKey())
    }
    return (retained + availableByKey.values).take(limit)
}

private suspend fun MetadataRepository.loadTvSearchBrowsePage(
    filterType: String?,
    genreIds: Set<Int>,
    year: Int?,
    minRating: Double?,
    minImdbVotes: Int?,
    runtimeFilter: TvSearchRuntimeFilter?,
    languageFilter: TvSearchLanguageFilter?,
    availabilityFilter: TvSearchAvailabilityFilter?,
    regionCode: String,
    page: Int,
): TvSearchCandidatePage {
    val types = when (filterType) {
        "movie" -> listOf("movie")
        "tv" -> listOf("tv")
        else -> listOf("movie", "tv")
    }
    val genres = genreIds.takeIf { it.isNotEmpty() }?.joinToString(",")
    val hasRatingCriteria = minRating != null || minImdbVotes != null
    val sortBy = if (hasRatingCriteria) "vote_average.desc" else "popularity.desc"
    val watchProviders = tvSearchWatchProvidersFor(availabilityFilter)
    val useTopRatedEndpoint = minRating == TV_SEARCH_TOP_RATED_FILTER &&
        genres == null && year == null && runtimeFilter == null &&
        languageFilter == null && watchProviders == null
    val pages = types.map { type ->
        if (useTopRatedEndpoint) {
            getTopRatedPaged(type, page)
        } else {
            discover(
                type = type,
                page = page,
                sortBy = sortBy,
                withGenres = genres,
                minRating = null,
                year = year.tvSearchYearFromForApi(),
                yearTo = year.tvSearchYearToForApi(),
                runtimeGte = runtimeFilter?.minMinutes,
                runtimeLte = runtimeFilter?.maxMinutes,
                originalLanguage = languageFilter?.code,
                withWatchProviders = watchProviders,
                watchRegion = watchProviders?.let { regionCode },
            )
        }
    }
    return TvSearchCandidatePage(
        items = pages.flatMap { it.items }.dedupeTvSearchResults(),
        page = page,
        totalPages = pages.maxOfOrNull { it.totalPages } ?: page,
    )
}

private suspend fun MetadataRepository.loadTvSearchBrowseResults(
    filterType: String?,
    genreIds: Set<Int>,
    year: Int?,
    minRating: Double?,
    minImdbVotes: Int?,
    runtimeFilter: TvSearchRuntimeFilter?,
    languageFilter: TvSearchLanguageFilter?,
    availabilityFilter: TvSearchAvailabilityFilter?,
    regionCode: String,
): List<MediaItem> {
    val hasRatingCriteria = minRating != null || minImdbVotes != null
    val pageRange = 1..if (hasRatingCriteria) {
        TV_SEARCH_RATING_DISCOVER_PAGE_COUNT
    } else {
        TV_SEARCH_DISCOVER_PAGE_COUNT
    }
    return pageRange
        .flatMap { page ->
            runCatching {
                loadTvSearchBrowsePage(
                    filterType = filterType,
                    genreIds = genreIds,
                    year = year,
                    minRating = minRating,
                    minImdbVotes = minImdbVotes,
                    runtimeFilter = runtimeFilter,
                    languageFilter = languageFilter,
                    availabilityFilter = availabilityFilter,
                    regionCode = regionCode,
                    page = page,
                ).items
            }.getOrDefault(emptyList())
        }
        .distinctBy { it.tvSearchStableKey() }
        .take(TV_SEARCH_MAX_RESULTS)
}

private suspend fun MetadataRepository.loadVerifiedBrowseBatch(
    ratingsEnricher: RatingsEnricher,
    apiKey: String,
    existingCandidates: List<MediaItem>,
    existingResults: List<MediaItem>,
    startPage: Int,
    targetResultCount: Int,
    filterType: String?,
    genreIds: Set<Int>,
    studioIds: Set<Int>,
    year: Int?,
    minRating: Double?,
    minImdbVotes: Int?,
    runtimeFilter: TvSearchRuntimeFilter?,
    languageFilter: TvSearchLanguageFilter?,
    availabilityFilter: TvSearchAvailabilityFilter?,
    regionCode: String,
    sourceAvailabilityByTmdbId: Map<Int, SourceAvailabilityRecord>,
): TvSearchVerifiedBrowseBatch {
    var candidates = existingCandidates
    var verified = candidates.filterTvSearchItems(
        genreIds = genreIds,
        studioIds = studioIds,
        year = year,
        minRating = minRating,
        minImdbVotes = minImdbVotes,
        runtimeFilter = runtimeFilter,
        availabilityFilter = availabilityFilter,
        sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
    ).take(TV_SEARCH_MAX_RESULTS)
    var page = startPage.coerceAtLeast(1)
    var totalPages = Int.MAX_VALUE
    var lastPageHadItems = true
    var pageLoadFailed = false
    var candidatePagesLoaded = 0
    val target = targetResultCount.coerceIn(1, TV_SEARCH_MAX_RESULTS)
    var visible = stableTvSearchProjection(existingResults, verified, target)

    while (
        visible.size < target &&
        page <= totalPages &&
        candidates.size < TV_SEARCH_MAX_CANDIDATES &&
        candidatePagesLoaded < TV_SEARCH_VERIFIED_MAX_CANDIDATE_PAGES_PER_BATCH
    ) {
        val candidatePageAttempt = runCatching {
            runCatching {
                loadTvSearchBrowsePage(
                    filterType = filterType,
                    genreIds = genreIds,
                    year = year,
                    minRating = minRating,
                    minImdbVotes = minImdbVotes,
                    runtimeFilter = runtimeFilter,
                    languageFilter = languageFilter,
                    availabilityFilter = availabilityFilter,
                    regionCode = regionCode,
                    page = page,
                )
            }.getOrElse {
                delay(350)
                runCatching {
                    loadTvSearchBrowsePage(
                        filterType = filterType,
                        genreIds = genreIds,
                        year = year,
                        minRating = minRating,
                        minImdbVotes = minImdbVotes,
                        runtimeFilter = runtimeFilter,
                        languageFilter = languageFilter,
                        availabilityFilter = availabilityFilter,
                        regionCode = regionCode,
                        page = page,
                    )
                }.getOrElse {
                    delay(700)
                    loadTvSearchBrowsePage(
                        filterType = filterType,
                        genreIds = genreIds,
                        year = year,
                        minRating = minRating,
                        minImdbVotes = minImdbVotes,
                        runtimeFilter = runtimeFilter,
                        languageFilter = languageFilter,
                        availabilityFilter = availabilityFilter,
                        regionCode = regionCode,
                        page = page,
                    )
                }
            }
        }
        val candidatePage = candidatePageAttempt.getOrNull()
        if (candidatePage == null) {
            pageLoadFailed = true
            Log.w(
                "TvSearchPaging",
                "verified_candidate_page_failed page=$page; returning ${visible.size} partial results",
                candidatePageAttempt.exceptionOrNull(),
            )
            break
        }
        totalPages = candidatePage.totalPages
        lastPageHadItems = candidatePage.items.isNotEmpty()
        if (!lastPageHadItems) break
        candidatePagesLoaded += 1

        val cached = withContext(Dispatchers.IO) {
            ratingsEnricher.hydrateListFromCache(candidatePage.items)
        }
        val enriched = ratingsEnricher.enrichImdbList(cached, apiKey)
        candidates = (candidates + enriched)
            .dedupeTvSearchResults()
            .take(TV_SEARCH_MAX_CANDIDATES)
        verified = candidates.filterTvSearchItems(
            genreIds = genreIds,
            studioIds = studioIds,
            year = year,
            minRating = minRating,
            minImdbVotes = minImdbVotes,
            runtimeFilter = runtimeFilter,
            availabilityFilter = availabilityFilter,
            sourceAvailabilityByTmdbId = sourceAvailabilityByTmdbId,
        ).take(TV_SEARCH_MAX_RESULTS)
        visible = stableTvSearchProjection(existingResults, verified, target)
        page = candidatePage.page + 1
    }

    val visibleKeys = visible.mapTo(mutableSetOf()) { it.tvSearchStableKey() }

    return TvSearchVerifiedBrowseBatch(
        candidates = candidates,
        results = visible,
        nextPage = page,
        totalPages = if (totalPages == Int.MAX_VALUE) page else totalPages,
        canLoadMore = !pageLoadFailed &&
            (verified.any { it.tvSearchStableKey() !in visibleKeys } ||
            (lastPageHadItems &&
                page <= totalPages &&
                candidates.size < TV_SEARCH_MAX_CANDIDATES &&
                verified.size < TV_SEARCH_MAX_RESULTS)),
    )
}

private fun tvSearchWatchProvidersFor(availabilityFilter: TvSearchAvailabilityFilter?): String? =
    when (availabilityFilter) {
        TvSearchAvailabilityFilter.STREAMING -> "8|9|337|350|15|384|531|1899"
        else -> null
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

internal fun List<MediaItem>.sortedForTvSearch(sort: TvSearchSort): List<MediaItem> = when (sort) {
    TvSearchSort.RELEVANCE -> this
    TvSearchSort.IMDB_DESC -> sortedByDescending { it.ratings?.imdbScore ?: -1f }
    TvSearchSort.IMDB_ASC -> sortedBy { it.ratings?.imdbScore ?: Float.MAX_VALUE }
    TvSearchSort.ROTTEN_TOMATOES_DESC -> sortedByDescending { it.ratings?.rottenTomatoesScore ?: -1 }
    TvSearchSort.ROTTEN_TOMATOES_ASC -> sortedBy { it.ratings?.rottenTomatoesScore ?: Int.MAX_VALUE }
    TvSearchSort.RT_AUDIENCE_DESC -> sortedByDescending { it.ratings?.rtAudienceScore ?: -1 }
    TvSearchSort.RT_AUDIENCE_ASC -> sortedBy { it.ratings?.rtAudienceScore ?: Int.MAX_VALUE }
    TvSearchSort.TMDB_DESC -> sortedByDescending {
        it.ratings?.tmdbScore ?: it.rating?.toFloat() ?: -1f
    }
    TvSearchSort.TMDB_ASC -> sortedBy {
        it.ratings?.tmdbScore ?: it.rating?.toFloat() ?: Float.MAX_VALUE
    }
    TvSearchSort.VOTES_DESC -> sortedByDescending { it.ratings?.imdbVotes ?: -1 }
    TvSearchSort.VOTES_ASC -> sortedBy { it.ratings?.imdbVotes ?: Int.MAX_VALUE }
    TvSearchSort.POPULARITY_DESC -> sortedByDescending { it.popularity ?: -1.0 }
    TvSearchSort.POPULARITY_ASC -> sortedBy { it.popularity ?: Double.MAX_VALUE }
    TvSearchSort.TITLE_ASC -> sortedBy { it.title.lowercase() }
    TvSearchSort.TITLE_DESC -> sortedByDescending { it.title.lowercase() }
}

private fun MediaItem.tvSearchBestAvailableRating(): Double? =
    ratings?.imdbScore?.takeIf { it > 0f }?.toDouble()
        ?: ratings?.tmdbScore?.takeIf { it > 0f }?.toDouble()
        ?: rating?.takeIf { it > 0.0 }

private fun List<MediaItem>.filterTvSearchItems(
    genreIds: Set<Int>,
    studioIds: Set<Int>,
    year: Int?,
    minRating: Double?,
    minImdbVotes: Int?,
    runtimeFilter: TvSearchRuntimeFilter?,
    availabilityFilter: TvSearchAvailabilityFilter?,
    sourceAvailabilityByTmdbId: Map<Int, SourceAvailabilityRecord>,
): List<MediaItem> =
    filter { item ->
            (genreIds.isEmpty() || item.genreIds.any { it in genreIds } || item.genres.any { it.id in genreIds }) &&
            (studioIds.isEmpty() || item.studios.any { it.id in studioIds }) &&
            item.matchesTvSearchYear(year) &&
            item.matchesTvSearchMinRating(minRating, minImdbVotes) &&
            item.matchesTvSearchRuntime(runtimeFilter) &&
            item.matchesTvSearchAvailability(availabilityFilter, sourceAvailabilityByTmdbId)
        }
        .dedupeTvSearchResults()

private fun MediaItem.matchesTvSearchYear(selectedYear: Int?): Boolean {
    selectedYear ?: return true
    val itemYear = year ?: return false
    return when (selectedYear) {
        2020 -> itemYear >= 2020
        2010, 2000, 1990, 1980, 1970 -> itemYear in selectedYear..(selectedYear + 9)
        0 -> itemYear < 1970
        else -> itemYear == selectedYear
    }
}

private fun tvSearchYearRangeLabel(start: Int): String = when (start) {
    2020 -> "2020+"
    0 -> "Before 1970"
    else -> "${start}–${start + 9}"
}

private fun Int?.tvSearchYearFromForApi(): Int? = when (this) {
    0, null -> null
    else -> this
}

private fun Int?.tvSearchYearToForApi(): Int? = when (this) {
    null, 2020 -> null
    0 -> 1969
    2010, 2000, 1990, 1980, 1970 -> this + 9
    else -> this
}

internal fun MediaItem.matchesTvSearchMinRating(minRating: Double?, minImdbVotes: Int?): Boolean {
    if (minRating == TV_SEARCH_TOP_RATED_FILTER) {
        val score = tvSearchBestAvailableRating() ?: return false
        val votes = ratings?.imdbVotes ?: voteCount ?: return false
        return score >= TV_SEARCH_TOP_RATED_MIN_SCORE && votes >= TV_SEARCH_TOP_RATED_MIN_VOTES
    }
    val imdbVotes = ratings?.imdbVotes
    val requiredVotes = minImdbVotes
    if (requiredVotes != null && (imdbVotes == null || imdbVotes < requiredVotes)) return false
    minRating ?: return true
    val imdbRating = ratings?.imdbScore?.takeIf { it > 0f }?.toDouble()
    if (minRating >= 9.0) return imdbRating?.let { it >= minRating } ?: false
    val externalRating = imdbRating
        ?: ratings?.tmdbScore?.takeIf { it > 0f }?.toDouble()
        ?: ratings?.letterboxdScore?.takeIf { it > 0f }?.times(2f)?.toDouble()
    if (externalRating != null) return externalRating >= minRating
    return minRating < 9.0 && (rating ?: 0.0) >= minRating
}

private fun MediaItem.matchesTvSearchRuntime(filter: TvSearchRuntimeFilter?): Boolean {
    filter ?: return true
    val value = runtime ?: return true
    val min = filter.minMinutes
    val max = filter.maxMinutes
    return (min == null || value >= min) && (max == null || value <= max)
}

private fun MediaItem.matchesTvSearchAvailability(
    filter: TvSearchAvailabilityFilter?,
    sourceAvailabilityByTmdbId: Map<Int, SourceAvailabilityRecord>,
): Boolean {
    return when (filter) {
        null,
        TvSearchAvailabilityFilter.STREAMING,
        TvSearchAvailabilityFilter.READY -> {
            val tmdb = tmdbId ?: return false
            sourceAvailabilityByTmdbId[tmdb]?.isAvailable ?: sourceAvailabilityByTmdbId.isEmpty()
        }
    }
}

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
