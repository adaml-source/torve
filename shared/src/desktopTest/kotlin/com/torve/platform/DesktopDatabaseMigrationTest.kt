package com.torve.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDatabaseMigrationTest {
    @Test
    fun versionNineDatabaseReceivesCatalogTopItemsTable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createLegacyGuideFixture(driver)

        TorveDatabase.Schema.migrate(driver, 9L, TorveDatabase.Schema.version)

        assertTrue(TorveDatabase.Schema.version > 9L)
        assertEquals(1L, countSchemaObject(driver, "table", "catalog_top_items"))
        assertEquals(1L, countSchemaObject(driver, "index", "catalog_top_items_lookup"))
        driver.close()
    }

    @Test
    fun versionTenDatabaseReceivesPlaybackSegmentCache() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createLegacyGuideFixture(driver)

        TorveDatabase.Schema.migrate(driver, 10L, TorveDatabase.Schema.version)

        assertTrue(TorveDatabase.Schema.version > 10L)
        assertEquals(1L, countSchemaObject(driver, "table", "playback_segment_analysis"))
        assertEquals(1L, countSchemaObject(driver, "index", "playback_segment_analysis_episode"))
        driver.close()
    }

    @Test
    fun desktopConnectionsUseWalAndBusyTimeout() {
        val database = kotlin.io.path.createTempFile("torve-sqlite-config", ".db").toFile()
        val driver = JdbcSqliteDriver(
            "jdbc:sqlite:${database.absolutePath}",
            desktopSqliteProperties(),
        )
        try {
            configureDesktopSqlite(driver)

            assertEquals("wal", queryString(driver, "PRAGMA journal_mode"))
            val busyTimeout = queryLong(driver, "PRAGMA busy_timeout")
            assertTrue(busyTimeout >= 10_000L, "busy_timeout was $busyTimeout ms")
        } finally {
            driver.close()
            database.delete()
            database.resolveSibling(database.name + "-wal").delete()
            database.resolveSibling(database.name + "-shm").delete()
        }
    }

    @Test
    fun guideMigrationPreservesProgrammesAndOnlyInvalidatesGuideLoadState() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            createLegacyGuideFixture(driver)
            driver.execute(null, "INSERT INTO iptv_epg_programme VALUES ('playlist', 'channel', 'Saved programme', 1000, 2000)", 0)
            driver.execute(null, "INSERT INTO preference VALUES ('viewer', 'epg_load_state_playlist', 'loaded')", 0)
            driver.execute(null, "INSERT INTO preference VALUES ('viewer', 'language', 'de')", 0)
            TorveDatabase.Schema.migrate(driver, 11L, TorveDatabase.Schema.version)
            assertEquals("Saved programme", queryString(driver, "SELECT title FROM iptv_epg_programme"))
            assertEquals(1L, queryLong(driver, "SELECT COUNT(*) FROM iptv_epg_programme WHERE sub_title IS NULL AND description IS NULL"))
            assertEquals(0L, queryLong(driver, "SELECT COUNT(*) FROM preference WHERE key LIKE 'epg_load_state_%'"))
            assertEquals("de", queryString(driver, "SELECT value FROM preference WHERE key = 'language'"))
        } finally { driver.close() }
    }

    /** Relevant pre-v12 tables must exist before testing a v9/v10/v11 upgrade. */
    private fun createLegacyGuideFixture(driver: JdbcSqliteDriver) {
        driver.execute(null, "CREATE TABLE iptv_epg_programme (playlist_id TEXT NOT NULL, epg_channel_id TEXT NOT NULL, title TEXT NOT NULL, start_time INTEGER NOT NULL, end_time INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE preference (user_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(user_id, key))", 0)
    }

    private fun countSchemaObject(
        driver: JdbcSqliteDriver,
        type: String,
        name: String,
    ): Long = driver.executeQuery(
        identifier = null,
        sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?",
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        parameters = 2,
        binders = {
            bindString(0, type)
            bindString(1, name)
        },
    ).value

    private fun queryString(driver: JdbcSqliteDriver, sql: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
            },
            parameters = 0,
        ).value

    private fun queryLong(driver: JdbcSqliteDriver, sql: String): Long =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
            },
            parameters = 0,
        ).value
}
