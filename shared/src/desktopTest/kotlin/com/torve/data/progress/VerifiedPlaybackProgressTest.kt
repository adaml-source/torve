package com.torve.data.progress

import com.torve.data.PersonalDeviceFixture
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.simkl.SimklClient
import com.torve.data.trakt.TraktClient
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.data.trakt.repo.TraktSyncRepositoryImpl
import com.torve.domain.model.*
import com.torve.domain.player.*
import com.torve.presentation.apple.ApplePlaybackSessions
import com.torve.presentation.detail.DetailUiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase

class VerifiedPlaybackProgressTest {
    @Test fun upgradeAddsCoverageWithoutChangingExistingWatchData() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            TorveDatabase.Schema.create(driver)
            val database = TorveDatabase(driver)
            database.torveQueries.upsertProgress("owner", "42", "series", "Fixture", null, null,
                60_000, 60_000, 1, 2, "Fixture", 123)
            driver.execute(null, "DROP TABLE playback_viewing_coverage", 0)
            TorveDatabase.Schema.migrate(driver, 12, 13)
            assertEquals(60_000, database.torveQueries.getProgress("owner", "42").executeAsOne().position_ms)
            assertTrue(database.torveQueries.getInProgress("owner", 20).executeAsList().isEmpty(),
                "Completed legacy TV episodes must not remain in progress")
            assertNull(database.torveQueries.getPlaybackCoverage("owner", "42", "series", 1, 2).executeAsOneOrNull())
        }
    }
    private suspend fun fixture(check: suspend (PersonalDeviceFixture, WatchProgressRepositoryImpl) -> Unit) {
        val json = Json { ignoreUnknownKeys = true }
        val http = HttpClient(MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
            install(ContentNegotiation) { json(json) }
        }
        PersonalDeviceFixture(http).use { device ->
            device.signIn()
            val tokens = TraktTokenStore(device.secrets, json)
            val trakt = TraktAuthorizedApi(TraktClient(http, json), tokens)
            check(device, WatchProgressRepositoryImpl(device.database, trakt, TmdbApiClient(http,
                apiKeyProvider = { "fixture-key" }, resolvedHostProvider = { emptyList() }, diagnostics = {}),
                TraktSyncRepositoryImpl(device.database, trakt, tokens, json, device.ids::currentDataUserId), SimklClient(http), device.secrets, device.ids))
        }
    }
    private fun coverage(watched: Long = 0, expected: Long = 60_000) = PlaybackViewingCoverage(60_000, expected,
        if (watched > 0) listOf(ViewedRange(0, watched)) else emptyList())
    private val movie = WatchProgress("fixture-movie", MediaType.MOVIE, "Fixture", positionMs = 59_000, durationMs = 60_000)

    @Test fun anImportedBookmarkIsNotACompletedViewing() = runTest {
        fixture { device, repo ->
            repo.saveBookmark(movie)
            assertFalse(assertNotNull(repo.getProgress(movie.mediaId)).isWatched())
            assertEquals(1, repo.getInProgress(20).size)
            assertEquals(0, device.database.torveQueries.getHistoryCount(device.ids.currentDataUserId()).executeAsOne())
        }
    }

    @Test fun seekBookmarksStayInProgressAndIncidentalSavesCannotCreateHistory() = runTest {
        fixture { device, repo ->
            repo.savePlaybackProgress(movie, coverage(1_000))
            repo.saveProgress(movie.copy(posterUrl = "https://fixture.example/poster.jpg"))
            repo.markWatchedAtMeaningfulContentEnd(movie)
            assertEquals(0, device.database.torveQueries.getHistoryCount(device.ids.currentDataUserId()).executeAsOne())
            assertEquals(movie.mediaId, repo.getInProgress(20).single().mediaId)
            assertFalse(assertNotNull(repo.getProgress(movie.mediaId)).isWatched())
            assertFalse(repo.getAllProgress().single().isWatched())
        }
    }
    @Test fun onlyVerified85PercentCreatesHistoryAndCompletedEpisodesLeaveInProgress() = runTest {
        fixture { device, repo ->
            val episode = movie.copy(mediaType = MediaType.SERIES, seasonNumber = 1, episodeNumber = 1)
            repo.savePlaybackProgress(episode, coverage(50_000))
            assertEquals(0, device.database.torveQueries.getHistoryCount(device.ids.currentDataUserId()).executeAsOne())
            repo.savePlaybackProgress(episode, coverage(51_000))
            repo.savePlaybackProgress(episode, coverage(52_000))
            assertTrue(repo.getInProgress(20).isEmpty())
            val rows = device.database.torveQueries.getAllHistory(device.ids.currentDataUserId()).executeAsList()
            assertEquals(1, rows.size)
            assertEquals(51_000, rows.single().duration_watched_ms)
            repo.savePlaybackProgress(episode.copy(episodeNumber = 2), coverage(2_000))
            assertEquals(2, repo.getInProgress(20).single().episodeNumber)
        }
    }
    @Test fun mismatchedAndUnknownRuntimeNeverCompleteEvenWithAnEndBookmark() = runTest {
        fixture { device, repo ->
            repo.savePlaybackProgress(movie, coverage(60_000, 0))
            repo.savePlaybackProgress(movie, coverage(60_000, 7_200_000))
            assertEquals(0, device.database.torveQueries.getHistoryCount(device.ids.currentDataUserId()).executeAsOne())
        }
    }
    @Test fun evidenceIsDurableScopedByProfileAndClearedWithProgress() = runTest {
        fixture { device, repo ->
            repo.savePlaybackProgress(movie, coverage(30_000))
            assertEquals(30_000, repo.playbackCoverage(movie)?.watchedMs)
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "other", "Other", 1, 0, null, null, 2)
            device.select("other")
            assertNull(repo.playbackCoverage(movie))
            device.select("default")
            assertEquals(30_000, repo.playbackCoverage(movie)?.watchedMs)
            repo.deleteProgress(movie.mediaId)
            assertNull(repo.playbackCoverage(movie))
        }
    }
    @Test fun explicitManualMarkingStillWorksAfterSeeking() = runTest {
        fixture { device, repo ->
            repo.savePlaybackProgress(movie, coverage(1_000))
            repo.markWatchedManually(movie)
            assertEquals(1, device.database.torveQueries.getHistoryCount(device.ids.currentDataUserId()).executeAsOne())
            assertTrue(repo.getInProgress(20).isEmpty())
        }
    }
    private fun play(tracker: PlaybackViewingTracker, from: Long, to: Long) {
        for (p in from..to step 500) tracker.observe(p, 60_000, p, true, true, 1.0)
    }
    @Test fun appleSessionsRequireDecoderPlaybackAndAccumulateAcrossReopening() = runTest {
        fixture { device, repo ->
            val state = DetailUiState(mediaItem = MediaItem(movie.mediaId, type = MediaType.MOVIE, title = "Fixture", runtime = 1))
            val first = ApplePlaybackSessions(device.ids, repo)
            val a = assertNotNull(first.begin(state))
            first.save(a, 59_000, 60_000, false)
            assertNull(repo.getProgress(movie.mediaId), "Opening without actual playback cannot save an end bookmark")
            play(assertNotNull(first.viewingTracker(a)), 0, 30_000)
            first.save(a, 30_000, 60_000, true)
            val reopened = ApplePlaybackSessions(device.ids, repo)
            val b = assertNotNull(reopened.begin(state.copy(watchProgress = repo.getProgress(movie.mediaId))))
            play(assertNotNull(reopened.viewingTracker(b)), 30_000, 51_000)
            reopened.save(b, 51_000, 60_000, true)
            assertEquals(51_000, repo.playbackCoverage(movie)?.watchedMs)
            assertEquals(1, device.database.torveQueries.getHistoryCount(device.ids.currentDataUserId()).executeAsOne())
        }
    }
    @Test fun aSeriesUsesTheSelectedEpisodesRuntimeRatherThanTheShowAverage() = runTest {
        fixture { device, repo ->
            val state = DetailUiState(mediaItem = MediaItem("fixture-show", type = MediaType.SERIES, title = "Fixture", runtime = 1),
                streamContextSeason = 2, streamContextEpisode = 3,
                seasonDetail = Season(2, 3, episodes = listOf(Episode(3, runtime = 2))))
            val sessions = ApplePlaybackSessions(device.ids, repo)
            val token = assertNotNull(sessions.begin(state))
            play(assertNotNull(sessions.viewingTracker(token)), 0, 60_000)
            sessions.save(token, 60_000, 60_000, true)
            assertEquals(0, device.database.torveQueries.getHistoryCount(device.ids.currentDataUserId()).executeAsOne())
        }
    }
}
