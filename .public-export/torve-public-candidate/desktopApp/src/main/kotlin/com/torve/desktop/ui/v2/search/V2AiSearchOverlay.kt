package com.torve.desktop.ui.v2.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.data.ai.AiProvider
import com.torve.data.ai.KeywordSearchResult
import com.torve.data.ai.KeywordSearchService
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.metadata.TmdbMappers
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.desktop.ui.v2.components.V2PosterCard
import com.torve.domain.model.MediaItem
import com.torve.domain.sourceavailability.SourceAvailabilityAggregator
import com.torve.domain.sourceavailability.SourceAvailabilityKind
import com.torve.presentation.sourceavailability.RankedItem
import com.torve.presentation.sourceavailability.SourceAvailabilityRanker
import kotlinx.coroutines.launch

/**
 * Modal overlay for natural-language AI search. Calls
 * [KeywordSearchService.searchWithAi] using the provider + key from
 * Settings. For "specific" mode (AI identified concrete titles) we
 * fetch full TMDB details and render as a poster grid. For "discover"
 * and "person" modes we use the discover endpoint with the parsed
 * filters; if those modes return nothing, the AI's suggested title and
 * inferred filters are surfaced as a banner so the user can refine.
 */
@Composable
fun V2AiSearchOverlay(
    keywordSearch: KeywordSearchService,
    tmdb: TmdbApiClient,
    provider: AiProvider,
    apiKey: String,
    onDismiss: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
    availabilityAggregator: SourceAvailabilityAggregator? = null,
) {
    val colors = TorveDesktopThemeTokens.colors
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<KeywordSearchResult?>(null) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var rankedItems by remember { mutableStateOf<List<RankedItem<MediaItem>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun resolveSpecific(items: List<com.torve.data.ai.SpecificItem>): List<MediaItem> =
        items.mapNotNull { sp ->
            runCatching {
                if (sp.mediaType == "tv") {
                    TmdbMappers.tvToMediaItem(tmdb.getTvDetail(sp.tmdbId))
                } else {
                    TmdbMappers.movieToMediaItem(tmdb.getMovieDetail(sp.tmdbId))
                }
            }.getOrNull()
        }

    suspend fun runDiscover(r: KeywordSearchResult): List<MediaItem> = runCatching {
        if (r.mediaType == "tv") {
            tmdb.discoverTv(
                page = 1,
                sortBy = r.sortBy,
                withGenres = r.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                minRating = r.minRating,
                year = r.yearFrom,
                yearTo = r.yearTo,
            ).results.map(TmdbMappers::tvToMediaItem)
        } else {
            tmdb.discoverMovies(
                page = 1,
                sortBy = r.sortBy,
                withGenres = r.genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                minRating = r.minRating,
                year = r.yearFrom,
                yearTo = r.yearTo,
            ).results.map(TmdbMappers::movieToMediaItem)
        }
    }.getOrDefault(emptyList())

    fun submit() {
        if (query.isBlank() || apiKey.isBlank()) return
        scope.launch {
            loading = true
            error = null
            items = emptyList()
            rankedItems = emptyList()
            try {
                val r = keywordSearch.searchWithAi(provider, apiKey, query.trim())
                result = r
                val raw = when {
                    r.specificItems.isNotEmpty() -> resolveSpecific(r.specificItems)
                    r.mode == "discover" -> runDiscover(r)
                    else -> emptyList()
                }
                items = raw
                rankedItems = if (availabilityAggregator != null && raw.isNotEmpty()) {
                    val records = availabilityAggregator.lookupBatch(
                        raw.mapNotNull { item ->
                            val id = item.tmdbId ?: return@mapNotNull null
                            id to item.type
                        },
                    )
                    SourceAvailabilityRanker.rerank(raw, records) { it.tmdbId }
                } else {
                    raw.mapIndexed { idx, item ->
                        RankedItem(item = item, originalIndex = idx, record = null)
                    }
                }
            } catch (t: Throwable) {
                error = t.message
            }
            loading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xA6000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(880.dp)
                .heightIn(max = 720.dp)
                .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                .clickable(enabled = false, onClick = {}),
            color = colors.elevatedSurface,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ask Torve",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TorveGhostButton(text = "Close", onClick = onDismiss)
                }
                Text(
                    "Describe what you want to watch in plain language. Provider: ${provider.label}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                if (apiKey.isBlank()) {
                    TorveBanner(
                        title = "AI key not set",
                        description = "Add an API key for ${provider.label} in Settings → AI Services first.",
                        tone = TorveBannerTone.Info,
                    )
                }
                TorveTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "e.g. Adam Sandler movies on the beach (Enter to search)",
                    modifier = Modifier.fillMaxWidth(),
                    onSubmit = ::submit,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorvePrimaryButton(
                        text = if (loading) "Thinking..." else "Search",
                        onClick = ::submit,
                        enabled = query.isNotBlank() && apiKey.isNotBlank() && !loading,
                    )
                    if (loading) CircularProgressIndicator(
                        modifier = Modifier.width(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                error?.let {
                    TorveBanner(title = "AI search failed", description = it, tone = TorveBannerTone.Info)
                }
                result?.takeIf { items.isEmpty() && !loading }?.let { r ->
                    TorveBanner(
                        title = r.title.ifBlank { "Parsed query" },
                        description = "AI mode: ${r.mode}. " +
                            (r.genreIds.takeIf { it.isNotEmpty() }
                                ?.let { "Genres: $it. " } ?: "") +
                            (r.keywordIds.takeIf { it.isNotEmpty() }
                                ?.let { "Keywords: $it." } ?: ""),
                        tone = TorveBannerTone.Info,
                    )
                }
                if (rankedItems.isNotEmpty()) {
                    val anyAvailable = rankedItems.any { it.isAvailable }
                    if (availabilityAggregator != null && !anyAvailable) {
                        // Setup-repair fallback per Prompt 8: when no
                        // result has an "owned" path, route the user to
                        // the credential-first hub instead of leaving
                        // generic TMDB rows as the only thing visible.
                        TorveBanner(
                            title = "No owned source matched.",
                            description = "These results aren't in your library, downloads, debrid cache, " +
                                "addons, Usenet stack, or live IPTV. Open Settings → Sources to connect a " +
                                "playback path, then re-run the search.",
                            tone = TorveBannerTone.Info,
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.heightIn(max = 480.dp).fillMaxWidth(),
                    ) {
                        items(rankedItems, key = { it.item.id }) { ranked ->
                            Box {
                                V2PosterCard(
                                    title = ranked.item.title,
                                    imageUrl = ranked.item.posterUrl,
                                    backdropUrl = ranked.item.backdropUrl,
                                    overview = ranked.item.overview,
                                    onClick = {
                                        onOpenDetail(ranked.item)
                                        onDismiss()
                                    },
                                )
                                ranked.record?.primaryBadge?.let { signal ->
                                    val tone = when (signal.kind) {
                                        SourceAvailabilityKind.LOCAL_DOWNLOAD -> TorveBadgeTone.Success
                                        SourceAvailabilityKind.PLEX -> TorveBadgeTone.Accent
                                        SourceAvailabilityKind.JELLYFIN -> TorveBadgeTone.Accent
                                        SourceAvailabilityKind.DEBRID_CACHE -> TorveBadgeTone.Success
                                        SourceAvailabilityKind.STREMIO_ADDON -> TorveBadgeTone.Accent
                                        SourceAvailabilityKind.USENET_READY -> TorveBadgeTone.Accent
                                        SourceAvailabilityKind.IPTV_LIVE -> TorveBadgeTone.Live
                                        SourceAvailabilityKind.WATCH_HISTORY -> TorveBadgeTone.Neutral
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(6.dp),
                                    ) {
                                        TorveBadge(text = signal.badge, tone = tone)
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
