package com.torve.data.catalog

import com.torve.data.metadata.TmdbApiClient
import com.torve.db.TorveDatabase
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

/**
 * Pre-cached catalog of the top ~1000 highly-rated titles per genre, per
 * media type. Refreshed in the background every 24 hours; reads are
 * served straight from SQLDelight on genre clicks for instant grid
 * population — no network round-trip.
 *
 * Why this exists: without a pre-cache, clicking a genre fires a TMDB
 * discover request paginated 20 items at a time. Highly-rated titles
 * frequently land on pages 4-10 (sorted by vote_average + min vote
 * count), so the user has to scroll past dozens of mid-tier titles
 * before reaching what they actually want. With this cache the entire
 * 1000-deep top list is local and the grid renders in one paint.
 *
 * Source ranking: TMDB discover with `sort_by=vote_average.desc` and
 * `vote_count.gte=100` (set in TmdbApiClient). vote_average correlates
 * tightly with IMDB rating for popular titles, and the floor on
 * vote_count keeps the list out of the indie-shorts long tail.
 */
class CatalogTopCacheRepository(
    private val database: TorveDatabase,
    private val tmdbApi: TmdbApiClient,
) {
    /** Total target size per (mediaType, genre) bucket. */
    private val maxItemsPerGenre = 1000

    /** Page size the TMDB discover endpoint returns. */
    private val pageSize = 20

    /** Brief delay between TMDB requests so we don't hammer the API. */
    private val perRequestDelayMs = 80L

    /**
     * True if we have at least one cached entry for the genre. Used by
     * the catalog VM to decide whether to read from cache or fall back
     * to a live discover() request.
     */
    fun hasCacheFor(mediaType: String, genreId: Int): Boolean {
        val count = database.torveQueries
            .countTopForGenre(mediaType, genreId.toLong())
            .executeAsOneOrNull() ?: return false
        return count > 0L
    }

    /**
     * Returns true if the cache for this genre is older than [maxAgeMs]
     * (or empty). Caller should kick off a background refresh in that
     * case but keep using the existing cached data meanwhile.
     */
    fun isStale(mediaType: String, genreId: Int, maxAgeMs: Long): Boolean {
        // selectLastRefreshTime returns Long? (SQL MAX() is nullable);
        // the outer executeAsOneOrNull() captures the empty-table case.
        val last = database.torveQueries
            .selectLastRefreshTime(mediaType, genreId.toLong())
            .executeAsOneOrNull() ?: return true
        val lastFetch = last.last_fetch ?: return true
        val now = Clock.System.now().toEpochMilliseconds()
        return now - lastFetch > maxAgeMs
    }

    /**
     * Reads the cached top-N list for a genre and converts to MediaItems
     * suitable for the catalog grid.
     */
    fun getTop(mediaType: String, genreId: Int, limit: Int = maxItemsPerGenre): List<MediaItem> {
        val rows = database.torveQueries
            .selectTopForGenre(mediaType, genreId.toLong(), limit.toLong())
            .executeAsList()
        val type = if (mediaType == "movie") MediaType.MOVIE else MediaType.SERIES
        return rows.map { row ->
            MediaItem(
                id = "tmdb:${row.tmdb_id}",
                tmdbId = row.tmdb_id.toInt(),
                title = row.title,
                type = type,
                posterUrl = row.poster_path?.let { "${TmdbApiClient.IMAGE_BASE}/w342$it" },
                backdropUrl = row.backdrop_path?.let { "${TmdbApiClient.IMAGE_BASE}/w780$it" },
                overview = row.overview,
                year = row.year?.toInt(),
                rating = row.vote_average,
                releaseDate = row.release_date,
                // Critical: populate genreIds with the queried genre. Without
                // this, the ContentPolicyFilter classifies cache items as
                // "unknown" and silently drops them all in strict mode --
                // bug observed: "All providers + Action + Most Popular sort"
                // returned empty grid because cache items had no genres,
                // while the IMDB-sort path bypassed the cache and rendered
                // discover() items that DO have genreIds populated.
                genreIds = listOf(genreId),
            )
        }
    }

    /**
     * Refreshes the cache for one (mediaType, genreId) bucket by walking
     * 50 pages of TMDB discover (= up to 1000 items). Replaces all
     * existing rows for that bucket atomically. Returns the number of
     * items written.
     */
    suspend fun refreshGenre(mediaType: String, genreId: Int): Int {
        val maxPages = maxItemsPerGenre / pageSize
        val collected = mutableListOf<RankedItem>()
        for (page in 1..maxPages) {
            val items = runCatching {
                fetchPage(mediaType, genreId, page)
            }.getOrElse {
                println("CATALOG_TOP_CACHE: page=$page genre=$genreId mediaType=$mediaType failed: ${it.message}")
                emptyList()
            }
            if (items.isEmpty()) break
            collected.addAll(items)
            if (collected.size >= maxItemsPerGenre) break
            delay(perRequestDelayMs)
        }

        if (collected.isEmpty()) return 0

        val now = Clock.System.now().toEpochMilliseconds()
        val capped = collected.take(maxItemsPerGenre)
        database.torveQueries.transaction {
            database.torveQueries.deleteTopForGenre(mediaType, genreId.toLong())
            capped.forEachIndexed { idx, row ->
                database.torveQueries.insertTopItem(
                    media_type = mediaType,
                    genre_id = genreId.toLong(),
                    rank = idx.toLong(),
                    tmdb_id = row.tmdbId.toLong(),
                    title = row.title,
                    poster_path = row.posterPath,
                    backdrop_path = row.backdropPath,
                    overview = row.overview,
                    release_date = row.releaseDate,
                    year = row.year?.toLong(),
                    vote_average = row.voteAverage,
                    vote_count = row.voteCount?.toLong(),
                    fetched_at = now,
                )
            }
        }
        println("CATALOG_TOP_CACHE: refreshed mediaType=$mediaType genre=$genreId items=${capped.size}")
        return capped.size
    }

    private suspend fun fetchPage(mediaType: String, genreId: Int, page: Int): List<RankedItem> {
        return if (mediaType == "movie") {
            val resp = tmdbApi.discoverMovies(
                page = page,
                sortBy = "vote_average.desc",
                withGenres = genreId.toString(),
                requestCategory = "tmdb.discover.movie.top_cache",
            )
            resp.results.map { m ->
                RankedItem(
                    tmdbId = m.id,
                    title = m.title,
                    posterPath = m.posterPath,
                    backdropPath = m.backdropPath,
                    overview = m.overview.takeIf { it.isNotBlank() },
                    releaseDate = m.releaseDate,
                    year = m.releaseDate?.take(4)?.toIntOrNull(),
                    voteAverage = m.voteAverage,
                    voteCount = m.voteCount,
                )
            }
        } else {
            val resp = tmdbApi.discoverTv(
                page = page,
                sortBy = "vote_average.desc",
                withGenres = genreId.toString(),
                requestCategory = "tmdb.discover.tv.top_cache",
            )
            resp.results.map { t ->
                RankedItem(
                    tmdbId = t.id,
                    title = t.name,
                    posterPath = t.posterPath,
                    backdropPath = t.backdropPath,
                    overview = t.overview.takeIf { it.isNotBlank() },
                    releaseDate = t.firstAirDate,
                    year = t.firstAirDate?.take(4)?.toIntOrNull(),
                    voteAverage = t.voteAverage,
                    voteCount = t.voteCount,
                )
            }
        }
    }

    private data class RankedItem(
        val tmdbId: Int,
        val title: String,
        val posterPath: String?,
        val backdropPath: String?,
        val overview: String?,
        val releaseDate: String?,
        val year: Int?,
        val voteAverage: Double,
        val voteCount: Int?,
    )
}
