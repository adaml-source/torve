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

        TorveDatabase.Schema.migrate(driver, 9L, TorveDatabase.Schema.version)

        assertTrue(TorveDatabase.Schema.version > 9L)
        assertEquals(1L, countSchemaObject(driver, "table", "catalog_top_items"))
        assertEquals(1L, countSchemaObject(driver, "index", "catalog_top_items_lookup"))
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
