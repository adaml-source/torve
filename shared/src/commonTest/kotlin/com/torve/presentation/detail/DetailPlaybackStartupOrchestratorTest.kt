package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailPlaybackStartupOrchestratorTest {

    @Test
    fun reducer_tracks_progressive_startup_phases_in_order() {
        var status = PlaybackStartupStatus()

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.LoadingStartupCandidates,
        )
        assertEquals(PlaybackStartupPhase.LOADING_STARTUP_CANDIDATES, status.phase)

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.StartupCandidatesAvailable(candidateCount = 2),
        )
        assertEquals(PlaybackStartupPhase.STARTUP_CANDIDATES_AVAILABLE, status.phase)
        assertEquals(2, status.startupCandidateCount)
        assertEquals(2, status.mergedResultCount)

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.AttemptingStartupAutoplay,
        )
        assertEquals(PlaybackStartupPhase.ATTEMPTING_STARTUP_AUTOPLAY, status.phase)

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.StartupCandidateFailed,
        )
        assertEquals(PlaybackStartupPhase.STARTUP_CANDIDATE_FAILED, status.phase)

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.FallingBackToFullFetch,
        )
        assertEquals(PlaybackStartupPhase.FALLING_BACK_TO_FULL_FETCH, status.phase)

        status = DetailPlaybackStartupOrchestrator.reduce(
            status,
            PlaybackStartupEvent.FullResultsAvailable(mergedResultCount = 5),
        )
        assertEquals(PlaybackStartupPhase.FULL_RESULTS_AVAILABLE, status.phase)
        assertEquals(5, status.mergedResultCount)
    }

    @Test
    fun mergeStreams_keeps_startup_order_and_replaces_duplicates_in_place() {
        val startupA = stream(key = "hash-a", title = "Startup A", quality = "720p")
        val startupB = stream(key = "hash-b", title = "Startup B", quality = "1080p")
        val fullB = stream(key = "hash-b", title = "Full B", quality = "4K")
        val fullC = stream(key = "hash-c", title = "Full C", quality = "1080p")

        val merged = DetailPlaybackStartupOrchestrator.mergeStreams(
            startupStreams = listOf(startupA, startupB),
            fullStreams = listOf(fullB, fullC),
            keySelector = { it.infoHash ?: it.title },
        )

        assertEquals(listOf("Startup A", "Full B", "Full C"), merged.map { it.title })
    }

    @Test
    fun mergeStreams_returns_single_copy_when_startup_and_full_match() {
        val startup = stream(key = "hash-a", title = "Startup", quality = "720p")
        val full = stream(key = "hash-a", title = "Full", quality = "1080p")

        val merged = DetailPlaybackStartupOrchestrator.mergeStreams(
            startupStreams = listOf(startup),
            fullStreams = listOf(full),
            keySelector = { it.infoHash ?: it.title },
        )

        assertEquals(1, merged.size)
        assertEquals("Full", merged.single().title)
    }

    private fun stream(
        key: String,
        title: String,
        quality: String,
    ) = ParsedStream(
        addonName = "Test",
        quality = quality,
        title = title,
        infoHash = key,
    )
}
