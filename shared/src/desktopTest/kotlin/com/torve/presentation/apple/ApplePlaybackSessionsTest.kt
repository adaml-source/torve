package com.torve.presentation.apple

import com.torve.data.PersonalDeviceFixture
import com.torve.data.addon.*
import com.torve.domain.model.*
import com.torve.domain.repository.WatchProgressRepository
import com.torve.presentation.detail.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.*

class ApplePlaybackSessionsTest {
    private suspend fun continuationFixture(check: suspend (PersonalDeviceFixture, ApplePlaybackSessions, String, ParsedStream) -> Unit) {
        PersonalDeviceFixture(HttpClient(MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) })).use { device ->
            device.signIn()
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "other", "Other", 1, 0, null, null, 2)
            val repo = Proxy.newProxyInstance(WatchProgressRepository::class.java.classLoader, arrayOf(WatchProgressRepository::class.java)) { _, _, _ -> Unit } as WatchProgressRepository
            val sessions = ApplePlaybackSessions(device.ids, repo)
            val stream = ParsedStream("Fixture", "1080p", "Show.S01E10.H264", directUrl = "https://fixture.example/10.mp4",
                languages = listOf("DE"), bingeGroup = "hd-de", addonBaseUrl = "https://addon.example/config")
            val state = DetailUiState(mediaItem = MediaItem("42", type = MediaType.SERIES, title = "Series"),
                streamContextSeason = 1, streamContextEpisode = 10, streams = listOf(stream))
            val token = assertNotNull(sessions.begin(state))
            val sourceSession = SourceContinuationSession().apply {
                stageResolvedSource(stream, stream.directUrl!!, origin = ContinuationSelectionOrigin.MANUAL)
            }
            val streams = Proxy.newProxyInstance(com.torve.domain.repository.StreamRepository::class.java.classLoader,
                arrayOf(com.torve.domain.repository.StreamRepository::class.java)) { _, _, _ -> error("No network expected") } as com.torve.domain.repository.StreamRepository
            AppleSourceRecovery(device.ids, sessions, token, streams, { StreamPreferences() }, { emptyMap() }, sourceSession)
                .started(stream.directUrl!!, 1_800_000)
            check(device, sessions, token, stream)
        }
    }

    @Test fun nextEpisodeKeepsConfirmedSourceAfterTheOldPlayerStops() = runTest {
        continuationFixture { _, sessions, token, stream ->
            val next = sessions.prepareContinuation(token, sessions.identity(token)!!.copy(season = 2, episode = 1))
            val ticket = assertNotNull(next.continuationToken)
            sessions.save(token, 1_799_000, 1_800_000, finish = true)
            assertNull(sessions.identity(token))
            val request = assertNotNull(sessions.consumeContinuation(ticket, "42", 2, 1))
            val profile = assertNotNull(request.source)
            assertEquals(stream.bingeGroup, profile.bingeGroup)
            assertEquals(1080, profile.resolutionHeight)
            assertEquals(setOf("de"), profile.languages)
            assertEquals(stream.addonBaseUrl, profile.addonBaseUrl)
            assertNull(sessions.consumeContinuation(ticket, "42", 2, 1), "A handoff is single use")
        }
    }

    @Test fun nextEpisodeSourceCannotCrossProfilesOrTargets() = runTest {
        continuationFixture { device, sessions, token, _ ->
            val target = sessions.identity(token)!!.copy(episode = 11)
            val wrongEpisode = assertNotNull(sessions.prepareContinuation(token, target).continuationToken)
            assertNull(sessions.consumeContinuation(wrongEpisode, "42", 1, 12))
            val wrongShow = assertNotNull(sessions.prepareContinuation(token, target).continuationToken)
            assertNull(sessions.consumeContinuation(wrongShow, "43", 1, 11))
            val otherProfile = assertNotNull(sessions.prepareContinuation(token, target).continuationToken)
            device.select("other")
            assertNull(sessions.consumeContinuation(otherProfile, "42", 1, 11))
            assertNull(sessions.prepareContinuation(token, target).continuationToken)
        }
    }

    @Test fun handoffDoesNotReuseAnUnrelatedSourceOrPreviousEpisode() = runTest {
        continuationFixture { _, sessions, token, stream ->
            val identity = sessions.identity(token)!!
            assertNull(sessions.prepareContinuation(token, identity.copy(episode = 9)).continuationToken)
            assertNull(sessions.prepareContinuation(token, identity.copy(mediaId = "43", episode = 11)).continuationToken)
            sessions.recordSource(token, SourceProfile.from(stream.copy(directUrl = "https://unrelated.example/video"), seasonNumber = 1, episodeNumber = 10))
            val ticket = assertNotNull(sessions.prepareContinuation(token, identity.copy(episode = 11)).continuationToken)
            assertNull(assertNotNull(sessions.consumeContinuation(ticket, "42", 1, 11)).source)
        }
    }

    @Test fun exactEpisodeResumeIsSavedOnlyForTheProfileThatOpenedThePlayer() = runTest {
        PersonalDeviceFixture(HttpClient(MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) })).use { device ->
            device.signIn()
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "other", "Other", 1, 0, null, null, 2)
            val saved = mutableListOf<WatchProgress>()
            val repo = Proxy.newProxyInstance(WatchProgressRepository::class.java.classLoader, arrayOf(WatchProgressRepository::class.java)) { _, method, args ->
                if (method.name == "savePlaybackProgress") saved.add(args[0] as WatchProgress)
                if (method.name == "playbackCoverage") null else Unit
            } as WatchProgressRepository
            val sessions = ApplePlaybackSessions(device.ids, repo)
            val item = MediaItem("42", type = MediaType.SERIES, title = "Series", tmdbId = 42)
            val state = DetailUiState(mediaItem = item, streamContextSeason = 2, streamContextEpisode = 3,
                watchProgress = WatchProgress("42", MediaType.SERIES, "Series", positionMs = 60000, durationMs = 600000, seasonNumber = 2, episodeNumber = 3))
            val token = assertNotNull(sessions.begin(state))
            assertEquals(60000, sessions.startPositionMs(token))
            val tracker = assertNotNull(sessions.viewingTracker(token))
            tracker.observe(60000, 600000, 0, true, true, 1.0)
            tracker.observe(61000, 600000, 1000, true, true, 1.0)
            sessions.save(token, 65000, 600000, false)
            assertEquals(2, saved.single().seasonNumber); assertEquals(3, saved.single().episodeNumber)
            device.select("other")
            sessions.save(token, 70000, 600000, true)
            assertEquals(1, saved.size)
            device.select("default")
            assertEquals(0, sessions.startPositionMs(token))
        }
    }
}
