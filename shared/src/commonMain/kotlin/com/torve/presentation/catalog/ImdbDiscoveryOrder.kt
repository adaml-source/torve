package com.torve.presentation.catalog

import com.torve.domain.discovery.matchesRatingFilter
import com.torve.domain.model.MediaItem

/** Rank genuine IMDb values; an unavailable score never borrows TMDB/Trakt. */
fun List<MediaItem>.highestImdbFirst(): List<MediaItem> =
    sortedWith(compareByDescending<MediaItem> { it.ratings?.imdbScore?.takeIf { score -> score.isFinite() && score in 0f..10f } ?: -1f }
        .thenByDescending { it.ratings?.imdbVotes ?: 0 })

fun CatalogFilter.applyRatingOrder(items: List<MediaItem>): List<MediaItem> {
    val matching = items.filter { it.matchesRatingFilter(minRating, ratingSource) }
    return if (sortBy == SortOption.IMDB_SCORE_DESC) matching.highestImdbFirst() else matching
}
