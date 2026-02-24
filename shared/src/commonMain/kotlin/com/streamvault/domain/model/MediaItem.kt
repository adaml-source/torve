package com.streamvault.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaType {
    MOVIE,
    SERIES;

    companion object {
        fun fromString(s: String): MediaType = when (s.lowercase()) {
            "movie" -> MOVIE
            "tv", "series" -> SERIES
            else -> MOVIE
        }
    }
}

@Serializable
data class MediaItem(
    val id: String,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val type: MediaType,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val rating: Double? = null,
    val voteCount: Int? = null,
    val runtime: Int? = null,
    val genres: List<Genre> = emptyList(),
    val genreIds: List<Int> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val director: String? = null,
    val directorId: Int? = null,
    val releaseDate: String? = null,
    val status: String? = null,
    val trailerKey: String? = null,
    val seasons: List<Season> = emptyList(),
    val tagline: String? = null,
    val popularity: Double? = null,
)

@Serializable
data class Genre(
    val id: Int,
    val name: String,
)

@Serializable
data class CastMember(
    val id: Int,
    val name: String,
    val character: String? = null,
    val profileUrl: String? = null,
)

@Serializable
data class Season(
    val seasonNumber: Int,
    val episodeCount: Int,
    val name: String? = null,
    val posterUrl: String? = null,
    val overview: String? = null,
    val airDate: String? = null,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class Episode(
    val episodeNumber: Int,
    val name: String = "",
    val overview: String = "",
    val stillUrl: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val rating: Double = 0.0,
)

@Serializable
data class PersonSummary(
    val id: Int,
    val name: String,
    val profileUrl: String? = null,
    val knownForDepartment: String? = null,
)

data class PagedResult(
    val items: List<MediaItem>,
    val page: Int,
    val totalPages: Int,
    val totalResults: Int,
)
