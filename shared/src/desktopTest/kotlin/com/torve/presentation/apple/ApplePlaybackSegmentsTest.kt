package com.torve.presentation.apple

import com.torve.data.PersonalDeviceFixture
import com.torve.data.addon.SourceContinuationSessionStore
import com.torve.domain.model.*
import com.torve.domain.player.*
import com.torve.domain.repository.*
import com.torve.presentation.detail.DetailUiState
import com.torve.presentation.settings.SettingsUiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.*

class ApplePlaybackSegmentsTest {
    private val duration = 600_000L
    private fun segment(type: SegmentType, start: Long, end: Long) = PlaybackSegment(type.name, type, start, end,
        .98, SegmentConfidence.VERY_HIGH, SegmentEvidenceSource.USER_VALIDATED, emptyList<SegmentEvidence>(),
        createdAtEpochMs = 1, updatedAtEpochMs = 1, version = 4)
    private suspend fun fixture(resume: Long = 0, markers: List<PlaybackSegment>, prefs: SettingsUiState,
                                check: suspend (ApplePlaybackSegments, PersonalDeviceFixture) -> Unit) {
        SourceContinuationSessionStore.session.clear()
        PersonalDeviceFixture(HttpClient(MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) })).use { device ->
            device.signIn()
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "other", "Other", 1, 0, null, null, 2)
            val progress = Proxy.newProxyInstance(WatchProgressRepository::class.java.classLoader,
                arrayOf(WatchProgressRepository::class.java)) { _, _, _ -> Unit } as WatchProgressRepository
            val sessions = ApplePlaybackSessions(device.ids, progress)
            val token = assertNotNull(sessions.begin(DetailUiState(mediaItem = MediaItem("42", type = MediaType.SERIES, title = "Series", tmdbId = 42),
                streamContextSeason = 2, streamContextEpisode = 3,
                watchProgress = WatchProgress("42", MediaType.SERIES, "Series", positionMs = resume, durationMs = duration, seasonNumber = 2, episodeNumber = 3))))
            val cache = object : PlaybackSegmentRepository {
                override suspend fun get(canonicalEpisodeId: String, mediaFingerprint: String, analysisVersion: Int) =
                    CachedSegmentAnalysis(canonicalEpisodeId, mediaFingerprint, duration, analysisVersion, "validated", 3, markers, 1, 1)
                override suspend fun put(analysis: CachedSegmentAnalysis) = Unit
                override suspend fun getEpisodeAnalyses(canonicalEpisodeId: String, analysisVersion: Int) = emptyList<CachedSegmentAnalysis>()
            }
            val runtime = ApplePlaybackSegments(device.ids, sessions, token, PlaybackSegmentEngine(cache)) { prefs }
            runtime.analyze(duration, "https://fixture.example/episode.mkv", emptyList())
            check(runtime, device)
        }
    }
    @Test fun pausedPlaybackDoesNotConsumeAutomaticIntroAndManualUndoStopsAnotherAutomaticSkip() = runTest {
        fixture(markers = listOf(segment(SegmentType.INTRO, 60_000, 90_000)), prefs = SettingsUiState(skipIntroMode = SegmentActionMode.AUTOMATIC)) { runtime, _ ->
            assertNull(runtime.update(70_000, duration, false).automaticSeekMs)
            val target = assertNotNull(runtime.update(70_000, duration, true).automaticSeekMs)
            runtime.manualSeek(target, 70_000)
            assertNull(runtime.update(70_000, duration, true).automaticSeekMs)
        }
    }
    @Test fun resumedEpisodeOffersManualSkipWithoutAutomaticallySkippingTheRestoredPosition() = runTest {
        fixture(resume = 70_000, markers = listOf(segment(SegmentType.INTRO, 60_000, 90_000)), prefs = SettingsUiState(skipIntroMode = SegmentActionMode.AUTOMATIC)) { runtime, _ ->
            val decision = runtime.update(70_000, duration, true)
            assertTrue(decision.state.showSkipAction)
            assertNull(decision.automaticSeekMs)
            assertNotNull(runtime.skipAt(70_000, duration))
        }
    }
    @Test fun postCreditSceneAndCancelProtectTheCurrentEpisodeAndProfileSwitchDisablesActions() = runTest {
        val prefs = SettingsUiState(nextEpisodeMode = NextEpisodeMode.AT_CREDITS, autoPlayNextEpisodeEnabled = true,
            playNextDuringCreditsMode = SegmentActionMode.AUTOMATIC, protectPostCreditScenes = true)
        fixture(markers = listOf(segment(SegmentType.CREDITS, 500_000, 540_000), segment(SegmentType.POST_CREDIT, 540_000, 560_000)), prefs = prefs) { runtime, device ->
            val decision = runtime.update(520_000, duration, true)
            assertTrue(decision.showNext)
            assertTrue(decision.state.extraSceneRemains)
            assertFalse(decision.automaticNext)
            assertFalse(decision.state.markWatched)
            runtime.cancelNext()
            assertFalse(runtime.update(520_000, duration, true).showNext)
            device.select("other")
            assertNull(runtime.skipAt(520_000, duration))
            assertFalse(runtime.update(520_000, duration, true).showNext)
        }
    }
}
