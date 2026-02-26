package com.streamvault.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RatingSource(
    val displayName: String,
    val iconChar: String,
    val defaultEnabled: Boolean,
    val defaultOrder: Int,
) {
    TORVE("Torve Score", "T+", false, 0),
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
enum class RatingPillStyle {
    COMPACT,
    MINIMAL,
    DETAILED,
}

@Serializable
enum class RatingPillPosition(val displayName: String) {
    INSIDE("Inside Card"),
    OUTSIDE("Outside Card"),
}

@Serializable
data class RatingDisplayPrefs(
    val showRatingsOnDetailPage: Boolean = true,
    val showTorveScoreOnDetailPage: Boolean = true,
    val showTorveScoreOnCards: Boolean = false,
    val torveWeights: Map<RatingSource, Int> = defaultTorveWeights(),
    val enabledProviders: List<RatingSource> = RatingSource.entries
        .filter { it.defaultEnabled },
    val providerOrder: List<RatingSource> = RatingSource.entries
        .sortedBy { it.defaultOrder },
    val maxRatingsOnCard: Int = 3,
    val allowRatingsOnLandscapeCards: Boolean = false,
    val pillStyle: RatingPillStyle = RatingPillStyle.COMPACT,
    val pillPosition: RatingPillPosition = RatingPillPosition.INSIDE,
)

fun deriveProvidersToRender(
    enabledProviders: List<RatingSource>,
    providerOrder: List<RatingSource>,
    maxRatingsOnCard: Int,
): List<RatingSource> {
    if (maxRatingsOnCard <= 0) return emptyList()
    return providerOrder
        .filter { enabledProviders.contains(it) }
        .take(maxRatingsOnCard)
}

fun RatingPillPosition.isOutside(): Boolean = this == RatingPillPosition.OUTSIDE

fun defaultTorveWeights(): Map<RatingSource, Int> = mapOf(
    RatingSource.IMDB to 35,
    RatingSource.TMDB to 25,
    RatingSource.ROTTEN_TOMATOES to 20,
    RatingSource.METACRITIC to 20,
)

fun MediaRatings.valueForTorve(source: RatingSource): Float? = when (source) {
    RatingSource.TORVE -> null
    RatingSource.IMDB -> imdbScore?.times(10f)
    RatingSource.ROTTEN_TOMATOES -> rottenTomatoesScore?.toFloat()
    RatingSource.RT_AUDIENCE -> rtAudienceScore?.toFloat()
    RatingSource.TMDB -> tmdbScore?.times(10f)
    RatingSource.METACRITIC -> metacriticScore?.toFloat()
    RatingSource.LETTERBOXD -> letterboxdScore?.times(20f)
    RatingSource.TRAKT -> traktScore
    RatingSource.MDBLIST -> mdblistScore
    RatingSource.MAL -> malScore?.times(10f)
}

fun calculateTorveScore(
    ratings: MediaRatings,
    weights: Map<RatingSource, Int>,
): Float? {
    val weighted = weights
        .asSequence()
        .filter { (source, weight) -> source != RatingSource.TORVE && weight > 0 }
        .mapNotNull { (source, weight) ->
            ratings.valueForTorve(source)?.let { value ->
                val normalized = value.coerceIn(0f, 100f)
                normalized to weight.toFloat()
            }
        }
        .toList()

    if (weighted.isEmpty()) return null

    val weightSum = weighted.sumOf { it.second.toDouble() }.toFloat()
    if (weightSum <= 0f) return null

    val score = weighted.sumOf { (value, weight) ->
        (value * (weight / weightSum)).toDouble()
    }.toFloat()
    return score.coerceIn(0f, 100f)
}
