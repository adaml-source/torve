package com.torve.data.stats

import com.torve.domain.model.MediaType
import com.torve.domain.stats.RuntimeConfidence
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchSessionSource
import com.torve.domain.stats.WatchSessionStatus
import com.torve.domain.stats.WatchStatsFilters
import com.torve.domain.stats.WatchStatsRepository
import com.torve.domain.stats.WatchStatsSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchSessionRecorderTest {
    @Test
    fun startAndProgressPlaybackUpdatesMeasuredSession() = runTest {
        val repo = FakeWatchStatsRepository()
        val recorder = WatchSessionRecorder(repo) { "user-a" }
        val id = recorder.startPlayerSession(movieIdentity(), startedAt = 1_000L)

        recorder.updatePlayerSessionProgress(id, positionMs = 600_000L, durationMs = 7_200_000L, updatedAt = 2_000L)

        val session = repo.sessions.getValue(id)
        assertEquals(WatchSessionStatus.PARTIAL, session.status)
        assertEquals(600_000L, session.maxPositionMs)
        assertEquals(7_200_000L, session.durationMs)
        assertEquals(RuntimeConfidence.MEASURED, session.runtimeConfidence)
        assertEquals("poster", session.posterUrl)
        assertEquals("backdrop", session.backdropUrl)
    }

    @Test
    fun finalPlaybackStatusUsesAbandonedPartialAndCompletedRules() = runTest {
        val repo = FakeWatchStatsRepository()
        val recorder = WatchSessionRecorder(repo) { "user-a" }
        val shortId = recorder.startPlayerSession(movieIdentity("short"), startedAt = 1L)
        val lowPercentId = recorder.startPlayerSession(movieIdentity("low"), startedAt = 2L)
        val partialId = recorder.startPlayerSession(movieIdentity("partial"), startedAt = 3L)
        val completedId = recorder.startPlayerSession(movieIdentity("done"), startedAt = 4L)

        recorder.finishPlayerSession(shortId, positionMs = 60_000L, durationMs = 7_200_000L, endedAt = 10L)
        recorder.finishPlayerSession(lowPercentId, positionMs = 240_000L, durationMs = 7_200_000L, endedAt = 10L)
        recorder.finishPlayerSession(partialId, positionMs = 600_000L, durationMs = 7_200_000L, endedAt = 10L)
        recorder.finishPlayerSession(completedId, positionMs = 6_120_000L, durationMs = 7_200_000L, endedAt = 10L)

        assertEquals(WatchSessionStatus.ABANDONED, repo.sessions.getValue(shortId).status)
        assertEquals(0L, repo.sessions.getValue(shortId).countedWatchMs)
        assertEquals(WatchSessionStatus.ABANDONED, repo.sessions.getValue(lowPercentId).status)
        assertEquals(WatchSessionStatus.PARTIAL, repo.sessions.getValue(partialId).status)
        assertEquals(600_000L, repo.sessions.getValue(partialId).countedWatchMs)
        assertEquals(WatchSessionStatus.COMPLETED, repo.sessions.getValue(completedId).status)
        assertEquals(7_200_000L, repo.sessions.getValue(completedId).countedWatchMs)
    }

    @Test
    fun manualCompletedUsesEstimatedOrUnknownRuntime() = runTest {
        val repo = FakeWatchStatsRepository()
        val recorder = WatchSessionRecorder(repo) { "user-a" }
        val estimated = recorder.recordManualCompleted(movieIdentity("manual-estimated"), eventAt = 1L, runtimeMs = 5_400_000L)
        val unknown = recorder.recordManualCompleted(movieIdentity("manual-unknown"), eventAt = 2L, runtimeMs = null)

        assertEquals(WatchSessionStatus.MANUAL_COMPLETED, repo.sessions.getValue(estimated).status)
        assertEquals(WatchSessionSource.MANUAL, repo.sessions.getValue(estimated).source)
        assertEquals(RuntimeConfidence.ESTIMATED, repo.sessions.getValue(estimated).runtimeConfidence)
        assertEquals(5_400_000L, repo.sessions.getValue(estimated).countedWatchMs)

        assertEquals(RuntimeConfidence.UNKNOWN, repo.sessions.getValue(unknown).runtimeConfidence)
        assertEquals(0L, repo.sessions.getValue(unknown).countedWatchMs)
    }

    @Test
    fun importedCompletedPreservesEpisodeIdentityAndNeverUsesMeasuredConfidence() = runTest {
        val repo = FakeWatchStatsRepository()
        val recorder = WatchSessionRecorder(repo) { "user-a" }
        val id = recorder.recordImportedCompleted(
            identity = WatchSessionMediaIdentity(
                mediaId = "show-1",
                mediaType = MediaType.SERIES,
                title = "Pilot",
                showId = "show-1",
                showTitle = "The Show",
                seasonNumber = 1,
                episodeNumber = 1,
                tmdbId = 123,
                imdbId = "tt1234567",
            ),
            source = WatchSessionSource.TRAKT,
            eventAt = 1L,
            importDiscriminator = "trakt-1",
            runtimeMs = null,
        )

        val session = repo.sessions.getValue(id)
        assertEquals(WatchSessionStatus.IMPORTED_COMPLETED, session.status)
        assertEquals(WatchSessionSource.TRAKT, session.source)
        assertEquals(RuntimeConfidence.UNKNOWN, session.runtimeConfidence)
        assertEquals(1, session.seasonNumber)
        assertEquals(1, session.episodeNumber)
        assertTrue(session.id.contains("s1"))
        assertTrue(session.id.contains("e1"))
    }

    @Test
    fun sessionIdsIncludeUserSourceMediaEpisodeAndEvent() = runTest {
        val repo = FakeWatchStatsRepository()
        val recorder = WatchSessionRecorder(repo) { "user-a" }
        val id = recorder.startPlayerSession(
            WatchSessionMediaIdentity(
                mediaId = "show-1",
                mediaType = MediaType.SERIES,
                title = "Episode",
                showId = "show-1",
                seasonNumber = 2,
                episodeNumber = 3,
            ),
            startedAt = 99L,
        )

        assertTrue(id.contains("user-a"))
        assertTrue(id.contains(WatchSessionSource.TORVE_PLAYER.name))
        assertTrue(id.contains("show-1"))
        assertTrue(id.contains("s2"))
        assertTrue(id.contains("e3"))
        assertTrue(id.contains("99"))
    }

    @Test
    fun episodeRolloverKeepsCurrentAndNextSessionsSeparate() = runTest {
        val repo = FakeWatchStatsRepository()
        val recorder = WatchSessionRecorder(repo) { "user-a" }
        val first = recorder.startPlayerSession(
            WatchSessionMediaIdentity(
                mediaId = "show-1",
                mediaType = MediaType.SERIES,
                title = "S01E01",
                showId = "show-1",
                showTitle = "The Show",
                seasonNumber = 1,
                episodeNumber = 1,
            ),
            startedAt = 1_000L,
        )

        recorder.finishPlayerSession(first, positionMs = 2_700_000L, durationMs = 3_000_000L, endedAt = 4_000L)
        val second = recorder.startPlayerSession(
            WatchSessionMediaIdentity(
                mediaId = "show-1",
                mediaType = MediaType.SERIES,
                title = "S01E02",
                showId = "show-1",
                showTitle = "The Show",
                seasonNumber = 1,
                episodeNumber = 2,
            ),
            startedAt = 4_000L,
        )

        assertTrue(first != second)
        assertEquals(2, repo.sessions.size)
        assertEquals(WatchSessionStatus.COMPLETED, repo.sessions.getValue(first).status)
        assertEquals(1, repo.sessions.getValue(first).episodeNumber)
        assertEquals(WatchSessionStatus.STARTED, repo.sessions.getValue(second).status)
        assertEquals(2, repo.sessions.getValue(second).episodeNumber)
    }

    private fun movieIdentity(id: String = "movie-1") =
        WatchSessionMediaIdentity(
            mediaId = id,
            mediaType = MediaType.MOVIE,
            title = id,
            posterUrl = "poster",
            backdropUrl = "backdrop",
            tmdbId = 101,
            imdbId = "tt0000101",
        )

    private class FakeWatchStatsRepository : WatchStatsRepository {
        val sessions = linkedMapOf<String, WatchSession>()
        override suspend fun getSessions(filters: WatchStatsFilters): List<WatchSession> =
            sessions.values.filter(filters::matches)

        override suspend fun getAggregation(filters: WatchStatsFilters): WatchStatsSummary =
            WatchStatsSummary(recentActivity = getSessions(filters))

        override suspend fun upsertSession(session: WatchSession) {
            sessions[session.id] = session
        }

        override suspend fun updateSessionProgress(
            id: String,
            endedAt: Long?,
            status: WatchSessionStatus,
            durationMs: Long?,
            maxPositionMs: Long,
            countedWatchMs: Long,
            completionPercent: Double,
            runtimeConfidence: RuntimeConfidence,
            updatedAt: Long,
        ) {
            val existing = sessions[id]
            assertNotNull(existing)
            sessions[id] = existing.copy(
                endedAt = endedAt,
                status = status,
                durationMs = durationMs,
                maxPositionMs = maxPositionMs,
                countedWatchMs = countedWatchMs,
                completionPercent = completionPercent,
                runtimeConfidence = runtimeConfidence,
                updatedAt = updatedAt,
            )
        }

        override suspend fun runBackfillIfNeeded() = Unit
        override suspend fun clearForCurrentUser() = sessions.clear()
        override suspend fun clearForMedia(mediaId: String) {
            sessions.entries.removeAll { it.value.mediaId == mediaId }
        }
    }
}

private fun runTest(block: suspend () -> Unit) = kotlinx.coroutines.test.runTest { block() }
