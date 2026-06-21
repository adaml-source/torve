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
import com.torve.android.tv.TV_PAGE_END_GUTTER
import com.torve.android.tv.TV_PAGE_TOP_GUTTER
import com.torve.android.tv.TvImagePrefetcher
import com.torve.android.tv.components.TvBrowsePreviewPanel
import com.torve.android.tv.components.cacheTvBrowsePreviewEnrichedItem
import com.torve.android.tv.components.tvExternalCardRatingPrefs
import com.torve.android.ui.components.PreferredRatingPills
import com.torve.android.tv.settings.TV_SEE_ALL_POSTER_COLUMN_OPTIONS
import com.torve.android.tv.settings.rememberTvSeeAllPosterColumnsPreference
import com.torve.android.tv.settings.setTvSeeAllPosterColumns
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.data.metadata.TmdbMappers
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.PagedResult
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsViewModel
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.hasRichExternalRating
import com.torve.domain.model.needsExternalRatingEnrichment
import com.torve.domain.model.withFallbackTmdbScore
import kotlinx.coroutines.Dispatchers
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
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val watchProgressRepo: WatchProgressRepository = koinInject()
    val libraryOverlayService: LibraryOverlayService = koinInject()
    val ratingsEnricher: RatingsEnricher = koinInject()
    val prefsRepo: PreferencesRepository = koinInject()
    val secretStore: IntegrationSecretStore = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val settingsState by settingsViewModel.state.collectAsState()
    val tvCardRatingPrefs = remember(settingsState.ratingPrefs) {
        settingsState.ratingPrefs.tvExternalCardRatingPrefs()
    }
    val isPersonCreditsRail = railKey.startsWith("person_credits_")
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
    val focusRestoreController = rememberTvModalFocusRestoreController(key = "see_all_${railKey}_$mediaType")
    val cacheKey = remember(railKey, mediaType) { "$railKey|$mediaType" }
    val sortOptions = remember(railKey) { sortOptionsForRail(railKey) }
    var selectedSortKey by remember(railKey) { mutableStateOf(defaultSortKeyForRail(railKey, sortOptions)) }
    val sortRequesters = remember(sortOptions) { List(sortOptions.size) { FocusRequester() } }
    val selectedSortIndex = sortOptions.indexOfFirst { it.key == selectedSortKey }.coerceAtLeast(0)
    val loadedItems = items.toList()
    var showFilters by rememberSaveable(railKey, mediaType) { mutableStateOf(false) }
    var selectedGenreIds by remember(railKey, mediaType) { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedStudioIds by remember(railKey, mediaType) { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedYear by remember(railKey, mediaType) { mutableStateOf<Int?>(null) }
    var selectedMinRating by remember(railKey, mediaType) { mutableStateOf<Double?>(null) }
    var restoreFocusAfterClearNonce by remember(railKey, mediaType) { mutableIntStateOf(0) }
    var focusFirstFilterAfterOpen by remember(railKey, mediaType) { mutableStateOf(false) }
    val defaultSortKey = defaultSortKeyForRail(railKey, sortOptions)
    val hasActiveFilters = selectedGenreIds.isNotEmpty() ||
        selectedStudioIds.isNotEmpty() ||
        selectedYear != null ||
        selectedMinRating != null
    // See All is a scoped expansion of the rail that opened it. Sort/filter
    // must transform the loaded rail source only; it must not switch to a
    // broader discovery query to "refill" results.
    val filterSourceItems = filterOptionItems.toList().ifEmpty { loadedItems }
    val filterMediaType = remember(railKey, mediaType) {
        mediaType.takeIf { it == "movie" || it == "tv" }
            ?: when {
                railKey.contains("_tv") || railKey.endsWith("-tv") -> "tv"
                else -> "movie"
            }
    }
    val availableGenres = remember(filterSourceItems, filterMediaType) {
        filterSourceItems.availableSeeAllGenres()
            .ifEmpty { defaultSeeAllGenres(filterMediaType) }
            .take(TV_SEE_ALL_FILTER_GENRE_LIMIT)
    }
    val availableStudios = remember(filterSourceItems, filterMediaType) {
        filterSourceItems.availableSeeAllStudios()
            .ifEmpty { defaultSeeAllStudios(filterMediaType) }
            .take(TV_SEE_ALL_FILTER_STUDIO_LIMIT)
    }
    val availableYears = remember(filterSourceItems) {
        filterSourceItems.mapNotNull { it.year }
            .filter { it in 1900..2100 }
            .distinct()
            .sortedDescending()
            .take(TV_SEE_ALL_FILTER_YEAR_LIMIT)
            .ifEmpty { defaultSeeAllYears() }
    }
    val availableRatingThresholds = listOf(7.0, 8.0, 9.0)
    val displayedItems = remember(
        loadedItems,
        selectedSortKey,
        selectedGenreIds,
        selectedStudioIds,
        selectedYear,
        selectedMinRating,
    ) {
        sortSeeAllItems(
            items = loadedItems
                .filterSeeAllItems(
                    genreIds = selectedGenreIds,
                    studioIds = selectedStudioIds,
                    year = selectedYear,
                    minRating = selectedMinRating,
                ),
            sortKey = selectedSortKey,
        )
    }
    val renderedItems = remember(displayedItems, loadedItems, selectedSortKey, hasActiveFilters) {
        if (hasActiveFilters && displayedItems.isEmpty() && loadedItems.isNotEmpty() && loading) {
            sortSeeAllItems(loadedItems, selectedSortKey)
        } else {
            displayedItems
        }
    }
    var initialFocusHandled by remember(railKey, mediaType) { mutableStateOf(false) }
    var focusedMediaItem by remember { mutableStateOf<MediaItem?>(null) }
    val enrichedSeeAllItemsByKey = remember(railKey, mediaType) { mutableStateMapOf<String, MediaItem>() }
    var lastFocusedIndex by remember { mutableIntStateOf(-1) }
    val focusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    var ratingEnrichmentAttemptedKeys by remember(railKey, mediaType) { mutableStateOf<Set<String>>(emptySet()) }
    val screenId = remember(railKey, mediaType) { "see_all:$railKey:$mediaType" }
    val filterSignature = remember(selectedGenreIds, selectedStudioIds, selectedYear, selectedMinRating, selectedSortKey) {
        "${selectedGenreIds.sorted()}|${selectedStudioIds.sorted()}|${selectedYear ?: "all"}|${selectedMinRating ?: "all"}|$selectedSortKey"
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
                    focusRestoreController.requestRestore(origin = origin, reason = reason)
                },
            ),
        )
        onDispose {
            registerFocusHandle?.invoke(null)
        }
    }

    BackHandler(onBack = {
        if (showFilters) {
            showFilters = false
            restoreFocusAfterClearNonce++
            return@BackHandler
        }
        lastFocusedIndex = -1  // Clear so we don't restore when exiting See All itself
        focusedMediaItem = null
        onBack()
    })

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

    val shouldLoadMore by remember(loading, currentPage, totalPages, displayedItems.size) {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            !loading &&
                currentPage < totalPages &&
                displayedItems.isNotEmpty() &&
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
            if (railKey.startsWith("provider_")) {
                val parts = railKey.split("_")
                val providerType = parts.getOrNull(1)?.takeIf { it == "movie" || it == "tv" } ?: "movie"
                val providerId = parts.getOrNull(2)
                if (providerId.isNullOrBlank()) {
                    loading = false
                    return
                }
                val result = metadataRepo.discover(
                    type = providerType,
                    page = page,
                    sortBy = "popularity.desc",
                    withWatchProviders = providerId,
                    watchRegion = "US",
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
            val result = when {
                railKey.startsWith("more_like_") -> {
                    val parts = railKey.split("_")
                    val relatedType = parts.getOrNull(2)?.takeIf { it == "movie" || it == "tv" } ?: mediaType
                    val relatedId = parts.getOrNull(3)?.toIntOrNull()
                    if (relatedId == null) {
                        loading = false
                        return
                    }
                    val recommendations = metadataRepo.getRecommendations(relatedType, relatedId, page)
                    val relatedItems = if (recommendations.isNotEmpty()) {
                        recommendations
                    } else {
                        metadataRepo.getSimilar(relatedType, relatedId, page)
                    }.filterNot { it.tmdbId == relatedId }
                    val existingKeys = items.mapTo(mutableSetOf()) { it.seeAllStableKey() }
                    items.addAll(relatedItems.filter { existingKeys.add(it.seeAllStableKey()) })
                    currentPage = page
                    totalPages = if (relatedItems.size < 20) page else Int.MAX_VALUE
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
            Log.w(
                TV_SEE_ALL_LOG_TAG,
                "loadPage failed rail=$railKey mediaType=$mediaType page=$page replace=$replace: ${error::class.simpleName}",
            )
        } finally {
            loading = false
            initialLoad = false
        }
    }

    LaunchedEffect(railKey, mediaType) {
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
            initialFocusHandled = false
            return@LaunchedEffect
        }
        items.clear()
        filterOptionItems.clear()
        currentPage = 0
        totalPages = Int.MAX_VALUE
        loading = false
        initialLoad = true
        personPanelInfo = null
        initialFocusHandled = false
        while (items.size < TV_SEE_ALL_INITIAL_TARGET_COUNT && currentPage < totalPages) {
            loadPage(currentPage + 1)
            if (totalPages == 1) break
        }
    }

    LaunchedEffect(filterSignature) {
        if (initialLoad) return@LaunchedEffect
        // Keep focus and scroll stable. displayedItems is derived from the
        // scoped source list, so no reload or global discovery fallback is
        // needed when sort/filter state changes.
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
            val enriched = ratingsEnricher.enrichList(snapshot, apiKey)
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
            onFirstContentRequester(sortRequesters[selectedSortIndex])
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

    // Restore focus to last focused item when returning from Details sub-route.
    // Uses Lifecycle ON_RESUME to detect when this composable's NavBackStackEntry
    // becomes the active destination again after Details is popped.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && lastFocusedIndex >= 0) {
                val requester = focusRequesters[lastFocusedIndex]
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
                if (sortOptions.isNotEmpty()) {
                    sortRequesters[selectedSortIndex].requestFocus()
                } else {
                    firstItemFocusRequester.requestFocus()
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
                .padding(start = 14.dp, top = TV_PAGE_TOP_GUTTER, end = TV_PAGE_END_GUTTER, bottom = TV_PAGE_BOTTOM_GUTTER),
        ) {
        // Info panel — left side
        if (!isPersonCreditsRail) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        val filterToggleRequester = remember(screenId) { FocusRequester() }
        val firstFilterRequester = remember(screenId, "first_filter") { FocusRequester() }
        val clearRequester = remember(screenId, "clear_filters") { FocusRequester() }
        val posterColumnsRequester = remember(screenId, "poster_columns") { FocusRequester() }
        val afterFiltersRequester = when {
            sortOptions.isNotEmpty() -> sortRequesters[selectedSortIndex]
            renderedItems.isNotEmpty() -> firstItemFocusRequester
            else -> null
        }
        val filterExitRequester = when {
            renderedItems.isNotEmpty() -> firstItemFocusRequester
            sortOptions.isNotEmpty() -> sortRequesters[selectedSortIndex]
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
        LaunchedEffect(
            showFilters,
            focusFirstFilterAfterOpen,
            availableGenres.size,
            availableStudios.size,
            availableYears.size,
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
            modifier = Modifier.padding(bottom = if (showFilters) 10.dp else 16.dp),
        ) {
            val hasFilterSelections = selectedGenreIds.isNotEmpty() ||
                selectedStudioIds.isNotEmpty() ||
                selectedYear != null ||
                selectedMinRating != null
            TvSeeAllFilterChip(
                label = "Filters",
                selected = showFilters,
                modifier = Modifier
                    .focusRequester(filterToggleRequester)
                    .focusProperties {
                        left = railFocusRequester
                        right = if (hasFilterSelections) clearRequester else posterColumnsRequester
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
                    focusFirstFilterAfterOpen = opening
                    if (!opening) {
                        restoreFocusAfterClearNonce++
                    }
                },
            )
            if (hasFilterSelections) {
                TvSeeAllFilterChip(
                    label = "Clear",
                    modifier = Modifier
                        .focusRequester(clearRequester)
                        .focusProperties {
                            left = filterToggleRequester
                            right = posterColumnsRequester
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
                        selectedYear = null
                        selectedMinRating = null
                        restoreFocusAfterClearNonce++
                    },
                )
            }
            TvSeeAllFilterChip(
                label = "Posters: $posterColumns",
                modifier = Modifier
                    .focusRequester(posterColumnsRequester)
                    .focusProperties {
                        left = if (hasFilterSelections) clearRequester else filterToggleRequester
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
                },
                studios = availableStudios,
                selectedStudioIds = selectedStudioIds,
                onToggleStudio = { id ->
                    val next = if (id in selectedStudioIds) selectedStudioIds - id else selectedStudioIds + id
                    selectedStudioIds = next
                },
                years = availableYears,
                selectedYear = selectedYear,
                onSelectYear = { year ->
                    val next = if (selectedYear == year) null else year
                    selectedYear = next
                },
                selectedMinRating = selectedMinRating,
                onSelectRating = { rating ->
                    val next = if (selectedMinRating == rating) null else rating
                    selectedMinRating = next
                },
                ratingThresholds = availableRatingThresholds,
                railFocusRequester = railFocusRequester,
                firstFilterRequester = firstFilterRequester,
                upRequester = filterToggleRequester,
                downRequester = filterExitRequester,
                onContentFocused = onContentFocused,
            )
        }

        if (sortOptions.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(bottom = 16.dp),
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
                    TvSeeAllSortButton(
                        label = option.label,
                        selected = option.key == selectedSortKey,
                        modifier = Modifier
                            .focusRequester(sortRequester)
                            .focusProperties {
                                if (index == 0) {
                                    left = railFocusRequester
                                } else {
                                    left = sortRequesters[index - 1]
                                }
                                if (index < sortOptions.lastIndex) {
                                    right = sortRequesters[index + 1]
                                }
                                if (showFilters) {
                                    up = firstFilterRequester
                                }
                                if (renderedItems.isNotEmpty()) {
                                    down = firstItemFocusRequester
                                }
                            },
                        onFocused = {
                            focusRestoreController.markFocused(sortTarget)
                            onContentFocused(sortRequester)
                        },
                        onClick = { selectedSortKey = option.key },
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
                        text = stringResource(R.string.tv_no_data),
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
                            val baseRequester = focusRequesters.getOrPut(index) {
                                if (index == 0) firstItemFocusRequester else FocusRequester()
                            }
                            val target = remember(screenId, index, item.id, item.tmdbId) {
                                TvFocusTargetId(
                                    screenId = screenId,
                                    rowKey = "grid",
                                    itemKey = item.seeAllStableKey(),
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
                            if (index == 0 && sortOptions.isEmpty()) {
                                onFirstContentRequester(requester)
                            }

                            SeeAllPosterCard(
                                item = item,
                                ratingPrefs = tvCardRatingPrefs,
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
                                                sortOptions.isNotEmpty() -> sortRequesters[selectedSortIndex]
                                                showFilters -> firstFilterRequester
                                                else -> filterToggleRequester
                                            }
                                        }
                                    },
                                onFocused = {
                                    focusRestoreController.markFocused(target)
                                    onContentFocused(requester)
                                    focusedMediaItem = enrichedSeeAllItemsByKey[item.seeAllStableKey()]
                                        ?.let { item.mergeSeeAllEnrichedItem(it) }
                                        ?: item
                                    lastFocusedIndex = index
                                    if (showFilters) {
                                        showFilters = false
                                    }
                                },
                                onClick = { onMediaClick(item) },
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

private fun sortOptionsForRail(railKey: String): List<TvSeeAllSortOption> {
    val firstLabel = if (railKey.startsWith("continue_watching")) "Recent Viewed" else "Default"
    return listOf(
        TvSeeAllSortOption(TvSeeAllSortKey.DEFAULT, firstLabel),
        TvSeeAllSortOption(TvSeeAllSortKey.RATING_DESC, "IMDb Rating"),
        TvSeeAllSortOption(TvSeeAllSortKey.TITLE_ASC, "A-Z"),
        TvSeeAllSortOption(TvSeeAllSortKey.TITLE_DESC, "Z-A"),
        TvSeeAllSortOption(TvSeeAllSortKey.NEWEST_RELEASE, "Newest Release"),
        TvSeeAllSortOption(TvSeeAllSortKey.OLDEST_RELEASE, "Oldest Release"),
    )
}

private fun defaultSortKeyForRail(
    railKey: String,
    sortOptions: List<TvSeeAllSortOption>,
): TvSeeAllSortKey =
    if (railKey.startsWith("upcoming_schedule")) {
        TvSeeAllSortKey.OLDEST_RELEASE
    } else {
        sortOptions.firstOrNull()?.key ?: TvSeeAllSortKey.DEFAULT
    }

private fun shouldUsePendingItemsForTvSeeAll(railKey: String): Boolean =
    when {
        railKey.startsWith("trending_") -> false
        railKey.startsWith("popular_") -> false
        railKey.startsWith("top_rated_") -> false
        railKey.startsWith("genre_") -> false
        railKey.startsWith("more_like_") -> false
        railKey.startsWith("provider_") -> false
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
                it.ratings?.imdbScore != null
            }.thenByDescending {
                it.ratings?.imdbScore ?: -1f
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
        listOf(10759, 16, 35, 80, 99, 18, 10751, 9648, 10765, 10768)
    } else {
        listOf(28, 12, 16, 35, 80, 99, 18, 14, 27, 9648, 10749, 878, 53)
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
    year: Int?,
    minRating: Double?,
): List<MediaItem> =
    filter { item ->
        (genreIds.isEmpty() || item.genreIds.any { it in genreIds } || item.genres.any { it.id in genreIds }) &&
            (studioIds.isEmpty() || item.studios.any { it.id in studioIds }) &&
            (year == null || item.year == year) &&
            (minRating == null || (item.ratings?.imdbScore?.toDouble() ?: item.rating ?: 0.0) >= minRating)
    }

@Composable
private fun TvSeeAllFilterRows(
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
    ratingThresholds: List<Double>,
    railFocusRequester: FocusRequester,
    firstFilterRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester?,
    onContentFocused: (FocusRequester) -> Unit,
) {
    val columns = 5
    Column(
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Charcoal.copy(alpha = 0.54f))
            .border(1.dp, Snow.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        val groups = remember(genres, studios, years, ratingThresholds) {
            buildList {
                if (genres.isNotEmpty()) add(TvSeeAllFilterGroup("genre", "Genre", genres))
                if (studios.isNotEmpty()) add(TvSeeAllFilterGroup("studio", "Studio / Network", studios))
                if (years.isNotEmpty()) add(TvSeeAllFilterGroup("year", "Year", years.map { it to it.toString() }))
                add(
                    TvSeeAllFilterGroup(
                        "rating",
                        "Rating",
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
                    modifier = Modifier.padding(top = if (rowIndex == 0) 0.dp else 4.dp),
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
                        "year" -> selectedYear == chip.id
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
    ratingPrefs: RatingDisplayPrefs,
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

        item.ratings.withFallbackTmdbScore(item.rating)?.let { ratings ->
            PreferredRatingPills(
                ratings = ratings,
                prefs = ratingPrefs,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }
    }
}
