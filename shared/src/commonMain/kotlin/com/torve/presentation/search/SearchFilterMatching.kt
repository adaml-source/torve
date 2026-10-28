package com.torve.presentation.search

import com.torve.domain.model.*
import com.torve.presentation.catalog.SortOption
import com.torve.presentation.catalog.highestImdbFirst

/** Unknown metadata never satisfies a requested bound. Certifications are independent of review scores. */
fun SearchFilter.matches(item: MediaItem, aiSearch: Boolean = false): Boolean {
    if (mediaType == "movie" && item.type != MediaType.MOVIE || mediaType == "tv" && item.type != MediaType.SERIES) return false
    val genres = if (aiSearch || mediaType == null) item.matchesAiGenres(genreIds) else genreIds.isEmpty() || genreIds.any { it in item.genreIds }
    return genres && (minRating == null || (item.rating ?: -1.0) >= minRating) &&
        (minImdbScore == null || (item.ratings?.imdbScore ?: -1f) >= minImdbScore) &&
        (minTmdbScore == null || (item.ratings?.tmdbScore?.takeIf { it > 0 } ?: item.rating?.toFloat() ?: -1f) >= minTmdbScore) &&
        (minTorveScore == null || (item.ratings?.let { calculateTorveScore(it, defaultTorveWeights()) } ?: -1f) >= minTorveScore) &&
        (yearFrom == null || (item.year ?: Int.MIN_VALUE) >= yearFrom) &&
        (yearTo == null || (item.year ?: Int.MAX_VALUE) <= yearTo) &&
        (runtimeFilter == null || item.runtime?.let { minutes ->
            when (runtimeFilter) {
                com.torve.presentation.catalog.RuntimeFilter.SHORT -> minutes < 90
                com.torve.presentation.catalog.RuntimeFilter.LONG -> minutes > 150
                else -> minutes in 90..150
            } } == true) &&
        (certification == null || item.ageCertifications.any { it.country.equals(certificationCountry, true) && it.rating.equals(certification, true) })
}
fun SearchFilter.order(items: List<MediaItem>): List<MediaItem> = when (sortBy) {
    SortOption.IMDB_SCORE_DESC -> items.highestImdbFirst()
    SortOption.TORVE_SCORE_DESC -> items.sortedByDescending { it.ratings?.let { ratings -> calculateTorveScore(ratings, defaultTorveWeights()) } ?: -1f }
    SortOption.VOTE_AVERAGE_DESC -> items.sortedByDescending { it.rating ?: -1.0 }
    SortOption.RELEASE_DATE_DESC -> items.sortedByDescending { it.releaseDate ?: it.year?.toString() ?: "" }
    SortOption.RELEASE_DATE_ASC -> items.sortedBy { it.releaseDate ?: it.year?.toString() ?: "9999" }
    SortOption.VOTE_COUNT_DESC -> items.sortedByDescending { it.voteCount ?: 0 }
    SortOption.REVENUE_DESC -> items // Revenue is ordered by discover; MediaItem has no revenue field.
    else -> items.sortedByDescending { it.popularity ?: 0.0 }
}
