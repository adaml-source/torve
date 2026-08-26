package com.torve.android.tv.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import com.torve.android.tv.toMediaItemOrNull
import com.torve.android.ui.theme.*
import com.torve.domain.integrations.LibraryOverlayService
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.tv.TV_PAGE_BOTTOM_GUTTER
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.TV_PAGE_END_GUTTER
import com.torve.android.tv.TV_PAGE_TOP_GUTTER
import com.torve.android.tv.TvImagePrefetcher
import com.torve.android.tv.components.TvBrowsePreviewPanel
import com.torve.android.tv.components.TvClickToEditSearchField
import com.torve.android.tv.components.TvProviderBrandHeader
import com.torve.android.tv.components.cacheTvBrowsePreviewEnrichedItem
import com.torve.android.tv.settings.TV_SEE_ALL_POSTER_COLUMN_OPTIONS
import com.torve.android.tv.settings.rememberTvSeeAllPosterColumnsPreference
import com.torve.android.tv.settings.setTvSeeAllPosterColumns
import com.torve.android.ui.home.ALL_STREAMING_SERVICES
import com.torve.android.ui.home.StreamingService
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.data.metadata.TmdbMappers
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.PagedResult
import com.torve.domain.model.StreamingProviderCandidate
import com.torve.domain.model.resolveStreamingProviderIds
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsViewModel
import com.torve.domain.model.hasRichExternalRating
import com.torve.domain.model.needsExternalRatingEnrichment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.torve.domain.model.MediaType
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.WatchProgressRepository
import com.torve.presentation.seeall.SeeAllViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.torve.android.tv.focus.TvFocusTargetId
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.focus.rememberRegisteredTvFocusRequester
import com.torve.android.tv.focus.rememberTvModalFocusRestoreController
import org.koin.compose.koinInject

private const val TV_SEE_ALL_INITIAL_TARGET_COUNT = 100
private const val TV_SEE_ALL_FILTER_METADATA_LIMIT = 30
private const val TV_SEE_ALL_FILTER_RESULT_TARGET_COUNT = 60
private const val TV_SEE_ALL_FILTER_GENRE_LIMIT = 8
private const val TV_PROVIDER_WORLDWIDE_REGION_BATCH_SIZE = 8
private const val NETFLIX_TMDB_PROVIDER_ID = 8
private const val TV_SEE_ALL_FILTER_STUDIO_LIMIT = 5
private const val TV_SEE_ALL_FILTER_YEAR_LIMIT = 5
private const val TV_SEE_ALL_LOG_TAG = "TvSeeAll"

private data class TvSeeAllCacheEntry(
    val items: List<MediaItem>,
    val currentPage: Int,
    val totalPages: Int,
    val personPanelInfo: TvPersonPanelInfo?,
)

private data class TvSeeAllFilterGroup(
    val key: String,
    val label: String,
    val options: List<Pair<Int, String>>,
)

private data class TvSeeAllFilterChipSpec(
    val groupKey: String,
    val id: Int,
    val label: String,
)

private data class TvSeeAllFilterVisualRow(
    val groupLabel: String?,
    val chipIndices: List<Int>,
)

private object TvSeeAllCache {
    private val entries = mutableMapOf<String, TvSeeAllCacheEntry>()

    fun get(key: String): TvSeeAllCacheEntry? = entries[key]

    fun put(key: String, entry: TvSeeAllCacheEntry) {
        entries[key] = entry
    }
}

private fun MediaRatings?.preferSeeAllRichRatings(other: MediaRatings?): MediaRatings? =
    when {
        this.hasRichExternalRating() -> this
        other.hasRichExternalRating() -> other
        this != null -> this
        else -> other
    }

private fun MediaItem.mergeSeeAllEnrichedItem(other: MediaItem): MediaItem =
    copy(
        tmdbId = tmdbId ?: other.tmdbId,
        imdbId = imdbId ?: other.imdbId,
        overview = overview ?: other.overview,
        genres = genres.ifEmpty { other.genres },
        genreIds = genreIds.ifEmpty { other.genreIds },
        studios = studios.ifEmpty { other.studios },
        rating = rating ?: other.rating,
        ratings = ratings.preferSeeAllRichRatings(other.ratings),
        year = year ?: other.year,
        releaseDate = releaseDate ?: other.releaseDate,
        posterUrl = posterUrl ?: other.posterUrl,
        backdropUrl = backdropUrl ?: other.backdropUrl,
        logoUrl = logoUrl ?: other.logoUrl,
        runtime = runtime ?: other.runtime,
        seasons = seasons.ifEmpty { other.seasons },
        cast = if (cast.isNotEmpty()) cast else other.cast,
    )

private fun MutableList<MediaItem>.replaceSeeAllItemsByKey(
    replacements: Map<String, MediaItem>,
): Boolean {
    var changed = false
    for (index in indices) {
        val current = this[index]
        val replacement = replacements[current.seeAllStableKey()] ?: continue
        val merged = current.mergeSeeAllEnrichedItem(replacement)
        if (merged != current) {
            this[index] = merged
            changed = true
        }
    }
    return changed
}

