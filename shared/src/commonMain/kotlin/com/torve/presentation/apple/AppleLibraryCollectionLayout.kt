package com.torve.presentation.apple

import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchHistoryEntry

/** Presentation grouping only: stored viewing events remain intact. */
object AppleLibraryCollectionLayout {
    fun history(entries: List<WatchHistoryEntry>): List<WatchHistoryEntry> = entries
        .sortedByDescending { it.watchedAt }
        .groupBy {
            val id = AppleDetailNavigation.catalogId(it.mediaId)?.toString()
                ?: it.mediaId.trim().ifBlank { "event:${it.id}" }
            "${MediaType.fromString(it.mediaType)}:$id"
        }.values.map { events ->
            val newest = events.first()
            newest.copy(
                posterUrl = newest.posterUrl?.takeIf { it.isNotBlank() } ?: events.firstNotNullOfOrNull { it.posterUrl?.takeIf(String::isNotBlank) },
                backdropUrl = newest.backdropUrl?.takeIf { it.isNotBlank() } ?: events.firstNotNullOfOrNull { it.backdropUrl?.takeIf(String::isNotBlank) },
            )
        }
}
