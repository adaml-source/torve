package com.torve.android.tv.components

import com.torve.domain.model.*
import com.torve.android.tv.withTvHeroDetail
import org.junit.Assert.*
import org.junit.Test

class TvExpandedCardPresentationTest {
    @Test fun calendarIdentityWinsOverAConflictingTitleLabel() {
        val item = MediaItem("trakt-calendar:42:2:1:2026-10-26T12:00:00Z", type = MediaType.SERIES,
            title = "Mobland - S9E9 - An episode")
        assertEquals("S02E01", item.tvPreviewEpisodeCode())
        assertEquals("Mobland", item.tvPreviewShowTitle())
    }
    @Test fun upNextAndCalendarLabelsDoNotDuplicateEpisodeCodesInTheLogoFallback() {
        for (title in listOf("S2 E1 · Mobland", "Mobland – S02E01 – An episode")) {
            val item = MediaItem("42", type = MediaType.SERIES, title = title)
            assertEquals("S02E01", item.tvPreviewEpisodeCode())
            assertEquals("Mobland", item.tvPreviewShowTitle())
        }
    }
    @Test fun genericShowAndMovieDoNotInventAnEpisode() {
        assertNull(MediaItem("42", type = MediaType.SERIES, title = "Mobland").tvPreviewEpisodeCode())
        val film = MediaItem("43", type = MediaType.MOVIE, title = "S1E2")
        assertNull(film.tvPreviewEpisodeCode())
        assertEquals("S1E2", film.tvPreviewShowTitle())
    }
    @Test fun hydrationKeepsEpisodeIdentityAndSuppliesCastForTheExpandedCard() {
        val item = MediaItem("trakt-calendar:42:2:1:date", type = MediaType.SERIES, title = "Mobland - S2E1")
        val detail = MediaItem("42", type = MediaType.SERIES, title = "Mobland", year = 2025,
            cast = listOf(CastMember(1, "Tom Hardy")))
        val merged = item.withTvHeroDetail(detail)
        assertEquals(item.id, merged.id)
        assertEquals(item.title, merged.title)
        assertEquals("S02E01", merged.tvPreviewEpisodeCode())
        assertEquals(2025, merged.year)
        assertEquals(detail.cast, merged.cast)
    }
}
