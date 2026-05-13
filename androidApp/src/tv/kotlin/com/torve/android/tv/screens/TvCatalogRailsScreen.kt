package com.torve.android.tv.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.torve.android.R
import com.torve.android.tv.NotificationType
import com.torve.android.tv.TvScreenCache
import com.torve.android.tv.TvNotificationQueue
import com.torve.android.tv.components.TvBrowseLayout
import com.torve.android.tv.components.TvCardStyle
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.components.dedupeAcrossRails
import com.torve.android.tv.components.rememberTvFocusMemory
import com.torve.android.tv.focus.TvScreenFocusHandle
import com.torve.android.tv.toMediaItemOrNull
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
import com.torve.domain.model.hasAnyEnabledDisplayValue
import com.torve.domain.model.withFallbackTmdbScore
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.data.mdblist.RatingsEnricher
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import org.koin.compose.koinInject

private data class CatalogRailsUiState(
    val loading: Boolean = true,
    val rails: List<TvContentRail> = emptyList(),
    val error: String? = null,
)

private data class GenreSpec(val id: Int, val label: String)

private enum class CatalogSearchMode { STANDARD, AI }
private enum class CatalogSearchResultsView(val contentDescription: String, val columns: Int) {
    LIST("List view", 1),
    RAIL_3("3 by 3 grid", 3),
    RAIL_4("4 by 4 grid", 4),
    RAIL_5("5 by 5 grid", 5),
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
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)? = null,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)? = null,
    registerFocusHandle: ((TvScreenFocusHandle?) -> Unit)? = null,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val authClient: AuthClient = koinInject()
    val localSettingsRepo: DeviceLocalSettingsRepository = koinInject()
    val catalogTopCache: CatalogTopCacheRepository = koinInject()
    val ratingsEnricher: RatingsEnricher = koinInject()
    val keywordSearchService: KeywordSearchService = koinInject()
    val homeViewModel: HomeViewModel = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
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
    var appliedInitialSearchQuery by rememberSaveable(mediaType) { mutableStateOf<String?>(null) }
    val hasAiSearch = settingsState.activeAiApiKey.isNotBlank()

    LaunchedEffect(hasAiSearch, searchMode) {
        if (!hasAiSearch && searchMode == CatalogSearchMode.AI) {
            searchMode = CatalogSearchMode.STANDARD
        }
    }

    LaunchedEffect(searchActive) {
        if (searchActive) {
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
            GenreSpec(28, stringResource(R.string.tv_genre_action)),
            GenreSpec(35, stringResource(R.string.tv_genre_comedy)),
            GenreSpec(878, stringResource(R.string.tv_genre_sci_fi)),
            GenreSpec(27, stringResource(R.string.tv_genre_horror)),
            GenreSpec(18, stringResource(R.string.tv_genre_drama)),
            GenreSpec(16, stringResource(R.string.tv_genre_animation)),
        )
    } else {
        listOf(
            GenreSpec(10759, stringResource(R.string.tv_genre_action_adventure)),
            GenreSpec(35, stringResource(R.string.tv_genre_comedy)),
            GenreSpec(18, stringResource(R.string.tv_genre_drama)),
            GenreSpec(10765, stringResource(R.string.tv_genre_sci_fi_fantasy)),
            GenreSpec(80, stringResource(R.string.tv_genre_crime)),
            GenreSpec(16, stringResource(R.string.tv_genre_animation)),
        )
    }

    val cacheKey = "catalog_$mediaType"
    var uiState by remember {
        mutableStateOf(TvScreenCache.get<CatalogRailsUiState>(cacheKey) ?: CatalogRailsUiState())
    }

    LaunchedEffect(mediaType) {
        if (uiState.rails.isNotEmpty()) return@LaunchedEffect
        uiState = CatalogRailsUiState(loading = true)
        val cachedState = withContext(Dispatchers.IO) {
            loadCachedCatalogRails(
                mediaType = mediaType,
                genreSpecs = genreSpecs,
                userId = authClient.getAuthenticatedUser()?.id,
                localSettingsRepo = localSettingsRepo,
                catalogTopCache = catalogTopCache,
                ratingsEnricher = ratingsEnricher,
                trendingLabel = trendingLabel,
                popularLabel = popularLabel,
                topRatedLabel = topRatedLabel,
            )
        }
        uiState = cachedState.also { TvScreenCache.put(cacheKey, it) }
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

    val ratingPrefs = settingsState.ratingPrefs
    val enrichCacheKey = remember(mediaType, ratingPrefs.enabledProviders) {
        val providerKey = ratingPrefs.enabledProviders.joinToString("_") { it.name }
            .ifBlank { "TMDB_FALLBACK" }
        "enriched_${mediaType}_$providerKey"
    }
    LaunchedEffect(uiState.rails, enrichCacheKey) {
        if (uiState.rails.isEmpty()) return@LaunchedEffect
        if (!uiState.rails.needsRatingEnrichment(ratingPrefs)) {
            TvScreenCache.put(enrichCacheKey, true)
            return@LaunchedEffect
        }
        if (TvScreenCache.get<Boolean>(enrichCacheKey) == true) return@LaunchedEffect

        launch(Dispatchers.IO) {
            val apiKey = runCatching {
                secretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            }.getOrDefault(MdbListApi.DEFAULT_API_KEY)

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

    */

    val continueWatchingLabel = stringResource(
        if (isMovieCatalog) R.string.tv_section_continue_watching_movies
        else R.string.tv_section_continue_watching_shows,
    )
    val continueWatchingRail = remember(homeState.continueWatching, targetMediaType) {
        val items = homeState.continueWatching
            .filter { it.mediaType == targetMediaType }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { it.toMediaItemOrNull() }
            .filter { it.tmdbId != null }
            .take(20)
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

    val filteredRails = remember(uiState.rails, maxContentRating, continueWatchingRail) {
        val catalogRails = if (maxContentRating == null) {
            uiState.rails
        } else {
            uiState.rails.mapNotNull { rail ->
                val filtered = ParentalFilter.filter(rail.items, maxContentRating)
                if (filtered.isEmpty()) null else rail.copy(items = filtered)
            }
        }
        if (continueWatchingRail != null) listOf(continueWatchingRail) + catalogRails
        else catalogRails
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
    if (searchActive) {
        TvCatalogContextualSearchSurface(
            mediaType = targetMediaType,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            searchMode = searchMode,
            onSearchModeChange = { searchMode = it },
            hasAiSearch = hasAiSearch,
            loading = searchLoading,
            error = searchError,
            results = searchResults,
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
            },
        )
    } else {
        LaunchedEffect(restoreSearchEntryFocus, searchActive) {
            if (restoreSearchEntryFocus && !searchActive) {
                restoreSearchEntryFocus = false
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
        TvMediaRails(
            rails = filteredRails,
            railFocusRequester = railFocusRequester,
            headerFocusRequester = headerFocusRequester,
            onMediaClick = onMediaClick,
            onFirstContentRequester = onFirstContentRequester,
            onContentFocused = onContentFocused,
            screenId = if (isMovieCatalog) "movies" else "shows",
            focusMemory = focusMemory,
            loading = uiState.loading,
            emptyMessage = emptyMessage,
            onMediaFocused = onMediaFocused,
            onSeeAll = onSeeAll,
            heroOverlay = heroOverlay,
            leadingContentFocusRequester = searchEntryRequester,
            leadingContent = {
                Box(
                    modifier = Modifier.padding(start = 24.dp, end = 48.dp),
                ) {
                    TvCatalogSearchEntry(
                        title = searchTitle,
                        subtitle = searchSubtitle,
                        icon = { Icon(Icons.Default.Search, contentDescription = null, tint = Amber) },
                        modifier = Modifier
                            .focusRequester(searchEntryRequester)
                            .focusProperties {
                                left = railFocusRequester
                                headerFocusRequester?.let { up = it }
                            },
                        onFocused = { onContentFocused(searchEntryRequester) },
                        onClick = {
                            searchMode = CatalogSearchMode.STANDARD
                            searchActive = true
                        },
                    )
                }
            },
            shouldAutoFocus = shouldAutoFocus,
            browseLayout = browseLayout,
            contextMenuActionsForItem = contextMenuActionsForItem,
            onContextMenuAction = onContextMenuAction,
            registerFocusHandle = registerFocusHandle,
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

@Composable
private fun TvCatalogContextualSearchSurface(
    mediaType: MediaType,
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
) {
    val inputRequester = remember { FocusRequester() }
    val firstResultRequester = remember { FocusRequester() }
    val aiRequester = remember { FocusRequester() }
    val clearRequester = remember { FocusRequester() }
    val closeRequester = remember { FocusRequester() }
    var resultsView by rememberSaveable(mediaType) { mutableStateOf(CatalogSearchResultsView.RAIL_5) }
    var searchInputEditing by remember { mutableStateOf(false) }
    var searchEditExitSignal by remember { mutableStateOf(0) }
    val hasResults = results.isNotEmpty()
    val aiProviderRequiredMessage = stringResource(R.string.tv_search_ai_provider_required)

    BackHandler(enabled = true) {
        if (searchInputEditing) {
            searchEditExitSignal++
        } else {
            onClose()
        }
    }

    LaunchedEffect(Unit) {
        onFirstContentRequester(inputRequester)
        withFrameNanos { }
        runCatching { inputRequester.requestFocus() }
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
                    if (hasResults) {
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
                    .width(if (hasResults) 320.dp else 420.dp)
                    .height(42.dp)
                    .focusRequester(inputRequester)
                    .focusProperties {
                        left = railFocusRequester
                        if (hasResults) {
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
                        right = if (query.isNotBlank()) clearRequester else closeRequester
                        if (hasResults) {
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
                            right = closeRequester
                            if (hasResults) {
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
                text = stringResource(R.string.common_close),
                selected = false,
                modifier = Modifier
                    .focusRequester(closeRequester)
                    .focusProperties {
                        left = if (query.isNotBlank()) clearRequester else aiRequester
                        if (hasResults) {
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
                            down = firstResultRequester
                        },
                        onFocused = { onClearMediaFocus?.invoke() },
                        onClick = { resultsView = option },
                    )
                }
            }
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

                LazyVerticalGrid(
                    columns = GridCells.Fixed(resultsView.columns),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp, start = 10.dp, end = 10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        results,
                        key = { index, item ->
                            item.tmdbId?.let { "ctx_${item.type}_$it" } ?: "ctx_${item.type}_${item.id}_$index"
                        },
                    ) { index, item ->
                        val requester = remember(item.id, item.tmdbId) { FocusRequester() }
                        val activeRequester = if (index == 0) firstResultRequester else requester
                        TvCatalogSearchResultCard(
                            item = item,
                            listStyle = resultsView == CatalogSearchResultsView.LIST,
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
                                    if (index < resultsView.columns) {
                                        up = inputRequester
                                    }
                                },
                            onFocused = {
                                onContentFocused(activeRequester)
                                onMediaFocused?.invoke(item)
                            },
                            onClick = { onMediaClick(item) },
                        )
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
            selected -> Amber
            focused && enabled -> AmberLight
            else -> Color.Transparent
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
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
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
            selected -> Amber
            focused -> AmberLight
            else -> Color.Transparent
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
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
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
            tint = if (selected || focused) AmberLight else Snow,
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
                AsyncImage(
                    model = item.posterUrl ?: item.backdropUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Obsidian.copy(alpha = 0.74f))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Snow,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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
    ratingsEnricher: RatingsEnricher,
    trendingLabel: String,
    popularLabel: String,
    topRatedLabel: String,
): CatalogRailsUiState {
    val fromBootstrap = userId
        ?.let { id -> localSettingsRepo.getString(catalogRailsBootstrapKey(id, mediaType)) }
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
        .orEmpty()

    val rails = if (fromBootstrap.isNotEmpty()) {
        fromBootstrap
    } else {
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
    }

    return CatalogRailsUiState(
        loading = false,
        rails = rails
            .dedupeAcrossRails()
            .hydrateRailsFromRatingCache(ratingsEnricher),
    )
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
        else -> genreSpecs.firstOrNull { key == "genre_${mediaType}_${it.id}" }?.label
            ?: key.substringAfterLast('_').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

private fun List<TvContentRail>.hydrateRailsFromRatingCache(
    ratingsEnricher: RatingsEnricher,
): List<TvContentRail> = map { rail ->
    rail.copy(items = ratingsEnricher.hydrateListFromCache(rail.items))
}

private fun List<TvContentRail>.needsRatingEnrichment(
    prefs: RatingDisplayPrefs,
): Boolean = any { rail ->
    rail.items.any { item ->
        item.ratings.withFallbackTmdbScore(item.rating)
            ?.hasAnyEnabledDisplayValue(prefs) != true
    }
}

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
