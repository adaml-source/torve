package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import com.torve.data.usenet.model.UsenetResolvedStream
import com.torve.domain.model.ResolvedStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreparingStreamStatePolicyTest {

    @Test
    fun cancelDuringPreparationKeepsSourcesAndEpisodeAvailableForAnotherAttempt() {
        val sources = listOf(ParsedStream("Panda", "1080p", "The Office S01E05"))
        val cancelled = DetailUiState(
            streams = sources,
            streamContextSeason = 1,
            streamContextEpisode = 5,
            isLoadingStreams = true,
            isLoadingMoreSources = true,
            isResolving = true,
            preparing = PreparingStreamState("The Office S01E05", 1L, 2, "TorBox"),
        ).withSourceLookupCancelled()

        assertEquals(sources, cancelled.streams)
        assertEquals(1, cancelled.streamContextSeason)
        assertEquals(5, cancelled.streamContextEpisode)
        assertTrue(cancelled.showStreamPicker)
        assertFalse(cancelled.isLoadingStreams)
        assertFalse(cancelled.isLoadingMoreSources)
        assertFalse(cancelled.isResolving)
        assertNull(cancelled.preparing)
    }

    @Test
    fun cancelBeforeUiConsumesReadySourceCannotLaunchOldPlayback() {
        val cancelled = DetailUiState(
            resolvedStream = ResolvedStream("https://fixture.example/old-episode.mp4"),
            usenetPlaybackIntent = UsenetResolvedStream("https://fixture.example/old-handoff"),
        ).withSourceLookupCancelled()

        assertNull(cancelled.resolvedStream)
        assertNull(cancelled.usenetPlaybackIntent)
        assertFalse(cancelled.showStreamPicker)
    }

    @Test
    fun terminalFailureReleasesBlockingStateAndExposesExistingChoices() {
        val state = DetailUiState(
            streams = listOf(ParsedStream("Panda", "1080p", "Source")),
            isResolving = true,
            autoPlayMessage = "Resolving",
            preparing = PreparingStreamState("Source", 1L, 2, "Panda"),
        )

        val failed = state.withPreparingFailure("source_not_found")

        assertNull(failed.preparing)
        assertFalse(failed.isResolving)
        assertNull(failed.autoPlayMessage)
        assertTrue(failed.showStreamPicker)
        assertEquals("source_not_found", failed.resolveError)
    }

    @Test
    fun terminalFailureWithoutChoicesReturnsToDetailsInsteadOfEmptyPicker() {
        val failed = DetailUiState(
            isResolving = true,
            preparing = PreparingStreamState("Source", 1L, 1, "Panda"),
        ).withPreparingFailure("source_not_found")

        assertFalse(failed.isResolving)
        assertFalse(failed.showStreamPicker)
    }
}
