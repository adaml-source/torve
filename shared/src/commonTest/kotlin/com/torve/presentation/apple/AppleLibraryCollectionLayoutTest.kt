package com.torve.presentation.apple

import com.torve.domain.model.WatchHistoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class AppleLibraryCollectionLayoutTest {
    private fun entry(id: String, media: String, type: String = "movie", time: Long = 1) =
        WatchHistoryEntry(id, media, type, "Same title", null, null, time, 0, null, null, null)

    @Test fun newestEventWinsWithoutMergingDifferentTitlesOrTypes() {
        val items = listOf(entry("old", "123"), entry("new", "tmdb:movie:123", time = 5),
            entry("show", "123", "tv", 3), entry("other", "456", time = 2))
        assertEquals(listOf("new", "show", "other"), AppleLibraryCollectionLayout.history(items).map { it.id })
        assertEquals(4, items.size)
    }
    @Test fun seriesAliasesGroupAtShowLevelAndMissingIdsStaySeparate() {
        val items = listOf(entry("episode1", "42", "series"), entry("episode2", "42", "tv", 2),
            entry("unknown1", ""), entry("unknown2", ""))
        assertEquals(listOf("episode2", "unknown1", "unknown2"), AppleLibraryCollectionLayout.history(items).map { it.id })
    }
    @Test fun newestShowEpisodeWinsAndMissingArtworkFallsBackWithoutChangingItsDate() {
        val old = entry("old", "42", "series", 1).copy(posterUrl = "poster", backdropUrl = "backdrop", seasonNumber = 1, episodeNumber = 1)
        val newest = entry("latest", "42", "SERIES", 7).copy(seasonNumber = 1, episodeNumber = 2)
        val display = AppleLibraryCollectionLayout.history(listOf(old, newest)).single()
        assertEquals("latest", display.id)
        assertEquals(7, display.watchedAt)
        assertEquals(2, display.episodeNumber)
        assertEquals("poster", display.posterUrl)
        assertEquals("backdrop", display.backdropUrl)
        assertEquals(null, newest.posterUrl, "Grouping must not mutate stored events")
    }
}
