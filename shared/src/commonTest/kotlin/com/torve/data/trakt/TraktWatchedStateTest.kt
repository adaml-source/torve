package com.torve.data.trakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraktWatchedStateTest {
    @Test
    fun watchedEpisodeMarkersAreResetAwareAndRejectInvalidRows() {
        val show = watchedShow(
            resetAt = "2026-08-01T00:00:00Z",
            seasons = listOf(
                TraktWatchedSeason(
                    number = 1,
                    episodes = listOf(
                        TraktWatchedEpisode(1, plays = 1, lastWatchedAt = "2026-07-01T00:00:00Z"),
                        TraktWatchedEpisode(2, plays = 1, lastWatchedAt = "2026-08-02T00:00:00Z"),
                        TraktWatchedEpisode(3, plays = 0, lastWatchedAt = "2026-08-03T00:00:00Z"),
                    ),
                ),
            ),
        )

        assertEquals(listOf(1 to 2), show.episodeMarkers().map { it.season to it.episode })
    }

    @Test
    fun latestEpisodeUsesWatchTimeBeforeEpisodeNumber() {
        val show = watchedShow(
            seasons = listOf(
                TraktWatchedSeason(
                    number = 1,
                    episodes = listOf(
                        TraktWatchedEpisode(9, plays = 1, lastWatchedAt = "2026-08-03T00:00:00Z"),
                        TraktWatchedEpisode(10, plays = 1, lastWatchedAt = "2026-08-02T00:00:00Z"),
                    ),
                ),
            ),
        )

        val latest = show.latestEpisodeMarker()
        assertEquals(1, latest?.season)
        assertEquals(9, latest?.episode)
    }

    @Test
    fun malformedDatesDoNotCreateUnsafeProgress() {
        val show = watchedShow(
            lastWatchedAt = "bad-date",
            seasons = listOf(
                TraktWatchedSeason(
                    number = 1,
                    episodes = listOf(TraktWatchedEpisode(1, plays = 1)),
                ),
            ),
        )

        assertNull(show.latestEpisodeMarker())
    }

    @Test
    fun remoteProgressOnlyReplacesAnOlderLocalSnapshot() {
        assertTrue(shouldApplyRemoteProgress(null, 20))
        assertTrue(shouldApplyRemoteProgress(10, 20))
        assertFalse(shouldApplyRemoteProgress(20, 20))
        assertFalse(shouldApplyRemoteProgress(30, 20))
    }

    @Test
    fun materializedHistoryIdsAreStablePerEpisode() {
        assertEquals("trakt_watched_77_5_12", traktWatchedHistoryId("77", 5, 12))
    }

    private fun watchedShow(
        lastWatchedAt: String = "2026-08-04T00:00:00Z",
        resetAt: String? = null,
        seasons: List<TraktWatchedSeason>,
    ) = TraktWatchedShowResponse(
        lastWatchedAt = lastWatchedAt,
        resetAt = resetAt,
        show = TraktWatchedShowMedia(
            title = "Show",
            ids = TraktIds(trakt = 77, tmdb = 88),
        ),
        seasons = seasons,
    )
}
