package com.torve.desktop.ui.v2.seeall

import androidx.compose.animation.Crossfade
import com.torve.desktop.ui.l10n.ds
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.DesktopRatingPills
import com.torve.desktop.ui.v2.components.V2FloatingBackButton
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.desktop.ui.v2.components.rememberCachedBitmap
import com.torve.desktop.ui.v2.discovery.BrandFilterUiModel
import com.torve.desktop.ui.v2.discovery.DiscoveryControls
import com.torve.desktop.ui.v2.discovery.DiscoveryDropdownKey
import com.torve.desktop.ui.v2.discovery.DiscoveryDropdownOption
import com.torve.desktop.ui.v2.discovery.DiscoveryDropdownUiModel
import com.torve.desktop.ui.v2.discovery.GenreFilterUiModel
import com.torve.desktop.ui.v2.discovery.MoodFilterUiModel
import com.torve.desktop.ui.v2.discovery.mixedDiscoveryFilterConfig
import com.torve.desktop.ui.v2.discovery.movieDiscoveryFilterConfig
import com.torve.desktop.ui.v2.discovery.tvDiscoveryFilterConfig
import com.torve.domain.discovery.DiscoveryRatingSource
import com.torve.domain.discovery.applyMoodFilter
import com.torve.domain.discovery.matchesRatingFilter
import com.torve.domain.discovery.nextSelectedMoodId
import com.torve.domain.discovery.ratingThresholdLabel
import com.torve.domain.model.CardHoverPrefs
import com.torve.domain.model.CardStyle
import com.torve.domain.model.MediaCompany
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.seeall.SeeAllSortMode
import com.torve.presentation.seeall.SeeAllViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val sortOptions = listOf(
    SeeAllSortMode.DEFAULT to "Default",
    SeeAllSortMode.A_Z to "Title A-Z",
    SeeAllSortMode.Z_A to "Title Z-A",
    SeeAllSortMode.YEAR_DESC to "Newest",
    SeeAllSortMode.YEAR_ASC to "Oldest",
    SeeAllSortMode.IMDB_DESC to "Highest rated (IMDb)",
    SeeAllSortMode.TMDB_DESC to "Highest rated (TMDB)",
)

private fun upcomingScheduleDateTime(value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        java.time.ZonedDateTime.parse(raw)
            .withZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrElse {
        raw.take(16).replace('T', ' ').removeSuffix("Z").takeIf { it.isNotBlank() }
    }
}

private fun MediaItem.matchesSeeAllQuery(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isBlank()) return true
    val haystack = buildString {
        append(title.lowercase())
        append(' ')
        append(overview.orEmpty().lowercase())
        append(' ')
        append(director.orEmpty().lowercase())
        append(' ')
        append(genres.joinToString(" ") { it.name }.lowercase())
        append(' ')
        append(cast.take(8).joinToString(" ") { it.name }.lowercase())
    }
    return q in haystack
}

private enum class RatingBucket(val threshold: Float?) {
    ANY(null),
    R6(6f),
    R7(7f),
    R8(8f),
    R9(9f),
}

private val seeAllTvMoodChips = listOf(
    MoodFilterUiModel("all", "All moods"),
    MoodFilterUiModel("dark", "Dark"),
    MoodFilterUiModel("funny", "Funny"),
    MoodFilterUiModel("cinematic", "Cinematic"),
    MoodFilterUiModel("fast", "Fast-paced"),
    MoodFilterUiModel("comfort", "Comfort watch"),
    MoodFilterUiModel("acclaimed", "Critically acclaimed"),
    MoodFilterUiModel("hidden", "Hidden gems"),
)

private val seeAllMovieMoodChips = listOf(
    MoodFilterUiModel("all", "All moods"),
    MoodFilterUiModel("blockbuster", "Blockbuster"),
    MoodFilterUiModel("acclaimed", "Critically acclaimed"),
    MoodFilterUiModel("easy", "Easy watch"),
    MoodFilterUiModel("date", "Date night"),
    MoodFilterUiModel("dark", "Dark"),
    MoodFilterUiModel("funny", "Funny"),
    MoodFilterUiModel("mind", "Mind-bending"),
    MoodFilterUiModel("hidden", "Hidden gems"),
    MoodFilterUiModel("family", "Family night"),
)

