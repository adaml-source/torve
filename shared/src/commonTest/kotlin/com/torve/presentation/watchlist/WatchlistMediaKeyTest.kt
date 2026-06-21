package com.torve.presentation.watchlist

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchlistMediaKeyTest {
    @Test
    fun watchlistMediaIdNormalizesTmdbPrefixedIds() {
        val item = MediaItem(
            id = "tmdb:movie:123",
            tmdbId = null,
            type = MediaType.MOVIE,
            title = "Example",
        )

        assertEquals("123", item.watchlistMediaId())
    }

    @Test
    fun watchlistMediaIdPrefersTmdbIdWhenPresent() {
        val item = MediaItem(
            id = "catalog:temporary:abc",
            tmdbId = 456,
            type = MediaType.SERIES,
            title = "Example",
        )

        assertEquals("456", item.watchlistMediaId())
    }
}
