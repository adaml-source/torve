package com.torve.desktop.ui.v2.shows

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import com.torve.desktop.ui.l10n.ds
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.components.V2Shelf
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import com.torve.data.ai.AiProvider
import com.torve.data.ai.KeywordSearchService
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.metadata.TmdbMappers
import com.torve.desktop.ui.v2.movies.CatalogMediaType
import com.torve.desktop.ui.v2.movies.V2CatalogAiResultsGrid
import com.torve.desktop.ui.v2.movies.V2CatalogFilterBar
import com.torve.desktop.ui.v2.seeall.SeeAllRequest
import com.torve.desktop.ui.v2.movies.rememberExtraRails
import com.torve.desktop.ui.v2.movies.tvExtraRails
import com.torve.domain.discovery.applyMoodFilter
import com.torve.domain.discovery.matchesRatingFilter
import com.torve.domain.model.MediaItem
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.catalog.CatalogViewModel
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Composable
fun V2ShowsPage(
    catalogViewModel: CatalogViewModel,
    metadataRepository: MetadataRepository,
    scrollState: ScrollState,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    onSeeAll: (SeeAllRequest) -> Unit = {},
    upcomingSchedule: List<MediaItem> = emptyList(),
    /** TV "Latest on Usenet" shelf - mirrors movies but groups
     *  per-episode NZBs under a single show poster via tmdbId. */
    nzbTvCatalogService: com.torve.desktop.adult.NzbTvCatalogService? = null,
    nzbIndexerType: String = "",
    nzbIndexerUrl: String = "",
    nzbIndexerApiKey: String = "",
    aiProvider: AiProvider = AiProvider.CLAUDE,
    aiApiKey: String = "",
    onOpenAiProviderSettings: (() -> Unit)? = null,
    keywordSearch: (() -> KeywordSearchService)? = null,
    tmdb: (() -> TmdbApiClient)? = null,
) {
    val state by catalogViewModel.state.collectAsState()
    val colors = TorveDesktopThemeTokens.colors
    val nzbScope = rememberCoroutineScope()
    var pageSearchQuery by remember { mutableStateOf("") }
    val aiProviderConfigured = aiApiKey.isNotBlank() && keywordSearch != null && tmdb != null
    var aiMode by remember { mutableStateOf(false) }
    var aiQuery by remember { mutableStateOf("") }
    var aiLoading by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var providerLogoUrls by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var selectedMoodId by remember { mutableStateOf("all") }

    LaunchedEffect(Unit) { if (!state.shelvesLoaded) catalogViewModel.loadCatalog() }
    LaunchedEffect(metadataRepository) {
        providerLogoUrls = runCatching {
            metadataRepository.getWatchProviderLogos(type = "tv", region = "US")
        }.getOrDefault(emptyMap())
    }
    LaunchedEffect(aiProviderConfigured) {
        if (!aiProviderConfigured) {
            aiMode = false
            aiItems = emptyList()
            aiError = null
        }
    }
    LaunchedEffect(nzbTvCatalogService, nzbIndexerType, nzbIndexerUrl, nzbIndexerApiKey) {
        if (nzbTvCatalogService != null && nzbIndexerUrl.isNotBlank() && nzbIndexerApiKey.isNotBlank()) {
            nzbTvCatalogService.ensureLoaded(
                scope = nzbScope,
                indexerType = nzbIndexerType,
                indexerUrl = nzbIndexerUrl,
                indexerApiKey = nzbIndexerApiKey,
            )
        }
    }
    val nzbStateFlow = nzbTvCatalogService?.state
    val nzbShows: List<com.torve.desktop.adult.NzbTvCatalogService.ShowCard> =
        if (nzbStateFlow != null) nzbStateFlow.collectAsState().value else emptyList()
    val extraRails = rememberExtraRails(metadataRepository, "tv")

    fun runAiSearch(prompt: String) {
        val search = keywordSearch ?: return
        val tmdbClient = tmdb ?: return
        if (prompt.isBlank() || !aiProviderConfigured) return
        nzbScope.launch {
            aiLoading = true
            aiError = null
            try {
                val result = search().searchWithAi(aiProvider, aiApiKey, prompt)
                val specific = result.specificItems
                    .filter { it.mediaType == "tv" }
                    .mapNotNull { item ->
                        runCatching {
                            TmdbMappers.tvToMediaItem(tmdbClient().getTvDetail(item.tmdbId))
                        }.getOrNull()
                    }
                aiItems = specific.ifEmpty {
                    tmdbClient().discoverTv(
                        page = 1,
                        sortBy = result.sortBy,
                        withGenres = result.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                        minRating = result.minRating,
                        year = result.yearFrom,
                        yearTo = result.yearTo,
                    ).results.map(TmdbMappers::tvToMediaItem)
                }
            } catch (t: Throwable) {
                aiError = t.message ?: "AI search failed."
                aiItems = emptyList()
            } finally {
                aiLoading = false
            }
        }
    }

    val heroItem = remember(state.trendingItems) { state.trendingItems.firstOrNull() }
    val backdrop = rememberCachedBitmap(heroItem?.backdropUrl)
    val logo = rememberCachedBitmap(heroItem?.logoUrl)
    val variant = (heroItem?.backdropUrl?.hashCode() ?: 0).absoluteValue % 4
    val gradTop by animateColorAsState(when (variant) { 0 -> Color(0xFF1A0E05); 1 -> Color(0xFF0A0F1E); 2 -> Color(0xFF140A1E); else -> Color(0xFF0A1614) }, label = "sGT")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vpH = maxHeight

        Box(Modifier.fillMaxSize()) {
            // Backdrop pinned behind page
            Box(Modifier.fillMaxWidth().height(vpH).background(Brush.verticalGradient(listOf(gradTop, colors.shellBackground)))) {
                backdrop?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Box(
                Modifier.fillMaxWidth().height(vpH).background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.25f to Color.Transparent,
                        0.50f to colors.shellBackground.copy(alpha = 0.50f),
                        0.72f to colors.shellBackground.copy(alpha = 0.82f),
                        1.0f to colors.shellBackground.copy(alpha = 0.96f),
                    ),
                ),
            )

            val isFilteredView = state.selectedGenreId != null ||
                state.filter.isActive ||
                state.providerId != null ||
                selectedMoodId != "all" ||
                pageSearchQuery.isNotBlank() ||
                aiMode

            if (isFilteredView) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(72.dp))
                    V2CatalogFilterBar(
                        catalogViewModel = catalogViewModel,
                        mediaType = CatalogMediaType.TV,
                        searchQuery = pageSearchQuery,
                        onSearchQueryChange = { pageSearchQuery = it },
                        aiProvider = aiProvider,
                        aiProviderConfigured = aiProviderConfigured,
                        aiMode = aiMode,
                        aiQuery = aiQuery,
                        aiLoading = aiLoading,
                        onAiModeChange = { enabled ->
                            aiMode = enabled
                            if (!enabled) {
                                aiItems = emptyList()
                                aiError = null
                            }
                        },
                        onAiQueryChange = { aiQuery = it },
                        onRunAiSearch = { runAiSearch(aiQuery) },
                        onOpenAiProviderSettings = onOpenAiProviderSettings,
                        providerLogoUrls = providerLogoUrls,
                        selectedMoodId = selectedMoodId,
                        onMoodSelected = { selectedMoodId = it },
                    )
                    if (aiMode) {
                        val filteredAiItems = remember(
                            aiItems,
                            selectedMoodId,
                            state.selectedGenreId,
                            state.filter.minRating,
                            state.filter.ratingSource,
                        ) {
                            aiItems
                                .filter { item ->
                                    state.selectedGenreId == null ||
                                        state.selectedGenreId in item.genreIds ||
                                        item.genres.any { it.id == state.selectedGenreId }
                                }
                                .filter { item -> item.matchesRatingFilter(state.filter.minRating, state.filter.ratingSource) }
                                .applyMoodFilter(selectedMoodId)
                        }
                        V2CatalogAiResultsGrid(
                            items = filteredAiItems,
                            isLoading = aiLoading,
                            error = aiError,
                            onOpenDetail = onOpenDetail,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        FilteredShowsGrid(
                            catalogViewModel = catalogViewModel,
                            onOpenDetail = onOpenDetail,
                            searchQuery = pageSearchQuery,
                            selectedMoodId = selectedMoodId,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
                return@Box
            }

            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                // Hero area
                Box(Modifier.fillMaxWidth().height(vpH * 0.75f)) {
                    if (heroItem != null) {
                        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.5f).padding(start = 72.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(color = colors.accent.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                Text(ds("TV Show"), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = colors.accent)
                            }
                            val meta = listOfNotNull(heroItem.year?.toString(), heroItem.rating?.let { String.format("%.1f", it) })
                            if (meta.isNotEmpty()) Text(meta.joinToString("  \u00B7  "), style = MaterialTheme.typography.bodySmall, color = colors.textPrimary.copy(alpha = 0.6f))
                            Box(Modifier.height(72.dp).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                                Crossfade(logo, label = "sLogo") { art ->
                                    if (art != null) Image(bitmap = art, contentDescription = heroItem.title, modifier = Modifier.height(72.dp).fillMaxWidth(0.9f), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
                                    else Text(heroItem.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            heroItem.overview?.take(160)?.let { if (it.isNotBlank()) Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TorvePrimaryButton(text = ds("Play"), onClick = { onPlay(heroItem) })
                                TorveGhostButton(text = ds("Details"), onClick = { onOpenDetail(heroItem) })
                            }
                        }
                    }
                }

                V2CatalogFilterBar(
                    catalogViewModel = catalogViewModel,
                    mediaType = CatalogMediaType.TV,
                    searchQuery = pageSearchQuery,
                    onSearchQueryChange = { pageSearchQuery = it },
                    aiProvider = aiProvider,
                    aiProviderConfigured = aiProviderConfigured,
                    aiMode = aiMode,
                    aiQuery = aiQuery,
                    aiLoading = aiLoading,
                    onAiModeChange = { enabled -> aiMode = enabled },
                    onAiQueryChange = { aiQuery = it },
                    onRunAiSearch = { runAiSearch(aiQuery) },
                    onOpenAiProviderSettings = onOpenAiProviderSettings,
                    providerLogoUrls = providerLogoUrls,
                    selectedMoodId = selectedMoodId,
                    onMoodSelected = { selectedMoodId = it },
                )

                // Filtered path is handled above (separate Column without
                // verticalScroll). When we reach here the catalog is in
                // unfiltered/rails mode.

                // Rails (10 total: 3 core + 7 extra).
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.trendingItems.isEmpty() && state.popularItems.isEmpty() && state.topRatedItems.isEmpty()) {
                        repeat(3) {
                            com.torve.desktop.ui.v2.components.V2ShelfSkeleton(
                                modifier = Modifier.padding(start = 72.dp),
                            )
                        }
                    }
                    if (upcomingSchedule.isNotEmpty()) {
                        V2Shelf(
                            ds("Upcoming Schedule"),
                            Modifier.padding(start = 72.dp),
                            onSeeAll = { onSeeAll(SeeAllRequest("upcoming_schedule", "Upcoming Schedule", upcomingSchedule)) },
                        ) {
                            upcomingSchedule.take(20).forEach {
                                V2PosterCard(it.title, it.posterUrl, Modifier.width(150.dp), upcomingScheduleDateTime(it.releaseDate), it.rating?.let { r -> String.format("%.1f", r) }, ratings = it.ratings, backdropUrl = it.backdropUrl, overview = it.overview) { onOpenDetail(it) }
                            }
                        }
                    }
                    if (state.trendingItems.size > 1) {
                        val trending = state.trendingItems.drop(1)
                        V2Shelf(ds("Trending Shows"), Modifier.padding(start = 72.dp), onSeeAll = { onSeeAll(SeeAllRequest("TRENDING_TV", "Trending TV Shows")) }) { trending.take(20).forEach { V2PosterCard(it.title, it.posterUrl, Modifier.width(150.dp), it.year?.toString(), it.rating?.let { r -> String.format("%.1f", r) }, ratings = it.ratings, backdropUrl = it.backdropUrl, overview = it.overview) { onOpenDetail(it) } } }
                    }
                    if (state.popularItems.isNotEmpty()) {
                        V2Shelf(ds("Popular"), Modifier.padding(start = 72.dp), onSeeAll = { onSeeAll(SeeAllRequest("POPULAR_TV", "Popular TV Shows")) }) { state.popularItems.take(20).forEach { V2PosterCard(it.title, it.posterUrl, Modifier.width(150.dp), it.year?.toString(), it.rating?.let { r -> String.format("%.1f", r) }, ratings = it.ratings, backdropUrl = it.backdropUrl, overview = it.overview) { onOpenDetail(it) } } }
                    }
                    if (state.topRatedItems.isNotEmpty()) {
                        V2Shelf(ds("Top Rated"), Modifier.padding(start = 72.dp), onSeeAll = { onSeeAll(SeeAllRequest("TOP_RATED_TV", "Top Rated TV Shows")) }) { state.topRatedItems.take(20).forEach { V2PosterCard(it.title, it.posterUrl, Modifier.width(150.dp), it.year?.toString(), it.rating?.let { r -> String.format("%.1f", r) }, ratings = it.ratings, backdropUrl = it.backdropUrl, overview = it.overview) { onOpenDetail(it) } } }
                    }

                    tvExtraRails.forEach { def ->
                        val items = extraRails[def.sectionId].orEmpty()
                        if (items.isNotEmpty()) {
                            V2Shelf(ds(def.title), Modifier.padding(start = 72.dp), onSeeAll = { onSeeAll(SeeAllRequest(def.sectionId, def.title)) }) {
                                items.take(20).forEach { V2PosterCard(it.title, it.posterUrl, Modifier.width(150.dp), it.year?.toString(), it.rating?.let { r -> String.format("%.1f", r) }, ratings = it.ratings, backdropUrl = it.backdropUrl, overview = it.overview) { onOpenDetail(it) } }
                            }
                        }
                    }

                    // "Latest on Usenet" - NZB releases aggregated by
                    // show. Click → standard show detail page (with
                    // seasons / episodes from TMDB). Episode-level NZB
                    // playback hints are deferred to the next session
                    // when the source-picker injection lands.
                    if (nzbShows.isNotEmpty()) {
                        V2Shelf(
                            title = ds("Latest on Usenet"),
                            modifier = Modifier.padding(start = 72.dp),
                            onSeeAll = { onSeeAll(SeeAllRequest("LATEST_ON_USENET_TV", "Latest on Usenet - TV")) },
                        ) {
                            nzbShows.take(20).forEach { card ->
                                V2PosterCard(
                                    card.match.title,
                                    card.match.posterUrl,
                                    Modifier.width(150.dp),
                                    card.match.year?.toString(),
                                    card.match.voteAverage?.let { String.format("%.1f", it) },
                                    ratings = card.mediaItem.ratings,
                                    backdropUrl = card.match.backdropUrl,
                                    overview = card.match.overview,
                                ) {
                                    com.torve.desktop.adult.NzbPlaybackHints.setTv(
                                        card.match.tmdbId,
                                        card.releases,
                                    )
                                    onOpenDetail(card.mediaItem)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

private fun upcomingScheduleDateTime(releaseDate: String?): String? {
    val raw = releaseDate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        java.time.ZonedDateTime.parse(raw)
            .withZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrElse {
        raw.take(16)
            .replace('T', ' ')
            .removeSuffix("Z")
            .takeIf { it.isNotBlank() }
    }
}

@Composable
private fun FilteredShowsGrid(
    catalogViewModel: CatalogViewModel,
    onOpenDetail: (MediaItem) -> Unit,
    searchQuery: String = "",
    selectedMoodId: String = "all",
    modifier: Modifier = Modifier,
) {
    val state by catalogViewModel.state.collectAsState()
    val colors = TorveDesktopThemeTokens.colors
    val gridState = rememberLazyGridState()
    val visibleItems = remember(state.items, searchQuery, selectedMoodId, state.filter.minRating, state.filter.ratingSource) {
        val needle = searchQuery.trim().lowercase()
        val explicitFiltered = (if (needle.isEmpty()) state.items
        else state.items.filter { it.title.lowercase().contains(needle) })
            .filter { item -> item.matchesRatingFilter(state.filter.minRating, state.filter.ratingSource) }
        explicitFiltered.applyMoodFilter(selectedMoodId)
    }

    LaunchedEffect(selectedMoodId, visibleItems.size, state.hasMore, state.isLoading, state.isLoadingMore, state.currentPage) {
        if (selectedMoodId != "all" &&
            visibleItems.size < 30 &&
            state.hasMore &&
            !state.isLoading &&
            !state.isLoadingMore &&
            state.currentPage < 5
        ) {
            catalogViewModel.loadMore()
        }
    }
    LaunchedEffect(gridState, state.items.size, state.hasMore, visibleItems.size) {
        androidx.compose.runtime.snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { lastVisible ->
            if (visibleItems.isNotEmpty() &&
                state.hasMore &&
                !state.isLoadingMore &&
                lastVisible >= visibleItems.size - 8
            ) {
                catalogViewModel.loadMore()
            }
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(start = 72.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(visibleItems, key = { it.id }) { item ->
            V2PosterCard(
                item.title,
                item.posterUrl,
                Modifier.width(150.dp),
                item.year?.toString(),
                item.rating?.let { r -> String.format("%.1f", r) },
                ratings = item.ratings,
                backdropUrl = item.backdropUrl,
                overview = item.overview,
            ) { onOpenDetail(item) }
        }
        if (state.isLoading || state.isLoadingMore) {
            item {
                Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        if (state.items.isEmpty() && !state.isLoading) {
            item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text(text = ds("No results for these filters."), color = colors.textSecondary)
                }
            }
        }
    }
}
