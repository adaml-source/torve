package com.torve.data.ratings

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class BackendRatingsApi(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val accessTokenProvider: suspend () -> String?,
) {
    suspend fun fetch(items: List<MediaItem>): BackendRatingsResult {
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
            ?: return BackendRatingsResult(reachable = false)
        val lookups = items
            .mapNotNull { item ->
                val tmdbId = item.tmdbId ?: return@mapNotNull null
                BackendRatingLookup(
                    mediaType = if (item.type == MediaType.SERIES) "tv" else "movie",
                    tmdbId = tmdbId,
                    imdbId = item.imdbId?.takeIf { it.isNotBlank() },
                )
            }
            .distinctBy { it.mediaType to it.tmdbId }
            // Search requests the visible window first. Deeper cards are
            // enriched on focus, avoiding a large cold-start fan-out.
            .take(12)
        if (lookups.isEmpty()) return BackendRatingsResult(reachable = true)
        return try {
            val response = httpClient.post(
                "${baseUrlProvider().trimEnd('/')}/me/ratings/batch",
            ) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(BackendRatingBatchRequest(lookups))
            }
            if (!response.status.isSuccess()) {
                BackendRatingsResult(reachable = false)
            } else {
                val body = response.body<BackendRatingBatchResponse>()
                BackendRatingsResult(
                    reachable = true,
                    ratings = body.items.associate { rating ->
                        rating.cacheKey() to rating.toMediaRatings()
                    },
                )
            }
        } catch (_: Exception) {
            BackendRatingsResult(reachable = false)
        }
    }
}

data class BackendRatingsResult(
    val reachable: Boolean,
    val ratings: Map<String, MediaRatings> = emptyMap(),
)

@Serializable
private data class BackendRatingBatchRequest(
    val items: List<BackendRatingLookup>,
)

@Serializable
private data class BackendRatingLookup(
    @SerialName("media_type")
    val mediaType: String,
    @SerialName("tmdb_id")
    val tmdbId: Int,
    @SerialName("imdb_id")
    val imdbId: String? = null,
)

@Serializable
private data class BackendRatingBatchResponse(
    val items: List<BackendRatingDto> = emptyList(),
)

@Serializable
private data class BackendRatingDto(
    @SerialName("media_type")
    val mediaType: String,
    @SerialName("tmdb_id")
    val tmdbId: Int,
    @SerialName("imdb_score")
    val imdbScore: Float? = null,
    @SerialName("imdb_votes")
    val imdbVotes: Int? = null,
    @SerialName("tmdb_score")
    val tmdbScore: Float? = null,
    @SerialName("rotten_tomatoes_score")
    val rottenTomatoesScore: Int? = null,
    @SerialName("rt_audience_score")
    val rtAudienceScore: Int? = null,
    @SerialName("metacritic_score")
    val metacriticScore: Int? = null,
    @SerialName("letterboxd_score")
    val letterboxdScore: Float? = null,
    @SerialName("trakt_score")
    val traktScore: Float? = null,
    @SerialName("mdblist_score")
    val mdblistScore: Float? = null,
    @SerialName("mal_score")
    val malScore: Float? = null,
) {
    fun cacheKey(): String =
        "${if (mediaType == "tv") MediaType.SERIES.name else MediaType.MOVIE.name}:$tmdbId"

    fun toMediaRatings(): MediaRatings = MediaRatings(
        imdbScore = imdbScore,
        imdbVotes = imdbVotes,
        rottenTomatoesScore = rottenTomatoesScore,
        rtAudienceScore = rtAudienceScore,
        tmdbScore = tmdbScore,
        metacriticScore = metacriticScore,
        letterboxdScore = letterboxdScore,
        traktScore = traktScore,
        mdblistScore = mdblistScore,
        malScore = malScore,
    )
}
