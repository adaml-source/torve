package com.torve.presentation.watching

import com.torve.domain.model.Episode
import kotlin.test.*

class EpisodePresentationTest {
    private val episode = Episode(5, "The character dies", "A major reveal", "https://scene", "2026-10-08", 50, 9.0)
    @Test fun hiddenSpoilersNeverRemoveTheEpisodeIdentityOrAirDate() {
        val safe = episode.forPresentation(true, false)
        assertEquals("Episode 5", safe.name); assertEquals("", safe.overview); assertNull(safe.stillUrl)
        assertEquals(episode.episodeNumber, safe.episodeNumber); assertEquals(episode.airDate, safe.airDate)
        assertEquals(episode.runtime, safe.runtime); assertEquals(0.0, safe.rating)
    }
    @Test fun watchedEpisodesAndExplicitOptOutRetainTheirFullInformation() {
        assertEquals(episode, episode.forPresentation(true, true))
        assertEquals(episode, episode.forPresentation(false, false))
    }
}
