package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import com.torve.domain.model.*
import kotlin.test.*

class DetailPresentationClosureTest {
    private val source = ParsedStream("Provider", "1080p", "Previous episode", directUrl = "https://fixture.invalid/one")
    @Test fun actorDepartureClosesPickerAndDiscardsReadyHandoffs() {
        val state = DetailUiState(streams = listOf(source), showStreamPicker = true,
            isLoadingStreams = true, isResolving = true, autoPlayStream = source,
            resolvedStream = ResolvedStream("https://fixture.invalid/expired"))
        val closed = state.withSourcePresentationClosed()
        assertFalse(closed.showStreamPicker); assertFalse(closed.isResolving); assertFalse(closed.isLoadingStreams)
        assertTrue(closed.streams.isEmpty()); assertNull(closed.resolvedStream); assertNull(closed.autoPlayStream)
    }
    @Test fun completedEpisodeReturnsWithNextTargetAndNoPreviousSources() {
        val next = NextEpisodeInfo(1, 2, mode = NextEpisodeMode.PLAY_FIRST_UNWATCHED)
        val closed = DetailUiState(mediaItem = MediaItem("series", title = "Series", type = MediaType.SERIES),
            streams = listOf(source), streamContextSeason = 1, streamContextEpisode = 1, showStreamPicker = true)
            .withSourcePresentationClosed().copy(nextEpisode = next, streamContextSeason = 1, streamContextEpisode = 2)
        assertEquals(2, closed.primaryPlaybackTarget.episode)
        assertFalse(closed.showStreamPicker); assertTrue(closed.streams.isEmpty())
    }
    @Test fun closedPresentationDoesNotDiscardMetadataOrWatchProgress() {
        val movie = MediaItem("movie", title = "Movie", type = MediaType.MOVIE)
        val progress = WatchProgress("movie", MediaType.MOVIE, "Movie", positionMs = 50_000, durationMs = 100_000)
        val closed = DetailUiState(mediaItem = movie, watchProgress = progress, streams = listOf(source)).withSourcePresentationClosed()
        assertEquals(movie, closed.mediaItem); assertEquals(progress, closed.watchProgress)
        assertTrue(closed.primaryPlaybackTarget.isResume)
    }
}
