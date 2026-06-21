package com.torve.android.tv.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.tv.TV_PAGE_CONTENT_GUTTER
import com.torve.android.tv.TV_PAGE_END_GUTTER
import com.torve.android.tv.NotificationType
import com.torve.android.tv.TvScreenCache
import com.torve.android.tv.TvNotificationQueue
import com.torve.android.tv.components.TvBrowseLayout
import com.torve.android.tv.components.TvCardStyle
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.components.TvRailsPresentationMode
import com.torve.android.tv.components.rememberTvFocusMemory
import com.torve.android.tv.components.tvExternalCardRatingPrefs
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.nav.TvRoutes
import com.torve.android.tv.toMediaItemOrNull
import com.torve.android.ui.components.PreferredRatingPills
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.android.ui.components.TorveSearchField
import com.torve.android.catalog.CatalogRailsBootstrapJson
import com.torve.android.catalog.CatalogRailsBootstrapPayload
import com.torve.android.catalog.CatalogRailsBootstrapRail
import com.torve.android.catalog.PUBLIC_CATALOG_RAILS_USER_ID
import com.torve.android.catalog.catalogRailsBootstrapKey
import com.torve.data.ai.KeywordSearchResult
import com.torve.data.ai.KeywordSearchService
import com.torve.data.auth.AuthClient
import com.torve.data.catalog.CatalogTopCacheRepository
import com.torve.data.network.catalogContentLoadErrorMessage
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.ParentalFilter
import com.torve.domain.model.ContentRating
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.dedupeByStableKey
import com.torve.domain.model.needsExternalRatingEnrichment
import com.torve.domain.model.ratingEnrichmentLookupKeys
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.domain.model.withEnrichedRatingsFrom
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.data.mdblist.RatingsEnricher
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.home.UpcomingScheduleStatus
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.koin.compose.koinInject

private data class CatalogRailsUiState(
    val loading: Boolean = true,
    val rails: List<TvContentRail> = emptyList(),
    val error: String? = null,
)

private const val TV_CATALOG_RAIL_ITEM_LIMIT = 24
private const val TV_CATALOG_RAIL_CANDIDATE_LIMIT = TV_CATALOG_RAIL_ITEM_LIMIT * 4
private const val TV_CATALOG_TOP_RATED_MIN_RATING = 7.0
private const val TV_CATALOG_TOP_RATED_MIN_VOTES = 200
private val TV_CATALOG_RAIL_RETRY_DELAYS_MS = listOf(0L, 2_500L, 5_000L, 10_000L, 20_000L)

private data class GenreSpec(val id: Int, val label: String)

private data class CatalogRailSpec(
    val key: String,
    val title: String,
    val genreId: Int? = null,
    val special: String? = null,
)

private data class CatalogSearchFilterGroup(
    val key: String,
    val label: String,
    val options: List<Pair<Int, String>>,
)

private data class CatalogSearchFilterChipSpec(
    val groupKey: String,
    val id: Int,
    val label: String,
)

private data class CatalogSearchFilterVisualRow(
    val groupLabel: String?,
    val chipIndices: List<Int>,
)

private enum class CatalogSearchMode { STANDARD, AI }
private enum class CatalogSearchResultsView(val contentDescription: String, val columns: Int) {
    LIST("List view", 1),
    RAIL_3("3 by 3 grid", 3),
    RAIL_4("4 by 4 grid", 4),
    RAIL_5("5 by 5 grid", 5),
}

private enum class CatalogSearchSort(val id: Int, val label: String) {
    RELEVANCE(0, "Default"),
    IMDB_RATING(1, "IMDb Rating"),
    RT_SCORE(2, "Rotten Tomatoes"),
    NEWEST(3, "Newest"),
    OLDEST(4, "Oldest"),
    TITLE_AZ(5, "A-Z"),
    TITLE_ZA(6, "Z-A"),
    ;

    companion object {
        fun fromId(id: Int): CatalogSearchSort =
            entries.firstOrNull { it.id == id } ?: RELEVANCE
    }
}

