package com.torve.data.channels

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import com.torve.data.auth.AuthClient
import com.torve.data.auth.UserIdProvider
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class ChannelCatalogReadDuringWriteTest {
    @Test fun live_read_preserves_staged_live_rows_until_writer_commits() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        val db = TorveDatabase(driver)
        val http = HttpClient(MockEngine { respond("{}") })
        val storage = object : SecureStorage {
            override suspend fun getString(key: String): String? = null
            override suspend fun putString(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val local = object : DeviceLocalSettingsRepository {
            override suspend fun getString(key: String): String? = null
            override suspend fun setString(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val auth = AuthClient(local, storage, http, { "https://example.test" }, { error("Unused") })
        val ids = UserIdProvider { auth }
        val owner = ids.currentDataUserId()
        val repo = ChannelRepositoryImpl(db, http, M3uParser(), EpgParser(), XtreamClient(http, Json { ignoreUnknownKeys = true }), storage, ids, driver)
        fun insert(generation: Long) {
            driver.execute(null, """INSERT INTO iptv_channel
                (user_id,playlist_id,generation_id,stable_id,sort_index,name,stream_url,group_title,content_type,updated_at)
                VALUES (?, 'p', ?, 'news', 0, 'News', 'https://example.test/live', 'DE| News', 'LIVE', 1)""", 2) {
                bindString(0, owner); bindLong(1, generation)
            }
        }
        fun writer(method: String) { repo.javaClass.getDeclaredMethod(method, String::class.java).apply { isAccessible = true }.invoke(repo, "$owner::p") }
        try {
            insert(100); insert(200)
            db.torveQueries.setPreference(owner, "channel_active_generation_p", "100")
            writer("beginCatalogWrite")
            db.torveQueries.setPreference(owner, "channel_staged_generation_p", "200")
            assertEquals(listOf("DE| News" to 1L), repo.getLiveCategoryCounts("p"))
            assertEquals(1L, db.torveQueries.getTotalChannelCountForPlaylist(owner, "p", 200).executeAsOne(), "Reading during VOD fetch must not delete staged live rows")
            db.torveQueries.setPreference(owner, "channel_active_generation_p", "200")
            db.torveQueries.deletePreference(owner, "channel_staged_generation_p")
            writer("endCatalogWrite")
            assertEquals(listOf("DE| News" to 1L), repo.getLiveCategoryCounts("p"))
            // A genuinely abandoned import is still cleaned up.
            insert(300)
            db.torveQueries.setPreference(owner, "channel_staged_generation_p", "300")
            repo.getLiveCategoryCounts("p")
            assertEquals(0L, db.torveQueries.getTotalChannelCountForPlaylist(owner, "p", 300).executeAsOne())
            assertEquals(1L, db.torveQueries.getTotalChannelCountForPlaylist(owner, "p", 200).executeAsOne())
        } finally { http.close(); driver.close() }
    }
}
