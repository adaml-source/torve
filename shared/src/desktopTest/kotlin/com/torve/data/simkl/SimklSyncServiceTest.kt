package com.torve.data.simkl

import com.torve.data.PersonalDeviceFixture
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SimklSyncServiceTest {
    private class Remote {
        var revision = "2026-10-06T20:00:00Z"
        var removed = "2026-10-01T20:00:00Z"
        var shows = """{"shows":[{"status":"watching","show":{"title":"The Gentlemen","ids":{"simkl":1,"tmdb":236235}},"seasons":[{"number":2,"episodes":[{"number":1,"watched_at":"2026-10-06T19:00:00Z"},{"number":2,"watched_at":"2026-10-06T20:00:00Z"}]}]},{"status":"plantowatch","show":{"title":"Another show","ids":{"simkl":2,"tmdb":20}}}]}"""
        var libraries = 0
        var beforeLibrary: (() -> Unit)? = null
        fun client() = HttpClient(MockEngine { request ->
            val response = if (request.url.encodedPath == "/sync/activities")
                """{"all":"$revision","tv_shows":{"all":"$revision","removed_from_list":"$removed"},"movies":{"all":"2026-10-01T20:00:00Z"},"anime":{"all":"2026-10-01T20:00:00Z"}}"""
            else {
                libraries++; beforeLibrary?.invoke()
                if (request.url.encodedPath.endsWith("/shows")) shows else "{}"
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) } }
    }
    private fun service(device: PersonalDeviceFixture) = SimklSyncService(SimklClient(device.http), device.database,
        device.preferences, device.secrets, device.ids, SettingsRefreshNotifier())

    @Test fun unchangedActivitiesSkipLibraryDownloadsAndPreserveTheExactImportedEpisodeProgress() = runTest {
        val remote = Remote()
        PersonalDeviceFixture(remote.client()).use { device ->
            device.signIn(); device.secrets.put(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, "test-simkl-token")
            val sync = service(device)
            assertTrue(sync.sync(true)); val downloads = remote.libraries
            assertTrue(sync.sync(true)); assertEquals(downloads, remote.libraries)
            assertEquals(listOf(2L, 1L), device.database.torveQueries.getAllHistory(device.ids.currentDataUserId()).executeAsList().map { it.episode_number })
            val progress = device.database.torveQueries.getAllProgress(device.ids.currentDataUserId()).executeAsList().single()
            assertEquals(2L, progress.season_number); assertEquals(2L, progress.episode_number)
            assertEquals("20", device.database.torveQueries.getAllWatchlist(device.ids.currentDataUserId()).executeAsList().single().media_id)
        }
    }

    @Test fun remoteRemovalReconcilesOnlySimklOwnedHistoryAndPreservesNewerLocalPlayback() = runTest {
        val remote = Remote()
        PersonalDeviceFixture(remote.client()).use { device ->
            device.signIn(); device.secrets.put(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, "test-simkl-token")
            val sync = service(device); assertTrue(sync.sync(true))
            val person = device.ids.currentDataUserId()
            device.database.torveQueries.insertHistory(person, "local-episode", "236235", "series", "Local episode", null, null, Long.MAX_VALUE - 1, 100, 2, 3, "The Gentlemen")
            device.database.torveQueries.upsertProgress(person, "236235", "series", "The Gentlemen", null, null, 100, 1000, 2, 3, "The Gentlemen", Long.MAX_VALUE - 1)
            remote.revision = "2026-10-07T10:00:00Z"; remote.removed = remote.revision; remote.shows = "{}"
            assertTrue(sync.sync(true))
            assertEquals(listOf("local-episode"), device.database.torveQueries.getAllHistory(person).executeAsList().map { it.id })
            assertEquals(3L, device.database.torveQueries.getAllProgress(person).executeAsList().single().episode_number)
        }
    }

    @Test fun newerWatchedHistoryCannotReplaceAnUnfinishedEpisodeBookmark() = runTest {
        val remote = Remote()
        PersonalDeviceFixture(remote.client()).use { device ->
            device.signIn(); device.secrets.put(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, "test-simkl-token")
            val person = device.ids.currentDataUserId()
            device.database.torveQueries.upsertProgress(person, "236235", "series", "The Gentlemen", null, null,
                600_000, 2_400_000, 2, 2, "The Gentlemen", 1_000)
            val original = device.database.torveQueries.getProgress(person, "236235").executeAsOne()
            assertTrue(service(device).sync(true))
            assertEquals(original, device.database.torveQueries.getProgress(person, "236235").executeAsOne())
            assertTrue(device.database.torveQueries.getAllHistory(person).executeAsList().any { it.episode_number == 2L })
        }
    }

    @Test fun removingTheImportedWatchedMarkRetainsTheOriginalPartialResume() = runTest {
        val remote = Remote()
        PersonalDeviceFixture(remote.client()).use { device ->
            device.signIn(); device.secrets.put(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, "test-simkl-token")
            val person = device.ids.currentDataUserId()
            device.database.torveQueries.upsertProgress(person, "236235", "series", "The Gentlemen", null, null,
                600_000, 2_400_000, 2, 2, "The Gentlemen", 1_000)
            val original = device.database.torveQueries.getProgress(person, "236235").executeAsOne()
            val sync = service(device)
            assertTrue(sync.sync(true))
            remote.revision = "2026-10-07T10:00:00Z"; remote.removed = remote.revision; remote.shows = "{}"
            assertTrue(sync.sync(true))
            assertEquals(original, device.database.torveQueries.getProgress(person, "236235").executeAsOne())
            assertTrue(device.database.torveQueries.getAllHistory(person).executeAsList().isEmpty())
        }
    }

    @Test fun newerCompletedHistoryStillAdvancesAnExistingCompletedBookmark() = runTest {
        val remote = Remote()
        PersonalDeviceFixture(remote.client()).use { device ->
            device.signIn(); device.secrets.put(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, "test-simkl-token")
            val person = device.ids.currentDataUserId()
            device.database.torveQueries.upsertProgress(person, "236235", "series", "The Gentlemen", null, null,
                2_400_000, 2_400_000, 2, 1, "The Gentlemen", 1_000)
            assertTrue(service(device).sync(true))
            assertEquals(2L, device.database.torveQueries.getProgress(person, "236235").executeAsOne().episode_number)
        }
    }

    @Test fun profileSwitchDuringANetworkRequestCannotImportIntoTheNextPersonsLibrary() = runTest {
        val remote = Remote()
        PersonalDeviceFixture(remote.client()).use { device ->
            device.signIn(); device.secrets.put(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, "test-simkl-token")
            device.database.torveQueries.insertProfile(PersonalDeviceFixture.ACCOUNT, "kids", "Kids", 1, 0, null, null, 2)
            remote.beforeLibrary = { device.select("kids") }
            val sync = service(device)
            val result = runCatching { sync.sync(true) }
            assertTrue(result.getOrNull() != true)
            assertEquals(device.ids.currentDataUserId(), sync.state.value.personalDataUserId)
            assertNull(sync.state.value.lastSyncedAt)
            assertFalse(sync.state.value.running)
            assertTrue(device.database.torveQueries.getAllHistory(device.ids.currentDataUserId()).executeAsList().isEmpty())
            assertTrue(device.database.torveQueries.getAllHistory(PersonalDeviceFixture.ACCOUNT).executeAsList().isEmpty())
        }
    }

    @Test fun failedSimklMutationsSurviveRestartButAreNeverReplayedToAnotherConnection() = runTest {
        val remote = Remote()
        PersonalDeviceFixture(remote.client()).use { device ->
            device.signIn()
            val body = simklEpisodeBody(SimklIds(tmdb = 236235), 2, 3)
            SimklMutationJournal(device.preferences, device.secrets, device.ids).submit("connection-a", "/sync/history", body) { _, _ -> error("Offline") }
            val reloaded = SimklMutationJournal(device.preferences, device.secrets, device.ids)
            val sent = mutableListOf<SimklSyncBody>()
            assertTrue(reloaded.flush("connection-a") { _, value -> sent += value })
            assertEquals(listOf(body), sent)
            reloaded.submit("connection-a", "/sync/history", body) { _, _ -> error("Offline") }
            assertTrue(reloaded.flush("connection-b") { _, _ -> error("Wrong account replay") })
        }
    }
}
