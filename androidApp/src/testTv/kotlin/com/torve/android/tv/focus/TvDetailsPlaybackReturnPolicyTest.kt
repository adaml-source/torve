package com.torve.android.tv.focus

import org.junit.Assert.assertEquals
import org.junit.Test

class TvDetailsPlaybackReturnPolicyTest {

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
