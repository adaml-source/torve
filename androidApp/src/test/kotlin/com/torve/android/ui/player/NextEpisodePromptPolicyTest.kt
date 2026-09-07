package com.torve.android.ui.player

import com.torve.domain.model.NextEpisodeMode
import com.torve.domain.player.SegmentActionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePromptPolicyTest {
    @Test
    fun detectedCreditsShowPromptWhenLegacyTimingIsAtEnd() {
        assertTrue(
            shouldShowNextEpisodePrompt(
                detectedCreditsPrompt = true,
                hasSegments = true,
                remainingMs = 45_000L,
                nextEpisodeMode = NextEpisodeMode.AT_END,
                creditsActionMode = SegmentActionMode.SHOW_BUTTON,
            ),
        )
    }

    @Test
    fun atEndChoicePreventsAutomaticCreditsCountdown() {
        assertFalse(
            allowDetectedCreditsCountdown(
                segmentAllowsCountdown = true,
                nextEpisodeMode = NextEpisodeMode.AT_END,
            ),
        )
        assertTrue(
            allowDetectedCreditsCountdown(
                segmentAllowsCountdown = true,
                nextEpisodeMode = NextEpisodeMode.AT_CREDITS,
            ),
        )
    }

    @Test
    fun globalAutoplayOffSuppressesPrompt() {
        assertFalse(
            shouldShowNextEpisodePrompt(
                detectedCreditsPrompt = true,
                hasSegments = true,
                remainingMs = 45_000L,
                nextEpisodeMode = NextEpisodeMode.OFF,
                creditsActionMode = SegmentActionMode.SHOW_BUTTON,
            ),
        )
    }
}
