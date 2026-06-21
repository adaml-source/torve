package com.torve.data.metadata

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
data class TmdbFindResponse(
    @SerialName("movie_results") val movieResults: List<TmdbMovie> = emptyList(),
    @SerialName("tv_results") val tvResults: List<TmdbTv> = emptyList(),
)

@Serializable
data class TmdbKeyword(
    val id: Int,
    val name: String = "",
)

@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String = "",
    val adult: Boolean? = null,
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
    @SerialName("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    val similar: TmdbResponse<TmdbMovie>? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    val images: TmdbImages? = null,
)

@Serializable
data class TmdbTv(
    val id: Int,
    val name: String = "",
    val adult: Boolean? = null,
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
    val networks: List<TmdbCompany> = emptyList(),
    @SerialName("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    val seasons: List<TmdbSeason>? = null,
    val similar: TmdbResponse<TmdbTv>? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    val images: TmdbImages? = null,
)

@Serializable
data class TmdbMultiResult(
    val id: Int,
    @SerialName("media_type") val mediaType: String = "",
    val adult: Boolean? = null,
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
data class TmdbCompany(
    val id: Int,
    val name: String = "",
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null,
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
    @SerialName("iso_639_1") val language: String? = null,
    val official: Boolean = false,
)

@Serializable
data class TmdbCrew(
    val id: Int,
    val name: String = "",
    val job: String = "",
    val department: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList(),
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

@Serializable
data class TmdbPerson(
    val id: Int,
    val name: String = "",
    val biography: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val birthday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
)

@Serializable
data class TmdbPersonCastCredit(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("media_type") val mediaType: String = "",
    val character: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double = 0.0,
)

@Serializable
data class TmdbPersonCrewCredit(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("media_type") val mediaType: String = "",
    val job: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double = 0.0,
)

@Serializable
data class TmdbPersonSummary(
    val id: Int,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    val popularity: Double = 0.0,
)

@Serializable
data class TmdbPersonCredits(
    val cast: List<TmdbPersonCastCredit> = emptyList(),
    val crew: List<TmdbPersonCrewCredit> = emptyList(),
)

@Serializable
data class TmdbEpisode(
    val id: Int,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String = "",
    val overview: String = "",
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
)

@Serializable
data class TmdbSeasonDetail(
    val id: Int,
    @SerialName("season_number") val seasonNumber: Int,
    val name: String = "",
    val overview: String = "",
    val episodes: List<TmdbEpisode> = emptyList(),
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
data class TmdbWatchProvidersResponse(
    val results: List<TmdbWatchProvider> = emptyList(),
)

@Serializable
data class TmdbWatchProvider(
    @SerialName("provider_id") val providerId: Int,
    @SerialName("provider_name") val providerName: String = "",
    @SerialName("logo_path") val logoPath: String? = null,
)

@Serializable
data class TmdbImageItem(
    @SerialName("file_path") val filePath: String,
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("aspect_ratio") val aspectRatio: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
)

@Serializable
data class TmdbImages(
    val logos: List<TmdbImageItem> = emptyList(),
    val profiles: List<TmdbImageItem> = emptyList(),
    val backdrops: List<TmdbImageItem> = emptyList(),
    val posters: List<TmdbImageItem> = emptyList(),
)

@Serializable
data class TmdbTitleWatchProvidersResponse(
    val id: Int,
    val results: Map<String, TmdbTitleRegionProviders> = emptyMap(),
)

@Serializable
data class TmdbTitleRegionProviders(
    val link: String? = null,
    val flatrate: List<TmdbWatchProvider> = emptyList(),
    val rent: List<TmdbWatchProvider> = emptyList(),
    val buy: List<TmdbWatchProvider> = emptyList(),
    val free: List<TmdbWatchProvider> = emptyList(),
    val ads: List<TmdbWatchProvider> = emptyList(),
)
