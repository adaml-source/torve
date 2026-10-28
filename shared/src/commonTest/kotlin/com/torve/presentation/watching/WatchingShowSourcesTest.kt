package com.torve.presentation.watching

import com.torve.data.addon.*
import com.torve.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.*

class WatchingShowSourcesTest {
    private val show = MediaItem("tt1234567", imdbId = "tt1234567", type = MediaType.SERIES, title = "Saved series")
    private val provider = InstalledAddon("https://metadata.test/manifest.json",
        AddonManifest("metadata", "Metadata", "1", resources = listOf("meta"), types = listOf("series"), idPrefixes = listOf("tt")))

    @Test fun imdbProgressAndNumericSavedShowShareTheirHistoryAndKeepTheProviderId() {
        val progress = WatchProgress("tt1234567:2:5", MediaType.SERIES, "Episode", showTitle = "Saved series", updatedAt = 20)
        val saved = WatchlistItem("42", MediaType.SERIES, 42, "tt1234567", "Saved series", addedAt = 10)
        val seed = watchingShowSeeds(listOf(progress), emptyList(), listOf(saved)).single()
        assertEquals(42, seed.show.tmdbId)
        assertEquals("tt1234567", seed.show.imdbId)
        assertEquals(setOf("42", "tt1234567:2:5"), seed.mediaIds)
        assertEquals(20L, seed.activityAt)
    }

    @Test fun providerEpisodeSchedulesWorkWithTmdbDisabled() = runTest {
        var tmdbCalls = 0
        val resolved = resolveWatchingMetadata(show, listOf(provider), { _, type, id ->
            assertEquals("series", type); assertEquals("tt1234567", id)
            StremioMeta(id, "series", "Saved series", videos = listOf(StremioVideo("$id:2:5", "Next", 2, 5, "2026-10-27T12:00:00Z")))
        }, tmdbAllowed = false, fetchTmdb = { tmdbCalls++; error("Disabled provider called") })
        val schedule = watchingSchedule(resolved) { _, _ -> error("Embedded extension episodes must be used") }
        assertEquals(0, tmdbCalls)
        assertEquals(5, schedule.episodes.single().episode.episodeNumber)
        assertEquals(LocalDate(2026, 10, 27), schedule.episodes.single().date)
    }

    @Test fun disabledMetadataProviderIsNeverRequested() = runTest {
        val resolved = resolveWatchingMetadata(show, listOf(provider.copy(isEnabled = false)),
            { _, _, _ -> error("Disabled extension called") }, false, { error("TMDB called") })
        assertTrue(resolved.seasons.isEmpty())
    }

    @Test fun oneFailedSeasonDoesNotDiscardOtherEpisodeSchedules() = runTest {
        val series = show.copy(tmdbId = 42, seasons = listOf(Season(1, 2), Season(2, 1)))
        val schedule = watchingSchedule(series) { _, season ->
            if (season == 1) error("Season unavailable")
            Season(2, 1, episodes = listOf(Episode(1, airDate = "2026-10-27")))
        }
        assertEquals(setOf(1), schedule.failedSeasons)
        assertEquals(2, schedule.episodes.single().season)
    }

    @Test fun scheduleCancellationIsNeverReportedAsAnEmptySeason() = runTest {
        assertFailsWith<CancellationException> {
            watchingSchedule(show.copy(tmdbId = 42, seasons = listOf(Season(1, 1)))) { _, _ -> throw CancellationException() }
        }
    }

    @Test fun offlineResumeUsesOnlyTheSavedEpisodeAndNeverResurrectsCompletedEpisodes() {
        val progress = WatchProgress(show.id, MediaType.SERIES, show.title, positionMs = 120_000,
            durationMs = 1_200_000, seasonNumber = 2, episodeNumber = 5)
        val resume = assertNotNull(savedWatchingResume(show, progress))
        assertEquals(2, resume.season); assertEquals(5, resume.episode.episodeNumber)
        assertEquals(120_000L, resume.positionMs); assertNull(resume.date)
        assertNull(savedWatchingResume(show, progress, setOf(2 to 5)))
        assertNull(savedWatchingResume(show, progress.copy(verifiedWatched = true)))
        assertNull(savedWatchingResume(show, progress.copy(episodeNumber = null)))
    }

    @Test fun failedImdbRefreshRetainsCachedRowsAfterTmdbIdentityEnrichment() {
        val value = WatchingEpisode(show.copy(tmdbId = 42), 2, Episode(5), null)
        val state = WatchingHubState(upNext = listOf(value))
        val result = state.withCompletedSchedules(listOf(null to emptyList()), emptyMap(), false, 1,
            failedShowKeys = setOf("imdb:tt1234567"))
        assertEquals(listOf(value), result.upNext)
    }

    @Test fun directFilesAndSportsBookmarksDoNotBecomeTelevisionSchedules() {
        val rows = listOf("direct:episode:42", "sports:42", "https://files.test/42").map {
            WatchProgress(it, MediaType.SERIES, "File", seasonNumber = 1, episodeNumber = 2)
        }
        assertTrue(watchingShowSeeds(rows, emptyList(), emptyList()).isEmpty())
    }

    @Test fun duplicateResolvedIdentitiesKeepOneResumeRow() {
        val numeric = WatchingEpisode(show.copy(id = "42", tmdbId = 42), 2, Episode(5), null)
        val provider = numeric.copy(show = show.copy(tmdbId = 42), resume = true, positionMs = 120_000L)
        val result = WatchingHubState().withCompletedSchedules(listOf(numeric to emptyList(), provider to emptyList()), emptyMap(), false, 0)
        assertEquals(listOf(provider), result.upNext)
    }
}
