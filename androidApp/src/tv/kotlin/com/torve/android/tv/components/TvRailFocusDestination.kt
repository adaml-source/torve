package com.torve.android.tv.components

import com.torve.domain.model.MediaItem

internal fun MediaItem.tvRailItemKey(): String =
    if (id.startsWith("trakt-calendar:")) "${type}_$id"
    else tmdbId?.let { "tmdb_${type}_$it" } ?: "${type}_$id"

/** The key of an unchanged title survives insertion/removal of its neighbours. */
internal fun tvRailLazyItemKeys(items: List<MediaItem>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return items.map { item ->
        val key = item.tvRailItemKey()
        val occurrence = occurrences[key] ?: 0
        occurrences[key] = occurrence + 1
        "$key:$occurrence"
    }
}

internal data class TvRailFocusDestination(val rowKey: String, val rowIndex: Int, val itemIndex: Int)

internal fun tvRailFocusDestination(
    rails: List<TvContentRail>,
    rowKey: String?,
    itemKey: String?,
    itemIndex: Int = 0,
    rowIndex: Int = 0,
    searchAcrossRows: Boolean = false,
): TvRailFocusDestination? {
    val available = rails.indices.filter { rails[it].items.isNotEmpty() }
    val index = available.firstOrNull { rails[it].key == rowKey }
        ?: available.minByOrNull { kotlin.math.abs(it - rowIndex) }
        ?: return null
    val row = rails[index]
    val exact = row.items.indexOfFirst { it.tvRailItemKey() == itemKey }
    if (exact < 0 && searchAcrossRows && itemKey != null) {
        for (candidate in available.sortedBy { kotlin.math.abs(it - index) }) {
            val item = rails[candidate].items.indexOfFirst { it.tvRailItemKey() == itemKey }
            if (item >= 0) return TvRailFocusDestination(rails[candidate].key, candidate, item)
        }
    }
    return TvRailFocusDestination(row.key, index, if (exact >= 0) exact else itemIndex.coerceIn(row.items.indices))
}
