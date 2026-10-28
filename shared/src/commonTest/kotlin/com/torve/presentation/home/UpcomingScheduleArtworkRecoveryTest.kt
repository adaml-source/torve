package com.torve.presentation.home

import com.torve.domain.model.*
import com.torve.presentation.util.needsSchedulePresentationRefresh
import com.torve.presentation.util.mergeScheduleVisualsFrom
import kotlin.test.*

class UpcomingScheduleArtworkRecoveryTest {
    @Test fun scheduleReusesInstalledCatalogueArtworkWithoutChangingEpisodeIdentity() {
        val episode = MediaItem("trakt-calendar:95480:6:6:2026-10-21T20:00:00Z", tmdbId = 95480,
            title = "Slow Horses - S6E6 - Judgement Day", type = MediaType.SERIES,
            releaseDate = "2026-10-21T20:00:00Z")
        val textOnlyCache = episode.copy(id = "95480", title = "Slow Horses")
        val catalogue = MediaItem("tt5875444", title = "Slow Horses", type = MediaType.SERIES,
            posterUrl = "poster.jpg", backdropUrl = "background.jpg", logoUrl = "logo.png", overview = "Series synopsis")
        val repaired = listOf(episode).mergeScheduleVisualsFrom(listOf(textOnlyCache, catalogue)).single()
        assertEquals(episode.id, repaired.id)
        assertEquals(episode.title, repaired.title)
        assertEquals(episode.releaseDate, repaired.releaseDate)
        assertEquals("poster.jpg", repaired.posterUrl)
        assertEquals("logo.png", repaired.logoUrl)
        assertFalse(listOf(repaired).needsSchedulePresentationRefresh())
    }

    @Test fun sameTitleWithConflictingSeriesIdDoesNotSupplyArtwork() {
        val episode = MediaItem("trakt-calendar:42:1:1:date", tmdbId = 42, title = "Show - S1E1", type = MediaType.SERIES)
        val other = MediaItem("other", tmdbId = 99, title = "Show", type = MediaType.SERIES, posterUrl = "wrong.jpg")
        assertNull(listOf(episode).mergeScheduleVisualsFrom(listOf(other)).single().posterUrl)
    }
    @Test fun datedTextOnlyCachedScheduleStillRequiresArtworkRepair() {
        val item = MediaItem("trakt-calendar:42:1:2:2026-10-25T12:00:00Z", tmdbId = 42, title = "Show - S1E2", type = MediaType.SERIES,
            releaseDate = "2026-10-25T12:00:00Z")
        assertTrue(listOf(item).needsSchedulePresentationRefresh())
        assertFalse(listOf(item.copy(posterUrl = "poster.jpg")).needsSchedulePresentationRefresh())
        assertFalse(listOf(item.copy(backdropUrl = "backdrop.jpg")).needsSchedulePresentationRefresh())
    }
}
