@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.torve.desktop.ui.v2.nzbmovies

import androidx.compose.foundation.background
import com.torve.desktop.ui.l10n.ds
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.desktop.adult.IndexerCategoryMap.MovieLanguage
import com.torve.desktop.adult.NzbCatalogService
import com.torve.desktop.adult.NzbSeeAllStateHolder
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2FloatingBackButton
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.domain.model.MediaItem
import com.torve.domain.model.hasValueFor

/**
 * "See all" surface for the Latest-on-Usenet shelf. Shows every TMDB-
 * matched NZB release in a poster grid, filterable by:
 *   • Language - drives the Newznab `cat=` parameter via
 *     IndexerCategoryMap. Triggers a refetch.
 *   • Search - drives the Newznab `q=` parameter. Triggers a refetch.
 *   • Genre - client-side filter on TMDB genre IDs.
 *
 * Posters use the standard [V2PosterCard] so click-to-detail and the
 * eventual play/watched indicators behave identically to the rest of
 * the catalog.
 */
@Composable
fun V2NzbSeeAllPage(
    catalogService: NzbCatalogService,
    indexerType: String,
    indexerUrl: String,
    indexerApiKey: String,
    initialLanguage: MovieLanguage = MovieLanguage.ANY,
    onBack: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val scope = rememberCoroutineScope()

    // Hydrate from the process-scoped holder so navigating into a
    // detail page and back keeps the user's filters and scroll
    // position. Defaults to [initialLanguage] only on a true first
    // open (no saved state).
    val saved = remember { NzbSeeAllStateHolder.get() }
    var query by remember { mutableStateOf(saved.query) }
    var language by remember {
        mutableStateOf(
            if (saved == NzbSeeAllStateHolder.State()) initialLanguage else saved.language,
        )
    }
    var genreFilter by remember { mutableStateOf(saved.genreFilter) }
    var minRating by remember { mutableStateOf(saved.minRating) }
    var requiredRatingSource by remember { mutableStateOf(saved.requiredRatingSource) }

    val items by catalogService.state.collectAsState()
    val loading by catalogService.loading.collectAsState()
    val hasMore by catalogService.hasMore.collectAsState()
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = saved.scrollIndex,
        initialFirstVisibleItemScrollOffset = saved.scrollOffset,
    )

    // Mirror every filter / scroll change back to the holder.
    LaunchedEffect(
        query, language, genreFilter, minRating, requiredRatingSource,
        gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset,
    ) {
        NzbSeeAllStateHolder.put(
            NzbSeeAllStateHolder.State(
                query = query,
                language = language,
                yearFilter = null,
                genreFilter = genreFilter,
                minRating = minRating,
                requiredRatingSource = requiredRatingSource,
                scrollIndex = gridState.firstVisibleItemIndex,
                scrollOffset = gridState.firstVisibleItemScrollOffset,
            ),
        )
    }

    // Re-trigger fetch whenever the indexer-driven filters (language /
    // search) change. Genre is client-side and doesn't require a
    // network round trip - they filter [items] in place. When the
    // filtered subset is too thin, the load-more effect below still
    // pulls more pages so the user gets a deep enough corpus.
    //
    // No `force=true` here on purpose: we WANT a remount with the same
    // filter (after navigating to a detail page and back) to resume
    // with the existing matched-release list intact, not refetch from
    // page 0. ensureLoaded's no-op guard handles that case for us.
    // When the user actually changes the language or query the
    // FilterKey changes inside the service and a fresh fetch runs.
    LaunchedEffect(indexerType, indexerUrl, indexerApiKey, language, query) {
        catalogService.ensureLoaded(
            scope = scope,
            indexerType = indexerType,
            indexerUrl = indexerUrl,
            indexerApiKey = indexerApiKey,
            language = language,
            query = query,
        )
    }

    // Compute the visible (post-client-filter) count *first* so the
    // load-more effect below can chase it. Mirrors the [visible] memo
    // below - keep the predicate identical.
    val filteredSize = remember(items, genreFilter, minRating, requiredRatingSource) {
        items.count { rel -> matchesClientFilters(rel, genreFilter, minRating, requiredRatingSource) }
    }

    // Lazy load triggers in two cases:
    //   1. User scrolls near the bottom of the grid.
    //   2. A client-side filter (year / genre) leaves too few visible
    //      posters - the indexer's first page might only contain a few
    //      titles for "German + 2025", so we keep pulling pages until
    //      either the filtered count hits TARGET or the indexer's out.
    LaunchedEffect(gridState, items.size, filteredSize, hasMore, loading) {
        if (hasMore && !loading && filteredSize < TARGET_VISIBLE) {
            catalogService.loadMore(scope, indexerType, indexerUrl, indexerApiKey)
            return@LaunchedEffect
        }
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (hasMore && !loading && lastVisible >= items.size - 12) {
                    catalogService.loadMore(scope, indexerType, indexerUrl, indexerApiKey)
                }
            }
    }

    // Available genres are derived from whatever's loaded right
    // now - the chip set updates as the indexer feed changes.
    val availableGenres = remember(items) {
        items.flatMap { it.match.genreIds }
            .filter { it in TMDB_MOVIE_GENRES }
            .distinct()
            .sortedBy { TMDB_MOVIE_GENRES[it] }
    }

    val visible = remember(items, genreFilter, minRating, requiredRatingSource) {
        items.filter { rel -> matchesClientFilters(rel, genreFilter, minRating, requiredRatingSource) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.shellBackground),
    ) {
        Row(
            // 72.dp clears the left nav rail - same as the rest of the
            // V2 surface pages.
            modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            V2FloatingBackButton(onBack = onBack, contentDescription = ds("Back"))
            Text(
                text = ds("Latest on Usenet"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                text = "${visible.size} of ${items.size}",
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
            )
        }

        // Search row - tight against the filter rows below so the
        // entire control band reads as one premium strip.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 24.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TorveTextField(
                value = query,
                onValueChange = { query = it },
                label = ds("Search Usenet movies (Enter)"),
                modifier = Modifier.weight(1f),
                onSubmit = { /* LaunchedEffect picks it up */ },
                trailingIcon = if (query.isNotBlank()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                } else null,
            )
            TorvePrimaryButton(text = ds("Search"), onClick = { /* LaunchedEffect picks it up */ })
        }

        // Language pills.
        FilterRow(label = ds("Language")) {
            MovieLanguage.entries.forEach { lang ->
                CompactFilterChip(
                    text = lang.label,
                    selected = lang == language,
                    onClick = { language = lang },
                )
            }
        }

        // Genre filter.
        if (availableGenres.isNotEmpty()) {
            FilterRow(label = ds("Genre")) {
                CompactFilterChip(text = ds("Any"), selected = genreFilter == null, onClick = { genreFilter = null })
                availableGenres.forEach { id ->
                    val name = TMDB_MOVIE_GENRES[id] ?: return@forEach
                    CompactFilterChip(
                        text = name,
                        selected = genreFilter == id,
                        onClick = { genreFilter = id },
                    )
                }
            }
        }

        // Min rating - common quality cutoffs. Filter is client-side
        // and uses the highest available source-normalized score per
        // item (IMDb preferred, then TMDB, then anything else
        // converted to a 0-10 scale).
        FilterRow(label = ds("Min rating")) {
            CompactFilterChip(text = ds("Any"), selected = minRating == null, onClick = { minRating = null })
            listOf(5f, 6f, 7f, 8f, 9f).forEach { threshold ->
                CompactFilterChip(
                    text = "${threshold.toInt()}+",
                    selected = minRating == threshold,
                    onClick = { minRating = threshold },
                )
            }
        }

        // Rating source - only show items that have a verified rating
        // from the chosen provider. Useful for finding TMDB-curated
        // titles vs. ones with full IMDb / RT coverage.
        FilterRow(label = ds("Rating source")) {
            CompactFilterChip(text = ds("Any"), selected = requiredRatingSource == null, onClick = { requiredRatingSource = null })
            RATING_SOURCE_FILTERS.forEach { source ->
                CompactFilterChip(
                    text = source.displayName,
                    selected = requiredRatingSource == source,
                    onClick = { requiredRatingSource = source },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && items.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = colors.accent) }
                visible.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (items.isEmpty()) "No releases found." else "No matches for these filters.",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        contentPadding = PaddingValues(start = 72.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(visible, key = { it.match.tmdbId }) { rel ->
                            V2PosterCard(
                                rel.match.title,
                                rel.match.posterUrl,
                                Modifier.width(150.dp),
                                rel.match.year?.toString(),
                                rel.match.voteAverage?.let { String.format("%.1f", it) },
                                ratings = rel.mediaItem.ratings,
                                backdropUrl = rel.match.backdropUrl,
                                overview = rel.match.overview,
                            ) {
                                // Register the NZB so launchPlayback can
                                // fall back to it if Stremio sources fail.
                                com.torve.desktop.adult.NzbPlaybackHints.set(rel.match.tmdbId, rel.nzb)
                                onOpenDetail(rel.mediaItem)
                            }
                        }
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
        }
    }
}