private val majorUsNetworkOrder = listOf(
    "abc",
    "cbs",
    "nbc",
    "fox",
    "the cw",
    "pbs",
    "hbo",
    "showtime",
    "starz",
    "amc",
    "fx",
    "fxx",
    "usa network",
    "tnt",
    "tbs",
    "espn",
).withIndex().associate { it.value to it.index }

private val majorUsNetworkAliases = setOf(
    "abc",
    "american broadcasting company",
    "cbs",
    "columbia broadcasting system",
    "nbc",
    "national broadcasting company",
    "fox",
    "fox broadcasting company",
    "the cw",
    "cw",
    "pbs",
    "public broadcasting service",
    "hbo",
    "home box office",
    "showtime",
    "starz",
    "amc",
    "fx",
    "fxx",
    "usa network",
    "tnt",
    "tbs",
    "espn",
)

private fun MediaCompany.normalizedNetworkKey(): String =
    name.lowercase()
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9+ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .let { normalized ->
            when (normalized) {
                "american broadcasting company" -> "abc"
                "columbia broadcasting system" -> "cbs"
                "national broadcasting company" -> "nbc"
                "fox broadcasting company" -> "fox"
                "cw" -> "the cw"
                "home box office" -> "hbo"
                "public broadcasting service" -> "pbs"
                else -> normalized
            }
        }

private fun MediaCompany.isMajorUsNetworkBrand(): Boolean =
    normalizedNetworkKey() in majorUsNetworkAliases

