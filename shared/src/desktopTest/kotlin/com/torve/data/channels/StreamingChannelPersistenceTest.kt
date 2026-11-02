package com.torve.data.channels

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.data.auth.AuthClient
import com.torve.data.auth.UserIdProvider
import com.torve.db.TorveDatabase
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class StreamingChannelPersistenceTest {
    @Test fun streamedCatalogPreservesEveryRowOrderingAndFirstDuplicate() = runTest {
        fixture(truncatedVod = false).use { f ->
            f.repo.saveXtreamPlaylistConfig("Test", "https://provider.test", "user", "password", "p")
            f.repo.refreshPlaylistCatalog("p")
            assertEquals(5_130L, f.repo.getTotalChannelCount("p"))
            val live = f.repo.getChannelsForCategory("p", "News")
            assertEquals(5_000, live.size)
            assertEquals("Title 0", live.first().name)
            assertEquals("Title 4999", live.last().name)
            assertEquals(130, f.repo.getChannelsForCategory("p", "VOD: Films").size)
            assertEquals(5_000L, f.database.torveQueries.getPlaylist(f.owner, "p").executeAsOne().channel_count)
        }
    }

    @Test fun truncatedAggregateDiscardsItsStagedBatchesBeforeCategoryFallback() = runTest {
        fixture(truncatedVod = true).use { f ->
            f.repo.saveXtreamPlaylistConfig("Test", "https://provider.test", "user", "password", "p")
            f.repo.refreshPlaylistCatalog("p")
            assertEquals(5_003L, f.repo.getTotalChannelCount("p"))
            assertEquals(listOf("Title 9000", "Title 9001", "Title 9002"),
                f.repo.getChannelsForCategory("p", "VOD: Films").map { it.name })
        }
    }

    private fun rows(range: IntRange): String = range.joinToString(",") {
        """{"stream_id":$it,"name":"Title $it","category_id":"1"}"""
    }

    private fun fixture(truncatedVod: Boolean): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        val database = TorveDatabase(driver)
        val values = mutableMapOf<String, String>()
        val storage = object : SecureStorage {
            override suspend fun getString(key: String) = values[key]
            override suspend fun putString(key: String, value: String) { values[key] = value }
            override suspend fun remove(key: String) { values.remove(key) }
        }
        val local = object : DeviceLocalSettingsRepository {
            override suspend fun getString(key: String): String? = null
            override suspend fun setString(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val http = HttpClient(MockEngine { request ->
            val body = when (request.url.parameters["action"]) {
                "get_live_categories" -> """[{"category_id":"1","category_name":"News"}]"""
                "get_vod_categories" -> """[{"category_id":"1","category_name":"Films"}]"""
                "get_series_categories", "get_series" -> "[]"
                "get_live_streams" -> "[${rows(0..4999)},${rows(0..0)}]"
                "get_vod_streams" -> when {
                    request.url.parameters["category_id"] != null -> "[${rows(9000..9002)}]"
                    truncatedVod -> "[${rows(0..80)}" // Two batches saved before the broken response ends.
                    else -> "[${rows(0..129)}]"
                }
                else -> "{}"
            }
            respond(body)
        })
        val auth = AuthClient(local, storage, http, { "https://example.test" }, { error("Unused") })
        val ids = UserIdProvider { auth }
        val repo = ChannelRepositoryImpl(database, http, M3uParser(), EpgParser(),
            XtreamClient(http, Json { ignoreUnknownKeys = true }, streamingHttpClient = http), storage, ids, driver)
        return Fixture(driver, database, http, repo, ids.currentDataUserId())
    }

    private data class Fixture(val driver: JdbcSqliteDriver, val database: TorveDatabase,
        val http: HttpClient, val repo: ChannelRepositoryImpl, val owner: String) : AutoCloseable {
        override fun close() { http.close(); driver.close() }
    }
}