@Composable
internal fun TvSeeAllScreen(
    railKey: String,
    mediaType: String,
    title: String,
    railFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onSeeAll: ((railKey: String, title: String, mediaType: String) -> Unit)? = null,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
) {
    val routeProviderId = remember(railKey) {
        railKey.takeIf { it.startsWith("provider_") }
            ?.split("_")
            ?.getOrNull(2)
            ?.toIntOrNull()
    }
    if (routeProviderId != null) {
        TvProviderCatalogScreen(
            providerId = routeProviderId,
            providerName = title,
            railFocusRequester = railFocusRequester,
            onMediaClick = onMediaClick,
            onBack = onBack,
            onFirstContentRequester = onFirstContentRequester,
            onContentFocused = onContentFocused,
            onSeeAll = onSeeAll,
            registerFocusHandle = registerFocusHandle,
        )
        return
    }
    val metadataRepo: MetadataRepository = koinInject()
    val watchProgressRepo: WatchProgressRepository = koinInject()
    val libraryOverlayService: LibraryOverlayService = koinInject()
    val ratingsEnricher: RatingsEnricher = koinInject()
    val prefsRepo: PreferencesRepository = koinInject()
    val secretStore: IntegrationSecretStore = koinInject()
    val isPersonCreditsRail = railKey.startsWith("person_credits_")
    val isProviderDiscoveryRail = railKey.startsWith("provider_")
    val isStreamingCatalogRail = railKey.startsWith("streaming_catalog_")
    val streamingCatalogProviderId = remember(railKey) {
        railKey.takeIf { it.startsWith("streaming_catalog_") }
            ?.split("_")
            ?.getOrNull(2)
            ?.toIntOrNull()
    }
    val configuredProviderId = routeProviderId ?: streamingCatalogProviderId
    val isProviderSearchRail = isProviderDiscoveryRail || isStreamingCatalogRail
    val providerDisplayName = remember(configuredProviderId, title) {
        ALL_STREAMING_SERVICES.firstOrNull { it.tmdbProviderId == configuredProviderId }?.name
            ?: title.removePrefix("Search & filter ").substringBefore(" · ")
    }
    val initialProviderType = remember(railKey) {
        when {
            railKey.endsWith("_popular_series") -> "tv"
            else -> railKey.split("_").getOrNull(1)?.takeIf { it == "movie" || it == "tv" } ?: "movie"
        }
    }
    var providerContentType by rememberSaveable(railKey) { mutableStateOf(initialProviderType) }
    var providerDefaultRegion by rememberSaveable(railKey) { mutableStateOf<String?>(null) }
    var selectedProviderRegions by remember(railKey) { mutableStateOf<Set<String>>(emptySet()) }
    var providerRegionQueries by remember(railKey, providerContentType) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var providerControlsInteracted by remember(railKey) { mutableStateOf(false) }
    val items = remember { mutableStateListOf<MediaItem>() }
    val filterOptionItems = remember(railKey, mediaType) { mutableStateListOf<MediaItem>() }
    var currentPage by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var loading by remember { mutableStateOf(false) }
    var initialLoad by remember { mutableStateOf(true) }
    var personPanelInfo by remember(railKey) { mutableStateOf<TvPersonPanelInfo?>(null) }
    val gridState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val previewFocusRequester = remember { FocusRequester() }
    val sortToggleRequester = remember(railKey, mediaType, "sort_toggle") { FocusRequester() }
    val catalogSearchRequester = remember(railKey, mediaType, "catalog_search") { FocusRequester() }
    val filterToggleRequester = remember(railKey, mediaType, "filter_toggle") { FocusRequester() }
    val focusRestoreController = rememberTvModalFocusRestoreController(key = "see_all_${railKey}_$mediaType")
    val sortOptions = remember(railKey) { sortOptionsForRail(railKey) }
    var selectedSortKey by remember(railKey) { mutableStateOf(defaultSortKeyForRail(railKey, sortOptions)) }
    val sortRequesters = remember(sortOptions) { List(sortOptions.size) { FocusRequester() } }
    val selectedSortIndex = sortOptions.indexOfFirst { it.key == selectedSortKey }.coerceAtLeast(0)
    val loadedItems = items.toList()
    var showFilters by rememberSaveable(railKey, mediaType) { mutableStateOf(false) }
    var showSortOptions by rememberSaveable(railKey, mediaType) { mutableStateOf(false) }
    var selectedGenreIds by remember(railKey, mediaType) { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedStudioIds by remember(railKey, mediaType) { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedYearRangeIds by remember(railKey, mediaType) { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedMinRating by remember(railKey, mediaType) { mutableStateOf<Double?>(null) }
    var titleQuery by rememberSaveable(railKey, mediaType) { mutableStateOf("") }
    var restoreFocusAfterClearNonce by remember(railKey, mediaType) { mutableIntStateOf(0) }
    var restoreSortToggleNonce by remember(railKey, mediaType) { mutableIntStateOf(0) }
    var focusFirstFilterAfterOpen by remember(railKey, mediaType) { mutableStateOf(false) }
    val defaultSortKey = defaultSortKeyForRail(railKey, sortOptions)
    val supportsGlobalTmdbFilters = remember(railKey) { supportsGlobalTmdbSeeAllQuery(railKey) }
    val hasActiveFilters = selectedGenreIds.isNotEmpty() ||
        selectedStudioIds.isNotEmpty() ||
        selectedYearRangeIds.isNotEmpty() ||
        selectedMinRating != null ||
        titleQuery.isNotBlank()
    val usesGlobalTmdbQuery = supportsGlobalTmdbFilters &&
        (hasActiveFilters || selectedSortKey != defaultSortKey)
    val filterSourceItems = filterOptionItems.toList().ifEmpty { loadedItems }
    val filterMediaType = remember(railKey, mediaType, providerContentType) {
        if (isProviderSearchRail) providerContentType else mediaType.takeIf { it == "movie" || it == "tv" }
            ?: when {
                railKey.contains("_tv") || railKey.endsWith("-tv") -> "tv"
                else -> "movie"
            }
    }
    val availableGenres = remember(filterSourceItems, filterMediaType, isProviderSearchRail, supportsGlobalTmdbFilters) {
        if (isProviderSearchRail || supportsGlobalTmdbFilters) {
            defaultSeeAllGenres(filterMediaType)
        } else {
            filterSourceItems.availableSeeAllGenres()
                .ifEmpty { defaultSeeAllGenres(filterMediaType) }
                .take(TV_SEE_ALL_FILTER_GENRE_LIMIT)
        }
    }
    val availableStudios = remember(filterSourceItems, filterMediaType, supportsGlobalTmdbFilters) {
        if (supportsGlobalTmdbFilters) {
            defaultSeeAllStudios(filterMediaType)
        } else {
            filterSourceItems.availableSeeAllStudios()
                .ifEmpty { defaultSeeAllStudios(filterMediaType) }
                .take(TV_SEE_ALL_FILTER_STUDIO_LIMIT)
        }
    }
    val availableYearRanges = remember(filterSourceItems, isProviderSearchRail, supportsGlobalTmdbFilters) {
        if (isProviderSearchRail || supportsGlobalTmdbFilters) {
            providerSeeAllYearRanges()
        } else {
            filterSourceItems.mapNotNull { it.year }
                .filter { it in 1900..2100 }
                .distinct()
                .sortedDescending()
                .take(TV_SEE_ALL_FILTER_YEAR_LIMIT)
                .ifEmpty { defaultSeeAllYears() }
                .map { year -> TvSeeAllYearRange(year, year, year, year.toString()) }
        }
    }
    val selectedYearRanges = remember(availableYearRanges, selectedYearRangeIds) {
        availableYearRanges.filter { it.id in selectedYearRangeIds }
    }
    val availableRatingThresholds = listOf(7.0, 8.0, 9.0)
    val displayedItems = remember(
        loadedItems,
        selectedSortKey,
        selectedGenreIds,
        selectedStudioIds,
        selectedYearRanges,
        selectedMinRating,
        titleQuery,
        usesGlobalTmdbQuery,
    ) {
        if (usesGlobalTmdbQuery) {
            loadedItems
        } else {
            val normalizedTitleQuery = titleQuery.trim()
            sortSeeAllItems(
                items = loadedItems
                    .filterSeeAllItems(
                        genreIds = selectedGenreIds,
                        studioIds = selectedStudioIds,
                        yearRanges = selectedYearRanges.map { it.startYear..it.endYear },
                        minRating = selectedMinRating,
                    )
                    .filter { item ->
                        normalizedTitleQuery.isBlank() ||
                            item.title.contains(normalizedTitleQuery, ignoreCase = true) ||
                            item.overview.orEmpty().contains(normalizedTitleQuery, ignoreCase = true)
                    },
                sortKey = selectedSortKey,
            )
        }
    }
    val renderedItems = remember(
        displayedItems,
        loadedItems,
        selectedSortKey,
        hasActiveFilters,
        isProviderSearchRail,
        usesGlobalTmdbQuery,
        loading,
    ) {
        if (!isProviderSearchRail && !usesGlobalTmdbQuery && hasActiveFilters && displayedItems.isEmpty() && loadedItems.isNotEmpty() && loading) {
            sortSeeAllItems(loadedItems, selectedSortKey)
        } else {
            displayedItems
        }
    }
    var initialFocusHandled by remember(railKey, mediaType) { mutableStateOf(false) }
    var focusedMediaItem by remember { mutableStateOf<MediaItem?>(null) }
    val enrichedSeeAllItemsByKey = remember(railKey, mediaType) { mutableStateMapOf<String, MediaItem>() }
    var lastFocusedIndex by remember { mutableIntStateOf(-1) }
    var posterBackTargetsFilters by remember(railKey, mediaType) { mutableStateOf(false) }
    var lastFocusedKey by remember { mutableStateOf<String?>(null) }
    var pendingGridRestoreKey by remember { mutableStateOf<String?>(null) }
    var pendingGridRestoreNonce by remember { mutableIntStateOf(0) }
    val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    var ratingEnrichmentAttemptedKeys by remember(railKey, mediaType) { mutableStateOf<Set<String>>(emptySet()) }
    val screenId = remember(railKey, mediaType) { "see_all:$railKey:$mediaType" }
    val filterSignature = remember(selectedGenreIds, selectedStudioIds, selectedYearRangeIds, selectedMinRating, selectedSortKey, titleQuery) {
        "${selectedGenreIds.sorted()}|${selectedStudioIds.sorted()}|${selectedYearRangeIds.sorted()}|${selectedMinRating ?: "all"}|${titleQuery.trim()}|$selectedSortKey"
    }
    val providerQuerySignature = remember(
        providerContentType,
        selectedProviderRegions,
        selectedGenreIds,
        selectedYearRangeIds,
        selectedMinRating,
        selectedSortKey,
        titleQuery,
        providerRegionQueries,
    ) {
        "$providerContentType|${selectedProviderRegions.sorted()}|${selectedGenreIds.sorted()}|" +
            "${selectedYearRangeIds.sorted()}|${selectedMinRating ?: "all"}|$selectedSortKey|" +
            "${titleQuery.trim().lowercase()}|${providerRegionQueries.entries.sortedBy { it.key }}"
    }
    val catalogQuerySignature = remember(supportsGlobalTmdbFilters, filterSignature) {
        if (supportsGlobalTmdbFilters) filterSignature else "scoped"
    }
    val cacheKey = remember(railKey, mediaType, providerQuerySignature, catalogQuerySignature) {
        when {
            isProviderSearchRail -> "$railKey|$providerQuerySignature"
            supportsGlobalTmdbFilters -> "$railKey|$mediaType|$catalogQuerySignature"
            else -> "$railKey|$mediaType"
        }
    }
    var filterBackfillAttempts by remember(filterSignature) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val posterColumns = rememberTvSeeAllPosterColumnsPreference()

    DisposableEffect(registerFocusHandle, focusRestoreController, screenId) {
        registerFocusHandle?.invoke(
            TvScreenFocusHandle(
                captureFocusedOrigin = {
                    focusRestoreController.captureFocusedOrigin(
                        screenId = screenId,
                    )
                },
                requestRestore = { origin, reason ->
                    pendingGridRestoreKey = origin.itemKey
                    pendingGridRestoreNonce += 1
                    focusRestoreController.requestRestore(origin = origin, reason = reason)
                },
                isOriginFocused = focusRestoreController::isOriginFocused,
            ),
        )
        onDispose {
            registerFocusHandle?.invoke(null)
        }
    }

    BackHandler(onBack = {
        if (showSortOptions) {
            showSortOptions = false
            restoreSortToggleNonce++
            return@BackHandler
        }
        if (showFilters) {
            showFilters = false
            restoreFocusAfterClearNonce++
            return@BackHandler
        }
        if (isStreamingCatalogRail && posterBackTargetsFilters) {
            posterBackTargetsFilters = false
            runCatching { filterToggleRequester.requestFocus() }
            onContentFocused(filterToggleRequester)
            return@BackHandler
        }
        lastFocusedIndex = -1  // Clear so we don't restore when exiting See All itself
        lastFocusedKey = null
        pendingGridRestoreKey = null
        focusedMediaItem = null
        onBack()
    })

    LaunchedEffect(isProviderSearchRail, railKey, providerContentType) {
        if (!isProviderSearchRail) return@LaunchedEffect
        val defaultRegion = prefsRepo.getString(SettingsViewModel.KEY_REGION_CODE)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.length == 2 }
            ?: "US"
        providerDefaultRegion = defaultRegion
        if (isStreamingCatalogRail) {
            val providerId = configuredProviderId ?: return@LaunchedEffect
            val candidates = runCatching {
                metadataRepo.getWatchProviderCandidates(providerContentType)
            }.getOrDefault(emptyList())
            val resolvedIds = resolveStreamingProviderIds(
                requestedName = providerDisplayName,
                configuredProviderId = providerId,
                region = defaultRegion,
                availableProviders = candidates,
            ).toSet()
            val queries = buildMap<String, MutableSet<Int>> {
                candidates.filter { it.id in resolvedIds }.forEach { candidate ->
                    candidate.regions.forEach { region ->
                        getOrPut(region) { linkedSetOf() }.add(candidate.id)
                    }
                }
            }.mapValues { (_, ids) -> ids.sorted().joinToString("|") }
            providerRegionQueries = queries.ifEmpty { mapOf(defaultRegion to providerId.toString()) }
            selectedProviderRegions = providerRegionQueries.keys
        } else if (selectedProviderRegions.isEmpty()) {
            selectedProviderRegions = setOf(defaultRegion)
        }
    }

    fun rememberBaseFilterOptions() {
        if (!isPersonCreditsRail) {
            filterOptionItems.clear()
            filterOptionItems.addAll(items)
        }
    }

    fun persistSeeAllCache() {
        rememberBaseFilterOptions()
        TvSeeAllCache.put(
            cacheKey,
            TvSeeAllCacheEntry(
                items = items.toList(),
                currentPage = currentPage,
                totalPages = totalPages,
                personPanelInfo = personPanelInfo,
            ),
        )
    }

    fun recordSeeAllEnrichedItems(enrichedItems: Collection<MediaItem>) {
        enrichedItems.forEach { item ->
            val key = item.seeAllStableKey()
            val merged = enrichedSeeAllItemsByKey[key]?.mergeSeeAllEnrichedItem(item) ?: item
            enrichedSeeAllItemsByKey[key] = merged
            cacheTvBrowsePreviewEnrichedItem(merged)
        }
    }

    fun updateFocusedItemFromReplacements(replacements: Map<String, MediaItem>) {
        val focused = focusedMediaItem ?: return
        val replacement = replacements[focused.seeAllStableKey()] ?: return
        val merged = focused.mergeSeeAllEnrichedItem(replacement)
        if (merged != focused) {
            focusedMediaItem = merged
            cacheTvBrowsePreviewEnrichedItem(merged)
            Log.d(TV_SEE_ALL_LOG_TAG, "see_all_focused_item_enriched_update key=${merged.seeAllStableKey()}")
        }
    }

    val shouldLoadMore by remember(loading, currentPage, totalPages, renderedItems.size) {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            !loading &&
                currentPage < totalPages &&
                renderedItems.isNotEmpty() &&
                lastVisibleIndex >= totalItems - 10
        }
    }

    fun queryMediaType(): String =
        mediaType.takeIf { it == "movie" || it == "tv" }
            ?: when {
                railKey.contains("_tv") || railKey.endsWith("-tv") -> "tv"
                else -> "movie"
            }

    suspend fun fetchRailPage(page: Int): PagedResult {
        val queryType = queryMediaType()
        val result = runCatching {
            when {
                railKey == "trending-movies" -> metadataRepo.getTrendingPaged("movie", page)
                railKey == "trending-tv" -> metadataRepo.getTrendingPaged("tv", page)
                railKey == "popular-movies" -> metadataRepo.getPopularPaged("movie", page)
                railKey == "popular-tv" -> metadataRepo.getPopularPaged("tv", page)
                railKey == "top-rated" -> metadataRepo.getTopRatedPaged("movie", page)
                railKey.startsWith("trending_") -> metadataRepo.getTrendingPaged(queryType, page)
                railKey.startsWith("popular_") -> metadataRepo.getPopularPaged(queryType, page)
                railKey.startsWith("top_rated_") -> metadataRepo.getTopRatedPaged(queryType, page)
                railKey.startsWith("genre_") -> {
                    val genreId = railKey.substringAfterLast("_")
                    metadataRepo.discover(type = queryType, page = page, withGenres = genreId)
                }
                railKey == "recommended" -> {
                    val pending = SeeAllViewModel.pendingItems[railKey]?.second.orEmpty()
                    PagedResult(
                        items = pending,
                        page = 1,
                        totalPages = 1,
                        totalResults = pending.size,
                    )
                }
                else -> {
                    val pending = SeeAllViewModel.pendingItems[railKey]?.second.orEmpty()
                    PagedResult(
                        items = pending,
                        page = 1,
                        totalPages = 1,
                        totalResults = pending.size,
                    )
                }
            }
        }.getOrElse { error ->
            Log.w(
                TV_SEE_ALL_LOG_TAG,
                "source scoped page failed rail=$railKey mediaType=$queryType page=$page error=${error::class.simpleName}",
            )
            PagedResult(items = emptyList(), page = page, totalPages = page, totalResults = 0)
        }
        return result
    }

    suspend fun loadPage(page: Int, replace: Boolean = false) {
        if (loading || page > totalPages) return
        loading = true
        try {
            SeeAllViewModel.pendingItems[railKey]
                ?.takeIf { shouldUsePendingItemsForTvSeeAll(railKey) }
                ?.let { (_, pendingItems) ->
                if (page == 1) {
                    items.clear()
                    items.addAll(pendingItems)
                }
                totalPages = 1
                currentPage = 1
                loading = false
                initialLoad = false
                persistSeeAllCache()
                return
            }

            if (isPersonCreditsRail) {
                val personId = railKey.removePrefix("person_credits_").toIntOrNull()
                if (personId != null && page == 1) {
                    personPanelInfo = TvPersonPanelInfo(name = title)
                    runCatching { metadataRepo.getPersonDetail(personId) }
                        .getOrNull()
                        ?.let { person ->
                            personPanelInfo = TvPersonPanelInfo(
                                name = person.name.ifBlank { title },
                                profileUrl = TmdbMappers.profileUrl(person.profilePath, size = "w342"),
                                imageUrls = (
                                    listOfNotNull(TmdbMappers.profileUrl(person.profilePath, size = "w342")) +
                                        runCatching { metadataRepo.getPersonImageUrls(personId) }
                                            .getOrDefault(emptyList())
                                    ).distinct(),
                                biography = person.biography,
                                knownFor = person.knownForDepartment.orEmpty(),
                                birthday = person.birthday,
                                placeOfBirth = person.placeOfBirth,
                            )
                        }
                    val credits = metadataRepo.getPersonCredits(personId)
                    items.addAll(credits)
                }
                totalPages = 1
                currentPage = 1
                loading = false
                initialLoad = false
                persistSeeAllCache()
                return
            }
            if (usesGlobalTmdbQuery) {
                val queryType = queryMediaType()
                val categoryGenreId = railKey
                    .takeIf { it.startsWith("genre_") }
                    ?.substringAfterLast("_")
                    ?.toIntOrNull()
                val genreQuery = buildTmdbGenreQuery(categoryGenreId, selectedGenreIds)
                val yearPlans: List<TvSeeAllYearRange?> = selectedYearRanges
                    .takeIf { it.isNotEmpty() }
                    ?.map { it }
                    ?: listOf(null)
                val effectiveMinRating = when {
                    railKey == "hidden_gems" -> maxOf(7.5, selectedMinRating ?: 7.5)
                    else -> selectedMinRating
                }
                val results = coroutineScope {
                    yearPlans.map { yearRange ->
                        async {
                            metadataRepo.discover(
                                type = queryType,
                                page = page,
                                sortBy = providerDiscoverSortBy(queryType, selectedSortKey),
                                withGenres = genreQuery,
                                minRating = effectiveMinRating?.toFloat(),
                                year = yearRange?.startYear,
                                yearTo = yearRange?.endYear,
                                withCompanies = selectedStudioIds
                                    .takeIf { it.isNotEmpty() }
                                    ?.sorted()
                                    ?.joinToString("|"),
                            )
                        }
                    }.map { it.await() }
                }
                if (replace || page == 1) items.clear()
                val existingKeys = items.mapTo(mutableSetOf()) { it.seeAllStableKey() }
                val pageItems = results
                    .flatMap { it.items }
                    .distinctBy { it.seeAllStableKey() }
                items.addAll(pageItems.filter { existingKeys.add(it.seeAllStableKey()) })
                currentPage = page
                totalPages = results.maxOfOrNull { it.totalPages } ?: page
                persistSeeAllCache()
                return
            }
            if (railKey.startsWith("continue_watching")) {
                val targetType = when (railKey) {
                    "continue_watching_movie" -> MediaType.MOVIE
                    "continue_watching_tv" -> MediaType.SERIES
                    else -> null
                }
                val overlayItems = try {
                    libraryOverlayService.getContinueWatching(200)
                } catch (_: Throwable) {
                    emptyList()
                }
                val mergedProgress = (watchProgressRepo.getInProgress(200) + overlayItems)
                    .groupBy { "${it.mediaType.name}:${it.mediaId}" }
                    .mapNotNull { (_, entries) -> entries.maxByOrNull { it.updatedAt } }
                    .sortedByDescending { it.updatedAt }
                val baseItems = mergedProgress
                    .asSequence()
                    .filter { targetType == null || it.mediaType == targetType }
                    .mapNotNull { it.toMediaItemOrNull() }
                    .filter { it.tmdbId != null }
                    .distinctBy { it.seeAllStableKey() }
                    .toList()
                val mergedItems = coroutineScope {
                    baseItems.map { item ->
                        async {
                            val tmdbId = item.tmdbId ?: return@async item
                            val detailType = if (item.type == MediaType.SERIES) "tv" else "movie"
                            val detail = runCatching { metadataRepo.getDetail(detailType, tmdbId) }.getOrNull()
                            detail
                                ?.copy(
                                    id = item.id,
                                    posterUrl = item.posterUrl ?: detail.posterUrl,
                                    backdropUrl = item.backdropUrl ?: detail.backdropUrl,
                                    logoUrl = item.logoUrl ?: detail.logoUrl,
                                )
                                ?: item
                        }
                    }.map { it.await() }
                }
                items.clear()
                items.addAll(mergedItems)
                totalPages = 1
                currentPage = 1
                loading = false
                initialLoad = false
                persistSeeAllCache()
                return
            }
            if (railKey == "upcoming") {
                val today = java.time.LocalDate.now().toString()
                val pageItems = metadataRepo.getUpcoming(page)
                    .filter { item -> item.releaseDate?.take(10)?.let { it >= today } == true }
                val existingKeys = items.mapTo(mutableSetOf()) { it.seeAllStableKey() }
                items.addAll(pageItems.filter { existingKeys.add(it.seeAllStableKey()) })
                currentPage = page
                totalPages = if (pageItems.size < 20) page else Int.MAX_VALUE
                loading = false
                initialLoad = false
                persistSeeAllCache()
                return
            }
            if (railKey == "now-playing") {
                val pageItems = metadataRepo.getNowPlaying(page)
                val existingKeys = items.mapTo(mutableSetOf()) { it.seeAllStableKey() }
                items.addAll(pageItems.filter { existingKeys.add(it.seeAllStableKey()) })
                currentPage = page
                totalPages = if (pageItems.size < 20) page else Int.MAX_VALUE
                loading = false
                initialLoad = false
                persistSeeAllCache()
                return
            }
            if (railKey == "hidden_gems") {
                val result = metadataRepo.discover(
                    type = mediaType.takeIf { it == "movie" || it == "tv" } ?: "movie",
                    sortBy = "vote_average.desc",
                    minRating = 7.5f,
                    page = page,
                )
                val existingKeys = items.mapTo(mutableSetOf()) { it.seeAllStableKey() }
                items.addAll(result.items.filter { existingKeys.add(it.seeAllStableKey()) })
                currentPage = page
                totalPages = result.totalPages
                loading = false
                initialLoad = false
                persistSeeAllCache()
                return
            }
            if (isProviderSearchRail) {
                val providerId = configuredProviderId
                if (providerId == null) {
                    loading = false
                    return
                }
                if (isStreamingCatalogRail && titleQuery.isNotBlank()) {
                    val searchPage = metadataRepo.searchMultiPaged(
                        query = titleQuery.trim(),
                        page = page,
                        type = providerContentType,
                    )
                    val pageItems = coroutineScope {
                        searchPage.items.map { item ->
                            async {
                                val tmdbId = item.tmdbId ?: return@async null
                                val itemType = if (item.type == MediaType.SERIES) "tv" else "movie"
                                val titleProviders = runCatching {
                                    metadataRepo.getTitleWatchProviderCandidates(itemType, tmdbId)
                                }.getOrDefault(emptyList())
                                val resolvedIds = resolveStreamingProviderIds(
                                    requestedName = providerDisplayName,
                                    configuredProviderId = providerId,
                                    region = providerDefaultRegion ?: "US",
                                    availableProviders = titleProviders,
                                )
                                val fixedCategoryGenre = railKey.substringAfterLast("_genre_", missingDelimiterValue = "")
                                    .takeIf { "_genre_" in railKey }
                                    ?.toIntOrNull()
                                item.takeIf { candidate ->
                                    titleProviders.any { it.id in resolvedIds } &&
                                        listOf(candidate).filterSeeAllItems(
                                            genreIds = selectedGenreIds + listOfNotNull(fixedCategoryGenre),
                                            studioIds = selectedStudioIds,
                                            yearRanges = selectedYearRanges.map { it.startYear..it.endYear },
                                            minRating = selectedMinRating,
                                        ).isNotEmpty()
                                }
                            }
                        }.mapNotNull { it.await() }
                    }
                    if (replace || page == 1) items.clear()
                    val existingKeys = items.mapTo(mutableSetOf()) { it.seeAllStableKey() }
                    items.addAll(pageItems.filter { existingKeys.add(it.seeAllStableKey()) })
                    currentPage = page
                    totalPages = searchPage.totalPages
                    loading = false
                    initialLoad = false
                    persistSeeAllCache()
                    return
                }

                val allProviderQueries = if (isStreamingCatalogRail) {
                    providerRegionQueries
                } else {
                    val watchRegions = selectedProviderRegions.ifEmpty { setOf(providerDefaultRegion ?: "US") }.sorted()
                    watchRegions.associateWith { watchRegion ->
                        val providerNames = runCatching {
                            metadataRepo.getWatchProviderNames(providerContentType, watchRegion)
                        }.getOrDefault(emptyMap())
                        resolveStreamingProviderIds(
                            requestedName = providerDisplayName,
                            configuredProviderId = providerId,
                            region = watchRegion,
                            availableProviders = providerNames.map { (id, name) ->
                                StreamingProviderCandidate(id = id, name = name)
                            },
                        ).filter { it > 0 }.joinToString("|").ifBlank { providerId.toString() }
                    }
                }
                val orderedQueries = allProviderQueries.entries.sortedWith(
                    compareBy<Map.Entry<String, String>> { it.key != providerDefaultRegion }.thenBy { it.key },
                )
                val queryBatches = orderedQueries.chunked(TV_PROVIDER_WORLDWIDE_REGION_BATCH_SIZE)
                if (queryBatches.isEmpty()) {
                    loading = false
                    return
                }
                val batchIndex = if (isStreamingCatalogRail) (page - 1) % queryBatches.size else 0
                val discoverPage = if (isStreamingCatalogRail) (page - 1) / queryBatches.size + 1 else page
                val providerQueries = queryBatches[batchIndex].associate { it.key to it.value }
                val yearPlans: List<TvSeeAllYearRange?> = selectedYearRanges
                    .takeIf { it.isNotEmpty() }
                    ?.map { it }
                    ?: listOf(null)
                val categoryGenreId = railKey.substringAfterLast("_genre_", missingDelimiterValue = "")
                    .takeIf { "_genre_" in railKey }
                    ?.toIntOrNull()
                val effectiveGenreIds = (selectedGenreIds + listOfNotNull(categoryGenreId)).sorted()
                val effectiveMinRating = selectedMinRating
                    ?: 7.0.takeIf { railKey.endsWith("_top_rated") }
                val effectiveSort = when {
                    railKey.endsWith("_top_rated") && selectedSortKey == TvSeeAllSortKey.DEFAULT -> "vote_average.desc"
                    railKey.endsWith("_recent") && selectedSortKey == TvSeeAllSortKey.DEFAULT -> {
                        if (providerContentType == "tv") "first_air_date.desc" else "primary_release_date.desc"
                    }
                    else -> providerDiscoverSortBy(providerContentType, selectedSortKey)
                }
                Log.d(
                    "TvProviderDiscovery",
                    "provider=$providerDisplayName type=$providerContentType regions=${providerQueries.keys} page=$discoverPage",
                )
                val results = coroutineScope {
                    providerQueries.flatMap { (watchRegion, providerQuery) ->
                        yearPlans.map { yearRange ->
                            async {
                                runCatching {
                                    metadataRepo.discover(
                                        type = providerContentType,
                                        page = discoverPage,
                                        sortBy = effectiveSort,
                                        withGenres = effectiveGenreIds.takeIf { it.isNotEmpty() }
                                            ?.joinToString("|"),
                                        minRating = effectiveMinRating?.toFloat(),
                                        year = yearRange?.startYear,
                                        yearTo = yearRange?.endYear,
                                        withWatchProviders = providerQuery,
                                        watchRegion = watchRegion,
                                    )
                                }.getOrElse {
                                    PagedResult(emptyList(), discoverPage, discoverPage, 0)
                                }
                            }
                        }
                    }.map { it.await() }
                }
                val pageItems = results.flatMap { it.items }.distinctBy { it.seeAllStableKey() }
                val existingKeys = items.mapTo(mutableSetOf()) { it.seeAllStableKey() }
                items.addAll(pageItems.filter { existingKeys.add(it.seeAllStableKey()) })
                currentPage = page
                totalPages = if (isStreamingCatalogRail) {
                    (results.maxOfOrNull { it.totalPages } ?: discoverPage) * queryBatches.size
                } else {
                    results.maxOfOrNull { it.totalPages } ?: page
                }
                loading = false
                initialLoad = false
                persistSeeAllCache()
                return
            }
            val result = when {
                railKey.startsWith("more_like_") -> {
                    val parts = railKey.split("_")
                    val relatedType = parts.getOrNull(2)?.takeIf { it == "movie" || it == "tv" } ?: mediaType
                    val relatedId = parts.getOrNull(3)?.toIntOrNull()
                    if (relatedId == null) {
                        loading = false
                        return
                    }
                    val (recommendations, similar) = coroutineScope {
                        val recommendationsDeferred = async {
                            runCatching {
                                metadataRepo.getRecommendations(relatedType, relatedId, page)
                            }.getOrDefault(emptyList())
                        }
                        val similarDeferred = async {
                            runCatching {
                                metadataRepo.getSimilar(relatedType, relatedId, page)
                            }.getOrDefault(emptyList())
                        }
                        recommendationsDeferred.await() to similarDeferred.await()
                    }
                    val relatedItems = mergeMoreLikeCandidates(
                        seedTmdbId = relatedId,
                        recommendations = recommendations,
                        similar = similar,
                    )
                    val existingKeys = items.mapTo(mutableSetOf()) { it.seeAllStableKey() }
                    items.addAll(relatedItems.filter { existingKeys.add(it.seeAllStableKey()) })
                    currentPage = page
                    totalPages = if (recommendations.size < 20 && similar.size < 20) page else Int.MAX_VALUE
                    loading = false
                    initialLoad = false
                    persistSeeAllCache()
                    return
                }
                railKey == "recommended" -> fetchRailPage(page)
                railKey.startsWith("watchlist_") -> {
                    loading = false
                    return
                }
                else -> fetchRailPage(page)
            }
            totalPages = result.totalPages
            val existingKeys = items.mapTo(mutableSetOf()) { it.seeAllStableKey() }
            items.addAll(result.items.filter { existingKeys.add(it.seeAllStableKey()) })
            currentPage = page
            persistSeeAllCache()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.w(
                TV_SEE_ALL_LOG_TAG,
                "loadPage failed rail=$railKey mediaType=$mediaType page=$page replace=$replace: ${error::class.simpleName}",
            )
        } finally {
            loading = false
            initialLoad = false
        }
    }

    LaunchedEffect(railKey, mediaType, providerQuerySignature, catalogQuerySignature) {
        if (isProviderSearchRail && providerDefaultRegion == null) return@LaunchedEffect
        if (isProviderDiscoveryRail && selectedProviderRegions.isEmpty()) return@LaunchedEffect
        if (isStreamingCatalogRail && providerRegionQueries.isEmpty()) return@LaunchedEffect
        val cached = TvSeeAllCache.get(cacheKey)
        if (cached != null && shouldRestoreTvSeeAllCache(railKey, cached)) {
            items.clear()
            items.addAll(cached.items)
            filterOptionItems.clear()
            filterOptionItems.addAll(cached.items)
            currentPage = cached.currentPage
            totalPages = cached.totalPages
            loading = false
            initialLoad = false
            personPanelInfo = cached.personPanelInfo
            initialFocusHandled = providerControlsInteracted
            return@LaunchedEffect
        }
        items.clear()
        filterOptionItems.clear()
        currentPage = 0
        totalPages = Int.MAX_VALUE
        loading = false
        initialLoad = true
        personPanelInfo = null
        initialFocusHandled = providerControlsInteracted
        val initialTargetCount = if (isProviderSearchRail) 40 else TV_SEE_ALL_INITIAL_TARGET_COUNT
        while (items.size < initialTargetCount && currentPage < totalPages) {
            loadPage(currentPage + 1)
            if (totalPages == 1) break
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            loadPage(currentPage + 1)
        }
    }

    LaunchedEffect(hasActiveFilters, renderedItems.size, currentPage, totalPages, filterBackfillAttempts) {
        if (!hasActiveFilters || loading || currentPage >= totalPages) return@LaunchedEffect
        if (renderedItems.size >= TV_SEE_ALL_FILTER_RESULT_TARGET_COUNT) return@LaunchedEffect
        if (filterBackfillAttempts >= 3) return@LaunchedEffect
        filterBackfillAttempts++
        loadPage(currentPage + 1)
    }

    LaunchedEffect(loadedItems) {
        if (loadedItems.isEmpty()) return@LaunchedEffect
        val hydrated = withContext(Dispatchers.IO) {
            ratingsEnricher.hydrateListFromCache(loadedItems)
        }
        val replacements = loadedItems.zip(hydrated)
            .mapNotNull { (before, after) ->
                val merged = before.mergeSeeAllEnrichedItem(after)
                if (merged != before) before.seeAllStableKey() to merged else null
            }
            .toMap()
        if (replacements.isEmpty()) {
            Log.d(TV_SEE_ALL_LOG_TAG, "see_all_ratings_cache_hydrate_miss items=${loadedItems.size}")
            return@LaunchedEffect
        }
        Log.d(
            TV_SEE_ALL_LOG_TAG,
            "see_all_ratings_cache_hydrate_hit items=${loadedItems.size} hydrated=${replacements.size}",
        )
        recordSeeAllEnrichedItems(replacements.values)
        val changed = items.replaceSeeAllItemsByKey(replacements)
        updateFocusedItemFromReplacements(replacements)
        if (changed) persistSeeAllCache()
    }

    LaunchedEffect(loadedItems, focusedMediaItem?.seeAllStableKey()) {
        val focused = focusedMediaItem ?: return@LaunchedEffect
        val replacement = loadedItems.firstOrNull { it.seeAllStableKey() == focused.seeAllStableKey() }
            ?: return@LaunchedEffect
        val merged = focused.mergeSeeAllEnrichedItem(replacement)
        if (merged != focused) {
            focusedMediaItem = merged
            cacheTvBrowsePreviewEnrichedItem(merged)
            Log.d(TV_SEE_ALL_LOG_TAG, "see_all_focused_item_enriched_update key=${merged.seeAllStableKey()}")
        }
    }

    // Background ratings enrichment — populate SQLite cache + update the visible
    // window in place. TMDB fallback is display-only; it does not make a card
    // "enriched enough", so newly paged/visible TMDB-only cards are still queued.
    LaunchedEffect(renderedItems, gridState.firstVisibleItemIndex, posterColumns) {
        if (renderedItems.isEmpty()) return@LaunchedEffect
        val layoutInfo = gridState.layoutInfo
        val firstVisible = layoutInfo.visibleItemsInfo.minOfOrNull { it.index }
            ?: gridState.firstVisibleItemIndex
        val visibleCount = layoutInfo.visibleItemsInfo.size.takeIf { it > 0 }
            ?: (posterColumns * 3)
        val start = firstVisible.coerceIn(0, renderedItems.lastIndex)
        val end = (start + visibleCount + posterColumns * 2).coerceAtMost(renderedItems.size)
        if (start >= end) return@LaunchedEffect

        TvImagePrefetcher.prefetchMediaItems(
            context = context,
            screenName = "tv_see_all",
            items = renderedItems.subList(start, end),
            maxImages = 36,
            includeHeroCandidates = true,
        )

        val snapshot = renderedItems.subList(start, end)
            .filter { it.needsExternalRatingEnrichment() }
            .filterNot { it.seeAllStableKey() in ratingEnrichmentAttemptedKeys }
            .take(48)
        if (snapshot.isEmpty()) return@LaunchedEffect

        val originalKeys = snapshot.map { it.seeAllStableKey() }.toSet()
        ratingEnrichmentAttemptedKeys = ratingEnrichmentAttemptedKeys + originalKeys
        Log.d(TV_SEE_ALL_LOG_TAG, "see_all_visible_enrichment_started items=${snapshot.size}")

        launch(Dispatchers.IO) {
            val apiKey = runCatching {
                secretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            }.getOrDefault(MdbListApi.DEFAULT_API_KEY)
            val remainingMs = ratingsEnricher.rateLimitRemainingMs()
            if (remainingMs > 0L) delay(remainingMs + 2_000L)
            // Provider discovery promises the same IMDb-enriched browsing
            // experience as Search. Use the focused IMDb path here: it
            // hydrates cache first and then fetches only the fields the card
            // and preview need, while the already-rendered poster grid stays
            // available and stable.
            val enriched = if (isProviderSearchRail) {
                ratingsEnricher.enrichImdbList(snapshot, apiKey)
            } else {
                ratingsEnricher.enrichList(snapshot, apiKey)
            }
            val enrichedByOriginalKey = snapshot.zip(enriched).associate { (before, after) ->
                before.seeAllStableKey() to before.mergeSeeAllEnrichedItem(after)
            }
            withContext(Dispatchers.Main) {
                recordSeeAllEnrichedItems(enrichedByOriginalKey.values)
                items.replaceSeeAllItemsByKey(enrichedByOriginalKey)
                updateFocusedItemFromReplacements(enrichedByOriginalKey)
                persistSeeAllCache()
                Log.d(
                    TV_SEE_ALL_LOG_TAG,
                    "see_all_visible_enrichment_completed items=${enrichedByOriginalKey.size}",
                )
            }
        }
    }

    LaunchedEffect(showFilters, items.size) {
        if (!showFilters || items.isEmpty()) return@LaunchedEffect
        val targets = items
            .filter { it.tmdbId != null && (it.genres.isEmpty() || it.studios.isEmpty()) }
            .take(TV_SEE_ALL_FILTER_METADATA_LIMIT)
        if (targets.isEmpty()) return@LaunchedEffect
        launch(Dispatchers.IO) {
            val hydrated = targets.mapNotNull { item ->
                val tmdbId = item.tmdbId ?: return@mapNotNull null
                val type = if (item.type == MediaType.SERIES) "tv" else "movie"
                val detail = runCatching { metadataRepo.getDetail(type, tmdbId) }.getOrNull()
                    ?: return@mapNotNull null
                item.seeAllStableKey() to item.copy(
                    imdbId = item.imdbId ?: detail.imdbId,
                    genres = item.genres.ifEmpty { detail.genres },
                    genreIds = item.genreIds.ifEmpty { detail.genreIds },
                    studios = item.studios.ifEmpty { detail.studios },
                    rating = item.rating ?: detail.rating,
                    ratings = item.ratings.preferSeeAllRichRatings(detail.ratings),
                    year = item.year ?: detail.year,
                    releaseDate = item.releaseDate ?: detail.releaseDate,
                    posterUrl = item.posterUrl ?: detail.posterUrl,
                    backdropUrl = item.backdropUrl ?: detail.backdropUrl,
                    logoUrl = item.logoUrl ?: detail.logoUrl,
                )
            }.toMap()
            if (hydrated.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val merged = hydrated.mapValues { (key, replacement) ->
                    items.firstOrNull { it.seeAllStableKey() == key }
                        ?.mergeSeeAllEnrichedItem(replacement)
                        ?: replacement
                }
                recordSeeAllEnrichedItems(merged.values)
                items.replaceSeeAllItemsByKey(merged)
                updateFocusedItemFromReplacements(merged)
                persistSeeAllCache()
            }
        }
    }

    LaunchedEffect(sortOptions, selectedSortIndex) {
        if (sortOptions.isNotEmpty()) {
            onFirstContentRequester(if (isStreamingCatalogRail) catalogSearchRequester else firstItemFocusRequester)
        }
    }

    LaunchedEffect(selectedSortKey) {
        if (gridState.firstVisibleItemIndex != 0) {
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(focusRestoreController.pendingRestore?.restoreToken) {
        focusRestoreController.restorePendingFocus(
            screenId = screenId,
        )
    }

    LaunchedEffect(pendingGridRestoreNonce, renderedItems.size, posterColumns) {
        val restoreKey = pendingGridRestoreKey ?: return@LaunchedEffect
        if (pendingGridRestoreNonce <= 0) return@LaunchedEffect
        val targetIndex = renderedItems.indexOfFirst { it.seeAllStableKey() == restoreKey }
        if (targetIndex < 0) return@LaunchedEffect

        repeat(12) {
            runCatching { gridState.scrollToItem(targetIndex) }
            withFrameNanos { }
            delay(40L)

            val requester = focusRequesters[restoreKey]
            if (requester != null) {
                runCatching { requester.requestFocus() }
                withFrameNanos { }
                if (lastFocusedKey == restoreKey) {
                    pendingGridRestoreKey = null
                    return@LaunchedEffect
                }
            }
        }
    }

    // Restore focus to last focused item when returning from Details sub-route.
    // Uses Lifecycle ON_RESUME to detect when this composable's NavBackStackEntry
    // becomes the active destination again after Details is popped.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && lastFocusedIndex >= 0) {
                val restoreKey = lastFocusedKey ?: renderedItems.getOrNull(lastFocusedIndex)?.seeAllStableKey()
                val requester = restoreKey?.let { focusRequesters[it] }
                if (requester != null) {
                    try { requester.requestFocus() } catch (_: Throwable) { }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-focus once content loads so D-pad works immediately.
    LaunchedEffect(initialLoad, renderedItems.size, sortOptions.size, selectedSortIndex) {
        if (!initialLoad && !initialFocusHandled && (sortOptions.isNotEmpty() || renderedItems.isNotEmpty())) {
            try {
                if (isStreamingCatalogRail) {
                    catalogSearchRequester.requestFocus()
                } else if (renderedItems.isNotEmpty()) {
                    firstItemFocusRequester.requestFocus()
                } else if (sortOptions.isNotEmpty()) {
                    sortToggleRequester.requestFocus()
                }
                initialFocusHandled = true
            } catch (_: IllegalStateException) { /* not yet attached */ }
        }
    }

    var backgroundMediaItem by remember(railKey, mediaType) { mutableStateOf<MediaItem?>(null) }
    val requestedBackgroundItem = focusedMediaItem ?: renderedItems.firstOrNull()
    LaunchedEffect(requestedBackgroundItem?.seeAllStableKey()) {
        if (requestedBackgroundItem == null) {
            backgroundMediaItem = null
            return@LaunchedEffect
        }
        delay(450L)
        backgroundMediaItem = requestedBackgroundItem
    }
    val cinematicBackgroundUrl = (backgroundMediaItem ?: renderedItems.firstOrNull())
        ?.let { it.backdropUrl?.takeIf { url -> url.isNotBlank() } ?: it.posterUrl?.takeIf { url -> url.isNotBlank() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian),
    ) {
        if (!cinematicBackgroundUrl.isNullOrBlank()) {
            Crossfade(
                targetState = cinematicBackgroundUrl,
                label = "seeAllCinematicBackground",
            ) { imageUrl ->
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(1.06f),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Graphite.copy(alpha = 0.34f),
                            Obsidian.copy(alpha = 0.78f),
                            Color.Black.copy(alpha = 0.96f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.52f),
                            Obsidian.copy(alpha = 0.84f),
                            Color.Black.copy(alpha = 0.98f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = TV_PAGE_CONTENT_GUTTER, top = TV_PAGE_TOP_GUTTER, end = TV_PAGE_END_GUTTER, bottom = TV_PAGE_BOTTOM_GUTTER),
        ) {
        val firstFilterRequester = remember(screenId, "first_filter") { FocusRequester() }
        val clearRequester = remember(screenId, "clear_filters") { FocusRequester() }
        val posterColumnsRequester = remember(screenId, "poster_columns") { FocusRequester() }
        val providerMovieRequester = remember(screenId, "provider_movie") { FocusRequester() }
        val providerSeriesRequester = remember(screenId, "provider_series") { FocusRequester() }

        // Provider identity and its deliberately compact search control share one header row.
        if (isProviderSearchRail) {
            val providerService = remember(configuredProviderId, title) {
                ALL_STREAMING_SERVICES.firstOrNull { it.tmdbProviderId == configuredProviderId }
                    ?: StreamingService(title, Graphite, configuredProviderId ?: 0)
            }
            val isNetflix = providerService.tmdbProviderId == NETFLIX_TMDB_PROVIDER_ID
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TvProviderBrandHeader(
                    service = providerService,
                    modifier = Modifier
                        .width(if (isNetflix) 176.dp else 220.dp)
                        .height(if (isNetflix) 53.dp else 66.dp),
                )
                if (isStreamingCatalogRail) {
                    TvClickToEditSearchField(
                        value = titleQuery,
                        onValueChange = {
                            providerControlsInteracted = true
                            titleQuery = it
                        },
                        placeholder = "Search movies or series",
                        onNavigateDown = {
                            runCatching { providerMovieRequester.requestFocus() }
                            onContentFocused(providerMovieRequester)
                        },
                        modifier = Modifier
                            .width(340.dp)
                            .focusRequester(catalogSearchRequester)
                            .focusProperties {
                                left = railFocusRequester
                                down = providerMovieRequester
                            }
                            .onFocusChanged {
                                if (it.isFocused) onContentFocused(catalogSearchRequester)
                            },
                    )
                }
            }
        } else if (!isPersonCreditsRail) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        val providerRegions = remember(providerDefaultRegion, isStreamingCatalogRail) {
            if (isStreamingCatalogRail) emptyList() else {
                listOfNotNull(providerDefaultRegion, "US", "DE", "GB", "CA", "AU", "AT", "CH", "FR")
                    .distinct()
            }
        }
        val providerRegionRequesters = remember(providerRegions) {
            List(providerRegions.size) { FocusRequester() }
        }
        val rememberedPosterRequester = lastFocusedKey?.let(focusRequesters::get)
        val afterFiltersRequester = when {
            rememberedPosterRequester != null -> rememberedPosterRequester
            renderedItems.isNotEmpty() -> focusRequesters[renderedItems.first().seeAllStableKey()]
            sortOptions.isNotEmpty() -> sortToggleRequester
            else -> null
        }
        val filterExitRequester = when {
            renderedItems.isNotEmpty() -> firstItemFocusRequester
            sortOptions.isNotEmpty() -> sortToggleRequester
            else -> null
        }
        LaunchedEffect(restoreFocusAfterClearNonce) {
            if (restoreFocusAfterClearNonce == 0) return@LaunchedEffect
            var requested = false
            repeat(8) {
                if (!requested) {
                    withFrameNanos { }
                    requested = runCatching { filterToggleRequester.requestFocus() }.isSuccess
                }
            }
            if (requested) {
                onContentFocused(filterToggleRequester)
            }
        }
        LaunchedEffect(restoreSortToggleNonce) {
            if (restoreSortToggleNonce == 0) return@LaunchedEffect
            withFrameNanos { }
            runCatching { sortToggleRequester.requestFocus() }
            onContentFocused(sortToggleRequester)
        }
        LaunchedEffect(
            showFilters,
            focusFirstFilterAfterOpen,
            availableGenres.size,
            availableStudios.size,
            availableYearRanges.size,
            availableRatingThresholds.size,
        ) {
            if (!showFilters || !focusFirstFilterAfterOpen) return@LaunchedEffect
            focusFirstFilterAfterOpen = false
            withFrameNanos { }
            val focusedFilter = runCatching { firstFilterRequester.requestFocus() }.isSuccess
            if (focusedFilter) {
                onContentFocused(firstFilterRequester)
            } else {
                runCatching { filterToggleRequester.requestFocus() }
                onContentFocused(filterToggleRequester)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = if (showFilters || showSortOptions) 10.dp else 16.dp),
        ) {
            if (isProviderSearchRail) {
                TvSeeAllFilterChip(
                    label = "Movies",
                    selected = providerContentType == "movie",
                    modifier = Modifier
                        .focusRequester(providerMovieRequester)
                        .focusProperties {
                            left = railFocusRequester
                            right = providerSeriesRequester
                            if (isStreamingCatalogRail) up = catalogSearchRequester
                            if (showFilters) {
                                down = firstFilterRequester
                            } else {
                                afterFiltersRequester?.let { down = it }
                            }
                        },
                    onFocused = { onContentFocused(providerMovieRequester) },
                    onClick = {
                        providerControlsInteracted = true
                        providerContentType = "movie"
                    },
                )
                TvSeeAllFilterChip(
                    label = "Series",
                    selected = providerContentType == "tv",
                    modifier = Modifier
                        .focusRequester(providerSeriesRequester)
                        .focusProperties {
                            left = providerMovieRequester
                            right = providerRegionRequesters.firstOrNull() ?: filterToggleRequester
                            if (isStreamingCatalogRail) up = catalogSearchRequester
                            if (showFilters) {
                                down = firstFilterRequester
                            } else {
                                afterFiltersRequester?.let { down = it }
                            }
                        },
                    onFocused = { onContentFocused(providerSeriesRequester) },
                    onClick = {
                        providerControlsInteracted = true
                        providerContentType = "tv"
                    },
                )
                providerRegions.forEachIndexed { index, region ->
                    val requester = providerRegionRequesters[index]
                    TvSeeAllFilterChip(
                        label = region,
                        selected = region in selectedProviderRegions,
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties {
                                left = if (index == 0) providerSeriesRequester else providerRegionRequesters[index - 1]
                                right = providerRegionRequesters.getOrNull(index + 1) ?: filterToggleRequester
                                if (isStreamingCatalogRail) up = catalogSearchRequester
                                if (showFilters) {
                                    down = firstFilterRequester
                                } else {
                                    afterFiltersRequester?.let { down = it }
                                }
                            },
                        onFocused = { onContentFocused(requester) },
                        onClick = {
                            providerControlsInteracted = true
                            selectedProviderRegions = if (region in selectedProviderRegions) {
                                selectedProviderRegions.minus(region).takeIf { it.isNotEmpty() } ?: selectedProviderRegions
                            } else {
                                selectedProviderRegions + region
                            }
                        },
                    )
                }
            }
            val hasFilterSelections = selectedGenreIds.isNotEmpty() ||
                selectedStudioIds.isNotEmpty() ||
                selectedYearRangeIds.isNotEmpty() ||
                selectedMinRating != null ||
                titleQuery.isNotBlank()
            TvSeeAllFilterChip(
                label = "Filters",
                selected = showFilters,
                modifier = Modifier
                    .focusRequester(filterToggleRequester)
                    .focusProperties {
                        left = if (isProviderSearchRail) {
                            providerRegionRequesters.lastOrNull() ?: providerSeriesRequester
                        } else if (isStreamingCatalogRail) {
                            catalogSearchRequester
                        } else {
                            railFocusRequester
                        }
                        if (isStreamingCatalogRail) {
                            up = catalogSearchRequester
                        }
                        right = sortToggleRequester
                        if (showFilters) {
                            down = firstFilterRequester
                        } else {
                            afterFiltersRequester?.let { down = it }
                        }
                    },
                onFocused = { onContentFocused(filterToggleRequester) },
                onClick = {
                    val opening = !showFilters
                    showFilters = opening
                    if (opening) showSortOptions = false
                    focusFirstFilterAfterOpen = opening
                    if (!opening) {
                        restoreFocusAfterClearNonce++
                    }
                },
            )
            TvSeeAllFilterChip(
                label = "Sort: ${sortOptions.getOrNull(selectedSortIndex)?.label ?: "Default"}",
                selected = showSortOptions,
                modifier = Modifier
                    .focusRequester(sortToggleRequester)
                    .focusProperties {
                        left = filterToggleRequester
                        right = if (hasFilterSelections) clearRequester else posterColumnsRequester
                        if (isStreamingCatalogRail) up = catalogSearchRequester
                        down = if (showSortOptions) {
                            sortRequesters.firstOrNull() ?: afterFiltersRequester ?: FocusRequester.Default
                        } else {
                            afterFiltersRequester ?: FocusRequester.Default
                        }
                    },
                onFocused = { onContentFocused(sortToggleRequester) },
                onClick = {
                    val opening = !showSortOptions
                    showSortOptions = opening
                    if (opening) showFilters = false
                    if (!opening) restoreSortToggleNonce++
                },
            )
            if (hasFilterSelections) {
                TvSeeAllFilterChip(
                    label = "Clear",
                    modifier = Modifier
                        .focusRequester(clearRequester)
                        .focusProperties {
                            left = sortToggleRequester
                            right = posterColumnsRequester
                            if (isStreamingCatalogRail) up = catalogSearchRequester
                            if (showFilters) {
                                down = firstFilterRequester
                            } else {
                                afterFiltersRequester?.let { down = it }
                            }
                    },
                    onFocused = { onContentFocused(clearRequester) },
                    onClick = {
                        runCatching { filterToggleRequester.requestFocus() }
                        onContentFocused(filterToggleRequester)
                        selectedGenreIds = emptySet()
                        selectedStudioIds = emptySet()
                        selectedYearRangeIds = emptySet()
                        selectedMinRating = null
                        titleQuery = ""
                        providerControlsInteracted = true
                        restoreFocusAfterClearNonce++
                    },
                )
            }
            TvSeeAllFilterChip(
                label = "Posters: $posterColumns",
                modifier = Modifier
                    .focusRequester(posterColumnsRequester)
                    .focusProperties {
                        left = if (hasFilterSelections) clearRequester else sortToggleRequester
                        if (isStreamingCatalogRail) up = catalogSearchRequester
                        if (showFilters) {
                            down = firstFilterRequester
                        } else {
                            afterFiltersRequester?.let { down = it }
                        }
                    },
                onFocused = { onContentFocused(posterColumnsRequester) },
                onClick = {
                    val currentIndex = TV_SEE_ALL_POSTER_COLUMN_OPTIONS
                        .indexOf(posterColumns)
                        .takeIf { it >= 0 } ?: 0
                    val next = TV_SEE_ALL_POSTER_COLUMN_OPTIONS[
                        (currentIndex + 1) % TV_SEE_ALL_POSTER_COLUMN_OPTIONS.size
                    ]
                    setTvSeeAllPosterColumns(context, next)
                },
            )
        }

        if (showFilters) {
            TvSeeAllFilterRows(
                genres = availableGenres,
                selectedGenreIds = selectedGenreIds,
                onToggleGenre = { id ->
                    val next = if (id in selectedGenreIds) selectedGenreIds - id else selectedGenreIds + id
                    selectedGenreIds = next
                    providerControlsInteracted = true
                },
                studios = availableStudios,
                selectedStudioIds = selectedStudioIds,
                onToggleStudio = { id ->
                    val next = if (id in selectedStudioIds) selectedStudioIds - id else selectedStudioIds + id
                    selectedStudioIds = next
                    providerControlsInteracted = true
                },
                years = availableYearRanges.map { it.id to it.label },
                selectedYearIds = selectedYearRangeIds,
                onToggleYear = { yearRangeId ->
                    selectedYearRangeIds = if (yearRangeId in selectedYearRangeIds) {
                        selectedYearRangeIds - yearRangeId
                    } else {
                        selectedYearRangeIds + yearRangeId
                    }
                    providerControlsInteracted = true
                },
                selectedMinRating = selectedMinRating,
                onSelectRating = { rating ->
                    val next = if (selectedMinRating == rating) null else rating
                    selectedMinRating = next
                    providerControlsInteracted = true
                },
                ratingThresholds = availableRatingThresholds,
                railFocusRequester = railFocusRequester,
                firstFilterRequester = firstFilterRequester,
                upRequester = filterToggleRequester,
                downRequester = filterExitRequester,
                onContentFocused = onContentFocused,
            )
        }

        if (showSortOptions && sortOptions.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                sortOptions.forEachIndexed { index, option ->
                    val sortTarget = remember(screenId, index, option.key) {
                        TvFocusTargetId(
                            screenId = screenId,
                            rowKey = "sort_bar",
                            itemKey = option.key.name,
                            rowIndex = 0,
                            itemIndex = index,
                            targetType = "sort",
                        )
                    }
                    val sortRequester = rememberRegisteredTvFocusRequester(
                        controller = focusRestoreController,
                        target = sortTarget,
                        externalRequester = sortRequesters[index],
                    )
                    TvSeeAllFilterChip(
                        label = option.label,
                        selected = option.key == selectedSortKey,
                        modifier = Modifier
                            .focusRequester(sortRequester)
                            .focusProperties {
                                if (index == 0) {
                                    left = sortToggleRequester
                                } else {
                                    left = sortRequesters[index - 1]
                                }
                                if (index < sortOptions.lastIndex) {
                                    right = sortRequesters[index + 1]
                                }
                                up = sortToggleRequester
                                if (renderedItems.isNotEmpty()) {
                                    down = firstItemFocusRequester
                                }
                            },
                        onFocused = {
                            focusRestoreController.markFocused(sortTarget)
                            onContentFocused(sortRequester)
                        },
                        onClick = {
                            selectedSortKey = option.key
                            providerControlsInteracted = true
                            showSortOptions = false
                            restoreSortToggleNonce++
                        },
                    )
                }
            }
        }

        when {
            initialLoad -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Amber)
                }
            }

            items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isProviderSearchRail) {
                            "No ${if (providerContentType == "tv") "series" else "movies"} match these filters for $providerDisplayName."
                        } else {
                            stringResource(R.string.tv_no_data)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = Silver,
                    )
                }
            }

            else -> {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (isPersonCreditsRail) {
                        TvPersonInfoPanel(
                            info = personPanelInfo,
                            fallbackTitle = title,
                            modifier = Modifier
                                .width(340.dp)
                                .fillMaxSize(),
                        )
                    } else {
                        val rawPreviewItem = focusedMediaItem ?: renderedItems.firstOrNull()
                        val previewItem = rawPreviewItem?.let { item ->
                            enrichedSeeAllItemsByKey[item.seeAllStableKey()]
                                ?.let { item.mergeSeeAllEnrichedItem(it) }
                                ?: item
                        }
                        TvBrowsePreviewPanel(
                            focusedItem = previewItem,
                            modifier = Modifier
                                .width(326.dp)
                                .fillMaxSize(),
                        )
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(posterColumns),
                        state = gridState,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                    ) {
                        itemsIndexed(
                            renderedItems,
                            key = { _, item -> "sa_${item.seeAllStableKey()}" },
                        ) { index, item ->
                            val itemKey = item.seeAllStableKey()
                            val baseRequester = if (index == 0) {
                                firstItemFocusRequester.also { focusRequesters[itemKey] = it }
                            } else {
                                focusRequesters.getOrPut(itemKey) { FocusRequester() }
                            }
                            val target = remember(screenId, index, posterColumns, itemKey) {
                                TvFocusTargetId(
                                    screenId = screenId,
                                    rowKey = "grid",
                                    itemKey = itemKey,
                                    rowIndex = index / posterColumns,
                                    itemIndex = index,
                                    targetType = "card",
                                )
                            }
                            val requester = rememberRegisteredTvFocusRequester(
                                controller = focusRestoreController,
                                target = target,
                                externalRequester = baseRequester,
                            )
                            if (index == 0) {
                                if (!isStreamingCatalogRail) onFirstContentRequester(requester)
                            }

                            SeeAllPosterCard(
                                item = item,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .focusRequester(requester)
                                    .focusProperties {
                                        if (index % posterColumns == 0) {
                                            left = railFocusRequester
                                        }
                                        if (index < posterColumns) {
                                            up = when {
                                                showSortOptions -> sortRequesters[selectedSortIndex]
                                                showFilters -> firstFilterRequester
                                                else -> sortToggleRequester
                                            }
                                        }
                                        if (index / posterColumns == renderedItems.lastIndex / posterColumns) {
                                            down = requester
                                        }
                                    },
                                onFocused = {
                                    focusRestoreController.markFocused(target)
                                    onContentFocused(requester)
                                    focusedMediaItem = enrichedSeeAllItemsByKey[item.seeAllStableKey()]
                                        ?.let { item.mergeSeeAllEnrichedItem(it) }
                                        ?: item
                                    lastFocusedIndex = index
                                    lastFocusedKey = itemKey
                                    posterBackTargetsFilters = isStreamingCatalogRail
                                    if (showFilters) {
                                        showFilters = false
                                    }
                                },
                                onClick = {
                                    focusRestoreController.markFocused(target)
                                    lastFocusedIndex = index
                                    lastFocusedKey = itemKey
                                    onMediaClick(item)
                                },
                            )
                        }

                        if (loading && !hasActiveFilters) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = Amber,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

}

private data class TvPersonPanelInfo(
    val name: String,
    val profileUrl: String? = null,
    val imageUrls: List<String> = emptyList(),
    val biography: String = "",
    val knownFor: String = "",
    val birthday: String? = null,
    val placeOfBirth: String? = null,
)

@Composable
private fun TvPersonInfoPanel(
    info: TvPersonPanelInfo?,
    fallbackTitle: String,
    modifier: Modifier = Modifier,
) {
    val displayName = info?.name?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val imageUrls = (info?.imageUrls.orEmpty() + listOfNotNull(info?.profileUrl)).distinct()
    val birthLine = listOfNotNull(
        info?.birthday?.takeIf { it.isNotBlank() },
        info?.placeOfBirth?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val biography = info?.biography.orEmpty()
    var imageIndex by remember(imageUrls) { mutableIntStateOf(0) }
    val currentImageUrl = imageUrls.getOrNull(imageIndex.coerceIn(0, (imageUrls.size - 1).coerceAtLeast(0)))
    val bioScrollState = rememberScrollState()
    var bioFocused by remember { mutableStateOf(false) }
    val bioBorderColor by animateColorAsState(
        targetValue = if (bioFocused) Amber else Steel.copy(alpha = 0.24f),
        label = "personBioBorder",
    )

    LaunchedEffect(imageUrls) {
        imageIndex = 0
        if (imageUrls.size > 1) {
            while (true) {
                delay(4_500L)
                imageIndex = (imageIndex + 1) % imageUrls.size
            }
        }
    }

    LaunchedEffect(biography, bioScrollState.maxValue) {
        bioScrollState.scrollTo(0)
        if (biography.isNotBlank() && bioScrollState.maxValue > 0) {
            while (true) {
                delay(2_000L)
                bioScrollState.animateScrollTo(
                    value = bioScrollState.maxValue,
                    animationSpec = tween(
                        durationMillis = (bioScrollState.maxValue * 72).coerceIn(22_000, 58_000),
                        easing = LinearEasing,
                    ),
                )
                delay(1_200L)
                bioScrollState.scrollTo(0)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Graphite.copy(alpha = 0.98f),
                        Charcoal.copy(alpha = 0.96f),
                        Color.Black.copy(alpha = 0.96f),
                    ),
                ),
            ),
    ) {
        if (!currentImageUrl.isNullOrBlank()) {
            Crossfade(
                targetState = currentImageUrl,
                label = "personPanelBackdrop",
            ) { imageUrl ->
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.12f),
                                Charcoal.copy(alpha = 0.58f),
                                Color.Black.copy(alpha = 0.96f),
                            ),
                        ),
                    ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineSmall,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (birthLine.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = birthLine,
                style = MaterialTheme.typography.bodySmall,
                color = Silver,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (biography.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .border(1.dp, bioBorderColor, RoundedCornerShape(16.dp))
                    .onFocusChanged { bioFocused = it.isFocused }
                    .focusable()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = biography,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Silver,
                    modifier = Modifier.verticalScroll(bioScrollState),
                )
            }
        } else if (info == null) {
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Amber,
                strokeWidth = 2.dp,
            )
        }
        }
    }
}

