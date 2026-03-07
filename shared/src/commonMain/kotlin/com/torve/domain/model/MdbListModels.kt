package com.torve.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MdbListInfo(
    val id: Int = 0,
    val name: String = "",
    val slug: String = "",
    val description: String = "",
    val mediatype: String = "",
    val items: Int = 0,
    val likes: Int = 0,
    @SerialName("user_name")
    val userName: String = "",
    val dynamic: Boolean = false,
)

@Serializable
data class MdbListItem(
    val id: Int = 0,
    val title: String = "",
    val year: Int? = null,
    @SerialName("mediatype")
    val mediaType: String = "",
    @SerialName("imdb_id")
    val imdbId: String? = null,
    @SerialName("tmdb_id")
    val tmdbId: Int? = null,
    @SerialName("tvdb_id")
    val tvdbId: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val runtime: Int? = null,
    @SerialName("score")
    val mdblistScore: Float? = null,
    @SerialName("score_average")
    val scoreAverage: Double? = null,
    @SerialName("imdbrating")
    val imdbRating: Float? = null,
    @SerialName("imdbvotes")
    val imdbVotes: Int? = null,
    @SerialName("tomatoesrating")
    val tomatoesRating: Int? = null,
    @SerialName("tomatoesaudience")
    val tomatoesAudience: Int? = null,
    @SerialName("tmdbrating")
    val tmdbRating: Float? = null,
    @SerialName("metacritic")
    val metacriticRating: Int? = null,
    @SerialName("traktrating")
    val traktRating: Float? = null,
    @SerialName("letterboxdrating")
    val letterboxdRating: Float? = null,
    @SerialName("malrating")
    val malRating: Float? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val language: String? = null,
    val certification: String? = null,
    val rank: Int? = null,
)

@Serializable
data class MdbListRatings(
    val title: String = "",
    @SerialName("imdbid")
    val imdbId: String? = null,
    val ratings: List<MdbRatingSource> = emptyList(),
)

@Serializable
data class MdbRatingSource(
    val source: String = "",
    val value: Float? = null,
    val score: Float? = null,
    val votes: Int? = null,
)

@Serializable
data class MdbListShelfConfig(
    val listId: Int,
    val name: String,
    val itemCount: Int = 20,
    val enabled: Boolean = true,
    val order: Int = 0,
)

fun MdbListItem.toMediaItem(): MediaItem {
    val type = when (mediaType.lowercase()) {
        "show", "tv", "series" -> MediaType.SERIES
        else -> MediaType.MOVIE
    }
    return MediaItem(
        id = (tmdbId ?: imdbId ?: id).toString(),
        tmdbId = tmdbId,
        imdbId = imdbId,
        type = type,
        title = title,
        year = year,
        overview = description,
        posterUrl = poster,
        backdropUrl = backdrop,
        rating = imdbRating?.toDouble(),
        runtime = runtime,
        ratings = MediaRatings(
            imdbScore = imdbRating,
            imdbVotes = imdbVotes,
            rottenTomatoesScore = tomatoesRating,
            rtAudienceScore = tomatoesAudience,
            tmdbScore = tmdbRating,
            metacriticScore = metacriticRating,
            letterboxdScore = letterboxdRating,
            traktScore = traktRating,
            mdblistScore = mdblistScore,
            malScore = malRating,
        ),
    )
}