/**
 * Single-line filter row for the see-all page. Tightened relative to
 * the page-header rows: 1.dp vertical padding so successive rows
 * stack densely, an 80.dp label column with a smaller label font, and
 * 4.dp chip gaps. Designed so the entire filter strip (search +
 * language + year + genre + min rating + rating source) occupies one
 * compact band at the top of the page rather than half the screen.
 */
@Composable
private fun FilterRow(
    label: String,
    content: @Composable FlowRowScope.() -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 24.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
            ),
            fontWeight = FontWeight.SemiBold,
            color = colors.textMuted,
            modifier = Modifier.width(80.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
            content = content,
        )
    }
}

/**
 * Slim capsule filter chip - the whole point of replacing
 * TorveFilterChip on the see-all page. Gives the filter band a clean,
 * almost text-link feel: no fill when inactive (just colour), accent
 * underline-pill when active. Significantly less vertical real-estate
 * than the standard chip.
 */
@Composable
private fun CompactFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val bg = if (selected) colors.accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) colors.accent else colors.textSecondary
    val border = if (selected) colors.accent else colors.borderSubtle
    androidx.compose.material3.Surface(
        color = bg,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, border),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * TMDB movie genre id → display name. Mirrors TMDB's `/genre/movie/list`
 * fixed list as of 2026 - matches the genre IDs returned in
 * [com.torve.data.metadata.TmdbMultiResult.genreIds]. Hard-coded to
 * avoid an extra API call just to label chips.
 */