private enum class TvSeeAllSortKey {
    DEFAULT,
    RATING_DESC,
    TITLE_ASC,
    TITLE_DESC,
    NEWEST_RELEASE,
    OLDEST_RELEASE,
}

private data class TvSeeAllSortOption(
    val key: TvSeeAllSortKey,
    val label: String,
)

private data class TvSeeAllYearRange(
    val id: Int,
    val startYear: Int,
    val endYear: Int,
    val label: String,
)

private fun providerSeeAllYearRanges(): List<TvSeeAllYearRange> {
    val currentYear = java.time.LocalDate.now().year
    val individualYears = (currentYear downTo 2020).map { year ->
        TvSeeAllYearRange(year, year, year, year.toString())
    }
    val decades = listOf(2010, 2000, 1990, 1980, 1970, 1960, 1950).map { start ->
        TvSeeAllYearRange(start, start, start + 9, "${start}s")
    }
    return individualYears + decades + TvSeeAllYearRange(1949, 1870, 1949, "Before 1950")
}

private fun providerDiscoverSortBy(mediaType: String, sortKey: TvSeeAllSortKey): String =
    when (sortKey) {
        TvSeeAllSortKey.RATING_DESC -> "vote_average.desc"
        TvSeeAllSortKey.TITLE_ASC -> if (mediaType == "tv") "original_name.asc" else "original_title.asc"
        TvSeeAllSortKey.TITLE_DESC -> if (mediaType == "tv") "original_name.desc" else "original_title.desc"
        TvSeeAllSortKey.NEWEST_RELEASE -> if (mediaType == "tv") "first_air_date.desc" else "primary_release_date.desc"
        TvSeeAllSortKey.OLDEST_RELEASE -> if (mediaType == "tv") "first_air_date.asc" else "primary_release_date.asc"
        TvSeeAllSortKey.DEFAULT -> "popularity.desc"
    }

