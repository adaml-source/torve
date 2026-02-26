package com.streamvault.data.mdblist

import com.streamvault.data.metadata.TmdbApiClient
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaRatings
import com.streamvault.domain.model.MediaType

class RatingsEnricher(
    private val api: MdbListApi,
    private val tmdbApi: TmdbApiClient,
) {

    private val cache = mutableMapOf<String, MediaRatings>()
    private val imdbCache = mutableMapOf<String, String?>()

    suspend fun enrichSingle(item: MediaItem, apiKey: String): MediaItem {
        if (apiKey.isBlank()) return item
        val tmdbId = item.tmdbId
        val imdbId = item.imdbId?.takeIf { it.startsWith("tt") }
            ?: resolveImdbId(item)

        val existing = item.ratings
        imdbId?.let {
            val cached = cache[it]
            if (cached != null) {
                return item.copy(
                    imdbId = it,
                    ratings = mergeRatings(existing, cached),
                )
            }
        }

        val mdbRatings = try {
            when {
                tmdbId != null && item.type == MediaType.MOVIE -> api.getRatingsByTmdbMovie(tmdbId, apiKey)
                tmdbId != null && item.type == MediaType.SERIES -> api.getRatingsByTmdbShow(tmdbId, apiKey)
                imdbId != null -> api.getRatings(imdbId, apiKey)
                else -> null
            }
        } catch (_: Exception) {
            null
        } ?: return item.copy(imdbId = imdbId)

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
        val resolvedImdbId = mdbRatings.imdbId ?: imdbId
        resolvedImdbId?.let { cache[it] = ratings }
        return item.copy(
            imdbId = resolvedImdbId ?: imdbId,
            ratings = mergeRatings(existing, ratings),
        )
    }

    suspend fun enrichList(items: List<MediaItem>, apiKey: String): List<MediaItem> {
        if (apiKey.isBlank()) return items
        return items.map { enrichSingle(it, apiKey) }
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
