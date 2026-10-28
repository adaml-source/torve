package com.torve.data.profile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import kotlinx.serialization.json.*
import kotlin.test.*

class HouseholdLedgerStoreTest {
    @Test fun upgradeSplitsAnOversizedLegacyOutboxBeforePreferenceObserversReadIt() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE TABLE preference (user_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(user_id, key))", 0)
            val key = "household_v1:default:history:removed-episode"
            val legacy = buildJsonObject {
                putJsonObject("records") { putJsonObject(key) { put("deleted", true); put("changedAt", 123L) } }
                putJsonArray("pending") { add(key) }
                put("fixtureHistory", "episode history ".repeat(180_000))
            }.toString()
            assertTrue(legacy.encodeToByteArray().size > 2 * 1024 * 1024)
            driver.execute(null, "INSERT INTO preference VALUES (?, ?, ?)", 3) {
                bindString(0, "owner"); bindString(1, "household_sync_ledger_v1"); bindString(2, legacy)
            }
            driver.execute(null, "INSERT INTO preference VALUES ('owner', 'theme_mode', 'DARK')", 0)
            driver.execute(null, "INSERT INTO preference VALUES ('other-owner', 'household_sync_ledger_v1', '{\"pending\":[\"other\"]}')", 0)

            TorveDatabase.Schema.migrate(driver, 13L, TorveDatabase.Schema.version)

            val database = TorveDatabase(driver)
            val store = HouseholdLedgerStore(database)
            assertEquals(legacy, store.read("owner"))
            assertEquals("{\"pending\":[\"other\"]}", store.read("other-owner"))
            assertNull(store.read("unknown-owner"))
            assertNull(database.torveQueries.getPreference("owner", "household_sync_ledger_v1").executeAsOneOrNull())
            assertEquals("DARK", database.torveQueries.getPreference("owner", "theme_mode").executeAsOne())
            val rows = database.torveQueries.getAllPreferences("owner").executeAsList()
            assertTrue(rows.size > 100)
            assertTrue(rows.all { it.value_.encodeToByteArray().size <= HouseholdLedgerStore.CHUNK_CHARACTERS * 4 })
            val restored = Json.parseToJsonElement(store.read("owner")!!).jsonObject
            assertEquals(key, restored.getValue("pending").jsonArray.single().jsonPrimitive.content)
            assertTrue(restored.getValue("records").jsonObject.getValue(key).jsonObject.getValue("deleted").jsonPrimitive.boolean)
        } finally { driver.close() }
    }

    @Test fun writesStayBoundedPreserveUnicodeAndReplaceAtomicallyWithoutCrossingAccounts() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TorveDatabase.Schema.create(driver)
            val database = TorveDatabase(driver)
            val store = HouseholdLedgerStore(database)
            val unicodeBoundary = "a".repeat(HouseholdLedgerStore.CHUNK_CHARACTERS - 1) + "🍿🎬" + "episode".repeat(400_000)
            store.write("owner", unicodeBoundary)
            store.write("other-owner", "other profile outbox")
            assertEquals(unicodeBoundary, store.read("owner"))
            assertTrue(database.torveQueries.getHouseholdLedgerChunks("owner").executeAsList()
                .all { it.encodeToByteArray().size <= HouseholdLedgerStore.CHUNK_CHARACTERS * 4 })
            assertFailsWith<IllegalStateException> {
                database.transaction { store.write("owner", "uncommitted"); error("interrupt write") }
            }
            assertEquals(unicodeBoundary, HouseholdLedgerStore(database).read("owner"))
            store.write("owner", "small outbox")
            assertEquals(listOf("small outbox"), database.torveQueries.getHouseholdLedgerChunks("owner").executeAsList())
            assertEquals("other profile outbox", store.read("other-owner"))
            database.torveQueries.deleteAllAccountPreferences()
            assertNull(store.read("owner"))
            assertNull(store.read("other-owner"))
        } finally { driver.close() }
    }
}
