package com.torve.domain.discovery

import com.torve.domain.model.MediaItem
import com.torve.domain.model.RatingSource
import com.torve.domain.model.withFallbackTmdbScore

enum class DiscoveryRatingSource(
    val label: String,
    val shortLabel: String,
) {
    AnyRating("Any rating", "Any rating"),
    TMDB("TMDB", "TMDB"),
    IMDB("IMDb", "IMDb"),
    TRAKT("Trakt", "Trakt"),
    ROTTEN_TOMATOES("Rotten Tomatoes", "RT"),
    METACRITIC("Metacritic", "MC"),
    LETTERBOXD("Letterboxd", "Letterboxd"),
    MDBLIST("MDBList", "MDBList"),
}

fun DiscoveryRatingSource.strictSource(): RatingSource? = when (this) {
    DiscoveryRatingSource.AnyRating -> null
    DiscoveryRatingSource.TMDB -> RatingSource.TMDB
    DiscoveryRatingSource.IMDB -> RatingSource.IMDB
    DiscoveryRatingSource.TRAKT -> RatingSource.TRAKT
    DiscoveryRatingSource.ROTTEN_TOMATOES -> RatingSource.ROTTEN_TOMATOES
    DiscoveryRatingSource.METACRITIC -> RatingSource.METACRITIC
    DiscoveryRatingSource.LETTERBOXD -> RatingSource.LETTERBOXD
    DiscoveryRatingSource.MDBLIST -> RatingSource.MDBLIST
}

fun MediaItem.matchesRatingFilter(
    threshold: Float?,
    source: DiscoveryRatingSource = DiscoveryRatingSource.TMDB,
): Boolean {
    if (threshold == null) return true
    return when (source) {
        DiscoveryRatingSource.AnyRating -> ratingValuesBySource().values.any { it >= threshold }
        else -> ratingValueFor(source)?.let { it >= threshold } ?: false
    }
}

fun MediaItem.ratingValueFor(source: DiscoveryRatingSource): Float? = when (source) {
    DiscoveryRatingSource.AnyRating -> ratingValuesBySource().values.maxOrNull()
    DiscoveryRatingSource.TMDB -> ratings.withFallbackTmdbScore(rating)?.tmdbScore?.normalizeRating()
    DiscoveryRatingSource.IMDB -> ratings?.imdbScore?.normalizeRating()
    DiscoveryRatingSource.TRAKT -> ratings?.traktScore?.normalizeRating()
    DiscoveryRatingSource.ROTTEN_TOMATOES -> ratings?.rottenTomatoesScore?.toFloat()?.normalizePercentRating()
    DiscoveryRatingSource.METACRITIC -> ratings?.metacriticScore?.toFloat()?.normalizePercentRating()
    DiscoveryRatingSource.LETTERBOXD -> ratings?.letterboxdScore?.let { (it * 2f).normalizeRating() }
    DiscoveryRatingSource.MDBLIST -> ratings?.mdblistScore?.normalizeRating()
}

fun MediaItem.ratingValuesBySource(): Map<DiscoveryRatingSource, Float> = buildMap {
    DiscoveryRatingSource.entries
        .filterNot { it == DiscoveryRatingSource.AnyRating }
        .forEach { source ->
            ratingValueFor(source)?.let { put(source, it) }
        }
}

fun ratingThresholdLabel(threshold: Float?): String =
    threshold?.let { "${it.toInt()}+" } ?: "Any"

fun tmdbDiscoverRating(threshold: Float?, source: DiscoveryRatingSource): Float? =
    threshold.takeIf { source == DiscoveryRatingSource.TMDB }

private fun Float.normalizeRating(): Float =
    when {
        this > 100f -> this / 10f
        this > 10f -> this / 10f
        else -> this
    }.coerceIn(0f, 10f)

private fun Float.normalizePercentRating(): Float =
    (this / 10f).coerceIn(0f, 10f)