internal fun supportsGlobalTmdbSeeAllQuery(railKey: String): Boolean = when {
    railKey.startsWith("trending_") -> true
    railKey.startsWith("popular_") -> true
    railKey.startsWith("top_rated_") -> true
    railKey.startsWith("genre_") -> true
    railKey in setOf(
        "trending-movies",
        "trending-tv",
        "popular-movies",
        "popular-tv",
        "top-rated",
        "now-playing",
        "upcoming",
        "hidden_gems",
    ) -> true
    else -> false
}

internal fun buildTmdbGenreQuery(
    fixedGenreId: Int?,
    selectedGenreIds: Set<Int>,
): String? {
    val selected = selectedGenreIds
        .filter { it > 0 && it != fixedGenreId }
        .sorted()
    return when {
        fixedGenreId != null && selected.isNotEmpty() -> "$fixedGenreId,${selected.joinToString("|")}"
        fixedGenreId != null -> fixedGenreId.toString()
        selected.isNotEmpty() -> selected.joinToString("|")
        else -> null
    }
}

private fun sortOptionsForRail(railKey: String): List<TvSeeAllSortOption> {
    val firstLabel = when {
        railKey.startsWith("continue_watching") -> "Recent Viewed"
        railKey.startsWith("more_like_") -> "Recommended"
        else -> "Default"
    }
    return listOf(
        TvSeeAllSortOption(TvSeeAllSortKey.DEFAULT, firstLabel),
        TvSeeAllSortOption(TvSeeAllSortKey.RATING_DESC, "IMDb Rating"),
        TvSeeAllSortOption(TvSeeAllSortKey.TITLE_ASC, "A-Z"),
        TvSeeAllSortOption(TvSeeAllSortKey.TITLE_DESC, "Z-A"),
        TvSeeAllSortOption(TvSeeAllSortKey.NEWEST_RELEASE, "Newest Release"),
        TvSeeAllSortOption(TvSeeAllSortKey.OLDEST_RELEASE, "Oldest Release"),
    )
}

