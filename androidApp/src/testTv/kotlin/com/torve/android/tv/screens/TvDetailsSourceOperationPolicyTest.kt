package com.torve.android.tv.screens

import com.torve.presentation.detail.DetailUiState
import com.torve.presentation.detail.PreparingStreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDetailsSourceOperationPolicyTest {

    @Test
    fun streamFailureSuppressesCinematicLayerEvenWhenLoadingFlagIsStale() {
        val state = DetailUiState(
            isLoadingStreams = true,
            streamsError = "No streams found",
        )

        assertTrue(state.hasTerminalSourceFailure())
        assertFalse(state.shouldShowCinematicSourceLoading(sourcePickerVisible = false))
    }

    @Test
    fun resolveFailureSuppressesCinematicLayerEvenWhenResolvingFlagIsStale() {
        val state = DetailUiState(
            isResolving = true,
            resolveError = "source_not_found",
        )

        assertTrue(state.hasTerminalSourceFailure())
        assertFalse(state.shouldShowCinematicSourceLoading(sourcePickerVisible = false))
    }

    @Test
    fun activeLookupStillShowsCinematicLayer() {
        assertTrue(
            DetailUiState(isLoadingStreams = true)
                .shouldShowCinematicSourceLoading(sourcePickerVisible = false),
        )
        assertFalse(
            DetailUiState(
                isResolving = true,
                preparing = PreparingStreamState("Source", 1L, 1, "Panda"),
            ).shouldShowCinematicSourceLoading(sourcePickerVisible = false),
        )
    }

    @Test
    fun terminalEpisodeFailureRestoresStableClickedEpisode() {
        val failed = DetailUiState(streamsError = "No streams found")

        assertEquals(
            2 to 7,
            terminalEpisodeFocusTarget(
                state = failed,
                sourcePickerOriginEpisode = 2 to 7,
                resolvingEpisodeTarget = 2 to 6,
            ),
        )
        assertNull(
            terminalEpisodeFocusTarget(
                state = DetailUiState(isLoadingStreams = true),
                sourcePickerOriginEpisode = 2 to 7,
                resolvingEpisodeTarget = 2 to 7,
            ),
        )
    }
}
