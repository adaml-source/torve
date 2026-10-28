package com.torve.presentation.home

import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings

/** A delayed ratings response may enrich current cards, never restore an old catalogue. */
internal class HomeRatingsUpdate(before: List<MediaItem>, enriched: List<MediaItem>) {
    private val originals = before.associateBy { it.type to it.id }
    private val updates = enriched.associateBy { it.type to it.id }

    fun apply(item: MediaItem): MediaItem {
        val key = item.type to item.id
        val original = originals[key] ?: return item
        val update = updates[key] ?: return item
        // A provider may reuse its own ID after a configuration change.
        if (item.tmdbId != null && original.tmdbId != null && item.tmdbId != original.tmdbId) return item
        if (item.imdbId != null && original.imdbId != null && item.imdbId != original.imdbId) return item
        val merged = item.copy(
            tmdbId = item.tmdbId.updatedIfUnchanged(original.tmdbId, update.tmdbId),
            imdbId = item.imdbId.updatedIfUnchanged(original.imdbId, update.imdbId),
            ratings = mergeRatings(item.ratings, original.ratings, update.ratings),
        )
        return if (merged == item) item else merged
    }

    fun apply(items: List<MediaItem>): List<MediaItem> {
        val merged = items.map(::apply)
        return if (merged == items) items else merged
    }

    fun apply(shelf: CatalogShelf): CatalogShelf {
        val items = apply(shelf.items)
        return if (items === shelf.items) shelf else shelf.copy(items = items)
    }
}

private fun <T> T?.updatedIfUnchanged(before: T?, after: T?): T? =
    if (this == before && after != null) after else this

private fun mergeRatings(current: MediaRatings?, before: MediaRatings?, after: MediaRatings?): MediaRatings? {
    if (after == null) return current
    if (current == before) return after
    // A newer load deliberately removed ratings; do not bring them back.
    if (current == null) return null
    return MediaRatings(
        imdbScore = current.imdbScore.updatedIfUnchanged(before?.imdbScore, after.imdbScore),
        imdbVotes = current.imdbVotes.updatedIfUnchanged(before?.imdbVotes, after.imdbVotes),
        rottenTomatoesScore = current.rottenTomatoesScore.updatedIfUnchanged(before?.rottenTomatoesScore, after.rottenTomatoesScore),
        rtAudienceScore = current.rtAudienceScore.updatedIfUnchanged(before?.rtAudienceScore, after.rtAudienceScore),
        tmdbScore = current.tmdbScore.updatedIfUnchanged(before?.tmdbScore, after.tmdbScore),
        metacriticScore = current.metacriticScore.updatedIfUnchanged(before?.metacriticScore, after.metacriticScore),
        letterboxdScore = current.letterboxdScore.updatedIfUnchanged(before?.letterboxdScore, after.letterboxdScore),
        traktScore = current.traktScore.updatedIfUnchanged(before?.traktScore, after.traktScore),
        mdblistScore = current.mdblistScore.updatedIfUnchanged(before?.mdblistScore, after.mdblistScore),
        malScore = current.malScore.updatedIfUnchanged(before?.malScore, after.malScore),
    )
}