@Composable
internal fun TvCatalogRailsScreen(
    mediaType: String,
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester?,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)? = null,
    onClearMediaFocus: (() -> Unit)? = null,
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    heroOverlay: (@Composable () -> Unit)? = null,
    shouldAutoFocus: Boolean = true,
    initialSearchQuery: String? = null,
    maxContentRating: ContentRating? = null,
    browseLayout: TvBrowseLayout = TvBrowseLayout.INFO_PANEL,
    progressResolver: ((MediaItem, Float?) -> Float?)? = null,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)? = null,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)? = null,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
    autoFocusRequestNonce: Int = 0,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val authClient: AuthClient = koinInject()
    val localSettingsRepo: DeviceLocalSettingsRepository = koinInject()
    val catalogTopCache: CatalogTopCacheRepository = koinInject()
    val ratingsEnricher: RatingsEnricher = koinInject()
    val keywordSearchService: KeywordSearchService = koinInject()
    val homeViewModel: HomeViewModel = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val prefsRepo: com.torve.domain.repository.PreferencesRepository = koinInject()
    val secretStore: com.torve.domain.integrations.IntegrationSecretStore = koinInject()
    val homeState by homeViewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val focusMemory = rememberTvFocusMemory()
    val isMovieCatalog = mediaType == "movie"
    val targetMediaType = if (isMovieCatalog) MediaType.MOVIE else MediaType.SERIES
    var searchActive by rememberSaveable(mediaType) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(mediaType) { mutableStateOf("") }
    var searchMode by rememberSaveable(mediaType) { mutableStateOf(CatalogSearchMode.STANDARD) }
    var searchResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchAiTitle by remember { mutableStateOf<String?>(null) }
    var searchAiFallback by remember { mutableStateOf(false) }
    var restoreSearchEntryFocus by remember { mutableStateOf(false) }
    var pendingSearchInitialFocusToken by remember { mutableIntStateOf(0) }
    var appliedInitialSearchQuery by rememberSaveable(mediaType) { mutableStateOf<String?>(null) }
    var searchEntryFocused by rememberSaveable(mediaType) { mutableStateOf(true) }
    val hasAiSearch = settingsState.activeAiApiKey.isNotBlank()

    LaunchedEffect(hasAiSearch, searchMode) {
        if (!hasAiSearch && searchMode == CatalogSearchMode.AI) {
            searchMode = CatalogSearchMode.STANDARD
        }
    }

    LaunchedEffect(isMovieCatalog, settingsState.traktConnected, settingsState.traktAccessToken) {
        if (!isMovieCatalog && settingsState.traktConnected) {
            homeViewModel.refreshUpcomingSchedule()
        }
    }

    LaunchedEffect(searchActive) {
        if (searchActive) {
            searchEntryFocused = true
            onClearMediaFocus?.invoke()
        }
    }

    LaunchedEffect(initialSearchQuery, mediaType) {
        val normalized = initialSearchQuery?.trim().orEmpty()
        if (normalized.isBlank() || normalized == appliedInitialSearchQuery) return@LaunchedEffect
        appliedInitialSearchQuery = normalized
        searchQuery = normalized
        searchActive = true
        searchMode = CatalogSearchMode.STANDARD
        pendingSearchInitialFocusToken++
    }

    val trendingLabel = if (isMovieCatalog) {
        stringResource(R.string.tv_section_trending_movies)
    } else {
        stringResource(R.string.tv_section_trending_shows)
    }
    val popularLabel = if (isMovieCatalog) {
        stringResource(R.string.tv_section_popular_movies)
    } else {
        stringResource(R.string.tv_section_popular_shows)
    }
    val topRatedLabel = if (isMovieCatalog) {
        stringResource(R.string.tv_section_top_rated_movies)
    } else {
        stringResource(R.string.tv_section_top_rated_shows)
    }

    val genreSpecs = if (isMovieCatalog) {
        listOf(
            GenreSpec(28, "Action Movies"),
            GenreSpec(12, "Adventure Movies"),
            GenreSpec(53, "Thriller Movies"),
            GenreSpec(35, "Comedy Movies"),
            GenreSpec(18, "Drama Movies"),
            GenreSpec(27, "Horror Movies"),
            GenreSpec(80, "Crime Movies"),
            GenreSpec(878, "Sci-Fi Movies"),
            GenreSpec(14, "Fantasy Movies"),
            GenreSpec(10749, "Romance Movies"),
            GenreSpec(9648, "Mystery Movies"),
            GenreSpec(16, "Animation Movies"),
            GenreSpec(10751, "Family Movies"),
            GenreSpec(99, "Documentaries"),
            GenreSpec(36, "History Movies"),
            GenreSpec(10752, "War Movies"),
            GenreSpec(37, "Western Movies"),
        )
    } else {
        listOf(
            GenreSpec(18, "Drama Shows"),
            GenreSpec(35, "Comedy Shows"),
            GenreSpec(10759, stringResource(R.string.tv_genre_action_adventure)),
            GenreSpec(80, "Crime Shows"),
            GenreSpec(9648, "Mystery Shows"),
            GenreSpec(10765, stringResource(R.string.tv_genre_sci_fi_fantasy)),
            GenreSpec(10768, "War & Politics"),
            GenreSpec(16, stringResource(R.string.tv_genre_animation)),
            GenreSpec(10751, "Family Shows"),
            GenreSpec(10762, "Kids Shows"),
            GenreSpec(99, "Documentaries"),
            GenreSpec(10764, "Reality Shows"),
            GenreSpec(10766, "Soap Shows"),
            GenreSpec(10767, "Talk Shows"),
            GenreSpec(10763, "News Shows"),
            GenreSpec(37, "Western Shows"),
        )
    }

    val cacheKey = "catalog_${mediaType}_trusted_v4"
    var uiState by remember {
        mutableStateOf(TvScreenCache.get<CatalogRailsUiState>(cacheKey) ?: CatalogRailsUiState())
    }

    LaunchedEffect(mediaType) {
        if (uiState.rails.isNotEmpty()) return@LaunchedEffect
        uiState = CatalogRailsUiState(loading = true)

        var lastState = CatalogRailsUiState(loading = true)
        TV_CATALOG_RAIL_RETRY_DELAYS_MS.forEachIndexed { attempt, delayMs ->
            if (delayMs > 0L) delay(delayMs)
            val loadedState = withContext(Dispatchers.IO) {
                val ratingsApiKey = runCatching {
                    secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.MDBLIST_API_KEY)
                        ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                        ?: com.torve.data.mdblist.MdbListApi.DEFAULT_API_KEY
                }.getOrDefault(com.torve.data.mdblist.MdbListApi.DEFAULT_API_KEY)
                loadCachedCatalogRails(
                    mediaType = mediaType,
                    genreSpecs = genreSpecs,
                    userId = authClient.getAuthenticatedUser()?.id,
                    localSettingsRepo = localSettingsRepo,
                    catalogTopCache = catalogTopCache,
                    metadataRepo = metadataRepo,
                    ratingsEnricher = ratingsEnricher,
                    ratingsApiKey = ratingsApiKey,
                    trendingLabel = trendingLabel,
                    popularLabel = popularLabel,
                    topRatedLabel = topRatedLabel,
                )
            }
            if (loadedState.rails.isNotEmpty()) {
                uiState = loadedState
                TvScreenCache.put(cacheKey, loadedState)
                return@LaunchedEffect
            }
            lastState = loadedState
            Log.i(
                "TvCatalogRails",
                "empty rails mediaType=$mediaType attempt=${attempt + 1}/${TV_CATALOG_RAIL_RETRY_DELAYS_MS.size} " +
                    "error=${loadedState.error ?: "none"}",
            )
            if (attempt < TV_CATALOG_RAIL_RETRY_DELAYS_MS.lastIndex) {
                uiState = CatalogRailsUiState(loading = true)
            }
        }

        val finalState = lastState.copy(
            loading = false,
            error = lastState.error ?: catalogContentLoadErrorMessage(mediaType),
        )
        uiState = finalState
        TvScreenCache.put(cacheKey, finalState)
    }

    /*
        Disabled: screen-entry network loading belongs in CatalogWarmupWorker.
        uiState = try {
            val rails = coroutineScope {
                val trendingDeferred = async { metadataRepo.getTrending(mediaType) }
                val popularDeferred = async { metadataRepo.getPopular(mediaType) }
                val topRatedDeferred = async { metadataRepo.getTopRated(mediaType) }

                val genreDeferreds = genreSpecs.map { spec ->
                    spec to async {
                        try {
                            metadataRepo.discover(
                                type = mediaType,
                                withGenres = spec.id.toString(),
                            ).items.take(24)
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    }
                }

                val trending = trendingDeferred.await().take(24)
                val popular = popularDeferred.await().take(24)
                val topRated = topRatedDeferred.await().take(24)

                buildList {
                    if (trending.isNotEmpty()) {
                        add(TvContentRail("trending_$mediaType", trendingLabel, trending))
                    }
                    if (popular.isNotEmpty()) {
                        add(TvContentRail("popular_$mediaType", popularLabel, popular))
                    }
                    if (topRated.isNotEmpty()) {
                        add(TvContentRail("top_rated_$mediaType", topRatedLabel, topRated))
                    }
                    for ((spec, deferred) in genreDeferreds) {
                        val items = deferred.await()
                        if (items.isNotEmpty()) {
                            add(
                                TvContentRail(
                                    key = "genre_${mediaType}_${spec.id}",
                                    title = spec.label,
                                    items = items,
                                ),
                            )
                        }
                    }
                }.dedupeAcrossRails()
                    .hydrateRailsFromRatingCache(ratingsEnricher)
            }
            CatalogRailsUiState(loading = false, rails = rails).also { TvScreenCache.put(cacheKey, it) }
        } catch (t: Throwable) {
            CatalogRailsUiState(loading = false, error = catalogContentLoadErrorMessage(mediaType))
        }
    }

    */

    // Background ratings enrichment — populates SQLite cache for all rail items.
    // Same pattern as HomeViewModel.refreshRatings(). Runs once after rails load.
    LaunchedEffect(cacheKey) {
        if (uiState.rails.isEmpty()) return@LaunchedEffect
        val hydrated = withContext(Dispatchers.IO) {
            uiState.rails.hydrateRailsFromRatingCache(ratingsEnricher)
        }
        if (railsRatingsChanged(uiState.rails, hydrated)) {
            uiState = uiState.copy(rails = hydrated)
            TvScreenCache.put(cacheKey, uiState)
        }
    }

    val catalogRatingPrefs = remember(settingsState.ratingPrefs) {
        settingsState.ratingPrefs.tvExternalCardRatingPrefs()
    }
    val enrichCacheKey = remember(mediaType, catalogRatingPrefs.enabledProviders) {
        val providerKey = catalogRatingPrefs.enabledProviders.joinToString("_") { it.name }
            .ifBlank { "TMDB_FALLBACK" }
        "enriched_${mediaType}_$providerKey"
    }
    LaunchedEffect(uiState.rails, enrichCacheKey) {
        if (uiState.rails.isEmpty()) return@LaunchedEffect
        if (!uiState.rails.needsRatingEnrichment()) {
            TvScreenCache.put(enrichCacheKey, true)
            return@LaunchedEffect
        }
        if (TvScreenCache.get<Boolean>(enrichCacheKey) == true) return@LaunchedEffect

        launch(Dispatchers.IO) {
            val apiKey = runCatching {
                secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: com.torve.data.mdblist.MdbListApi.DEFAULT_API_KEY
            }.getOrDefault(com.torve.data.mdblist.MdbListApi.DEFAULT_API_KEY)

            // Retry loop mirrors HomeViewModel: if MDBList rate-limits, wait out
            // the cooldown and re-enrich so RT/Metacritic pills eventually appear.
            var iterations = 0
            while (iterations < 5) {
                iterations++
                val enrichedRails = uiState.rails.map { rail ->
                    val enrichedItems = ratingsEnricher.enrichList(rail.items, apiKey)
                    rail.copy(items = enrichedItems)
                }
                withContext(Dispatchers.Main) {
                    if (railsRatingsChanged(uiState.rails, enrichedRails)) {
                        uiState = uiState.copy(rails = enrichedRails)
                    }
                    TvScreenCache.put(cacheKey, uiState)
                }
                val remainingMs = ratingsEnricher.rateLimitRemainingMs()
                if (remainingMs <= 0L) break
                kotlinx.coroutines.delay(remainingMs + 2_000L)
            }
            TvScreenCache.put(enrichCacheKey, true)
        }
    }

    val continueWatchingLabel = stringResource(
        if (isMovieCatalog) R.string.tv_section_continue_watching_movies
        else R.string.tv_section_continue_watching_shows,
    )
    var enrichedInjectedRailItems by remember(mediaType) {
        mutableStateOf<Map<String, List<MediaItem>>>(emptyMap())
    }
    var attemptedInjectedRatingKeys by remember(mediaType, catalogRatingPrefs.enabledProviders) {
        mutableStateOf<Set<String>>(emptySet())
    }

    val continueWatchingRail = remember(
        homeState.continueWatching,
        homeState.continueWatchingRatings,
        targetMediaType,
    ) {
        val items = homeState.continueWatching
            .filter { it.mediaType == targetMediaType }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { it.toMediaItemOrNull() }
            .filter { it.tmdbId != null }
            .take(20)
            .withEnrichedRatingsFrom(homeState.continueWatchingRatings)
        if (items.isEmpty()) null
        else TvContentRail(
            key = "continue_watching_$mediaType",
            title = continueWatchingLabel,
            items = items,
            cardStyle = TvCardStyle.BACKDROP,
            progressByMediaId = homeState.continueWatching
                .filter { it.mediaType == targetMediaType && it.progressPercent > 0f }
                .associate { it.mediaId to it.progressPercent },
        )
    }
    val upcomingScheduleRail = remember(
        homeState.upcomingSchedule,
        homeState.continueWatchingRatings,
        targetMediaType,
    ) {
        if (targetMediaType != MediaType.SERIES) null
        else {
            val items = homeState.upcomingSchedule
                .take(24)
                .withEnrichedRatingsFrom(homeState.continueWatchingRatings)
            if (items.isEmpty()) null
            else TvContentRail(
                key = "upcoming_schedule_tv",
                title = "Upcoming Schedule",
                items = items,
                cardStyle = TvCardStyle.BACKDROP,
            )
        }
    }

    val injectedRails = remember(continueWatchingRail, upcomingScheduleRail) {
        listOfNotNull(continueWatchingRail, upcomingScheduleRail)
    }
    val displayInjectedRails = remember(injectedRails, enrichedInjectedRailItems) {
        injectedRails.map { rail ->
            enrichedInjectedRailItems[rail.key]?.let { items -> rail.copy(items = items) } ?: rail
        }
    }

    LaunchedEffect(injectedRails, catalogRatingPrefs.enabledProviders) {
        if (injectedRails.isEmpty()) return@LaunchedEffect
        val hydratedRails = withContext(Dispatchers.IO) {
            injectedRails.hydrateRailsFromRatingCache(ratingsEnricher)
        }
        if (railsRatingsChanged(injectedRails, hydratedRails)) {
            enrichedInjectedRailItems = enrichedInjectedRailItems +
                hydratedRails.associate { rail -> rail.key to rail.items }
        }

        val itemsNeedingEnrichment = hydratedRails
            .flatMap { rail -> rail.items }
            .filter { item -> item.needsExternalRatingEnrichment() }
        val newAttemptKeys = itemsNeedingEnrichment
            .map { item -> item.ratingEnrichmentAttemptKey() }
            .filterNot { key -> key in attemptedInjectedRatingKeys }
            .toSet()
        if (newAttemptKeys.isEmpty()) return@LaunchedEffect
        attemptedInjectedRatingKeys = attemptedInjectedRatingKeys + newAttemptKeys

        val apiKey = runCatching {
            secretStore.get(com.torve.domain.integrations.IntegrationSecretKey.MDBLIST_API_KEY)
                ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                ?: com.torve.data.mdblist.MdbListApi.DEFAULT_API_KEY
        }.getOrDefault(com.torve.data.mdblist.MdbListApi.DEFAULT_API_KEY)

        withContext(Dispatchers.IO) {
            var currentRails = hydratedRails
            var iterations = 0
            while (iterations < 5 && currentRails.needsRatingEnrichment()) {
                iterations++
                val enrichedRails = currentRails.map { rail ->
                    rail.copy(items = ratingsEnricher.enrichList(rail.items, apiKey))
                }
                withContext(Dispatchers.Main) {
                    if (railsRatingsChanged(currentRails, enrichedRails)) {
                        enrichedInjectedRailItems = enrichedInjectedRailItems +
                            enrichedRails.associate { rail -> rail.key to rail.items }
                    }
                }
                currentRails = enrichedRails
                val remainingMs = ratingsEnricher.rateLimitRemainingMs()
                if (remainingMs <= 0L) break
                delay(remainingMs + 2_000L)
            }
        }
    }

    val filteredRails = remember(uiState.rails, maxContentRating, displayInjectedRails) {
        val catalogRails = if (maxContentRating == null) {
            uiState.rails
        } else {
            uiState.rails.mapNotNull { rail ->
                val filtered = ParentalFilter.filter(rail.items, maxContentRating)
                if (filtered.isEmpty()) null else rail.copy(items = filtered)
            }
        }
        displayInjectedRails + catalogRails
    }

    val defaultHeroItem = remember(filteredRails, mediaType) {
        filteredRails
            .firstOrNull { it.key.startsWith("trending_") || it.key == "trending-$mediaType" }
            ?.items
            ?.firstOrNull()
            ?: filteredRails
                .firstOrNull { !it.key.startsWith("continue_watching") && it.items.isNotEmpty() }
                ?.items
                ?.firstOrNull()
    }

    LaunchedEffect(searchActive, mediaType, defaultHeroItem?.id, defaultHeroItem?.tmdbId) {
        if (!searchActive && defaultHeroItem != null) {
            val heroRoute = if (mediaType == "movie") TvRoutes.MOVIES else TvRoutes.SHOWS
            TvScreenCache.put("featured:$heroRoute", defaultHeroItem)
            onMediaFocused?.invoke(defaultHeroItem)
        }
    }

    LaunchedEffect(searchActive, searchQuery, searchMode, mediaType) {
        if (!searchActive) return@LaunchedEffect
        val query = searchQuery.trim()
        searchAiTitle = null
        searchAiFallback = false
        if (query.length < 2) {
            searchResults = emptyList()
            searchLoading = false
            searchError = null
            return@LaunchedEffect
        }
        searchLoading = true
        searchError = null
        try {
            delay(if (searchMode == CatalogSearchMode.AI) 300 else 220)
            val raw = if (searchMode == CatalogSearchMode.AI && hasAiSearch) {
                val aiResult = keywordSearchService.searchWithAi(
                    settingsState.aiProvider,
                    settingsState.activeAiApiKey,
                    query,
                )
                searchAiTitle = aiResult.title
                resolveCatalogAiSearch(
                    aiResult = aiResult,
                    fallbackType = mediaType,
                    metadataRepo = metadataRepo,
                ).ifEmpty {
                    searchAiFallback = true
                    searchAiTitle = null
                    metadataRepo.searchMulti(query, 1).take(60)
                }
            } else {
                metadataRepo.searchMulti(query, 1).take(60)
            }
            searchResults = raw
                .filter { it.type == targetMediaType }
                .take(60)
        } catch (t: Throwable) {
            searchResults = emptyList()
            searchError = t.message ?: "Search failed"
        } finally {
            searchLoading = false
        }
    }

    val preparingMessage = stringResource(
        if (isMovieCatalog) R.string.tv_catalog_movies_preparing else R.string.tv_catalog_shows_preparing,
    )
    val emptyMessage = uiState.error ?: preparingMessage
    val searchEntryRequester = remember(mediaType) { FocusRequester() }
    val contextualSearchResults = remember(searchQuery, searchResults, filteredRails) {
        if (searchQuery.trim().length < 2) {
            filteredRails
                .flatMap { it.items }
                .dedupeByStableKey()
                .take(160)
        } else {
            searchResults
        }
    }
    val contextualSearchLoading = searchLoading && searchQuery.trim().length >= 2
    if (searchActive) {
        TvCatalogContextualSearchSurface(
            mediaType = targetMediaType,
            metadataRepo = metadataRepo,
            genreSpecs = genreSpecs,
            ratingPrefs = catalogRatingPrefs,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            searchMode = searchMode,
            onSearchModeChange = { searchMode = it },
            hasAiSearch = hasAiSearch,
            loading = contextualSearchLoading,
            error = searchError,
            results = contextualSearchResults,
            aiTitle = searchAiTitle,
            aiFallback = searchAiFallback,
            railFocusRequester = railFocusRequester,
            onFirstContentRequester = onFirstContentRequester,
            onContentFocused = onContentFocused,
            onMediaFocused = onMediaFocused,
            onClearMediaFocus = onClearMediaFocus,
            onMediaClick = onMediaClick,
            onClose = {
                searchActive = false
                searchQuery = ""
                searchResults = emptyList()
                searchError = null
                restoreSearchEntryFocus = true
                searchEntryFocused = true
            },
            initialFocusToken = pendingSearchInitialFocusToken,
            onInitialFocusConsumed = { pendingSearchInitialFocusToken = 0 },
        )
    } else {
        LaunchedEffect(restoreSearchEntryFocus, searchActive) {
            if (restoreSearchEntryFocus && !searchActive) {
                restoreSearchEntryFocus = false
                searchEntryFocused = true
                repeat(6) {
                    withFrameNanos { }
                    kotlinx.coroutines.delay(60)
                    if (runCatching { searchEntryRequester.requestFocus() }.isSuccess) {
                        onContentFocused(searchEntryRequester)
                        return@LaunchedEffect
                    }
                }
            }
        }
        val searchTitle = stringResource(
            if (isMovieCatalog) R.string.tv_catalog_search_movies else R.string.tv_catalog_search_shows,
        )
        val searchSubtitle = stringResource(
            if (isMovieCatalog) {
                R.string.tv_catalog_search_movies_subtitle
            } else {
                R.string.tv_catalog_search_shows_subtitle
            },
        )
        val upcomingScheduleMessage = remember(
            isMovieCatalog,
            homeState.upcomingScheduleStatus,
            homeState.upcomingSchedule.isNotEmpty(),
        ) {
            if (isMovieCatalog) {
                null
            } else {
                tvUpcomingScheduleStatusMessage(
                    status = homeState.upcomingScheduleStatus,
                    hasUpcomingData = homeState.upcomingSchedule.isNotEmpty(),
                    traktConnected = settingsState.traktConnected,
                )
            }
        }
        TvMediaRails(
            rails = filteredRails,
            railFocusRequester = railFocusRequester,
            headerFocusRequester = headerFocusRequester,
            onMediaClick = onMediaClick,
            onFirstContentRequester = onFirstContentRequester,
            onContentFocused = {
                searchEntryFocused = false
                onContentFocused(it)
            },
            screenId = if (isMovieCatalog) "movies" else "shows",
            focusMemory = focusMemory,
            loading = uiState.loading,
            emptyMessage = emptyMessage,
            onMediaFocused = onMediaFocused,
            onSeeAll = onSeeAll,
            heroOverlay = heroOverlay,
            presentationMode = TvRailsPresentationMode.CatalogHero,
            focusExclusive = true,
            leadingContentFocusRequester = searchEntryRequester,
            leadingContentVisible = searchEntryFocused,
            leadingContent = {
                Column(
                    modifier = Modifier
                        .height(
                            if (!searchEntryFocused) {
                                1.dp
                            } else if (upcomingScheduleMessage != null) {
                                124.dp
                            } else {
                                86.dp
                            },
                        )
                        .clipToBounds()
                        .graphicsLayer { alpha = if (searchEntryFocused) 1f else 0f }
                        .then(if (searchEntryFocused) Modifier else Modifier.clearAndSetSemantics { })
                        .padding(start = TV_PAGE_CONTENT_GUTTER, end = TV_PAGE_END_GUTTER),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TvCatalogSearchEntry(
                        title = searchTitle,
                        subtitle = searchSubtitle,
                        icon = { Icon(Icons.Default.Search, contentDescription = null, tint = Amber) },
                        modifier = Modifier
                            .focusRequester(searchEntryRequester)
                            .focusProperties {
                                canFocus = searchEntryFocused
                                left = railFocusRequester
                                headerFocusRequester?.let { up = it }
                            },
                        onFocused = {
                            searchEntryFocused = true
                            onClearMediaFocus?.invoke()
                            onContentFocused(searchEntryRequester)
                        },
                        onClick = {
                            searchMode = CatalogSearchMode.STANDARD
                            pendingSearchInitialFocusToken++
                            searchActive = true
                        },
                    )
                    upcomingScheduleMessage?.let { message ->
                        TvUpcomingScheduleStatusBanner(message)
                    }
                }
            },
            shouldAutoFocus = shouldAutoFocus,
            browseLayout = browseLayout,
            progressResolver = progressResolver,
            contextMenuActionsForItem = contextMenuActionsForItem,
            onContextMenuAction = onContextMenuAction,
            registerFocusHandle = registerFocusHandle,
            autoFocusRequestNonce = autoFocusRequestNonce,
        )
    }
}

