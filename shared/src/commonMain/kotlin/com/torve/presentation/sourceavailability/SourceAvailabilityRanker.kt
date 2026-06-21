package com.torve.presentation.sourceavailability

import com.torve.domain.sourceavailability.SourceAvailabilityRecord

/**
 * Pure deterministic re-ranker. Given a list of items in the order TMDB
 * returned them and a map of availability records, emits a stable
 * ordering where:
 *
 *   1. Items with at least one source availability signal come first,
 *      sorted descending by [SourceAvailabilityRecord.score].
 *   2. Items with no availability signal preserve their original TMDB
 *      order at the bottom of the list.
 *
 * Ties within group 1 fall back to the original order (stable sort).
 *
 * This is the *only* place ordering changes — the AI prompt itself
 * stays metadata-only; this layer runs entirely after AI returns its
 * structured filters and TMDB returns its results.
 */
object SourceAvailabilityRanker {

    /**
     * @param tmdbIdSelector how to extract the tmdbId from a row.
     * @param records availability lookups keyed by tmdbId.
     */
    fun <T> rerank(
        items: List<T>,
        records: Map<Int, SourceAvailabilityRecord>,
        tmdbIdSelector: (T) -> Int?,
    ): List<RankedItem<T>> {
        val annotated = items.mapIndexed { index, item ->
            val id = tmdbIdSelector(item)
            val record = id?.let { records[it] }
            RankedItem(
                item = item,
                originalIndex = index,
                record = record,
            )
        }
        val available = annotated
            .filter { it.record?.isAvailable == true }
            .sortedWith(
                compareByDescending<RankedItem<T>> { it.record?.score ?: 0 }
                    .thenBy { it.originalIndex },
            )
        val unavailable = annotated.filter { it.record?.isAvailable != true }
        return available + unavailable
    }
}

data class RankedItem<T>(
    val item: T,
    val originalIndex: Int,
    val record: SourceAvailabilityRecord?,
) {
    val isAvailable: Boolean get() = record?.isAvailable == true
    val primaryBadge: String? get() = record?.primaryBadge?.badge
}
