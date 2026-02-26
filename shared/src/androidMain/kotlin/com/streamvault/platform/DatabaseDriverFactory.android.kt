package com.streamvault.platform

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.streamvault.db.StreamVaultDatabase

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = StreamVaultDatabase.Schema,
            context = context,
            name = "streamvault.db",
            callback = object : AndroidSqliteDriver.Callback(StreamVaultDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // Ensure ALL tables exist for databases created before later sprints.
                    // CREATE TABLE IF NOT EXISTS is a no-op when the table already exists.
                    ensureAllTables(db)
                }
            },
        )

    private fun ensureAllTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS metadata_cache (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                json_data TEXT NOT NULL,
                cached_at INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS watch_progress (
                media_id TEXT NOT NULL PRIMARY KEY,
                media_type TEXT NOT NULL,
                title TEXT NOT NULL,
                poster_url TEXT,
                backdrop_url TEXT,
                position_ms INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                season_number INTEGER,
                episode_number INTEGER,
                show_title TEXT,
                updated_at INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS preference (
                key TEXT NOT NULL PRIMARY KEY,
                value TEXT NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS addon (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                version TEXT NOT NULL,
                description TEXT,
                transport_url TEXT NOT NULL,
                logo_url TEXT,
                types TEXT NOT NULL,
                catalogs TEXT,
                is_enabled INTEGER NOT NULL DEFAULT 1,
                sort_order INTEGER NOT NULL DEFAULT 0,
                installed_at INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS debrid_account (
                id TEXT NOT NULL PRIMARY KEY,
                provider TEXT NOT NULL,
                api_key TEXT NOT NULL,
                username TEXT,
                email TEXT,
                premium_expires INTEGER,
                is_active INTEGER NOT NULL DEFAULT 1,
                added_at INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS iptv_playlist (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                url TEXT NOT NULL,
                epg_url TEXT,
                channel_count INTEGER NOT NULL DEFAULT 0,
                last_updated INTEGER,
                type TEXT NOT NULL DEFAULT 'm3u',
                server TEXT,
                username TEXT,
                password TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS iptv_favorite (
                channel_id TEXT NOT NULL PRIMARY KEY,
                playlist_id TEXT NOT NULL,
                name TEXT NOT NULL,
                logo_url TEXT,
                group_title TEXT,
                added_at INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS iptv_recent (
                channel_id TEXT NOT NULL PRIMARY KEY,
                playlist_id TEXT NOT NULL,
                name TEXT NOT NULL,
                logo_url TEXT,
                group_title TEXT,
                stream_url TEXT NOT NULL,
                viewed_at INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS iptv_category_config (
                playlist_id TEXT NOT NULL,
                category_name TEXT NOT NULL,
                is_visible INTEGER NOT NULL DEFAULT 1,
                sort_order INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (playlist_id, category_name)
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS download_queue (
                id TEXT NOT NULL PRIMARY KEY,
                media_id TEXT NOT NULL,
                media_type TEXT NOT NULL,
                title TEXT NOT NULL,
                poster_url TEXT,
                stream_url TEXT NOT NULL,
                file_path TEXT,
                file_size_bytes INTEGER,
                downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'pending',
                season_number INTEGER,
                episode_number INTEGER,
                created_at INTEGER NOT NULL,
                completed_at INTEGER
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS subscription (
                id TEXT NOT NULL PRIMARY KEY,
                tier TEXT NOT NULL,
                purchase_token TEXT,
                expires_at INTEGER,
                is_active INTEGER NOT NULL DEFAULT 0,
                platform TEXT NOT NULL,
                purchased_at INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS user_profile (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                avatar_index INTEGER NOT NULL DEFAULT 0,
                is_active INTEGER NOT NULL DEFAULT 0,
                pin TEXT,
                max_content_rating TEXT,
                created_at INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS home_shelf_config (
                shelf_id TEXT NOT NULL PRIMARY KEY,
                is_visible INTEGER NOT NULL DEFAULT 1,
                sort_order INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS watchlist (
                media_id TEXT NOT NULL PRIMARY KEY,
                media_type TEXT NOT NULL,
                tmdb_id INTEGER NOT NULL,
                imdb_id TEXT,
                title TEXT NOT NULL,
                poster_url TEXT,
                backdrop_url TEXT,
                rating REAL,
                year INTEGER,
                genres TEXT,
                added_at INTEGER NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS watch_history (
                id TEXT NOT NULL PRIMARY KEY,
                media_id TEXT NOT NULL,
                media_type TEXT NOT NULL,
                title TEXT NOT NULL,
                poster_url TEXT,
                backdrop_url TEXT,
                watched_at INTEGER NOT NULL,
                duration_watched_ms INTEGER NOT NULL DEFAULT 0,
                season_number INTEGER,
                episode_number INTEGER,
                show_title TEXT
            )""",
        )

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS trakt_rating (
                media_key TEXT NOT NULL PRIMARY KEY,
                tmdb_id INTEGER NOT NULL,
                media_type TEXT NOT NULL,
                rating INTEGER NOT NULL,
                rated_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS trakt_sync_state (
                domain TEXT NOT NULL PRIMARY KEY,
                last_sync_at INTEGER NOT NULL,
                cursor TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS trakt_sync_queue (
                id TEXT NOT NULL PRIMARY KEY,
                action_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                next_retry_at INTEGER
            )""",
        )

        // Migration: add next_retry_at column to trakt_sync_queue (for DBs created before this column existed)
        // Skip if column already present (table created with it, or previous migration ran)
        val cursor = db.query("PRAGMA table_info(trakt_sync_queue)")
        var hasNextRetryAt = false
        while (cursor.moveToNext()) {
            if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "next_retry_at") {
                hasNextRetryAt = true
                break
            }
        }
        cursor.close()
        if (!hasNextRetryAt) {
            db.execSQL("ALTER TABLE trakt_sync_queue ADD COLUMN next_retry_at INTEGER")
        }
    }
}
