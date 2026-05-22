package com.torve.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.round

@Serializable
enum class RatingSource(
    val displayName: String,
    val iconChar: String,
    val defaultEnabled: Boolean,
    val defaultOrder: Int,
) {
    TORVE("Torve Score", "T+", false, 0),
    IMDB("IMDb", "I", true, 1),
    ROTTEN_TOMATOES("Rotten Tomatoes", "R", true, 2),
    RT_AUDIENCE("RT Audience", "A", false, 3),
    TMDB("TMDB", "T", true, 0),
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
enum class RatingConfidence {
    NORMAL,
}

@Serializable
data class DisplayRating(
    val value: Double,
    val source: RatingSource,
    val confidence: RatingConfidence = RatingConfidence.NORMAL,
)

fun MediaItem.bestDisplayRating(): DisplayRating? =
    ratings.bestDisplayRating(tmdbFallback = rating)

fun MediaRatings?.bestDisplayRating(tmdbFallback: Double? = null): DisplayRating? {
    val ratings = this
    val candidates = listOf(
        RatingSource.IMDB to ratings?.imdbScore?.toDouble(),
        RatingSource.MDBLIST to ratings?.mdblistScore?.toDouble(),
        RatingSource.TRAKT to ratings?.traktScore?.toDouble(),
        RatingSource.TMDB to (ratings?.tmdbScore?.toDouble() ?: tmdbFallback),
    )
    return candidates.firstNotNullOfOrNull { (source, rawValue) ->
        rawValue.normalizedDisplayRating()?.let { normalized ->
            DisplayRating(value = normalized, source = source)
        }
    }
}

private fun Double?.normalizedDisplayRating(): Double? {
    val raw = this ?: return null
    if (raw.isNaN() || raw.isInfinite() || raw <= 0.0 || raw > 100.0) return null
    val normalized = if (raw > 10.0) raw / 10.0 else raw
    if (normalized <= 0.0 || normalized > 10.0) return null
    return round(normalized * 10.0) / 10.0
}

fun MediaRatings?.withFallbackTmdbScore(tmdbRating: Double?): MediaRatings? {
    val fallbackScore = tmdbRating?.takeIf { it > 0 }?.toFloat()
    return this?.let { ratings ->
        if (ratings.tmdbScore == null && fallbackScore != null) {
            ratings.copy(tmdbScore = fallbackScore)
        } else {
            ratings
        }
    } ?: fallbackScore?.let { MediaRatings(tmdbScore = it) }
}

fun MediaRatings?.hasRichExternalRating(): Boolean {
    val ratings = this ?: return false
    return ratings.imdbScore.isValidRatingScore() ||
        ratings.rottenTomatoesScore.isValidPercentScore() ||
        ratings.rtAudienceScore.isValidPercentScore() ||
        ratings.metacriticScore.isValidPercentScore() ||
        ratings.letterboxdScore.isValidRatingScore() ||
        ratings.traktScore.isValidRatingScore() ||
        ratings.mdblistScore.isValidRatingScore() ||
        ratings.malScore.isValidRatingScore()
}

fun MediaItem.hasExternalRatingLookupIdentity(): Boolean =
    tmdbId != null ||
        !imdbId.isNullOrBlank() ||
        id.extractTmdbIdOrNull() != null ||
        id.extractImdbIdOrNull() != null

fun MediaItem.needsExternalRatingEnrichment(): Boolean =
    hasExternalRatingLookupIdentity() && !ratings.hasRichExternalRating()

fun MediaItem.ratingEnrichmentLookupKeys(): List<String> = buildList {
    add("id:${type.name}:$id")
    add(id)
    tmdbId?.let { tmdb ->
        add("tmdb:${type.name}:$tmdb")
        add(tmdb.toString())
    }
    imdbId?.takeIf { it.isNotBlank() }?.lowercase()?.let { imdb ->
        add("imdb:${type.name}:$imdb")
        add(imdb)
    }
    val normalizedTitle = title.normalizedRatingLookupTitle()
    if (normalizedTitle.isNotBlank() && year != null) {
        add("title:${type.name}:$normalizedTitle:$year")
    }
}.distinct()

fun MediaItem.withEnrichedRatingsFrom(
    ratingsByKey: Map<String, MediaRatings>,
): MediaItem {
    if (ratingsByKey.isEmpty()) return this
    val enriched = ratingEnrichmentLookupKeys()
        .firstNotNullOfOrNull { key -> ratingsByKey[key] }
        ?: return this
    val merged = mergeRatingsPreservingExisting(ratings, enriched)
    return if (merged == ratings) this else copy(ratings = merged)
}

fun List<MediaItem>.withEnrichedRatingsFrom(
    ratingsByKey: Map<String, MediaRatings>,
): List<MediaItem> =
    if (ratingsByKey.isEmpty()) this else map { item -> item.withEnrichedRatingsFrom(ratingsByKey) }

private fun Float?.isValidRatingScore(): Boolean {
    val score = this ?: return false
    return score > 0f && score <= 100f && !score.isNaN()
}

private fun Int?.isValidPercentScore(): Boolean {
    val score = this ?: return false
    return score in 1..100
}

private fun mergeRatingsPreservingExisting(
    existing: MediaRatings?,
    enriched: MediaRatings,
): MediaRatings {
    if (existing == null) return enriched
    return MediaRatings(
        imdbScore = existing.imdbScore ?: enriched.imdbScore,
        imdbVotes = existing.imdbVotes ?: enriched.imdbVotes,
        rottenTomatoesScore = existing.rottenTomatoesScore ?: enriched.rottenTomatoesScore,
        rtAudienceScore = existing.rtAudienceScore ?: enriched.rtAudienceScore,
        tmdbScore = existing.tmdbScore ?: enriched.tmdbScore,
        metacriticScore = existing.metacriticScore ?: enriched.metacriticScore,
        letterboxdScore = existing.letterboxdScore ?: enriched.letterboxdScore,
        traktScore = existing.traktScore ?: enriched.traktScore,
        mdblistScore = existing.mdblistScore ?: enriched.mdblistScore,
        malScore = existing.malScore ?: enriched.malScore,
    )
}

private fun String.normalizedRatingLookupTitle(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
        .trim()

@Serializable
enum class RatingPillStyle {
    ICON,
    LETTER,
    ;
    companion object {
        /** Migration: map old values to new ones. */
        fun fromLegacy(name: String): RatingPillStyle = when (name) {
            "COMPACT", "DETAILED", "MINIMAL" -> ICON
            else -> valueOf(name)
        }
    }
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
    val pillStyle: RatingPillStyle = RatingPillStyle.ICON,
    val pillPosition: RatingPillPosition = RatingPillPosition.INSIDE,
)

fun deriveProvidersToRender(
    enabledProviders: List<RatingSource>,
    providerOrder: List<RatingSource>,
    maxRatingsOnCard: Int,
    fallbackToTmdbWhenNoneSelected: Boolean = true,
): List<RatingSource> {
    if (maxRatingsOnCard <= 0) return emptyList()
    val providers = if (enabledProviders.isEmpty() && fallbackToTmdbWhenNoneSelected) {
        listOf(RatingSource.TMDB)
    } else {
        enabledProviders
    }
    return (providerOrder + RatingSource.entries).distinct()
        .filter { providers.contains(it) }
        .take(maxRatingsOnCard)
}

fun RatingPillPosition.isOutside(): Boolean = this == RatingPillPosition.OUTSIDE

fun RatingDisplayPrefs.allowsTmdbRatingProvider(): Boolean =
    enabledProviders.isEmpty() || RatingSource.TMDB in enabledProviders

fun defaultTorveWeights(): Map<RatingSource, Int> = mapOf(
    RatingSource.IMDB to 35,
    RatingSource.TMDB to 25,
    RatingSource.ROTTEN_TOMATOES to 20,
    RatingSource.METACRITIC to 20,
)

fun MediaRatings.hasValueFor(source: RatingSource): Boolean = when (source) {
    RatingSource.TORVE -> false
    RatingSource.IMDB -> imdbScore != null
    RatingSource.ROTTEN_TOMATOES -> rottenTomatoesScore != null
    RatingSource.RT_AUDIENCE -> rtAudienceScore != null
    RatingSource.TMDB -> tmdbScore != null
    RatingSource.METACRITIC -> metacriticScore != null
    RatingSource.LETTERBOXD -> letterboxdScore != null
    RatingSource.TRAKT -> traktScore != null
    RatingSource.MDBLIST -> mdblistScore != null
    RatingSource.MAL -> malScore != null
}

fun MediaRatings.hasAnyEnabledDisplayValue(
    prefs: RatingDisplayPrefs,
    includeTorve: Boolean = false,
): Boolean {
    val providers = if (prefs.enabledProviders.isEmpty()) {
        listOf(RatingSource.TMDB)
    } else {
        prefs.enabledProviders
    }
    return providers.any { source ->
        when {
            source == RatingSource.TORVE -> includeTorve && calculateTorveScore(this, prefs.torveWeights) != null
            else -> hasValueFor(source)
        }
    }
}

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
