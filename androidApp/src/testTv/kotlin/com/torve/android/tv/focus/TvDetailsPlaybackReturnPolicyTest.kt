package com.torve.android.tv.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDetailsPlaybackReturnPolicyTest {

    @Test
    fun episodeRestoreWaitsUntilTheLazyItemIsActuallyComposed() {
        val target = 2 to 14

        assertFalse(
            isTvPlaybackEpisodeFocusTargetReady(
                requestedEpisode = target,
                selectedSeason = 2,
                resolvedEpisodeNumber = 14,
                composedEpisodeNumbers = setOf(1, 2, 3, 4),
            ),
        )
        assertTrue(
            isTvPlaybackEpisodeFocusTargetReady(
                requestedEpisode = target,
                selectedSeason = 2,
                resolvedEpisodeNumber = 14,
                composedEpisodeNumbers = setOf(12, 13, 14, 15),
            ),
        )
    }

    @Test
    fun seriesPlaybackReturnsToTheOriginatingEpisodeIdentity() {
        assertEquals(
            TvDetailsPlaybackReturnTarget.Episode(season = 4, episode = 8),
            resolveTvDetailsPlaybackReturnTarget(
                isSeries = true,
                originSeason = 4,
                originEpisode = 8,
            ),
        )
    }

    @Test
    fun movieAndMissingEpisodeOriginsUseThePrimaryActionFallback() {
        assertEquals(
            TvDetailsPlaybackReturnTarget.PrimaryAction,
            resolveTvDetailsPlaybackReturnTarget(false, -1, -1),
        )
        assertEquals(
            TvDetailsPlaybackReturnTarget.PrimaryAction,
            resolveTvDetailsPlaybackReturnTarget(true, 3, -1),
        )
    }
}
