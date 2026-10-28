package com.torve.android.tv.components

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvRailFocusDestinationTest {
    @Test fun episodeIdentityRestoresTheRightAiringWhenTwoEpisodesBelongToOneShow() {
        val first = item(42).copy(type = MediaType.SERIES, id = "trakt-calendar:42:1:1:date")
        val second = first.copy(id = "trakt-calendar:42:1:2:date")
        val rows = listOf(TvContentRail("calendar", "Calendar", listOf(first, second)))
        assertEquals(TvRailFocusDestination("calendar", 0, 1), tvRailFocusDestination(rows, "calendar", second.tvRailItemKey()))
    }

    private fun item(id: Int) = MediaItem(id.toString(), tmdbId = id, type = MediaType.MOVIE, title = "Title $id")
    private fun row(key: String, vararg ids: Int) = TvContentRail(key, key, ids.map(::item))

    @Test fun insertingAvailabilityRowKeepsTheSameResumeTitleAtItsNewPosition() {
        val rails = listOf(row("available", 9), row("continue", 3, 1, 2))
        assertEquals(TvRailFocusDestination("continue", 1, 2), tvRailFocusDestination(rails, "continue", item(2).tvRailItemKey(), 1, 0))
    }

    @Test fun removedTitleFallsBackToNearestRemainingCardInItsRow() {
        assertEquals(TvRailFocusDestination("continue", 0, 1), tvRailFocusDestination(listOf(row("continue", 1, 3)), "continue", item(2).tvRailItemKey(), 1))
    }

    @Test fun removedRowFallsBackToNearestNonemptyRow() {
        assertEquals(TvRailFocusDestination("watchlist", 1, 0), tvRailFocusDestination(listOf(row("available", 9), row("watchlist", 4)), "continue", null, 7, 1))
    }

    @Test fun emptyRowsCannotBecomeAnEntryTarget() {
        assertEquals(TvRailFocusDestination("watchlist", 1, 0), tvRailFocusDestination(listOf(row("empty"), row("watchlist", 4)), "empty", null))
        assertNull(tvRailFocusDestination(listOf(row("empty")), "empty", null))
    }

    @Test fun lazyKeysSurviveInsertionAndRemainUniqueForDuplicates() {
        val original = tvRailLazyItemKeys(listOf(item(1), item(2)))
        val inserted = tvRailLazyItemKeys(listOf(item(9), item(1), item(2), item(2)))
        assertEquals(original, inserted.subList(1, 3))
        assertEquals(inserted.size, inserted.toSet().size)
    }

    @Test fun lazyKeysDistinguishMovieAndSeriesWithTheSameTmdbNumber() {
        val keys = tvRailLazyItemKeys(listOf(item(1), item(1).copy(type = MediaType.SERIES)))
        assertEquals(2, keys.toSet().size)
    }
}
