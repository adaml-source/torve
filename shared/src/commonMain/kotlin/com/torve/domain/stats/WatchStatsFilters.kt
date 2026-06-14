package com.torve.domain.stats

import com.torve.domain.model.MediaType

data class WatchStatsFilters(
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
    fun matches(session: WatchSession): Boolean {
        return matches(session, metadata = null)
    }

    fun matches(session: WatchSession, metadata: WatchStatsMetadata?): Boolean {
        if (startMs != null && session.startedAt < startMs) return false
        if (endMs != null && session.startedAt > endMs) return false
        if (mediaTypes.isNotEmpty() && session.mediaType !in mediaTypes) return false
        if (statuses.isNotEmpty() && session.status !in statuses) return false
        if (sources.isNotEmpty() && session.source !in sources) return false
        if (!matchesAdvanced(metadata)) return false
        return true
    }

    val hasAdvancedFilters: Boolean
        get() = genres.isNotEmpty() ||
            years.isNotEmpty() ||
            decades.isNotEmpty() ||
            ratingBands.isNotEmpty() ||
            actors.isNotEmpty() ||
            directors.isNotEmpty() ||
            studios.isNotEmpty() ||
            networks.isNotEmpty() ||
            providers.isNotEmpty() ||
            certifications.isNotEmpty()

    private fun matchesAdvanced(metadata: WatchStatsMetadata?): Boolean {
        if (!hasAdvancedFilters) return true
        val data = metadata ?: return false
        if (genres.isNotEmpty() && data.genres.normalizedValues().none { it in genres.normalizedSet() }) return false
        if (years.isNotEmpty() && data.year !in years) return false
        if (decades.isNotEmpty() && data.year?.toDecade() !in decades) return false
        if (ratingBands.isNotEmpty() && data.ratingBand() !in ratingBands) return false
        if (actors.isNotEmpty() && data.actors.normalizedValues().none { it in actors.normalizedSet() }) return false
        if (directors.isNotEmpty() && data.directors.normalizedValues().none { it in directors.normalizedSet() }) return false
        if (studios.isNotEmpty() && data.studios.normalizedValues().none { it in studios.normalizedSet() }) return false
        if (networks.isNotEmpty() && data.networks.normalizedValues().none { it in networks.normalizedSet() }) return false
        if (providers.isNotEmpty() && data.providers.normalizedValues().none { it in providers.normalizedSet() }) return false
        if (certifications.isNotEmpty() && data.certification.normalizedValue() !in certifications.normalizedSet()) return false
        return true
    }
}

internal fun WatchStatsMetadata.ratingBand(): WatchStatsRatingBand? {
    val score = ratingImdb?.takeIf { it > 0.0 }?.coerceAtMost(10.0)
        ?: ratingTmdb?.takeIf { it > 0.0 }?.coerceAtMost(10.0)
        ?: ratingRt?.takeIf { it > 0.0 }?.let { (it / 10.0).coerceAtMost(10.0) }
    return when {
        score == null -> null
        score >= 8.0 -> WatchStatsRatingBand.EXCELLENT
        score >= 7.0 -> WatchStatsRatingBand.GOOD
        score >= 6.0 -> WatchStatsRatingBand.OK
        else -> WatchStatsRatingBand.LOW
    }
}

internal fun Int.toDecade(): Int = (this / 10) * 10

internal fun String?.normalizedValue(): String =
    this.orEmpty().trim().lowercase()

internal fun Collection<String>.normalizedValues(): Set<String> =
    mapNotNull { value ->
        value.trim().takeIf { it.isNotBlank() }?.lowercase()
    }.toSet()

private fun Set<String>.normalizedSet(): Set<String> = normalizedValues()