internal fun mergeMoreLikeCandidates(
    seedTmdbId: Int,
    recommendations: List<MediaItem>,
    similar: List<MediaItem>,
): List<MediaItem> {
    val seen = mutableSetOf<String>()
    return (recommendations + similar).filter { item ->
        item.tmdbId != seedTmdbId && seen.add(item.seeAllStableKey())
    }
}

private fun defaultSortKeyForRail(
    railKey: String,
    sortOptions: List<TvSeeAllSortOption>,
): TvSeeAllSortKey =
    when {
        railKey.startsWith("upcoming_schedule") -> TvSeeAllSortKey.OLDEST_RELEASE
        railKey == "top-rated" || railKey.startsWith("top_rated_") -> TvSeeAllSortKey.RATING_DESC
        else -> sortOptions.firstOrNull()?.key ?: TvSeeAllSortKey.DEFAULT
    }

private fun shouldUsePendingItemsForTvSeeAll(railKey: String): Boolean =
    when {
        railKey.startsWith("trending_") -> false
        railKey.startsWith("popular_") -> false
        railKey.startsWith("top_rated_") -> false
        railKey.startsWith("genre_") -> false
        railKey.startsWith("more_like_") -> false
        railKey.startsWith("provider_") -> false
        railKey.startsWith("streaming_catalog_") -> false
        railKey in setOf(
            "trending-movies",
            "trending-tv",
            "popular-movies",
            "popular-tv",
            "now-playing",
            "top-rated",
            "upcoming",
            "hidden_gems",
        ) -> false
        else -> true
    }