private data class SeeAllScrollSnapshot(
    val selectedIndex: Int = 0,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

private object SeeAllDesktopStateHolder {
    private val snapshots = mutableMapOf<String, SeeAllScrollSnapshot>()

    fun get(sectionId: String): SeeAllScrollSnapshot =
        snapshots[sectionId] ?: SeeAllScrollSnapshot()

    fun put(sectionId: String, snapshot: SeeAllScrollSnapshot) {
        snapshots[sectionId] = snapshot
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun V2SeeAllPage(
    request: SeeAllRequest,
    viewModel: SeeAllViewModel,
    metadataRepository: MetadataRepository,
    onBack: () -> Unit,
    onPlay: (MediaItem) -> Unit = {},
    onOpenDetail: (MediaItem) -> Unit,
    onOpenPerson: (Int) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val state by viewModel.state.collectAsState()

    LaunchedEffect(request.sectionId) {
        val shouldInitializeSort = state.sectionId != request.sectionId || state.items.isEmpty()
        val usesFallbackSnapshot = request.sectionId.startsWith("shelf:") ||
            request.sectionId == "upcoming_schedule"
        if (usesFallbackSnapshot && request.fallbackItems.isNotEmpty()) {
            SeeAllViewModel.pendingItems[request.sectionId] = request.title to request.fallbackItems
        }
        if (request.sectionId.startsWith("shelf:") && request.fallbackItems.isNotEmpty()) {
            val key = request.sectionId.removePrefix("shelf:")
            SeeAllViewModel.pendingItems[key] = request.title to request.fallbackItems
        }
        viewModel.loadSection(request.sectionId)
        if (shouldInitializeSort) {
            viewModel.setSortMode(
                if (request.sectionId.startsWith("upcoming_schedule")) {
                    SeeAllSortMode.YEAR_ASC
                } else {
                    SeeAllSortMode.DEFAULT
                },
            )
        }
    }

    val displayed = state.displayedItems
    var ratingBucket by remember(request.sectionId) { mutableStateOf(RatingBucket.ANY) }
    var ratingSource by remember(request.sectionId) { mutableStateOf(DiscoveryRatingSource.AnyRating) }
    var seeAllSearchQuery by remember(request.sectionId) { mutableStateOf("") }
    var selectedMoodId by remember(request.sectionId) { mutableStateOf("all") }
    val isTvSection = displayed.any { it.type == MediaType.SERIES } ||
        request.sectionId.contains("TV", ignoreCase = true) ||
        request.sectionId.startsWith("upcoming_schedule")
    val isMovieSection = displayed.isNotEmpty() && displayed.all { it.type == MediaType.MOVIE }
    val majorNetworkBrands = remember(state.availableStudioBrands, isTvSection) {
        if (!isTvSection) emptyList()
        else state.availableStudioBrands
            .filter { it.isMajorUsNetworkBrand() }
            .distinctBy { it.normalizedNetworkKey() }
            .sortedWith(
                compareBy<MediaCompany> { majorUsNetworkOrder[it.normalizedNetworkKey()] ?: Int.MAX_VALUE }
                    .thenBy { it.name },
            )
    }
    LaunchedEffect(request.sectionId, majorNetworkBrands, state.filterStudioIds) {
        val visibleIds = majorNetworkBrands.map { it.id }.toSet()
        if (state.filterStudioIds.any { it !in visibleIds }) {
            viewModel.clearStudios()
        }
    }
    val locallyFiltered = remember(displayed, ratingBucket, ratingSource, seeAllSearchQuery, selectedMoodId) {
        val explicitFiltered = displayed
            .filter { item -> item.matchesRatingFilter(ratingBucket.threshold, ratingSource) }
            .filter { item -> item.matchesSeeAllQuery(seeAllSearchQuery) }
        explicitFiltered.applyMoodFilter(selectedMoodId)
    }
    val displayTotalCount = when {
        seeAllSearchQuery.isBlank() &&
            ratingBucket == RatingBucket.ANY &&
            state.filterGenreIds.isEmpty() &&
            state.filterStudioIds.isEmpty() &&
            selectedMoodId == "all" &&
            state.totalResults > locallyFiltered.size -> state.totalResults
        state.hasMore -> locallyFiltered.size
        else -> locallyFiltered.size
    }
    val savedSnapshot = remember(request.sectionId) { SeeAllDesktopStateHolder.get(request.sectionId) }
    var selectedIndex by remember(request.sectionId) { mutableStateOf(savedSnapshot.selectedIndex) }
    val safeIndex = selectedIndex.coerceIn(0, (locallyFiltered.size - 1).coerceAtLeast(0))
    val baseSelected = locallyFiltered.getOrNull(safeIndex)

    // Cache TMDB-fetched detail per item id. The hydrated detail fills metadata gaps,
    // including ratings, while list-level enrichment remains free to replace them later.
    val detailCache = remember(request.sectionId) { mutableStateMapOf<String, MediaItem>() }
    LaunchedEffect(baseSelected?.id, baseSelected?.tmdbId) {
        val base = baseSelected ?: return@LaunchedEffect
        if (detailCache.containsKey(base.id)) return@LaunchedEffect
        val tmdbId = base.tmdbId ?: return@LaunchedEffect
        val type = if (base.type == MediaType.SERIES) "tv" else "movie"
        val detail = runCatching { withContext(Dispatchers.IO) { metadataRepository.getDetail(type, tmdbId) } }.getOrNull()
        if (detail != null) detailCache[base.id] = detail
    }
    val enrichedSelected: MediaItem? = baseSelected?.let { base ->
        val detail = detailCache[base.id]
        if (detail == null) base
        else base.copy(
            cast = base.cast.ifEmpty { detail.cast },
            director = base.director?.takeIf { it.isNotBlank() } ?: detail.director,
            directorId = base.directorId ?: detail.directorId,
            directorProfileUrl = base.directorProfileUrl ?: detail.directorProfileUrl,
            genres = base.genres.ifEmpty { detail.genres },
            overview = base.overview?.takeIf { it.isNotBlank() } ?: detail.overview,
            logoUrl = base.logoUrl ?: detail.logoUrl,
            backdropUrl = base.backdropUrl ?: detail.backdropUrl,
            runtime = base.runtime ?: detail.runtime,
            tagline = base.tagline ?: detail.tagline,
            rating = base.rating ?: detail.rating,
            ratings = base.ratings ?: detail.ratings,
        )
    }

    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = savedSnapshot.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = savedSnapshot.firstVisibleItemScrollOffset,
    )
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)
    DisposableEffect(request.sectionId) {
        onDispose {
            SeeAllDesktopStateHolder.put(
                request.sectionId,
                SeeAllScrollSnapshot(
                    selectedIndex = latestSelectedIndex,
                    firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset,
                ),
            )
        }
    }
    val ratingSourceContributes = ratingBucket != RatingBucket.ANY &&
        ratingSource != DiscoveryRatingSource.AnyRating
    val hasActiveLocalFilter = seeAllSearchQuery.isNotBlank() ||
        ratingBucket != RatingBucket.ANY ||
        state.filterGenreIds.isNotEmpty() ||
        state.filterStudioIds.isNotEmpty() ||
        selectedMoodId != "all"
    val nearEnd by remember(locallyFiltered.size, hasActiveLocalFilter) {
        derivedStateOf {
            if (locallyFiltered.isEmpty()) {
                hasActiveLocalFilter
            } else {
                val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                last >= (locallyFiltered.size - 8)
            }
        }
    }
    LaunchedEffect(nearEnd, state.hasMore, state.isLoading, hasActiveLocalFilter, state.page) {
        val canProbeMoreFilteredPages = hasActiveLocalFilter && state.page <= 50
        if (nearEnd && state.hasMore && !state.isLoading && (locallyFiltered.isNotEmpty() || canProbeMoreFilteredPages)) {
            viewModel.loadMore()
        }
    }
    LaunchedEffect(hasActiveLocalFilter, locallyFiltered.size, state.hasMore, state.isLoading, state.page) {
        if (hasActiveLocalFilter &&
            locallyFiltered.size < 60 &&
            state.hasMore &&
            !state.isLoading &&
            state.page <= 50
        ) {
            viewModel.loadMore()
        }
    }

    LaunchedEffect(safeIndex) {
        val info = gridState.layoutInfo.visibleItemsInfo
        val firstVisible = info.firstOrNull()?.index ?: 0
        val lastVisible = info.lastOrNull()?.index ?: 0
        if (safeIndex < firstVisible || safeIndex > lastVisible) {
            runCatching { gridState.animateScrollToItem(safeIndex) }
        }
    }

    var columns by remember { mutableStateOf(6) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(request.sectionId) { runCatching { focusRequester.requestFocus() } }

    var filtersExpanded by remember(request.sectionId) { mutableStateOf(true) }
    val browseMode by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > columns }
    }
    LaunchedEffect(browseMode) {
        if (browseMode) filtersExpanded = false
    }
    val activeFilterCount = listOf(
        seeAllSearchQuery.isNotBlank(),
        ratingBucket != RatingBucket.ANY,
        ratingSourceContributes,
        state.filterGenreIds.isNotEmpty(),
        state.filterStudioIds.isNotEmpty(),
        selectedMoodId != "all",
    ).count { it }
    val scope = rememberCoroutineScope()
    val browsePosterStyle = remember {
        CardStyle(
            hover = CardHoverPrefs(
                enabled = true,
                scalePercent = 103,
                elevationOnHover = true,
                borderOnHover = true,
            ),
        )
    }
    val fastScrollAnchors = remember(locallyFiltered, state.sortMode, ratingBucket, state.hasMore, displayTotalCount) {
        fastScrollAnchorsFor(
            items = locallyFiltered,
            sortMode = state.sortMode,
            ratingBucket = ratingBucket,
            hasMore = state.hasMore,
            totalResults = displayTotalCount,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.shellBackground)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val size = locallyFiltered.size
                if (size == 0) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { selectedIndex = (safeIndex - 1).coerceAtLeast(0); true }
                    Key.DirectionRight -> { selectedIndex = (safeIndex + 1).coerceAtMost(size - 1); true }
                    Key.DirectionUp -> { selectedIndex = (safeIndex - columns).coerceAtLeast(0); true }
                    Key.DirectionDown -> { selectedIndex = (safeIndex + columns).coerceAtMost(size - 1); true }
                    Key.Enter, Key.NumPadEnter -> {
                        locallyFiltered.getOrNull(safeIndex)?.let { onOpenDetail(it) }
                        true
                    }
                    Key.Escape -> { onBack(); true }
                    else -> false
                }
            },
    ) {
        SeeAllLiquidBackdrop(enrichedSelected)
        Column(Modifier.fillMaxSize()) {
            // Header - leave space on the right so sort button doesn't collide with user badge.
            Row(
                Modifier.fillMaxWidth().padding(start = 72.dp, end = 200.dp, top = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                V2FloatingBackButton(onBack = onBack, contentDescription = ds("Back"))
                Text(
                    state.title.ifBlank { request.title },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${formatCount(displayTotalCount)}${if (state.hasMore && displayTotalCount == locallyFiltered.size) "+" else ""} items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                Spacer(Modifier.weight(1f))
            }

            Column(
                Modifier.fillMaxWidth().padding(start = 72.dp, end = 28.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val config = when {
                    isTvSection -> tvDiscoveryFilterConfig(
                        providerAvailable = false,
                        networkAvailable = majorNetworkBrands.isNotEmpty(),
                        aiAvailable = false,
                        geminiReadyAvailable = false,
                        showYear = false,
                        showStatus = false,
                        showRuntime = false,
                        showRating = true,
                        showLanguage = false,
                    )
                    isMovieSection -> movieDiscoveryFilterConfig(
                        aiAvailable = false,
                        geminiReadyAvailable = false,
                        showYear = false,
                        showRuntime = false,
                        showRating = true,
                        showLanguage = false,
                    )
                    else -> mixedDiscoveryFilterConfig(
                        aiAvailable = false,
                        geminiReadyAvailable = false,
                        showYear = false,
                    )
                }
                val ratingSourceOptions = listOf(
                    DiscoveryRatingSource.AnyRating,
                    DiscoveryRatingSource.TMDB,
                    DiscoveryRatingSource.IMDB,
                    DiscoveryRatingSource.TRAKT,
                    DiscoveryRatingSource.ROTTEN_TOMATOES,
                    DiscoveryRatingSource.METACRITIC,
                )
                val dropdowns = listOf(
                    DiscoveryDropdownUiModel(
                        key = DiscoveryDropdownKey.Rating,
                        label = "Rating",
                        value = ratingThresholdLabel(ratingBucket.threshold),
                        options = RatingBucket.entries.map { bucket ->
                            DiscoveryDropdownOption(ratingThresholdLabel(bucket.threshold)) {
                                ratingBucket = bucket
                            }
                        },
                    ),
                    DiscoveryDropdownUiModel(
                        key = DiscoveryDropdownKey.RatingSource,
                        label = "Rating source",
                        value = ratingSource.shortLabel,
                        options = ratingSourceOptions.map { source ->
                            DiscoveryDropdownOption(source.label) {
                                ratingSource = source
                            }
                        },
                    ),
                    DiscoveryDropdownUiModel(
                        key = DiscoveryDropdownKey.Sort,
                        label = "Sort",
                        value = sortOptions.firstOrNull { it.first == state.sortMode }?.second ?: "Default",
                        options = sortOptions.map { (mode, label) ->
                            DiscoveryDropdownOption(label) { viewModel.setSortMode(mode) }
                        },
                    ),
                )
                val genreChips = listOf(GenreFilterUiModel("all", "All genres")) +
                    state.availableGenres.take(20).map { (id, name) -> GenreFilterUiModel(id.toString(), name) }
                val networkChips = listOf(BrandFilterUiModel("all", "All networks")) +
                    majorNetworkBrands.map { studio ->
                        BrandFilterUiModel(studio.id.toString(), studio.name, studio.logoUrl)
                    }
                val moodChips = if (isTvSection) seeAllTvMoodChips else seeAllMovieMoodChips
                DiscoveryControls(
                    config = config,
                    query = seeAllSearchQuery,
                    onQueryChange = { seeAllSearchQuery = it },
                    filtersExpanded = filtersExpanded,
                    onFiltersClick = { filtersExpanded = !filtersExpanded },
                    activeFilterCount = activeFilterCount,
                    canReset = seeAllSearchQuery.isNotBlank() ||
                        ratingBucket != RatingBucket.ANY ||
                        ratingSource != DiscoveryRatingSource.AnyRating ||
                        state.filterGenreIds.isNotEmpty() ||
                        state.filterStudioIds.isNotEmpty() ||
                        selectedMoodId != "all",
                    onResetClick = {
                        seeAllSearchQuery = ""
                        ratingBucket = RatingBucket.ANY
                        ratingSource = DiscoveryRatingSource.AnyRating
                        selectedMoodId = "all"
                        viewModel.clearFilters()
                    },
                    dropdowns = dropdowns,
                    networks = if (isTvSection) networkChips else emptyList(),
                    genres = genreChips,
                    moods = moodChips,
                    selectedNetworkIds = setOf(
                        if (state.filterStudioIds.isEmpty()) "all" else "",
                    ) + state.filterStudioIds.map { it.toString() },
                    selectedGenreIds = setOf(
                        if (state.filterGenreIds.isEmpty()) "all" else "",
                    ) + state.filterGenreIds.map { it.toString() },
                    selectedMoodIds = setOf(selectedMoodId),
                    onNetworkClick = { network ->
                        if (network.id == "all") viewModel.clearStudios()
                        else network.id.toIntOrNull()?.let { viewModel.toggleStudio(it) }
                    },
                    onGenreClick = { genre ->
                        if (genre.id == "all") viewModel.clearGenres()
                        else genre.id.toIntOrNull()?.let { viewModel.toggleGenre(it) }
                    },
                    onMoodClick = { mood -> selectedMoodId = nextSelectedMoodId(selectedMoodId, mood.id) },
                )
            }

            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(start = 72.dp, bottom = 24.dp)) {
                    if (locallyFiltered.isEmpty() && !state.isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(ds("No items match your filters."), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
                        }
                    } else {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val minCell = 160
                            val availablePx = constraints.maxWidth
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            val minCellPx = with(density) { minCell.dp.toPx() }.toInt().coerceAtLeast(1)
                            val computedCols = (availablePx / minCellPx).coerceAtLeast(1)
                            LaunchedEffect(computedCols) { columns = computedCols }

                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Adaptive(minSize = minCell.dp),
                                modifier = Modifier.fillMaxSize().padding(end = 76.dp),
                                contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                itemsIndexed(locallyFiltered, key = { _, item -> item.id + ":" + item.type.name }) { idx, item ->
                                    val isSelected = idx == safeIndex
                                    Box(
                                        Modifier
                                            .padding(3.dp)
                                            .graphicsLayer {
                                                scaleX = if (isSelected) 1.025f else 1f
                                                scaleY = if (isSelected) 1.025f else 1f
                                            }
                                            .background(
                                                if (isSelected) colors.accent.copy(alpha = 0.15f) else Color.Transparent,
                                                RoundedCornerShape(12.dp),
                                            )
                                            .padding(3.dp),
                                    ) {
                                        V2PosterCard(
                                            title = item.title,
                                            imageUrl = item.posterUrl,
                                            modifier = Modifier.width(160.dp),
                                            cardStyle = browsePosterStyle,
                                            year = if (item.id.startsWith("trakt-calendar:")) {
                                                upcomingScheduleDateTime(item.releaseDate)
                                            } else {
                                                item.year?.toString()
                                            },
                                            rating = item.rating?.let { String.format("%.1f", it) },
                                            ratings = item.ratings,
                                            backdropUrl = item.backdropUrl,
                                            overview = item.overview,
                                            onClick = { selectedIndex = idx },
                                        )
                                    }
                                }
                            }
                            if (fastScrollAnchors.isNotEmpty() && (displayTotalCount > 500 || state.hasMore)) {
                                FastScrollRail(
                                    anchors = fastScrollAnchors,
                                    positionLabel = "${formatCount((safeIndex + 1).coerceAtMost(displayTotalCount))} / ${formatCount(displayTotalCount)}",
                                    onJump = { index ->
                                        selectedIndex = index.coerceIn(0, (locallyFiltered.size - 1).coerceAtLeast(0))
                                        scope.launch { gridState.animateScrollToItem(selectedIndex) }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 18.dp),
                                )
                            }
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(gridState),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .padding(end = 4.dp),
                            )
                        }
                    }
                    if (state.isLoading && locallyFiltered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.accent)
                        }
                    } else if (state.isLoading) {
                        Box(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp).align(Alignment.BottomCenter),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(420.dp)
                        .padding(end = 28.dp, bottom = 24.dp),
                ) {
                    InfoPanel(
                        item = enrichedSelected,
                        onPlay = onPlay,
                        onOpenDetail = onOpenDetail,
                        onOpenPerson = onOpenPerson,
                    )
                }
            }
        }
    }
}

