package com.torve.data.channels

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelCategoryQueryTest {
    private fun freshDb(): TorveDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        return TorveDatabase(driver)
    }

    @Test
    fun unfilteredCategoryQueriesKeepLiveAndVodCountsSeparate() {
        val db = freshDb()
        db.insertChannel("live-1", "News", "LIVE", 0)
        db.insertChannel("live-2", "News", "UNKNOWN", 1)
        db.insertChannel("vod-1", "Movies", "VOD_MOVIE", 2)
        db.insertChannel("series-1", "Series", "VOD_SERIES", 3)
        db.insertChannel("legacy-vod", "VOD: Classics", "UNKNOWN", 4)

        val live = db.torveQueries
            .getLiveCategoryCountsForPlaylistUnfiltered("user", "playlist", 1)
            .executeAsList()
        val vod = db.torveQueries
            .getVodCategoryCountsForPlaylistUnfiltered("user", "playlist", 1)
            .executeAsList()

        assertEquals(listOf("News" to 2L), live.map { it.group_title to it.channel_count })
        assertEquals(
            mapOf("Movies" to 1L, "Series" to 1L, "VOD: Classics" to 1L),
            vod.associate { requireNotNull(it.group_title) to it.channel_count },
        )
    }

    @Test
    fun hiddenChannelQueryStillExcludesHiddenStableIds() {
        val db = freshDb()
        db.insertChannel("live-visible", "News", "LIVE", 0)
        db.insertChannel("live-hidden", "News", "LIVE", 1)
        db.torveQueries.insertHiddenChannel("live-hidden")

        assertEquals(1L, db.torveQueries.countHiddenChannelIds().executeAsOne())
        val filtered = db.torveQueries
            .getLiveCategoryCountsForPlaylist("user", "playlist", 1)
            .executeAsList()
        assertEquals(listOf("News" to 1L), filtered.map { it.group_title to it.channel_count })
    }

    private fun TorveDatabase.insertChannel(
        stableId: String,
        group: String,
        contentType: String,
        sortIndex: Long,
    ) {
        torveQueries.insertChannel(
            user_id = "user",
            playlist_id = "playlist",
            generation_id = 1,
            stable_id = stableId,
            sort_index = sortIndex,
            name = stableId,
            stream_url = "https://example.com/$stableId",
            tvg_id = null,
            tvg_name = null,
            logo_url = null,
            group_title = group,
            tvg_language = null,
            tvg_country = null,
            tvg_shift = null,
            channel_number = null,
            duration = -1,
            catchup_type = null,
            catchup_days = null,
            catchup_source = null,
            user_agent = null,
            vlc_options = "",
            kodi_props = "",
            content_type = contentType,
            updated_at = 1,
        )
    }
}