private fun shouldRestoreTvSeeAllCache(
    railKey: String,
    entry: TvSeeAllCacheEntry,
): Boolean {
    if (entry.items.isEmpty()) return false
    if (shouldUsePendingItemsForTvSeeAll(railKey)) return true
    if (railKey.startsWith("provider_") || railKey.startsWith("streaming_catalog_")) {
        return entry.currentPage > 1 || entry.items.size >= 40
    }

    // Discovery/category shelves should behave like catalog pages: enter with a
    // deep first batch, then keep paginating. Older in-memory entries created
    // from a 20-item rail seed must not make See All look identical to Home.
    return entry.currentPage > 1 || entry.items.size >= TV_SEE_ALL_INITIAL_TARGET_COUNT
}

private fun sortSeeAllItems(
    items: List<MediaItem>,
    sortKey: TvSeeAllSortKey,
): List<MediaItem> {
    fun MediaItem.latestKnownReleaseDate(): String? =
        seasons.mapNotNull { it.airDate }.maxOrNull()
            ?: releaseDate
            ?: year?.toString()
    fun MediaItem.latestKnownReleaseDateOrHigh(): String =
        latestKnownReleaseDate() ?: "9999-99-99T99:99:99Z"
    fun MediaItem.normalizedTitle(): String = title.trim().lowercase()

    return when (sortKey) {
        TvSeeAllSortKey.DEFAULT -> items
        TvSeeAllSortKey.RATING_DESC -> items.sortedWith(
            compareByDescending<MediaItem> {
                it.seeAllExternalRating10() != null
            }.thenByDescending {
                it.seeAllExternalRating10() ?: -1.0
            }.thenBy { it.normalizedTitle() },
        )
        TvSeeAllSortKey.TITLE_ASC -> items.sortedWith(compareBy<MediaItem> { it.normalizedTitle() }.thenBy { it.id })
        TvSeeAllSortKey.TITLE_DESC -> items.sortedWith(compareByDescending<MediaItem> { it.normalizedTitle() }.thenBy { it.id })
        TvSeeAllSortKey.NEWEST_RELEASE -> items.sortedWith(
            compareByDescending<MediaItem> { it.latestKnownReleaseDate().orEmpty() }.thenBy { it.normalizedTitle() },
        )
        TvSeeAllSortKey.OLDEST_RELEASE -> items.sortedWith(
            compareBy<MediaItem> { it.latestKnownReleaseDateOrHigh() }.thenBy { it.normalizedTitle() },
        )
    }
}

