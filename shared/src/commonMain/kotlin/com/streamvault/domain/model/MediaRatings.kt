package com.streamvault.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RatingSource(
    val displayName: String,
    val iconChar: String,
    val defaultEnabled: Boolean,
    val defaultOrder: Int,
) {
    IMDB("IMDb", "I", true, 0),
    ROTTEN_TOMATOES("Rotten Tomatoes", "R", true, 1),
    RT_AUDIENCE("RT Audience", "A", false, 2),
    TMDB("TMDB", "T", true, 3),
    METACRITIC("Metacritic", "M", false, 4),
    LETTERBOXD("Letterboxd", "L", false, 5),
    TRAKT("Trakt", "K", false, 6),
    MDBLIST("MDBList", "D", false, 7),
    MAL("MAL", "X", false, 8),
}

@Serializable
data class MediaRatings(
    val imdbScore: Float? = null,
    val imdbVotes: Int? = null,
    val rottenTomatoesScore: Int? = null,
    val rtAudienceScore: Int? = null,
    val tmdbScore: Float? = null,
    val metacriticScore: Int? = null,
    val letterboxdScore: Float? = null,
    val traktScore: Float? = null,
    val mdblistScore: Float? = null,
    val malScore: Float? = null,
)

@Serializable
data class RatingSourceConfig(
    val source: RatingSource,
    val enabled: Boolean,
    val order: Int,
)

@Serializable
enum class RatingPillStyle {
    COMPACT,
    MINIMAL,
    DETAILED,
}

@Serializable
enum class RatingPillPlacement(val displayName: String) {
    INSIDE_TOP_END("Inside Top-Right"),
    INSIDE_TOP_START("Inside Top-Left"),
    INSIDE_BOTTOM_END("Inside Bottom-Right"),
    INSIDE_BOTTOM_START("Inside Bottom-Left"),
    OUTSIDE_TOP("Outside Top"),
    OUTSIDE_BOTTOM("Outside Bottom"),
}

@Serializable
data class RatingDisplayPrefs(
    val showRatingsOnCards: Boolean = true,
    val showRatingsOnDetailPage: Boolean = true,
    val sources: List<RatingSourceConfig> = RatingSource.entries.map {
        RatingSourceConfig(it, it.defaultEnabled, it.defaultOrder)
    },
    val maxPillsOnCard: Int = 3,
    val pillStyle: RatingPillStyle = RatingPillStyle.COMPACT,
    val pillPlacement: RatingPillPlacement = RatingPillPlacement.INSIDE_TOP_END,
)
