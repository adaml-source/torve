package com.torve.presentation.stats

import com.torve.domain.model.MediaType
import com.torve.domain.stats.WatchSessionSource
import com.torve.domain.stats.WatchSessionStatus
import com.torve.domain.stats.WatchStatsFilters
import com.torve.domain.stats.WatchStatsRatingBand

data class WatchStatsFilterState(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val mediaTypes: Set<MediaType> = emptySet(),
    val statuses: Set<WatchSessionStatus> = emptySet(),
    val sources: Set<WatchSessionSource> = emptySet(),
    val genres: Set<String> = emptySet(),
    val years: Set<Int> = emptySet(),
    val decades: Set<Int> = emptySet(),
    val ratingBands: Set<WatchStatsRatingBand> = emptySet(),
    val actors: Set<String> = emptySet(),
    val directors: Set<String> = emptySet(),
    val studios: Set<String> = emptySet(),
    val networks: Set<String> = emptySet(),
    val providers: Set<String> = emptySet(),
    val certifications: Set<String> = emptySet(),
) {
    fun toDomain(): WatchStatsFilters =
        WatchStatsFilters(
            startMs = startMs,
            endMs = endMs,
            mediaTypes = mediaTypes,
            statuses = statuses,
            sources = sources,
            genres = genres,
            years = years,
            decades = decades,
            ratingBands = ratingBands,
            actors = actors,
            directors = directors,
            studios = studios,
            networks = networks,
            providers = providers,
            certifications = certifications,
        )
}
