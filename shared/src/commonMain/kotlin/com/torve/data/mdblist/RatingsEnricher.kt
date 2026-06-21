package com.torve.data.mdblist

import com.torve.data.metadata.TmdbApiClient
import com.torve.data.ratings.OmdbClient
import com.torve.data.trakt.TraktClient
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.domain.model.extractTmdbIdOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

class RatingsEnricher(
    private val api: MdbListApi,
    private val tmdbApi: TmdbApiClient,
    private val traktClient: TraktClient,
    private val cacheRepo: RatingsCacheRepository,
    private val omdbClient: OmdbClient,
) {

    private val imdbCache = mutableMapOf<String, String?>()
    private val imdbCacheMutex = Mutex()

    // Class-level (not per-call) so all concurrent enrichList invocations
    // across the app share the same budget. A per-call semaphore let every
    // rail spin up 6 requests independently — 10 rails = 60 in-flight =
    // instant MDBList 429 and UI jank from the flood of state updates.
    private val globalSemaphore = Semaphore(MAX_CONCURRENT_ENRICH)

    /**
     * When MDBList returns 429 we back off for a cooldown window rather than sticking
     * the flag for the whole session — a rate limit on one rail shouldn't wipe ratings
     * off every later rail.
     */
    private var rateLimitExpiresAt: Long = 0L

    private var loggedMdbListDisabled = false

    val rateLimited: Boolean
        get() = Clock.System.now().toEpochMilliseconds() < rateLimitExpiresAt

    /**
     * Milliseconds until the MDBList rate-limit cooldown expires. Zero if
     * not currently rate-limited. Callers use this to pace a retry sweep
     * after a storm of enrichment calls hit 429.
     */
    fun rateLimitRemainingMs(): Long {
        val now = Clock.System.now().toEpochMilliseconds()
        val remaining = rateLimitExpiresAt - now
        return if (remaining > 0) remaining else 0L
    }

    private fun markRateLimited() {
        rateLimitExpiresAt = Clock.System.now().toEpochMilliseconds() + RATE_LIMIT_COOLDOWN_MS
    }

    companion object {
        private const val RATE_LIMIT_COOLDOWN_MS = 60_000L
        // Cap concurrent network fan-out per rail. OMDB allows 1000/day on
        // the free tier and MDBList has its own per-minute window — 6 in
        // flight gives ~3-4× the throughput of sequential without piling
        // up requests fast enough to trigger immediate rate-limits.
        private const val MAX_CONCURRENT_ENRICH = 6
    }

    /**
     * Enriches a single MediaItem with ratings from all available tiers:
     *
     * Tier 0: Persistent SQLite cache (30 days) — return immediately if fresh
     * Tier 1: TMDB baseline — always available from item.rating
     * Tier 2: OMDB (if key configured) — IMDb, RT, Metacritic in one call
     * Tier 3: MDBList (if key configured) — full suite: all sources
     * Tier 4: Trakt public API (free, always available) — fallback
     *
     * All fetched ratings are merged and cached for 30 days.
     */
    suspend fun enrichSingle(item: MediaItem, apiKey: String): MediaItem {
        // Addon catalog items (Trending, Latest Releases, Popular, Language,
        // Year, …) arrive with namespaced ids like "tmdb:1523145" or "tt0111161"
        // while `tmdbId` / `imdbId` are left unset. Without recovering those,
        // cache lookup, OMDB, MDBList and Trakt all bail → the card renders
        // with zero rating pills.
        val tmdbId = item.tmdbId ?: item.id.extractTmdbIdOrNull()
        val existing = item.ratings

        // Extract IMDB ID from item fields first (no network). Used as cache key
        // fallback when tmdbId is null (e.g. Stremio items arriving as "tt0403358").
        val quickImdbId = item.imdbId?.takeIf { it.startsWith("tt") }
            ?: item.id.extractImdbIdOrNull()

        // 1. Build cache key — prefer TMDB ID, fall back to IMDB ID so items that
        // arrive without a tmdbId still hit/populate the cache.
        val cacheKey = tmdbId?.let { "${item.type.name}:$it" }
            ?: quickImdbId?.let { "${item.type.name}:$it" }

        // 2. Check persistent SQLite cache — fresh (< 30 days)? return cached ratings
        cacheKey?.let {
            val cached = cacheRepo.getCached(it)
            if (cached != null) {
                return item.copy(
                    tmdbId = tmdbId,
                    ratings = mergeRatings(existing, cached),
                )
            }
        }

        // Resolve IMDb ID — use the quick one already extracted, or look it up via TMDB
        val imdbId = quickImdbId
            ?: tmdbId?.let { resolveImdbIdForTmdb(item.type, it) }

        // Accumulate ratings from all tiers
        var accumulated = MediaRatings(
            tmdbScore = item.rating?.toFloat(), // TMDB baseline always available
        )

        // 3. Tier 2: OMDB — IMDb + RT + Metacritic (free key, 1000 calls/day)
        if (imdbId != null) {
            val omdbRatings = try {
                omdbClient.fetchRatings(imdbId)
            } catch (_: Exception) {
                null
            }
            if (omdbRatings != null) {
                accumulated = mergeRatings(accumulated, omdbRatings)
            }
        }

        // 4. Tier 3: MDBList — full suite (if apiKey valid + not rate-limited)
        var mdbSucceeded = false
        // Track whether MDBList was skipped/dropped *because of* rate limiting
        // (vs. simply returning null for a title it doesn't know). Rate-
        // limited skips mean "try again later" — we must NOT cache the
        // OMDB-only partial result, otherwise the 30-day TTL pins the item
        // to incomplete data long past MDBList's cooldown.
        var mdbRateLimitSkipped = false
        var resolvedImdbId: String? = imdbId
        if (!apiKey.isUsableMdbListApiKey()) {
            logMdbListDisabled()
        } else {
            if (rateLimited) {
                mdbRateLimitSkipped = true
            }
            val mdbRatings = if (!rateLimited) try {
                when {
                    tmdbId != null && item.type == MediaType.MOVIE -> api.getRatingsByTmdbMovie(tmdbId, apiKey)
                    tmdbId != null && item.type == MediaType.SERIES -> api.getRatingsByTmdbShow(tmdbId, apiKey)
                    imdbId != null -> api.getRatings(imdbId, apiKey)
                    else -> null
                }
            } catch (e: MdbListApi.RateLimitException) {
                markRateLimited()
                mdbRateLimitSkipped = true
                null
            } catch (_: Exception) {
                null
            } else null

            if (mdbRatings != null) {
                val mdbMediaRatings = MediaRatings(
                    imdbScore = mdbRatings.ratings.find { it.source == "imdb" }?.value,
                    imdbVotes = mdbRatings.ratings.find { it.source == "imdb" }?.votes,
                    rottenTomatoesScore = mdbRatings.ratings.find { it.source == "tomatoes" }?.value?.toInt(),
                    rtAudienceScore = mdbRatings.ratings.find { it.source == "tomatoesaudience" }?.value?.toInt(),
                    tmdbScore = mdbRatings.ratings.find { it.source == "tmdb" }?.value,
                    metacriticScore = mdbRatings.ratings.find { it.source == "metacritic" }?.value?.toInt(),
                    letterboxdScore = mdbRatings.ratings.find { it.source == "letterboxd" }?.value,
                    traktScore = mdbRatings.ratings.find { it.source == "trakt" }?.value,
                    mdblistScore = mdbRatings.ratings.find { it.source == "mdblist" }?.score,
                    malScore = mdbRatings.ratings.find { it.source == "mal" }?.value,
                )
                accumulated = mergeRatings(accumulated, mdbMediaRatings)
                resolvedImdbId = mdbRatings.imdbId ?: imdbId
                mdbSucceeded = true
            }
        }

        // 5. Tier 4: Trakt public API (free, always available)
        // Fire whenever we don't already have a Trakt score, even if MDBList
        // succeeded — MDBList's response leaves `trakt` null for plenty of
        // titles (especially newer/less-tracked ones) and we don't want that
        // pill to stay blank when Trakt itself has the data. Also serves as
        // the IMDB fallback when neither OMDB nor MDBList yielded one.
        if (resolvedImdbId != null && accumulated.traktScore == null) {
            val traktRating = try {
                when (item.type) {
                    MediaType.MOVIE -> traktClient.getMoviePublicRating(resolvedImdbId)
                    MediaType.SERIES -> traktClient.getShowPublicRating(resolvedImdbId)
                }
            } catch (_: Exception) {
                null
            }
            if (traktRating != null && traktRating.rating > 0f) {
                accumulated = mergeRatings(accumulated, MediaRatings(
                    traktScore = traktRating.rating * 10f, // Trakt 0-10 → percentage
                    // Trakt ratings are user-voted on an IMDB-aligned 0-10 scale.
                    // Use it as the IMDB-pill fallback when neither OMDB nor
                    // MDBList provided one.
                    imdbScore = if (accumulated.imdbScore == null) traktRating.rating else null,
                ))
            }
        }

        // 6. Cache and return.
        // Cache when we got *any* non-TMDB signal (OMDB, MDBList, or Trakt).
        // Skip caching when we have only the TMDB baseline — caching would
        // pin the card to TMDB-only for the 30-day TTL and block a later
        // MDBList/OMDB hit (e.g. once an unreleased title goes live).
        val hasFreshSignal = accumulated.imdbScore != null ||
            accumulated.rottenTomatoesScore != null ||
            accumulated.metacriticScore != null ||
            accumulated.rtAudienceScore != null ||
            accumulated.letterboxdScore != null ||
            accumulated.traktScore != null ||
            accumulated.mdblistScore != null ||
            accumulated.malScore != null
        // Only cache when MDBList completed (full data set) OR we explicitly
        // have OMDB-tier data. Trakt-only is fragile for unreleased titles
        // so we keep it transient — same rule as before.
        //
        // CRITICAL: skip caching if MDBList was rate-limited during this call.
        // Caching the OMDB-only partial pins the item to incomplete data for
        // the 30-day TTL — the retry pass in the viewmodel waits out the
        // cooldown and re-runs; if we cached, that retry is a no-op.
        val hasOmdbData = accumulated.rottenTomatoesScore != null ||
            accumulated.metacriticScore != null
        if (hasFreshSignal && (mdbSucceeded || hasOmdbData) && !mdbRateLimitSkipped) {
            cacheKey?.let { cacheRepo.put(it, accumulated) }
        }
        return item.copy(
            tmdbId = tmdbId,
            imdbId = resolvedImdbId ?: item.imdbId,
            ratings = mergeRatings(existing, accumulated),
        )
    }

    suspend fun enrichList(items: List<MediaItem>, apiKey: String): List<MediaItem> = withContext(Dispatchers.Default) {
        if (items.isEmpty()) return@withContext items
        coroutineScope {
        val enriched = items.map { item ->
            async {
                globalSemaphore.withPermit {
                    runCatching { enrichSingle(item, apiKey) }.getOrDefault(item)
                }
            }
        }.awaitAll()
        enriched
        }
    }

    /**
     * Restores persisted ratings without doing network work.
     * This is used when screens rebuild content after navigation or refresh.
     *
     * Addon items arrive with namespaced ids ("tmdb:1523145") and a null
     * tmdbId field — pull the id out so hydration still finds the cached
     * entry. Without this, pills briefly vanish on reload for every addon
     * rail until refreshRatings catches up seconds later.
     */
    fun hydrateFromCache(item: MediaItem): MediaItem {
        val tmdbId = item.tmdbId ?: item.id.extractTmdbIdOrNull()
        val imdbId = item.imdbId?.takeIf { it.startsWith("tt") } ?: item.id.extractImdbIdOrNull()
        val cacheKey = tmdbId?.let { "${item.type.name}:$it" }
            ?: imdbId?.let { "${item.type.name}:$it" }
            ?: return item
        val cached = cacheRepo.getCached(cacheKey) ?: return item
        return item.copy(
            tmdbId = tmdbId,
            ratings = mergeRatings(item.ratings, cached),
        )
    }

    fun hydrateListFromCache(items: List<MediaItem>): List<MediaItem> {
        return items.map(::hydrateFromCache)
    }

    fun clearPersistentCache() {
        cacheRepo.clearAll()
    }

    fun clearExpiredCache() {
        cacheRepo.deleteStale()
    }

    /** Check if an item has any cached ratings (for background enrichment scheduling). */
    fun hasCachedRatings(item: MediaItem): Boolean {
        val tmdbId = item.tmdbId ?: return false
        val cacheKey = "${item.type.name}:$tmdbId"
        return cacheRepo.getCached(cacheKey) != null
    }

    private suspend fun resolveImdbIdForTmdb(type: MediaType, tmdbId: Int): String? {
        val cacheKey = "${type.name}:$tmdbId"
        imdbCacheMutex.withLock {
            if (imdbCache.containsKey(cacheKey)) return imdbCache[cacheKey]
        }

        val imdbId = try {
            when (type) {
                MediaType.MOVIE -> {
                    tmdbApi.getMovieExternalIds(tmdbId).imdbId
                        ?: tmdbApi.getMovieDetail(tmdbId).imdbId
                }
                MediaType.SERIES -> {
                    tmdbApi.getTvExternalIds(tmdbId).imdbId
                        ?: tmdbApi.getTvDetail(tmdbId).externalIds?.imdbId
                }
            }
        } catch (_: Exception) {
            null
        }
        imdbCacheMutex.withLock { imdbCache[cacheKey] = imdbId }
        return imdbId
    }

    private fun mergeRatings(existing: MediaRatings?, fresh: MediaRatings): MediaRatings {
        if (existing == null) return fresh
        return MediaRatings(
            imdbScore = existing.imdbScore ?: fresh.imdbScore,
            imdbVotes = existing.imdbVotes ?: fresh.imdbVotes,
            rottenTomatoesScore = existing.rottenTomatoesScore ?: fresh.rottenTomatoesScore,
            rtAudienceScore = existing.rtAudienceScore ?: fresh.rtAudienceScore,
            tmdbScore = existing.tmdbScore ?: fresh.tmdbScore,
            metacriticScore = existing.metacriticScore ?: fresh.metacriticScore,
            letterboxdScore = existing.letterboxdScore ?: fresh.letterboxdScore,
            traktScore = existing.traktScore ?: fresh.traktScore,
            mdblistScore = existing.mdblistScore ?: fresh.mdblistScore,
            malScore = existing.malScore ?: fresh.malScore,
        )
    }

    private fun String.isUsableMdbListApiKey(): Boolean =
        isNotBlank() &&
            this != MdbListApi.DEFAULT_API_KEY &&
            !contains("INSERT", ignoreCase = true)

    private fun logMdbListDisabled() {
        if (loggedMdbListDisabled) return
        loggedMdbListDisabled = true
        println("TORVE_RATINGS: MDBList disabled; configure MDBLIST_API_KEY to enable MDBList aggregate, RT audience, Letterboxd, MAL, and related provider ratings.")
    }
}
