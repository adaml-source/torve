package com.torve.data.mdblist

import com.torve.data.metadata.TmdbApiClient
import com.torve.data.ratings.OmdbClient
import com.torve.data.trakt.TraktClient
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import kotlinx.datetime.Clock

class RatingsEnricher(
    private val api: MdbListApi,
    private val tmdbApi: TmdbApiClient,
    private val traktClient: TraktClient,
    private val cacheRepo: RatingsCacheRepository,
    private val omdbClient: OmdbClient,
) {

    private val imdbCache = mutableMapOf<String, String?>()

    /**
     * When MDBList returns 429 we back off for a cooldown window rather than sticking
     * the flag for the whole session — a rate limit on one rail shouldn't wipe ratings
     * off every later rail.
     */
    @Volatile
    private var rateLimitExpiresAt: Long = 0L

    val rateLimited: Boolean
        get() = Clock.System.now().toEpochMilliseconds() < rateLimitExpiresAt

    private fun markRateLimited() {
        rateLimitExpiresAt = Clock.System.now().toEpochMilliseconds() + RATE_LIMIT_COOLDOWN_MS
    }

    companion object {
        private const val RATE_LIMIT_COOLDOWN_MS = 60_000L
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
        val tmdbId = item.tmdbId
        val existing = item.ratings

        // 1. Build cache key
        val cacheKey = tmdbId?.let { "${item.type.name}:$it" }

        // 2. Check persistent SQLite cache — fresh (< 30 days)? return cached ratings
        cacheKey?.let {
            val cached = cacheRepo.getCached(it)
            if (cached != null) {
                return item.copy(ratings = mergeRatings(existing, cached))
            }
        }

        // Resolve IMDb ID (needed by OMDB + MDBList + Trakt)
        val imdbId = item.imdbId?.takeIf { it.startsWith("tt") }
            ?: resolveImdbId(item)

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
        if (apiKey.isNotBlank() && !rateLimited) {
            val mdbRatings = try {
                when {
                    tmdbId != null && item.type == MediaType.MOVIE -> api.getRatingsByTmdbMovie(tmdbId, apiKey)
                    tmdbId != null && item.type == MediaType.SERIES -> api.getRatingsByTmdbShow(tmdbId, apiKey)
                    imdbId != null -> api.getRatings(imdbId, apiKey)
                    else -> null
                }
            } catch (e: MdbListApi.RateLimitException) {
                markRateLimited()
                null
            } catch (_: Exception) {
                null
            }

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
                val resolvedImdbId = mdbRatings.imdbId ?: imdbId

                // Cache the final merged result
                cacheKey?.let { cacheRepo.put(it, accumulated) }
                return item.copy(
                    imdbId = resolvedImdbId ?: imdbId,
                    ratings = mergeRatings(existing, accumulated),
                )
            }
        }

        // If we got OMDB data, cache it even without MDBList
        val hasOmdbData = accumulated.imdbScore != null ||
            accumulated.rottenTomatoesScore != null ||
            accumulated.metacriticScore != null
        if (hasOmdbData) {
            cacheKey?.let { cacheRepo.put(it, accumulated) }
            return item.copy(
                imdbId = imdbId ?: item.imdbId,
                ratings = mergeRatings(existing, accumulated),
            )
        }

        // 5. Tier 4: Trakt public API (free, always available)
        if (imdbId != null) {
            val traktRating = try {
                when (item.type) {
                    MediaType.MOVIE -> traktClient.getMoviePublicRating(imdbId)
                    MediaType.SERIES -> traktClient.getShowPublicRating(imdbId)
                }
            } catch (_: Exception) {
                null
            }

            if (traktRating != null && traktRating.rating > 0f) {
                accumulated = mergeRatings(accumulated, MediaRatings(
                    traktScore = traktRating.rating * 10f, // Trakt 0-10 → percentage
                    // Trakt ratings are user-voted on an IMDB-aligned 0-10 scale.
                    // If OMDB/MDBList gave us nothing, surface Trakt's score as
                    // the IMDB pill too, so every enriched card consistently has
                    // at least IMDB + TMDB + Trakt pills rather than Trakt only.
                    imdbScore = traktRating.rating,
                ))
                // Do NOT cache partial Trakt-only data: for unreleased titles
                // this could pin the card to just-Trakt for 30 days (cache TTL),
                // blocking the fuller OMDB/MDBList result once the film releases.
                return item.copy(
                    imdbId = imdbId,
                    ratings = mergeRatings(existing, accumulated),
                )
            }
        }

        // 6. Fallback: item unchanged (TMDB score from item.rating still shows).
        // Do NOT cache here — we got no extra signal beyond the TMDB baseline
        // the item already had, and caching would block a later MDBList/OMDB
        // hit for up to 30 days (the cache TTL).
        return item.copy(
            imdbId = imdbId ?: item.imdbId,
            ratings = mergeRatings(existing, accumulated),
        )
    }

    suspend fun enrichList(items: List<MediaItem>, apiKey: String): List<MediaItem> {
        return items.map { enrichSingle(it, apiKey) }
    }

    /**
     * Restores persisted ratings without doing network work.
     * This is used when screens rebuild content after navigation or refresh.
     */
    fun hydrateFromCache(item: MediaItem): MediaItem {
        val tmdbId = item.tmdbId ?: return item
        val cacheKey = "${item.type.name}:$tmdbId"
        val cached = cacheRepo.getCached(cacheKey) ?: return item
        return item.copy(ratings = mergeRatings(item.ratings, cached))
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

    private suspend fun resolveImdbId(item: MediaItem): String? {
        val tmdbId = item.tmdbId ?: return null
        val cacheKey = "${item.type.name}:$tmdbId"
        if (imdbCache.containsKey(cacheKey)) return imdbCache[cacheKey]

        val imdbId = try {
            when (item.type) {
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
        imdbCache[cacheKey] = imdbId
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
}
