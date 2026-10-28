package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CatalogPlaybackHistoryTest {
    @Test
    fun sportsAndFileKeysNeverBecomeCatalogCardsEvenWhenTheyEndInDigits() {
        for (id in listOf("sports:123", "sports:-456", " SPORTS:123 ", "direct:94997", "webdav:27205")) {
            assertFalse(id.isCatalogPlaybackIdentity(), id)
            assertNull(entry(id).copy(posterUrl = "https://example.com/poster.jpg")
                .toCatalogHistoryItemOrNull(), id)
        }
    }

    @Test
    fun urlsAndEmptyIdentitiesAreNotMovieHistory() {
        for (id in listOf("", "  ", "https://example.com/upload/123", "file:///upload/123", "content://media/123")) {
            assertNull(entry(id).toCatalogHistoryItemOrNull(), id)
        }
    }

    @Test
    fun genuineMoviesStayVisibleEvenWithoutArtwork() {
        for (id in listOf("27205", "movie_27205", "tmdb:27205", "imdb:tt1375666", "addon-custom-movie")) {
            val card = assertNotNull(entry(id).copy(title = "Inception").toCatalogHistoryItemOrNull())
            assertEquals(id, card.id)
            assertEquals("Inception", card.title)
            assertEquals(MediaType.MOVIE, card.type)
            assertNull(card.posterUrl)
        }
    }

    @Test
    fun seriesHistoryUsesTheShowTitleAcrossLegacyTypeNames() {
        for (type in listOf("SERIES", "series", "tv", "TV")) {
            val card = assertNotNull(entry("series:2316").copy(
                mediaType = type, title = "Basketball", showTitle = "The Office",
                seasonNumber = 1, episodeNumber = 5,
            ).toCatalogHistoryItemOrNull())
            assertEquals("The Office", card.title)
            assertEquals(MediaType.SERIES, card.type)
            assertEquals(2316, card.tmdbId)
        }
    }

    @Test
    fun explicitlyNonCatalogTypesAreExcludedEvenWithANumericId() {
        for (type in listOf("sports", "live", "file", "unknown")) {
            assertNull(entry("123").copy(mediaType = type).toCatalogHistoryItemOrNull(), type)
        }
    }

    private fun entry(id: String) = WatchHistoryEntry(
        id = "history-$id", mediaId = id, mediaType = "movie",
        title = "upload/Fussball.Premier.League.2026.mkv",
        posterUrl = null, backdropUrl = null, watchedAt = 10L, durationWatchedMs = 100L,
        seasonNumber = null, episodeNumber = null, showTitle = null,
    )
}
