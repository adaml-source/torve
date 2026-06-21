package com.torve.data.mdblist

import com.torve.db.TorveDatabase
import com.torve.domain.model.MediaRatings
import kotlinx.datetime.Clock

class RatingsCacheRepository(
    private val database: TorveDatabase,
) {
    companion object {
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }

    private val queries get() = database.torveQueries

    fun getCached(key: String): MediaRatings? {
        val row = queries.getCachedRating(key).executeAsOneOrNull() ?: return null
        val age = Clock.System.now().toEpochMilliseconds() - row.fetched_at
        if (age > THIRTY_DAYS_MS) return null
        val ratings = MediaRatings(
            imdbScore = row.imdb_score?.toFloat(),
            imdbVotes = row.imdb_votes?.toInt(),
            rottenTomatoesScore = row.rt_score?.toInt(),
            rtAudienceScore = row.rt_audience?.toInt(),
            tmdbScore = row.tmdb_score?.toFloat(),
            metacriticScore = row.metacritic_score?.toInt(),
            letterboxdScore = row.letterboxd_score?.toFloat(),
            traktScore = row.trakt_score?.toFloat(),
            mdblistScore = row.mdblist_score?.toFloat(),
            malScore = row.mal_score?.toFloat(),
        )
        // Reject partial cache entries (e.g. Trakt-only fallback from older versions)
        if (!isComplete(ratings)) {
            queries.deleteRating(key)
            return null
        }
        return ratings
    }

    /** A cached entry is considered complete if it has at least 2 non-null rating fields. */
    private fun isComplete(ratings: MediaRatings): Boolean {
        var count = 0
        if (ratings.imdbScore != null) count++
        if (ratings.rottenTomatoesScore != null) count++
        if (ratings.tmdbScore != null) count++
        if (ratings.metacriticScore != null) count++
        if (ratings.letterboxdScore != null) count++
        if (ratings.traktScore != null) count++
        if (ratings.mdblistScore != null) count++
        if (ratings.malScore != null) count++
        return count >= 2
    }

    fun put(key: String, ratings: MediaRatings) {
        queries.upsertRating(
            cache_key = key,
            imdb_score = ratings.imdbScore?.toDouble(),
            imdb_votes = ratings.imdbVotes?.toLong(),
            rt_score = ratings.rottenTomatoesScore?.toLong(),
            rt_audience = ratings.rtAudienceScore?.toLong(),
            tmdb_score = ratings.tmdbScore?.toDouble(),
            metacritic_score = ratings.metacriticScore?.toLong(),
            letterboxd_score = ratings.letterboxdScore?.toDouble(),
            trakt_score = ratings.traktScore?.toDouble(),
            mdblist_score = ratings.mdblistScore?.toDouble(),
            mal_score = ratings.malScore?.toDouble(),
            fetched_at = Clock.System.now().toEpochMilliseconds(),
        )
    }

    fun clearAll() {
        queries.deleteAllRatings()
    }

    fun deleteStale() {
        val cutoff = Clock.System.now().toEpochMilliseconds() - THIRTY_DAYS_MS
        queries.deleteStaleRatings(cutoff)
    }
}
