package com.torve.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.data.auth.UserIdProvider
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.simkl.SimklClient
import com.torve.data.stats.insertOrReplaceWatchSession
import com.torve.data.trakt.TraktClient
import com.torve.data.trakt.TraktTokens
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.data.trakt.repo.TraktSyncRepositoryImpl
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchHistoryEntry
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchSessionSource
import com.torve.domain.stats.WatchSessionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatchHistorySeriesSyncTest {
    @Test
    fun missingSeasonTwoEpisodesArriveAndNotifyTheOpenEpisodeList() = runTest {
        fixture().use { f ->
            f.seed("trakt_old", episode = 1)
            f.seed("other-show", mediaId = "999", episode = 2)
            f.seed("other-user", userId = "another-account", episode = 2)
            val initial = CompletableDeferred<Unit>()
            val emissions = mutableListOf<Set<Int>>()
            val observer = launch {
                f.history.observeForMedia(SHOW.id).take(2).collect { entries ->
                    emissions += entries.mapNotNull { it.episodeNumber }.toSet()
                    initial.complete(Unit)
                }
            }
            initial.await()

            f.history.refreshSeries(SHOW)
            observer.join()

            assertEquals(listOf(setOf(1), setOf(1, 2, 3)), emissions)
            assertEquals(1, f.history.getForMedia("999").size)
            assertEquals(1, f.db.torveQueries.getHistoryForMedia("another-account", SHOW.id).executeAsList().size)
            assertEquals(listOf("/shows/tt13210838/progress/watched"), f.remote.reads)
        }
    }

    @Test
    fun offlineRefreshRetainsCachedMarkersAndRetriesAfterTheCooldown() = runTest {
        fixture().use { f ->
            f.seed("trakt_old", episode = 1)
            f.remote.readFailure = true
            assertFailsWith<Exception> { f.history.refreshSeries(SHOW) }
            f.history.refreshSeries(SHOW)
            assertEquals(setOf(1), f.episodes())
            assertEquals(1, f.remote.reads.size)

            f.remote.readFailure = false
            f.clock.ms += 60_001L
            f.history.refreshSeries(SHOW)
            assertEquals(setOf(1, 2, 3), f.episodes())
        }
    }

    @Test
    fun malformedRemoteDataCannotEraseCachedWatchedFlags() = runTest {
        fixture().use { f ->
            f.seed("trakt_old", episode = 1)
            f.remote.bodyOverride = "{}"
            assertFailsWith<Exception> { f.history.refreshSeries(SHOW) }
            assertEquals(setOf(1), f.episodes())
        }
    }

    @Test
    fun overlappingEntryAndResumeRefreshesMakeOneRequest() = runTest {
        fixture().use { f ->
            (1..12).map { async { f.history.refreshSeries(SHOW) } }.awaitAll()
            assertEquals(1, f.remote.reads.size)
            assertEquals(setOf(1, 2, 3), f.episodes())
        }
    }

    @Test
    fun switchingTraktConnectionsDiscardsTheOldResponse() = runTest {
        fixture().use { f ->
            f.seed("trakt_old", episode = 1)
            f.remote.beforeRead = { f.api.resetForAccountChange() }
            f.history.refreshSeries(SHOW)
            assertEquals(setOf(1), f.episodes())
        }
    }

    @Test
    fun pendingOfflineRemovalIsNotResurrectedByRemoteHistory() = runTest {
        fixture().use { f ->
            f.seed("manual", episode = 2)
            f.seed("trakt_old", episode = 2)
            f.queue.enqueueEpisodeHistoryRemove(SHOW.tmdbId!!, SHOW.imdbId, 2, 2)
            f.history.deleteEpisode(SHOW.id, 2, 2)
            f.remote.writeFailure = true
            f.history.refreshSeries(SHOW)

            assertEquals(setOf(1, 3), f.episodes())
            assertEquals(false, f.queue.pendingEpisodeHistoryChanges(SHOW.tmdbId!!)[2 to 2])
        }
    }

    @Test
    fun removingAnEpisodeClearsOnlyItsEstimatedSessionsAndKeepsMeasuredPlayback() = runTest {
        fixture().use { f ->
            f.seed("manual", episode = 2)
            val person = UserIdProvider.SIGNED_OUT_USER_ID
            val actual = WatchSession("player", person, SHOW.id, MediaType.SERIES, "Episode 2",
                seasonNumber = 2, episodeNumber = 2, startedAt = 1_000,
                source = WatchSessionSource.TORVE_PLAYER, status = WatchSessionStatus.PARTIAL,
                durationMs = 2_400_000, maxPositionMs = 600_000, createdAt = 1_000, updatedAt = 2_000)
            f.db.torveQueries.insertOrReplaceWatchSession(actual)
            for (source in WatchSessionSource.entries.filter { it != WatchSessionSource.TORVE_PLAYER }) {
                f.db.torveQueries.insertOrReplaceWatchSession(actual.copy(id = "estimate-$source", source = source,
                    status = WatchSessionStatus.IMPORTED_COMPLETED))
            }
            f.db.torveQueries.insertOrReplaceWatchSession(actual.copy(id = "other-episode",
                source = WatchSessionSource.MANUAL, episodeNumber = 3))
            f.db.torveQueries.insertOrReplaceWatchSession(actual.copy(id = "other-media",
                source = WatchSessionSource.MANUAL, mediaId = "other-show"))
            f.db.torveQueries.insertOrReplaceWatchSession(actual.copy(id = "other-person",
                source = WatchSessionSource.MANUAL, userId = "another-account"))

            f.history.deleteEpisode(SHOW.id, 2, 2)

            assertTrue(f.episodes().isEmpty())
            val remaining = f.db.torveQueries.selectWatchSessionsForUser(person).executeAsList()
            assertEquals(setOf("player", "other-episode", "other-media"), remaining.map { it.id }.toSet())
            assertEquals(600_000L, remaining.single { it.id == "player" }.max_position_ms)
            assertEquals("other-person", f.db.torveQueries.selectWatchSessionsForUser("another-account")
                .executeAsOne().id)
        }
    }

    @Test
    fun anEpisodeMarkingHasADurableRetryBeforeItsFirstNetworkAttempt() = runTest {
        fixture().use { f ->
            f.remote.writeFailure = true
            f.remote.beforeWrite = {
                assertEquals(true, f.queue.pendingEpisodeHistoryChanges(SHOW.tmdbId!!)[2 to 2])
                assertEquals(setOf(2), f.episodes())
            }
            f.history.record(
                WatchHistoryEntry("${SHOW.id}_s2e2", SHOW.id, "SERIES", "Episode 2", null, null,
                    f.clock.ms, 0, 2, 2, SHOW.title),
            )
            assertEquals(setOf(2), f.episodes())
            assertEquals(1, f.db.torveQueries.getAllTraktQueue().executeAsList().size)
        }
    }

    @Test
    fun concurrentFlushesCannotSendTheSameWatchedMarkingTwice() = runTest {
        fixture().use { f ->
            f.queue.enqueueEpisodeHistoryAdd(SHOW.tmdbId!!, SHOW.imdbId, 2, 2)
            (1..8).map { async { f.queue.flushPendingWrites() } }.awaitAll()
            assertEquals(1, f.remote.writes)
            assertTrue(f.db.torveQueries.getAllTraktQueue().executeAsList().isEmpty())
        }
    }

    @Test
    fun aLocalChangeDuringTheRemoteFetchWinsOverTheOlderSnapshot() = runTest {
        fixture().use { f ->
            f.seed("trakt_old", episode = 1)
            f.remote.beforeRead = { f.seed("local-new", episode = 4) }
            f.history.refreshSeries(SHOW)
            assertEquals(setOf(1, 4), f.episodes())
        }
    }

    @Test
    fun refreshingAnUnconnectedDeviceUsesOnlyItsLocalHistory() = runTest {
        fixture().use { f ->
            f.seed("manual", episode = 1)
            f.tokens.clear()
            f.history.refreshSeries(SHOW)
            assertEquals(setOf(1), f.episodes())
            assertTrue(f.remote.reads.isEmpty())
        }
    }

    private suspend fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        val db = TorveDatabase(driver)
        val remote = Remote()
        val json = Json { ignoreUnknownKeys = true }
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/progress/watched") -> {
                    remote.reads += path
                    assertEquals("true", request.url.parameters["hidden"])
                    assertEquals("true", request.url.parameters["specials"])
                    remote.beforeRead()
                    respond(remote.bodyOverride ?: PROGRESS,
                        if (remote.readFailure) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
                path == "/sync/history" || path == "/sync/history/remove" -> {
                    remote.writes++
                    remote.beforeWrite()
                    respond("{}", if (remote.writeFailure) HttpStatusCode.BadRequest else HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
                else -> error("Unexpected request: $path")
            }
        }) { install(ContentNegotiation) { json(json) } }
        val tokens = TraktTokenStore(MemorySecrets(), json)
        tokens.write(TraktTokens("test-access", "test-refresh", 0, 0))
        val api = TraktAuthorizedApi(TraktClient(http, json = json), tokens)
        val queue = TraktSyncRepositoryImpl(db, api, tokens, json)
        val auth = AuthClient(EmptySettings(), EmptySecureStorage(), http, { "https://example.test" },
            { DeviceRegistrationDto(installation_id = "test-install", device_name = "Test",
                device_type = "desktop", platform = "desktop") })
        val clock = TestClock()
        val history = WatchHistoryRepositoryImpl(db, api, TmdbApiClient(http), queue,
            SimklClient(http), MemorySecrets(), UserIdProvider { auth }, clock = clock)
        return Fixture(db, history, api, queue, tokens, remote, clock, http, driver)
    }

    private class Fixture(
        val db: TorveDatabase,
        val history: WatchHistoryRepositoryImpl,
        val api: TraktAuthorizedApi,
        val queue: TraktSyncRepositoryImpl,
        val tokens: TraktTokenStore,
        val remote: Remote,
        val clock: TestClock,
        private val http: HttpClient,
        private val driver: JdbcSqliteDriver,
    ) : java.io.Closeable {
        fun seed(id: String, mediaId: String = SHOW.id, userId: String = UserIdProvider.SIGNED_OUT_USER_ID,
                 episode: Int) {
            db.torveQueries.insertHistory(userId, id, mediaId, "series", SHOW.title, null, null,
                clock.ms - 1, 0, 2, episode.toLong(), SHOW.title)
        }
        suspend fun episodes() = history.getForMedia(SHOW.id).mapNotNull { it.episodeNumber }.toSet()
        override fun close() { http.close(); driver.close() }
    }

    private class Remote {
        val reads = mutableListOf<String>()
        var writes = 0
        var readFailure = false
        var writeFailure = false
        var bodyOverride: String? = null
        var beforeRead: suspend () -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
    }
    private class TestClock(var ms: Long = 1_791_244_800_000L) : Clock {
        override fun now() = Instant.fromEpochMilliseconds(ms)
    }
    private class MemorySecrets : IntegrationSecretStore {
        private val values = mutableMapOf<Pair<IntegrationSecretKey, String?>, String>()
        override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) { values[key to subKey] = value }
        override suspend fun get(key: IntegrationSecretKey, subKey: String?) = values[key to subKey]
        override suspend fun remove(key: IntegrationSecretKey, subKey: String?) { values.remove(key to subKey) }
        override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
        override suspend fun getStorageMode(key: IntegrationSecretKey) = IntegrationStorageMode.DEVICE_ONLY
        override suspend fun clearAllSecrets() { values.clear() }
    }
    private class EmptySettings : DeviceLocalSettingsRepository {
        override suspend fun getString(key: String): String? = null
        override suspend fun setString(key: String, value: String) = Unit
        override suspend fun remove(key: String) = Unit
    }
    private class EmptySecureStorage : SecureStorage {
        override suspend fun getString(key: String): String? = null
        override suspend fun putString(key: String, value: String) = Unit
        override suspend fun remove(key: String) = Unit
    }
    private companion object {
        val SHOW = MediaItem("236235", tmdbId = 236235, imdbId = "tt13210838", type = MediaType.SERIES, title = "The Gentlemen")
        const val PROGRESS = """{"seasons":[{"number":2,"episodes":[{"number":1,"completed":true,"last_watched_at":"2026-10-01T20:00:00Z"},{"number":2,"completed":true,"last_watched_at":"2026-10-02T20:00:00Z"},{"number":3,"completed":true,"last_watched_at":"2026-10-03T20:00:00Z"}]}],"reset_at":null}"""
    }
}
