package com.torve.android.tv.screens

import com.torve.presentation.detail.NextEpisodeInfo
import com.torve.presentation.detail.NextEpisodeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvDetailsContentRowsTest {
    @Test
    fun relatedGridReturnsToAnExistingFocusableRowForMoviesAndSeries() {
        assertEquals("cast", tvDetailsRelatedReturnRow(false, true, true))
        assertEquals("cast_secondary", tvDetailsRelatedReturnRow(true, true, true))
        assertEquals("director", tvDetailsRelatedReturnRow(false, false, true))
        assertEquals("hero", tvDetailsRelatedReturnRow(false, false, false))
    }

    @Test
    fun seriesEpisodesStayImmediatelyAfterHeroEvenWithAllMetadata() {
        val rows = tvDetailsContentRowKeys(
            isSeries = true,
            hasTagline = true,
            hasGenres = true,
            hasOverview = true,
            hasDirector = true,
            hasCast = true,
        )

        assertEquals(
            listOf("hero", "episodes", "tagline", "genres", "overview", "director", "cast_secondary"),
            rows,
        )
        assertEquals(1, rows.indexOf("episodes"))
    }

    @Test
    fun missingOrDisabledMetadataDoesNotCreatePhantomScrollRows() {
        assertEquals(
            listOf("hero", "episodes", "overview", "cast_secondary"),
            tvDetailsContentRowKeys(true, false, false, true, false, true),
        )
        assertEquals(
            listOf("hero", "episodes"),
            tvDetailsContentRowKeys(true, false, false, false, false, false),
        )
    }

    @Test
    fun movieCastFollowsHeroWithoutATemporaryLibraryRow() {
        assertEquals(
            listOf("hero", "cast"),
            tvDetailsContentRowKeys(false, false, false, false, false, true),
        )
    }

    @Test
    fun primaryActionNamesTheNextEpisodeAndOnlyResumesMatchingProgress() {
        val next = NextEpisodeInfo(2, 4, mode = NextEpisodeMode.PLAY_FIRST_UNWATCHED)
        assertEquals("Watch S2 E4", tvSeriesPlayActionLabel(next, null, null))
        val resume = next.copy(mode = NextEpisodeMode.RESUME_IN_PROGRESS)
        assertEquals("Resume S2 E4", tvSeriesPlayActionLabel(resume, null, null))
        assertEquals("Watch S1 E3", tvSeriesPlayActionLabel(resume, 1, 3))
        assertEquals("Watch now", tvSeriesPlayActionLabel(null, null, null))
    }

    @Test
    fun aPartiallyWatchedSeriesShowsAnEpisodeCountRatherThanACompletionBadge() {
        assertNull(tvSeriesWatchedSummary(emptySet()))
        assertEquals("1 episode watched", tvSeriesWatchedSummary(setOf("s2e1")))
        assertEquals("3 episodes watched", tvSeriesWatchedSummary(setOf("s2e1", "s2e2", "s2e3", "s0e1", "invalid")))
    }
}
