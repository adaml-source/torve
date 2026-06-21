package com.torve.desktop.adult

import com.torve.desktop.adult.IndexerCategoryMap.MovieLanguage
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.MediaRatings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * TV-show counterpart to [NzbCatalogService].
 *
 * Differences from the movies service:
 *   • Newznab category 5000 (TV) instead of 2000 (Movies). Per-indexer
 *     language sub-cats aren't well-documented for TV so we always
 *     query 5000 and rely on title-based language filtering.
 *   • Releases are per-episode (or per-season) on the wire, but the
 *     UI surface is per-show - we aggregate by TMDB show id and keep
 *     a count of how many episodes / season-packs we've seen for each
 *     show so the poster card can render a "12 episodes" badge.
 *   • TMDB lookup forces media kind = TV through
 *     [NzbPosterCache.lookup] so we don't accidentally match a movie
 *     of the same title.
 *
 * Backed by the same disk cache as movies; cache key namespaces TV
 * separately so a `tv|scenenzbs|GERMAN|` filter doesn't collide with
 * the movies version.
 */
class NzbTvCatalogService(
    private val newznab: NewznabClient,
    private val posterCache: NzbPosterCache,
    private val ratingsEnricher: com.torve.data.mdblist.RatingsEnricher? = null,
    private val mdbListApiKey: () -> String = { "" },
) {
    /** Aggregated TV show - one card represents many episode releases. */
    data class ShowCard(
        val match: NzbPosterCache.Match.Found,
        val mediaItem: MediaItem,
        /** All NZB releases that match this show, newest first. The
         *  detail page can use this to populate per-season/episode
         *  download / play hints. */
        val releases: List<NewznabItem>,
    ) {
        val episodeCount: Int get() = releases.size
    }

    private data class FilterKey(val language: MovieLanguage, val query: String)

    private val _state = MutableStateFlow<List<ShowCard>>(emptyList())
    val state: StateFlow<List<ShowCard>> = _state.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val mutex = Mutex()
    private var inFlight: Job? = null
    private var activeFilter: FilterKey? = null
    private var nextOffset: Int = 0

    private val tmdbSemaphore = Semaphore(permits = 16)

    companion object {
        const val PAGE_SIZE_RAW = 100
        const val MAX_SHOWS = 500
    }

    private var activeIndexerType: String = ""

    fun ensureLoaded(
        scope: CoroutineScope,
        indexerType: String,
        indexerUrl: String,
        indexerApiKey: String,
        language: MovieLanguage = MovieLanguage.ANY,
        query: String = "",
        force: Boolean = false,
    ) {
        activeIndexerType = indexerType
        if (indexerUrl.isBlank() || indexerApiKey.isBlank()) {
            _state.value = emptyList()
            return
        }
        val target = FilterKey(language, query.trim())
        if (target == activeFilter && !force && _state.value.isNotEmpty()) return
        inFlight?.cancel()
        activeFilter = target
        nextOffset = 0
        _state.value = emptyList()
        _hasMore.value = true
        loadMore(scope, indexerType, indexerUrl, indexerApiKey)
    }

    fun loadMore(
        scope: CoroutineScope,
        indexerType: String,
        indexerUrl: String,
        indexerApiKey: String,
    ) {
        val filter = activeFilter ?: return
        if (_loading.value || !_hasMore.value) return
        if (_state.value.size >= MAX_SHOWS) {
            _hasMore.value = false
            return
        }
        inFlight = scope.launch {
            _loading.value = true
            val offset = nextOffset
            try {
                val newReleases = withContext(Dispatchers.IO) {
                    fetchAndMatch(indexerUrl, indexerApiKey, filter, offset)
                }
                mutex.withLock {
                    nextOffset = offset + PAGE_SIZE_RAW
                    if (newReleases.isEmpty()) _hasMore.value = false
                    if (newReleases.isNotEmpty()) {
                        // Aggregate by tmdbId - fold each new release
                        // into the existing show card, or create a new
                        // card if first sighting.
                        val byId = _state.value.associateBy { it.match.tmdbId }.toMutableMap()
                        for ((nzb, match) in newReleases) {
                            val existing = byId[match.tmdbId]
                            if (existing == null) {
                                byId[match.tmdbId] = ShowCard(
                                    match = match,
                                    mediaItem = match.toMediaItem(),
                                    releases = listOf(nzb),
                                )
                            } else if (existing.releases.none { it.guid == nzb.guid && it.guid != null }) {
                                byId[match.tmdbId] = existing.copy(
                                    releases = existing.releases + nzb,
                                )
                            }
                        }
                        _state.value = byId.values.toList()
                    }
                }
                if (newReleases.isNotEmpty()) {
                    enrichInBackground(scope, newReleases.map { it.second })
                }
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Lookup helper for the playback fallback: given a show + episode,
     * return the best-matching NZB. Falls back to the most recent NZB
     * for the show if no specific episode match is found.
     */
    fun findReleaseFor(tmdbId: Int, season: Int? = null, episode: Int? = null): NewznabItem? {
        val card = _state.value.firstOrNull { it.match.tmdbId == tmdbId } ?: return null
        if (season != null && episode != null) {
            // Prefer an exact S/E match by parsing each release name.
            val exact = card.releases.firstOrNull { rel ->
                val parsed = NzbTvReleaseTitleParser.parse(rel.title) ?: return@firstOrNull false
                parsed.seasonNumber == season && parsed.episodeNumber == episode
            }
            if (exact != null) return exact
        }
        return card.releases.firstOrNull()
    }

    private suspend fun fetchAndMatch(
        indexerUrl: String,
        indexerApiKey: String,
        filter: FilterKey,
        offset: Int,
    ): List<Pair<NewznabItem, NzbPosterCache.Match.Found>> = withContext(Dispatchers.IO) {
        // Use the per-indexer TV category map: scenenzbs → 5100 for
        // German, 5200 for Spanish, etc. Indexers without language
        // sub-cats return 5000 and rely on the title-tag filter +
        // query augmentation below.
        val categoryParam = IndexerCategoryMap.tvCategoriesFor(activeIndexerType, setOf(filter.language))
        val supportsLangCats = IndexerCategoryMap.hasTvLanguageCategories(activeIndexerType)
        val needsTitleFallback = !supportsLangCats
        val effectiveQuery = IndexerCategoryMap.augmentQueryForLanguage(
            indexerSupportsLanguageCats = supportsLangCats,
            baseQuery = filter.query,
            language = filter.language,
        )
        val raw = if (effectiveQuery.isBlank()) {
            newznab.browse(indexerUrl, indexerApiKey, categoryParam, offset = offset, limit = PAGE_SIZE_RAW)
        } else {
            newznab.search(indexerUrl, indexerApiKey, categoryParam, effectiveQuery, offset = offset, limit = PAGE_SIZE_RAW)
        }
        val filtered = if (needsTitleFallback && filter.language != MovieLanguage.ANY) {
            raw.filter { IndexerCategoryMap.titleMatchesLanguages(it.title, setOf(filter.language)) }
        } else raw
        val parsedHits = filtered.mapNotNull { item ->
            val parsed = NzbTvReleaseTitleParser.parse(item.title) ?: return@mapNotNull null
            item to parsed
        }
        coroutineScope {
            parsedHits.map { (item, parsed) ->
                async {
                    tmdbSemaphore.withPermit {
                        val match = posterCache.lookup(parsed.showTitle, parsed.year, NzbPosterCache.MediaKind.TV)
                                as? NzbPosterCache.Match.Found
                            ?: return@async null
                        item to match
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun enrichInBackground(scope: CoroutineScope, matches: List<NzbPosterCache.Match.Found>) {
        val key = mdbListApiKey().trim()
        if (ratingsEnricher == null || key.isBlank()) return
        // Dedupe by tmdbId - many NZB releases can share a show.
        val unique = matches.distinctBy { it.tmdbId }
        if (unique.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val enriched = runCatching {
                ratingsEnricher.enrichList(items = unique.map { it.toMediaItem() }, apiKey = key)
            }.getOrNull() ?: return@launch
            mutex.withLock {
                val byTmdb = enriched.associateBy { it.tmdbId }
                _state.value = _state.value.map { card ->
                    val updated = byTmdb[card.match.tmdbId] ?: return@map card
                    card.copy(mediaItem = updated)
                }
            }
        }
    }

    private fun NzbPosterCache.Match.Found.toMediaItem(): MediaItem = MediaItem(
        // SERIES type so the standard detail page renders seasons /
        // episodes from TMDB. id format mirrors movies.
        id = "tmdb:tv:$tmdbId",
        tmdbId = tmdbId,
        type = MediaType.SERIES,
        title = title,
        year = year,
        overview = overview,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = voteAverage,
        ratings = voteAverage?.let { MediaRatings(tmdbScore = it.toFloat()) },
    )
}
