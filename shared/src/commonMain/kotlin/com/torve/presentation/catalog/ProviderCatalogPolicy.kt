package com.torve.presentation.catalog

import com.torve.domain.discovery.tmdbDiscoverRating
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType

/** Keeps global search hits within the same controls as provider discovery. */
object ProviderCatalogPolicy {
    fun matchesFilters(item: MediaItem, mediaType: String, genreId: Int?, filter: CatalogFilter): Boolean {
        val expectedType = if (mediaType == "tv") MediaType.SERIES else MediaType.MOVIE
        if (item.type != expectedType) return false
        if (genreId != null && genreId !in item.genreIds && item.genres.none { it.id == genreId }) return false
        if (filter.year != null && (item.year ?: Int.MIN_VALUE) < filter.year) return false
        if (filter.yearTo != null && (item.year ?: Int.MAX_VALUE) > filter.yearTo) return false
        val minimum = tmdbDiscoverRating(filter.minRating, filter.ratingSource)
        if (minimum != null && (item.rating ?: -1.0) < minimum) return false
        val runtime = filter.runtimeFilter
        if (runtime?.minMinutes != null && (item.runtime ?: -1) < runtime.minMinutes) return false
        if (runtime?.maxMinutes != null && (item.runtime ?: Int.MAX_VALUE) > runtime.maxMinutes) return false
        return true
    }
}
