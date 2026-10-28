package com.torve.presentation.apple

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType

/** TV preserves the selected provider identity just as iPhone catalogue detail does. */
object AppleTvCataloguePolicy {
    fun catalogueId(mediaId: String): Int? {
        val id = mediaId.trim()
        val knownCatalogueId = id.toIntOrNull()?.let { it > 0 } == true ||
            id.startsWith("tmdb:") || id.startsWith("trakt-calendar:") ||
            Regex("(?:tv|movie|series)_\\d+").matches(id)
        return if (knownCatalogueId) AppleDetailNavigation.catalogId(id) else null
    }

    fun needsExtensionDetail(item: MediaItem, catalogueEnabled: Boolean): Boolean =
        !catalogueEnabled || catalogueId(item.id) == null

    fun seed(row: AppleTvRow): MediaItem = MediaItem(
        id = row.targetId, type = MediaType.fromString(row.mediaType), title = row.title,
        posterUrl = row.artwork, backdropUrl = row.backdrop, logoUrl = row.logo,
        overview = row.overview, year = row.year,
    )
}
