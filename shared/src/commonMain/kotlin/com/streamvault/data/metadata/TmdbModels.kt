package com.streamvault.data.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbResponse<T>(
    val page: Int,
    val results: List<T>,
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("total_results") val totalResults: Int = 0,
)

@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val popularity: Double = 0.0,
    val runtime: Int? = null,
    val genres: List<TmdbGenre>? = null,
    val tagline: String? = null,
    val status: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    val similar: TmdbResponse<TmdbMovie>? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
)

@Serializable
data class TmdbTv(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val popularity: Double = 0.0,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    val genres: List<TmdbGenre>? = null,
    val tagline: String? = null,
    val status: String? = null,
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    val seasons: List<TmdbSeason>? = null,
    val similar: TmdbResponse<TmdbTv>? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
)

@Serializable
data class TmdbMultiResult(
    val id: Int,
    @SerialName("media_type") val mediaType: String = "",
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val popularity: Double = 0.0,
)

@Serializable
data class TmdbGenre(
    val id: Int,
    val name: String,
)

@Serializable
data class TmdbCast(
    val id: Int,
    val name: String = "",
    val character: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 0,
)

@Serializable
data class TmdbVideo(
    val key: String,
    val site: String = "",
    val type: String = "",
    val name: String = "",
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCast> = emptyList(),
)

@Serializable
data class TmdbVideos(
    val results: List<TmdbVideo> = emptyList(),
)

@Serializable
data class TmdbSeason(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_count") val episodeCount: Int = 0,
    val name: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    val overview: String = "",
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
)