private fun List<MediaItem>.availableSeeAllGenres(): List<Pair<Int, String>> =
    flatMap { item ->
        item.genres.map { it.id to it.name }
            .ifEmpty { item.genreIds.mapNotNull { id -> genreLabelForId(id)?.let { id to it } } }
    }
        .filter { it.first > 0 && it.second.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }

private fun defaultSeeAllGenres(mediaType: String): List<Pair<Int, String>> {
    val ids = if (mediaType == "tv") {
        listOf(10759, 16, 35, 80, 99, 18, 10751, 10762, 9648, 10763, 10764, 10765, 10766, 10767, 10768)
    } else {
        listOf(28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 10770, 53, 10752, 37)
    }
    return ids.mapNotNull { id -> genreLabelForId(id)?.let { id to it } }
}

private fun defaultSeeAllStudios(mediaType: String): List<Pair<Int, String>> =
    if (mediaType == "tv") {
        listOf(
            49 to "HBO",
            213 to "Netflix",
            1024 to "Prime Video",
            2739 to "Disney+",
            2552 to "Apple TV+",
        )
    } else {
        listOf(
            2 to "Walt Disney Pictures",
            33 to "Universal Pictures",
            174 to "Warner Bros.",
            420 to "Marvel Studios",
            4 to "Paramount Pictures",
        )
    }

