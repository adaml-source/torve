package com.torve.presentation.tvhome

import com.torve.domain.model.MediaItem
import com.torve.domain.model.dedupeByStableKey
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.presentation.home.HomeUiState

/** Personal candidates keep their order and identity, but borrow known catalogue artwork. */
internal fun tvHomeOutcomeCandidates(home: HomeUiState): List<MediaItem> {
    val candidates = buildList {
        home.continueWatching.forEach { progress ->
            val tmdbId = progress.mediaId.extractTmdbIdOrNull() ?: return@forEach
            add(MediaItem(
                id = progress.mediaId,
                tmdbId = tmdbId,
                type = progress.mediaType,
                title = progress.showTitle ?: progress.title,
                posterUrl = progress.posterUrl,
                backdropUrl = progress.backdropUrl,
            ))
        }
        addAll(home.watchlistItems)
        addAll(home.recommendedItems.map { it.item })
        addAll(home.recentlyWatched)
    }.dedupeByStableKey()

    val knownItems = buildList {
        home.heroItem?.let(::add)
        addAll(home.shelves.flatMap { it.items })
        home.watchlistShelf?.let { addAll(it.items) }
        addAll(home.addonShelves.flatMap { it.items })
        addAll(home.customShelves.values.flatten())
        addAll(home.mdbListShelves.flatMap { it.items })
    }.dedupeByStableKey().associateBy { it.type to it.tmdbId }

    return candidates.map { candidate ->
        val known = candidate.tmdbId?.let { knownItems[candidate.type to it] }
        if (known == null) candidate else listOf(candidate, known).dedupeByStableKey().first()
    }
}
