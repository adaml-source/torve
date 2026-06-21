package com.torve.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import java.io.File

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        val dbFile = resolveDesktopDatabaseFile()
        val isFreshFile = !dbFile.exists()
        dbFile.parentFile?.mkdirs()

        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        val targetVersion = TorveDatabase.Schema.version

        if (isFreshFile) {
            // No DB on disk yet — let SQLDelight create the latest schema and
            // record the target version.
            TorveDatabase.Schema.create(driver)
            setUserVersion(driver, targetVersion)
        } else {
            val storedVersion = readUserVersion(driver)
            val hasTables = hasAnyUserTables(driver)
            val effectiveOldVersion: Long = when {
                // Truly empty file — Schema.create from scratch.
                !hasTables -> {
                    TorveDatabase.Schema.create(driver)
                    targetVersion
                }
                storedVersion >= targetVersion -> targetVersion
                storedVersion in 1L until targetVersion -> storedVersion
                // user_version = 0 + tables exist = pre-tracking DB. The old
                // desktop driver applied a hand-rolled subset of operations
                // (specifically the addon ALTERs from 2.sqm and the iptv /
                // stream_resolve table creates) without bumping user_version.
                // Probe column presence to figure out which migrations have
                // effectively run, and start `Schema.migrate` from there.
                else -> probeAppliedSchemaVersion(driver)
            }
            if (effectiveOldVersion in 1L until targetVersion) {
                TorveDatabase.Schema.migrate(driver, effectiveOldVersion, targetVersion)
            }
            setUserVersion(driver, targetVersion)
        }
        ensureDesktopIncrementalMigrations(driver)
        return driver
    }
}

/**
 * For pre-version-tracking desktop DBs, infer the post-migration version on
 * disk by probing for marker columns each migration adds. SQLDelight's
 * `migrate(driver, oldVersion, newVersion)` applies migration `N.sqm` when
 * `oldVersion <= N && newVersion > N` — i.e. `N.sqm` is the N→N+1 step.
 * So the returned value is the version AFTER the highest migration that
 * has effectively run, which `Schema.migrate` then uses as the starting
 * point for any remaining migrations.
 */
private fun probeAppliedSchemaVersion(driver: SqlDriver): Long {
    // 5.sqm added addon.config_id → DB is at version 6 once that migration ran.
    if (columnExists(driver, "addon", "config_id")) return 6
    // 4.sqm added user_id to preference (and many other tables).
    if (columnExists(driver, "preference", "user_id")) return 5
    // 3.sqm added user_id to watch_progress + watchlist.
    if (columnExists(driver, "watch_progress", "user_id")) return 4
    // 2.sqm added addon.server_id (also applied by the old hand-rolled migration).
    if (columnExists(driver, "addon", "server_id")) return 3
    // No migration markers found — treat as the v2 baseline (pre-any-migration).
    return 2
}

private fun columnExists(driver: SqlDriver, table: String, column: String): Boolean {
    return driver.executeQuery(
        identifier = null,
        sql = "PRAGMA table_info($table)",
        mapper = { cursor ->
            var found = false
            while (cursor.next().value) {
                if (cursor.getString(1) == column) { found = true; break }
            }
            app.cash.sqldelight.db.QueryResult.Value(found)
        },
        parameters = 0,
    ).value
}

private fun readUserVersion(driver: SqlDriver): Long {
    return driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(
                if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L,
            )
        },
        parameters = 0,
    ).value
}

private fun setUserVersion(driver: SqlDriver, version: Long) {
    driver.execute(null, "PRAGMA user_version = $version", 0)
}

private fun hasAnyUserTables(driver: SqlDriver): Boolean {
    return driver.executeQuery(
        identifier = null,
        sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(
                if (cursor.next().value) (cursor.getLong(0) ?: 0L) > 0L else false,
            )
        },
        parameters = 0,
    ).value
}

private fun ensureDesktopIncrementalMigrations(driver: SqlDriver) {
    driver.execute(
        null,
        """CREATE TABLE IF NOT EXISTS iptv_hidden_channel (
            hidden_id TEXT NOT NULL PRIMARY KEY
        )""",
        0,
    )
    driver.execute(
        null,
        """CREATE TABLE IF NOT EXISTS stream_resolve_memory (
            content_key TEXT NOT NULL,
            stream_key TEXT NOT NULL,
            media_type TEXT NOT NULL,
            imdb_id TEXT NOT NULL,
            season_number INTEGER,
            episode_number INTEGER,
            addon_name TEXT NOT NULL,
            stream_title TEXT NOT NULL,
            info_hash TEXT,
            direct_url TEXT,
            quality TEXT NOT NULL,
            source_name TEXT,
            is_cached INTEGER NOT NULL DEFAULT 0,
            resolved_provider TEXT,
            success_count INTEGER NOT NULL DEFAULT 0,
            last_success_at INTEGER NOT NULL,
            PRIMARY KEY (content_key, stream_key)
        )""",
        0,
    )
    driver.execute(
        null,
        """CREATE INDEX IF NOT EXISTS idx_stream_resolve_memory_content_recent
            ON stream_resolve_memory(content_key, last_success_at DESC)""",
        0,
    )
    // The addon ALTER ADD COLUMN lines that lived here (server_id, synced_at,
    // installed_from) are now applied by SQLDelight migration 2.sqm. Their
    // presence here would conflict with the proper migration on DBs that
    // were partially patched by the old hand-rolled path.
}

private fun resolveDesktopDatabaseFile(): File {
    val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
    val baseDir = if (localAppData != null) {
        File(localAppData, "Torve")
    } else {
        File(System.getProperty("user.home"), ".torve")
    }
    return File(baseDir, "torve.db")
}
