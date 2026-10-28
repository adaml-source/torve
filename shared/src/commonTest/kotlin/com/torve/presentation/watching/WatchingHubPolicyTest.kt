package com.torve.presentation.watching

import com.torve.domain.model.*
import kotlinx.datetime.LocalDate
import kotlin.test.*

class WatchingHubPolicyTest {
    @Test fun offlineRefreshRetainsOnlyTheSavedSchedulesThatFailed() {
        val other = ep(1, 1).copy(show = show.copy(id = "2", tmdbId = 2))
        val initial = WatchingHubState(upNext = listOf(ep(1, 2), other))
        val result = initial.withCompletedSchedules(listOf(null to emptyList()), emptyMap(),
            loading = false, failures = 1, failedShowIds = setOf(1))
        assertEquals(listOf(1), result.upNext.map { it.show.tmdbId })
        assertEquals(WatchingHubError.PARTIAL_REFRESH, result.errorKind)
    }
    @Test fun aReadyScheduleAppearsWhileOtherShowsStillLoadAndRetainsEnrichment() {
        val first = ep(1, 2).copy(show = show.copy(ratings = MediaRatings(imdbScore = 8f, rottenTomatoesScore = 92)))
        val second = ep(1, 1).copy(show = show.copy(id = "2", tmdbId = 2), resume = true)
        val initial = WatchingHubState(upNext = listOf(first), hideSpoilers = true)
        val partial = initial.withCompletedSchedules(listOf(second to listOf(second)), mapOf("tmdb:1" to 10L, "tmdb:2" to 5L), true, 0)
        assertTrue(partial.loading)
        assertTrue(partial.hideSpoilers)
        assertEquals(listOf(2, 1), partial.upNext.map { it.show.tmdbId })
        val complete = partial.withCompletedSchedules(listOf(second to listOf(second), ep(1, 2) to emptyList()), emptyMap(), false, 0)
        assertFalse(complete.loading)
        assertEquals(92, complete.upNext.last().show.ratings?.rottenTomatoesScore)
        assertEquals(1, complete.calendar.size)
    }

    @Test fun completionRemovesCaughtUpRowsAndReportsPartialScheduleFailures() {
        val initial = WatchingHubState(upNext = listOf(ep(1, 2)), calendar = listOf(ep(1, 3)))
        val partial = initial.withCompletedSchedules(listOf(null to emptyList()), emptyMap(), true, 1)
        assertEquals(initial.upNext, partial.upNext)
        val complete = partial.withCompletedSchedules(listOf(null to emptyList()), emptyMap(), false, 1)
        assertTrue(complete.upNext.isEmpty())
        assertTrue(complete.calendar.isEmpty())
        assertEquals(WatchingHubError.PARTIAL_REFRESH, complete.errorKind)
        assertEquals(1, complete.incompleteShows)
    }

    @Test fun enrichmentUpdatesBothTabsWithoutChangingEpisodeProgressOrArtwork() {
        val series = MediaItem("saved:1", tmdbId = 1, type = MediaType.SERIES, title = "Saved show", year = 2014, posterUrl = "poster")
        val next = WatchingEpisode(series, 1, Episode(2, "Next episode"), LocalDate(2026, 10, 7), resume = true, positionMs = 12_000L)
        val calendar = next.copy(episode = Episode(3, "Upcoming"), resume = false, positionMs = null)
        val state = WatchingHubState(upNext = listOf(next), calendar = listOf(calendar))
        val ratings = MediaRatings(imdbScore = 8.7f, rottenTomatoesScore = 95)
        val result = state.withEnrichedShowRatings(listOf(series.copy(id = "1", posterUrl = "other", ratings = ratings)))
        assertEquals(ratings, result.upNext.single().show.ratings)
        assertEquals(ratings, result.calendar.single().show.ratings)
        assertEquals(next, result.upNext.single().copy(show = series))
        assertEquals("poster", result.upNext.single().show.posterUrl)
        assertEquals(2014, result.upNext.single().show.year)
    }

