package com.torve.data.progress

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import kotlin.test.*

class BoundedPreferenceReadTest {
    @Test fun existingOversizedRowsReadLosslesslyWithoutAnyOversizedCursorRow() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val database = TorveDatabase(driver); TorveDatabase.Schema.create(driver)
            val q = database.torveQueries
            // Mix supplementary Unicode with ASCII across slice boundaries.
            val value = "{\"titles\":[\"" + "A🎬é".repeat(800_000) + "\"]}"
            q.setPreference("owner", "catalog_snapshot", value)
            assertEquals(value, q.readPreferenceSafely("owner", "catalog_snapshot"))
            assertEquals(listOf("catalog_snapshot"), q.getPreferenceKeys("owner").executeAsList())
            assertNull(q.readPreferenceSafely("other", "catalog_snapshot"))
            assertNull(q.readPreferenceSafely("owner", "missing"))
            assertTrue(q.getPreferenceSlice("1", "131072", "owner", "catalog_snapshot").executeAsOne().part!!.encodeToByteArray().size < 524_288)
            assertEquals(value, q.getPreference("owner", "catalog_snapshot").executeAsOne()) // no migration/deletion
        } finally { driver.close() }
    }
    @Test fun smallEmptyAndBoundaryValuesStayExact() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val database = TorveDatabase(driver); TorveDatabase.Schema.create(driver); val q = database.torveQueries
            for (value in listOf("", "GERMAN", "x".repeat(131_072), "x".repeat(131_073))) {
                q.setPreference("owner", "setting", value)
                assertEquals(value, q.readPreferenceSafely("owner", "setting"))
            }
        } finally { driver.close() }
    }
}
