package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreparingStreamStatePolicyTest {

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
