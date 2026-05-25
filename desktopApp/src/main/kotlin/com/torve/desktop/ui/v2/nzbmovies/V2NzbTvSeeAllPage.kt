@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.torve.desktop.ui.v2.nzbmovies

import androidx.compose.foundation.background
import com.torve.desktop.ui.l10n.ds
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.desktop.adult.IndexerCategoryMap.MovieLanguage
import com.torve.desktop.adult.NzbTvCatalogService
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2FloatingBackButton
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.domain.model.MediaItem
import com.torve.domain.model.RatingSource
import com.torve.domain.model.hasValueFor

/**
 * "See all" surface for the Latest-on-Usenet TV shelf. Mirrors the
 * movies see-all but operates on [NzbTvCatalogService.ShowCard]
 * (one card per show, episodes aggregated). Filters: search, language,
 * genre, min rating, rating source.
 */
@Composable
fun V2NzbTvSeeAllPage(
    catalogService: NzbTvCatalogService,
    indexerType: String,
    indexerUrl: String,
    indexerApiKey: String,
    onBack: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(MovieLanguage.ANY) }
    var genreFilter by remember { mutableStateOf<Int?>(null) }
    var minRating by remember { mutableStateOf<Float?>(null) }
    var requiredRatingSource by remember { mutableStateOf<RatingSource?>(null) }

    val items by catalogService.state.collectAsState()
    val loading by catalogService.loading.collectAsState()
    val hasMore by catalogService.hasMore.collectAsState()
    val gridState = rememberLazyGridState()

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

    val availableGenres = remember(items) {
        items.flatMap { it.match.genreIds }
            .filter { it in TMDB_TV_GENRES }
            .distinct()
            .sortedBy { TMDB_TV_GENRES[it] }
    }

    val filteredSize = remember(items, genreFilter, minRating, requiredRatingSource) {
        items.count { matchesTvClientFilters(it, genreFilter, minRating, requiredRatingSource) }
    }
    val visible = remember(items, genreFilter, minRating, requiredRatingSource) {
        items.filter { matchesTvClientFilters(it, genreFilter, minRating, requiredRatingSource) }
    }

    LaunchedEffect(gridState, items.size, filteredSize, hasMore, loading) {
        if (hasMore && !loading && filteredSize < TARGET_VISIBLE_TV) {
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

    Column(
        modifier = Modifier.fillMaxSize().background(colors.shellBackground),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            V2FloatingBackButton(onBack = onBack, contentDescription = ds("Back"))
            Text(
                text = ds("Latest on Usenet - TV"),
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 24.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TorveTextField(
                value = query,
                onValueChange = { query = it },
                label = ds("Search Usenet TV shows (Enter)"),
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

        TvFilterRow(label = ds("Language")) {
            MovieLanguage.entries.forEach { lang ->
                TvCompactFilterChip(text = lang.label, selected = lang == language) { language = lang }
            }
        }
        if (availableGenres.isNotEmpty()) {
            TvFilterRow(label = ds("Genre")) {
                TvCompactFilterChip(text = ds("Any"), selected = genreFilter == null) { genreFilter = null }
                availableGenres.forEach { id ->
                    val name = TMDB_TV_GENRES[id] ?: return@forEach
                    TvCompactFilterChip(text = name, selected = genreFilter == id) { genreFilter = id }
                }
            }
        }
        TvFilterRow(label = ds("Min rating")) {
            TvCompactFilterChip(text = ds("Any"), selected = minRating == null) { minRating = null }
            listOf(5f, 6f, 7f, 8f, 9f).forEach { threshold ->
                TvCompactFilterChip(
                    text = "${threshold.toInt()}+",
                    selected = minRating == threshold,
                ) { minRating = threshold }
            }
        }
        TvFilterRow(label = ds("Rating source")) {
            TvCompactFilterChip(text = ds("Any"), selected = requiredRatingSource == null) { requiredRatingSource = null }
            TV_RATING_SOURCE_FILTERS.forEach { source ->
                TvCompactFilterChip(
                    text = source.displayName,
                    selected = requiredRatingSource == source,
                ) { requiredRatingSource = source }
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
                        items(visible, key = { it.match.tmdbId }) { card ->
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
                                // Stash this show's NZB releases so the
                                // detail page's Play button can fall back
                                // to the original NZB when Stremio addons
                                // return zero candidates.
                                com.torve.desktop.adult.NzbPlaybackHints.setTv(
                                    card.match.tmdbId,
                                    card.releases,
                                )
                                onOpenDetail(card.mediaItem)
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

@Composable
private fun TvFilterRow(label: String, content: @Composable FlowRowScope.() -> Unit) {
    val colors = TorveDesktopThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 24.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.6.sp),
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

@Composable
private fun TvCompactFilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = TorveDesktopThemeTokens.colors
    val bg = if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent
    val fg = if (selected) colors.accent else colors.textSecondary
    val border = if (selected) colors.accent else colors.borderSubtle
    Surface(
        color = bg,
        shape = RoundedCornerShape(50),
        border = BorderStroke(0.5.dp, border),
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

private const val TARGET_VISIBLE_TV = 100

private val TV_RATING_SOURCE_FILTERS: List<RatingSource> = listOf(
    RatingSource.IMDB,
    RatingSource.TMDB,
    RatingSource.ROTTEN_TOMATOES,
    RatingSource.METACRITIC,
    RatingSource.TRAKT,
    RatingSource.MDBLIST,
)

private fun matchesTvClientFilters(
    card: NzbTvCatalogService.ShowCard,
    genreFilter: Int?,
    minRating: Float?,
    requiredRatingSource: RatingSource?,
): Boolean {
    if (genreFilter != null && genreFilter !in card.match.genreIds) return false
    val ratings = card.mediaItem.ratings
    if (requiredRatingSource != null) {
        if (ratings == null || !ratings.hasValueFor(requiredRatingSource)) return false
    }
    if (minRating != null) {
        val r = ratings ?: return false
        val score = r.imdbScore
            ?: r.tmdbScore
            ?: r.rottenTomatoesScore?.let { it / 10f }
            ?: r.metacriticScore?.let { it / 10f }
            ?: r.traktScore?.let { it / 10f }
            ?: r.mdblistScore?.let { it / 10f }
            ?: r.letterboxdScore?.let { it * 2f }
            ?: r.malScore
            ?: return false
        if (score < minRating) return false
    }
    return true
}

/** TMDB TV genre id → display name. Diverges from the movie list (no
 *  Music, Romance, etc.; adds Reality, Soap, etc.). */
private val TMDB_TV_GENRES = linkedMapOf(
    10759 to "Action & Adv.",
    16 to "Animation",
    35 to "Comedy",
    80 to "Crime",
    99 to "Documentary",
    18 to "Drama",
    10751 to "Family",
    10762 to "Kids",
    9648 to "Mystery",
    10763 to "News",
    10764 to "Reality",
    10765 to "Sci-Fi & Fantasy",
    10766 to "Soap",
    10767 to "Talk",
    10768 to "War & Politics",
    37 to "Western",
)
