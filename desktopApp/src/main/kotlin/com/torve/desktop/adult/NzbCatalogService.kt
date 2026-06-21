package com.torve.desktop.adult

import com.torve.desktop.adult.IndexerCategoryMap.MovieLanguage
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
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
 * Cross-page catalog of "fresh on Usenet" movie releases.
 *
 * Backs both:
 *   • V2MoviesPage's "Latest on Usenet" shelf (top N matched releases).
 *   • V2NzbSeeAllPage (paged grid filterable by year / language / genre
 *     / search).
 *
 * The service maintains a single "active filter" (language + query) and
 * a paged result list for that filter. Changing the active filter resets
 * the paging cursor and fetches page 0 fresh; [loadMore] expands the
 * existing list. Year and genre are client-side filters and don't reset
 * the page cursor.
 *
 * Each fetched indexer page produces matched releases by:
 *   1. Parsing scene release titles into (title, year)
 *   2. Looking each up in [NzbPosterCache] (TMDB)
 *   3. Deduping by tmdbId across the entire active list so multiple
 *      release groups for the same film don't clutter the grid.
 */
class NzbCatalogService(
    private val newznab: NewznabClient,
    private val posterCache: NzbPosterCache,
    /** When non-null and [mdbListApiKey] returns a non-blank key, each
     *  fetched page is enriched with IMDb / RT / Metacritic ratings via
     *  MdbList so the V2PosterCard rating pills line up with the rest
     *  of the catalog. */
    private val ratingsEnricher: com.torve.data.mdblist.RatingsEnricher? = null,
    private val mdbListApiKey: () -> String = { "" },
) {
    data class MatchedRelease(
        val nzb: NewznabItem,
        val match: NzbPosterCache.Match.Found,
        val mediaItem: MediaItem,
    )

    private data class FilterKey(val language: MovieLanguage, val query: String)

    private val _state = MutableStateFlow<List<MatchedRelease>>(emptyList())
    val state: StateFlow<List<MatchedRelease>> = _state.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val mutex = Mutex()
    private var inFlight: Job? = null
    private var activeFilter: FilterKey? = null
    private var nextOffset: Int = 0

    /**
     * Bound concurrent TMDB lookups so we don't slam image.tmdb.org's
     * search endpoint with 100 simultaneous requests on every page -
     * TMDB rate-limits at ~50 req/sec. 16 in-flight is the sweet spot
     * we observed for fast catalog hydration without 429s.
     */
    private val tmdbSemaphore = Semaphore(permits = 16)

    companion object {
        const val PAGE_SIZE_RAW = 100
        /** Stop expanding once we hit this many matched releases - beyond
         *  here the grid scrolls long enough for any reasonable browse. */
        const val MAX_MATCHED = 1_000
    }

    /**
     * Set or change the active filter and fetch the first page. No-op
     * if the same filter is already active and not [force]-refreshing.
     *
     * Disk-cache lifecycle:
     *  • First visit to a filter: try to hydrate from disk for instant
     *    render. If the snapshot is fresh (<1h old), surface it and
     *    skip the network entirely. If stale, surface immediately and
     *    fire a background refresh.
     *  • No disk hit: fetch page 0 from the indexer as before.
     */
    fun ensureLoaded(
        scope: CoroutineScope,
        indexerType: String,
        indexerUrl: String,
        indexerApiKey: String,
        language: MovieLanguage = MovieLanguage.ANY,
        query: String = "",
        force: Boolean = false,
    ) {
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

        // Try disk cache first - instant paint while the network catches up.
        val diskKey = catalogCacheKey(indexerType, target)
        val snapshot = NzbDiskCache.loadCatalog(diskKey)
        if (snapshot != null && snapshot.releases.isNotEmpty()) {
            _state.value = snapshot.releases
            nextOffset = snapshot.nextOffset
            _hasMore.value = snapshot.hasMore
            // Fresh-enough: skip the network. The user can pull more
            // pages later via load-more if they scroll.
            if (!snapshot.isStale && !force) return
            // Stale: render the snapshot now, then refresh the first
            // page in the background to top up.
            scope.launch {
                kotlinx.coroutines.delay(50)
                if (activeFilter != target) return@launch
                // Reset the offset so background refresh fetches page 0,
                // not the next page after the cached set.
                val priorOffset = nextOffset
                nextOffset = 0
                loadMore(scope, indexerType, indexerUrl, indexerApiKey)
                // Restore offset so subsequent loadMore continues from
                // where the cache left off.
                if (priorOffset > nextOffset) nextOffset = priorOffset
            }
            return
        }

        loadMore(
            scope = scope,
            indexerType = indexerType,
            indexerUrl = indexerUrl,
            indexerApiKey = indexerApiKey,
        )
    }

    private fun catalogCacheKey(indexerType: String, filter: FilterKey): String =
        "$indexerType|${filter.language.name}|${filter.query}"

    /**
     * Pull the next page from the indexer for the active filter and
     * append matched releases to [state]. Caller is expected to call
     * this when the see-all grid scrolls near the bottom.
     */
    fun loadMore(
        scope: CoroutineScope,
        indexerType: String,
        indexerUrl: String,
        indexerApiKey: String,
    ) {
        val filter = activeFilter ?: return
        if (_loading.value || !_hasMore.value) return
        if (_state.value.size >= MAX_MATCHED) {
            _hasMore.value = false
            return
        }
        inFlight = scope.launch {
            _loading.value = true
            val offset = nextOffset
            try {
                // Stream matched releases into [_state] AS THEY ARE
                // RESOLVED (rather than waiting for the full page +
                // MdbList enrichment). A 100-release page that used to
                // make the user stare at a blank grid for ~1 minute
                // now shows posters within a couple of seconds and
                // keeps filling in. MdbList enrichment runs separately
                // in [enrichInBackground] and updates ratings pills
                // in-place once IMDb / RT / Metacritic land.
                val page = streamFetchAndMatch(
                    scope = this,
                    indexerType = indexerType,
                    indexerUrl = indexerUrl,
                    indexerApiKey = indexerApiKey,
                    filter = filter,
                    offset = offset,
                )
                mutex.withLock {
                    nextOffset = offset + PAGE_SIZE_RAW
                    if (page.isEmpty()) _hasMore.value = false
                }
                // Persist the fresh state to disk so a subsequent
                // restart / re-entry to this filter renders instantly.
                persistCatalogSnapshot(indexerType, filter)
                // Background enrichment - non-blocking. Items already
                // visible in the grid get their ratings pills upgraded
                // when each batch lands.
                if (page.isNotEmpty()) enrichInBackground(scope, page, indexerType, filter)
            } finally {
                _loading.value = false
            }
        }
    }

    private fun persistCatalogSnapshot(indexerType: String, filter: FilterKey) {
        val key = catalogCacheKey(indexerType, filter)
        NzbDiskCache.saveCatalog(
            filterKey = key,
            nextOffset = nextOffset,
            hasMore = _hasMore.value,
            releases = _state.value,
        )
    }

    /**
     * Fetches one indexer page and emits matched releases to [_state]
     * incrementally as TMDB lookups complete. Returns the full set of
     * matched releases so the caller can hand them to [enrichInBackground]
     * for a separate MdbList pass.
     */
    private suspend fun streamFetchAndMatch(
        scope: CoroutineScope,
        indexerType: String,
        indexerUrl: String,
        indexerApiKey: String,
        filter: FilterKey,
        offset: Int,
    ): List<MatchedRelease> = withContext(Dispatchers.IO) {
        val categoryParam = IndexerCategoryMap.moviesCategoriesFor(indexerType, setOf(filter.language))
        val needsTitleFallback = !IndexerCategoryMap.hasLanguageCategories(indexerType)
        val raw = if (filter.query.isBlank()) {
            newznab.browse(indexerUrl, indexerApiKey, categoryParam, offset = offset, limit = PAGE_SIZE_RAW)
        } else {
            newznab.search(indexerUrl, indexerApiKey, categoryParam, filter.query, offset = offset, limit = PAGE_SIZE_RAW)
        }
        val filtered = if (needsTitleFallback) {
            raw.filter { IndexerCategoryMap.titleMatchesLanguages(it.title, setOf(filter.language)) }
        } else raw
        val parsedHits = filtered.mapNotNull { item ->
            val parsed = NzbReleaseTitleParser.parse(item.title) ?: return@mapNotNull null
            item to parsed
        }

        coroutineScope {
            val resolved = parsedHits.map { (item, parsed) ->
                async {
                    tmdbSemaphore.withPermit {
                        val match = posterCache.lookup(parsed.title, parsed.year)
                                as? NzbPosterCache.Match.Found
                            ?: return@async null
                        val rel = MatchedRelease(
                            nzb = item,
                            match = match,
                            mediaItem = match.toMediaItem(),
                        )
                        // Stream this single match into the shared
                        // state immediately. The caller uses [_state]
                        // directly so the UI sees it on the next
                        // recomposition.
                        appendIfNew(rel)
                        rel
                    }
                }
            }
            resolved.awaitAll().filterNotNull()
        }
    }

    private suspend fun appendIfNew(rel: MatchedRelease) {
        mutex.withLock {
            val current = _state.value
            if (current.any { it.match.tmdbId == rel.match.tmdbId }) return
            _state.value = current + rel
        }
    }

    /**
     * Run MdbList enrichment off the critical path. Items are already
     * visible - we just patch their ratings in place as enrichment
     * batches return. Best-effort; rate limits / missing key just
     * leave the TMDB-only pill in place. Saves the enriched state to
     * disk too so reopens see the full pill stack instantly.
     */
    private fun enrichInBackground(
        scope: CoroutineScope,
        page: List<MatchedRelease>,
        indexerType: String,
        filter: FilterKey,
    ) {
        val key = mdbListApiKey().trim()
        if (ratingsEnricher == null || key.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val enrichedItems = runCatching {
                ratingsEnricher.enrichList(items = page.map { it.mediaItem }, apiKey = key)
            }.getOrNull() ?: return@launch
            mutex.withLock {
                val byTmdb = enrichedItems.associateBy { it.tmdbId }
                _state.value = _state.value.map { rel ->
                    val updated = byTmdb[rel.match.tmdbId] ?: return@map rel
                    rel.copy(mediaItem = updated)
                }
            }
            // Re-persist with enriched ratings so a restart shows the
            // full pill stack from the cache, not the TMDB-only baseline.
            persistCatalogSnapshot(indexerType, filter)
        }
    }

    /**
     * Lookup helper used by V2App's launchPlayback fallback: when the
     * user opens a Latest-on-Usenet item's detail page and clicks Play,
     * this lets the playback flow recover the original NZB if
     * conventional source resolution finds zero candidates.
     */
    fun findReleaseFor(tmdbId: Int): MatchedRelease? =
        _state.value.firstOrNull { it.match.tmdbId == tmdbId }

    private fun NzbPosterCache.Match.Found.toMediaItem(): MediaItem = MediaItem(
        // TMDB id so the standard detail page resolves through existing
        // TMDB plumbing - opens with the proper synopsis / cast / etc.
        id = "tmdb:movie:$tmdbId",
        tmdbId = tmdbId,
        type = MediaType.MOVIE,
        title = title,
        year = year,
        overview = overview,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = voteAverage,
        // Carry the TMDB score into MediaRatings so V2PosterCard's
        // DesktopRatingPills renders at least the TMDB pill on the
        // shelf and see-all grid. IMDb / RT enrichment via MdbList
        // happens in [enrichListInPlace] when the MdbList key is set.
        ratings = voteAverage?.let {
            com.torve.domain.model.MediaRatings(tmdbScore = it.toFloat())
        },
    )
}
