package com.torve.android.tv

import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
