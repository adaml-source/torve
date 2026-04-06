package com.torve.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.tv.TvScreenCache
import com.torve.android.tv.components.TvBrowseLayout
import com.torve.android.tv.components.TvCardStyle
import com.torve.android.tv.components.TvContentRail
import com.torve.android.tv.components.TvMediaContextMenuAction
import com.torve.android.tv.components.TvMediaRails
import com.torve.android.tv.components.dedupeAcrossRails
import com.torve.android.tv.components.rememberTvFocusMemory
import com.torve.android.tv.toMediaItemOrNull
import com.torve.data.network.catalogContentLoadErrorMessage
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.ParentalFilter
import com.torve.domain.model.ContentRating
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.data.mdblist.MdbListApi
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.presentation.home.HomeViewModel
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private data class CatalogRailsUiState(
    val loading: Boolean = true,
    val rails: List<TvContentRail> = emptyList(),
    val error: String? = null,
)

private data class GenreSpec(val id: Int, val label: String)

@Composable
internal fun TvCatalogRailsScreen(
    mediaType: String,
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)? = null,
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    heroOverlay: (@Composable () -> Unit)? = null,
    shouldAutoFocus: Boolean = true,
    maxContentRating: ContentRating? = null,
    browseLayout: TvBrowseLayout = TvBrowseLayout.INFO_PANEL,
    contextMenuActionsForItem: ((MediaItem, Float?) -> List<TvMediaContextMenuAction>)? = null,
    onContextMenuAction: ((MediaItem, TvMediaContextMenuAction, Float?) -> Unit)? = null,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val ratingsEnricher: RatingsEnricher = koinInject()
    val prefsRepo: PreferencesRepository = koinInject()
    val secretStore: IntegrationSecretStore = koinInject()
    val homeViewModel: HomeViewModel = koinInject()
    val homeState by homeViewModel.state.collectAsState()
    val focusMemory = rememberTvFocusMemory()

    val trendingLabel = if (mediaType == "movie") {
        stringResource(R.string.tv_section_trending_movies)
    } else {
        stringResource(R.string.tv_section_trending_shows)
    }
    val popularLabel = if (mediaType == "movie") {
        stringResource(R.string.tv_section_popular_movies)
    } else {
        stringResource(R.string.tv_section_popular_shows)
    }
    val topRatedLabel = if (mediaType == "movie") {
        stringResource(R.string.tv_section_top_rated_movies)
    } else {
        stringResource(R.string.tv_section_top_rated_shows)
    }

    val genreSpecs = if (mediaType == "movie") {
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
            }
            CatalogRailsUiState(loading = false, rails = rails).also { TvScreenCache.put(cacheKey, it) }
        } catch (t: Throwable) {
            CatalogRailsUiState(loading = false, error = catalogContentLoadErrorMessage(mediaType))
        }
    }

    // Background ratings enrichment — populates SQLite cache for all rail items.
    // Same pattern as HomeViewModel.refreshRatings(). Runs once after rails load.
    val enrichCacheKey = "enriched_$mediaType"
    LaunchedEffect(uiState.rails.isNotEmpty()) {
        if (uiState.rails.isEmpty()) return@LaunchedEffect
        // Skip if already enriched this session
        if (TvScreenCache.get<Boolean>(enrichCacheKey) == true) return@LaunchedEffect
        TvScreenCache.put(enrichCacheKey, true)

        launch(Dispatchers.IO) {
            val apiKey = runCatching {
                secretStore.get(IntegrationSecretKey.MDBLIST_API_KEY)
                    ?: prefsRepo.getString(SettingsViewModel.KEY_MDBLIST_API_KEY)
                    ?: MdbListApi.DEFAULT_API_KEY
            }.getOrDefault(MdbListApi.DEFAULT_API_KEY)

            val enrichedRails = uiState.rails.map { rail ->
                val enrichedItems = ratingsEnricher.enrichList(rail.items, apiKey)
                rail.copy(items = enrichedItems)
            }
            withContext(Dispatchers.Main) {
                uiState = uiState.copy(rails = enrichedRails)
                TvScreenCache.put(cacheKey, uiState)
            }
        }
    }

    val targetMediaType = if (mediaType == "movie") MediaType.MOVIE else MediaType.SERIES
    val continueWatchingLabel = stringResource(
        if (mediaType == "movie") R.string.tv_section_continue_watching_movies
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

    val emptyMessage = uiState.error ?: stringResource(R.string.tv_no_data)
    TvMediaRails(
        rails = filteredRails,
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = onMediaClick,
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        screenId = if (mediaType == "movie") "movies" else "shows",
        focusMemory = focusMemory,
        loading = uiState.loading,
        emptyMessage = emptyMessage,
        onMediaFocused = onMediaFocused,
        onSeeAll = onSeeAll,
        heroOverlay = heroOverlay,
        shouldAutoFocus = shouldAutoFocus,
        browseLayout = browseLayout,
        contextMenuActionsForItem = contextMenuActionsForItem,
        onContextMenuAction = onContextMenuAction,
    )
}