private data class FastScrollAnchor(
    val label: String,
    val index: Int,
)

private fun formatCount(value: Int): String =
    String.format(Locale.US, "%,d", value)

@Composable
private fun SeeAllLiquidBackdrop(item: MediaItem?) {
    val colors = TorveDesktopThemeTokens.colors
    val backdropUrl = item
        ?.takeUnless { it.isContentPlaceholder || it.isStubDetail }
        ?.let { it.backdropUrl ?: it.posterUrl }
    val backdrop = rememberCachedBitmap(backdropUrl)
    Box(Modifier.fillMaxSize().background(colors.shellBackground)) {
        Crossfade(backdrop, label = "seeAllBackdrop") { bmp ->
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(
                        colors.accent.copy(alpha = 0.16f),
                        Color(0xFF0B1220).copy(alpha = 0.76f),
                        colors.shellBackground.copy(alpha = 0.98f),
                    ),
                    radius = 1300f,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = 0.38f),
                    0.34f to Color.Black.copy(alpha = 0.50f),
                    1.0f to colors.shellBackground.copy(alpha = 0.98f),
                ),
            ),
        )
    }
}

@Composable
private fun FastScrollRail(
    anchors: List<FastScrollAnchor>,
    positionLabel: String,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    Column(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.34f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            positionLabel,
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        anchors.take(12).forEach { anchor ->
            Surface(
                modifier = Modifier
                    .width(54.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onJump(anchor.index) },
                    ),
                color = colors.fieldSurface.copy(alpha = 0.42f),
                shape = RoundedCornerShape(999.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
            ) {
                Text(
                    anchor.label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun fastScrollAnchorsFor(
    items: List<MediaItem>,
    sortMode: SeeAllSortMode,
    ratingBucket: RatingBucket,
    hasMore: Boolean = false,
    totalResults: Int = items.size,
): List<FastScrollAnchor> {
    if (items.isEmpty()) return emptyList()
    if (items.size < 20 && !hasMore && totalResults < 500) return emptyList()
    val anchors = linkedMapOf<String, Int>()
    fun add(label: String, index: Int) {
        anchors.putIfAbsent(label, index.coerceIn(0, items.lastIndex))
    }

    when (sortMode) {
        SeeAllSortMode.YEAR_DESC, SeeAllSortMode.YEAR_ASC -> {
            val yearAnchors = listOf(2026, 2025, 2024, 2020, 2010, 2000)
            yearAnchors.forEach { year ->
                val idx = items.indexOfFirst { (it.year ?: 0) <= year }
                if (idx >= 0) add(if (year % 10 == 0) "${year}s" else year.toString(), idx)
            }
            val older = items.indexOfFirst { (it.year ?: Int.MAX_VALUE) < 2000 }
            if (older >= 0) add("Older", older)
        }
        SeeAllSortMode.IMDB_DESC, SeeAllSortMode.TMDB_DESC -> {
            listOf(9f, 8f, 7f, 6f).forEach { threshold ->
                val idx = items.indexOfFirst { (it.rating?.toFloat() ?: 0f) <= threshold }
                if (idx >= 0) add("${threshold.toInt()}+", idx)
            }
        }
        SeeAllSortMode.A_Z, SeeAllSortMode.Z_A -> {
            ('A'..'Z').forEach { letter ->
                val idx = items.indexOfFirst { it.title.firstOrNull()?.uppercaseChar() == letter }
                if (idx >= 0) add(letter.toString(), idx)
            }
        }
        else -> {
            add("Top", 0)
            add("10%", (items.lastIndex * 0.10f).toInt())
            add("25%", (items.lastIndex * 0.25f).toInt())
            add("50%", (items.lastIndex * 0.50f).toInt())
            add("Later", (items.lastIndex * 0.75f).toInt())
        }
    }
    if (anchors.isEmpty() && ratingBucket != RatingBucket.ANY) add("Top", 0)
    return anchors.map { (label, index) -> FastScrollAnchor(label, index) }
}

@Composable
private fun InfoPanel(
    item: MediaItem?,
    onPlay: (MediaItem) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    onOpenPerson: (Int) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(ds("Select an item"), color = colors.textMuted)
        }
        return
    }

    val backdrop = rememberCachedBitmap(item.backdropUrl ?: item.posterUrl)
    val logo = rememberCachedBitmap(item.logoUrl)
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.cardSurface.copy(alpha = 0.6f))
            .verticalScroll(scroll),
    ) {
        // Hero banner
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Brush.linearGradient(listOf(colors.fieldSurface, colors.shellBackground))),
        ) {
            Crossfade(backdrop, label = "sa_hero") { bmp ->
                if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1.0f to colors.cardSurface.copy(alpha = 0.95f),
                        ),
                    ),
            )
            // Logo if present, otherwise title
            Box(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Crossfade(logo, label = "sa_logo") { art ->
                    if (art != null) {
                        Image(
                            bitmap = art,
                            contentDescription = item.title,
                            modifier = Modifier.height(56.dp).fillMaxWidth(0.85f),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                        )
                    } else {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (logo != null) {
                // When logo is shown in the hero, still show title as secondary so it's readable.
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val metaParts = listOfNotNull(
                item.year?.toString(),
                item.runtime?.takeIf { it > 0 }?.let { "${it}m" },
                if (item.type == MediaType.SERIES) "TV Show" else "Movie",
            )
            if (metaParts.isNotEmpty()) {
                Text(
                    metaParts.joinToString("  \u00B7  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }

            if (item.ratings != null) {
                DesktopRatingPills(ratings = item.ratings)
            }

            if (item.genres.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.genres.forEach { g ->
                        Surface(color = colors.fieldSurface.copy(alpha = 0.55f), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                g.name,
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textPrimary,
                            )
                        }
                    }
                }
            }

            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!item.director.isNullOrBlank() && item.directorId != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ds("Director"), style = MaterialTheme.typography.labelLarge, color = colors.textMuted)
                    PersonTile(
                        name = item.director!!,
                        subtitle = null,
                        profileUrl = item.directorProfileUrl,
                        onClick = { onOpenPerson(item.directorId!!) },
                    )
                }
            }

            if (item.cast.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ds("Cast"), style = MaterialTheme.typography.labelLarge, color = colors.textMuted)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.cast.take(5).forEach { member ->
                            PersonTile(
                                name = member.name,
                                subtitle = member.character?.takeIf { it.isNotBlank() },
                                profileUrl = member.profileUrl,
                                onClick = { onOpenPerson(member.id) },
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SeeAllPanelAction(
                    text = ds("Play"),
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onPlay(item) },
                )
                SeeAllPanelAction(
                    text = if (item.type == MediaType.SERIES) "Episodes" else ds("Details"),
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenDetail(item) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SeeAllPanelAction(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        color = if (primary) colors.accent else colors.fieldSurface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (primary) colors.accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f),
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color(0xFF0A0B14) else colors.textPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PersonTile(
    name: String,
    subtitle: String?,
    profileUrl: String?,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val bmp = rememberCachedBitmap(profileUrl)
    Row(
        Modifier.fillMaxWidth().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.fieldSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Filled.Person, null, tint = colors.textMuted, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
