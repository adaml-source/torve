package com.streamvault.data.mdblist

import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaRatings

class RatingsEnricher(private val api: MdbListApi) {

    private val cache = mutableMapOf<String, MediaRatings>()

    suspend fun enrichSingle(item: MediaItem, apiKey: String): MediaItem {
        if (apiKey.isBlank()) return item
        if (item.ratings != null) return item
        val imdbId = item.imdbId ?: return item

        val cached = cache[imdbId]
        if (cached != null) return item.copy(ratings = cached)

        val mdbRatings = try { api.getRatings(imdbId, apiKey) } catch (_: Exception) { null }
            ?: return item

        val ratings = MediaRatings(
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
        cache[imdbId] = ratings
        return item.copy(ratings = ratings)
    }

    suspend fun enrichList(items: List<MediaItem>, apiKey: String): List<MediaItem> {
        if (apiKey.isBlank()) return items
        return items.map { enrichSingle(it, apiKey) }
    }
}