@Composable
private fun TvCatalogSearchEntry(
    title: String,
    subtitle: String,
    modifier: Modifier,
    width: androidx.compose.ui.unit.Dp = 520.dp,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "catalogSearchEntryScale")
    val borderColor by animateColorAsState(
        if (focused) Amber else Steel.copy(alpha = 0.38f),
        label = "catalogSearchEntryBorder",
    )
    val backgroundColor by animateColorAsState(
        when {
            focused -> Amber.copy(alpha = 0.18f)
            enabled -> Charcoal.copy(alpha = 0.72f)
            else -> Charcoal.copy(alpha = 0.38f)
        },
        label = "catalogSearchEntryBackground",
    )
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .width(width)
            .height(70.dp)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(backgroundColor)
            .border(if (focused) 4.dp else 1.dp, borderColor, RoundedCornerShape(18.dp))
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Snow else Steel,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) Silver else Steel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun tvUpcomingScheduleStatusMessage(
    status: UpcomingScheduleStatus,
    hasUpcomingData: Boolean,
    traktConnected: Boolean,
): String? = when (status) {
    UpcomingScheduleStatus.LOADING -> "Loading upcoming episodes"
    UpcomingScheduleStatus.HAS_DATA -> null
    UpcomingScheduleStatus.EMPTY_CONNECTED -> "No upcoming episodes found"
    UpcomingScheduleStatus.DISCONNECTED -> if (traktConnected) {
        "Calendar did not load. Reconnect Trakt in Settings."
    } else {
        "Connect Trakt calendar to see upcoming episodes"
    }
    UpcomingScheduleStatus.STALE -> if (hasUpcomingData) {
        "Showing saved calendar data"
    } else {
        "Calendar did not load. Reconnect Trakt in Settings."
    }
    UpcomingScheduleStatus.RATE_LIMITED -> "Calendar is temporarily limited. Try again later."
    UpcomingScheduleStatus.ERROR -> if (traktConnected) {
        "Calendar did not load. Reconnect Trakt in Settings."
    } else {
        "Upcoming episodes are unavailable right now"
    }
}

