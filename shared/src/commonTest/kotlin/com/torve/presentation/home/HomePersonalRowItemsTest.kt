package com.torve.presentation.home

import com.torve.domain.model.*
import kotlin.test.*

class HomePersonalRowItemsTest {
    @Test fun savedWatchlistKeepsBothIdentitiesForMetadataProviders() {
        val saved = WatchlistItem("106379", MediaType.SERIES, 106379, imdbId = "tt12637874", title = "Fallout", year = 2024, addedAt = 0)
        val card = saved.toHomeWatchlistItem()
        assertEquals(saved.mediaId, card.id); assertEquals(saved.tmdbId, card.tmdbId)
        assertEquals(saved.imdbId, card.imdbId); assertEquals(saved.year, card.year)
    }
}
