package com.torve.data.channels

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.db.TorveDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PlaylistDuplicateRepairTest {

    private fun freshDb(): TorveDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        return TorveDatabase(driver)
    }

    @Test
    fun m3uIdentityNormalizesWhitespaceSchemeAndHostWithoutChangingTokenCase() {
        assertEquals(
            m3uPlaylistIdentity(" HTTP://Example.COM/path/list.m3u?token=AbC123 "),
            m3uPlaylistIdentity("http://example.com/path/list.m3u?token=AbC123"),
        )
        assertNotEquals(
            m3uPlaylistIdentity("http://example.com/path/list.m3u?token=AbC123"),
            m3uPlaylistIdentity("http://example.com/path/list.m3u?token=abc123"),
        )
    }

    @Test
    fun xtreamIdentityUsesServerAndUsernameButNotPassword() {
        assertEquals(
            xtreamPlaylistIdentity("HTTPS://Panel.EXAMPLE.com/", " userA "),
            xtreamPlaylistIdentity("https://panel.example.com", "userA"),
        )
        assertNotEquals(
            xtreamPlaylistIdentity("https://panel.example.com", "userA"),
            xtreamPlaylistIdentity("https://panel.example.com", "userB"),
        )
    }

    @Test
    fun existingDuplicateM3uPlaylistsAreMergedIntoOneStoredRow() = runTest {
        val db = freshDb()
        val user = "user-a"
        db.insertPlaylist(user, "p1", "Main", "HTTP://Example.com/list.m3u?token=AbC", channelCount = 3)
        db.insertPlaylist(user, "p2", "Main copy", " http://example.com/list.m3u?token=AbC ", channelCount = 1)

        val result = db.repair(user)

        assertEquals(1, result.mergedGroups)
        assertEquals(setOf("p2"), result.removedPlaylistIds)
        assertEquals(listOf("p1"), db.torveQueries.getAllPlaylists(userId = user).executeAsList().map { it.id })
    }

    @Test
    fun existingDuplicateXtreamPlaylistsAreMergedByServerAndUsername() = runTest {
        val db = freshDb()
        val user = "user-a"
        db.insertXtreamPlaylist(user, "x1", "Panel", "HTTPS://panel.example.com/", "same-user", 12)
        db.insertXtreamPlaylist(user, "x2", "Panel copy", "https://panel.example.com", "same-user", 2)

        val result = db.repair(user)

        assertEquals(1, result.mergedGroups)
        assertEquals(setOf("x2"), result.removedPlaylistIds)
        assertEquals(listOf("x1"), db.torveQueries.getAllPlaylists(userId = user).executeAsList().map { it.id })
    }

    @Test
    fun xtreamSameServerDifferentUsersRemainSeparate() = runTest {
        val db = freshDb()
        val user = "user-a"
        db.insertXtreamPlaylist(user, "x1", "Panel A", "https://panel.example.com", "user-a", 1)
        db.insertXtreamPlaylist(user, "x2", "Panel B", "https://panel.example.com", "user-b", 1)

        val result = db.repair(user)

        assertEquals(0, result.mergedGroups)
        assertEquals(listOf("x1", "x2"), db.torveQueries.getAllPlaylists(userId = user).executeAsList().map { it.id }.sorted())
    }

    @Test
    fun cleanupIsScopedToCurrentUserOnly() = runTest {
        val db = freshDb()
        db.insertPlaylist("user-a", "a1", "Main", "https://example.com/list.m3u", channelCount = 1)
        db.insertPlaylist("user-a", "a2", "Main copy", "https://example.com/list.m3u", channelCount = 1)
        db.insertPlaylist("user-b", "b1", "Main", "https://example.com/list.m3u", channelCount = 1)

        db.repair("user-a")

        assertEquals(1, db.torveQueries.getAllPlaylists(userId = "user-a").executeAsList().size)
        assertEquals(listOf("b1"), db.torveQueries.getAllPlaylists(userId = "user-b").executeAsList().map { it.id })
    }

    @Test
    fun mergePreservesSelectedPlaylistAndRepointsLinkedState() = runTest {
        val db = freshDb()
        val user = "user-a"
        db.insertPlaylist(user, "p1", "Full catalog", "https://example.com/list.m3u", channelCount = 1)
        db.insertPlaylist(user, "p2", "Selected duplicate", "https://example.com/list.m3u", channelCount = 0)
        db.torveQueries.setPreference(user_id = user, key = "channels_selected_playlist", value_ = "p2")
        db.torveQueries.setPreference(user_id = user, key = "iptv_channel_active_generation_p1", value_ = "100")
        db.torveQueries.setPreference(user_id = user, key = "epg_active_generation_p1", value_ = "200")
        db.insertChannel(user, "p1", 100, "p1::news")
        db.torveQueries.insertFavorite(
            user_id = user,
            channel_id = "p1::news",
            playlist_id = "p1",
            name = "News",
            logo_url = null,
            group_title = "News",
            added_at = 10,
        )
        db.torveQueries.upsertCategoryConfig(
            user_id = user,
            playlist_id = "p1",
            category_name = "News",
            is_visible = 0,
            sort_order = 7,
        )
        db.insertEpg(user, "p1", 200, "p1::news")

        val result = db.repair(user)

        assertEquals(1, result.mergedGroups)
        assertEquals(listOf("p2"), db.torveQueries.getAllPlaylists(userId = user).executeAsList().map { it.id })
        assertEquals("p2", db.torveQueries.getPreference(userId = user, key = "channels_selected_playlist").executeAsOne())

        val activeGeneration = db.torveQueries
            .getPreference(userId = user, key = "iptv_channel_active_generation_p2")
            .executeAsOne()
            .toLong()
        val channels = db.torveQueries
            .getChannelsForPlaylistGeneration(userId = user, playlistId = "p2", generationId = activeGeneration)
            .executeAsList()
        assertEquals(listOf("p2::news"), channels.map { it.stable_id })

        val favorites = db.torveQueries.getAllFavorites(userId = user).executeAsList()
        assertEquals(listOf("p2::news"), favorites.map { it.channel_id })
        assertEquals(listOf("p2"), favorites.map { it.playlist_id })

        val categories = db.torveQueries.getCategoryConfigs(userId = user, playlistId = "p2").executeAsList()
        assertEquals(listOf("News"), categories.map { it.category_name })
        assertEquals(0, categories.first().is_visible)

        val epgGeneration = db.torveQueries
            .getPreference(userId = user, key = "epg_active_generation_p2")
            .executeAsOne()
            .toLong()
        val epgRows = db.torveQueries
            .getAllEpgProgrammeRowsForPlaylistGeneration(userId = user, playlistId = "p2", generationId = epgGeneration)
            .executeAsList()
        assertEquals(listOf("p2::news"), epgRows.map { it.epg_channel_key })

        assertNull(db.torveQueries.getPlaylist(userId = user, playlistId = "p1").executeAsOneOrNull())
        assertEquals(0, db.torveQueries.getAllFavorites(userId = user).executeAsList().count { it.playlist_id == "p1" })
    }

    private suspend fun TorveDatabase.repair(userId: String): DuplicatePlaylistRepairResult {
        val passwords = mutableMapOf<String, String>()
        return repairDuplicatePlaylistsForUser(
            database = this,
            userId = userId,
            loadXtreamPassword = { passwords[it] },
            saveXtreamPassword = { playlistId, password -> passwords[playlistId] = password },
            removeXtreamPassword = { passwords.remove(it) },
        )
    }

    private fun TorveDatabase.insertPlaylist(
        userId: String,
        id: String,
        name: String,
        url: String,
        channelCount: Long,
    ) {
        torveQueries.insertPlaylist(
            user_id = userId,
            id = id,
            name = name,
            url = url,
            epg_url = null,
            channel_count = channelCount,
            last_updated = id.filter(Char::isDigit).toLongOrNull() ?: channelCount,
            type = "m3u",
            server = null,
            username = null,
            password = null,
        )
    }

    private fun TorveDatabase.insertXtreamPlaylist(
        userId: String,
        id: String,
        name: String,
        server: String,
        username: String,
        channelCount: Long,
    ) {
        torveQueries.insertPlaylist(
            user_id = userId,
            id = id,
            name = name,
            url = "$server/player_api.php",
            epg_url = null,
            channel_count = channelCount,
            last_updated = channelCount,
            type = "xtream",
            server = server,
            username = username,
            password = null,
        )
    }

    private fun TorveDatabase.insertChannel(
        userId: String,
        playlistId: String,
        generationId: Long,
        stableId: String,
    ) {
        torveQueries.insertChannel(
            user_id = userId,
            playlist_id = playlistId,
            generation_id = generationId,
            stable_id = stableId,
            sort_index = 0,
            name = "News",
            stream_url = "https://example.com/news.m3u8",
            tvg_id = "news",
            tvg_name = "News",
            logo_url = null,
            group_title = "News",
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
            content_type = "LIVE",
            updated_at = 100,
        )
    }

    private fun TorveDatabase.insertEpg(
        userId: String,
        playlistId: String,
        generationId: Long,
        channelKey: String,
    ) {
        torveQueries.insertEpgChannel(
            user_id = userId,
            playlist_id = playlistId,
            generation_id = generationId,
            channel_id = channelKey,
            epg_channel_key = channelKey,
            xmltv_channel_id = "news",
            display_name = "News",
            icon_url = null,
            updated_at = 100,
        )
        torveQueries.insertEpgProgramme(
            user_id = userId,
            playlist_id = playlistId,
            generation_id = generationId,
            channel_id = channelKey,
            epg_channel_key = channelKey,
            xmltv_channel_id = "news",
            start_time = 1000,
            end_time = 2000,
            title = "News at Ten",
        )
    }
}
