package com.torve.data.progress

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class ContinueWatchingQueryTest {

    @Test
    fun completedEpisodeKeepsSeriesAvailableWhileCompletedMovieIsRemoved() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        val db = TorveDatabase(driver)

        db.insertProgress("series", "series", 900L, 1_000L, 3L)
        db.insertProgress("movie-complete", "movie", 900L, 1_000L, 2L)
        db.insertProgress("movie-partial", "movie", 500L, 1_000L, 1L)
        db.insertProgress("invalid-duration", "series", 10L, 0L, 4L)

        val ids = db.torveQueries.getInProgress("user", 20L).executeAsList().map { it.media_id }

        assertEquals(listOf("series", "movie-partial"), ids)
    }

    private fun TorveDatabase.insertProgress(
        id: String,
        type: String,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long,
    ) {
        torveQueries.upsertProgress(
            user_id = "user",
            media_id = id,
            media_type = type,
            title = id,
            poster_url = null,
            backdrop_url = null,
            position_ms = positionMs,
            duration_ms = durationMs,
            season_number = null,
            episode_number = null,
            show_title = null,
            updated_at = updatedAt,
        )
    }
}
