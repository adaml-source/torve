package com.torve.data.trakt.db

import com.torve.data.trakt.TraktIds
import com.torve.data.trakt.TraktRatingResponse
import com.torve.data.trakt.TraktWatchlistMediaResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TraktMappersTest {

    @Test
    fun mapRatingResponseToCache_mapsMovieResponse() {
        val result = mapRatingResponseToCache(
            item = TraktRatingResponse(
                ratedAt = "2026-02-25T10:00:00.000Z",
                rating = 9,
                type = "movie",
                movie = TraktWatchlistMediaResponse(
                    title = "Movie",
                    ids = TraktIds(tmdb = 1234),
                ),
            ),
            nowMs = 1000L,
        )

        assertNotNull(result)
        assertEquals("movie:1234", result.mediaKey)
        assertEquals("movie", result.mediaType)
        assertEquals(9, result.rating)
    }

    @Test
    fun mapRatingResponseToCache_returnsNullWithoutTmdbId() {
        val result = mapRatingResponseToCache(
            item = TraktRatingResponse(
                rating = 7,
                type = "show",
                show = TraktWatchlistMediaResponse(title = "Show", ids = TraktIds(tmdb = null)),
            ),
            nowMs = 1000L,
        )
        assertNull(result)
    }
}
