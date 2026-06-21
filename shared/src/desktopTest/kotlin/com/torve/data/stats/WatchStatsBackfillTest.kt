package com.torve.data.stats

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.stats.RuntimeConfidence
import com.torve.domain.stats.WatchSession
import com.torve.domain.stats.WatchSessionSource
import com.torve.domain.stats.WatchSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchStatsBackfillTest {
    @Test
    fun zeroDurationHistoryDoesNotProduceFakeWatchTime() = runBlockingTest {
        val db = freshDb()
        db.torveQueries.insertHistory(
            user_id = "user-a",
            id = "history-zero",
            media_id = "101",
            media_type = "movie",
            title = "Zero",
            poster_url = null,
            backdrop_url = null,
            watched_at = 10L,
            duration_watched_ms = 0L,
            season_number = null,
            episode_number = null,
            show_title = null,
        )

        backfill(db, "user-a").runIfNeeded()

        assertEquals(0, db.torveQueries.selectWatchSessionsForUser("user-a").executeAsList().size)
    }

    @Test
    fun durationHistoryMigratesCountedTimeAndDisplayIdentity() = runBlockingTest {
        val db = freshDb()
        db.torveQueries.insertHistory(
            user_id = "user-a",
            id = "history-duration",
            media_id = "202",
            media_type = "movie",
            title = "Measured Legacy",
            poster_url = "poster.jpg",
            backdrop_url = "backdrop.jpg",
            watched_at = 20L,
            duration_watched_ms = 900_000L,
            season_number = null,
            episode_number = null,
            show_title = null,
        )

        backfill(db, "user-a").runIfNeeded()

        val session = db.torveQueries.selectWatchSessionsForUser("user-a").executeAsOne().toDomain()
        assertEquals(900_000L, session.countedWatchMs)
        assertEquals(RuntimeConfidence.ESTIMATED, session.runtimeConfidence)
        assertEquals("poster.jpg", session.posterUrl)
        assertEquals("backdrop.jpg", session.backdropUrl)
        assertEquals(202, session.tmdbId)
    }

    @Test
    fun progressMigratesPartialCompletedAndLegacyPlaceholderSafely() = runBlockingTest {
        val db = freshDb()
        insertProgress(db, userId = "user-a", mediaId = "partial", position = 600_000L, duration = 7_200_000L)
        insertProgress(db, userId = "user-a", mediaId = "complete", position = 6_120_000L, duration = 7_200_000L)
        insertProgress(db, userId = "user-a", mediaId = "placeholder", position = 900_000L, duration = 1_000_000L)

        backfill(db, "user-a").runIfNeeded()

        val sessions = db.torveQueries.selectWatchSessionsForUser("user-a")
            .executeAsList()
            .map { it.toDomain() }
            .associateBy { it.mediaId }

        assertEquals(WatchSessionStatus.PARTIAL, sessions.getValue("partial").status)
        assertEquals(600_000L, sessions.getValue("partial").countedWatchMs)
        assertEquals(RuntimeConfidence.ESTIMATED, sessions.getValue("partial").runtimeConfidence)

        assertEquals(WatchSessionStatus.COMPLETED, sessions.getValue("complete").status)
        assertEquals(7_200_000L, sessions.getValue("complete").countedWatchMs)
        assertEquals(RuntimeConfidence.ESTIMATED, sessions.getValue("complete").runtimeConfidence)

        assertEquals(WatchSessionStatus.MANUAL_COMPLETED, sessions.getValue("placeholder").status)
        assertEquals(0L, sessions.getValue("placeholder").countedWatchMs)
        assertEquals(RuntimeConfidence.UNKNOWN, sessions.getValue("placeholder").runtimeConfidence)
    }

    @Test
    fun episodeMigrationKeepsEpisodeIdentity() = runBlockingTest {
        val db = freshDb()
        insertHistory(
            db = db,
            userId = "user-a",
            id = "history-s1e1",
            mediaId = "show-1",
            title = "Episode One",
            durationWatchedMs = 2_700_000L,
            season = 1L,
            episode = 1L,
        )
        insertHistory(
            db = db,
            userId = "user-a",
            id = "history-s1e2",
            mediaId = "show-1",
            title = "Episode Two",
            durationWatchedMs = 2_700_000L,
            season = 1L,
            episode = 2L,
        )

        backfill(db, "user-a").runIfNeeded()

        val sessions = db.torveQueries.selectWatchSessionsForUser("user-a").executeAsList().map { it.toDomain() }
        assertEquals(2, sessions.size)
        assertEquals(setOf(1, 2), sessions.mapNotNull { it.episodeNumber }.toSet())
        assertEquals(setOf("show-1"), sessions.mapNotNull { it.showId }.toSet())
    }

    @Test
    fun backfillIsIdempotentAndUserScoped() = runBlockingTest {
        val db = freshDb()
        insertProgress(db, userId = "user-a", mediaId = "a", position = 600_000L, duration = 7_200_000L)
        insertProgress(db, userId = "user-b", mediaId = "b", position = 600_000L, duration = 7_200_000L)
        val prefs = FakePreferencesRepository()
        val userABackfill = WatchStatsBackfill(db, prefs) { "user-a" }

        userABackfill.runIfNeeded()
        userABackfill.runIfNeeded()

        assertEquals(1, db.torveQueries.selectWatchSessionsForUser("user-a").executeAsList().size)
        assertEquals(0, db.torveQueries.selectWatchSessionsForUser("user-b").executeAsList().size)
    }

    @Test
    fun migrationCreatesWatchSessionTableOnDesktopPath() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.migrate(driver, 8L, TorveDatabase.Schema.version)

        assertTrue(tableExists(driver, "watch_session"))
        assertTrue(indexExists(driver, "idx_watch_session_episode_identity"))
    }

    @Test
    fun watchSessionsAreUserScopedAndClearable() {
        val db = freshDb()
        db.torveQueries.insertOrReplaceWatchSession(testSession("a-session", "user-a"))
        db.torveQueries.insertOrReplaceWatchSession(testSession("b-session", "user-b"))

        assertEquals(1, db.torveQueries.selectWatchSessionsForUser("user-a").executeAsList().size)
        assertEquals(1, db.torveQueries.selectWatchSessionsForUser("user-b").executeAsList().size)

        db.torveQueries.clearWatchSessionsForUser("user-a")

        assertEquals(0, db.torveQueries.selectWatchSessionsForUser("user-a").executeAsList().size)
        assertEquals(1, db.torveQueries.selectWatchSessionsForUser("user-b").executeAsList().size)

        db.torveQueries.clearAllWatchSessionsForAllUsers()

        assertEquals(0, db.torveQueries.selectWatchSessionsForUser("user-b").executeAsList().size)
    }

    private fun freshDb(): TorveDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        return TorveDatabase(driver)
    }

    private fun backfill(db: TorveDatabase, userId: String): WatchStatsBackfill =
        WatchStatsBackfill(db, FakePreferencesRepository()) { userId }

    private fun insertProgress(
        db: TorveDatabase,
        userId: String,
        mediaId: String,
        mediaType: String = "movie",
        title: String = mediaId,
        position: Long,
        duration: Long,
        season: Long? = null,
        episode: Long? = null,
        showTitle: String? = null,
    ) {
        db.torveQueries.upsertProgress(
            user_id = userId,
            media_id = mediaId,
            media_type = mediaType,
            title = title,
            poster_url = "poster-$mediaId",
            backdrop_url = "backdrop-$mediaId",
            position_ms = position,
            duration_ms = duration,
            season_number = season,
            episode_number = episode,
            show_title = showTitle,
            updated_at = 100L + position,
        )
    }

    private fun insertHistory(
        db: TorveDatabase,
        userId: String,
        id: String,
        mediaId: String,
        title: String,
        durationWatchedMs: Long,
        season: Long?,
        episode: Long?,
    ) {
        db.torveQueries.insertHistory(
            user_id = userId,
            id = id,
            media_id = mediaId,
            media_type = "series",
            title = title,
            poster_url = "poster-$id",
            backdrop_url = "backdrop-$id",
            watched_at = 100L + (episode ?: 0L),
            duration_watched_ms = durationWatchedMs,
            season_number = season,
            episode_number = episode,
            show_title = "The Show",
        )
    }

    private fun tableExists(driver: SqlDriver, table: String): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            mapper = { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.next().value) },
            parameters = 1,
        ) {
            bindString(0, table)
        }.value

    private fun indexExists(driver: SqlDriver, index: String): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            mapper = { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.next().value) },
            parameters = 1,
        ) {
            bindString(0, index)
        }.value

    private fun testSession(id: String, userId: String): WatchSession =
        WatchSession(
            id = id,
            userId = userId,
            mediaId = id,
            mediaType = MediaType.MOVIE,
            title = id,
            startedAt = 1L,
            source = WatchSessionSource.TORVE_PLAYER,
            status = WatchSessionStatus.COMPLETED,
            countedWatchMs = 1_000L,
            runtimeConfidence = RuntimeConfidence.MEASURED,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private class FakePreferencesRepository : PreferencesRepository {
        private val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun setString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }
}

private fun runBlockingTest(block: suspend () -> Unit) = kotlinx.coroutines.test.runTest { block() }