    @Test fun enrichmentDoesNotBorrowRatingsFromAnotherMediaTypeOrClearExistingRatings() {
        val rating = MediaRatings(imdbScore = 8.7f)
        val series = MediaItem("1", tmdbId = 1, type = MediaType.SERIES, title = "Show", ratings = rating)
        val state = WatchingHubState(upNext = listOf(WatchingEpisode(series, 1, Episode(1), null)))
        assertEquals(state, state.withEnrichedShowRatings(listOf(series.copy(type = MediaType.MOVIE, ratings = MediaRatings(imdbScore = 1f)))))
        assertEquals(state, state.withEnrichedShowRatings(listOf(series.copy(ratings = null))))
    }
    private val today = LocalDate(2026, 10, 7)
    private val show = MediaItem("1", tmdbId = 1, type = MediaType.SERIES, title = "Series")
    private fun ep(season: Int, episode: Int, date: LocalDate? = today) =
        WatchingEpisode(show, season, Episode(episode, "Episode $episode"), date)
    @Test fun nextEpisodeUsesSeasonOrderAndCountsOnlyAiredUnwatchedEpisodes() {
        val episodes = listOf(ep(2, 3), ep(1, 2), ep(1, 1), ep(2, 1), ep(2, 2),
            ep(2, 4, LocalDate(2026, 10, 8)), ep(2, 5, null))
        val next = assertNotNull(chooseUpNext(episodes, setOf(1 to 1, 1 to 2, 2 to 1), null, today))
        assertEquals(2, next.season); assertEquals(2, next.episode.episodeNumber)
        assertEquals(2, next.remainingAired)
    }
    @Test fun partialEpisodeTakesPriorityWithoutHidingEarlierGaps() {
        val progress = WatchProgress("1", MediaType.SERIES, "Series", positionMs = 10, durationMs = 100,
            seasonNumber = 2, episodeNumber = 3)
        val next = assertNotNull(chooseUpNext(listOf(ep(2, 2), ep(2, 3)), emptySet(), progress, today))
        assertEquals(3, next.episode.episodeNumber); assertTrue(next.resume); assertEquals(2, next.remainingAired)
        assertEquals(10L, next.positionMs); assertEquals(.1f, next.progressPercent)
    }
    @Test fun resumePositionDoesNotLeakIntoADifferentEpisode() {
        val progress = WatchProgress("1", MediaType.SERIES, "Series", positionMs = 90, durationMs = 100,
            seasonNumber = 2, episodeNumber = 3)
        val next = assertNotNull(chooseUpNext(listOf(ep(2, 2), ep(2, 3)), setOf(2 to 3), progress, today))
        assertEquals(2, next.episode.episodeNumber)
        assertFalse(next.resume); assertNull(next.positionMs); assertNull(next.progressPercent)
    }
    @Test fun caughtUpAndUnreleasedShowsHaveNoNextPlayableEpisode() {
        assertNull(chooseUpNext(listOf(ep(1, 1)), setOf(1 to 1), null, today))
        assertNull(chooseUpNext(listOf(ep(1, 1, LocalDate(2026, 10, 8)), ep(1, 2, null)), emptySet(), null, today))
    }
    @Test fun restoredProgressAndHistoryProvideMissingShowArtwork() {
        val restored = WatchProgress("1", MediaType.SERIES, "Series", posterUrl = "saved-poster")
        val history = WatchHistoryEntry("h", "1", "series", "Series", null, "saved-backdrop", 1, 1, 1, 1, "Series")
        val result = retainWatchingArtwork(show.copy(backdropUrl = " ", posterUrl = ""), restored, history, "watchlist-poster")
        assertEquals("saved-poster", result.posterUrl)
        assertEquals("saved-backdrop", result.backdropUrl)
        assertEquals(show.id, result.id)
    }
    @Test fun refreshedMetadataArtworkWinsAndWatchlistCanSupplyTheLastFallback() {
        val restored = WatchProgress("1", MediaType.SERIES, "Series", posterUrl = "saved-poster", backdropUrl = "saved-backdrop")
        val fresh = show.copy(backdropUrl = "fresh-backdrop", posterUrl = "fresh-poster")
        assertEquals(fresh, retainWatchingArtwork(fresh, restored, null, "watchlist-poster"))
        assertEquals("watchlist-poster", retainWatchingArtwork(show, null, null, "watchlist-poster").posterUrl)
    }
}