@Composable
private fun TvUpcomingScheduleStatusBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Charcoal.copy(alpha = 0.70f))
            .border(1.dp, Steel.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = Snow,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvCatalogContextualSearchSurface(
    mediaType: MediaType,
    metadataRepo: MetadataRepository,
    genreSpecs: List<GenreSpec>,
    ratingPrefs: RatingDisplayPrefs,
    query: String,
    onQueryChange: (String) -> Unit,
    searchMode: CatalogSearchMode,
    onSearchModeChange: (CatalogSearchMode) -> Unit,
    hasAiSearch: Boolean,
    loading: Boolean,
    error: String?,
    results: List<MediaItem>,
    aiTitle: String?,
    aiFallback: Boolean,
    railFocusRequester: FocusRequester,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)?,
    onClearMediaFocus: (() -> Unit)?,
    onMediaClick: (MediaItem) -> Unit,
    onClose: () -> Unit,
    initialFocusToken: Int,
    onInitialFocusConsumed: () -> Unit,
) {
    val inputRequester = remember { FocusRequester() }
    val firstResultRequester = remember { FocusRequester() }
    val aiRequester = remember { FocusRequester() }
    val clearRequester = remember { FocusRequester() }
    val closeRequester = remember { FocusRequester() }
    val filterRequester = remember { FocusRequester() }
    val firstFilterRequester = remember { FocusRequester() }
    var resultsView by rememberSaveable(mediaType) { mutableStateOf(CatalogSearchResultsView.RAIL_5) }
    var searchInputEditing by remember { mutableStateOf(false) }
    var searchEditExitSignal by remember { mutableStateOf(0) }
    var showFilters by rememberSaveable(mediaType) { mutableStateOf(true) }
    var selectedGenreIds by remember(mediaType) { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedStudioIds by remember(mediaType) { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedYear by remember(mediaType) { mutableStateOf<Int?>(null) }
    var selectedMinRating by remember(mediaType) { mutableStateOf<Double?>(null) }
    var selectedSortId by rememberSaveable(mediaType) { mutableIntStateOf(CatalogSearchSort.RELEVANCE.id) }
    var hydratedResults by remember(results) { mutableStateOf(results) }
    var previewItem by remember(mediaType) { mutableStateOf<MediaItem?>(null) }
    val hasResults = results.isNotEmpty()
    val visibleResults = remember(
        hydratedResults,
        selectedGenreIds,
        selectedStudioIds,
        selectedYear,
        selectedMinRating,
        selectedSortId,
    ) {
        hydratedResults.filterCatalogSearchItems(
            genreIds = selectedGenreIds,
            studioIds = selectedStudioIds,
            year = selectedYear,
            minRating = selectedMinRating,
        ).sortedCatalogSearchItems(CatalogSearchSort.fromId(selectedSortId))
    }
    val hasVisibleResults = visibleResults.isNotEmpty()
    val availableGenres = remember(hydratedResults, genreSpecs) {
        (
            genreSpecs.map { it.id to it.label.removeSuffix(" Movies").removeSuffix(" Shows") } +
                hydratedResults.availableCatalogSearchGenres(genreSpecs)
            )
            .distinctBy { it.first }
            .filter { it.first > 0 && it.second.isNotBlank() }
    }
    val availableStudios = remember(hydratedResults) {
        hydratedResults.availableCatalogSearchStudios().take(12)
    }
    val availableYears = remember(hydratedResults) {
        hydratedResults.mapNotNull { it.year }
            .filter { it in 1900..2100 }
            .distinct()
            .sortedDescending()
            .take(12)
    }
    val aiProviderRequiredMessage = stringResource(R.string.tv_search_ai_provider_required)

    BackHandler(enabled = true) {
        if (searchInputEditing) {
            searchEditExitSignal++
        } else {
            onClose()
        }
    }

    LaunchedEffect(initialFocusToken) {
        if (initialFocusToken <= 0) return@LaunchedEffect
        onFirstContentRequester(inputRequester)
        withFrameNanos { }
        runCatching { inputRequester.requestFocus() }
        onInitialFocusConsumed()
    }

    LaunchedEffect(results) {
        selectedGenreIds = emptySet()
        selectedStudioIds = emptySet()
        selectedYear = null
        selectedMinRating = null
        selectedSortId = CatalogSearchSort.RELEVANCE.id
    }

    LaunchedEffect(visibleResults.firstOrNull()?.catalogSearchStableKey(), visibleResults.size) {
        val currentKey = previewItem?.catalogSearchStableKey()
        if (currentKey == null || visibleResults.none { it.catalogSearchStableKey() == currentKey }) {
            previewItem = visibleResults.firstOrNull()
        }
    }

    LaunchedEffect(showFilters, results) {
        if (!showFilters || results.isEmpty()) return@LaunchedEffect
        val targets = results
            .filter { it.tmdbId != null && (it.genres.isEmpty() || it.studios.isEmpty()) }
            .take(48)
        if (targets.isEmpty()) return@LaunchedEffect
        val hydrated = withContext(Dispatchers.IO) {
            targets.mapNotNull { item ->
                val tmdbId = item.tmdbId ?: return@mapNotNull null
                val type = if (item.type == MediaType.SERIES) "tv" else "movie"
                val detail = runCatching { metadataRepo.getDetail(type, tmdbId) }.getOrNull()
                    ?: return@mapNotNull null
                item.catalogSearchStableKey() to item.copy(
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
        }
        if (hydrated.isNotEmpty()) {
            hydratedResults = hydratedResults.map { item ->
                hydrated[item.catalogSearchStableKey()] ?: item
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
            .padding(
                start = 28.dp,
                top = 0.dp,
                end = 36.dp,
                bottom = 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(if (hasResults) 4.dp else 10.dp),
    ) {
        if (!hasResults) {
            Text(
                text = stringResource(
                    if (mediaType == MediaType.MOVIE) {
                        R.string.tv_catalog_search_movies_heading
                    } else {
                        R.string.tv_catalog_search_shows_heading
                    },
                ),
                style = MaterialTheme.typography.headlineMedium,
                color = Snow,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(top = 0.dp, bottom = 0.dp),
        ) {
            TorveSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(
                    if (mediaType == MediaType.MOVIE) R.string.tv_catalog_search_movies else R.string.tv_catalog_search_shows,
                ),
                onSubmit = {
                    if (hasResults) {
                        runCatching { firstResultRequester.requestFocus() }
                    }
                },
                showFocusRing = true,
                editOnClick = true,
                onMoveDownFromEdit = {
                    if (showFilters) {
                        runCatching { firstFilterRequester.requestFocus() }
                    } else if (hasVisibleResults) {
                        runCatching { firstResultRequester.requestFocus() }
                    } else {
                        runCatching { aiRequester.requestFocus() }
                    }
                },
                onMoveRightFromEdit = {
                    runCatching { aiRequester.requestFocus() }
                },
                forceExitEditSignal = searchEditExitSignal,
                onEditingChanged = { searchInputEditing = it },
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .focusRequester(inputRequester)
                    .focusProperties {
                        left = railFocusRequester
                        if (showFilters) {
                            down = firstFilterRequester
                        } else if (hasVisibleResults) {
                            down = firstResultRequester
                        }
                        right = aiRequester
                    }
                    .onFocusChanged {
                        if (it.isFocused) {
                            onClearMediaFocus?.invoke()
                            onContentFocused(inputRequester)
                        }
                    },
            )
            TvCatalogSearchChip(
                text = stringResource(R.string.tv_search_mode_ai),
                selected = searchMode == CatalogSearchMode.AI && hasAiSearch,
                enabled = true,
                modifier = Modifier
                    .focusRequester(aiRequester)
                    .focusProperties {
                        left = inputRequester
                        right = when {
                            query.isNotBlank() -> clearRequester
                            hasResults -> filterRequester
                            else -> closeRequester
                        }
                        if (showFilters) {
                            down = firstFilterRequester
                        } else if (hasVisibleResults) {
                            down = firstResultRequester
                        }
                },
                onFocused = {
                    onClearMediaFocus?.invoke()
                    onContentFocused(aiRequester)
                },
                onClick = {
                    if (hasAiSearch) {
                        onSearchModeChange(
                            if (searchMode == CatalogSearchMode.AI) {
                                CatalogSearchMode.STANDARD
                            } else {
                                CatalogSearchMode.AI
                            },
                        )
                    } else {
                        TvNotificationQueue.post(
                            aiProviderRequiredMessage,
                            NotificationType.ERROR,
                        )
                    }
                },
            )
            if (query.isNotBlank()) {
                TvCatalogSearchChip(
                    text = stringResource(R.string.common_clear),
                    selected = false,
                    modifier = Modifier
                        .focusRequester(clearRequester)
                        .focusProperties {
                            left = aiRequester
                            right = if (hasResults) filterRequester else closeRequester
                            if (showFilters) {
                                down = firstFilterRequester
                            } else if (hasVisibleResults) {
                                down = firstResultRequester
                            }
                        },
                    onFocused = {
                        onClearMediaFocus?.invoke()
                        onContentFocused(clearRequester)
                    },
                    onClick = {
                        onQueryChange("")
                        runCatching { inputRequester.requestFocus() }
                    },
                )
            }
            TvCatalogSearchChip(
                text = if (showFilters) "Hide filters" else "Filters",
                selected = showFilters,
                enabled = hasResults,
                modifier = Modifier
                    .focusRequester(filterRequester)
                    .focusProperties {
                        left = if (query.isNotBlank()) clearRequester else aiRequester
                        right = closeRequester
                        if (showFilters) {
                            down = firstFilterRequester
                        } else if (hasVisibleResults) {
                            down = firstResultRequester
                        }
                    },
                onFocused = {
                    onClearMediaFocus?.invoke()
                    onContentFocused(filterRequester)
                },
                onClick = {
                    if (hasResults) showFilters = !showFilters
                },
            )
            TvCatalogSearchChip(
                text = stringResource(R.string.common_close),
                selected = false,
                modifier = Modifier
                    .focusRequester(closeRequester)
                    .focusProperties {
                        left = when {
                            hasResults -> filterRequester
                            query.isNotBlank() -> clearRequester
                            else -> aiRequester
                        }
                        if (showFilters) {
                            down = firstFilterRequester
                        } else if (hasVisibleResults) {
                            down = firstResultRequester
                        }
                    },
                onFocused = {
                    onClearMediaFocus?.invoke()
                    onContentFocused(closeRequester)
                },
                onClick = onClose,
            )
            if (hasResults) {
                CatalogSearchResultsView.entries.forEach { option ->
                    TvCatalogSearchViewChip(
                        view = option,
                        selected = resultsView == option,
                        modifier = Modifier.focusProperties {
                            if (showFilters) {
                                down = firstFilterRequester
                            } else if (hasVisibleResults) {
                                down = firstResultRequester
                            }
                        },
                        onFocused = { onClearMediaFocus?.invoke() },
                        onClick = { resultsView = option },
                    )
                }
            }
        }

        if (showFilters) {
            TvCatalogSearchFilterRows(
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
                onSelectRating = { rating ->
                    selectedMinRating = if (selectedMinRating == rating) null else rating
                },
                selectedSortId = selectedSortId,
                onSelectSort = { sortId -> selectedSortId = sortId },
                firstFilterRequester = firstFilterRequester,
                railFocusRequester = railFocusRequester,
                upRequester = filterRequester,
                resultsRequester = firstResultRequester.takeIf { hasVisibleResults },
                onContentFocused = onContentFocused,
                onClearMediaFocus = onClearMediaFocus,
            )
        }

        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Amber)
                }
            }

            error != null -> {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ruby.copy(alpha = 0.82f),
                )
            }

            hasResults -> {
                if (aiTitle != null && searchMode == CatalogSearchMode.AI) {
                    Text(
                        text = "AI results for $aiTitle",
                        style = MaterialTheme.typography.titleMedium,
                        color = Amber,
                    )
                } else if (aiFallback) {
                    Text(
                        text = "AI search fell back to standard results",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Silver,
                    )
                }

                if (!hasVisibleResults) {
                    Text(
                        text = "No results match those filters.",
                        style = MaterialTheme.typography.titleLarge,
                        color = Silver,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(resultsView.columns),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp, start = 2.dp, end = 10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(
                            visibleResults,
                            key = { index, item ->
                                item.tmdbId?.let { "ctx_${item.type}_$it" } ?: "ctx_${item.type}_${item.id}_$index"
                            },
                        ) { index, item ->
                            val requester = remember(item.id, item.tmdbId) { FocusRequester() }
                            val activeRequester = if (index == 0) firstResultRequester else requester
                            TvCatalogSearchResultCard(
                                item = item,
                                listStyle = resultsView == CatalogSearchResultsView.LIST,
                                ratingPrefs = ratingPrefs,
                                modifier = Modifier
                                    .focusRequester(activeRequester)
                                    .then(
                                        if (resultsView == CatalogSearchResultsView.LIST) {
                                            Modifier
                                                .fillMaxWidth()
                                                .height(112.dp)
                                        } else {
                                            Modifier.aspectRatio(2f / 3f)
                                        },
                                    )
                                    .focusProperties {
                                        if (index % resultsView.columns == 0) {
                                            left = railFocusRequester
                                        }
                                        if (showFilters) {
                                            up = firstFilterRequester
                                        } else if (index < resultsView.columns) {
                                            up = inputRequester
                                        }
                                    },
                                onFocused = {
                                    onContentFocused(activeRequester)
                                    previewItem = item
                                    onClearMediaFocus?.invoke()
                                },
                                onClick = { onMediaClick(item) },
                            )
                        }
                    }
                }
            }

            query.trim().length >= 2 -> {
                Text(
                    text = "No results found.",
                    style = MaterialTheme.typography.titleLarge,
                    color = Silver,
                )
            }
        }
    }
}

@Composable
private fun TvCatalogSearchFilterRows(
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
    selectedSortId: Int,
    onSelectSort: (Int) -> Unit,
    firstFilterRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    upRequester: FocusRequester,
    resultsRequester: FocusRequester?,
    onContentFocused: (FocusRequester) -> Unit,
    onClearMediaFocus: (() -> Unit)?,
) {
    val columns = 5
    Column(
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    ) {
        val groups = remember(genres, studios, years) {
            buildList {
                if (genres.isNotEmpty()) {
                    add(CatalogSearchFilterGroup("genre", "Genre", genres))
                }
                if (studios.isNotEmpty()) {
                    add(CatalogSearchFilterGroup("studio", "Network / Studio", studios))
                }
                if (years.isNotEmpty()) {
                    add(CatalogSearchFilterGroup("year", "Year", years.map { it to it.toString() }))
                }
                add(CatalogSearchFilterGroup("rating", "Rating", listOf(7 to "7+", 8 to "8+", 9 to "9+")))
                add(CatalogSearchFilterGroup("sort", "Sort", CatalogSearchSort.entries.map { it.id to it.label }))
            }
        }
        val chips = remember(groups) {
            groups.flatMap { group ->
                group.options.map { (id, label) ->
                    CatalogSearchFilterChipSpec(group.key, id, label)
                }
            }
        }
        val visualRows = remember(groups) {
            val rows = mutableListOf<CatalogSearchFilterVisualRow>()
            var chipIndex = 0
            groups.forEach { group ->
                group.options.chunked(columns).forEachIndexed { chunkIndex, chunk ->
                    rows += CatalogSearchFilterVisualRow(
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
            List(chipKeys.size) { index ->
                if (index == 0) firstFilterRequester else FocusRequester()
            }
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.chipIndices.forEachIndexed { columnIndex, chipIndex ->
                    val chip = chips[chipIndex]
                    val requester = chipRequesters[chipIndex]
                    val previousInRow = row.chipIndices.getOrNull(columnIndex - 1)
                        ?.let { chipRequesters[it] }
                    val nextInRow = row.chipIndices.getOrNull(columnIndex + 1)
                        ?.let { chipRequesters[it] }
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
                        "sort" -> selectedSortId == chip.id
                        else -> false
                    }
                    TvCatalogSearchChip(
                        text = chip.label,
                        selected = selected,
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties {
                                left = previousInRow ?: railFocusRequester
                                right = nextInRow ?: FocusRequester.Default
                                up = previousRow ?: upRequester
                                down = nextRow ?: resultsRequester ?: FocusRequester.Default
                            },
                        onFocused = {
                            onClearMediaFocus?.invoke()
                            onContentFocused(requester)
                        },
                        onClick = {
                            when (chip.groupKey) {
                                "genre" -> onToggleGenre(chip.id)
                                "studio" -> onToggleStudio(chip.id)
                                "year" -> onSelectYear(chip.id)
                                "rating" -> onSelectRating(chip.id.toDouble())
                                "sort" -> onSelectSort(chip.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun List<MediaItem>.availableCatalogSearchGenres(
    genreSpecs: List<GenreSpec>,
): List<Pair<Int, String>> =
    flatMap { item ->
        item.genres.map { it.id to it.name }
            .ifEmpty {
                item.genreIds.mapNotNull { id ->
                    catalogSearchGenreLabel(id, genreSpecs)?.let { id to it }
                }
            }
    }
        .filter { it.first > 0 && it.second.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }

private fun List<MediaItem>.availableCatalogSearchStudios(): List<Pair<Int, String>> =
    flatMap { it.studios }
        .filter { it.id > 0 && it.name.isNotBlank() }
        .groupingBy { it.id to it.name }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }

private fun List<MediaItem>.filterCatalogSearchItems(
    genreIds: Set<Int>,
    studioIds: Set<Int>,
    year: Int?,
    minRating: Double?,
): List<MediaItem> =
    filter { item ->
        (genreIds.isEmpty() || item.genreIds.any { it in genreIds } || item.genres.any { it.id in genreIds }) &&
            (studioIds.isEmpty() || item.studios.any { it.id in studioIds }) &&
            (year == null || item.year == year) &&
            (minRating == null || item.catalogSearchRatingValue() >= minRating)
    }

private fun List<MediaItem>.sortedCatalogSearchItems(sort: CatalogSearchSort): List<MediaItem> =
    when (sort) {
        CatalogSearchSort.RELEVANCE -> this
        CatalogSearchSort.IMDB_RATING -> sortedWith(
            compareByDescending<MediaItem> { it.ratings?.imdbScore ?: -1f }
                .thenByDescending { it.catalogSearchRatingValue() },
        )
        CatalogSearchSort.RT_SCORE -> sortedByDescending { it.ratings?.rottenTomatoesScore ?: -1 }
        CatalogSearchSort.NEWEST -> sortedByDescending { it.year ?: 0 }
        CatalogSearchSort.OLDEST -> sortedBy { it.year ?: Int.MAX_VALUE }
        CatalogSearchSort.TITLE_AZ -> sortedBy { it.title.lowercase() }
        CatalogSearchSort.TITLE_ZA -> sortedByDescending { it.title.lowercase() }
    }

private fun MediaItem.catalogSearchRatingValue(): Double =
    ratings?.imdbScore?.toDouble()
        ?: ratings?.tmdbScore?.toDouble()
        ?: rating
        ?: 0.0

private fun MediaItem.catalogSearchStableKey(): String = "${type.name}:${tmdbId ?: id}"

private fun catalogSearchGenreLabel(id: Int, genreSpecs: List<GenreSpec>): String? =
    genreSpecs.firstOrNull { it.id == id }
        ?.label
        ?.removeSuffix(" Movies")
        ?.removeSuffix(" Shows")
        ?: when (id) {
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
            878 -> "Sci-Fi"
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

@Composable
private fun TvCatalogSearchChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && enabled) 1.04f else 1f, label = "catalogSearchChipScale")
    val borderColor by animateColorAsState(
        when {
            selected && focused && enabled -> Snow
            selected -> Amber
            focused && enabled -> AmberLight
            else -> Snow.copy(alpha = 0.10f)
        },
        label = "catalogSearchChipBorder",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    selected -> Amber.copy(alpha = 0.16f)
                    focused && enabled -> Graphite.copy(alpha = 0.78f)
                    else -> Charcoal.copy(alpha = 0.58f)
                },
            )
            .border(if (focused && enabled) 2.dp else 1.dp, borderColor, RoundedCornerShape(18.dp))
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) Snow else Steel,
        )
    }
}

@Composable
private fun TvCatalogSearchViewChip(
    view: CatalogSearchResultsView,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "catalogSearchViewChipScale")
    val borderColor by animateColorAsState(
        when {
            selected && focused -> Snow
            selected -> Amber
            focused -> AmberLight
            else -> Snow.copy(alpha = 0.10f)
        },
        label = "catalogSearchViewChipBorder",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    selected -> Amber.copy(alpha = 0.16f)
                    focused -> Graphite.copy(alpha = 0.78f)
                    else -> Charcoal.copy(alpha = 0.58f)
                },
            )
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        CatalogSearchResultsViewIcon(
            view = view,
            tint = if (selected && focused) Snow else if (selected || focused) AmberLight else Snow,
        )
    }
}

@Composable
private fun CatalogSearchResultsViewIcon(
    view: CatalogSearchResultsView,
    tint: Color,
) {
    if (view == CatalogSearchResultsView.LIST) {
        Column(
            modifier = Modifier
                .width(24.dp)
                .height(20.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(tint),
                )
            }
        }
        return
    }

    val gridSize = view.columns
    Column(
        modifier = Modifier.size(24.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        repeat(gridSize) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                repeat(gridSize) {
                    Box(
                        modifier = Modifier
                            .size(if (gridSize == 5) 2.6.dp else 3.2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(tint),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvCatalogSearchResultCard(
    item: MediaItem,
    listStyle: Boolean,
    ratingPrefs: RatingDisplayPrefs,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "catalogResultScale")
    val borderColor by animateColorAsState(
        if (focused) AmberLight else Color.Transparent,
        label = "catalogResultBorder",
    )
    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(if (focused) 4.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .background(if (focused) Charcoal.copy(alpha = 0.92f) else Charcoal.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
    ) {
        if (listStyle) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = item.posterUrl ?: item.backdropUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(96.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Snow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.year?.let { year ->
                        Text(
                            text = year.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Silver,
                        )
                    }
                    item.ratings.withFallbackTmdbScore(item.rating)?.let { ratings ->
                        PreferredRatingPills(
                            ratings = ratings,
                            prefs = ratingPrefs,
                        )
                    }
                    item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                        Text(
                            text = overview,
                            style = MaterialTheme.typography.bodySmall,
                            color = Silver,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp)),
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
                item.ratings.withFallbackTmdbScore(item.rating)?.let { ratings ->
                    PreferredRatingPills(
                        ratings = ratings,
                        prefs = ratingPrefs,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(7.dp),
                    )
                }
            }
        }
    }
}

private suspend fun loadCachedCatalogRails(
    mediaType: String,
    genreSpecs: List<GenreSpec>,
    userId: String?,
    localSettingsRepo: DeviceLocalSettingsRepository,
    catalogTopCache: CatalogTopCacheRepository,
    metadataRepo: MetadataRepository,
    ratingsEnricher: RatingsEnricher,
    ratingsApiKey: String,
    trendingLabel: String,
    popularLabel: String,
    topRatedLabel: String,
): CatalogRailsUiState {
    val cacheOwnerId = userId ?: PUBLIC_CATALOG_RAILS_USER_ID
    val cachedBootstrap = listOfNotNull(userId, PUBLIC_CATALOG_RAILS_USER_ID)
        .distinct()
        .firstNotNullOfOrNull { id ->
            localSettingsRepo.getString(catalogRailsBootstrapKey(id, mediaType))
                ?.let { cached ->
                    runCatching {
                        CatalogRailsBootstrapJson.decodeFromString<CatalogRailsBootstrapPayload>(cached)
                    }.getOrNull()
                }
                ?.rails
                ?.mapNotNull { rail ->
                    if (rail.items.isEmpty()) return@mapNotNull null
                    TvContentRail(
                        key = rail.key,
                        title = catalogRailTitle(rail.key, mediaType, genreSpecs, trendingLabel, popularLabel, topRatedLabel),
                        items = rail.items,
                    )
                }
                ?.takeIf { it.isNotEmpty() }
    }
        .orEmpty()
    val fromBootstrap = cachedBootstrap
    val expectedKeys = catalogRailSpecs(
        mediaType = mediaType,
        genreSpecs = genreSpecs,
        trendingLabel = trendingLabel,
        popularLabel = popularLabel,
        topRatedLabel = topRatedLabel,
    ).map { it.key }

    val bootstrapUsable = isUsableCatalogBootstrap(fromBootstrap, mediaType, expectedKeys)
    val fromCatalogTop = if (!bootstrapUsable) {
        genreSpecs.mapNotNull { spec ->
            val items = runCatching {
                catalogTopCache.getTop(mediaType, spec.id, limit = 24)
            }.getOrDefault(emptyList())
            if (items.isEmpty()) null else TvContentRail(
                key = "genre_${mediaType}_${spec.id}",
                title = spec.label,
                items = items,
            )
        }
    } else {
        emptyList()
    }

    val fromNetwork = if (!bootstrapUsable) {
        loadLiveCatalogRails(
            mediaType = mediaType,
            genreSpecs = genreSpecs,
            metadataRepo = metadataRepo,
            trendingLabel = trendingLabel,
            popularLabel = popularLabel,
            topRatedLabel = topRatedLabel,
        )
    } else {
        emptyList()
    }

    if (fromBootstrap.isEmpty() && fromCatalogTop.isEmpty() && fromNetwork.isEmpty()) {
        return CatalogRailsUiState(
            loading = false,
            error = catalogContentLoadErrorMessage(mediaType),
        )
    }

    if (fromNetwork.isNotEmpty()) {
        runCatching {
            localSettingsRepo.setString(
                catalogRailsBootstrapKey(cacheOwnerId, mediaType),
                CatalogRailsBootstrapJson.encodeToString(
                    CatalogRailsBootstrapPayload(
                        savedAtMs = System.currentTimeMillis(),
                        mediaType = mediaType,
                        rails = fromNetwork.map { rail ->
                            CatalogRailsBootstrapRail(
                                key = rail.key,
                                items = rail.items,
                            )
                        },
                    ),
                ),
            )
        }
    }

    val candidateRails = mergeCatalogRailsByKey(
        expectedKeys = expectedKeys,
        preferred = fromNetwork,
        fallback = fromBootstrap,
        lastResort = fromCatalogTop,
    )
        .hydrateRailsFromRatingCache(ratingsEnricher)
        .enrichTopRatedRailsForImdbGate(ratingsEnricher, ratingsApiKey)
    val rails = candidateRails.finalizeCatalogRails(mediaType = mediaType)

    return CatalogRailsUiState(
        loading = false,
        rails = rails,
    )
}

private fun mergeCatalogRailsByKey(
    expectedKeys: List<String>,
    preferred: List<TvContentRail>,
    fallback: List<TvContentRail>,
    lastResort: List<TvContentRail>,
): List<TvContentRail> {
    val preferredByKey = preferred.associateBy { it.key }
    val fallbackByKey = fallback.associateBy { it.key }
    val lastResortByKey = lastResort.associateBy { it.key }
    val merged = expectedKeys.mapNotNull { key ->
        preferredByKey[key]?.takeIf { it.items.isNotEmpty() }
            ?: fallbackByKey[key]?.takeIf { it.items.isNotEmpty() }
            ?: lastResortByKey[key]?.takeIf { it.items.isNotEmpty() }
    }
    val extras = (preferred + fallback + lastResort)
        .filter { rail -> rail.key !in expectedKeys && rail.items.isNotEmpty() }
        .distinctBy { it.key }
    return merged + extras
}

private fun List<TvContentRail>.finalizeCatalogRails(
    mediaType: String,
    targetCount: Int = TV_CATALOG_RAIL_ITEM_LIMIT,
): List<TvContentRail> {
    if (isEmpty()) return emptyList()
    val selectedByKey = linkedMapOf<String, List<MediaItem>>()
    val seen = linkedSetOf<String>()
    val railsByPriority = sortedWith(
        compareBy<TvContentRail> { catalogRailPriority(it.key) }
            .thenBy { indexOfFirst { candidate -> candidate.key == it.key }.takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
    )

    for (rail in railsByPriority) {
        val beforeCount = rail.items.size
        val locallySeen = linkedSetOf<String>()
        var removedByDedupe = 0
        var removedByQuality = 0
        val selected = mutableListOf<MediaItem>()

        for (item in rail.items) {
            if (!item.passesCatalogRailQuality(rail.key)) {
                removedByQuality++
                continue
            }
            val identity = item.catalogDedupeIdentity()
            if (!locallySeen.add(identity) || identity in seen) {
                removedByDedupe++
                continue
            }
            selected += item
            seen += identity
            if (selected.size >= targetCount) break
        }

        if (selected.isNotEmpty()) {
            selectedByKey[rail.key] = selected
        }
        Log.i(
            "TvCatalogRails",
            "rail_finalize mediaType=$mediaType key=${rail.key} source=${catalogRailTrustedSource(rail.key)} " +
                "candidates=$beforeCount qualityRemoved=$removedByQuality dedupeRemoved=$removedByDedupe final=${selected.size}",
        )
    }

    return mapNotNull { rail ->
        selectedByKey[rail.key]?.takeIf { it.isNotEmpty() }?.let { items ->
            rail.copy(items = items)
        }
    }
}

private fun catalogRailPriority(key: String): Int = when {
    key.startsWith("continue_watching") -> 0
    key.startsWith("watchlist") || key.startsWith("favorites") -> 1
    key.startsWith("featured") || key.startsWith("trending_") -> 2
    key.startsWith("top_rated_") -> 3
    key.startsWith("popular_") -> 4
    key == "now-playing" || key == "upcoming" -> 5
    key == "airing_today_tv" -> 5
    key.startsWith("genre_") -> 6
    else -> 7
}

private fun catalogRailTrustedSource(key: String): String = when {
    key.startsWith("trending_") -> "tmdb.trending"
    key.startsWith("popular_") -> "tmdb.popular"
    key.startsWith("top_rated_") -> "tmdb.top_rated"
    key == "now-playing" -> "tmdb.now_playing"
    key == "upcoming" -> "tmdb.upcoming"
    key == "airing_today_tv" -> "tmdb.airing_today"
    key.startsWith("genre_") -> "tmdb.discover.genre"
    else -> "configured"
}

private fun List<MediaItem>.dedupeByCatalogIdentity(): List<MediaItem> {
    val seen = linkedSetOf<String>()
    return filter { item -> seen.add(item.catalogDedupeIdentity()) }
}

private fun MediaItem.catalogDedupeIdentity(): String {
    val typeKey = type.name.lowercase()
    tmdbId?.let { return "tmdb:$typeKey:$it" }
    imdbId?.takeIf { it.isNotBlank() }?.let { return "imdb:$typeKey:${it.trim().lowercase()}" }
    val normalized = title.normalizedCatalogTitle()
    if (normalized.isNotBlank() && year != null) {
        return "title:$typeKey:$normalized:$year"
    }
    return "provider:$typeKey:${id.trim().lowercase()}"
}

private fun String.normalizedCatalogTitle(): String {
    val raw = trim().lowercase()
    val articleFixed = if (raw.endsWith(", the")) {
        "the " + raw.removeSuffix(", the").trim()
    } else {
        raw
    }
    return articleFixed.replace(Regex("[^a-z0-9]+"), "")
}

private fun MediaItem.passesCatalogRailQuality(railKey: String): Boolean {
    if (!railKey.startsWith("top_rated_")) return true
    val ratings = ratings.withFallbackTmdbScore(rating)
    val imdbScore = ratings?.imdbScore?.toDouble() ?: return false
    val imdbVotes = ratings.imdbVotes ?: return false
    return imdbScore >= TV_CATALOG_TOP_RATED_MIN_RATING &&
        imdbVotes >= TV_CATALOG_TOP_RATED_MIN_VOTES
}

private fun MediaItem.passesCatalogRailCandidateQuality(railKey: String): Boolean {
    if (!railKey.startsWith("top_rated_")) return true
    val candidateScore = listOfNotNull(
        rating,
        ratings?.tmdbScore?.toDouble(),
        ratings?.imdbScore?.toDouble(),
    ).maxOrNull() ?: return true
    return candidateScore >= 6.5
}

private fun isUsableCatalogBootstrap(
    rails: List<TvContentRail>,
    mediaType: String,
    expectedKeys: List<String>,
): Boolean {
    val keys = rails.mapTo(mutableSetOf()) { it.key }
    val hasDesktopExtraRails = if (mediaType == "movie") {
        "now-playing" in keys && "upcoming" in keys
    } else {
        "genre_tv_99" in keys
    }
    return expectedKeys.all { it in keys } &&
        rails.size >= expectedKeys.size &&
        hasDesktopExtraRails &&
        "trending_$mediaType" in keys &&
        "popular_$mediaType" in keys &&
        "top_rated_$mediaType" in keys &&
        rails.firstOrNull { it.key == "top_rated_$mediaType" }
            ?.items
            ?.take(12)
            ?.all { it.passesCatalogRailCandidateQuality("top_rated_$mediaType") } == true
}

private suspend fun loadLiveCatalogRails(
    mediaType: String,
    genreSpecs: List<GenreSpec>,
    metadataRepo: MetadataRepository,
    trendingLabel: String,
    popularLabel: String,
    topRatedLabel: String,
): List<TvContentRail> = coroutineScope {
    val specs = catalogRailSpecs(
        mediaType = mediaType,
        genreSpecs = genreSpecs,
        trendingLabel = trendingLabel,
        popularLabel = popularLabel,
        topRatedLabel = topRatedLabel,
    )
    val rails = specs.map { spec ->
        async {
            fetchCatalogRailOrNull(
                mediaType = mediaType,
                key = spec.key,
                title = spec.title,
            ) {
                loadCatalogRailItems(metadataRepo, mediaType, spec)
            }
        }
    }.awaitAll().filterNotNull()

    if (rails.isNotEmpty()) {
        Log.i("TvCatalogRails", "live rails loaded mediaType=$mediaType rails=${rails.size}")
    } else {
        Log.w("TvCatalogRails", "live rails empty mediaType=$mediaType")
    }
    rails
}

private fun catalogRailSpecs(
    mediaType: String,
    genreSpecs: List<GenreSpec>,
    trendingLabel: String,
    popularLabel: String,
    topRatedLabel: String,
): List<CatalogRailSpec> = buildList {
    add(CatalogRailSpec(key = "trending_$mediaType", title = trendingLabel, special = "trending"))
    add(CatalogRailSpec(key = "popular_$mediaType", title = popularLabel, special = "popular"))
    add(CatalogRailSpec(key = "top_rated_$mediaType", title = topRatedLabel, special = "top_rated"))
    if (mediaType == "movie") {
        add(CatalogRailSpec(key = "now-playing", title = "Now Playing", special = "now_playing"))
        add(CatalogRailSpec(key = "upcoming", title = "Upcoming", special = "upcoming"))
    } else {
        add(CatalogRailSpec(key = "airing_today_tv", title = "Airing Today", special = "airing_today"))
    }
    genreSpecs.forEach { spec ->
        add(CatalogRailSpec(key = "genre_${mediaType}_${spec.id}", title = spec.label, genreId = spec.id))
    }
}

private suspend fun loadCatalogRailItems(
    metadataRepo: MetadataRepository,
    mediaType: String,
    spec: CatalogRailSpec,
): List<MediaItem> {
    val items = mutableListOf<MediaItem>()
    for (page in 1..5) {
        val pageItems = runCatching {
            when (spec.special) {
                "trending" -> metadataRepo.getTrending(mediaType, page)
                "popular" -> metadataRepo.getPopular(mediaType, page)
                "top_rated" -> metadataRepo.getTopRated(mediaType, page)
                "now_playing" -> metadataRepo.getNowPlaying(page)
                "upcoming" -> metadataRepo.getUpcoming(page)
                "airing_today" -> metadataRepo.getAiringToday(page)
                else -> metadataRepo.discover(
                    type = mediaType,
                    withGenres = spec.genreId?.toString(),
                    sortBy = "popularity.desc",
                    page = page,
                ).items
            }
        }.getOrElse {
            loadCatalogRailFallbackItems(metadataRepo, mediaType, spec, page)
        }
        items += pageItems.filter { it.passesCatalogRailCandidateQuality(spec.key) }
        if (items.size >= TV_CATALOG_RAIL_CANDIDATE_LIMIT) break
    }
    return items
        .dedupeByCatalogIdentity()
        .take(TV_CATALOG_RAIL_CANDIDATE_LIMIT)
}

private suspend fun loadCatalogRailFallbackItems(
    metadataRepo: MetadataRepository,
    mediaType: String,
    spec: CatalogRailSpec,
    page: Int,
): List<MediaItem> {
    return runCatching {
        when (spec.special) {
            "trending", "popular" -> metadataRepo.discover(
                type = mediaType,
                sortBy = "popularity.desc",
                page = page,
            ).items
            "top_rated" -> metadataRepo.discover(
                type = mediaType,
                sortBy = "vote_average.desc",
                minRating = 7.0f,
                page = page,
            ).items
            "airing_today" -> metadataRepo.discover(
                type = "tv",
                sortBy = "popularity.desc",
                page = page,
            ).items
            else -> metadataRepo.discover(
                type = mediaType,
                withGenres = spec.genreId?.toString(),
                sortBy = "popularity.desc",
                page = page,
            ).items
        }
    }.getOrDefault(emptyList())
}

private suspend fun fetchCatalogRailOrNull(
    mediaType: String,
    key: String,
    title: String,
    loadItems: suspend () -> List<MediaItem>,
): TvContentRail? {
    val items = runCatching { loadItems() }
        .onFailure { error ->
            Log.w("TvCatalogRails", "live rail failed mediaType=$mediaType key=$key error=${error::class.simpleName}")
        }
        .getOrDefault(emptyList())
    return if (items.isEmpty()) null else TvContentRail(key, title, items)
}

private fun catalogRailTitle(
    key: String,
    mediaType: String,
    genreSpecs: List<GenreSpec>,
    trendingLabel: String,
    popularLabel: String,
    topRatedLabel: String,
): String {
    return when (key) {
        "trending_$mediaType" -> trendingLabel
        "popular_$mediaType" -> popularLabel
        "top_rated_$mediaType" -> topRatedLabel
        "now-playing" -> "Now Playing"
        "upcoming" -> "Upcoming"
        "airing_today_tv" -> "Airing Today"
        else -> genreSpecs.firstOrNull { key == "genre_${mediaType}_${it.id}" }?.label
            ?: key.substringAfterLast('_').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

private fun List<TvContentRail>.hydrateRailsFromRatingCache(
    ratingsEnricher: RatingsEnricher,
): List<TvContentRail> = map { rail ->
    rail.copy(items = ratingsEnricher.hydrateListFromCache(rail.items))
}

private suspend fun List<TvContentRail>.enrichTopRatedRailsForImdbGate(
    ratingsEnricher: RatingsEnricher,
    apiKey: String,
): List<TvContentRail> = map { rail ->
    if (!rail.key.startsWith("top_rated_") || rail.items.isEmpty()) {
        rail
    } else {
        rail.copy(items = ratingsEnricher.enrichList(rail.items, apiKey))
    }
}

private fun List<TvContentRail>.needsRatingEnrichment(): Boolean =
    any { rail -> rail.items.any { item -> item.needsExternalRatingEnrichment() } }

private fun MediaItem.ratingEnrichmentAttemptKey(): String =
    ratingEnrichmentLookupKeys().firstOrNull() ?: "${type.name}:$id"

private fun railsRatingsChanged(
    before: List<TvContentRail>,
    after: List<TvContentRail>,
): Boolean {
    if (before.size != after.size) return true
    for (railIndex in before.indices) {
        val beforeItems = before[railIndex].items
        val afterItems = after[railIndex].items
        if (beforeItems.size != afterItems.size) return true
        for (itemIndex in beforeItems.indices) {
            if (beforeItems[itemIndex].ratings != afterItems[itemIndex].ratings) return true
            if (beforeItems[itemIndex].imdbId != afterItems[itemIndex].imdbId) return true
        }
    }
    return false
}

private suspend fun resolveCatalogAiSearch(
    aiResult: KeywordSearchResult,
    fallbackType: String,
    metadataRepo: MetadataRepository,
): List<MediaItem> {
    return when {
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
            val type = aiResult.mediaType ?: fallbackType
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

        else -> {
            metadataRepo.discover(
                type = aiResult.mediaType ?: fallbackType,
                sortBy = aiResult.sortBy,
                withGenres = aiResult.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                withKeywords = aiResult.keywordIds.takeIf { it.isNotEmpty() }?.joinToString("|"),
                minRating = aiResult.minRating,
                year = aiResult.yearFrom,
                yearTo = aiResult.yearTo,
            ).items.take(60)
        }
    }
}
