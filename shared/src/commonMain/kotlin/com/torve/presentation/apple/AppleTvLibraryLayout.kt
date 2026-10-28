package com.torve.presentation.apple

import com.torve.domain.model.*
import com.torve.presentation.home.HomeUiState

/** Saved records own identity and order; Home supplies its already-enriched metadata. */
object AppleTvLibraryLayout {
    fun watchlist(item: WatchlistItem, home: HomeUiState): MediaItem = rated(
        MediaItem(id = item.mediaId, tmdbId = item.tmdbId.takeIf { it > 0 }, imdbId = item.imdbId,
            type = item.mediaType, title = item.title, posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl, year = item.year, rating = item.rating), home)

    fun progress(item: WatchProgress): AppleTvRow {
        val type = if (item.mediaType == MediaType.SERIES) "tv" else "movie"
        val remaining = (item.durationMs - item.positionMs).coerceAtLeast(0)
        val remainingText = when {
            remaining == 0L -> "Playback stopped"
            remaining < 60_000 -> "Less than a minute remaining"
            else -> "${remaining / 60_000} min remaining"
        }
        return AppleTvRow("$type:${item.mediaId}", item.showTitle ?: item.title, remainingText, item.posterUrl, type,
            item.seasonNumber ?: 0, item.episodeNumber ?: 0, item.progressPercent,
            targetId = AppleTvCataloguePolicy.catalogueId(item.mediaId)?.toString() ?: item.mediaId,
            backdrop = item.backdropUrl,
            status = "continue")
    }

    fun history(entries: List<WatchHistoryEntry>): List<AppleTvRow> = AppleLibraryCollectionLayout.history(entries).take(50).map { entry ->
        val type = if (MediaType.fromString(entry.mediaType) == MediaType.SERIES) "tv" else "movie"
        val target = AppleTvCataloguePolicy.catalogueId(entry.mediaId)?.toString() ?: entry.mediaId
        AppleTvRow("$type:$target", entry.showTitle ?: entry.title, artwork = entry.posterUrl, mediaType = type,
            season = entry.seasonNumber ?: 0, episode = entry.episodeNumber ?: 0, targetId = target,
            backdrop = entry.backdropUrl, watched = true, status = "history", watchedAt = entry.watchedAt)
    }

    fun rated(item: MediaItem, home: HomeUiState): MediaItem {
        val candidates = home.watchlistItems + home.recentlyWatched + home.shelves.flatMap { it.items }
        val metadata = candidates.firstOrNull { candidate ->
            candidate.type == item.type && (
                item.stableTmdbIdString()?.let { it == candidate.stableTmdbIdString() } == true ||
                item.imdbId?.let { it == candidate.imdbId } == true || candidate.id == item.id)
        }
        val merged = metadata?.ratings?.let { ratings ->
            item.withEnrichedRatingsFrom(mapOf("id:${item.type.name}:${item.id}" to ratings))
        } ?: item
        // Typed keys prevent a movie and series with the same numeric ID sharing a score.
        val typed = home.continueWatchingRatings.filterKeys { key ->
            key.startsWith("id:${item.type.name}:") || key.startsWith("tmdb:${item.type.name}:") ||
                key.startsWith("imdb:${item.type.name}:") || key.startsWith("title:${item.type.name}:")
        }
        return merged.withEnrichedRatingsFrom(typed)
    }
}
