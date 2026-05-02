package com.torve.desktop.adult

import com.torve.data.metadata.TmdbApiClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory TMDB lookup cache for NZB release titles. Used by
 * [com.torve.desktop.ui.v2.nzbmovies.V2NzbMoviesPage] to render a poster
 * grid instead of plain text rows.
 *
 * Caches both hits and misses (negative-cache as `Match.None`) so we
 * don't re-query TMDB for releases that have no match - common for
 * non-English/regional uploads or scene names with no canonical TMDB
 * entry. Lookup keys are `<title>|<year>` so different years of the
 * same title don't collide.
 */
class NzbPosterCache(
    private val tmdb: TmdbApiClient,
) {
    sealed interface Match {
        @kotlinx.serialization.Serializable
        data class Found(
            val tmdbId: Int,
            val title: String,
            val year: Int?,
            val posterUrl: String?,
            val backdropUrl: String?,
            val voteAverage: Double?,
            val overview: String?,
            /** TMDB genre IDs - fed into the genre filter chips on the
             *  Latest-on-Usenet see-all page. */
            val genreIds: List<Int> = emptyList(),
        ) : Match
        data object None : Match
    }

    private val cache = mutableMapOf<String, Match>()
    private val mutex = Mutex()
    @Volatile private var hydratedFromDisk = false
    @Volatile private var lastSaveAtMs = 0L

    enum class MediaKind(val tmdbType: String) {
        MOVIE("movie"),
        TV("tv"),
    }

    suspend fun lookup(title: String, year: Int?): Match = lookup(title, year, MediaKind.MOVIE)

    suspend fun lookup(title: String, year: Int?, mediaKind: MediaKind): Match {
        val key = "${mediaKind.name}|${title.lowercase()}|${year ?: -1}"
        // Lazy disk hydration on first lookup so app startup isn't
        // gated on the file read.
        if (!hydratedFromDisk) {
            hydratedFromDisk = true
            val loaded = NzbDiskCache.loadPosterCache()
            if (loaded.isNotEmpty()) {
                mutex.withLock { cache.putAll(loaded) }
            }
        }
        mutex.withLock { cache[key] }?.let { return it }

        val resolved: Match = runCatching {
            val resp = tmdb.searchMulti(title)
            val results = resp.results
            // Prefer the hit matching the requested media kind + year;
            // fall back to media-kind only, then the top result.
            val target = mediaKind.tmdbType
            val movie = results.firstOrNull { it.mediaType == target && it.releaseYear() == year }
                ?: results.firstOrNull { it.mediaType == target }
                ?: results.firstOrNull()
            if (movie != null) {
                Match.Found(
                    tmdbId = movie.id,
                    title = movie.title.orEmpty().ifBlank { movie.name.orEmpty() },
                    year = movie.releaseYear(),
                    posterUrl = movie.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                    backdropUrl = movie.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
                    voteAverage = movie.voteAverage.takeIf { it > 0.0 },
                    overview = movie.overview,
                    genreIds = movie.genreIds,
                )
            } else Match.None
        }.getOrDefault(Match.None)

        mutex.withLock { cache[key] = resolved }
        // Debounced disk save: write the full positive-match map at
        // most every 3s so a 100-row page coalesces into ~1 fsync.
        // Negative-cache entries (Match.None) stay in-memory only.
        val now = System.currentTimeMillis()
        if (now - lastSaveAtMs > 3_000L) {
            lastSaveAtMs = now
            val snapshot = mutex.withLock {
                cache.entries
                    .mapNotNull { (k, v) -> (v as? Match.Found)?.let { k to it } }
                    .toMap()
            }
            NzbDiskCache.savePosterCache(snapshot)
        }
        return resolved
    }

    private fun com.torve.data.metadata.TmdbMultiResult.releaseYear(): Int? {
        val raw = releaseDate?.takeIf { it.isNotBlank() } ?: firstAirDate
        return raw?.take(4)?.toIntOrNull()
    }
}