/** When a client-side filter (year/genre) is selected, the auto-pull
 *  effect keeps fetching more indexer pages until the filtered subset
 *  reaches this size or the indexer's exhausted. Tuned to feel "full"
 *  on a 4-column grid without spending forever paginating. */
private const val TARGET_VISIBLE = 100

/** Subset of rating sources surfaced as filter chips. TORVE is a
 *  computed score, not a real provider - no point filtering by it.
 *  RT_AUDIENCE is excluded because it follows the same API path as
 *  ROTTEN_TOMATOES and adds noise to the chip row. */
private val RATING_SOURCE_FILTERS: List<com.torve.domain.model.RatingSource> = listOf(
    com.torve.domain.model.RatingSource.IMDB,
    com.torve.domain.model.RatingSource.TMDB,
    com.torve.domain.model.RatingSource.ROTTEN_TOMATOES,
    com.torve.domain.model.RatingSource.METACRITIC,
    com.torve.domain.model.RatingSource.TRAKT,
    com.torve.domain.model.RatingSource.MDBLIST,
    com.torve.domain.model.RatingSource.LETTERBOXD,
)

/**
 * Centralised predicate so the filter logic stays consistent between
 * the [filteredSize] count (used to decide whether to load more pages)
 * and the actual [visible] grid items.
 */
private fun matchesClientFilters(
    rel: com.torve.desktop.adult.NzbCatalogService.MatchedRelease,
    genreFilter: Int?,
    minRating: Float?,
    requiredRatingSource: com.torve.domain.model.RatingSource?,
): Boolean {
    if (genreFilter != null && genreFilter !in rel.match.genreIds) return false
    val ratings = rel.mediaItem.ratings
    if (requiredRatingSource != null) {
        if (ratings == null) return false
        if (!ratings.hasValueFor(requiredRatingSource)) return false
    }
    if (minRating != null) {
        val score = bestNormalizedScore(ratings) ?: return false
        if (score < minRating) return false
    }
    return true
}

/**
 * Best-effort 0-10 score from a [com.torve.domain.model.MediaRatings].
 * IMDb takes priority (most users align mental rankings to it), then
 * TMDB, then RT/Metacritic/Trakt/MDBList normalised /10. Returns null
 * if the item carries no rating at all - those rows are filtered out
 * when a min-rating threshold is active.
 */
private fun bestNormalizedScore(r: com.torve.domain.model.MediaRatings?): Float? {
    if (r == null) return null
    r.imdbScore?.let { return it }
    r.tmdbScore?.let { return it }
    r.rottenTomatoesScore?.let { return it / 10f }
    r.metacriticScore?.let { return it / 10f }
    r.traktScore?.let { return it / 10f }
    r.mdblistScore?.let { return it / 10f }
    r.letterboxdScore?.let { return it * 2f }
    r.malScore?.let { return it }
    return null
}


private val TMDB_MOVIE_GENRES = linkedMapOf(
    28 to "Action",
    12 to "Adventure",
    16 to "Animation",
    35 to "Comedy",
    80 to "Crime",
    99 to "Documentary",
    18 to "Drama",
    10751 to "Family",
    14 to "Fantasy",
    36 to "History",
    27 to "Horror",
    10402 to "Music",
    9648 to "Mystery",
    10749 to "Romance",
    878 to "Sci-Fi",
    10770 to "TV Movie",
    53 to "Thriller",
    10752 to "War",
    37 to "Western",
)
