package com.torve.data.ratings

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.extractTmdbIdOrNull
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
import kotlinx.coroutines.CancellationException

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
                val tmdbId = item.tmdbId ?: item.id.extractTmdbIdOrNull() ?: return@mapNotNull null
                BackendRatingLookup(
                    mediaType = if (item.type == MediaType.SERIES) "tv" else "movie",
                    tmdbId = tmdbId,
                    imdbId = item.imdbId?.takeIf { it.isNotBlank() },
                )
            }
            .distinctBy { it.mediaType to it.tmdbId }
        if (lookups.isEmpty()) return BackendRatingsResult(reachable = true)
        val ratings = mutableMapOf<String, MediaRatings>()
        val freshImdbKeys = mutableSetOf<String>()
        var reachable = true
        for (batch in lookups.chunked(12)) try {
            val response = httpClient.post(
                "${baseUrlProvider().trimEnd('/')}/me/ratings/batch",
            ) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(BackendRatingBatchRequest(batch))
            }
            if (!response.status.isSuccess()) {
                reachable = false
            } else {
                val body = response.body<BackendRatingBatchResponse>()
                ratings.putAll(body.items.associate { rating ->
                        rating.cacheKey() to rating.toMediaRatings()
                    })
                freshImdbKeys += body.items.filter { !it.cached && it.imdbScore != null }.map { it.cacheKey() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            reachable = false
        }
        return BackendRatingsResult(reachable = reachable, ratings = ratings, freshImdbKeys = freshImdbKeys)
    }
}

data class BackendRatingsResult(
    val reachable: Boolean,
    val ratings: Map<String, MediaRatings> = emptyMap(),
    val freshImdbKeys: Set<String> = emptySet(),
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
    val cached: Boolean = true,
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