private fun defaultSeeAllYears(): List<Int> {
    val currentYear = java.time.LocalDate.now().year
    return listOf(currentYear, currentYear - 1, 2024, 2023, 2022)
        .distinct()
        .filter { it in 1900..2100 }
        .take(TV_SEE_ALL_FILTER_YEAR_LIMIT)
}

private fun List<MediaItem>.availableSeeAllStudios(): List<Pair<Int, String>> =
    flatMap { it.studios }
        .filter { it.id > 0 && it.name.isNotBlank() }
        .groupingBy { it.id to it.name }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }

private fun List<MediaItem>.filterSeeAllItems(
    genreIds: Set<Int>,
    studioIds: Set<Int>,
    yearRanges: List<IntRange>,
    minRating: Double?,
): List<MediaItem> =
    filter { item ->
            (genreIds.isEmpty() || item.genreIds.any { it in genreIds } || item.genres.any { it.id in genreIds }) &&
            (studioIds.isEmpty() || item.studios.any { it.id in studioIds }) &&
            (yearRanges.isEmpty() || item.year?.let { year -> yearRanges.any { year in it } } == true) &&
            (minRating == null || (item.rating ?: 0.0) >= minRating)
    }

private fun MediaItem.seeAllExternalRating10(): Double? =
    ratings?.imdbScore
        ?.takeIf { it > 0f }
        ?.toDouble()
        ?: rating?.takeIf { it > 0.0 }

@Composable
private fun TvSeeAllFilterRows(
    genres: List<Pair<Int, String>>,
    selectedGenreIds: Set<Int>,
    onToggleGenre: (Int) -> Unit,
    studios: List<Pair<Int, String>>,
    selectedStudioIds: Set<Int>,
    onToggleStudio: (Int) -> Unit,
    years: List<Pair<Int, String>>,
    selectedYearIds: Set<Int>,
    onToggleYear: (Int) -> Unit,
    selectedMinRating: Double?,
    onSelectRating: (Double) -> Unit,
    ratingThresholds: List<Double>,
    railFocusRequester: FocusRequester,
    firstFilterRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester?,
    onContentFocused: (FocusRequester) -> Unit,
) {
    val columns = 5
    val filterScrollState = rememberScrollState()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(bottom = 10.dp)
            .heightIn(max = 250.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Charcoal.copy(alpha = 0.54f))
            .border(1.dp, Snow.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
            .verticalScroll(filterScrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val groups = remember(genres, studios, years, ratingThresholds) {
            buildList {
                if (genres.isNotEmpty()) add(TvSeeAllFilterGroup("genre", "Genre", genres))
                if (studios.isNotEmpty()) add(TvSeeAllFilterGroup("studio", "Studio / Network", studios))
                if (years.isNotEmpty()) add(TvSeeAllFilterGroup("year", "Year / decade", years))
                add(
                    TvSeeAllFilterGroup(
                        "rating",
                        "TMDB rating",
                        ratingThresholds.map { it.toInt() to "${it.toInt()}+" },
                    ),
                )
            }
        }
        val chips = remember(groups) {
            groups.flatMap { group ->
                group.options.map { (id, label) -> TvSeeAllFilterChipSpec(group.key, id, label) }
            }
        }
        val visualRows = remember(groups) {
            val rows = mutableListOf<TvSeeAllFilterVisualRow>()
            var chipIndex = 0
            groups.forEach { group ->
                group.options.chunked(columns).forEachIndexed { chunkIndex, chunk ->
                    rows += TvSeeAllFilterVisualRow(
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
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = if (rowIndex == 0) 0.dp else 2.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        "year" -> chip.id in selectedYearIds
                        "rating" -> selectedMinRating?.toInt() == chip.id
                        else -> false
                    }
                TvSeeAllFilterChip(
                    label = chip.label,
                    selected = selected,
                    modifier = Modifier
                        .focusRequester(requester)
                        .focusProperties {
                            left = previousInRow ?: railFocusRequester
                            right = nextInRow ?: nextRow ?: FocusRequester.Default
                            up = previousRow ?: upRequester
                            down = nextRow ?: downRequester ?: FocusRequester.Default
                        },
                    onFocused = { onContentFocused(requester) },
                    onClick = {
                        when (chip.groupKey) {
                            "genre" -> onToggleGenre(chip.id)
                            "studio" -> onToggleStudio(chip.id)
                            "year" -> onToggleYear(chip.id)
                            "rating" -> onSelectRating(chip.id.toDouble())
                        }
                    },
                )
                }
            }
        }
    }
}

private fun MediaItem.seeAllStableKey(): String = "${type.name}:${tmdbId ?: id}"

private fun genreLabelForId(id: Int): String? = when (id) {
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

private fun MediaItem.upcomingScheduleMetadata(): String? {
    if (!id.startsWith("trakt-calendar:")) return null
    val raw = releaseDate?.trim()?.takeIf { it.isNotEmpty() }
        ?: id.split(":", limit = 5).getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    return runCatching {
        java.time.ZonedDateTime.parse(raw)
            .withZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", java.util.Locale.US))
    }.getOrElse {
        raw.take(16).replace('T', ' ').removeSuffix("Z").takeIf { it.isNotBlank() }
    }
}

@Composable
private fun TvSeeAllFilterChip(
    label: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val backgroundColor by animateColorAsState(
        targetValue = when {
            focused && selected -> Amber.copy(alpha = 0.30f)
            selected -> Amber.copy(alpha = 0.22f)
            focused -> Graphite.copy(alpha = 0.95f)
            else -> Charcoal.copy(alpha = 0.64f)
        },
        label = "tvSeeAllFilterBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && selected -> Snow
            selected -> Amber
            focused -> AmberLight
            else -> Snow.copy(alpha = 0.10f)
        },
        label = "tvSeeAllFilterBorder",
    )
    Box(
        modifier = modifier
            .height(30.dp)
            .widthIn(min = 54.dp, max = 160.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused && selected) Snow else if (selected) AmberLight else Snow,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TvSeeAllSortButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected -> Amber
            focused -> Graphite.copy(alpha = 0.95f)
            else -> Charcoal.copy(alpha = 0.75f)
        },
        label = "tvSeeAllSortBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) Obsidian else Snow,
        label = "tvSeeAllSortText",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            focused && selected -> Snow
            focused -> AmberLight
            selected -> Amber.copy(alpha = 0.72f)
            else -> Snow.copy(alpha = 0.10f)
        },
        label = "tvSeeAllSortBorder",
    )

    Box(
        modifier = modifier
            .height(38.dp)
            .widthIn(min = 58.dp, max = 112.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .border(if (focused) 3.dp else 1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SeeAllPosterCard(
    item: MediaItem,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.035f else 1f, label = "seeAllCardScale")
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "seeAllBorder",
    )
    val baseColor by animateColorAsState(
        targetValue = if (focused) Graphite.copy(alpha = 0.78f) else Charcoal.copy(alpha = 0.42f),
        label = "seeAllCardBase",
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
            .clip(RoundedCornerShape(16.dp))
            .background(baseColor)
            .border(if (focused) 3.dp else 1.dp, borderColor, RoundedCornerShape(16.dp)),
    ) {
        val imageUrl = item.posterUrl ?: item.backdropUrl
        if (imageUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A2E)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Snow,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
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
