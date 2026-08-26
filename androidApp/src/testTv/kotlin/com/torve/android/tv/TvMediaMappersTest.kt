package com.torve.android.tv

import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TvMediaMappersTest {

    @Test
    fun blankPosterFallsBackToBackdrop() {
        assertEquals("https://img.test/backdrop.jpg", preferredTvPosterUrl("", "https://img.test/backdrop.jpg"))

        val item = WatchProgress(
            mediaId = "tmdb:1",
            mediaType = MediaType.MOVIE,
            title = "Movie",
            posterUrl = "   ",
            backdropUrl = "https://img.test/backdrop.jpg",
        ).toMediaItemOrNull()

        assertEquals("https://img.test/backdrop.jpg", item?.posterUrl)
    }

    @Test
    fun blankArtworkNormalizesToMissing() {
        assertNull(preferredTvPosterUrl(" ", ""))
    }

    @Test
    fun imdbOnlySeriesProgressStillBuildsAContinueWatchingCard() {
        val item = WatchProgress(
            mediaId = "tt0369179",
            mediaType = MediaType.SERIES,
            title = "S01E01 - Pilot",
            showTitle = "Two and a Half Men",
            positionMs = 600_000L,
            durationMs = 1_200_000L,
        ).toMediaItemOrNull()

        assertNotNull(item)
        assertEquals("tt0369179", item?.imdbId)
        assertEquals("Two and a Half Men", item?.title)
    }
}
