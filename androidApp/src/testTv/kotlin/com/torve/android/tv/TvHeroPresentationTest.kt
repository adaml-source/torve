package com.torve.android.tv

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class TvHeroPresentationTest {
    @Test fun importedWatchlistIdentitySurvivesArtworkAndSynopsisHydration() {
        val saved = MediaItem("tt1234567", title = "Saved title", type = MediaType.MOVIE)
        val result = saved.withTvHeroDetail(MediaItem("42", tmdbId = 42, title = "Provider title", type = MediaType.MOVIE,
            logoUrl = "logo.png", overview = "The full synopsis", posterUrl = "poster.jpg"))
        assertEquals("tt1234567", result.id)
        assertEquals("Saved title", result.title)
        assertEquals(42, result.tmdbId)
        assertEquals("logo.png", result.logoUrl)
        assertEquals("The full synopsis", result.overview)
    }
    @Test fun calendarEpisodeIdentityAndAiringTimeArePreserved() {
        val saved = MediaItem("trakt-calendar:42:2:3:2026-10-25T12:00:00Z", tmdbId = 42,
            title = "Show - S2E3 - The episode", type = MediaType.SERIES, releaseDate = "2026-10-25T12:00:00Z")
        val result = saved.withTvHeroDetail(MediaItem("42", tmdbId = 42, title = "Show", type = MediaType.SERIES, logoUrl = "logo.png"))
        assertEquals(saved.id, result.id)
        assertEquals(saved.title, result.title)
        assertEquals(saved.releaseDate, result.releaseDate)
        assertEquals("logo.png", result.logoUrl)
    }
}
