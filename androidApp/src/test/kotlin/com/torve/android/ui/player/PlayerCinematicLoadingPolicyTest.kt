package com.torve.android.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCinematicLoadingPolicyTest {

    @Test
    fun nextEpisodeLoadingIsVisibleOnlyUntilPlaybackStarts() {
        assertTrue(
            shouldShowPlayerCinematicLoading(
                playbackUrl = "https://stream/season-1-episode-2",
                firstFrameAtMs = 0L,
                errorMessage = null,
            ),
        )

        assertFalse(
            shouldShowPlayerCinematicLoading(
                playbackUrl = "https://stream/season-1-episode-2",
                firstFrameAtMs = 42L,
                errorMessage = null,
            ),
        )
    }

    @Test
    fun terminalErrorOrMissingPlaybackUrlNeverLeavesArtworkBlockingPlayer() {
        assertFalse(
            shouldShowPlayerCinematicLoading(
                playbackUrl = "https://stream/season-1-episode-2",
                firstFrameAtMs = 0L,
                errorMessage = "Playback failed",
            ),
        )
        assertFalse(
            shouldShowPlayerCinematicLoading(
                playbackUrl = "",
                firstFrameAtMs = 0L,
                errorMessage = null,
            ),
        )
    }
}
