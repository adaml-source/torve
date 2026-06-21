package com.torve.data.scoping

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two-user state-isolation tests for the user-scoped tables introduced
 * by migration v6 → v7. Each test boots a fresh in-memory SQLite,
 * applies the latest schema, and exercises queries directly on
 * [TorveDatabase] to assert that rows written under one user_id are
 * invisible to a different user_id.
 *
 * The repository layer simply delegates to these queries with
 * UserIdProvider.currentUserId(), so testing at the SQL boundary is
 * sufficient to prove the isolation contract — and avoids pulling in
 * the full repository graph (Auth, Koin, Ktor) for a unit test.
 */
class UserScopingIsolationTest {

    private fun freshDb(): TorveDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        return TorveDatabase(driver)
    }

    private fun TorveDatabase.insertAddonRow(userId: String, manifestUrl: String) {
        torveQueries.insertAddon(
            user_id = userId,
            manifest_url = manifestUrl,
            id = manifestUrl,
            name = "Test Addon",
            version = "1.0.0",
            description = "for test",
            logo = null,
            manifest_json = "{}",
            is_enabled = 1,
            priority = 0,
            installed_at = 0L,
            server_id = null,
            synced_at = null,
            installed_from = "app",
            config_id = null,
        )
    }

    private fun TorveDatabase.insertSubscriptionRow(userId: String, tier: String) {
        torveQueries.insertSubscription(
            user_id = userId,
            id = "sub_${userId}_$tier",
            tier = tier,
            purchase_token = null,
            expires_at = null,
            is_active = 1,
            platform = "test",
            purchased_at = 0L,
        )
    }

    /**
     * Test 1 (two-user isolation): rows written by user A are invisible
     * to user B's queries on every user-scoped table touched by
     * migration v6 → v7.
     */
    @Test
    fun userARowsInvisibleToUserB() {
        val db = freshDb()
        val userA = "user-a"
        val userB = "user-b"

        db.insertAddonRow(userA, "https://example.com/a/manifest.json")
        db.insertSubscriptionRow(userA, "LIFETIME")
        db.torveQueries.upsertCategoryConfig(
            user_id = userA, playlist_id = "pl-a", category_name = "News",
            is_visible = 1, sort_order = 0,
        )

        // User A sees their own data.
        assertEquals(1, db.torveQueries.getAllAddons(userId = userA).executeAsList().size)
        assertNotNull(db.torveQueries.getActiveSubscription(userId = userA).executeAsOneOrNull())
        assertEquals(1, db.torveQueries.getCategoryConfigs(userId = userA, playlistId = "pl-a").executeAsList().size)

        // User B sees nothing.
        assertEquals(0, db.torveQueries.getAllAddons(userId = userB).executeAsList().size)
        assertNull(db.torveQueries.getActiveSubscription(userId = userB).executeAsOneOrNull())
        assertEquals(0, db.torveQueries.getCategoryConfigs(userId = userB, playlistId = "pl-a").executeAsList().size)
    }

    /**
     * Test 2 (reverse isolation): writing under B and then querying as A
     * also returns nothing — symmetric to test 1, catches an index or
     * filter clause that may have been baked-in to one direction.
     */
    @Test
    fun userBRowsInvisibleToUserA() {
        val db = freshDb()
        val userA = "user-a"
        val userB = "user-b"

        db.insertAddonRow(userB, "https://example.com/b/manifest.json")
        db.insertSubscriptionRow(userB, "MONTHLY")

        assertEquals(1, db.torveQueries.getAllAddons(userId = userB).executeAsList().size)
        assertNotNull(db.torveQueries.getActiveSubscription(userId = userB).executeAsOneOrNull())

        assertEquals(0, db.torveQueries.getAllAddons(userId = userA).executeAsList().size)
        assertNull(db.torveQueries.getActiveSubscription(userId = userA).executeAsOneOrNull())
    }

    /**
     * Test 3 (sign-out behaviour): when no user is signed in we treat
     * userId as the empty string. Empty-string queries must not see
     * any of user A's rows. Exercises the same defence the
     * repositories now apply via `if (!isSignedIn()) return ...`.
     */
    @Test
    fun signedOutQueriesReturnEmpty() {
        val db = freshDb()
        val userA = "user-a"
        val signedOut = ""

        db.insertAddonRow(userA, "https://example.com/x/manifest.json")
        db.insertSubscriptionRow(userA, "LIFETIME")

        assertEquals(0, db.torveQueries.getAllAddons(userId = signedOut).executeAsList().size)
        assertNull(db.torveQueries.getActiveSubscription(userId = signedOut).executeAsOneOrNull())
    }

    /**
     * Test 4 (migration v6 → v7): an existing DB at v6 with rows that
     * predate the user_id column must drop those rows during migration
     * (per the policy that pre-migration rows are contaminated and
     * unsafe to keep) and emerge with the new user_id column intact.
     */
    @Test
    fun migrationDropsLegacyRowsAndRecreatesUserScopedTables() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Build the v6 schema by hand — only the bits that matter for
        // this test: an `addon` table without a user_id column, plus
        // a single legacy row. Then run Schema.migrate(6 → current).
        driver.execute(
            null,
            """CREATE TABLE addon (
                manifest_url TEXT NOT NULL PRIMARY KEY,
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                version TEXT NOT NULL,
                description TEXT,
                logo TEXT,
                manifest_json TEXT NOT NULL,
                is_enabled INTEGER NOT NULL DEFAULT 1,
                priority INTEGER NOT NULL DEFAULT 0,
                installed_at INTEGER NOT NULL,
                server_id TEXT,
                synced_at INTEGER,
                installed_from TEXT NOT NULL DEFAULT 'app',
                config_id TEXT
            )""",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO addon (manifest_url, id, name, version, manifest_json, installed_at) " +
                "VALUES ('legacy://leak', 'legacy://leak', 'Legacy', '1.0', '{}', 0)",
            0,
        )

        // Migrate from v6 to current schema version.
        TorveDatabase.Schema.migrate(driver, 6L, TorveDatabase.Schema.version)

        val db = TorveDatabase(driver)

        // Legacy row was dropped — every user_id read returns empty.
        assertEquals(0, db.torveQueries.getAllAddons(userId = "").executeAsList().size)
        assertEquals(0, db.torveQueries.getAllAddons(userId = "any-user").executeAsList().size)

        // user_id column now exists and writes succeed.
        db.insertAddonRow("post-migration-user", "https://example.com/post/manifest.json")
        assertEquals(
            1,
            db.torveQueries.getAllAddons(userId = "post-migration-user").executeAsList().size,
        )
    }

    /**
     * Test 5 (SQLDelight signature smoke): if any user-scoped query
     * regresses to an unscoped form, this test won't compile (the
     * named-parameter call would fail to resolve). Treat this test
     * as a tripwire that the generated interface still requires
     * userId on every leaky-table operation.
     */
    @Test
    fun userScopedQueriesAcceptUserIdParameter() {
        val db = freshDb()
        val uid = "compile-check-user"

        // Each call below is the proof — calling them with userId =
        // confirms the generated SQLDelight Kotlin signature still
        // requires the parameter. The test passes if the calls compile
        // and execute without throwing.
        db.torveQueries.getAllAddons(userId = uid).executeAsList()
        db.torveQueries.getEnabledAddons(userId = uid).executeAsList()
        db.torveQueries.getActiveSubscription(userId = uid).executeAsOneOrNull()
        db.torveQueries.getCategoryConfigs(userId = uid, playlistId = "pl").executeAsList()
        db.torveQueries.getEpgChannelsForPlaylistGeneration(
            userId = uid, playlistId = "pl", generationId = 0L,
        ).executeAsList()
        db.torveQueries.getEpgProgrammesForPlaylistWindowLimited(
            userId = uid, playlistId = "pl", generationId = 0L,
            startTime = 0L, endTime = 1L, rowLimit = 10L,
        ).executeAsList()
        db.torveQueries.getChannelsForPlaylistGeneration(
            userId = uid, playlistId = "pl", generationId = 0L,
        ).executeAsList()

        assertTrue(true)
    }
}
