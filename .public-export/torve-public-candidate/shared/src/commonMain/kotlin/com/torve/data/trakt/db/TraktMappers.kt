package com.torve.data.trakt.db

import com.torve.data.trakt.TraktRatingResponse
import kotlinx.datetime.Instant

data class TraktCachedRating(
    val mediaKey: String,
    val tmdbId: Int,
    val mediaType: String,
    val rating: Int,
    val ratedAt: Long,
    val updatedAt: Long,
)

fun mapRatingResponseToCache(
    item: TraktRatingResponse,
    nowMs: Long,
): TraktCachedRating? {
    val isMovie = item.type == "movie"
    val media = if (isMovie) item.movie else item.show
    val tmdbId = media?.ids?.tmdb ?: return null
    val mediaType = if (isMovie) "movie" else "series"
    val ratedAt = runCatching { Instant.parse(item.ratedAt).toEpochMilliseconds() }.getOrDefault(nowMs)
    return TraktCachedRating(
        mediaKey = "$mediaType:$tmdbId",
        tmdbId = tmdbId,
        mediaType = mediaType,
        rating = item.rating.coerceIn(1, 10),
        ratedAt = ratedAt,
        updatedAt = nowMs,
    )
}
