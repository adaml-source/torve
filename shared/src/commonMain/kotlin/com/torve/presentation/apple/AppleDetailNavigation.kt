package com.torve.presentation.apple

import com.torve.domain.model.MediaItem
import com.torve.domain.model.extractTmdbIdOrNull

/** List identity (especially a calendar episode) is not its detail identity. */
object AppleDetailNavigation {
    fun mediaId(item: MediaItem): String =
        (item.tmdbId?.takeIf { it > 0 } ?: catalogId(item.id))?.toString() ?: item.id

    fun catalogId(mediaId: String): Int? {
        val raw = mediaId.trim()
        // Never interpret an episode number or the timezone at the end of a
        // calendar key as a show ID. Older snapshots can lack the tmdbId field.
        if (raw.startsWith("trakt-calendar:")) {
            return raw.removePrefix("trakt-calendar:").substringBefore(':')
                .toIntOrNull()?.takeIf { it > 0 }
        }
        return raw.extractTmdbIdOrNull()?.takeIf { it > 0 }
    }
}
